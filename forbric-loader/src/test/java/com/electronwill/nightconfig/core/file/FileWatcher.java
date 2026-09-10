package com.electronwill.nightconfig.core.file;

/** Test stand-in for night-config's watcher: records stop(), and like the real one refuses a second stop. */
public final class FileWatcher {
	private static volatile FileWatcher DEFAULT_INSTANCE;
	public boolean running = true;
	public int stops;

	public static synchronized FileWatcher defaultInstance() {
		if (DEFAULT_INSTANCE == null || !DEFAULT_INSTANCE.running) DEFAULT_INSTANCE = new FileWatcher();
		return DEFAULT_INSTANCE;
	}

	public static void reset() {
		DEFAULT_INSTANCE = null;
	}

	public static FileWatcher peekDefault() {
		return DEFAULT_INSTANCE;
	}

	public void stop() {
		if (!running) throw new IllegalStateException("already stopped");
		running = false;
		stops++;
	}

	public java.util.concurrent.CompletableFuture<Void> stopFuture() {
		stop();
		return java.util.concurrent.CompletableFuture.completedFuture(null);
	}
}
