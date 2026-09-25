/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.component.DataComponents;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.ToolAction;
import net.minecraftforge.common.util.Result;
import net.minecraftforge.event.ForgeEventFactory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.AnvilUpdateEvent;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.entity.EntityEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.living.LivingConversionEvent;
import net.neoforged.neoforge.event.entity.living.LivingEvent;
import net.neoforged.neoforge.event.entity.living.LivingHealEvent;
import net.neoforged.neoforge.event.entity.living.MobEffectEvent;
import net.neoforged.neoforge.event.entity.player.AnvilCraftEvent;
import net.neoforged.neoforge.event.entity.player.CriticalHitEvent;
import net.neoforged.neoforge.event.entity.player.PermissionsChangedEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.PlayerWakeUpEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

/**
 * The rest of MinecraftForge's world and entity events that the merged game posts only NeoForge's version of.
 *
 * <p>Every merged call site here constructs NeoForge's event, posts it and reads it back; none calls MinecraftForge's
 * hook. Observers forward (chunks JourneyMap maps, entities leaving a level, section moves, waking, tag reloads,
 * effects added and expired, a finished conversion); the rest carry the MinecraftForge answer back at LOWEST — a
 * cancel one way, a changed value only where it differs, a decision only where NeoForge left it open. Where a
 * MinecraftForge hook is not a pure emitter, or the merged base still asks MinecraftForge somewhere, the forward says so
 * next to it.
 */
public final class KernelGameWorldEvents {
	private KernelGameWorldEvents() {
	}

	// ---- observers ----

	public static void installChunkLoad(Object neoBus) {
		KernelGameServerEvents.forward((IEventBus) neoBus, ChunkEvent.Load.class, "ChunkEvent.Load",
				event -> ForgeEventFactory.onChunkLoad(event.getChunk(), event.isNewChunk()));
	}

	public static void installChunkUnload(Object neoBus) {
		KernelGameServerEvents.forward((IEventBus) neoBus, ChunkEvent.Unload.class, "ChunkEvent.Unload",
				event -> ForgeEventFactory.onChunkUnload(event.getChunk()));
	}

	public static void installEntityLeaveLevel(Object neoBus) {
		KernelGameServerEvents.forward((IEventBus) neoBus, EntityLeaveLevelEvent.class, "EntityLeaveLevelEvent",
				event -> ForgeEventFactory.onEntityLeaveLevel(event.getEntity(), event.getLevel()));
	}

	/** Every entity crossing a section boundary: asked only when a MinecraftForge mod listens. */
	public static void installEnteringSection(Object neoBus) {
		KernelGameServerEvents.forward((IEventBus) neoBus, EntityEvent.EnteringSection.class, "EntityEvent.EnteringSection",
				event -> {
					if (!net.minecraftforge.event.entity.EntityEvent.EnteringSection.BUS.hasListeners()) return;
					ForgeEventFactory.onEntityEnterSection(event.getEntity(), event.getPackedOldPos(), event.getPackedNewPos());
				});
	}

	public static void installPlayerWakeUp(Object neoBus) {
		KernelGameServerEvents.forward((IEventBus) neoBus, PlayerWakeUpEvent.class, "PlayerWakeUpEvent",
				event -> ForgeEventFactory.onPlayerWakeup(event.getEntity(), event.wakeImmediately(), event.updateLevel()));
	}

	public static void installTagsUpdated(Object neoBus) {
		KernelGameServerEvents.forward((IEventBus) neoBus, TagsUpdatedEvent.class, "TagsUpdatedEvent",
				event -> ForgeEventFactory.onTagsUpdated(event.getRegistries(),
						event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.CLIENT_PACKET_RECEIVED,
						!event.shouldUpdateStaticData()));
	}

	public static void installEffectAdded(Object neoBus) {
		KernelGameServerEvents.forward((IEventBus) neoBus, MobEffectEvent.Added.class, "MobEffectEvent.Added",
				event -> ForgeEventFactory.onLivingEffectAdd(event.getEntity(), event.getOldEffectInstance(),
						event.getEffectInstance(), event.getEffectSource()));
	}

