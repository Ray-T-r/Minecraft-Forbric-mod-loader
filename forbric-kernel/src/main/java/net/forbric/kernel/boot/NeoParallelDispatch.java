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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Function;

import net.forbric.kernel.util.Caught;

/**
 * Runs one NeoForge mod-loading phase the way NeoForge's {@code ModLoader.dispatchParallelTask} runs it: every mod on
 * NeoForge's own {@code modloading-worker} pool, each mod as soon as the mods it waits for have finished, the caller
 * waiting for all of them.
 *
 * <p><b>What native NeoForge 26.2.0.93 does</b> (FML 11.0.16, decompiled; measured on the native .93 server with four
 * probe mods, Stage 4 C5 lane THR): {@code ModLoader.constructMods} and every {@code dispatchParallelEvent} phase — mod
 * construction with its {@code @EventBusSubscriber} classes and {@code FMLConstructModEvent}, common setup, the sided
 * setup, both IMC phases and load complete — hand each container to {@code ModWorkManager.parallelExecutor()}, a
 * {@code ForkJoinPool} of {@code maxThreads} (processor count by default) whose threads are all named
 * {@code modloading-worker-0}. A mod's task waits ({@code CompletableFuture.allOf(...).handleAsync}) for the tasks of
 * the mods {@code LoadingModList.getDependencies} names, sets {@code ModLoadingContext.get().setActiveContainer} on its
 * worker, and clears it after. Mods with nothing between them run at the same time (three 300 ms constructors took
 * 310 ms together). The caller only waits; the deferred work runs after on {@code modloading-sync-worker}
 * ({@link NeoDeferredWork}).
 *
 * <p>The kernel ran all of it on the caller's thread, one mod after another: {@code main} on a server, the render thread
 * on a client. A mod could tell: {@code Thread.currentThread()} was the game's own thread, {@code ModLoadingContext.get()}
 * was the same object for every mod, and on a client a setup listener calling {@code Minecraft.execute} ran its task
 * inline inside {@code Minecraft.<init>} instead of queueing it — the shape {@link NeoDeferredWork} documents for the
 * deferred queue (Xaero's World Map).
 *
 * <p><b>The order kept.</b> A mod waits for every mod the kernel's dependency order puts before it
 * ({@link ModConstructionOrder#edges}): its {@code BEFORE}/{@code AFTER} declarations, as natively, and also every mod it
 * requires, which native NeoForge does not wait for. Every schedule this allows is one native NeoForge can produce too.
 * Edges through a mod this phase does not dispatch (a Fabric mod, a library with nothing to run) are followed through it.
 * A mod only ever waits for a mod listed BEFORE it, so a declared cycle, which the dependency order already reports and
 * leaves in the found order, cannot deadlock the phase. With dependency ordering off ({@code -Dforbric.modOrder=name})
 * the mods run one after another in the given order, on the same workers.
 *
 * <p><b>What it does not change.</b> A mod's failure is not handled here: the body's throw is handed back to the caller,
 * per mod and in the given order, and the caller decides what a failure means exactly as it did when it ran the body
 * itself. A mod whose dependency failed still runs, as before (natively it is skipped, because natively the boot stops).
 *
 * <p>With no {@code ModWorkManager} on the carrier the phase runs on the caller's thread in the given order, which is
 * what the kernel did before.
 */
public final class NeoParallelDispatch {
	private static final String WORK_MANAGER = "net.neoforged.fml.ModWorkManager";

	private NeoParallelDispatch() {
	}

	/** One mod's part of a phase. It runs on a worker; whatever it throws is handed back to the caller. */
	public interface Body<T> {
		void run(T item) throws Throwable;
	}

	/**
	 * NeoForge's own {@code modloading-worker} pool ({@code ModWorkManager.parallelExecutor()}), or {@code null} when
	 * this carrier has none — then {@link #run} runs on the caller's thread.
	 */
	public static Executor parallelExecutor(ClassLoader cl) {
		try {
			Class<?> workManager = Class.forName(WORK_MANAGER, false, cl);
			Object executor = workManager.getMethod("parallelExecutor").invoke(null);
			return executor instanceof Executor parallel ? parallel : null;
		} catch (ClassNotFoundException absent) {
			Caught.expected(absent);
			return null;
		} catch (Throwable unusable) {
			Caught.warn(unusable, "[Forbric/Lifecycle] no usable %s.parallelExecutor() on this carrier — NeoForge mods' "
					+ "construction and setup run on the calling thread, one after another", WORK_MANAGER);
			return null;
		}
	}

