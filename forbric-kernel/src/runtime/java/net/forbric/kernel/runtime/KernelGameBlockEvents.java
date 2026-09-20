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

import java.util.function.Consumer;

import net.minecraft.util.TriState;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.util.Result;
import net.minecraftforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
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
 *
 * <h2>Clicking a block</h2>
 *
 * <p>Same class of hole in the same class: {@code ServerPlayerGameMode} posts NeoForge's
 * {@code PlayerInteractEvent.RightClickBlock} and {@code .LeftClickBlock} and nothing of MinecraftForge's. These
 * two carry more than a cancel flag — each has a {@code useBlock} and a {@code useItem} decision, spelled
 * {@code TriState} on the NeoForge side and {@code Result} on the MinecraftForge one — so the forward has to
 * translate rather than merely observe.
 *
 * <p>The write-back rule is the cancel rule generalised: a MinecraftForge mod's decision is taken when the
 * NeoForge side is still {@code DEFAULT}, and ignored when a NeoForge listener has already decided. So both
 * directions of a MinecraftForge decision cross — denying the block use AND forcing it — but neither overrules a
 * NeoForge mod that ran first, which is the same asymmetry cancellation already has and for the same reason: a
 * silent overrule is a far harder bug to find than a decision that did not apply.
 */
public final class KernelGameBlockEvents {
	private KernelGameBlockEvents() {
	}

	/** NeoForge {@code RightClickBlock} → MinecraftForge's, with both tri-state decisions carried back. */
	public static void installRightClickBlock(Object neoBus) {
		KernelGameEntityEvents.subscribe((net.neoforged.bus.api.IEventBus) neoBus,
				PlayerInteractEvent.RightClickBlock.class, "PlayerInteractEvent.RightClickBlock",
				"a MinecraftForge mod cannot see or refuse a right-click on a block — protection, locks and "
						+ "custom block interactions do nothing",
				KernelGameBlockEvents::fireRightClick);
	}

	/** NeoForge {@code LeftClickBlock} → MinecraftForge's, with both tri-state decisions carried back. */
	public static void installLeftClickBlock(Object neoBus) {
		KernelGameEntityEvents.subscribe((net.neoforged.bus.api.IEventBus) neoBus,
				PlayerInteractEvent.LeftClickBlock.class, "PlayerInteractEvent.LeftClickBlock",
				"a MinecraftForge mod cannot see or refuse a left-click on a block — the first half of every "
						+ "protection rule about breaking one",
				KernelGameBlockEvents::fireLeftClick);
	}

	/** Posts MinecraftForge's right-click event and carries what it decided back. Package-private for the test. */
	static boolean fireRightClick(PlayerInteractEvent.RightClickBlock neo) {
		net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickBlock forge =
				new net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickBlock(
						neo.getEntity(), neo.getHand(), neo.getPos(), neo.getHitVec());
		forge.setUseBlock(seed(neo.getUseBlock()));
		forge.setUseItem(seed(neo.getUseItem()));
		boolean canceled = net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickBlock.BUS.post(forge);
		carryDecision(neo.getUseBlock(), forge.getUseBlock(), neo::setUseBlock);
		carryDecision(neo.getUseItem(), forge.getUseItem(), neo::setUseItem);
		if (canceled) neo.setCancellationResult(forge.getCancellationResult());
		return canceled;
	}

	/** Posts MinecraftForge's left-click event and carries what it decided back. Package-private for the test. */
	static boolean fireLeftClick(PlayerInteractEvent.LeftClickBlock neo) {
		net.minecraftforge.event.entity.player.PlayerInteractEvent.LeftClickBlock.Action action =
				action(neo.getAction());
		if (action == null) return false;

		net.minecraftforge.event.entity.player.PlayerInteractEvent.LeftClickBlock forge =
				new net.minecraftforge.event.entity.player.PlayerInteractEvent.LeftClickBlock(
						neo.getEntity(), neo.getPos(), neo.getFace(), action);
		forge.setUseBlock(seed(neo.getUseBlock()));
		forge.setUseItem(seed(neo.getUseItem()));
		boolean canceled = net.minecraftforge.event.entity.player.PlayerInteractEvent.LeftClickBlock.BUS.post(forge);
		carryDecision(neo.getUseBlock(), forge.getUseBlock(), neo::setUseBlock);
		carryDecision(neo.getUseItem(), forge.getUseItem(), neo::setUseItem);
		return canceled;
	}

	/**
	 * The same action under MinecraftForge's name, or null when it has none.
	 *
	 * <p>Matched by NAME rather than by ordinal: the two enums carry the same four constants today, and an
	 * ordinal match would keep compiling and start meaning something else the day either family inserts one.
	 * A left-click whose action MinecraftForge does not know is skipped rather than guessed — the MinecraftForge
	 * event has no way to spell it, and inventing the nearest one would tell a mod something untrue.
	 */
	static net.minecraftforge.event.entity.player.PlayerInteractEvent.LeftClickBlock.Action action(
			PlayerInteractEvent.LeftClickBlock.Action neo) {
		if (neo == null) return null;
		for (net.minecraftforge.event.entity.player.PlayerInteractEvent.LeftClickBlock.Action candidate
				: net.minecraftforge.event.entity.player.PlayerInteractEvent.LeftClickBlock.Action.values()) {
			if (candidate.name().equals(neo.name())) return candidate;
		}
		return null;
	}

	/** The NeoForge decision under MinecraftForge's name, so a mod reads the state it would read natively. */
	static Result seed(TriState neo) {
		if (neo.isFalse()) return Result.DENY;
		if (neo.isTrue()) return Result.ALLOW;
		return Result.DEFAULT;
	}

	/**
	 * Writes one MinecraftForge decision back, under the rule cancellation already follows.
	 *
	 * <p>Taken when the NeoForge side is still undecided; dropped when a NeoForge listener has decided, whichever
	 * way round. A MinecraftForge mod has no standing to overrule one that ran first, and letting it do so
	 * silently is worse than the decision not applying — the first is a bug nobody can see, the second is one the
	 * dead-event audit already names.
	 */
	static void carryDecision(TriState neoValue, Result forgeValue, Consumer<TriState> write) {
		if (!neoValue.isDefault() || forgeValue.isDefault()) return;
		write.accept(forgeValue.isDenied() ? TriState.FALSE : TriState.TRUE);
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
