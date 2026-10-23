/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.interop;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.ForbricLog;

/**
 * Stops the non-daemon background executors the two Forge-family config systems leave running, so the JVM can
 * end: a client at the end of {@code Minecraft.close()} instead of hanging until vanilla's 15-second
 * {@code ClientShutdownWatchdog} files a "Client shutdown from post-main" crash, a dedicated server at the end of
 * {@code onServerExit()} instead of sitting there forever after "Stopping server" (it has no watchdog and no
 * {@code System.exit}). The class keeps its client-era name; the kernel calls it on both sides.
 *
 * <p>Both leaks are night-config's {@code FileWatcher}: each of its per-filesystem watchers owns a
 * {@code Executors.newScheduledThreadPool(1)} built with no thread factory — a non-daemon worker parked forever on
 * {@code DelayedWorkQueue.take}, so the JVM cannot exit while one exists. FML's own shutdown would close them; the
 * kernel drives both loaders' lifecycles itself and does not run that path. There are TWO owners, and until this
 * class knew both it only ever stopped one:
 * <ul>
 * <li>NeoForge's {@code ConfigTracker} watches through night-config's {@code FileWatcher.defaultInstance()};</li>
 * <li>MinecraftForge's {@code ConfigFileTypeHandler} has three static handlers (CLIENT, COMMON, SERVER), each
 *     lazily building a PRIVATE {@code new FileWatcher()} in {@code getWatcher()} and dropping it in
 *     {@code stopWatcher()} — which Forge calls only for SERVER on disconnect and for all three from
 *     {@code ConfigTracker.forceUnload()}, which nobody calls here.</li>
 * </ul>
 *
 * <p>And a stop is not the end of it: night-config's {@code defaultInstance()} builds a NEW watcher when the old
 * one is stopped, and Forge's {@code getWatcher()} does the same once its field is null. A config (re)load that
 * lands after the stop — the client's disconnect is still being processed on a Netty thread while the render thread
 * is already in {@code close()} — quietly brings a fresh non-daemon executor into a JVM that is trying to exit.
 * That was the one-in-three hang. So after the first sweep a daemon guard keeps sweeping for a few seconds, until
 * no non-daemon thread but the exiting main one is left, and says in the log what it had to stop late; if the
 * deadline passes with threads still alive it names them, so the next hang is diagnosable from the log rather than
 * only from the watchdog's crash report.
 *
 * <p>Config watchers are the leak the kernel itself causes, by not running FML's shutdown; a mod can leave the same
 * thing behind on its own — a worker pool it never stops (Create Fly's Flywheel pool is one: natively its client ends
 * with vanilla's watchdog crash). The guard does not know or ask what such threads are. Once the side's own exiting
 * thread has ended and every non-daemon thread still alive is parked without a timeout, with nothing running that
 * could wake it, it ends the JVM ({@link #runExitGuard}).
 *
 * <p>Reflection-only (no compile/runtime dep on night-config or either loader); every piece is a no-op where its
 * class is absent, and everything is idempotent.
 */
public final class ClientShutdown {
	private static final String NIGHTCONFIG_WATCHER = "com.electronwill.nightconfig.core.file.FileWatcher";
	private static final String FORGE_FILE_TYPE_HANDLER = "net.minecraftforge.fml.config.ConfigFileTypeHandler";
	private static final long GUARD_DEADLINE_MS = Long.getLong("forbric.exitGuardMillis", 8_000L);
	private static final long GUARD_PERIOD_MS = 200L;
	private static final long STOP_WAIT_MS = Long.getLong("forbric.watcherStopMillis", 1_500L);
	/**
	 * How many consecutive polls the leaked threads must stay parked, every one of them, after the side's own exiting
	 * thread has ended, before the guard ends the JVM: one second at the usual period. A thread something is about to
	 * wake shows up as RUNNABLE, or the set changes, within that window.
	 */
	static final int IDLE_POLLS = Integer.getInteger("forbric.exitGuardIdlePolls", 5);
	/**
	 * When the ONLY non-daemon threads left are parked ones nothing that is still running can reach — an orphan
	 * executor from the stop-versus-addWatch race, a mod's worker pool nobody stops, a worker that missed its last
	 * notification — end the JVM ourselves instead of letting vanilla's watchdog crash it (client) or the process sit
	 * there forever (dedicated server). {@code -Dforbric.exitGuardHalt=false} keeps the hang for diagnosis.
	 */
	private static final boolean HALT_ON_ORPHAN_WORKERS = !"false".equals(System.getProperty("forbric.exitGuardHalt"));

