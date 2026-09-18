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

import java.util.Map;

import net.forbric.api.ForeignType;
import net.forbric.kernel.boot.KernelModLoader;
import net.forbric.kernel.boot.NeoDeferredWork;
import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
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
 * <p>The MinecraftForge twin is {@link KernelForgeSetup}, and the two stay apart on purpose: NeoForge dispatches
 * on an {@code IEventBus} instance held per mod, while EventBus 7 resolves a bus from the EVENT plus that mod's
 * {@code BusGroup}. Folding them would be the averaging-away this repo's {@code ForeignType} javadoc warns
 * about; the divergence stays as data at the call site, which is why every call above comes in pairs.
 *
 * <p>Like its twin, this takes the phase as a {@link ForeignType} rather than a class-name string, so the
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

		for (Map.Entry<String, KernelModLoader.NeoIdentity> e : mods.entrySet()) {
			// The active container has to be set for the DISPATCH, not just for construction. A setup listener
			// that registers anything through ModLoadingContext.get() reads getActiveContainer(), which with none
			// set falls back to the "minecraft" container and throws "Where is minecraft???!". That killed
			// CreativeCore's and Sound Physics' reload-listener registration outright, and CreativeCore then
			// half-initialised: GuiStyle.mc stayed null ("Could not load default style"), and the next reload
			// re-ran registerDefault and died on 'default' already exists — three failures, one missing line.
			KernelModLoader.setNeoActiveContainer(KernelNeoSetup.class.getClassLoader(), e.getValue().container());
			try {
				post(event, (IEventBus) e.getValue().bus(), (ModContainer) e.getValue().container(), queue);
				fired++;
			} catch (Throwable perMod) {
				ForbricLog.warn("[Forbric/Lifecycle] " + e.getKey() + " failed during " + label,
						Reflect.unwrap(perMod));
			} finally {
				KernelModLoader.setNeoActiveContainer(KernelNeoSetup.class.getClassLoader(), null);
			}
		}

		// Off the caller's thread, because that is where NeoForge runs it and mods can tell the difference — see
		// NeoDeferredWork for the resource-manager window this was landing in.
		//
		// GUARDED, and the MinecraftForge twin already was: KernelForgeSetup wraps its own drain and this one did
		// not, which is the asymmetry that pairing exists to expose. DeferredWorkQueue.runTasks does NOT abort at
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
			ForbricLog.warn("[Forbric/Lifecycle] " + (failures.length == 0 ? 1 : failures.length)
					+ " deferred task(s) failed during " + label + " — each owning mod is named above by NeoForge's "
					+ "own report, and is left half-loaded. The other mods' " + label + " completed",
					Reflect.unwrap(drained));
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
