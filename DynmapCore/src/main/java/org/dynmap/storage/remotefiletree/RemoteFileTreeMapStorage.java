package org.dynmap.storage.remotefiletree;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;

import org.dynmap.DynmapCore;
import org.dynmap.DynmapWorld;
import org.dynmap.Log;
import org.dynmap.MapType;
import org.dynmap.MapType.ImageEncoding;
import org.dynmap.MapType.ImageVariant;
import org.dynmap.storage.MapStorage;
import org.dynmap.storage.MapStorageTile;
import org.dynmap.utils.BufferInputStream;

public class RemoteFileTreeMapStorage extends MapStorage {
	private String urlBase;
	private String accessKey;
	private final String multipartBoundary;
	private final String remoteFileTreeBaseUrl = "standalone/filetree/";

	public class StorageTile extends MapStorageTile {
		/**
		 * The tile path from the world to the name of the file. No leading HTTP path or
		 * extension included.
		 * 
		 * Ex: world/map/zoom/x.y
		 */
		private String tilePath;
		private String fullTileUrlNoExt;

		private final byte[] PNG_HEADER = new byte[] { (byte) 0x89, 'P', 'N', 'G', (byte) 0x0D, (byte) 0x0A,
				(byte) 0x1A, (byte) 0x0A };
		private final byte[] JPEG_HEADER = new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF };

		protected StorageTile(DynmapWorld world, MapType map, int x, int y, int zoom, ImageVariant var) {
			super(world, map, x, y, zoom, var);

			tilePath = world.getName() + "/" + map.getPrefix() + var.variantSuffix + "/" + zoom + "/" + x + "." + y;
			fullTileUrlNoExt = urlBase + "/" + remoteFileTreeBaseUrl + "/tiles/" + tilePath;
		}

		/**
		 * Gets the fully qualified path to a tile using the specified format
		 */
		private String getFullTileUrl(ImageEncoding format) {
			return fullTileUrlNoExt + "." + format.getFileExt();
		}

		@Override
		public TileRead read() {
			HttpURLConnection conn = getTileConnection();
			TileRead read = new TileRead();

			// read image data
			try {
				InputStream body = conn.getInputStream();
				String lengthString = conn.getHeaderField("Content-Length");
				int bodyLength = Integer.parseInt(lengthString);
				byte[] buffer = new byte[bodyLength];
			}
			catch(IOException | NumberFormatException ex) {
				Log.severe("Tile read error: " + ex);
				return null;
			}
		}

		@Override
		public boolean exists() {
			return getTileConnection() != null; // returns null if the tile doesn't exist remotely
		}

		/**
		 * Opens an HTTP connection to the tile file
		 * 
		 * @return The HttpURLConnection, or null if the tile doesn't have a valid URL
		 */
		private HttpURLConnection getTileConnection() {
			// try the map default encoding first
			ImageEncoding defaultEncoding = map.getImageFormat().getEncoding() == ImageEncoding.PNG ? ImageEncoding.PNG
					: ImageEncoding.JPG;

			// try default encoding
			String tileUrl = getFullTileUrl(defaultEncoding);
			HttpURLConnection response = tryGetResponse(tileUrl);
			if (response != null) {
				return response;
			}

			// try other encoding
			tileUrl = getFullTileUrl(defaultEncoding == ImageEncoding.PNG ? ImageEncoding.JPG : ImageEncoding.PNG);
			response = tryGetResponse(tileUrl);
			return response;
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
		return remoteFileTreeBaseUrl + "tiles.php";
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

	/**
	 * Gets the data for a simple HTTP form
	 * 
	 * @throws UnsupportedEncodingException
	 */
	private byte[] getFormData(OutputStream out, Map<String, String> params) throws UnsupportedEncodingException {
		StringJoiner sj = new StringJoiner("&");
		for (Map.Entry<String, String> entry : params.entrySet())
			sj.add(URLEncoder.encode(entry.getKey(), "UTF-8") + "=" + URLEncoder.encode(entry.getValue(), "UTF-8"));
		return sj.toString().getBytes(StandardCharsets.UTF_8);
	}

	private void writeMultipartTextField(OutputStream out, String key, String value)
			throws UnsupportedEncodingException, IOException {
		String o = "Content-Disposition: form-data; name=\"" + URLEncoder.encode(key, "UTF-8") + "\"\r\n\r\n";
		out.write(o.getBytes(StandardCharsets.UTF_8));
		out.write(URLEncoder.encode(value, "UTF-8").getBytes(StandardCharsets.UTF_8));
		out.write("\r\n".getBytes(StandardCharsets.UTF_8));
	}

	private void writeMultipartFile(OutputStream out, String name, String fileName, InputStream fileStream)
			throws IOException {
		String o = "Content-Disposition: form-data; name=\"" + URLEncoder.encode(name, "UTF-8") + "\"; filename=\""
				+ URLEncoder.encode(fileName, "UTF-8") + "\"\r\n\r\n";
		out.write(o.getBytes(StandardCharsets.UTF_8));
		byte[] buffer = new byte[2048];
		for (int n = 0; n >= 0; n = fileStream.read(buffer))
			out.write(buffer, 0, n);
		out.write("\r\n".getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Writes a multipart boundary to the HTTP stream.
	 * 
	 * @param out The HTTP stream
	 * @param end Whether the multipart boundary signifies the end of the multipart
	 *            form
	 */
	private void writeMultipartBoundary(OutputStream out, boolean end) throws IOException {
		out.write(("--" + multipartBoundary + (end ? "--" : "")).getBytes(StandardCharsets.UTF_8));
	}
}
