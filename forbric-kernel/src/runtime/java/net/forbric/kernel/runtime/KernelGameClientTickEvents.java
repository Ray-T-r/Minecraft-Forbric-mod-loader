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

import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
import net.minecraftforge.event.ForgeEventFactory;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Re-emits NeoForge's CLIENT tick on traditional MinecraftForge's hook.
 *
 * <p>Separate from {@link KernelGameTickEvents} because it names {@code net.neoforged.neoforge.client.event
 * .ClientTickEvent}: a class in the client package, which a dedicated server must never be made to resolve. The
 * kernel installs this one only on the client, and keeping it in its own class means the server never loads it.
 *
 * <p>Why it matters more than the other ticks. The client tick is where a MinecraftForge mod POLLS ITS KEY
 * BINDINGS — {@code while (MY_KEY.consumeClick())} inside a {@code ClientTickEvent} handler is the standard
 * shape, because {@code consumeClick} drains a counter and has to be drained every tick. So with this hook dead,
 * a Forge mod's keys are registered, appear in the Controls screen, bind correctly, and do nothing when pressed:
 * the counter is incremented by the game and never read by anyone. That is a failure with no exception, no log
 * and a perfectly healthy-looking Controls screen, and it sits directly on top of the KeyMapping work the merged
 * base already needed.
 *
 * <p>{@code Minecraft.tick} on the merged base calls {@code ClientHooks.fireClientTickPre/Post} (NeoForge) and
 * carries no reference to MinecraftForge's {@code onPreClientTick}/{@code onPostClientTick} at all.
 */
public final class KernelGameClientTickEvents {
	private KernelGameClientTickEvents() {
	}

	/** NeoForge {@code ClientTickEvent.Pre} → MinecraftForge {@code onPreClientTick}. */
	public static void installPre(Object neoBus) {
		subscribe((IEventBus) neoBus, ClientTickEvent.Pre.class, "Pre", ForgeEventFactory::onPreClientTick);
	}

	/** NeoForge {@code ClientTickEvent.Post} → MinecraftForge {@code onPostClientTick}. */
	public static void installPost(Object neoBus) {
		subscribe((IEventBus) neoBus, ClientTickEvent.Post.class, "Post", ForgeEventFactory::onPostClientTick);
	}

	/** The MinecraftForge side of one client tick. Both hooks are no-arg, so there is nothing to carry across. */
	@FunctionalInterface
	private interface ForgeClientTick {
		void fire();
	}

	private static <E extends ClientTickEvent> void subscribe(IEventBus bus, Class<E> event, String kind,
			ForgeClientTick forge) {
		AtomicBoolean warned = new AtomicBoolean();
		// Four-argument overload with LOWEST, as everywhere in this package: the shorter overloads open with
		// `getstatic EventPriority.NORMAL` and would silently promote the forward.
		bus.addListener(EventPriority.LOWEST, false, event, neoEvent -> {
			try {
				forge.fire();
			} catch (Throwable t) {
				if (warned.compareAndSet(false, true)) {
					ForbricLog.warn("[Forbric/EventMux] on" + kind + "ClientTick forward failed; a MinecraftForge "
							+ "mod that polls its key bindings from the client tick will not respond to any key",
							Reflect.unwrap(t));
				}
			}
		});
	}
}
