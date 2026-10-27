package com.MuslimMedia.ans.mahjoob;

import okhttp3.HttpUrl;

import java.util.Locale;
import java.util.Map;

public final class MahjoobRequestPolicy {
	public static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
	public static final int MAX_VIDEO_FRAME_BYTES = 384 * 1024;
	public static final long MIN_VIDEO_FRAME_INTERVAL_MS = 450;

	private MahjoobRequestPolicy() {
	}

	public static String normalizeMode(String value) {
		if ("off".equals(value) || "women".equals(value) || "men".equals(value)
				|| "everyone".equals(value)) {
			return value;
		}
		return "everyone";
	}

	public static String cacheKey(String url, String mode) {
		return url + "|" + normalizeMode(mode);
	}

	public static boolean isCandidateImageRequest(String method, Map<String, String> headers, String url,
			boolean isMainFrame) {
		if (isMainFrame || !"GET".equalsIgnoreCase(method)) {
			return false;
		}
		if (headers != null) {
			for (Map.Entry<String, String> header : headers.entrySet()) {
				if (!"accept".equalsIgnoreCase(header.getKey()) || header.getValue() == null) {
					continue;
				}
				String accept = header.getValue().toLowerCase(Locale.US);
				if (accept.contains("text/html") || accept.contains("application/xhtml+xml")
						|| accept.contains("text/css") || accept.contains("javascript")
						|| accept.contains("application/json") || accept.contains("text/plain")
						|| accept.contains("font/")) {
					return false;
				}
				if (accept.matches("(?s).*\\bimage/[a-z0-9.+*-]+(?:\\s*;[^,]*)?(?:\\s*,.*)?")) {
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

	public static boolean isWithinSizeLimit(int size, int maximum) {
		return size >= 0 && size <= maximum;
	}

	public static boolean hasElapsed(long lastAcceptedAt, long now, long minimumIntervalMs) {
		return lastAcceptedAt < 0 || (now >= lastAcceptedAt && now - lastAcceptedAt >= minimumIntervalMs);
	}
}