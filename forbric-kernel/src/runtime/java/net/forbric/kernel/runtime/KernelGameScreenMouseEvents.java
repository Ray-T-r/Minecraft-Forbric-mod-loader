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
import net.minecraftforge.client.event.ForgeEventFactoryClient;
import net.minecraftforge.client.event.ScreenEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;

/**
 * Re-emits the SCREEN MOUSE events the byte merge left firing on NeoForge's hook alone.
 *
 * <p>Its own class, for the same reason {@link KernelGameClientTickEvents} and
 * {@link KernelGameRenderFrameEvents} are theirs: it names types in {@code net.neoforged.neoforge.client.event},
 * which a dedicated server must never be made to resolve. The kernel installs it only on the client.
 *
 * <h2>What is dead without it</h2>
 *
 * <p>Measured on {@code patched-mc-merged-26.2.jar}: of the 46 hooks {@code ForgeEventFactoryClient} declares,
 * EIGHT still have a call site (around {@code Camera}, {@code Gui} twice, {@code Screen},
 * {@code AbstractContainerScreen} twice, {@code ClientPacketListener} and {@code BlockStateDefinitions}). The
 * whole screen mouse family is in the other 38: the merged {@code MouseHandler} routes to NeoForge's
 * {@code ClientHooks} at every one of them — {@code onButton} at bci 316/345 (pressed) and 448/473 (released),
 * {@code handleAccumulatedMovement} at 247/288 (dragged), {@code onScroll} at 172/217 (scrolled) — and carries no
 * reference to {@code ForgeEventFactoryClient} in any of those methods.
 *
 * <p>So a traditional-Forge mod listening on {@code ScreenEvent.Mouse*} has a listener on a bus nobody posts to.
 * The render-frame bridge does not reach it: this is a different producer in a different class, and a mod can be
 * live on one and dead on the other. MouseTweaks is the whole mod: {@code yalter.mousetweaks.forge
 * .MouseTweaksForge} declares exactly four handlers — {@code ScreenEvent$MouseButtonPressed$Pre},
 * {@code ScreenEvent$MouseButtonReleased$Pre}, {@code ScreenEvent$MouseDragged$Pre} and
 * {@code ScreenEvent$MouseScrolled$Post} — and does nothing else. With these four dead the mod loads cleanly,
 * registers cleanly, reports nothing, and every one of its features is absent.
 *
 * <h2>Why two of the four do not go through the MinecraftForge hook</h2>
 *
 * <p>{@code onScreenMouseScrollPost} and {@code onScreenMouseDragPre} are pure emitters — they build the event,
 * post it, and return — so the forward calls them, exactly as the other bridges in this package call theirs.
 *
 * <p>{@code onScreenMouseClicked} and {@code onScreenMouseReleased} are NOT. Each is a whole call site rather
 * than a hook: it posts {@code Pre}, and if nothing cancelled it calls {@code Screen.mouseClicked} /
 * {@code Screen.mouseReleased} itself, then posts {@code Post} and folds its {@code Result} into the answer.
 * Calling one of those from here would dispatch the click to the screen a SECOND time — the merged
 * {@code MouseHandler} has already done it — which is a double click for every click, invisible in any log and
 * far worse than the hole being fixed. So those two forwards post MinecraftForge's own {@code Pre} event
 * directly, the way {@link KernelGameBlockEvents#firePlace} posts MinecraftForge's place event rather than
 * calling a hook that would redo the work the merged base has already done.
 *
 * <p>Only the {@code Pre} half of those two pairs is bridged, because only the {@code Pre} half has a consumer:
 * their {@code Post} events carry a tri-state {@code Result} that overrides the click's outcome, and re-emitting
 * one without a mod asking for it would put a second authority on a decision the merged base has already made.
 *
 * <h2>Coordinates</h2>
 *
 * <p>Nothing is converted across the bridge and nothing may be. Both families' events carry GUI-SCALED mouse
 * coordinates: the merged {@code MouseHandler.onButton} builds its {@code MouseButtonEvent} from
 * {@code getScaledXPos}/{@code getScaledYPos} (bci 197-238) and NeoForge's {@code ScreenEvent.MouseInput} stores
 * exactly those, while MinecraftForge's own dead call site was passed the same two numbers. Handing a mod raw
 * window pixels instead would leave it hit-testing the wrong slot — a bug that looks like the mod misbehaving
 * rather than like a bridge being wrong.
 */
public final class KernelGameScreenMouseEvents {
	private KernelGameScreenMouseEvents() {
	}

	/**
	 * Proof of life for the one forward that cannot carry a veto, and therefore cannot be inferred from
	 * behaviour. A player scrolls in bursts, so unlike a tick this is counted rather than timed, and one line
	 * after {@value #PROOF} scrolls is enough to say the seam is on the real path.
	 */
	private static final long PROOF = 20L;

