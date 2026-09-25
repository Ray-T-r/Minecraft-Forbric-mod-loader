/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraftforge.event.ForgeEventFactory;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.brewing.RegisterBrewingRecipesEvent;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;

/**
 * MinecraftForge events whose loss cost a player something they keep: items, experience, blocks, recipes.
 *
 * <p>Each merged call site posts NeoForge's event and reads it back, with no MinecraftForge hook left:
 * {@code ServerPlayer.restoreFrom} (Clone — packedup keeps a dead player's backpacks and gives them back here, so they
 * vanished), {@code LivingEntity.dropExperience} and the dragon's death (experience drop — Tombstone keeps the
 * experience with the grave and cancels the drop, so it was duplicated), {@code ServerExplosion.hurtEntities}
 * (Detonate — Tombstone takes its graves off the list, so creepers blew them up) and {@code PotionBrewing.bootstrap}
 * (brewing registration — a MinecraftForge mod's potions could not be brewed). These forward at LOWEST, and carry
 * back what the MinecraftForge event can change: a cancel one way, the dropped experience, and the affected lists,
 * which cross by reference.
 */
public final class KernelGameServerEvents {
	private KernelGameServerEvents() {
	}

	/** NeoForge {@code PlayerEvent.Clone} → MinecraftForge {@code PlayerEvent.Clone}. */
	public static void installPlayerClone(Object neoBus) {
		forward((IEventBus) neoBus, PlayerEvent.Clone.class, "PlayerEvent.Clone",
				event -> ForgeEventFactory.onPlayerClone((net.minecraft.world.entity.player.Player) event.getEntity(),
						event.getOriginal(), event.isWasDeath()));
	}

	/** NeoForge {@code LivingExperienceDropEvent} → MinecraftForge's: cancel carried, dropped amount written back. */
	public static void installExperienceDrop(Object neoBus) {
		forward((IEventBus) neoBus, LivingExperienceDropEvent.class, "LivingExperienceDropEvent", event -> {
			if (event.isCanceled()) return;
			var forge = new net.minecraftforge.event.entity.living.LivingExperienceDropEvent(event.getEntity(),
					event.getAttackingPlayer(), event.getOriginalExperience());
			forge.setDroppedExperience(event.getDroppedExperience());
			if (net.minecraftforge.event.entity.living.LivingExperienceDropEvent.BUS.post(forge)) {
				event.setCanceled(true);
				return;
			}
			if (forge.getDroppedExperience() != event.getDroppedExperience()) event.setDroppedExperience(forge.getDroppedExperience());
		});
	}

	/** NeoForge {@code ExplosionEvent.Detonate} → MinecraftForge's, on the same two lists. */
	public static void installExplosionDetonate(Object neoBus) {
		forward((IEventBus) neoBus, ExplosionEvent.Detonate.class, "ExplosionEvent.Detonate",
				event -> ForgeEventFactory.onExplosionDetonate(event.getLevel(), event.getExplosion(),
						event.getAffectedBlocks(), event.getAffectedEntities(), 0.0D));
	}

	/**
	 * NeoForge {@code RegisterBrewingRecipesEvent} → MinecraftForge {@code BrewingRecipeRegisterEvent}, on the same
	 * builder. A MinecraftForge recipe lands in the builder's NeoForge-typed list through {@code Builder.add}, which
	 * ForgeBrewingRecipesInjector makes wrap it ({@link KernelBrewing}).
	 */
	public static void installBrewingRecipes(Object neoBus) {
		forward((IEventBus) neoBus, RegisterBrewingRecipesEvent.class, "RegisterBrewingRecipesEvent",
				event -> ForgeEventFactory.onBrewingRecipeRegister(event.getBuilder(), features(event.getBuilder())));
	}

	private static volatile Field enabledFeatures;

	/** The builder's feature flags; MinecraftForge's event carries them and the builder keeps them private. */
	private static FeatureFlagSet features(PotionBrewing.Builder builder) throws ReflectiveOperationException {
		Field field = enabledFeatures;
		if (field == null) {
			field = PotionBrewing.Builder.class.getDeclaredField("enabledFeatures");
			field.setAccessible(true);
			enabledFeatures = field;
		}
		return (FeatureFlagSet) field.get(builder);
	}

	@FunctionalInterface
	interface Forward<E> {
		void accept(E event) throws Throwable;
	}

	/** A LOWEST forward of one NeoForge event; one warning per event if it throws. */
	static <E extends Event> void forward(IEventBus bus, Class<E> event, String name, Forward<E> forward) {
		AtomicBoolean warned = new AtomicBoolean();
		Consumer<E> listener = neoEvent -> {
			try {
				forward.accept(neoEvent);
			} catch (Throwable t) {
				if (warned.compareAndSet(false, true)) {
					ForbricLog.warn("[Forbric/EventMux] " + name + " forward failed — MinecraftForge mods listening "
							+ "for it are not told", Reflect.unwrap(t));
				}
			}
		};
		bus.addListener(EventPriority.LOWEST, false, event, listener);
	}
}