	private static volatile boolean ran;
	/** Watchers already stopped, so a repeat sweep can tell "re-created" from "still the same one". */
	private static final Map<Object, Boolean> STOPPED = new IdentityHashMap<>();

	private ClientShutdown() {
	}

	/**
	 * Called at each side's end of life: every return of {@code Minecraft.close()} on the client (main thread, before
	 * {@code startShutdownWatchdog}) and of {@code DedicatedServer.onServerExit()} on the server (server thread, the
	 * last thing {@code MinecraftServer.run} does — a dedicated server has no {@code System.exit} anywhere, so a
	 * leaked worker there does not crash the process, it keeps it alive forever after "Stopping server").
	 */
	public static void stopLeakedBackgroundExecutors(ClassLoader cl) {
		if (ran) return;
		ran = true;
		List<String> stopped = sweep(cl);
		ForbricLog.info("[Forbric/Shutdown] stopped %d config file-watcher(s) at exit so the JVM can end: %s",
				stopped.size(), stopped);
		startExitGuard(cl, Thread.currentThread());
	}


	/**
	 * One pass over every known watcher owner: stops whatever is running and returns a description of each watcher
	 * it stopped. Package-visible so a test can drive the guard's loop without waiting on wall-clock time.
	 */
	static List<String> sweep(ClassLoader cl) {
		List<String> stopped = new ArrayList<>();
		synchronized (STOPPED) {
			stopNightconfigDefaultInstance(cl, stopped);
			stopForgeHandlerWatchers(cl, stopped);
		}
		return stopped;
	}

