package com.MuslimMedia.ans.mahjoob;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.webkit.WebView;

import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import dev.mahjoob.core.Decision;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class MahjoobVideoBridge implements AutoCloseable {
	private static final String BRIDGE_NAME = "mahjoobVideo";
	private static final int MAX_MESSAGE_CHARS = 512 * 1024;
	private static final int MAX_FRAME_BYTES = 384 * 1024;
	private static final int MAX_FRAME_SIDE = 480;
	private static final long MIN_FRAME_INTERVAL_MS = 450;
	private static final long GUARD_READY_TIMEOUT_MS = 15000;
	private static final String SCRIPT_PATH = "mahjoob/video_filter.js";

	private final MahjoobManager manager;
	private final String script;
	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	private final Map<WebView, RequestState> requestStates = Collections.synchronizedMap(new WeakHashMap<>());
	private final ThreadPoolExecutor inferenceExecutor = new ThreadPoolExecutor(
			1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2), runnable -> {
				Thread thread = new Thread(runnable, "mahjoob-video-inference");
				thread.setDaemon(true);
				return thread;
			});
	private volatile boolean closed;

	public MahjoobVideoBridge(Context context, MahjoobManager manager) throws IOException {
		this.manager = manager;
		this.script = readScript(context.getApplicationContext());
	}

	public static boolean areRequiredFeaturesAvailable() {
		try {
			return WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
					&& WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT);
		} catch (RuntimeException | LinkageError failure) {
			return false;
		}
	}

	public boolean install(WebView webView) {
		if (closed || script == null || !areRequiredFeaturesAvailable()) {
			return false;
		}
		try {
			WebViewCompat.addWebMessageListener(webView, BRIDGE_NAME,
					Collections.singleton("*"), this::onPostMessage);
			WebViewCompat.addDocumentStartJavaScript(webView, script, Collections.singleton("*"));
			return true;
		} catch (RuntimeException | LinkageError failure) {
			return false;
		}
	}

	private void onPostMessage(WebView webView, WebMessageCompat message, Uri sourceOrigin,
			boolean isMainFrame, JavaScriptReplyProxy replyProxy) {
		if (closed || message == null || message.getData() == null
				|| message.getData().length() > MAX_MESSAGE_CHARS) {
			return;
		}

		long requestId;
		String encodedFrame;
		try {
			JSONObject payload = new JSONObject(message.getData());
			requestId = payload.optLong("id", -1);
			if (requestId <= 0 || !"frame".equals(payload.optString("type"))) {
				return;
			}
			encodedFrame = payload.optString("jpeg", "");
			if (encodedFrame.isEmpty() || encodedFrame.length() > MAX_MESSAGE_CHARS) {
				reply(replyProxy, requestId, false);
				return;
			}
		} catch (JSONException exception) {
			return;
		}

		RequestState state = stateFor(webView);
		long now = SystemClock.elapsedRealtime();
		synchronized (state) {
			if (state.busy || now - state.lastAcceptedAt < MIN_FRAME_INTERVAL_MS) {
				reply(replyProxy, requestId, false);
				return;
			}
			state.busy = true;
			state.lastAcceptedAt = now;
		}

		try {
			inferenceExecutor.execute(() -> processFrame(encodedFrame, requestId, replyProxy, state));
		} catch (RejectedExecutionException exception) {
			synchronized (state) {
				state.busy = false;
			}
			reply(replyProxy, requestId, false);
		}
	}

	private void processFrame(String encodedFrame, long requestId, JavaScriptReplyProxy replyProxy,
			RequestState state) {
		boolean allow = false;
		Bitmap bitmap = null;
		try {
			byte[] jpeg = Base64.decode(encodedFrame, Base64.DEFAULT);
			if (jpeg.length < 4 || jpeg.length > MAX_FRAME_BYTES
					|| (jpeg[0] & 0xff) != 0xff || (jpeg[1] & 0xff) != 0xd8) {
				return;
			}
			BitmapFactory.Options bounds = new BitmapFactory.Options();
			bounds.inJustDecodeBounds = true;
			BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
			if (bounds.outWidth <= 0 || bounds.outHeight <= 0
					|| bounds.outWidth > MAX_FRAME_SIDE || bounds.outHeight > MAX_FRAME_SIDE) {
				return;
			}
			bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, new BitmapFactory.Options());
			if (bitmap == null || !manager.awaitReady(GUARD_READY_TIMEOUT_MS)) {
				return;
			}
			Decision decision = manager.check(bitmap);
			allow = decision != null && !decision.isBlocked();
		} catch (RuntimeException | OutOfMemoryError failure) {
			allow = false;
		} finally {
			if (bitmap != null) {
				bitmap.recycle();
			}
			synchronized (state) {
				state.busy = false;
			}
			reply(replyProxy, requestId, allow);
		}
	}

	private RequestState stateFor(WebView webView) {
		synchronized (requestStates) {
			RequestState state = requestStates.get(webView);
			if (state == null) {
				state = new RequestState();
				requestStates.put(webView, state);
			}
			return state;
		}
	}

	private void reply(JavaScriptReplyProxy replyProxy, long requestId, boolean allow) {
		String decision = allow ? "allow" : "block";
		String response = "{\"id\":" + requestId + ",\"decision\":\"" + decision + "\"}";
		mainHandler.post(() -> {
			try {
				replyProxy.postMessage(response);
			} catch (RuntimeException ignored) {
			}
		});
	}

	private static String readScript(Context context) throws IOException {
		try (InputStream input = context.getAssets().open(SCRIPT_PATH);
				ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			byte[] buffer = new byte[4096];
			int total = 0;
			int read;
			while ((read = input.read(buffer)) != -1) {
				total += read;
				if (total > 64 * 1024) {
					throw new IOException("Mahjoob video script is too large");
				}
				output.write(buffer, 0, read);
			}
			return new String(output.toByteArray(), StandardCharsets.UTF_8);
		}
	}

	@Override
	public void close() {
		closed = true;
		inferenceExecutor.shutdownNow();
		synchronized (requestStates) {
			requestStates.clear();
		}
	}

	private static final class RequestState {
		long lastAcceptedAt;
		boolean busy;
	}
}