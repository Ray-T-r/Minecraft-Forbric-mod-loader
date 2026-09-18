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

import java.util.List;

import net.forbric.api.ForeignType;
import net.forbric.kernel.boot.KernelForgeModContext.Handle;
import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
import net.forbric.api.ModCatalog;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.fml.DeferredWorkQueue;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModLoadingStage;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLConstructModEvent;
import net.minecraftforge.fml.event.lifecycle.FMLDedicatedServerSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.fml.event.lifecycle.InterModEnqueueEvent;
import net.minecraftforge.fml.event.lifecycle.InterModProcessEvent;

/**
 * The game side of a traditional-MinecraftForge setup phase: post one mod-lifecycle event at every MinecraftForge
 * mod, then run what they deferred.
 *
 * <h2>What moving here bought, beyond the usual</h2>
 *
 * <p>The boot side used to take the event and its {@code ModLoadingStage} as two INDEPENDENT strings and pair
 * them at each of eleven call sites. Nothing related them. A crossed pair — {@code FMLLoadCompleteEvent} with
 * {@code COMMON_SETUP} — compiles, posts, and delivers the wrong stage's deferred queue, so the work one phase
 * filed runs at another phase or not at all. Every event belongs to exactly one stage, and now the pairing lives
 * in one place where javac checks both halves: {@link #stageOf} is exhaustive over the events this kernel posts,
 * and {@link #post} cannot name a constructor that does not exist.
 *
 * <p>Two smaller facts the reflective version could not report. All six constructors are {@code public} — the
 * boot side called {@code getDeclaredConstructor} and {@code setAccessible(true)} on them, which was never
 * needed and would have gone on hiding a genuine visibility change. And {@code DeferredWorkQueue.runTasks} is
 * public API on {@code ModLoadingStage.getDeferredWorkQueue()}, not the {@code Object}-typed hop it used to be.
 *
 * <h2>Why this is safe to link on a dedicated server</h2>
 *
 * <p>{@link FMLClientSetupEvent} is named here and a dedicated server loads this class. That is checked, not
 * assumed: disassembled, that event declares only {@code getBus} and its constructor, extends
 * {@code ParallelDispatchEvent}, and its constant pool names no client type at all. The client-only event in
 * this kernel is NeoForge's client TICK, which is why that one lives in a class of its own.
 *
 * <p>The other half of that guard is the caller's: {@code KernelForgeModContext.fireSetupPhase} returns before
 * naming this class when no MinecraftForge mod was published, and mods are published only where
 * {@code KernelForgeModContext.available} held. A NeoForge-only instance therefore never loads this at all.
 */
public final class KernelForgeSetup {
	private KernelForgeSetup() {
	}

