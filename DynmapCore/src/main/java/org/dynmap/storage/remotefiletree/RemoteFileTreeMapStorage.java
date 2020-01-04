package org.dynmap.storage.remotefiletree;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;

import org.dynmap.DynmapCore;
import org.dynmap.DynmapWorld;
import org.dynmap.Log;
import org.dynmap.MapType;
import org.dynmap.PlayerFaces;
import org.dynmap.MapType.ImageEncoding;
import org.dynmap.MapType.ImageVariant;
import org.dynmap.storage.MapStorage;
import org.dynmap.storage.MapStorageTile;
import org.dynmap.utils.BufferInputStream;
import org.dynmap.utils.BufferOutputStream;

public class RemoteFileTreeMapStorage extends MapStorage {
	private String urlBase;
	private String accessKey;
	private final String multipartBoundary;
	private final String remoteFileTreeBaseUrl = "standalone/filetree/";
	private final String tilesEndpoint = remoteFileTreeBaseUrl + "tiles.php";

	public class StorageTile extends MapStorageTile {
		/**
		 * The tile path from the world to the name of the file. No leading HTTP path or
		 * extension included.
		 * 
		 * Ex: world/map/zoom/x.y
		 */
		private String tilePath;
		private String fullTileUrlNoExt;

		protected StorageTile(DynmapWorld world, MapType map, int x, int y, int zoom, ImageVariant var) {
			super(world, map, x, y, zoom, var);

			tilePath = world.getName() + "/" + map.getPrefix() + var.variantSuffix + "/" + zoom + "/" + x + "." + y;
			fullTileUrlNoExt = urlBase + "/" + remoteFileTreeBaseUrl + "/tiles/" + tilePath;
		}

		/**
		 * Gets the fully qualified path to a tile using the specified format
		 */
		private String createFullTileUrl(ImageEncoding format) {
			return fullTileUrlNoExt + "." + format.getFileExt();
		}

		@Override
		public TileRead read() {
			HttpURLConnection conn = fetchTileConnection();

			if (conn == null) {
				return null;
			}

			TileRead read = new TileRead();

			// read image data
			try {
				InputStream bodyStream = conn.getInputStream();
				int bodyLength = conn.getHeaderFieldInt("Content-Length", -1);

				// make sure body length was parsed correctly
				if (bodyLength == -1) {
					throw new Exception("Server sent invalid body length");
				}

				// read image data
				byte[] buffer = new byte[bodyLength];
				while (bodyStream.read(buffer) >= 0) {
				} // read body into buffer
				read.image = new BufferInputStream(buffer);

				// read content-type header for image format
				String contentType = conn.getHeaderField("Content-Type");
				switch (contentType) {
				case "image/png":
					read.format = ImageEncoding.PNG;
					break;
				case "image/jpeg":
				case "image/jpg": // not technically a valid MIME but we all make mistakes
					read.format = ImageEncoding.JPG;
					break;
				default:
					throw new Exception("Invalid MIME type: " + contentType);
				}

				// get tile last modified date
				read.lastModified = conn.getHeaderFieldDate("Last-Modified", -1);
				if (read.lastModified == -1) {
					throw new Exception("Server sent invalid or missing Last-Modified header");
				}

				// fetch file hash
				Long hashCode = fetchRemoteHashCode(conn.getURL().toString());
				if (hashCode == null) {
					throw new Exception("Failed to fetch hash code");
				}

				read.hashCode = hashCode;
			} catch (Exception ex) {
				Log.severe("Remote tile read error: " + ex);
				return null;
			}

			return read;
		}

