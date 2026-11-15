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
import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.Reflect;
import net.forbric.api.ModCatalog;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.DeferredWorkQueue;
import net.neoforged.fml.ModContainer;
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
 */
public final class KernelNeoSetup {
	private KernelNeoSetup() {
	}

	/**
	 * Posts {@code event} at every mod, then drains the queue they filed into.
	 *
	 * @param mods modid → that mod's bus and container
	 * @return how many mods the event reached
	 */
	public static int firePhase(Map<String, KernelModLoader.NeoIdentity> mods, ForeignType event, String label)
			throws Throwable {
		DeferredWorkQueue queue = new DeferredWorkQueue(label);
		int fired = 0;

		// Each mod on NeoForge's own modloading-worker pool, as soon as the mods it waits for have taken the phase, the
		// way ModLoader.dispatchParallelEvent posts it (NeoParallelDispatch). It ran on the caller's thread, one mod
		// after another — on a client the render thread, where a listener's Minecraft.execute ran inline instead of
		// queueing. DeferredWorkQueue takes enqueueWork from several workers at once, as natively.
		ClassLoader cl = KernelNeoSetup.class.getClassLoader();
		List<Map.Entry<String, KernelModLoader.NeoIdentity>> entries = new ArrayList<>(mods.entrySet());
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
						post(event, (IEventBus) e.getValue().bus(), (ModContainer) e.getValue().container(), queue);
					} finally {
						KernelModLoader.setNeoActiveContainer(cl, null);
					}
				});
		// What each mod's listeners came to, handled here in the order the mods are walked.
		for (int i = 0; i < entries.size(); i++) {
			Throwable perMod = failed.get(i);
			if (perMod == null) {
				fired++;
				continue;
			}
			String modId = entries.get(i).getKey();
			Caught.warn(perMod, "[Forbric/Lifecycle] %s failed during %s", modId, label);
			ModCatalog.mark(modId, ModCatalog.Status.DEGRADED, "it threw during " + label);
		}

		// Off the caller's thread, because that is where NeoForge runs it and mods can tell the difference — see
		// NeoDeferredWork for the resource-manager window this was landing in.
		//
		// GUARDED: DeferredWorkQueue.runTasks does NOT abort at
		// the first failure — it collects each one as a suppressed cause (and NeoForge's own captureException has
		// already logged "Mod '<id>' encountered an error in a deferred task") and throws at the END. Letting that
		// escape cost the phase REPORT: bucket_of_frog's task threw NoClassDefFoundError for a class upstream
		// NeoForge had deleted, and "posted FML common setup to 23 NeoForge mod(s)" never printed at all. Every
		// other mod's setup had in fact completed; nothing said so, and gate-m9's assertion on that line went red
		// for a reason that had nothing to do with the 23.
		try {
			NeoDeferredWork.runBlocking(
					NeoDeferredWork.syncExecutor(KernelNeoSetup.class.getClassLoader()), queue::runTasks);
		} catch (Throwable drained) {
			Throwable[] failures = Reflect.unwrap(drained).getSuppressed();
			List<String> owners = DeferredWorkFailures.owners(queue);
			for (String id : owners) {
				ModCatalog.mark(id, ModCatalog.Status.DEGRADED, "one of its deferred setup tasks threw during " + label);
			}
			Caught.warn(drained, "[Forbric/Lifecycle] %d deferred task(s) failed during %s — %s; the other mods' %s "
					+ "completed", failures.length == 0 ? 1 : failures.length, label,
					owners.isEmpty() ? "owner unknown" : String.join(", ", owners), label);
		}
		return fired;
	}

	/** Builds and posts the one event {@code kind} names on {@code bus}. */
	private static void post(ForeignType kind, IEventBus bus, ModContainer container, DeferredWorkQueue queue) {
		switch (kind) {
			case FML_CONSTRUCT_MOD_EVENT -> bus.post(new FMLConstructModEvent(container, queue));
			case FML_COMMON_SETUP_EVENT -> bus.post(new FMLCommonSetupEvent(container, queue));
			case FML_CLIENT_SETUP_EVENT -> bus.post(new FMLClientSetupEvent(container, queue));
			case FML_DEDICATED_SERVER_SETUP_EVENT -> bus.post(new FMLDedicatedServerSetupEvent(container, queue));
			case INTER_MOD_ENQUEUE_EVENT -> bus.post(new InterModEnqueueEvent(container, queue));
			case INTER_MOD_PROCESS_EVENT -> bus.post(new InterModProcessEvent(container, queue));
			case FML_LOAD_COMPLETE_EVENT -> bus.post(new FMLLoadCompleteEvent(container, queue));
			default -> throw new IllegalArgumentException(kind + " is not a NeoForge setup phase");
		}
	}
}
