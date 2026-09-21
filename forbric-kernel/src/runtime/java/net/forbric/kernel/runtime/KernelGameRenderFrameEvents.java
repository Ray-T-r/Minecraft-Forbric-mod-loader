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

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
import net.minecraft.client.DeltaTracker;
import net.minecraftforge.client.event.ForgeEventFactoryClient;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/**
 * Re-emits NeoForge's render frame on traditional MinecraftForge's render-tick hook.
 *
 * <p>Separate from {@link KernelGameClientTickEvents} for the same reason that one is separate from
 * {@link KernelGameTickEvents}: it names {@code net.neoforged.neoforge.client.event.RenderFrameEvent}, a client
 * class a dedicated server must never be made to resolve. The kernel installs this one only on the client.
 *
 * <p><b>What is dead without it.</b> The merged {@code Minecraft.renderFrame(Z)V} calls
 * {@code ClientHooks.fireRenderFramePre} (bci 514) and {@code fireRenderFramePost} (bci 533) and carries no
 * reference to {@code ForgeEventFactoryClient} at all — NeoForge won that method in the byte-merge and
 * MinecraftForge's {@code TickEvent.RenderTickEvent} went with it. Three listeners in this instance's pack are
 * registered on it and none of them had ever run: {@code xaero.map.events.ClientEventsForge.renderTick},
 * {@code xaero.common.events.ClientEventsForge.handleRenderTickEvent} and
 * {@code xaero.lib.client.event.ClientEventsForge.onRenderTick}/{@code onRenderTickPost}.
 *
 * <p><b>Why that is a black screen rather than a missing frame.</b> Xaero's world map does not draw from the
 * screen's render method; the render tick is the only pump that builds and uploads its region textures
 * ({@code MapProcessor.onRenderProcess}). With the pump dead the screen opens, the map has nothing to show, and
 * XaeroLib's immediate buffer — which the same listener is what drains — doubles without bound instead:
 * measured on the reporting instance, 3872 bytes to 31,719,424 over one map-open window, still growing. The
 * region directory {@code xaero/world-map/<world>/} held ZERO {@code .xwmc} files, which is the judgement here;
 * the buffer count cannot tell "leak fixed" from "map still black".
 *
 * <p>The two hooks take exactly the {@code DeltaTracker} that {@link RenderFrameEvent#getPartialTick()} returns,
 * so nothing is converted or invented across the bridge.
 */
public final class KernelGameRenderFrameEvents {
	private KernelGameRenderFrameEvents() {
	}

	/**
	 * Proof of life, per direction. This fires once per rendered frame, so unlike the tick bridges it cannot log
	 * per forward and cannot log at a small threshold either — at 60fps a "first 20" line would appear a third of
	 * a second in and say nothing. One line each way after {@value #PROOF} frames says the forward is really on
	 * the frame path, and then never speaks again.
	 */
	private static final long PROOF = 200L;

	/** NeoForge {@code RenderFrameEvent.Pre} → MinecraftForge {@code onRenderTickStart}. */
	public static void installPre(Object neoBus) {
		subscribe((IEventBus) neoBus, RenderFrameEvent.Pre.class, "Start",
				ForgeEventFactoryClient::onRenderTickStart);
	}

	/** NeoForge {@code RenderFrameEvent.Post} → MinecraftForge {@code onRenderTickEnd}. */
	public static void installPost(Object neoBus) {
		subscribe((IEventBus) neoBus, RenderFrameEvent.Post.class, "End",
				ForgeEventFactoryClient::onRenderTickEnd);
	}

	/**
	 * The MinecraftForge side of one render frame.
	 *
	 * <p>Named rather than reused as a {@code Consumer<DeltaTracker>} so the two method references cannot be
	 * crossed: both hooks have descriptor {@code (Lnet/minecraft/client/DeltaTracker;)V}, so wiring Post to
	 * {@code onRenderTickStart} compiles, installs, and fixes nothing — Xaero's world map listens on
	 * {@code $Post} alone.
	 */
	@FunctionalInterface
	private interface ForgeRenderTick {
		void fire(DeltaTracker partialTick);
	}

	private static <E extends RenderFrameEvent> void subscribe(IEventBus bus, Class<E> event, String kind,
			ForgeRenderTick forge) {
		AtomicBoolean warned = new AtomicBoolean();
		AtomicLong frames = new AtomicLong();
		// Four-argument overload with LOWEST, as everywhere in this package: the shorter overloads open with
		// `getstatic EventPriority.NORMAL` and would silently promote the forward.
		bus.addListener(EventPriority.LOWEST, false, event, neoEvent -> {
			try {
				forge.fire(neoEvent.getPartialTick());
				if (frames.incrementAndGet() == PROOF) {
					ForbricLog.info("[Forbric/EventMux] onRenderTick%s has forwarded %d frames — MinecraftForge's "
							+ "render tick is live again; it is the only pump a map mod's region builder runs on",
							kind, PROOF);
				}
			} catch (Throwable t) {
				if (warned.compareAndSet(false, true)) {
					ForbricLog.warn("[Forbric/EventMux] onRenderTick" + kind + " forward failed; a MinecraftForge "
							+ "mod that builds anything from the render tick (a map mod's region upload) stays "
							+ "empty and its vertex buffer grows without being drained", Reflect.unwrap(t));
				}
			}
		});
	}
}
