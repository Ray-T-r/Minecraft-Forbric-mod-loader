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

package net.forbric.kernel.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.forbric.api.ForeignType;
import net.forbric.kernel.boot.KernelModLoader;
import net.forbric.kernel.boot.NeoDeferredWork;
import net.forbric.kernel.boot.NeoParallelDispatch;
import net.forbric.kernel.boot.DeferredWorkFailures;
import net.forbric.kernel.boot.RegistrationStop;
import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.Reflect;
import net.forbric.api.ModCatalog;
import net.neoforged.fml.DeferredWorkQueue;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLConstructModEvent;
import net.neoforged.fml.event.lifecycle.FMLDedicatedServerSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.neoforged.fml.event.lifecycle.InterModEnqueueEvent;
import net.neoforged.fml.event.lifecycle.InterModProcessEvent;

/**
 * The game side of a NEOFORGE setup phase: post one mod-lifecycle event at every NeoForge mod, then run what
 * they deferred.
 *
 * <p>NeoForge dispatches on an {@code IEventBus} instance held per mod.
 *
 * <p>This takes the phase as a {@link ForeignType} rather than a class-name string, so the
 * constructor for each of the six is named in code and checked.
 *
 * <p>A phase that fails stops the boot, as native NeoForge 26.2.0.93 stops it (D82 "stop like native", extended to every
 * phase by D87): {@code ModLoader.dispatchParallelEvent} hands the event to every mod ({@code ModContainer.acceptEvent},
 * which turns a listener's throw into a {@code ModLoadingException} naming the mod), waits for all of them, and a phase in
 * which any mod threw ends in {@code waitForFuture}'s {@code ModLoadingException} before its deferred work runs; deferred
 * work that throws ends in the same way under "&lt;phase&gt;: Deferred Queue" (javap of ModLoader, ModContainer,
 * DeferredWorkQueue). Until then a mod that threw here was marked DEGRADED and the boot carried on without that part of it.
 */
public final class KernelNeoSetup {
	private KernelNeoSetup() {
	}

	/**
	 * Posts {@code event} at every mod, then drains the queue they filed into. A mod that throws does not stop the others
	 * from getting the event; once all have, the boot stops on what the failing ones threw ({@link RegistrationStop}),
	 * and the phase's deferred work does not run. Deferred work that throws stops the boot the same way.
	 *
	 * @param mods modid → that mod's bus and container
	 * @return how many mods the event reached
	 */
	public static int firePhase(Map<String, KernelModLoader.NeoIdentity> mods, ForeignType event, String label)
			throws Throwable {
		ClassLoader cl = KernelNeoSetup.class.getClassLoader();
		String task = nativeTask(event);
		DeferredWorkQueue queue = new DeferredWorkQueue(task);
		int fired = 0;
		List<Throwable> failures = new ArrayList<>();

		// What an earlier step of the same task recorded (construction: a constructor or a subscriber class that threw),
		// and the mods it belongs to, which natively take no further part in the task. A mod waiting for one of them
		// waits for what that one waits for (NeoParallelDispatch follows an edge through a mod it does not dispatch).
		java.util.Set<String> alreadyFailed = RegistrationStop.pendingMods(task);
		List<Map.Entry<String, KernelModLoader.NeoIdentity>> entries = new ArrayList<>();
		for (Map.Entry<String, KernelModLoader.NeoIdentity> e : mods.entrySet()) {
			if (!alreadyFailed.contains(e.getKey())) entries.add(e);
		}
		// Each mod on NeoForge's own modloading-worker pool, as soon as the mods it waits for have taken the phase, the
		// way ModLoader.dispatchParallelEvent posts it (NeoParallelDispatch). It ran on the caller's thread, one mod
		// after another — on a client the render thread, where a listener's Minecraft.execute ran inline instead of
		// queueing. DeferredWorkQueue takes enqueueWork from several workers at once, as natively.
		List<Throwable> failed = NeoParallelDispatch.run(NeoParallelDispatch.parallelExecutor(cl), entries,
				Map.Entry::getKey, KernelModLoader.dependencyAfter(), e -> {
					// The active container has to be set for the DISPATCH, not just for construction, and on the thread
					// that dispatches: ModLoadingContext is per thread. A setup listener that registers anything through
					// ModLoadingContext.get() reads getActiveContainer(), which with none set falls back to the
					// "minecraft" container and throws "Where is minecraft???!". That killed CreativeCore's and Sound
					// Physics' reload-listener registration outright, and CreativeCore then half-initialised:
					// GuiStyle.mc stayed null ("Could not load default style"), and the next reload re-ran
					// registerDefault and died on 'default' already exists — three failures, one missing line.
					KernelModLoader.setNeoActiveContainer(cl, e.getValue().container());
					try {
						post(event, (ModContainer) e.getValue().container(), queue);
					} finally {
						KernelModLoader.setNeoActiveContainer(cl, null);
					}
				});
		// What each mod's listeners came to, handled here, on the caller's thread and in the order the mods are walked.
		for (int i = 0; i < entries.size(); i++) {
			Throwable perMod = failed.get(i);
			if (perMod == null) {
				fired++;
				continue;
			}
			// NeoForge's ModLoadingException naming the mod (acceptEvent logged "Caught exception during event …"): handed
			// to the stop below once every mod has had the event, as ModLoader.waitForFuture does.
			Caught.expected(perMod);
			failures.add(perMod);
			ModCatalog.mark(entries.get(i).getKey(), ModCatalog.Status.FAILED, "it threw during " + label);
		}
		List<Throwable> all = RegistrationStop.takePending(task);
		all.addAll(failures);
		if (!all.isEmpty()) throw RegistrationStop.propagate(RegistrationStop.stopAll(cl, task, all));

		// Off the caller's thread, because that is where NeoForge runs it and mods can tell the difference — see
		// NeoDeferredWork for the resource-manager window this was landing in.
		//
		// DeferredWorkQueue.runTasks does NOT abort at the first failure — it runs every task, collects each failure as a
		// suppressed cause (and NeoForge's own captureException has already logged "Mod '<id>' encountered an error in a
		// deferred task") and throws at the END: natively that is the "<phase>: Deferred Queue" init task failing, and
		// the boot stops on it.
		try {
			NeoDeferredWork.runBlocking(NeoDeferredWork.syncExecutor(cl), queue::runTasks);
		} catch (Throwable drained) {
			for (String id : DeferredWorkFailures.owners(queue)) {
				ModCatalog.mark(id, ModCatalog.Status.FAILED, "one of its deferred setup tasks threw during " + label);
			}
			throw RegistrationStop.propagate(RegistrationStop.stop(cl, RegistrationStop.deferredQueue(task), Reflect.unwrap(drained)));
		}
		return fired;
	}