	/** MinecraftForge's Expired is not cancellable: an observer (its Remove, still live, runs the cleanup). */
	public static void installEffectExpired(Object neoBus) {
		KernelGameServerEvents.forward((IEventBus) neoBus, MobEffectEvent.Expired.class, "MobEffectEvent.Expired",
				event -> {
					if (event.isCanceled()) return;
					ForgeEventFactory.onLivingEffectExpire(event.getEntity(), event.getEffectInstance());
				});
	}

	/**
	 * A finished conversion. The Zombie paths kept MinecraftForge's lambdas, which NeoConversionPostInjector sends through
	 * KernelConversions: NeoForge's Post there too, and MinecraftForge's only through this forward — once either way.
	 */
	public static void installConversionPost(Object neoBus) {
		KernelGameServerEvents.forward((IEventBus) neoBus, LivingConversionEvent.Post.class, "LivingConversionEvent.Post",
				event -> ForgeEventFactory.onLivingConvert(event.getEntity(), event.getOutcome()));
	}

	// ---- cancels carried back ----

	/**
	 * A conversion about to happen. The merged Zombie keeps MinecraftForge's own question on drowning, a husk's
	 * conversion and a villager's zombification; those three are skipped here so MinecraftForge is asked once.
	 */
	public static void installConversionPre(Object neoBus) {
		KernelGameEntityEvents.subscribe((IEventBus) neoBus, LivingConversionEvent.Pre.class, "LivingConversionEvent.Pre",
				"a MinecraftForge mod cannot stop a mob converting (its protected mobs turn anyway)",
				event -> {
					var outcome = event.getOutcome();
					if (event.getEntity() instanceof Zombie && (outcome == EntityTypes.DROWNED || outcome == EntityTypes.ZOMBIE)) return false;
					if (event.getEntity() instanceof Villager && outcome == EntityTypes.ZOMBIE_VILLAGER) return false;
					return !ForgeEventFactory.canLivingConvert(event.getEntity(), outcome, event::setConversionTimer);
				});
	}

	public static void installProjectileImpact(Object neoBus) {
		KernelGameEntityEvents.subscribe((IEventBus) neoBus, ProjectileImpactEvent.class, "ProjectileImpactEvent",
				"a MinecraftForge mod cannot stop or redirect a projectile's hit",
				event -> ForgeEventFactory.onProjectileImpact(event.getProjectile(), event.getRayTraceResult()));
	}

	/** Posted directly: MinecraftForge's own trample hook would ask canTrample again and invert the answer. */
	public static void installFarmlandTrample(Object neoBus) {
		KernelGameEntityEvents.subscribe((IEventBus) neoBus, BlockEvent.FarmlandTrampleEvent.class, "BlockEvent.FarmlandTrampleEvent",
				"a MinecraftForge mod cannot keep farmland from being trampled",
				event -> event.getLevel() instanceof ServerLevel level
						&& ForgeEventFactory.fireFarmlandTrampleEvent(level, event.getPos(), event.getState(),
								event.getFallDistance(), event.getEntity()));
	}

	public static void installPermissionsChanged(Object neoBus) {
		KernelGameEntityEvents.subscribe((IEventBus) neoBus, PermissionsChangedEvent.class, "PermissionsChangedEvent",
				"a MinecraftForge mod never learns a player was opped or deopped, and cannot refuse it",
				event -> net.minecraftforge.event.entity.player.PermissionsChangedEvent.BUS.post(
						new net.minecraftforge.event.entity.player.PermissionsChangedEvent(
								(net.minecraft.server.level.ServerPlayer) event.getEntity(), event.getNewLevel(), event.getOldLevel())));
	}