	/**
	 * Posts {@code event} at every handle, each with that mod's container ACTIVE, then drains the phase's
	 * deferred work queue.
	 *
	 * <p>Per-mod isolation, as genuine FML does: it collects these rather than dying on the first, and one mod's
	 * broken setup must not cost every mod after it the same phase.
	 *
	 * <p><b>The deferred queue is half the work.</b> {@code ParallelDispatchEvent.enqueueWork} does not run the
	 * runnable; it files it on the {@code DeferredWorkQueue} the {@code ModLoadingStage} itself owns. Posting the
	 * event without draining that queue delivers the event and still runs none of the work — which for
	 * BiomesOPlenty was precisely the call that registered its biomes with TerraBlender, so the world came out
	 * looking vanilla with every BOP block still in the creative menu and nothing anywhere saying why. Drained
	 * once per phase, after every mod has seen it, which is the order genuine FML uses.
	 *
	 * @return how many mods the event reached
	 */
	public static int firePhase(List<Handle> handles, ForeignType event, String label) {
		ModLoadingStage stage = stageOf(event);
		int fired = 0;

		try {
			for (Handle handle : handles) {
				try {
					// The mod that is registering must be the active container while its listener runs, exactly as
					// in fireRegisterEvents — otherwise whatever it registers is namespaced under the last one.
					KernelForgeContainers.setActiveContainer(handle.container());
					post(event, (ModContainer) handle.container(), (BusGroup) handle.busGroup(), stage);
					fired++;
				} catch (Throwable t) {
					ForbricLog.warn("[Forbric/Lifecycle] " + handle.modId() + " threw during traditional-Forge "
							+ label, Reflect.unwrap(t));
					ModCatalog.mark(handle.modId(), ModCatalog.Status.DEGRADED, "it threw during " + label);
				}
			}
		} finally {
			try {
				KernelForgeContainers.setActiveContainer(null);
			} catch (Exception stuck) {
				ForbricLog.warn("[Forbric/Lifecycle] could not clear the active MinecraftForge container after "
						+ label + " — whatever registers next may be namespaced under the last mod",
						Reflect.unwrap(stuck));
			}
		}

		// Now the work they filed. Before this line the event has been delivered and nothing it asked for has run.
		try {
			DeferredWorkQueue queue = stage.getDeferredWorkQueue();
			if (queue != null) queue.runTasks();
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] traditional-Forge " + label + " was delivered but its deferred "
					+ "work did not run — a mod that registers from enqueueWork has done nothing",
					Reflect.unwrap(t));
		}
		return fired;
	}

	/**
	 * The stage each event belongs to.
	 *
	 * <p>This is the pairing genuine FML holds in its own phase table, and getting it wrong is silent: the event
	 * still posts, and the queue drained afterwards is some other phase's.
	 */
	static ModLoadingStage stageOf(ForeignType event) {
		return switch (event) {
			case FML_CONSTRUCT_MOD_EVENT -> ModLoadingStage.CONSTRUCT;
			case FML_COMMON_SETUP_EVENT -> ModLoadingStage.COMMON_SETUP;
			// Both sided events share one stage, and so one queue — the server posts one, the client the other.
			case FML_CLIENT_SETUP_EVENT, FML_DEDICATED_SERVER_SETUP_EVENT -> ModLoadingStage.SIDED_SETUP;
			case INTER_MOD_ENQUEUE_EVENT -> ModLoadingStage.ENQUEUE_IMC;
			case INTER_MOD_PROCESS_EVENT -> ModLoadingStage.PROCESS_IMC;
			case FML_LOAD_COMPLETE_EVENT -> ModLoadingStage.COMPLETE;
			default -> throw new IllegalArgumentException(event + " is not a MinecraftForge setup phase");
		};
	}

	/** Builds and posts the one event {@code kind} names on {@code group}'s bus for it. */
	private static void post(ForeignType kind, ModContainer container, BusGroup group, ModLoadingStage stage) {
		switch (kind) {
			case FML_CONSTRUCT_MOD_EVENT ->
					FMLConstructModEvent.getBus(group).post(new FMLConstructModEvent(container, stage));
			case FML_COMMON_SETUP_EVENT ->
					FMLCommonSetupEvent.getBus(group).post(new FMLCommonSetupEvent(container, stage));
			case FML_CLIENT_SETUP_EVENT ->
					FMLClientSetupEvent.getBus(group).post(new FMLClientSetupEvent(container, stage));
			case FML_DEDICATED_SERVER_SETUP_EVENT ->
					FMLDedicatedServerSetupEvent.getBus(group)
							.post(new FMLDedicatedServerSetupEvent(container, stage));
			case INTER_MOD_ENQUEUE_EVENT ->
					InterModEnqueueEvent.getBus(group).post(new InterModEnqueueEvent(container, stage));
			case INTER_MOD_PROCESS_EVENT ->
					InterModProcessEvent.getBus(group).post(new InterModProcessEvent(container, stage));
			case FML_LOAD_COMPLETE_EVENT ->
					FMLLoadCompleteEvent.getBus(group).post(new FMLLoadCompleteEvent(container, stage));
			default -> throw new IllegalArgumentException(kind + " is not a MinecraftForge setup phase");
		}
	}
}