	/**
	 * The init task native NeoForge runs {@code kind} as: {@code ModLoader.constructMods}' "Mod Construction", and
	 * {@code CommonModLoader.load}'s "Common setup", "Sided setup", "Enqueue IMC", "Process IMC" and
	 * "Complete loading of %d mods" (javap of 26.2.0.93).
	 */
	static String nativeTask(ForeignType kind) {
		return switch (kind) {
			case FML_CONSTRUCT_MOD_EVENT -> RegistrationStop.CONSTRUCTION;
			case FML_COMMON_SETUP_EVENT -> "Common setup";
			case FML_CLIENT_SETUP_EVENT, FML_DEDICATED_SERVER_SETUP_EVENT -> "Sided setup";
			case INTER_MOD_ENQUEUE_EVENT -> "Enqueue IMC";
			case INTER_MOD_PROCESS_EVENT -> "Process IMC";
			case FML_LOAD_COMPLETE_EVENT -> "Complete loading of %d mods".formatted(ModList.get() == null ? 0 : ModList.get().size());
			default -> throw new IllegalArgumentException(kind + " is not a NeoForge setup phase");
		};
	}

	/**
	 * Builds the one event {@code kind} names and hands it to {@code container} as {@code ModLoader.dispatchParallelEvent}
	 * does: through {@code ModContainer.acceptEvent}, which posts it on the mod's bus and turns a listener's throw into
	 * NeoForge's {@code ModLoadingException} naming the mod.
	 */
	private static void post(ForeignType kind, ModContainer container, DeferredWorkQueue queue) {
		switch (kind) {
			case FML_CONSTRUCT_MOD_EVENT -> container.acceptEvent(new FMLConstructModEvent(container, queue));
			case FML_COMMON_SETUP_EVENT -> container.acceptEvent(new FMLCommonSetupEvent(container, queue));
			case FML_CLIENT_SETUP_EVENT -> container.acceptEvent(new FMLClientSetupEvent(container, queue));
			case FML_DEDICATED_SERVER_SETUP_EVENT -> container.acceptEvent(new FMLDedicatedServerSetupEvent(container, queue));
			case INTER_MOD_ENQUEUE_EVENT -> container.acceptEvent(new InterModEnqueueEvent(container, queue));
			case INTER_MOD_PROCESS_EVENT -> container.acceptEvent(new InterModProcessEvent(container, queue));
			case FML_LOAD_COMPLETE_EVENT -> container.acceptEvent(new FMLLoadCompleteEvent(container, queue));
			default -> throw new IllegalArgumentException(kind + " is not a NeoForge setup phase");
		}
	}
}