	/** A command about to run: MinecraftForge's parse results and exception are carried back as well as its cancel. */
	public static void installCommand(Object neoBus) {
		KernelGameEntityEvents.subscribe((IEventBus) neoBus, CommandEvent.class, "CommandEvent",
				"a MinecraftForge mod cannot see, change or refuse a command",
				event -> {
					var forge = new net.minecraftforge.event.CommandEvent(event.getParseResults());
					boolean cancelled = net.minecraftforge.event.CommandEvent.BUS.post(forge);
					if (forge.getParseResults() != event.getParseResults()) event.setParseResults(forge.getParseResults());
					if (forge.getException() != null && event.getException() == null) event.setException(forge.getException());
					return cancelled;
				});
	}

	/** Right-clicking an entity at a point: MinecraftForge's "specific" event, with its cancellation result carried back. */
	public static void installEntityInteractSpecific(Object neoBus) {
		KernelGameEntityEvents.subscribe((IEventBus) neoBus, PlayerInteractEvent.EntityInteract.class, "PlayerInteractEvent.EntityInteract",
				"a MinecraftForge mod's item used on an entity does nothing",
				event -> {
					var forge = new net.minecraftforge.event.entity.player.PlayerInteractEvent.EntityInteractSpecific(
							event.getEntity(), event.getHand(), event.getTarget(), event.getLocation());
					if (!net.minecraftforge.event.entity.player.PlayerInteractEvent.EntityInteractSpecific.BUS.post(forge)) return false;
					event.setCancellationResult(forge.getCancellationResult());
					return true;
				});
	}

	// ---- values carried back ----

	public static void installHeal(Object neoBus) {
		KernelGameEntityEvents.subscribe((IEventBus) neoBus, LivingHealEvent.class, "LivingHealEvent",
				"a MinecraftForge mod cannot stop or change healing",
				event -> {
					var forge = new net.minecraftforge.event.entity.living.LivingHealEvent(event.getEntity(), event.getAmount());
					if (net.minecraftforge.event.entity.living.LivingHealEvent.BUS.post(forge)) return true;
					if (forge.getAmount() != event.getAmount()) event.setAmount(forge.getAmount());
					return false;
				});
	}

	/** How visible an entity is, asked for every mob looking at every target: only when a MinecraftForge mod listens. */
	public static void installVisibility(Object neoBus) {
		KernelGameServerEvents.forward((IEventBus) neoBus, LivingEvent.LivingVisibilityEvent.class, "LivingEvent.LivingVisibilityEvent",
				event -> {
					if (!net.minecraftforge.event.entity.living.LivingEvent.LivingVisibilityEvent.BUS.hasListeners()) return;
					double before = event.getVisibilityModifier();
					if (before == 0) return;
					double after = ForgeHooks.getEntityVisibilityMultiplier(event.getEntity(), event.getLookingEntity(), before);
					if (after != before) event.modifyVisibility(after / before);
				});
	}

	/**
	 * Whether an effect may apply. A spider's poison immunity is still asked of MinecraftForge by the merged Spider, so
	 * that one is skipped; MinecraftForge's answer counts only where NeoForge's listeners left it open.
	 */
	public static void installEffectApplicable(Object neoBus) {
		KernelGameServerEvents.forward((IEventBus) neoBus, MobEffectEvent.Applicable.class, "MobEffectEvent.Applicable",
				event -> {
					if (event.getEntity() instanceof Spider && event.getEffectInstance().is(MobEffects.POISON)) return;
					var forge = new net.minecraftforge.event.entity.living.MobEffectEvent.Applicable(event.getEntity(), event.getEffectInstance());
					var neo = event.getResult();
					if (neo == MobEffectEvent.Applicable.Result.APPLY) forge.setResult(Result.ALLOW);
					else if (neo == MobEffectEvent.Applicable.Result.DO_NOT_APPLY) forge.setResult(Result.DENY);
					net.minecraftforge.event.entity.living.MobEffectEvent.Applicable.BUS.post(forge);
					if (neo != MobEffectEvent.Applicable.Result.DEFAULT || forge.getResult().isDefault()) return;
					event.setResult(forge.getResult().isAllowed() ? MobEffectEvent.Applicable.Result.APPLY
							: MobEffectEvent.Applicable.Result.DO_NOT_APPLY);
				});
	}

