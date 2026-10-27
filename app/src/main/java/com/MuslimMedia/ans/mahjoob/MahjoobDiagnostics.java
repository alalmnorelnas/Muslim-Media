package com.MuslimMedia.ans.mahjoob;

import android.os.SystemClock;
import android.util.Log;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class MahjoobDiagnostics {
	private static final boolean LOG_DIAGNOSTICS = true;
	private static final MahjoobDiagnostics INSTANCE = new MahjoobDiagnostics();
	private final AtomicLong requestsIntercepted = new AtomicLong();
	private final AtomicLong handledAsImage = new AtomicLong();
	private final AtomicLong passedThrough = new AtomicLong();
	private final AtomicLong imagesAllowed = new AtomicLong();
	private final AtomicLong imagesBlocked = new AtomicLong();
	private final AtomicLong timeouts = new AtomicLong();
	private final AtomicLong inferenceCount = new AtomicLong();
	private final AtomicLong inferenceTimeMs = new AtomicLong();
	private final AtomicLong initializationStartedAt = new AtomicLong();
	private final AtomicLong initializationTimeMs = new AtomicLong();
	private final ConcurrentHashMap<String, AtomicLong> errors = new ConcurrentHashMap<>();
	private volatile String lastErrorMessage = "-";

	private MahjoobDiagnostics() {
	}

	public static MahjoobDiagnostics getInstance() {
		return INSTANCE;
	}

	public void requestIntercepted() {
		requestsIntercepted.incrementAndGet();
	}

	public void handledAsImage() {
		handledAsImage.incrementAndGet();
	}

	public void passedThrough() {
		passedThrough.incrementAndGet();
	}

	public void imageAllowed() {
		imagesAllowed.incrementAndGet();
	}

	public void imageBlocked() {
		imagesBlocked.incrementAndGet();
	}

	public void timeout() {
		timeouts.incrementAndGet();
		recordError("timeout", null);
	}

	public void recordError(String type, Throwable error) {
		errors.computeIfAbsent(type, ignored -> new AtomicLong()).incrementAndGet();
		String message = error == null ? type : error.getClass().getSimpleName()
				+ (error.getMessage() == null ? "" : ": " + redactUrls(error.getMessage()));
		lastErrorMessage = message.replace('\n', ' ').replace('\r', ' ');
		if (LOG_DIAGNOSTICS) {
			Log.w("Mahjoob", type + (error == null ? "" : " " + error.getClass().getSimpleName()));
		}
	}

	public void initializationStarted() {
		initializationStartedAt.set(SystemClock.elapsedRealtime());
	}

	public void initializationFinished() {
		long startedAt = initializationStartedAt.getAndSet(0);
		if (startedAt > 0) {
			initializationTimeMs.set(SystemClock.elapsedRealtime() - startedAt);
		}
	}

	public void inferenceFinished(long durationMs) {
		inferenceCount.incrementAndGet();
		inferenceTimeMs.addAndGet(Math.max(0, durationMs));
	}

	public String format(MahjoobManager.State state, Throwable initializationError,
			int inFlight, int queueLength) {
		String stateLabel;
		switch (state) {
			case LOADING:
				stateLabel = "loading";
				break;
			case READY:
				stateLabel = "ready";
				break;
			case FAILED:
			case CLOSED:
				stateLabel = "error";
				break;
			default:
				stateLabel = "idle";
		}
		String errorMessage = initializationError == null ? lastErrorMessage
				: initializationError.getClass().getSimpleName()
					+ (initializationError.getMessage() == null ? ""
							: ": " + redactUrls(initializationError.getMessage()));
		StringBuilder errorCounts = new StringBuilder();
		for (Map.Entry<String, AtomicLong> entry : new TreeMap<>(errors).entrySet()) {
			if (errorCounts.length() > 0) {
				errorCounts.append(", ");
			}
			errorCounts.append(entry.getKey()).append('=').append(entry.getValue().get());
		}
		long count = inferenceCount.get();
		long averageInference = count == 0 ? 0 : inferenceTimeMs.get() / count;
		return "Mahjoob: " + stateLabel
				+ "\nLast error: " + errorMessage
				+ "\nRequests intercepted: " + requestsIntercepted.get()
				+ "\nHandled as image: " + handledAsImage.get()
				+ "\nPassed through (non-image): " + passedThrough.get()
				+ "\nImages allowed: " + imagesAllowed.get()
				+ "\nImages blocked: " + imagesBlocked.get()
				+ "\nErrors by type: " + (errorCounts.length() == 0 ? "-" : errorCounts)
				+ "\nTimeouts: " + timeouts.get()
				+ "\nInit time (ms): " + initializationTimeMs.get()
				+ "\nCurrent in-flight: " + inFlight
				+ "\nQueue length: " + queueLength
				+ "\nAverage inference (ms): " + averageInference;
	}

	private static String redactUrls(String message) {
		return message.replaceAll("(?i)https?://\\S+", "[url]");
	}
}