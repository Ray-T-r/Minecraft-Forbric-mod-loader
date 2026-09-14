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

package net.forbric.kernel.boot;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

import net.forbric.kernel.util.ForbricLog;

/**
 * Runs a NeoForge {@code DeferredWorkQueue} on the thread NeoForge runs it on.
 *
 * <p><b>Which thread runs the deferred work is part of the contract</b>, and the kernel got it wrong by running
 * it on the caller's. NeoForge dispatches every parallel phase through
 * {@code ModLoader.dispatchParallelEvent}, whose tail is
 * {@code runInitTask(name, ModWorkManager.syncExecutor(), periodicTask, queue::runTasks)} — and
 * {@code ModWorkManager.syncExecutor()} is {@code Executors.newSingleThreadExecutor}, a daemon thread called
 * {@code modloading-sync-worker}. So NeoForge's deferred tasks do <b>not</b> run on the render thread; the render
 * thread is parked waiting for them.
 *
 * <p>That distinction is load-bearing for any mod whose deferred task calls {@code Minecraft.execute}.
 * {@code BlockableEventLoop.execute} runs a task INLINE when it is already on the game thread and QUEUES it
 * otherwise — and {@code Minecraft.gameThread} is assigned early in {@code Minecraft.<init>}, long before client
 * mod loading. Firing the queue on the render thread therefore turns "run this in the game loop" into "run this
 * right now", inside the constructor.
 *
 * <p>What that cost: {@code ClientModLoader.finish()} — where the kernel anchors NeoForge client setup, because
 * it is where NeoForge does it too — sits ~70 bytes BEFORE {@code resourceManager.createReload(...)} in
 * {@code Minecraft.<init>}. A task that meant to run in the game loop instead ran while
 * {@code ReloadableResourceManager} still held its empty placeholder, so every {@code getResource} came back
 * empty. Xaero's World Map does exactly this: its client setup enqueues work that calls
 * {@code Minecraft.getInstance().execute(this::loadLaterClientRender)}, which reads
 * {@code xaeroworldmap:vanilla_states.dat} off the resource manager and calls {@code Optional.get()} on it —
 * {@code NoSuchElementException}, and the mod's own crash handler took the client down on the first tick.
 *
 * <p>Borrowing NeoForge's executor rather than making our own is deliberate: same thread name, same daemon flag,
 * same single-thread serialisation between phases, and the context classloader a mod sees is the one it would
 * see under NeoForge.
 */
final class NeoDeferredWork {
	private static final String WORK_MANAGER = "net.neoforged.fml.ModWorkManager";

	private NeoDeferredWork() {
	}

	/** A runnable that may throw — reflection's {@code invoke} does. */
	interface ThrowingRunnable {
		void run() throws Throwable;
	}

	/**
	 * NeoForge's own {@code modloading-sync-worker} executor, or {@code null} when this carrier has no
	 * {@code ModWorkManager} — in which case the caller runs the work itself, which is what the kernel did before
	 * and is still better than not running it.
	 */
	static Executor syncExecutor(ClassLoader cl) {
		try {
			Class<?> workManager = Class.forName(WORK_MANAGER, false, cl);
			Object executor = workManager.getMethod("syncExecutor").invoke(null);
			return executor instanceof Executor sync ? sync : null;
		} catch (Throwable absent) {
			ForbricLog.debug("[Forbric/Lifecycle] no usable %s.syncExecutor() on this carrier — deferred work will "
					+ "run on the calling thread, so a mod that defers to the game loop runs inline instead: %s",
					WORK_MANAGER, absent);
			return null;
		}
	}

	/**
	 * Runs {@code tasks} on {@code sync} and blocks until it is done, rethrowing whatever it threw unwrapped so a
	 * caller's {@code catch} sees the mod's own failure and not a {@link CompletionException} around it.
	 *
	 * <p>A {@code null} executor runs {@code tasks} on the calling thread.
	 */
	static void runBlocking(Executor sync, ThrowingRunnable tasks) throws Throwable {
		if (sync == null) {
			tasks.run();
			return;
		}
		try {
			CompletableFuture.runAsync(() -> {
				try {
					tasks.run();
				} catch (RuntimeException | Error direct) {
					throw direct;
				} catch (Throwable checked) {
					throw new CompletionException(checked);
				}
			}, sync).join();
		} catch (CompletionException wrapped) {
			throw wrapped.getCause() != null ? wrapped.getCause() : wrapped;
		}
	}
}
