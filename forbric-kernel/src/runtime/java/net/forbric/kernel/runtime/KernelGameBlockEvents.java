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

import net.minecraft.world.level.Level;
import net.minecraftforge.common.util.Result;
import net.minecraftforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;

/**
 * Re-emits the CANCELLABLE block events the byte merge left NeoForge-only.
 *
 * <h2>Breaking a block</h2>
 *
 * <p>{@code ServerPlayerGameMode} on the merged base calls {@code CommonHooks.fireBlockBreak} and branches on the
 * returned event's {@code isCanceled()}; it carries no {@code net/minecraftforge/} hook at all. So a
 * MinecraftForge mod's {@code BlockEvent.BreakEvent} listener never runs, and the cost is not cosmetic: claim and
 * protection mods do not protect, and block-logging mods record nothing. Both failures are silent — the mod loads,
 * its listener is registered, and the block simply breaks.
 *
 * <p>The forward POSTS MinecraftForge's own event rather than calling {@code ForgeHooks.onBlockBreakEvent}. That
 * hook re-does work the merged base has already done on the NeoForge path — the reach check, the block-entity
 * resync, the two packets it sends through {@code player.connection} when it denies — and running it here would
 * duplicate all of it, on top of requiring a {@code ServerPlayer} with a live connection, which the client-side
 * post from {@code MultiPlayerGameMode} does not have. Posting the event is what a mod is actually waiting for.
 *
 * <p>The event is seeded with {@code DENY} when the NeoForge event arrives already cancelled, because that is
 * what the flag means at this point: the game has decided against the break. A mod reading {@code getResult()}
 * rather than cancelling — MinecraftForge's own hook writes the result the same way — then sees the truth.
 *
 * <p><b>Known limit, stated rather than hidden:</b> {@code setExpToDrop} does not cross back. NeoForge's event has
 * no experience field for it to be written into, and the merged base takes the drop from its own path, so a
 * MinecraftForge mod that only adjusts the experience of a break is observed and ignored. Cancelling — the part
 * protection mods depend on — does cross.
 */
public final class KernelGameBlockEvents {
	private KernelGameBlockEvents() {
	}

	/** NeoForge {@code BreakBlockEvent} → MinecraftForge {@code BlockEvent.BreakEvent}, cancel carried back. */
	public static void installBlockBreak(Object neoBus) {
		KernelGameEntityEvents.subscribe((net.neoforged.bus.api.IEventBus) neoBus, BreakBlockEvent.class,
				"BlockEvent.BreakEvent",
				"a MinecraftForge claim or protection mod does not protect, and a block-logging mod records nothing",
				KernelGameBlockEvents::fireBreak);
	}

	/**
	 * Posts MinecraftForge's {@code BreakEvent} for one NeoForge break. Package-private so a test can drive it.
	 *
	 * @return whether MinecraftForge vetoed the break, by cancelling or by denying it
	 */
	static boolean fireBreak(BreakBlockEvent neoEvent) {
		// A LevelAccessor that is not a Level cannot build the MinecraftForge event, whose constructor takes one.
		// That is not a failure worth a warning: the merged base only ever posts this from the two game modes,
		// both of which hold a real Level.
		if (!(neoEvent.getLevel() instanceof Level level)) return false;
		// A player is REQUIRED, not merely expected: MinecraftForge's BreakEvent constructor asks
		// ForgeHooks.isCorrectToolForDrops about it and NPEs on null before any listener is reached. The merged
		// base only posts this from the two game modes, both of which hold one — but a mod posting the NeoForge
		// event itself does not have to, and that throw would be charged to this bridge.
		if (neoEvent.getPlayer() == null) return false;

		return vetoed(new BlockEvent.BreakEvent(level, neoEvent.getPos(), neoEvent.getState(), neoEvent.getPlayer(),
				seed(neoEvent.isCanceled())));
	}

	/**
	 * The result the MinecraftForge event starts with.
	 *
	 * <p>{@code DENY} when the game has already decided against the break, which is what an arriving cancelled
	 * NeoForge event means. MinecraftForge's own {@code onBlockBreakEvent} seeds it the same way — from
	 * {@code blockActionRestricted} — so a mod reading {@code getResult()} rather than the cancel flag sees what
	 * it would see on a MinecraftForge instance instead of a break that looks permitted.
	 */
	static Result seed(boolean alreadyCanceled) {
		return alreadyCanceled ? Result.DENY : Result.DEFAULT;
	}

	/**
	 * Posts one MinecraftForge break event and reads BOTH ways a mod can refuse it.
	 *
	 * <p>Both, because MinecraftForge's own hook reads only {@code getResult().isDenied()} while the event is also
	 * {@code Cancellable} and {@code post} returns whether a listener cancelled it. Reading one alone would honour
	 * half the mods that say no, and which half depends on which idiom each mod happened to use.
	 */
	static boolean vetoed(BlockEvent.BreakEvent forge) {
		boolean canceled = BlockEvent.BreakEvent.BUS.post(forge);
		return canceled || forge.getResult().isDenied();
	}
}