	/** A critical hit: posted directly, since MinecraftForge's hook answers null when there is no crit to report. */
	public static void installCriticalHit(Object neoBus) {
		KernelGameServerEvents.forward((IEventBus) neoBus, CriticalHitEvent.class, "CriticalHitEvent",
				event -> {
					var forge = new net.minecraftforge.event.entity.player.CriticalHitEvent(event.getEntity(), event.getTarget(),
							event.getVanillaMultiplier(), event.isVanillaCritical());
					forge.setDamageModifier(event.getDamageMultiplier());
					boolean decided = event.isCriticalHit() != event.isVanillaCritical();
					if (decided) forge.setResult(event.isCriticalHit() ? Result.ALLOW : Result.DENY);
					net.minecraftforge.event.entity.player.CriticalHitEvent.BUS.post(forge);
					if (!decided && !forge.getResult().isDefault()) event.setCriticalHit(forge.getResult().isAllowed());
					if (forge.getDamageModifier() != event.getDamageMultiplier()) event.setDamageMultiplier(forge.getDamageModifier());
				});
	}

	/**
	 * The anvil's result. MinecraftForge asks before vanilla and a non-empty output replaces it; the merged anvil asks
	 * NeoForge after vanilla. So MinecraftForge's output is taken only while NeoForge's listeners left vanilla's result
	 * in place, and its cancel is carried back.
	 */
	public static void installAnvilUpdate(Object neoBus) {
		KernelGameEntityEvents.subscribe((IEventBus) neoBus, AnvilUpdateEvent.class, "AnvilUpdateEvent",
				"a MinecraftForge mod's anvil recipes produce nothing",
				event -> {
					long base = (long) (Integer) event.getLeft().getOrDefault(DataComponents.REPAIR_COST, 0)
							+ (Integer) event.getRight().getOrDefault(DataComponents.REPAIR_COST, 0);
					var forge = new net.minecraftforge.event.AnvilUpdateEvent(event.getLeft(), event.getRight(), event.getName(), base,
							event.getPlayer());
					if (net.minecraftforge.event.AnvilUpdateEvent.BUS.post(forge)) return true;
					var vanilla = event.getVanillaResult();
					boolean untouched = ItemStack.matches(event.getOutput(), vanilla.output())
							&& event.getXpCost() == vanilla.xpCost() && event.getMaterialCost() == vanilla.materialCost();
					if (!forge.getOutput().isEmpty() && untouched) {
						event.setOutput(forge.getOutput());
						event.setXpCost((int) forge.getCost());
						event.setMaterialCost(forge.getMaterialCost());
					}
					return false;
				});
	}

	/** Taking an anvil's result: MinecraftForge is told; nothing it can change has a NeoForge counterpart. */
	public static void installAnvilRepair(Object neoBus) {
		KernelGameServerEvents.forward((IEventBus) neoBus, AnvilCraftEvent.Pre.class, "AnvilCraftEvent.Pre",
				event -> {
					if (event.isCanceled()) return;
					ForgeEventFactory.onAnvilRepair(event.getEntity(), event.getOutput(), event.getLeft(), event.getRight());
				});
	}

	/** A tool changing a block (tilling, stripping, flattening): MinecraftForge's action has the same name as NeoForge's. */
	public static void installToolModification(Object neoBus) {
		KernelGameEntityEvents.subscribe((IEventBus) neoBus, BlockEvent.BlockToolModificationEvent.class, "BlockEvent.BlockToolModificationEvent",
				"a MinecraftForge mod's tillable, strippable or flattenable blocks cannot be changed with a tool",
				event -> {
					var forge = ForgeEventFactory.onToolUse(event.getState(), event.getContext(),
							ToolAction.get(event.getItemAbility().name()), event.isSimulated());
					if (forge == null) return true;
					if (forge != event.getState() && event.getFinalState() == event.getState()) event.setFinalState(forge);
					return false;
				});
	}
}
