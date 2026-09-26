/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import net.forbric.kernel.util.ForbricLog;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;

/**
 * fabric-content-registries' fluid behaviours as the fluid types the merged entity code asks (KernelFluidTypes).
 *
 * <p>A Fabric mod gives its fluid entity physics with {@code EntityFluidInteractionRegistry.register(tag, behaviour)}:
 * whether mobs swim or drown in it, whether a boat floats on it, how it pushes. Vanilla asks those questions through
 * fluid tags and fabric-api's mixins add the registered tags beside water and lava. The merged entity code asks
 * NeoForge's {@code FluidType} instead ({@code canSwimInFluidType} in updateSwimming, {@code canDrownInFluidType} in
 * {@code CommonHooks.onLivingBreathe}, {@code supportsBoating} in the boat's {@code canBoatInFluid}), where
 * ForeignFluidTypeInjector gave such a fluid NeoForge's empty type — so none of it happened. Worse, fabric-api's own
 * per-tick hook ({@code EntityMixin.handleCustomFluidInteractionUpdates}) and its air-bubble HUD ask the merged
 * {@code EntityFluidInteraction} about each registered tag, which answers only water and lava and throws for any
 * other: the first entity to tick after a Fabric mod registered a behaviour took the server down
 * (FabricFluidBehaviorInjector).
 *
 * <p>So each registered tag gets one type of each family — {@link NeoType}, {@link ForgeType} — and a foreign fluid in
 * that tag gets it (the water and lava tags still win, as before). The type answers canSwim, canDrownIn and
 * supportsBoating from the behaviour, and has NeoForge's empty type's properties, today's answer, in everything else but
 * the push — but it is not air: {@code FluidType.isAir()} is final and true for the empty type itself only.
 * Pushing and fall distance are fabric-api's: its per-tick hook hands the entity to the behaviour, which pushes through
 * {@code applyCurrentTo(tag, …)} — the tracker of this same type, since the merged class tracks by type identity — and
 * scales the fall distance itself. NeoForge gathers a tracker's current only for a type that {@code canPushEntity},
 * then applies it at {@code motionScale} without using it up; so the type pushes at scale zero: the current is there
 * for the behaviour, NeoForge adds none of it, and fabric-api applies it once. Its fall-distance modifier stays 1.
 *
 * <p>The types are not in NeoForge's or MinecraftForge's fluid-type registry, and need not be: the trackers key them
 * by identity, the client's fluid extensions default for an unknown type, the description id is set rather than
 * derived from a registry name, and NeoForge's own {@code toString} prints "Unregistered FluidType" for one.
 * Not covered: travel through the fluid (the merged {@code travelInFluid} asks only water's type, so no fluid type's
 * {@code move} is reached, and fabric-api's {@code travelInCustomFluid} anchor is gone — a body moves as through lava,
 * as it did before); breathing in a fluid whose behaviour does not drown (NeoForge's {@code CommonHooks.onLivingBreathe}
 * refills air only in air, so the air bar holds while the eyes are in it, where on Fabric it refills); a behaviour on a
 * tag holding a vanilla or NeoForge fluid (NeoForge answers those fluids; said once per tag, when the tag's fluids are
 * known the first time it is asked about — a question before tags are loaded, as a client's resource reload can ask,
 * says nothing); and a fluid in two behaviour tags (the first by tag name wins).
 * {@code -Dforbric.fabricFluidBehavior=off} leaves foreign fluids with the tag-implied type and the merged class as is.
 */
public final class KernelFabricFluidBehaviors {
	public static final String PROPERTY = "forbric.fabricFluidBehavior";
	private static final String REGISTRY = "net.fabricmc.fabric.impl.content.registry.fluid.EntityFluidInteractionRegistryImpl";
	private static final String BEHAVIOR = "net.fabricmc.fabric.api.registry.fluid.FluidBehavior";

	private static final Map<TagKey<Fluid>, NeoType> NEO = new ConcurrentHashMap<>();
	private static final Map<TagKey<Fluid>, ForgeType> FORGE = new ConcurrentHashMap<>();
	private static final Set<TagKey<Fluid>> WARNED_NATIVE = ConcurrentHashMap.newKeySet();
	private static volatile boolean resolved;
	private static volatile Handles handles;
	private static volatile List<TagKey<Fluid>> ordered = List.of();
	private static volatile int orderedFrom = -1;

	/** fabric-content-registries' registry and the three behaviour questions a fluid type answers. */
	record Handles(MethodHandle tracked, MethodHandle behavior, MethodHandle canSwim, MethodHandle canDrown, MethodHandle canBoat) {
	}

