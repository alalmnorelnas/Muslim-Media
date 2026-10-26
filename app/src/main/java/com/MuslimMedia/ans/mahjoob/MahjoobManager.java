package com.MuslimMedia.ans.mahjoob;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import dev.mahjoob.android.MahjoobGuard;
import dev.mahjoob.android.MahjoobOptions;
import dev.mahjoob.core.Decision;
import dev.mahjoob.core.TargetGender;

import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

public final class MahjoobManager {
	public enum State {
		NEW,
		LOADING,
		READY,
		FAILED,
		CLOSED
	}

	public interface Listener {
		void onStateChanged(State state);
	}

	private static final Object INSTANCE_LOCK = new Object();
	private static volatile MahjoobManager instance;

	private final Object lock = new Object();
	private final Object inferenceLock = new Object();
	private final Context appContext;
	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	private volatile State state = State.NEW;
	private volatile MahjoobGuard guard;
	private volatile Throwable initializationError;
	private CountDownLatch readiness = new CountDownLatch(0);
	private long configurationVersion;
	private TargetGender configuredTarget;
	private boolean configuredStrict;
	private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "mahjoob-init");
		thread.setDaemon(true);
		return thread;
	});
	private volatile WeakReference<Listener> listener = new WeakReference<>(null);

	private MahjoobManager(Context context) {
		appContext = context.getApplicationContext();
	}

	public static MahjoobManager getInstance(Context context) {
		MahjoobManager current = instance;
		if (current == null) {
			synchronized (INSTANCE_LOCK) {
				current = instance;
				if (current == null) {
					current = new MahjoobManager(context);
					instance = current;
				}
			}
		}
		return current;
	}

	public void initialize(TargetGender target, boolean strict, Listener stateListener) {
		State immediateState = null;
		synchronized (lock) {
			listener = new WeakReference<>(stateListener);
			if (state == State.CLOSED) {
				immediateState = State.CLOSED;
			} else if (state == State.READY && target == configuredTarget && strict == configuredStrict) {
				immediateState = State.READY;
			} else if (state == State.LOADING && target == configuredTarget && strict == configuredStrict) {
				return;
			} else {
				configuredTarget = target;
				configuredStrict = strict;
				initializationError = null;
				readiness.countDown();
				readiness = new CountDownLatch(1);
				state = State.LOADING;
				TargetGender selectedTarget = target;
				boolean strictMode = strict;
				long version = ++configurationVersion;
				try {
					executor.execute(() -> loadGuard(selectedTarget, strictMode, version));
				} catch (RejectedExecutionException failure) {
					initializationError = failure;
					state = State.FAILED;
					readiness.countDown();
					immediateState = State.FAILED;
				}
			}
		}
		if (immediateState != null) {
			notifyListener(immediateState);
		}
	}

	public boolean awaitReady(long timeoutMs) {
		if (Looper.myLooper() == Looper.getMainLooper()) {
			throw new IllegalStateException("Mahjoob readiness cannot be awaited on the main thread");
		}
		if (state == State.READY) {
			return true;
		}
		if (state != State.LOADING) {
			return false;
		}
		try {
			return readiness.await(timeoutMs, TimeUnit.MILLISECONDS) && state == State.READY;
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	public MahjoobGuard getGuardIfReady() {
		return state == State.READY ? guard : null;
	}

	public Decision check(Bitmap bitmap) {
		return check(bitmap, null);
	}

	public Decision check(Bitmap bitmap, String cacheKey) {
		if (Looper.myLooper() == Looper.getMainLooper()) {
			throw new IllegalStateException("Mahjoob inference cannot run on the main thread");
		}
		synchronized (inferenceLock) {
			MahjoobGuard activeGuard = getGuardIfReady();
			if (activeGuard == null) {
				return null;
			}
			return cacheKey == null ? activeGuard.check(bitmap) : activeGuard.check(bitmap, cacheKey);
		}
	}

	public State getState() {
		return state;
	}

	public Throwable getInitializationError() {
		return initializationError;
	}

	private void loadGuard(TargetGender target, boolean strict, long version) {
		MahjoobGuard loadedGuard = null;
		Throwable error = null;
		State finalState = null;
		synchronized (inferenceLock) {
			MahjoobGuard oldGuard;
			synchronized (lock) {
				if (state == State.CLOSED || version != configurationVersion) {
					return;
				}
				oldGuard = guard;
				guard = null;
			}
			if (oldGuard != null) {
				oldGuard.close();
			}
			try {
				MahjoobOptions options = new MahjoobOptions.Builder(target)
						.strict(strict)
						.blockThreshold(0.2)
						.timeoutMs(15000)
						.maxSide(1280)
						.assetDir("mahjoob")
						.build();
				loadedGuard = MahjoobGuard.create(appContext, options);
			} catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
				error = failure;
			}
			synchronized (lock) {
				if (state == State.CLOSED || version != configurationVersion) {
					if (loadedGuard != null) {
						loadedGuard.close();
					}
					return;
				}
				guard = loadedGuard;
				initializationError = error;
				state = loadedGuard == null ? State.FAILED : State.READY;
				finalState = state;
				readiness.countDown();
			}
		}
		notifyListener(finalState);
	}

	private void notifyListener(State newState) {
		WeakReference<Listener> listenerReference = listener;
		mainHandler.post(() -> {
			Listener current = listenerReference.get();
			if (current != null) {
				current.onStateChanged(newState);
			}
		});
	}

	public void close() {
		MahjoobGuard oldGuard;
		synchronized (lock) {
			if (state == State.CLOSED) {
				return;
			}
			state = State.CLOSED;
			readiness.countDown();
			oldGuard = guard;
			guard = null;
		}
		executor.shutdownNow();
		if (oldGuard != null) {
			synchronized (inferenceLock) {
				oldGuard.close();
			}
		}
	}

	static void closeProcessInstance() {
		MahjoobManager current = instance;
		if (current != null) {
			current.close();
		}
	}
}