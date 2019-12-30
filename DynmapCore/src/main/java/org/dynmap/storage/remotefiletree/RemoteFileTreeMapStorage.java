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
import org.dynmap.MapType.ImageVariant;
import org.dynmap.storage.MapStorage;
import org.dynmap.storage.MapStorageTile;

public class RemoteFileTreeMapStorage extends MapStorage {
	private String urlBase;
	private String accessKey;
	private final String multipartBoundary;
	private final String remoteFileTreeBaseUrl = "standalone/filetree/";

	public class StorageTile extends MapStorageTile {
		private String uri;

		protected StorageTile(DynmapWorld world, MapType map, int x, int y, int zoom, ImageVariant var) {
			super(world, map, x, y, zoom, var);

			uri = world.getName() + "/" + map.getPrefix() + var.variantsuffix + "/" + zoom + "/" + x + "." + y + "." + map.getImageFormat().getSuffix();
		}

		@Override
		public void exists() {
			
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