	private KernelFabricFluidBehaviors() {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/** The behaviour tag a foreign fluid state is in, or null: none registered, fabric-content-registries absent, or switched off. */
	static TagKey<Fluid> tagOf(FluidState state) {
		Handles h = handles();
		if (h == null) return null;
		for (TagKey<Fluid> tag : ordered(h)) if (state.is(tag)) return tag;
		return null;
	}

	/**
	 * Whether Fabric registered a behaviour for {@code tag}. By its tracked set, never {@code getFluidBehavior}: that
	 * one {@code requireNonNull}s, and every tag somebody asks {@code getFluidTypeByTag} about comes through here.
	 */
	static boolean registered(TagKey<Fluid> tag) {
		Handles h = handles();
		return h != null && tracked(h).contains(tag);
	}

	static net.neoforged.neoforge.fluids.FluidType neoType(TagKey<Fluid> tag) {
		return NEO.computeIfAbsent(tag, NeoType::new);
	}

	static net.minecraftforge.fluids.FluidType forgeType(TagKey<Fluid> tag) {
		return FORGE.computeIfAbsent(tag, ForgeType::new);
	}

	/** Registered tags in a fixed order (by name), re-sorted only when fabric-api's map has changed size. */
	private static List<TagKey<Fluid>> ordered(Handles h) {
		Collection<TagKey<Fluid>> tracked = tracked(h);
		if (tracked.size() != orderedFrom) {
			List<TagKey<Fluid>> sorted = new ArrayList<>(tracked);
			sorted.sort(Comparator.comparing(tag -> tag.location().toString()));
			ordered = List.copyOf(sorted);
			orderedFrom = tracked.size();
			for (TagKey<Fluid> tag : sorted) warnIfNative(tag);
		}
		return ordered;
	}

	/** Once per tag: a behaviour on a tag that holds a vanilla or NeoForge fluid does not reach it; NeoForge answers it. */
	private static void warnIfNative(TagKey<Fluid> tag) {
		try {
			for (Holder<Fluid> holder : BuiltInRegistries.FLUID.getTagOrEmpty(tag)) {
				if (KernelFluidTypes.isNative(holder.value()) && WARNED_NATIVE.add(tag)) {
					ForbricLog.warn("[Forbric/Fluid] fabric-api fluid behaviour on %s also names %s, a vanilla or NeoForge fluid: "
							+ "NeoForge's own fluid type answers for it, so the behaviour applies to the tag's other fluids only",
							tag.location(), BuiltInRegistries.FLUID.getKey(holder.value()));
					return;
				}
			}
		} catch (RuntimeException unbound) {
			// Tags not bound yet: asked again only when fabric-api's tracked set changes size.
		}
	}

	@SuppressWarnings("unchecked")
	private static Collection<TagKey<Fluid>> tracked(Handles h) {
		try {
			return (Collection<TagKey<Fluid>>) h.tracked().invoke();
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	private static Handles handles() {
		if (!enabled()) return null;
		if (!resolved) resolve(Fluid.class.getClassLoader());
		return handles;
	}

	/**
	 * fabric-content-registries, once; null (for good) when it is not installed, does not link, or its shape changed.
	 * Never an Error out of here: a foreign fluid's type is asked inside the client's resource reload too (a fluid
	 * container's item model), and a LinkageError escaping into a reload drops every resource pack for a black screen.
	 * {@code resolved} is set last: handles() reads {@code handles} without the lock once it sees it, and the server's
	 * entity tick and the render thread's air bubbles can ask first together.
	 */
	static synchronized void resolve(ClassLoader loader) {
		if (resolved) return;
		try {
			handles = lookUp(loader);
		} finally {
			resolved = true;
		}
	}

	private static Handles lookUp(ClassLoader loader) {
		Class<?> registry, behaviour;
		try {
			registry = Class.forName(REGISTRY, true, loader);
			behaviour = Class.forName(BEHAVIOR, false, registry.getClassLoader());
		} catch (ClassNotFoundException absent) {
			return null;
		} catch (LinkageError broken) {
			ForbricLog.warn("[Forbric/Fluid] fabric-content-registries' fluid behaviour registry does not load on this game — "
					+ "a Fabric mod's fluid behaviour is not applied, and its fluid acts as its tags say", broken);
			return null;
		}
		try {
			MethodHandles.Lookup lookup = MethodHandles.publicLookup();
			return new Handles(
					lookup.findStatic(registry, "getTrackedFluids", MethodType.methodType(Collection.class)),
					lookup.findStatic(registry, "getFluidBehavior", MethodType.methodType(behaviour, TagKey.class)),
					lookup.findVirtual(behaviour, "canSwimInFluid", MethodType.methodType(boolean.class, TagKey.class, Entity.class)),
					lookup.findVirtual(behaviour, "canDrownInFluid", MethodType.methodType(boolean.class, TagKey.class, LivingEntity.class)),
					lookup.findVirtual(behaviour, "canSupportBoat", MethodType.methodType(boolean.class, TagKey.class, Entity.class)));
		} catch (ReflectiveOperationException | RuntimeException | LinkageError drifted) {
			ForbricLog.warn("[Forbric/Fluid] fabric-content-registries' fluid behaviour API is not the one this was written for — "
					+ "a Fabric mod's fluid behaviour is not applied, and its fluid acts as its tags say", drifted);
			return null;
		}
	}

	/**
	 * The behaviour's answer to {@code question} about {@code tag}; false — the empty type's answer, and fabric-api's
	 * own default for all three — when there is none (switched off since). A mod's exception is the mod's.
	 */
	private static boolean ask(Function<Handles, MethodHandle> question, TagKey<Fluid> tag, Entity entity) {
		Handles h = handles();
		if (h == null || !tracked(h).contains(tag)) return false;
		try {
			return (boolean) question.apply(h).invoke(h.behavior().invoke(tag), tag, entity);
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	private static RuntimeException rethrow(Throwable t) {
		if (t instanceof RuntimeException runtime) throw runtime;
		if (t instanceof Error error) throw error;
		throw new IllegalStateException(t);
	}

	/**
	 * NeoForge's fluid type for a Fabric behaviour tag: the properties of NeoForge's own empty type
	 * ({@code NeoForgeMod.EMPTY_TYPE}), so a question the behaviour does not answer keeps today's answer — but pushing
	 * at scale zero, so the tracker gathers the current fabric-api's tick applies, and not air (only the empty type is).
	 */
	static final class NeoType extends net.neoforged.neoforge.fluids.FluidType {
		final TagKey<Fluid> tag;

		NeoType(TagKey<Fluid> tag) {
			super(Properties.create().descriptionId("block.minecraft.air").motionScale(0.0).canPushEntity(true).canSwim(false)
					.canDrown(false).fallDistanceModifier(1.0F).pathType(null).adjacentPathType(null).density(0).temperature(0)
					.viscosity(0));
			this.tag = tag;
		}

		@Override public boolean canSwim(Entity entity) {
			return ask(Handles::canSwim, tag, entity);
		}

		@Override public boolean canDrownIn(LivingEntity entity) {
			return ask(Handles::canDrown, tag, entity);
		}

		@Override public boolean supportsBoating(AbstractBoat boat) {
			return ask(Handles::canBoat, tag, boat);
		}

		/**
		 * As the empty type moves it: unlike that one, this type is not air, so ItemEntity hands it the item — and
		 * FluidType's own default would float it, where vanilla lets an item fall through a fluid in no water or lava tag.
		 */
		@Override public void setItemMovement(ItemEntity item) {
			net.neoforged.neoforge.common.NeoForgeMod.EMPTY_TYPE.value().setItemMovement(item);
		}
	}

	/** MinecraftForge's fluid type for the same tag, from MinecraftForge's own empty type: MinecraftForge mods ask this family. */
	static final class ForgeType extends net.minecraftforge.fluids.FluidType {
		final TagKey<Fluid> tag;

		ForgeType(TagKey<Fluid> tag) {
			super(Properties.create().descriptionId("block.minecraft.air").motionScale(0.0).canPushEntity(true).canSwim(false)
					.canDrown(false).fallDistanceModifier(1.0F).pathType(null).adjacentPathType(null).density(0).temperature(0)
					.viscosity(0));
			this.tag = tag;
		}

		@Override public boolean canSwim(Entity entity) {
			return ask(Handles::canSwim, tag, entity);
		}

		@Override public boolean canDrownIn(LivingEntity entity) {
			return ask(Handles::canDrown, tag, entity);
		}

		@Override public boolean supportsBoating(AbstractBoat boat) {
			return ask(Handles::canBoat, tag, boat);
		}

		@Override public void setItemMovement(ItemEntity item) {
			net.minecraftforge.common.ForgeMod.EMPTY_TYPE.get().setItemMovement(item);
		}
	}
}
