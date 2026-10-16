/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.interop;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.tools.ToolProvider;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The exit guard ends a JVM that only parked non-daemon threads hold open, whatever those threads are.
 *
 * <p>The sample here is a worker pool under names of its own with the classic lost wakeup: the worker reads "nothing
 * queued, still running" outside its monitor, the stop flips the flag and notifies in between, and the worker then
 * parks on the monitor for good. That leaves exactly what a mod's unstopped pool leaves at exit — a non-daemon thread
 * in {@code WAITING} with nothing running that could wake it — which natively holds a client open until vanilla's
 * shutdown watchdog crashes it and a dedicated server open forever. The guard reads only thread states, never a class,
 * field or method of the pool, so the real Flywheel pool in Create Fly (run unmodified below) is the same case.
 */
class ExitGuardParkedOrphansTest {
	@TempDir Path root;

	/** A pool written the racy way: predicate checked outside the monitor, then a bare wait on it. */
	static final class Loom {
		final AtomicBoolean spinning = new AtomicBoolean(true);
		final Deque<Runnable> bobbins = new ArrayDeque<>();
		final Object bell = new Object();
		final List<Thread> shuttles = new ArrayList<>();

		/** {@code beforePark} runs between the worker's predicate check and its park: the race window, held open. */
		void start(int count, Runnable beforePark) {
			for (int i = 0; i < count; i++) {
				Thread shuttle = new Thread(() -> {
					while (spinning.get()) {
						Runnable next;
						synchronized (bobbins) { next = bobbins.pollFirst(); }
						if (next != null) { next.run(); continue; }
						beforePark.run();
						synchronized (bell) {
							try { bell.wait(); } catch (InterruptedException ignored) { return; }
						}
					}
				}, "loom-shuttle-" + i);
				shuttle.setDaemon(false);
				shuttles.add(shuttle);
				shuttle.start();
			}
		}

		void stop() {
			spinning.set(false);
			synchronized (bell) { bell.notifyAll(); }
		}

		List<Thread> alive() {
			return shuttles.stream().filter(Thread::isAlive).toList();
		}

		void release() throws InterruptedException {
			for (Thread t : shuttles) t.interrupt();
			for (Thread t : shuttles) t.join(5_000);
		}
	}

	private static void awaitState(Thread t, Thread.State state) throws InterruptedException {
		long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (t.getState() != state) {
			assertTrue(System.nanoTime() < until, t.getName() + " never reached " + state + " (is " + t.getState() + ")");
			Thread.sleep(5);
		}
	}

	private static Thread endedThread() throws InterruptedException {
		Thread exiting = new Thread(() -> { }, "exiting-side");
		exiting.start();
		exiting.join();
		return exiting;
	}

