/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.interop;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import net.forbric.kernel.util.ForbricLog;

/**
 * A file watch's handler that runs only for a change made after the watch was registered.
 *
 * <p>night-config's {@code FileWatcher} watches the DIRECTORY and hands each event to the watch registered for that
 * file name. Where a directory is already watched for another file, a file created in it afterwards — a config tracker
 * writing a new config, then registering its watch on it — reaches the new watch as a change: the event (or, on a
 * polling watch service, the next directory scan) describes the creation, which happened before the watch existed.
 * The handler then "reloads" a file nobody touched. In the merged game the carriers' own configs sit in {@code config/}
 * and are watched from startup, so the first server start of a Fabric mod's config through the config port fired a
 * Reloading event two seconds after its Loading event, every time; natively nothing else watched that directory and it
 * never fired.
 *
 * <p>So the state of the file (existence, size, modification time, content digest) is taken when the watch is
 * registered, and an event that finds the file exactly so is a stale report about something before the watch: the
 * handler is not run. Any real change — content, size or time — runs it, and becomes the new baseline.
 */
public final class StaleWatchGuard implements Runnable {
	private static final AtomicBoolean REPORTED = new AtomicBoolean();
	private final Path file;
	private final Runnable handler;
	private byte[] baseline;

	private StaleWatchGuard(Path file, Runnable handler) {
		this.file = file;
		this.handler = handler;
		this.baseline = state(file);
	}

	/** {@code handler}, run only for a change to {@code file} made after this call. */
	public static Runnable guard(Path file, Runnable handler) {
		if (file == null || handler == null || "off".equalsIgnoreCase(System.getProperty("forbric.staleWatchGuard", "on"))) return handler;
		return new StaleWatchGuard(file, handler);
	}

	/** The {@code File} overload's form of {@link #guard(Path, Runnable)}. */
	public static Runnable guard(File file, Runnable handler) {
		return file == null ? handler : guard(file.toPath(), handler);
	}

	@Override public void run() {
		byte[] now = state(file);
		synchronized (this) {
			if (Arrays.equals(now, baseline)) {
				if (REPORTED.compareAndSet(false, true)) {
					ForbricLog.info("[Forbric/Config] a change event for %s found it exactly as it was when its watch was registered "
							+ "— a report of its own creation in an already-watched directory, not a change; its handler was not run",
							file.getFileName());
				}
				return;
			}
			baseline = now;
		}
		handler.run();
	}

	/** Existence, size, modification time and content digest, as one comparable value. */
	static byte[] state(Path file) {
		try {
			if (!Files.isRegularFile(file)) return new byte[] { 0 };
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update(Long.toString(Files.size(file)).getBytes());
			digest.update(Long.toString(Files.getLastModifiedTime(file).toMillis()).getBytes());
			digest.update(Files.readAllBytes(file));
			return digest.digest();
		} catch (IOException | NoSuchAlgorithmException unreadable) {
			// Unreadable now: never equal to a baseline, so the handler runs as it would have.
			return new byte[] { 1, (byte) System.identityHashCode(unreadable) };
		}
	}

	@Override public String toString() {
		return "StaleWatchGuard[" + file + " -> " + handler + "]";
	}
}
