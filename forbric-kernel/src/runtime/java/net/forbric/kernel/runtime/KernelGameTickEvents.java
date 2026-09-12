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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.ForgeEventFactory;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Re-emits NeoForge's server tick on traditional MinecraftForge's hook.
 *
 * <p>On the merged base NeoForge won the tick: {@code MinecraftServer.tickServer} calls NeoForge's
 * {@code EventHooks.fireServerTickPost}, and MinecraftForge's {@code ForgeEventFactory} site is dead code. A
 * traditional-Forge mod's {@code ServerTickEvent} listener therefore never fires on its own. Subscribing to the
 * surviving hook at LOWEST priority and forwarding gives a 1:1 re-emission — one tick in, one tick out, per
 * family, in the same loop.
 *
 * <h2>What javac checks here, and what it does not</h2>
 *
 * <p>Every TYPE is checked: a renamed {@code ServerTickEvent} or a changed {@code onPostServerTick} signature is
 * now a build failure instead of a {@code NoSuchMethodException} that arrives once the server is already
 * ticking. Four things it cannot check are called out at their site below, because each of them compiles
 * perfectly while being wrong.
 */
public final class KernelGameTickEvents {
	/** Ticks to observe before logging the 1:1 proof once. Mirrors the old canary's "20 server ticks". */
	private static final int TICK_PROOF_THRESHOLD = 20;

	private KernelGameTickEvents() {
	}

	/**
	 * NeoForge {@code ServerTickEvent.Pre} → MinecraftForge {@code onPreServerTick}.
	 *
	 * <p>Pre and Post are two methods rather than one method taking the kind, and that is deliberate. The two
	 * Forge hooks have the SAME descriptor — {@code (Ljava/util/function/BooleanSupplier;Lnet/minecraft/server/
	 * MinecraftServer;)V} — so crossing them compiles, links, runs, and leaves gate-m4 green, because the gate
	 * asserts a log line whose kind is interpolated from the same parameter that chose the hook. Nothing
	 * mechanical stands behind this pairing. Written out twice, a cross is at least visible in a diff.
	 *
	 * @param neoBus NeoForge's {@code IEventBus}, handed over untyped from the boot side, which cannot name it
	 */
	public static void installPre(Object neoBus) {
		subscribe((IEventBus) neoBus, ServerTickEvent.Pre.class, "Pre",
				(haveTime, server) -> ForgeEventFactory.onPreServerTick(haveTime, server));
	}

	/** NeoForge {@code ServerTickEvent.Post} → MinecraftForge {@code onPostServerTick}. See {@link #installPre}. */
	public static void installPost(Object neoBus) {
		subscribe((IEventBus) neoBus, ServerTickEvent.Post.class, "Post",
				(haveTime, server) -> ForgeEventFactory.onPostServerTick(haveTime, server));
	}

	/** The MinecraftForge side of one tick, named so the two pairings above read as one line each. */
	@FunctionalInterface
	private interface ForgeTick {
		void fire(BooleanSupplier haveTime, MinecraftServer server);
	}

	private static <E extends ServerTickEvent> void subscribe(IEventBus bus, Class<E> event, String kind,
			ForgeTick forge) {
		AtomicBoolean warned = new AtomicBoolean();
		AtomicInteger forwarded = new AtomicInteger();

		Consumer<E> listener = neoEvent -> {
			try {
				// LAZY, and fail-OPEN. Both halves are load-bearing and both look redundant once typed:
				//
				// Lazy, because "is there time left this tick" is answered live on all three legs — NeoForge's
				// hasTime() reads a BooleanSupplier field, MinecraftForge's haveTime() calls through its own
				// supplier, and the source is MinecraftServer::haveTime comparing System nanos against the tick
				// deadline. A snapshot taken here would tell every deferred Forge task the answer from the top
				// of the tick, so they would either overrun their budget or never run.
				//
				// Fail-open, because this supplier is consulted from inside another family's code. Returning
				// false on an unexpected failure would stop that family's deferred work with no exception and
				// no log anywhere — this catch is deliberately silent, so fail-closed would be invisible.
				BooleanSupplier haveTime = () -> {
					try {
						return neoEvent.hasTime();
					} catch (Throwable t) {
						return true;
					}
				};
				forge.fire(haveTime, neoEvent.getServer());

				// The 1:1 proof, and the only evidence that works with REAL mods, which do not count ticks: this
				// listener IS the NeoForge tick and each forward IS the MinecraftForge one, so N forwards means
				// both families ticked N times in one loop, once each. gate-m4 asserts this wording.
				if (forwarded.incrementAndGet() == TICK_PROOF_THRESHOLD) {
					ForbricLog.info("[Forbric/EventMux] bridged %d ServerTickEvent.%s to MinecraftForge — 1:1 from "
							+ "NeoForge (both game-event families tick in one loop)", TICK_PROOF_THRESHOLD, kind);
				}
			} catch (Throwable t) {
				// Once, not per tick: degrade to NeoForge-only ticks rather than fill the log.
				if (warned.compareAndSet(false, true)) {
					ForbricLog.warn("[Forbric/EventMux] on" + kind + "ServerTick forward failed; Forge-family mods "
							+ "won't receive this tick event", Reflect.unwrap(t));
				}
			}
		};

		// The four-argument overload, ALWAYS. IEventBus declares eight addListener overloads and the shorter ones
		// are not shorthand for this: `javap -c` shows addListener(Class, Consumer) opens with
		// `getstatic EventPriority.NORMAL; iconst_0`, so reaching for the tidier call silently promotes this
		// listener from LOWEST to NORMAL. LOWEST is what makes the forward come last, after every NeoForge mod
		// has seen the event. The boolean is receiveCanceled, and false is correct whether or not the event ever
		// becomes cancellable.
		bus.addListener(EventPriority.LOWEST, false, event, listener);
	}
}