		@Override
		public boolean write(long hash, BufferOutputStream encImage) {
			try {
				// first, delete the alternate format tile (if it exists)
				HttpURLConnection deleteAltTileConn = createHttpRequest(tilesEndpoint, "POST");
				deleteAltTileConn.getOutputStream().write(createDeleteTilePayload(getOppositeEncoding()));

				// make sure alternate tile was deleted correctly. even if the tile didn't
				// exist, server should return 200
				if (deleteAltTileConn.getResponseCode() != 200) {
					throw new Exception("Failed to delete opposite format tile: "
							+ readInputStreamToString(deleteAltTileConn.getInputStream()));
				}

				// prepare connection for new tile
				HttpURLConnection writeTileConn = createHttpRequest(tilesEndpoint, "POST");

				// if encImage is null, we are deleting the current tile
				if (encImage == null) {
					writeTileConn.getOutputStream().write(createDeleteTilePayload(getMatchingEncoding()));

					// make sure file deletion was successful
					if (writeTileConn.getResponseCode() == 200) {
						return true;
					} else {
						throw new Exception("Failed to delete tile: "
								+ readInputStreamToString(deleteAltTileConn.getInputStream()));
					}
				}

				// write new tile
				writeTileConn.setChunkedStreamingMode(0); // used to indicate we don't know what the size of the body
															// will be
				OutputStream out = writeTileConn.getOutputStream();

				// create map of params to send in multipart form
				Map<String, String> formData = new HashMap<String, String>();
				formData.put("key", accessKey);
				formData.put("world", world.getName());
				formData.put("map_prefix", map.getPrefix() + var.variantSuffix);
				formData.put("zoom", Integer.toString(zoom));
				formData.put("x", Integer.toString(x));
				formData.put("y", Integer.toString(y));
				formData.put("file_type", getMatchingEncoding().getFileExt());
				formData.put("hash", Long.toString(hash));

				// write the contents of formData to multipart form
				writeMultipartFormData(out, formData);

				// write file data to multipart form
				writeMultipartBoundary(out, false);
				writeMultipartFile(out, "tile", "tile", encImage.buf);
				writeMultipartBoundary(out, true); // multipart end boundary

				if (writeTileConn.getResponseCode() == 200) {
					return true;
				} else {
					throw new Exception("Error sending request to server: "
							+ readInputStreamToString(writeTileConn.getInputStream()));
				}

			} catch (Exception ex) {
				Log.severe("Failed to write tile (" + this.toString() + "): " + ex.getMessage());
				return false;
			}
		}

		@Override
		public boolean exists() {
			return fetchTileConnection() != null; // getTileConnection() returns null if the tile doesn't exist remotely
		}

		@Override
		public boolean getWriteLock() {
			return RemoteFileTreeMapStorage.this.getWriteLock(tilePath);
		}

		@Override
		public void releaseWriteLock() {
			RemoteFileTreeMapStorage.this.releaseWriteLock(tilePath);
		}

		@Override
		public boolean getReadLock(long timeout) {
			return RemoteFileTreeMapStorage.this.getReadLock(tilePath, timeout);
		}

		@Override
		public void releaseReadLock() {
			RemoteFileTreeMapStorage.this.releaseReadLock(tilePath);
		}

		@Override
		public String getURI() {
			return tilePath;
		}

		@Override
		public boolean equals(Object o) {
			if (o instanceof StorageTile) {
				StorageTile st = (StorageTile) o;
				return tilePath.equals(st.tilePath);
			}
			return false;
		}

		@Override
		public void cleanup() {
		}

		private Long fetchRemoteHashCode(String tileUrl) {
			// check if we need to find the correct tile url
			if (tileUrl == null) {
				tileUrl = fetchTileConnectionUrl();

				// if tile url is still null the tile doesn't exist
				if (tileUrl == null)
					return null;
			}

			try {
				HttpURLConnection hashConn = createHttpRequest(tileUrl + ".hash", "GET");

				// make sure hash exists
				if (hashConn.getResponseCode() != 200) {
					throw new Exception("Hash not found");
				}

				// get length of hash body
				int hashBodyLength = hashConn.getHeaderFieldInt("Content-Length", -1);
				if (hashBodyLength == -1) {
					throw new Exception("Server sent invalid hash body length");
				}

				// hash body to long
				String hashString = readInputStreamToString(hashConn.getInputStream());
				return Long.parseLong(hashString);
			} catch (Exception ex) {
				Log.severe(
						"Failed to fetch hash code for tile " + StorageTile.this.toString() + ": " + ex.getMessage());
				return null;
			}
		}

		@Override
		public boolean matchesHashCode(long hash) {
			Long thisHash = fetchRemoteHashCode(null);
			return thisHash.equals(hash);
		}

		/**
		 * Gets the ImageEncoding used by the map
		 */
		private ImageEncoding getMatchingEncoding() {
			return map.getImageFormat().getEncoding();
		}

		/**
		 * Gets the ImageEncoding not used by the map
		 */
		private ImageEncoding getOppositeEncoding() {
			return getMatchingEncoding() == ImageEncoding.JPG ? ImageEncoding.PNG : ImageEncoding.JPG;
		}

