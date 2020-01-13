package org.dynmap.storage.remotefiletree;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
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
import org.dynmap.storage.MapStorageBaseTileEnumCB;
import org.dynmap.storage.MapStorageTile;
import org.dynmap.storage.MapStorageTileEnumCB;
import org.dynmap.storage.MapStorageTileSearchEndCB;
import org.dynmap.utils.BufferInputStream;
import org.dynmap.utils.BufferOutputStream;

public class RemoteFileTreeMapStorage extends MapStorage {
	/**
	 * Base URL for the web location dynmap is located
	 */
	private String urlBase;

	/**
	 * Location of the remotefiletree folder on web
	 */
	private String remoteFileTreeBaseUrl;

	/**
	 * Location of tiles.php endpoint
	 */
	private String tilesEndpoint;
	private String accessKey;

	private final String multipartBoundary;

	public class StorageTile extends MapStorageTile {
		/**
		 * The tile path from the world to the name of the file. No leading HTTP path or
		 * extension included.
		 * 
		 * Ex: world/map/zoom/x.y
		 */
		private String tilePath;

		/**
		 * Fully-qualified URL to the tile without a file extension
		 */
		private String fullTileUrlNoExt;

		protected StorageTile(DynmapWorld world, MapType map, int x, int y, int zoom, ImageVariant var) {
			super(world, map, x, y, zoom, var);

			tilePath = world.getName() + "/" + map.getPrefix() + var.variantSuffix + "/" + zoom + "/" + x + "." + y;
			fullTileUrlNoExt = remoteFileTreeBaseUrl + "/tiles/" + tilePath;
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
				Long hashCode = fetchRemoteHashCode();
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
				int deleteAltTileResponse = deleteAltTileConn.getResponseCode();
				if (deleteAltTileResponse != 200) {
					throw new Exception(String.format("Failed to delete opposite format tile: HTTP %d: %s",
							deleteAltTileResponse, readInputStreamToString(deleteAltTileConn.getInputStream())));
				}

				// prepare connection for new tile
				HttpURLConnection writeTileConn = createHttpRequest(tilesEndpoint, "POST");

				// if encImage is null, we are deleting the current tile
				if (encImage == null) {
					writeTileConn.getOutputStream().write(createDeleteTilePayload(getMatchingEncoding()));

					// make sure file deletion was successful
					int writeTileResponse = writeTileConn.getResponseCode();
					if (writeTileResponse != 200) {
						throw new Exception(String.format("Failed to delete tile: HTTP %d: %s", deleteAltTileResponse,
								readInputStreamToString(writeTileConn.getInputStream())));
					}

					// Signal update for zoom out
					if (zoom == 0) {
						world.enqueueZoomOutUpdate(this);
					}

					return true;
				}

				writeTileConn.setRequestProperty("Connection", "Keep-Alive");
				writeTileConn.setRequestProperty("Cache-Control", "no-cache");
				writeTileConn.setRequestProperty("Content-Type", "multipart/form-data;boundary=" + multipartBoundary);
				ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();

				// create map of params to send in multipart form
				Map<String, String> params = new HashMap<String, String>();
				params.put("key", accessKey);
				params.put("action", "write");
				params.put("world", world.getName());
				params.put("map_prefix", map.getPrefix() + var.variantSuffix);
				params.put("zoom", Integer.toString(zoom));
				params.put("x", Integer.toString(x));
				params.put("y", Integer.toString(y));
				params.put("file_type", getMatchingEncoding().getFileExt());
				params.put("hash", Long.toString(hash));

				// write the contents of formData to multipart form
				writeMultipartFormData(outBuffer, params);
				writeMultipartBoundary(outBuffer, false);

				// write file data to multipart form
				writeMultipartFile(outBuffer, "file", "file", encImage.buf);

				// write multipart end boundary
				writeMultipartBoundary(outBuffer, true);

				// write buffer to connection
				writeTileConn.setFixedLengthStreamingMode(outBuffer.size());
				OutputStream outStream = writeTileConn.getOutputStream();
				outStream.write(outBuffer.toByteArray());
				outStream.flush();
				outStream.close();

				// check response
				int writeTileResponse = writeTileConn.getResponseCode();
				if (writeTileResponse != 200) {
					throw new Exception(String.format("Failed to write tile: HTTP %d: %s", writeTileResponse,
							readInputStreamToString(writeTileConn.getInputStream())));
				}

				// Signal update for zoom out
				if (zoom == 0) {
					world.enqueueZoomOutUpdate(this);
				}

				return true;
			} catch (Exception ex) {
				Log.severe("Error writing tile " + this.toString() + ": " + ex.getMessage());
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

		/**
		 * Fetches the remote hash code for a tile
		 * 
		 * @return The hash code, or null if it does not exist
		 */
		private Long fetchRemoteHashCode() {
			try {
				HttpURLConnection hashConn = createHttpRequest(fullTileUrlNoExt + ".hash", "GET");

				// check response
				int responseCode = hashConn.getResponseCode();
				if (responseCode == 404) {
					return null;
				} else if (responseCode != 200) {
					throw new Exception(String.format("Failed to fetch hash: HTTP %d: %s", responseCode,
							readInputStreamToString(hashConn.getInputStream())));
				}

				// get length of hash body
				int hashBodyLength = hashConn.getHeaderFieldInt("Content-Length", -1);
				if (hashBodyLength == -1) {
					throw new Exception("Invalid or missing Content-Length");
				}

				// hash body to long
				String hashString = readInputStreamToString(hashConn.getInputStream());
				return Long.parseLong(hashString.trim());
			} catch (Exception ex) {
				Log.severe("Error fetching hash: " + StorageTile.this.toString() + ": " + ex.getMessage());
				return null;
			}
		}

		@Override
		public boolean matchesHashCode(long hash) {
			Long thisHash = fetchRemoteHashCode();
			if (thisHash == null) {
				return false;
			}
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

				if (conn.getResponseCode() == 200) {
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
			params.put("action", "delete");
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

		// build URLs
		urlBase = core.configuration.getString("storage/url");
		remoteFileTreeBaseUrl = urlBase + "/standalone/filetree/";
		tilesEndpoint = remoteFileTreeBaseUrl + "tiles.php";

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

	@Override
	public void enumMapTiles(DynmapWorld world, MapType map, MapStorageTileEnumCB cb) {
		List<MapType> mtlist;

		if (map != null) {
			mtlist = Collections.singletonList(map);
		} else { // Else, add all maps
			mtlist = new ArrayList<MapType>(world.maps);
		}
		for (MapType mt : mtlist) {
			ImageVariant[] vars = mt.getVariants();
			for (ImageVariant var : vars) {
				// enumerate zoom levels
				ArrayList<Integer> zoomLevels = new ArrayList<Integer>();
				try {
					// get url parameters
					Map<String, String> params = new HashMap<String, String>();
					params.put("action", "enumzoom");
					params.put("world", world.getName());
					params.put("map_prefix", map.getPrefix() + var.variantSuffix);
					String urlParams = createUrlParams(params);

					// make connection
					HttpURLConnection conn = createHttpRequest(tilesEndpoint + "?" + urlParams, "GET");

					// make sure we get the correct response code
					int responseCode = conn.getResponseCode();
					if (responseCode != 200) {
						// throw new Exception("Invalid response code: " + responseCode);
						Log.severe("Failed to enumerate zoom levels, server responded with: " + responseCode + "\n"
								+ readInputStreamToString(conn.getInputStream()));
						return;
					}

					// read each zoom level from response
					BufferedReader stringReader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
					String zoomString;
					while ((zoomString = stringReader.readLine()) != null) {
						try {
							zoomLevels.add(Integer.parseInt(zoomString));
						} catch (NumberFormatException ex) {
							Log.warning("Invalid zoom level " + zoomString + " enumerated for map " + world.getName()
									+ ":" + map.getPrefix() + var.variantSuffix);
							continue; // ignore invalid entries
						}
					}
				} catch (Exception ex) {
					Log.severe("Failed to enumerate zoom levels: " + ex.getMessage());
				}

				for (Integer zoom : zoomLevels) {
					processEnumMapTiles(world, mt, var, zoom, cb, null, null);
				}
			}
		}
	}

	@Override
	public void enumMapBaseTiles(DynmapWorld world, MapType map, MapStorageBaseTileEnumCB cbBase,
			MapStorageTileSearchEndCB cbEnd) {
		List<MapType> mtlist;

		if (map != null) {
			mtlist = Collections.singletonList(map);
		} else { // Else, add all maps
			mtlist = new ArrayList<MapType>(world.maps);
		}
		for (MapType mt : mtlist) {
			ImageVariant[] vars = mt.getVariants();
			for (ImageVariant var : vars) {
				processEnumMapTiles(world, mt, var, 0, null, cbBase, cbEnd);
			}
		}
	}

	private void processEnumMapTiles(DynmapWorld world, MapType map, ImageVariant var, int zoom,
			MapStorageTileEnumCB cb, MapStorageBaseTileEnumCB cbBase, MapStorageTileSearchEndCB cbEnd) {
		try {
			// create url parameters
			Map<String, String> params = new HashMap<String, String>();
			params.put("action", "enumtiles");
			params.put("world", world.getName());
			params.put("map_prefix", map.getPrefix() + var.variantSuffix);
			params.put("zoom", Integer.toString(zoom));
			String urlParams = createUrlParams(params);

			// make connection
			HttpURLConnection conn = createHttpRequest(tilesEndpoint + "?" + urlParams, "GET");

			// make sure we get the correct response code
			int responseCode = conn.getResponseCode();
			if (responseCode != 200) {
				throw new Exception("Invalid response code: " + responseCode);
			}

			// read each line as a new map tile
			BufferedReader stringReader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
			String entry;
			while ((entry = stringReader.readLine()) != null) {
				// tiles follow format x.y.ext
				String[] fileNameSplit = entry.split("\\.");
				StorageTile st = new StorageTile(world, map, Integer.parseInt(fileNameSplit[0]),
						Integer.parseInt(fileNameSplit[1]), zoom, var);
				final ImageEncoding enc = ImageEncoding.fromExt(fileNameSplit[2]);

				if (cb != null) {
					cb.tileFound(st, enc);
				}
				if (cbBase != null && zoom == 0) {
					cbBase.tileFound(st, enc);
				}
			}
		} catch (Exception ex) {
			Log.severe("Failed to enumerate map tiles: " + ex.getMessage());
		} finally {
			if (cbEnd != null) {
				cbEnd.searchEnded();
			}
		}

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
	public BufferInputStream getMarkerImage(String markerid) {
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
	 * Starts an HTTP request to a server
	 * 
	 * @return The connection object, or null if there was an error
	 */
	private HttpURLConnection createHttpRequest(String endpoint, String method) throws IOException {
		String combinedUrl = endpoint;
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
		http.setUseCaches(false);
		http.setDoOutput(true);
		http.setRequestMethod(method);

		return http;
	}

	/**
	 * Gets the data for a simple HTTP urlencoded form (POST only)
	 * 
	 * @throws UnsupportedEncodingException
	 */
	private byte[] createFormData(Map<String, String> params) throws UnsupportedEncodingException {
		return createUrlParams(params).getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * Gets the urlencoded parameters for an HTTP request
	 */
	private String createUrlParams(Map<String, String> params) throws UnsupportedEncodingException {
		StringJoiner sj = new StringJoiner("&");
		for (Map.Entry<String, String> entry : params.entrySet())
			sj.add(URLEncoder.encode(entry.getKey(), "UTF-8") + "=" + URLEncoder.encode(entry.getValue(), "UTF-8"));
		return sj.toString();
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
		}
	}

	/**
	 * Writes a single multipart text field to the specified OutputStream
	 */
	private void writeMultipartTextField(OutputStream out, String key, String value)
			throws UnsupportedEncodingException, IOException {
		String o = "\r\nContent-Disposition: form-data; name=\"" + URLEncoder.encode(key, "UTF-8") + "\"\r\n\r\n";
		out.write(o.getBytes(StandardCharsets.UTF_8));
		out.write(URLEncoder.encode(value, "UTF-8").getBytes(StandardCharsets.UTF_8));
		out.write("\r\n".getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Writes a multipart file to the specified OutputStream
	 */
	private void writeMultipartFile(OutputStream out, String name, String fileName, byte[] buffer) throws IOException {
		String o = "\r\nContent-Disposition: form-data; name=\"" + URLEncoder.encode(name, "UTF-8") + "\"; filename=\""
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
		StringBuilder stringBuilder = new StringBuilder();
		String line;
		while ((line = stringReader.readLine()) != null) {
			stringBuilder.append(line + "\n");
		}
		stringReader.close();

		return stringBuilder.toString();
	}
}