	/**
	 * NeoForge {@code ScreenEvent.MouseButtonPressed.Pre} → MinecraftForge's, cancel carried back.
	 *
	 * <p>Cancelling is what the listener is FOR here: MouseTweaks returns true from
	 * {@code onGuiMouseClickedPre} when it has handled the click itself, and a forward that dropped the answer
	 * would let the screen handle the same click again.
	 */
	public static void installPressedPre(Object neoBus) {
		KernelGameEntityEvents.subscribe((IEventBus) neoBus,
				net.neoforged.neoforge.client.event.ScreenEvent.MouseButtonPressed.Pre.class,
				"ScreenEvent.MouseButtonPressed.Pre",
				"a MinecraftForge mod cannot see or refuse a click inside a screen — every inventory-tweak mod's "
						+ "click handling does nothing",
				neo -> ScreenEvent.MouseButtonPressed.Pre.BUS.post(
						new ScreenEvent.MouseButtonPressed.Pre(neo.getScreen(), neo.getMouseX(), neo.getMouseY(),
								neo.getMouseButtonEvent())));
	}

	/** NeoForge {@code ScreenEvent.MouseButtonReleased.Pre} → MinecraftForge's, cancel carried back. */
	public static void installReleasedPre(Object neoBus) {
		KernelGameEntityEvents.subscribe((IEventBus) neoBus,
				net.neoforged.neoforge.client.event.ScreenEvent.MouseButtonReleased.Pre.class,
				"ScreenEvent.MouseButtonReleased.Pre",
				"a MinecraftForge mod never sees a mouse button released over a screen, so a drag it started is "
						+ "never ended and the stack it was moving is left mid-move",
				neo -> ScreenEvent.MouseButtonReleased.Pre.BUS.post(
						new ScreenEvent.MouseButtonReleased.Pre(neo.getScreen(), neo.getMouseX(), neo.getMouseY(),
								neo.getButton())));
	}

	/**
	 * NeoForge {@code ScreenEvent.MouseDragged.Pre} → MinecraftForge {@code onScreenMouseDragPre}.
	 *
	 * <p>A pure emitter on the MinecraftForge side, so this one really is the hook call the other bridges make.
	 */
	public static void installDragPre(Object neoBus) {
		KernelGameEntityEvents.subscribe((IEventBus) neoBus,
				net.neoforged.neoforge.client.event.ScreenEvent.MouseDragged.Pre.class,
				"ScreenEvent.MouseDragged.Pre",
				"a MinecraftForge mod cannot see a drag across a screen's slots — dragging a stack over a row of "
						+ "slots to fill them does nothing",
				neo -> ForgeEventFactoryClient.onScreenMouseDragPre(neo.getScreen(), neo.getMouseX(),
						neo.getMouseY(), neo.getMouseButton(), neo.getDragX(), neo.getDragY()));
	}

	/**
	 * NeoForge {@code ScreenEvent.MouseScrolled.Post} → MinecraftForge {@code onScreenMouseScrollPost}.
	 *
	 * <p>The only one of the four that is not cancellable, so it has its own subscribe: MinecraftForge's hook
	 * returns void and there is nothing to carry back. {@code Post} rather than {@code Pre} because that is the
	 * half a scroll-wheel mod listens on — it moves items AFTER the screen has had its own chance to scroll.
	 */
	public static void installScrollPost(Object neoBus) {
		AtomicBoolean warned = new AtomicBoolean();
		AtomicLong scrolls = new AtomicLong();
		// Four-argument overload with LOWEST, as everywhere in this package: the shorter overloads open with
		// `getstatic EventPriority.NORMAL` and would silently promote the forward.
		((IEventBus) neoBus).addListener(EventPriority.LOWEST, false,
				net.neoforged.neoforge.client.event.ScreenEvent.MouseScrolled.Post.class, neo -> {
					try {
						ForgeEventFactoryClient.onScreenMouseScrollPost(neo.getScreen(), neo.getMouseX(),
								neo.getMouseY(), neo.getScrollDeltaX(), neo.getScrollDeltaY());
						if (scrolls.incrementAndGet() == PROOF) {
							ForbricLog.info("[Forbric/EventMux] onScreenMouseScrollPost has forwarded %d scroll(s) "
									+ "— MinecraftForge mods see the wheel inside a screen again", PROOF);
						}
					} catch (Throwable t) {
						if (warned.compareAndSet(false, true)) {
							ForbricLog.warn("[Forbric/EventMux] onScreenMouseScrollPost forward failed; a "
									+ "MinecraftForge mod that moves items with the scroll wheel does nothing",
									Reflect.unwrap(t));
						}
					}
				});
	}
}