	/** The lost wakeup, deterministically: the stop lands inside the window between the check and the park. */
	private static Loom loomThatMissedItsStop() throws InterruptedException {
		Loom loom = new Loom();
		CountDownLatch inWindow = new CountDownLatch(1), stopped = new CountDownLatch(1);
		loom.start(1, () -> {
			inWindow.countDown();
			try { stopped.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
		});
		inWindow.await();
		loom.stop();
		stopped.countDown();
		awaitState(loom.shuttles.getFirst(), Thread.State.WAITING);
		return loom;
	}

	@Test void aWorkerThatMissedItsLastNotificationIsEndedOnceTheExitingThreadHasEnded() throws Exception {
		Loom loom = loomThatMissedItsStop();
		try {
			// The control: nothing will ever wake it — it is still parked a while after the stop.
			Thread.sleep(200);
			assertEquals(Thread.State.WAITING, loom.shuttles.getFirst().getState());
			AtomicInteger exits = new AtomicInteger();
			var outcome = ClientShutdown.runExitGuard(getClass().getClassLoader(), endedThread(), loom::alive, 5_000, 20,
					exits::incrementAndGet);
			assertEquals(ClientShutdown.GuardOutcome.ENDED_PARKED_ORPHANS, outcome);
			assertEquals(1, exits.get(), "the JVM is ended exactly once");
		} finally {
			loom.release();
		}
	}

	@Test void whileTheExitingThreadIsStillAliveTheGuardDoesNotEndTheJvmEarly() throws Exception {
		Loom loom = loomThatMissedItsStop();
		CountDownLatch release = new CountDownLatch(1);
		Thread exiting = new Thread(() -> { try { release.await(); } catch (InterruptedException ignored) { } }, "exiting-side");
		exiting.start();
		try {
			long started = System.nanoTime();
			AtomicInteger exits = new AtomicInteger();
			var outcome = ClientShutdown.runExitGuard(getClass().getClassLoader(), exiting, loom::alive, 800, 20,
					exits::incrementAndGet);
			long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
			// Parked-only at the deadline still ends it, but never before the deadline while the game is exiting.
			assertEquals(ClientShutdown.GuardOutcome.ENDED_PARKED_ORPHANS, outcome);
			assertTrue(elapsed >= 750, "ended after " + elapsed + " ms, before the deadline");
			assertEquals(1, exits.get());
		} finally {
			release.countDown();
			exiting.join();
			loom.release();
		}
	}

	@Test void aRunningThreadIsNeverEndedAndTheGuardGivesUpAtTheDeadline() throws Exception {
		AtomicBoolean work = new AtomicBoolean(true);
		Thread busy = new Thread(() -> { while (work.get()) Thread.onSpinWait(); }, "busy-writer");
		busy.start();
		try {
			awaitState(busy, Thread.State.RUNNABLE);
			AtomicInteger exits = new AtomicInteger();
			var outcome = ClientShutdown.runExitGuard(getClass().getClassLoader(), endedThread(), () -> List.of(busy), 500, 20,
					exits::incrementAndGet);
			assertEquals(ClientShutdown.GuardOutcome.GAVE_UP, outcome);
			assertEquals(0, exits.get(), "a thread that is still running is waited for, never cut off");
		} finally {
			work.set(false);
			busy.join();
		}
	}

	@Test void aParkedThreadThatIsWokenAndEndsLetsTheJvmEndOnItsOwn() throws Exception {
		Loom loom = new Loom();
		loom.start(1, () -> { });
		awaitState(loom.shuttles.getFirst(), Thread.State.WAITING);
		// Its stop arrives while the guard is still watching: the worker wakes and ends, nothing is cut off.
		Thread stopper = new Thread(() -> {
			try { Thread.sleep(60); } catch (InterruptedException ignored) { }
			loom.stop();
		}, "late-stop");
		stopper.setDaemon(true);
		stopper.start();
		try {
			AtomicInteger exits = new AtomicInteger();
			var outcome = ClientShutdown.runExitGuard(getClass().getClassLoader(), endedThread(), loom::alive, 5_000, 100,
					exits::incrementAndGet);
			assertEquals(ClientShutdown.GuardOutcome.ALL_ENDED, outcome);
			assertEquals(0, exits.get());
		} finally {
			loom.release();
		}
	}

	/** What the game does: the exiting thread calls the hook and ends; only the orphan is left. */
	public static final class OrphanMain {
		public static void main(String[] args) throws Exception {
			Object lock = new Object();
			Thread orphan = new Thread(() -> {
				synchronized (lock) { try { lock.wait(); } catch (InterruptedException ignored) { } }
			}, "orphan-worker");
			orphan.start();
			Thread exiting = new Thread(() -> ClientShutdown.stopLeakedBackgroundExecutors(OrphanMain.class.getClassLoader()),
					"exiting-side");
			exiting.start();
			exiting.join();
			System.out.println("main returned");
		}
	}

	private Process orphanJvm(String... properties) throws Exception {
		List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString()));
		command.addAll(List.of(properties));
		command.addAll(List.of("-cp", System.getProperty("java.class.path"), OrphanMain.class.getName()));
		return new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(root.resolve("child.log").toFile()).start();
	}

	@Test void aJvmHeldOpenOnlyByAParkedOrphanEndsOnItsOwnWithStatusZero() throws Exception {
		Process child = orphanJvm("-Dforbric.exitGuardIdlePolls=3");
		try {
			assertTrue(child.waitFor(20, TimeUnit.SECONDS), "the JVM did not end: " + Files.readString(root.resolve("child.log")));
			assertEquals(0, child.exitValue(), Files.readString(root.resolve("child.log")));
			String log = Files.readString(root.resolve("child.log"));
			assertTrue(log.contains("parked with nothing running to wake them"), log);
			assertTrue(log.contains("orphan-worker"), log);
		} finally {
			child.destroyForcibly();
		}
		// The control: with the halt switched off the same JVM is still held open by its orphan.
		Process held = orphanJvm("-Dforbric.exitGuardHalt=false", "-Dforbric.exitGuardMillis=60000");
		try {
			assertFalse(held.waitFor(4, TimeUnit.SECONDS), "with -Dforbric.exitGuardHalt=false the orphan must hold the JVM open");
		} finally {
			held.destroyForcibly();
			held.waitFor(10, TimeUnit.SECONDS);
		}
	}

	/** Create Fly's own Flywheel pool, unmodified: its workers parked after their last task are the same case. */
	@Test void realFlywheelWorkersLeftRunningAreEndedByTheGuard() throws Exception {
		Path jar = Path.of(System.getProperty("forbric.createFlyJar", "build/compat-inputs/create-fly/create-fly.jar"));
		TestFixtures.require(Fixture.THIRD_PARTY, Files.isRegularFile(jar), "Create Fly jar absent: " + jar);
		String prefix = "com/zurrtum/create/client/flywheel/impl/";
		Map<String, String> sources = Map.of(
				"com/google/common/base/Preconditions.java", "package com.google.common.base; public class Preconditions {public static void checkArgument(boolean ok,Object reason){if(!ok)throw new IllegalArgumentException(String.valueOf(reason));}}",
				"net/minecraft/util/Mth.java", "package net.minecraft.util; public class Mth {public static int clamp(int value,int min,int max){return Math.max(min,Math.min(value,max));}}",
				"org/slf4j/Logger.java", "package org.slf4j; public interface Logger {default void info(String s){} default void info(String s,Object o){} default void error(String s){} default void error(String s,Object o){} default void error(String s,Throwable t){} }",
				prefix + "FlwImpl.java", "package com.zurrtum.create.client.flywheel.impl; public class FlwImpl {public static final org.slf4j.Logger LOGGER=new org.slf4j.Logger(){};}");
		List<String> args = new ArrayList<>(List.of("--release", "21", "-d", root.toString()));
		for (var source : sources.entrySet()) {
			Path file = root.resolve(source.getKey());
			Files.createDirectories(file.getParent());
			Files.writeString(file, source.getValue());
			args.add(file.toString());
		}
		assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
		try (var loader = new URLClassLoader(new URL[] { root.toUri().toURL(), jar.toUri().toURL() }, ClassLoader.getPlatformClassLoader())) {
			Class<?> pool = loader.loadClass((prefix + "task/ParallelTaskExecutor").replace('/', '.'));
			Object executor = pool.getConstructor(String.class, int.class).newInstance("Exit guard sample", 2);
			var threadsField = pool.getDeclaredField("threads");
			threadsField.setAccessible(true);
			pool.getMethod("startWorkers").invoke(executor);
			try {
				AtomicInteger completed = new AtomicInteger();
				for (int i = 0; i < 50; i++) pool.getMethod("execute", Runnable.class).invoke(executor, (Runnable) completed::incrementAndGet);
				List<Thread> workers = new ArrayList<>();
				for (Object worker : (List<?>) threadsField.get(executor)) workers.add((Thread) worker);
				assertFalse(workers.isEmpty());
				for (Thread worker : workers) {
					assertFalse(worker.isDaemon(), "Flywheel's workers hold the JVM open natively");
					awaitState(worker, Thread.State.WAITING);
				}
				assertEquals(50, completed.get());
				AtomicInteger exits = new AtomicInteger();
				var outcome = ClientShutdown.runExitGuard(getClass().getClassLoader(), endedThread(),
						() -> workers.stream().filter(Thread::isAlive).toList(), 5_000, 20, exits::incrementAndGet);
				assertEquals(ClientShutdown.GuardOutcome.ENDED_PARKED_ORPHANS, outcome);
				assertEquals(1, exits.get());
			} finally {
				if (!((List<?>) threadsField.get(executor)).isEmpty()) pool.getMethod("stopWorkers").invoke(executor);
			}
		}
	}
}
