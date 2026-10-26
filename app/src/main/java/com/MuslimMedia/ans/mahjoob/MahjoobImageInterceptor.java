package com.MuslimMedia.ans.mahjoob;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import dev.mahjoob.android.MahjoobGuard;
import dev.mahjoob.core.Decision;

public final class MahjoobImageInterceptor {
	private static final int MAX_RESPONSE_BYTES = 10 * 1024 * 1024;
	private static final int MAX_BITMAP_SIDE = 1280;
	private static final int MIN_CLASSIFY_SIDE = 40;
	private static final int MAX_REDIRECTS = 10;
	private static final long NETWORK_TIMEOUT_MS = 10000;
	private static final long GUARD_READY_TIMEOUT_MS = 15000;
	private static final long SHARED_RESULT_TIMEOUT_MS = 26000;
	private static final int DECISION_CACHE_SIZE = 500;
	private static final byte[] PLACEHOLDER_PNG = createPlaceholder();

	private final MahjoobManager manager;
	private final android.content.SharedPreferences preferences;
	private final OkHttpClient httpClient;
	private final Object cacheLock = new Object();
	private final Map<String, Boolean> decisions = new LinkedHashMap<String, Boolean>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
			return size() > DECISION_CACHE_SIZE;
		}
	};
	private final ConcurrentHashMap<String, CompletableFuture<ImageResult>> inFlight = new ConcurrentHashMap<>();

	public MahjoobImageInterceptor(android.content.SharedPreferences preferences, MahjoobManager manager) {
		this.preferences = preferences;
		this.manager = manager;
		this.httpClient = new OkHttpClient.Builder()
				.connectTimeout(10, TimeUnit.SECONDS)
				.readTimeout(10, TimeUnit.SECONDS)
				.writeTimeout(10, TimeUnit.SECONDS)
				.followRedirects(false)
				.followSslRedirects(false)
				.build();
	}

	public WebResourceResponse intercept(WebView view, WebResourceRequest request) {
		if (MahjoobSettings.readMode(preferences) == MahjoobSettings.Mode.OFF
				|| request == null
				|| !"GET".equalsIgnoreCase(request.getMethod())
				|| !acceptsImages(request.getRequestHeaders(), request.getUrl().toString())) {
			return null;
		}
		if (Looper.myLooper() == Looper.getMainLooper()) {
			return blockedResponse();
		}

		String url = request.getUrl().toString();
		HttpUrl parsedUrl = HttpUrl.parse(url);
		if (parsedUrl == null || !("http".equals(parsedUrl.scheme()) || "https".equals(parsedUrl.scheme()))) {
			return blockedResponse();
		}

		MahjoobSettings.Mode mode = MahjoobSettings.readMode(preferences);
		String cacheKey = url + "|" + mode.name();
		ImageResult result = getOrCreateResult(cacheKey, request);
		if (result == null) {
			return blockedResponse();
		}
		if (result.bytes == null) {
			return blockedResponse();
		}
		return result.blocked
				? blockedResponse()
				: new WebResourceResponse(result.mimeType, result.encoding, result.statusCode,
					result.reasonPhrase, result.responseHeaders, new ByteArrayInputStream(result.bytes));
	}

	private ImageResult getOrCreateResult(String key, WebResourceRequest request) {
		CompletableFuture<ImageResult> created = new CompletableFuture<>();
		CompletableFuture<ImageResult> existing = inFlight.putIfAbsent(key, created);
		if (existing == null) {
			try {
				ImageResult result = fetchAndClassify(request, key);
				created.complete(result);
				return result;
			} catch (RuntimeException | IOException | OutOfMemoryError failure) {
				created.complete(ImageResult.blocked());
				return ImageResult.blocked();
			} finally {
				inFlight.remove(key, created);
			}
		}
		try {
			return existing.get(SHARED_RESULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			return ImageResult.blocked();
		} catch (ExecutionException | TimeoutException exception) {
			return ImageResult.blocked();
		}
	}

	private ImageResult fetchAndClassify(WebResourceRequest request, String cacheKey) throws IOException {
		Map<String, String> requestHeaders = request.getRequestHeaders();
		String requestCookie = null;
		if (requestHeaders != null) {
			for (Map.Entry<String, String> header : requestHeaders.entrySet()) {
				if (header.getKey() == null || header.getValue() == null) {
					continue;
				}
				if ("cookie".equalsIgnoreCase(header.getKey())) {
					requestCookie = header.getValue();
				}
			}
		}
		try (Response response = fetchFollowingRedirects(request, requestHeaders, requestCookie)) {
			ResponseBody body = response.body();
			if (!response.isSuccessful() || body == null) {
				return ImageResult.blocked();
			}
			byte[] bytes = readBounded(body);
			String mimeType = response.header("Content-Type");
			if (mimeType == null && body.contentType() != null) {
				mimeType = body.contentType().toString();
			}
			if (mimeType == null) {
				return ImageResult.blocked();
			}
			mimeType = mimeType.split(";", 2)[0].trim().toLowerCase(Locale.US);
			if (!mimeType.startsWith("image/")) {
				return ImageResult.blocked();
			}

			Map<String, String> responseHeaders = copyResponseHeaders(response.headers());
			if ("image/svg+xml".equals(mimeType)) {
				return ImageResult.allowed(bytes, mimeType, response, responseHeaders);
			}

			Boolean cachedDecision = getCachedDecision(cacheKey);
			if (cachedDecision != null) {
				return ImageResult.fromDecision(cachedDecision, bytes, mimeType, response, responseHeaders);
			}

			Bitmap bitmap = decodeForClassification(bytes);
			if (bitmap == null) {
				cacheDecision(cacheKey, true);
				return ImageResult.blockedImage(bytes, mimeType, response, responseHeaders);
			}
			boolean blocked;
			boolean shouldCache = true;
			try {
				if (bitmap.getWidth() < MIN_CLASSIFY_SIDE || bitmap.getHeight() < MIN_CLASSIFY_SIDE) {
					blocked = false;
				} else if (!manager.awaitReady(GUARD_READY_TIMEOUT_MS)) {
					blocked = true;
					shouldCache = false;
				} else {
					MahjoobGuard guard = manager.getGuardIfReady();
					if (guard == null) {
						blocked = true;
						shouldCache = false;
					} else {
						Decision decision = guard.check(bitmap, cacheKey);
						blocked = decision == null || decision.isBlocked();
					}
				}
			} finally {
				bitmap.recycle();
			}
			if (shouldCache) {
				cacheDecision(cacheKey, blocked);
			}
			return ImageResult.fromDecision(blocked, bytes, mimeType, response, responseHeaders);
		}
	}

	private Response fetchFollowingRedirects(WebResourceRequest request, Map<String, String> requestHeaders,
			String initialRequestCookie) throws IOException {
		HttpUrl initialUrl = HttpUrl.parse(request.getUrl().toString());
		HttpUrl currentUrl = initialUrl;
		boolean preserveAuthorization = true;
		long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(NETWORK_TIMEOUT_MS);
		for (int redirectCount = 0; redirectCount <= MAX_REDIRECTS; redirectCount++) {
			if (!isSameOrigin(initialUrl, currentUrl)) {
				preserveAuthorization = false;
			}
			long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
			if (remainingMs <= 0) {
				throw new IOException("Image request timed out");
			}
			Request.Builder requestBuilder = new Request.Builder().url(currentUrl).get();
			if (requestHeaders != null) {
				for (Map.Entry<String, String> header : requestHeaders.entrySet()) {
					if (header.getKey() == null || header.getValue() == null
							|| "cookie".equalsIgnoreCase(header.getKey())
							|| "host".equalsIgnoreCase(header.getKey())
							|| "content-length".equalsIgnoreCase(header.getKey())
							|| "accept-encoding".equalsIgnoreCase(header.getKey())
							|| (!preserveAuthorization && ("authorization".equalsIgnoreCase(header.getKey())
							|| "proxy-authorization".equalsIgnoreCase(header.getKey())))) {
						continue;
					}
					requestBuilder.header(header.getKey(), header.getValue());
				}
			}
			String cookie = CookieManager.getInstance().getCookie(currentUrl.toString());
			if ((cookie == null || cookie.isEmpty()) && redirectCount == 0) {
				cookie = initialRequestCookie;
			}
			if (cookie != null && !cookie.isEmpty()) {
				requestBuilder.header("Cookie", cookie);
			}

			OkHttpClient timedClient = httpClient.newBuilder()
					.callTimeout(remainingMs, TimeUnit.MILLISECONDS)
					.build();
			Response response = timedClient.newCall(requestBuilder.build()).execute();
			if (!isRedirect(response.code()) || response.header("Location") == null) {
				storeResponseCookies(currentUrl, response);
				return response;
			}
			HttpUrl nextUrl = currentUrl.resolve(response.header("Location"));
			storeResponseCookies(currentUrl, response);
			response.close();
			if (nextUrl == null || !("http".equals(nextUrl.scheme()) || "https".equals(nextUrl.scheme()))) {
				throw new IOException("Invalid image redirect target");
			}
			currentUrl = nextUrl;
		}
		throw new IOException("Too many image redirects");
	}

	private static boolean isSameOrigin(HttpUrl first, HttpUrl second) {
		return first.scheme().equals(second.scheme()) && first.host().equals(second.host())
				&& first.port() == second.port();
	}

	private static void storeResponseCookies(HttpUrl url, Response response) {
		for (String setCookie : response.headers("Set-Cookie")) {
			CookieManager.getInstance().setCookie(url.toString(), setCookie);
		}
	}

	private static boolean isRedirect(int statusCode) {
		return statusCode == 300 || statusCode == 301 || statusCode == 302 || statusCode == 303
				|| statusCode == 307 || statusCode == 308;
	}

	private static byte[] readBounded(ResponseBody body) throws IOException {
		if (body.contentLength() > MAX_RESPONSE_BYTES) {
			throw new IOException("Image response exceeded the size limit");
		}
		try (InputStream input = body.byteStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			byte[] buffer = new byte[8192];
			int total = 0;
			int read;
			while ((read = input.read(buffer)) != -1) {
				total += read;
				if (total > MAX_RESPONSE_BYTES) {
					throw new IOException("Image response exceeded the size limit");
				}
				output.write(buffer, 0, read);
			}
			return output.toByteArray();
		}
	}

	private static Bitmap decodeForClassification(byte[] bytes) {
		BitmapFactory.Options bounds = new BitmapFactory.Options();
		bounds.inJustDecodeBounds = true;
		BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
		if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
			return null;
		}
		BitmapFactory.Options options = new BitmapFactory.Options();
		options.inSampleSize = 1;
		while (Math.max(bounds.outWidth / options.inSampleSize, bounds.outHeight / options.inSampleSize) > MAX_BITMAP_SIDE) {
			options.inSampleSize *= 2;
		}
		return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
	}

	private Boolean getCachedDecision(String key) {
		synchronized (cacheLock) {
			return decisions.get(key);
		}
	}

	private void cacheDecision(String key, boolean blocked) {
		synchronized (cacheLock) {
			decisions.put(key, blocked);
		}
	}

	private static boolean acceptsImages(Map<String, String> headers, String url) {
		if (headers != null) {
			for (Map.Entry<String, String> header : headers.entrySet()) {
				if ("accept".equalsIgnoreCase(header.getKey()) && header.getValue() != null
						&& header.getValue().toLowerCase(Locale.US).contains("image/")) {
					return true;
				}
			}
		}
		HttpUrl parsedUrl = HttpUrl.parse(url);
		if (parsedUrl == null) {
			return false;
		}
		String path = parsedUrl.encodedPath().toLowerCase(Locale.US);
		return path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg")
				|| path.endsWith(".gif") || path.endsWith(".webp") || path.endsWith(".avif")
				|| path.endsWith(".bmp") || path.endsWith(".ico") || path.endsWith(".tif")
				|| path.endsWith(".tiff") || path.endsWith(".svg");
	}

	private static Map<String, String> copyResponseHeaders(Headers headers) {
		Map<String, String> copied = new LinkedHashMap<>();
		for (String name : headers.names()) {
			if ("set-cookie".equalsIgnoreCase(name)) {
				continue;
			}
			List<String> values = headers.values(name);
			StringBuilder combined = new StringBuilder();
			for (String value : values) {
				if (combined.length() > 0) {
					combined.append(", ");
				}
				combined.append(value);
			}
			copied.put(name, combined.toString());
		}
		return Collections.unmodifiableMap(copied);
	}

	private static WebResourceResponse blockedResponse() {
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("Cache-Control", "no-store");
		return new WebResourceResponse("image/png", "UTF-8", 200, "OK", headers,
				new ByteArrayInputStream(PLACEHOLDER_PNG));
	}

	private static byte[] createPlaceholder() {
		Bitmap bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
		Canvas canvas = new Canvas(bitmap);
		Paint paint = new Paint();
		paint.setColor(Color.GRAY);
		canvas.drawRect(0, 0, 24, 24, paint);
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
		bitmap.recycle();
		return output.toByteArray();
	}

	private static final class ImageResult {
		final boolean blocked;
		final byte[] bytes;
		final String mimeType;
		final String encoding;
		final int statusCode;
		final String reasonPhrase;
		final Map<String, String> responseHeaders;

		private ImageResult(boolean blocked, byte[] bytes, String mimeType, String encoding,
				int statusCode, String reasonPhrase, Map<String, String> responseHeaders) {
			this.blocked = blocked;
			this.bytes = bytes;
			this.mimeType = mimeType;
			this.encoding = encoding;
			this.statusCode = statusCode;
			this.reasonPhrase = reasonPhrase;
			this.responseHeaders = responseHeaders;
		}

		static ImageResult blocked() {
			return new ImageResult(true, null, "image/png", null, 200, "OK", Collections.emptyMap());
		}

		static ImageResult allowed(byte[] bytes, String mimeType, Response response, Map<String, String> headers) {
			return fromDecision(false, bytes, mimeType, response, headers);
		}

		static ImageResult blockedImage(byte[] bytes, String mimeType, Response response, Map<String, String> headers) {
			return fromDecision(true, bytes, mimeType, response, headers);
		}

		static ImageResult fromDecision(boolean blocked, byte[] bytes, String mimeType,
				Response response, Map<String, String> headers) {
			String encoding = response.body() != null && response.body().contentType() != null
					&& response.body().contentType().charset() != null
					? response.body().contentType().charset().name()
					: null;
			String reasonPhrase = response.message();
			if (reasonPhrase == null || reasonPhrase.isEmpty()) {
				reasonPhrase = "OK";
			}
			return new ImageResult(blocked, bytes, mimeType, encoding, response.code(),
				reasonPhrase, headers);
		}
	}
}