	/**
	 * Runs {@code body} for every item and returns once all have finished.
	 *
	 * <p>Items may share an id (two {@code @Mod} classes of one mod): they run one after another in the given order, and a
	 * mod waiting for that id waits for the last of them.
	 *
	 * @param executor where the bodies run; {@code null} runs them here, in order
	 * @param items    the mods, in the order the phase walks them (dependency order)
	 * @param after    {@code after.get(x)}: the ids that must not start before {@code x} has finished
	 *                 ({@link ModConstructionOrder#edges}); {@code null} runs the items one after another
	 * @return what each item's body threw, or {@code null} when it completed, indexed like {@code items}
	 */
	public static <T> List<Throwable> run(Executor executor, List<T> items, Function<T, String> idOf,
			Map<String, Set<String>> after, Body<T> body) {
		// No pool: the same schedule on this thread. A task handed to a direct executor runs as it is scheduled, and
		// everything it can wait for was scheduled, so has run, before it.
		Executor where = executor != null ? executor : Runnable::run;
		List<String> ids = new ArrayList<>(items.size());
		for (T item : items) ids.add(idOf.apply(item));
		Map<String, Set<String>> waits = after == null ? null : waitsFor(ids, after);

		Map<String, CompletableFuture<Void>> latest = new HashMap<>();
		List<CompletableFuture<Void>> all = new ArrayList<>(items.size());
		CompletableFuture<Void> previous = CompletableFuture.completedFuture(null);
		for (int i = 0; i < items.size(); i++) {
			T item = items.get(i);
			String id = ids.get(i);
			List<CompletableFuture<Void>> before = new ArrayList<>();
			if (waits == null) {
				before.add(previous);
			} else {
				// Only mods already scheduled, i.e. listed earlier: an edge to a later one is a cycle's, and waiting
				// for it would never end.
				for (String dependency : waits.getOrDefault(id, Set.of())) {
					CompletableFuture<Void> done = latest.get(dependency);
					if (done != null) before.add(done);
				}
				CompletableFuture<Void> sameMod = latest.get(id);
				if (sameMod != null) before.add(sameMod);
			}
			// A failed dependency does not stop this one (handle, not then): what a failure means is the caller's.
			// The body's own throw completes this task exceptionally; it is read back below, per item.
			CompletableFuture<Void> task = CompletableFuture.allOf(before.toArray(new CompletableFuture[0]))
					.handleAsync((ignored, dependencyFailed) -> {
						try {
							body.run(item);
						} catch (RuntimeException | Error unchecked) {
							throw unchecked;
						} catch (Throwable checked) {
							throw new CompletionException(checked);
						}
						return null;
					}, where);
			latest.put(id, task);
			all.add(task);
			previous = task;
		}
		CompletableFuture.allOf(all.toArray(new CompletableFuture[0])).handle((ignored, someFailed) -> null).join();

		List<Throwable> failures = new ArrayList<>(items.size());
		for (CompletableFuture<Void> task : all) {
			Throwable failed = task.handle((ignored, thrown) -> thrown).join();
			Throwable cause = failed instanceof CompletionException wrapped && wrapped.getCause() != null
					? wrapped.getCause() : failed;
			// Not a mod's failure: the pool refused the work.
			if (cause instanceof java.util.concurrent.RejectedExecutionException refused) throw refused;
			failures.add(cause);
		}
		return failures;
	}

	/**
	 * Runs {@code body} for the one {@code item} on {@code executor}, waits for it, and throws what it threw, as if it had
	 * run here: for a step NeoForge runs on a worker that has no siblings to wait by (NeoForge's own mod).
	 */
	public static <T> void runOne(Executor executor, T item, String id, Body<T> body) throws Throwable {
		Throwable threw = run(executor, List.of(item), ignored -> id, null, body).get(0);
		if (threw != null) throw threw;
	}

	/**
	 * For each id, the ids among {@code ids} it waits for: the nearest ones upstream of it in {@code after}, looking
	 * through every node that is not among {@code ids}. Its own id never.
	 */
	static Map<String, Set<String>> waitsFor(List<String> ids, Map<String, Set<String>> after) {
		Set<String> dispatched = new HashSet<>(ids);
		Map<String, Set<String>> upstream = new HashMap<>();
		for (Map.Entry<String, Set<String>> edge : after.entrySet()) {
			for (String next : edge.getValue()) {
				upstream.computeIfAbsent(next, k -> new LinkedHashSet<>()).add(edge.getKey());
			}
		}
		Map<String, Set<String>> waits = new LinkedHashMap<>();
		for (String id : ids) {
			Set<String> found = new LinkedHashSet<>();
			Set<String> seen = new HashSet<>(Set.of(id));
			Deque<String> todo = new ArrayDeque<>(upstream.getOrDefault(id, Set.of()));
			while (!todo.isEmpty()) {
				String node = todo.poll();
				if (!seen.add(node)) continue;
				if (dispatched.contains(node)) {
					found.add(node);
				} else {
					todo.addAll(upstream.getOrDefault(node, Set.of()));
				}
			}
			waits.put(id, found);
		}
		return waits;
	}
}