	/** NeoForge's side: night-config's process-wide default watcher, if one was ever created and is running. */
	private static void stopNightconfigDefaultInstance(ClassLoader cl, List<String> stopped) {
		Class<?> fileWatcher = load(cl, NIGHTCONFIG_WATCHER);
		if (fileWatcher == null) return;
		try {
			Object instance = null;
			for (Field f : fileWatcher.getDeclaredFields()) {
				if (Modifier.isStatic(f.getModifiers()) && f.getType() == fileWatcher) {
					f.setAccessible(true);
					instance = f.get(null); // the DEFAULT_INSTANCE holder, whatever its exact name
					break;
				}
			}
			// Never created ⇒ no thread or executor exists ⇒ nothing to leak. Do NOT ask defaultInstance() — it
			// would build one just for us to stop.
			if (instance != null) stopWatcher(fileWatcher, instance, "night-config default instance", stopped);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Shutdown] could not inspect night-config's default file-watcher");
		}
	}

	/** MinecraftForge's side: the private watcher of each of ConfigFileTypeHandler's static handlers. */
	private static void stopForgeHandlerWatchers(ClassLoader cl, List<String> stopped) {
		Class<?> handlerClass = load(cl, FORGE_FILE_TYPE_HANDLER);
		if (handlerClass == null) return;
		Class<?> fileWatcher = load(cl, NIGHTCONFIG_WATCHER);
		if (fileWatcher == null) return;
		try {
			Field watcherField = null;
			for (Field f : handlerClass.getDeclaredFields()) {
				if (!Modifier.isStatic(f.getModifiers()) && f.getType() == fileWatcher) {
					watcherField = f;
					break;
				}
			}
			if (watcherField == null) return;
			watcherField.setAccessible(true);
			for (Field f : handlerClass.getDeclaredFields()) {
				if (!Modifier.isStatic(f.getModifiers()) || f.getType() != handlerClass) continue;
				f.setAccessible(true);
				Object handler = f.get(null);
				if (handler == null) continue;
				Object watcher = watcherField.get(handler);
				if (watcher == null) continue;
				// Forge's own stopWatcher(): stop + null the field, so a later getWatcher() builds afresh instead of
				// using a stopped one (which would throw on addWatch).
				if (stopWatcher(fileWatcher, watcher, "MinecraftForge " + f.getName() + " config handler", stopped)) {
					watcherField.set(handler, null);
				}
			}
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Shutdown] could not inspect MinecraftForge's config file-watchers");
		}
	}

	/**
	 * Stops one watcher unless this class already did; true when it stopped it now. Prefers {@code stopFuture()}
	 * and waits (briefly) for it: the stop is a poison message the watcher thread has to consume before it shuts
	 * its executor down, and {@code close()} should not return until that has happened.
	 */
	private static boolean stopWatcher(Class<?> fileWatcher, Object watcher, String owner, List<String> stopped)
			throws ReflectiveOperationException {
		if (STOPPED.containsKey(watcher)) return false;
		STOPPED.put(watcher, Boolean.TRUE);
		try {
			Method stopFuture = findMethod(fileWatcher, "stopFuture");
			if (stopFuture != null) {
				Object future = stopFuture.invoke(watcher);
				if (future instanceof java.util.concurrent.Future<?> f) {
					try {
						f.get(STOP_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
					} catch (java.util.concurrent.TimeoutException slow) {
						// Not a failure: the watcher thread takes the poison message on its next poll, and the exit guard
						// keeps sweeping until no non-daemon thread is left. A thread that never ends is what it reports.
						Caught.expected(slow);
						ForbricLog.info("[Forbric/Shutdown] %s did not confirm its stop within %d ms; the exit guard keeps "
								+ "watching", owner, STOP_WAIT_MS);
					} catch (java.util.concurrent.ExecutionException | InterruptedException failed) {
						// stopped with an error, or we were interrupted: either way it is no longer ours to wait on
						Caught.warn(failed, "[Forbric/Shutdown] %s stopped with an error, or the wait for it was "
								+ "interrupted", owner);
						if (failed instanceof InterruptedException) Thread.currentThread().interrupt();
					}
				}
			} else {
				fileWatcher.getMethod("stop").invoke(watcher);
			}
		} catch (java.lang.reflect.InvocationTargetException alreadyStopped) {
			// night-config throws IllegalStateException on a watcher stopped by someone else; that is fine.
			Caught.expected(alreadyStopped);
			return false;
		}
		stopped.add(owner + " #" + System.identityHashCode(watcher));
		return true;
	}

	private static Method findMethod(Class<?> owner, String name) {
		try {
			return owner.getMethod(name);
		} catch (NoSuchMethodException absent) {
			Caught.expected(absent);
			return null;
		}
	}

	/**
	 * A daemon thread that repeats the sweep until nothing non-daemon is left (the normal case within a few
	 * hundred milliseconds), or until every non-daemon thread left is parked with nothing running to wake it, or the
	 * deadline passes; it reports either way.
	 */
	private static void startExitGuard(ClassLoader cl, Thread exiting) {
		Thread guard = new Thread(() -> runExitGuard(cl, exiting, ClientShutdown::leakedNonDaemonThreads,
				GUARD_DEADLINE_MS, GUARD_PERIOD_MS, () -> System.exit(0)), "Forbric exit guard");
		guard.setDaemon(true);
		guard.start();
	}

	/** What one run of the exit guard ended with. */
	enum GuardOutcome { ALL_ENDED, ENDED_PARKED_ORPHANS, GAVE_UP }

	/**
	 * The guard's loop, with its inputs passed in so a test can drive it on threads of its own.
	 *
	 * <p>A non-daemon thread a mod started and never stopped keeps the JVM alive after the game's own exiting thread
	 * is gone: natively a client then waits for vanilla's 15-second shutdown watchdog to file a "Client shutdown from
	 * post-main" crash and halt, and a dedicated server never ends at all. When every such thread is
	 * {@link Thread.State#WAITING parked without a timeout} and stays so, and the side's own {@code exiting} thread has
	 * ended, nothing that is still running can wake them — only another non-daemon thread could, and there is none —
	 * so the guard ends the JVM there. What a thread is, whose pool it belongs to or which mod started it plays no
	 * part: an idle executor worker, a pool worker parked on its own monitor and a worker that missed its last
	 * notification are the same case. A thread that is running, blocked on a monitor, or parked with a timeout is
	 * left to finish until the deadline; at the deadline the guard ends the JVM only when every one left is parked
	 * (with or without a timeout), and otherwise names them and leaves the hang to vanilla's watchdog.
	 *
	 * @param exiting the side's exiting thread (the client's render thread, the dedicated server's thread), or null
	 * @param leaked  the non-daemon threads that would hold the JVM open
	 * @param exit    what ends the JVM ({@code System.exit(0)} in the game)
	 */
	static GuardOutcome runExitGuard(ClassLoader cl, Thread exiting, java.util.function.Supplier<List<Thread>> leaked,
			long deadlineMillis, long periodMillis, Runnable exit) {
		long deadline = System.nanoTime() + deadlineMillis * 1_000_000L;
		List<Thread> previous = List.of();
		int parkedPolls = 0;
		while (System.nanoTime() < deadline) {
			List<String> late = sweep(cl);
			if (!late.isEmpty()) {
				ForbricLog.warn("[Forbric/Shutdown] stopped %d config file-watcher(s) re-created AFTER the exit sweep "
						+ "had already stopped the earlier ones — a config was loaded while the game was exiting: %s",
						late.size(), late);
			}
			List<Thread> alive = leaked.get();
			if (alive.isEmpty()) return GuardOutcome.ALL_ENDED;
			boolean parked = (exiting == null || !exiting.isAlive()) && sameThreads(alive, previous)
					&& alive.stream().allMatch(t -> t.getState() == Thread.State.WAITING);
			parkedPolls = parked ? parkedPolls + 1 : 0;
			previous = alive;
			if (parkedPolls >= IDLE_POLLS && HALT_ON_ORPHAN_WORKERS) {
				ForbricLog.info("[Forbric/Shutdown] %d non-daemon thread(s) a mod left behind are parked with nothing "
						+ "running to wake them, and the game's own exiting thread has ended — ending the JVM now "
						+ "instead of leaving it to vanilla's shutdown watchdog:%s", alive.size(), describe(alive));
				exit.run();
				return GuardOutcome.ENDED_PARKED_ORPHANS;
			}
			try {
				Thread.sleep(periodMillis);
			} catch (InterruptedException interrupted) {
				Caught.expected(interrupted);
				Thread.currentThread().interrupt();
				return GuardOutcome.GAVE_UP;
			}
		}
		List<Thread> alive = leaked.get();
		if (alive.isEmpty()) return GuardOutcome.ALL_ENDED;
		boolean onlyParked = alive.stream().allMatch(ClientShutdown::isParked);
		ForbricLog.warn("[Forbric/Shutdown] %d non-daemon thread(s) still alive %d ms after the exit sweep — the JVM "
				+ "cannot end until they do; %s:%s", alive.size(), deadlineMillis,
				onlyParked && HALT_ON_ORPHAN_WORKERS ? "all are parked threads nothing can reach, exiting now"
						: "on a client vanilla's shutdown watchdog will crash it, a dedicated server just never exits",
				describe(alive));
		if (onlyParked && HALT_ON_ORPHAN_WORKERS) {
			exit.run();
			return GuardOutcome.ENDED_PARKED_ORPHANS;
		}
		return GuardOutcome.GAVE_UP;
	}

	/** The same threads, by identity, as the previous poll saw. */
	private static boolean sameThreads(List<Thread> now, List<Thread> before) {
		if (now.size() != before.size()) return false;
		Set<Thread> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		seen.addAll(before);
		return seen.containsAll(now);
	}

	/** Parked in a JDK wait, with or without a timeout: not running, not contending for a monitor. */
	static boolean isParked(Thread t) {
		Thread.State state = t.getState();
		return state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING;
	}

	private static String describe(List<Thread> threads) {
		StringBuilder report = new StringBuilder();
		for (Thread t : threads) {
			report.append("\n  ").append(t.getName()).append(" (").append(t.getState()).append(')');
			StackTraceElement[] frames = t.getStackTrace();
			for (int i = 0; i < Math.min(6, frames.length); i++) report.append("\n      at ").append(frames[i]);
		}
		return report.toString();
	}

	/** Non-daemon threads that would hold the JVM open, excluding the thread that is itself exiting. */
	static List<Thread> leakedNonDaemonThreads() {
		List<Thread> alive = new ArrayList<>();
		Set<Thread> threads = Thread.getAllStackTraces().keySet();
		for (Thread t : threads) {
			if (t.isDaemon() || !t.isAlive() || t == Thread.currentThread()) continue;
			String name = t.getName();
			// The exiting thread of each side, and the JVM's own: they end on their own once we let them.
			if ("main".equals(name) || "DestroyJavaVM".equals(name) || name.startsWith("Render thread")
					|| "Server thread".equals(name)) continue;
			alive.add(t);
		}
		return alive;
	}

	private static Class<?> load(ClassLoader cl, String name) {
		try {
			return Class.forName(name, false, cl);
		} catch (ClassNotFoundException | LinkageError absent) {
			Caught.expected(absent);
			return null;
		}
	}
}