		/**
		 * Opens an HTTP connection to the tile file
		 * 
		 * @return The HttpURLConnection, or null if the tile doesn't have a valid URL
		 */
		private HttpURLConnection fetchTileConnection() {
			// try default encoding
			String tileUrl = createFullTileUrl(getMatchingEncoding());
			HttpURLConnection response = tryGetResponse(tileUrl);
			if (response != null) {
				return response;
			}

			// try other encoding
			tileUrl = createFullTileUrl(getOppositeEncoding());
			response = tryGetResponse(tileUrl);
			return response;
		}

		private String fetchTileConnectionUrl() {
			HttpURLConnection conn = fetchTileConnection();
			if (conn == null)
				return null;
			return conn.getURL().toString();
		}

		/**
		 * Tries to open an HTTP request, returns null if the response is not a 200 OK
		 */
		private HttpURLConnection tryGetResponse(String url) {
			try {
				HttpURLConnection conn = createHttpRequest(url, "GET");

				if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
					return conn;
				} else {
					return null;
				}
			} catch (IOException ex) {
				Log.severe("HTTP error: " + ex.getMessage());
				return null;
			}
		}

		@Override
		public MapStorageTile getZoomOutTile() {
			int xx, yy;
			int step = 1 << zoom;
			if (x >= 0)
				xx = x - (x % (2 * step));
			else
				xx = x + (x % (2 * step));
			yy = -y;
			if (yy >= 0)
				yy = yy - (yy % (2 * step));
			else
				yy = yy + (yy % (2 * step));
			yy = -yy;
			return new StorageTile(world, map, xx, yy, zoom + 1, var);
		}

		@Override
		public int hashCode() {
			return tilePath.hashCode();
		}

		@Override
		public void enqueueZoomOutUpdate() {
			world.enqueueZoomOutUpdate(this);
		}

		private byte[] createDeleteTilePayload(ImageEncoding imageType) throws UnsupportedEncodingException {
			Map<String, String> params = new HashMap<String, String>();
			params.put("key", accessKey);
			params.put("world", world.getName());
			params.put("map_prefix", map.getPrefix() + var.variantSuffix);
			params.put("zoom", Integer.toString(zoom));
			params.put("x", Integer.toString(x));
			params.put("y", Integer.toString(y));
			params.put("file_type", imageType.getFileExt());

			return createFormData(params);
		}
	}

	public RemoteFileTreeMapStorage() {
		super();
		multipartBoundary = UUID.randomUUID().toString();
	}

	@Override
	public boolean init(DynmapCore core) {
		if (!super.init(core)) {
			return false;
		}

		urlBase = core.configuration.getString("storage/url");
		accessKey = core.configuration.getString("storage/key");

		return true;
	}

	@Override
	public String getTilesURI(boolean login_enabled) {
		return remoteFileTreeBaseUrl + "tiles/";
	}

	@Override
	public String getMarkersURI(boolean login_enabled) {
		return remoteFileTreeBaseUrl + "markers/";
	}

	@Override
	public MapStorageTile getTile(DynmapWorld world, MapType map, int x, int y, int zoom, ImageVariant var) {
		return new StorageTile(world, map, x, y, zoom, var);
	}

	@Override
	public MapStorageTile getTile(DynmapWorld world, String uri) {
		String[] suri = uri.split("/");
		if (suri.length < 2)
			return null;
		String mname = suri[0]; // Map URI - might include variant
		MapType mt = null;
		ImageVariant imgvar = null;
		// Find matching map type and image variant
		for (int mti = 0; (mt == null) && (mti < world.maps.size()); mti++) {
			MapType type = world.maps.get(mti);
			ImageVariant[] var = type.getVariants();
			for (int ivi = 0; (imgvar == null) && (ivi < var.length); ivi++) {
				if (mname.equals(type.getPrefix() + var[ivi].variantSuffix)) {
					mt = type;
					imgvar = var[ivi];
				}
			}
		}
		if (mt == null) { // Not found?
			return null;
		}
		// Now, take the last section and parse out coordinates and zoom
		String fname = suri[suri.length - 1];
		String[] coord = fname.split("[_\\.]");
		if (coord.length < 3) { // 3 or 4
			return null;
		}
		int zoom = 0;
		int x, y;
		try {
			if (coord[0].charAt(0) == 'z') {
				zoom = coord[0].length();
				x = Integer.parseInt(coord[1]);
				y = Integer.parseInt(coord[2]);
			} else {
				x = Integer.parseInt(coord[0]);
				y = Integer.parseInt(coord[1]);
			}
			return getTile(world, mt, x, y, zoom, imgvar);
		} catch (NumberFormatException nfx) {
			return null;
		}
	}

	/**
	 * Starts an HTTP request to a server
	 */
	private HttpURLConnection createHttpRequest(String endpoint, String method) throws IOException {
		String combinedUrl = urlBase + "/" + endpoint;
		URL url;
		try {
			url = new URL(combinedUrl);
		} catch (MalformedURLException e) {
			Log.severe("Malformed URL: " + combinedUrl, e);
			return null;
		}

		// open connection to server
		URLConnection conn = url.openConnection();
		HttpURLConnection http = (HttpURLConnection) conn;

		// set up http client
		http.setRequestMethod(method);
		http.setDoOutput(true);

		return http;
	}

	@Override
	public String getMarkerFile(String world) {
		// TODO: fix stub
		return "{}";
	}

	@Override
	public void purgeMapTiles(DynmapWorld world, MapType type) {
		// TODO: fix stub
	}

	@Override
	public boolean setPlayerFaceImage(String playerName, PlayerFaces.FaceType type, BufferOutputStream encImage) {
		// TODO: fix stub
		return false;
	}

	@Override
	public BufferInputStream getPlayerFaceImage(String playername, PlayerFaces.FaceType facetype) {
		// TODO: fix stub
		return new BufferInputStream(new byte[0]);
	}

	@Override
	public boolean setMarkerImage(String markerid, BufferOutputStream encImage) {
		// TODO: fix stub
		return false;
	}

	@Override
	public boolean setMarkerFile(String world, String content) {
		// TODO: fix stub
		return false;
	}

	@Override
	public boolean hasPlayerFaceImage(String playerName, PlayerFaces.FaceType type) {
		// TODO: fix stub
		return false;
	}

	/**
	 * Gets the data for a simple HTTP form
	 * 
	 * @throws UnsupportedEncodingException
	 */
	private byte[] createFormData(Map<String, String> params) throws UnsupportedEncodingException {
		StringJoiner sj = new StringJoiner("&");
		for (Map.Entry<String, String> entry : params.entrySet())
			sj.add(URLEncoder.encode(entry.getKey(), "UTF-8") + "=" + URLEncoder.encode(entry.getValue(), "UTF-8"));
		return sj.toString().getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * Writes multiple multipart text fields to an OutputStream. This does not write
	 * the multipart ending boundary, one must be written manually.
	 */
	private void writeMultipartFormData(OutputStream out, Map<String, String> params)
			throws UnsupportedEncodingException, IOException {
		for (Map.Entry<String, String> entry : params.entrySet()) {
			writeMultipartBoundary(out, false);
			writeMultipartTextField(out, entry.getKey(), entry.getValue());
			writeMultipartBoundary(out, false);
		}
	}

	/**
	 * Writes a single multipart text field to the specified OutputStream
	 */
	private void writeMultipartTextField(OutputStream out, String key, String value)
			throws UnsupportedEncodingException, IOException {
		String o = "Content-Disposition: form-data; name=\"" + URLEncoder.encode(key, "UTF-8") + "\"\r\n\r\n";
		out.write(o.getBytes(StandardCharsets.UTF_8));
		out.write(URLEncoder.encode(value, "UTF-8").getBytes(StandardCharsets.UTF_8));
		out.write("\r\n".getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Writes a multipart file to the specified OutputStream
	 */
	private void writeMultipartFile(OutputStream out, String name, String fileName, byte[] buffer) throws IOException {
		String o = "Content-Disposition: form-data; name=\"" + URLEncoder.encode(name, "UTF-8") + "\"; filename=\""
				+ URLEncoder.encode(fileName, "UTF-8") + "\"\r\n\r\n";
		out.write(o.getBytes(StandardCharsets.UTF_8));
		out.write(buffer);
		out.write("\r\n".getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Writes a multipart boundary to the OutputStream
	 * 
	 * @param out The HTTP stream
	 * @param end Whether the multipart boundary signifies the end of the multipart
	 *            form
	 */
	private void writeMultipartBoundary(OutputStream out, boolean end) throws IOException {
		out.write(("--" + multipartBoundary + (end ? "--" : "")).getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Reads an entire InputStream to a single String
	 */
	private String readInputStreamToString(InputStream inputStream) throws IOException {
		BufferedReader stringReader = new BufferedReader(new InputStreamReader(inputStream));
		String hashString = stringReader.readLine();
		stringReader.close();

		return hashString;
	}
}
