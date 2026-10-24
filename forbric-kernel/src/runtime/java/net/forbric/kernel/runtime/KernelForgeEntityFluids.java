/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

import net.forbric.kernel.util.Caught;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.fluids.FluidType;
import net.neoforged.neoforge.common.NeoForgeMod;

/**
 * MinecraftForge's half of {@code EntityFluidInteraction}: what its own {@code update} records about the fluids an entity
 * is in, which the merge dropped (ForgeEntityFluidsInjector feeds it from the merged {@code update}, cell by cell).
 *
 * <p>The merged class is NeoForge's: its {@code update} fills trackers keyed by NeoForge fluid type, and its entity code
 * asks those. MinecraftForge's {@code update} fills two other things, and the merged class kept MinecraftForge's queries
 * of both but no code that writes them:
 * <ul>
 *   <li>MinecraftForge's own state — whether the entity touches any fluid, the MinecraftForge type its eyes are in, and a
 *       height and eye flag per MinecraftForge type. {@code getEyeInFluidType()}, {@code isInFluidType()},
 *       {@code isInFluidType(BiPredicate, boolean)}, {@code getFluidTypeHeight}, {@code getMaxHeightFluidType} — every
 *       entity-fluid question a MinecraftForge mod asks, and the merged {@code ScreenEffectRenderer}'s overlay of a
 *       MinecraftForge fluid — read it, so they answered "no fluid" in vanilla water too. Here it is recorded for every
 *       fluid MinecraftForge types itself: vanilla's, and a MinecraftForge mod's. A Fabric or NeoForge mod's fluid holds
 *       the kernel's tag-derived stand-in ({@link KernelFluidTypes#forgeTypeIsStandIn}), whose client extensions describe
 *       some other fluid, so it is left out, as before.</li>
 *   <li>Its trackers by fluid tag. MinecraftForge decides "in water" and "in lava" by {@code minecraft:water} and
 *       {@code minecraft:lava}: a MinecraftForge mod's fluid in one of them puts a body in water or lava as well as in its
 *       own type. NeoForge asks the same questions of its water and lava types ({@code isInLava()} is
 *       {@code isInFluid(LAVA_TYPE)}, a tag query goes through {@code getFluidTypeByTag}), which such a fluid — its NeoForge
 *       type is its own ({@link KernelForgeFluidTypeViews}) — is never in. The merged type queries for NeoForge's water or
 *       lava type therefore also count a MinecraftForge mod's fluid in that tag ({@link #neoHeight} and the two beside it).
 *       Only those: a NeoForge mod's fluid is decided as on NeoForge, and vanilla's and a Fabric fluid's already have
 *       NeoForge's water or lava type.</li>
 * </ul>
 * Each entity's interaction carries one {@link State}, made with the tags it tracks (an entity's are water and lava).
 * {@code -Dforbric.forgeEntityFluids=off} leaves both as merged: MinecraftForge's queries answer "no fluid", and a
 * MinecraftForge mod's fluid in {@code minecraft:water} is not water to NeoForge's entity code.
 */
public final class KernelForgeEntityFluids {
	public static final String PROPERTY = "forbric.forgeEntityFluids";
	private static final MethodHandle FORGE_TYPE = forgeTypeGetter();

	private KernelForgeEntityFluids() {
	}

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	private static MethodHandle forgeTypeGetter() {
		try {
			return MethodHandles.publicLookup().findVirtual(Fluid.class, "getFluidType", MethodType.methodType(FluidType.class));
		} catch (ReflectiveOperationException missing) {
			throw new NoSuchMethodError("Fluid.getFluidType()Lnet/minecraftforge/fluids/FluidType; " + missing);
		}
	}

	/** One tracker: MinecraftForge's {@code EntityFluidInteraction.Tracker} without the current it never reads back here. */
	static final class Tracker {
		double height;
		boolean eyes;
	}

	/** What MinecraftForge's {@code update} last recorded for one entity. Ticked on the entity's own thread only. */
	public static final class State {
		final List<TagKey<Fluid>> tags;
		final Tracker[] byTag;
		/** By MinecraftForge type, in the order the types were first met, as MinecraftForge's map keeps them. */
		final Map<FluidType, Tracker> byType = new LinkedHashMap<>();
		boolean inFluid;
		FluidType eye;
		boolean tagged;
		boolean touched;
		double boxMinY, bodyMinY, eyeY;
		int blockX, blockZ;

		State(Set<TagKey<Fluid>> tracked) {
			tags = tracked == null ? List.of() : List.copyOf(tracked);
			byTag = new Tracker[tags.size()];
			for (int i = 0; i < byTag.length; i++) byTag[i] = new Tracker();
		}

		Tracker tag(TagKey<Fluid> tag) {
			int i = tags.indexOf(tag);
			return i < 0 ? null : byTag[i];
		}
	}

	/** Made at the end of the interaction's constructor, with the tags it was made with; null when switched off. */
	public static Object state(Set<TagKey<Fluid>> tags) {
		return enabled() ? new State(tags) : null;
	}

	/** First thing in the merged {@code update}: MinecraftForge resets everything before it walks the cells. */
	public static void begin(Object state, Entity entity) {
		if (!(state instanceof State s)) return;
		if (s.touched) {
			for (Tracker t : s.byType.values()) { t.height = 0; t.eyes = false; }
			for (Tracker t : s.byTag) { t.height = 0; t.eyes = false; }
			s.inFluid = false;
			s.eye = null;
			s.tagged = false;
			s.touched = false;
		}
		AABB box = entity.getFluidInteractionBox();
		s.boxMinY = box == null ? Double.POSITIVE_INFINITY : box.minY;
		s.bodyMinY = entity.getBoundingBox().minY;
		s.eyeY = entity.getEyeY();
		s.blockX = entity.getBlockX();
		s.blockZ = entity.getBlockZ();
	}

	/** Each cell the merged {@code update} reads, right after it reads it: what MinecraftForge's loop records there. */
	public static void cell(Object state, FluidState fluidState, BlockGetter level, BlockPos pos) {
		if (!(state instanceof State s) || fluidState.isEmpty()) return;
		s.inFluid = true;
		s.touched = true;
		double y = pos.getY();
		double top = y + fluidState.getHeight(level, pos);
		if (top < s.boxMinY) return;
		Fluid fluid = fluidState.getType();
		boolean eyes = pos.getX() == s.blockX && pos.getZ() == s.blockZ && s.eyeY >= y && s.eyeY <= top;
		double height = top - s.bodyMinY;
		FluidType type = forgeType(fluid);
		if (type != null && !KernelFluidTypes.forgeTypeIsStandIn(fluid)) {
			Tracker t = s.byType.computeIfAbsent(type, unused -> new Tracker());
			t.height = Math.max(height, t.height);
			if (eyes) {
				t.eyes = true;
				s.eye = type;
			}
		}
		if (!s.tags.isEmpty() && KernelForgeFluidTypeViews.ownsForgeType(fluid)) {
			for (int i = 0; i < s.byTag.length; i++) {
				if (!in(fluid, s.tags.get(i))) continue;
				Tracker t = s.byTag[i];
				t.height = Math.max(height, t.height);
				if (eyes) t.eyes = true;
				s.tagged = true;
			}
		}
	}

	private static boolean in(Fluid fluid, TagKey<Fluid> tag) {
		try {
			return fluid.is(tag);
		} catch (IllegalStateException unbound) {
			// Tags are not bound yet (registration): nothing is in a world before they are.
			Caught.expected(unbound);
			return false;
		}
	}

	/** The fluid's MinecraftForge type, or null when it cannot be read yet (a mod's registry entry still unfilled). */
	private static FluidType forgeType(Fluid fluid) {
		try {
			return (FluidType) FORGE_TYPE.invokeExact(fluid);
		} catch (RuntimeException notYet) {
			// Caught prints a site a bounded number of times and counts the rest: an entity standing in such a fluid every
			// tick is one site, not a flood.
			Caught.warn(notYet, "[Forbric/Fluid] %s's MinecraftForge fluid type could not be read for an entity in it; "
					+ "MinecraftForge's entity-fluid queries leave that fluid out until it can", fluid.getClass().getName());
			return null;
		} catch (Error error) {
			throw error;
		} catch (Throwable checked) {
			throw new IllegalStateException(checked);
		}
	}

	// ---- MinecraftForge's queries, as its EntityFluidInteraction answers them from what its update recorded ----

	/** {@code getFluidHeight(FluidType)}. */
	public static double forgeHeight(Object state, FluidType type) {
		Tracker t = state instanceof State s ? s.byType.get(type) : null;
		return t == null ? 0 : t.height;
	}

	/** {@code isEyeInFluid(FluidType)}. */
	public static boolean forgeEyes(Object state, FluidType type) {
		Tracker t = state instanceof State s ? s.byType.get(type) : null;
		return t != null && t.eyes;
	}

	/** {@code getEyeFluid()}: MinecraftForge's empty type when the eyes are in none. */
	public static FluidType forgeEye(Object state) {
		FluidType eye = state instanceof State s ? s.eye : null;
		return eye != null ? eye : net.minecraftforge.common.ForgeMod.EMPTY_TYPE.get();
	}

	/** {@code isInFluid()}: any fluid cell inside the interaction box, whatever its height. */
	public static boolean forgeInFluid(Object state) {
		return state instanceof State s && s.inFluid;
	}

	/**
	 * {@code isInFluid(BiPredicate, boolean)}, MinecraftForge's loop exactly: over every type met so far, in its order —
	 * with {@code all} false, whether one the entity is in (height above zero) matches; with {@code all} true, false at the
	 * first type that is not both in and matching (one met earlier and left counts), else whether one matched.
	 */
	public static boolean forgeMatching(Object state, BiPredicate<FluidType, Double> predicate, boolean all) {
		if (!(state instanceof State s)) return false;
		boolean found = false;
		for (Map.Entry<FluidType, Tracker> entry : s.byType.entrySet()) {
			double height = entry.getValue().height;
			if (height > 0 && predicate.test(entry.getKey(), height)) {
				found = true;
				if (!all) return true;
			} else if (all) {
				return false;
			}
		}
		return found;
	}

	/** {@code getMaxHeightFluid()}: the first type with the greatest height, else the empty type. */
	public static FluidType forgeMaxHeight(Object state) {
		FluidType best = net.minecraftforge.common.ForgeMod.EMPTY_TYPE.get();
		if (!(state instanceof State s)) return best;
		double max = 0;
		for (Map.Entry<FluidType, Tracker> entry : s.byType.entrySet()) {
			if (entry.getValue().height > max) {
				max = entry.getValue().height;
				best = entry.getKey();
			}
		}
		return best;
	}

	// ---- NeoForge's water and lava questions, widened by a MinecraftForge mod's fluid in minecraft:water / minecraft:lava ----

	private static Tracker tagged(Object state, net.neoforged.neoforge.fluids.FluidType type) {
		if (!(state instanceof State s) || !s.tagged) return null;
		if (type == NeoForgeMod.WATER_TYPE.value()) return s.tag(FluidTags.WATER);
		if (type == NeoForgeMod.LAVA_TYPE.value()) return s.tag(FluidTags.LAVA);
		return null;
	}

	/** {@code getFluidHeight(FluidType)}: NeoForge's answer, or a MinecraftForge mod's fluid in that type's tag if higher. */
	public static double neoHeight(Object state, net.neoforged.neoforge.fluids.FluidType type, double neo) {
		Tracker t = tagged(state, type);
		return t == null ? neo : Math.max(neo, t.height);
	}

	/** {@code isInFluid(FluidType)}: NeoForge's answer, or a MinecraftForge mod's fluid in that type's tag. */
	public static boolean neoIn(Object state, net.neoforged.neoforge.fluids.FluidType type, boolean neo) {
		if (neo) return true;
		Tracker t = tagged(state, type);
		return t != null && t.height > 0;
	}

	/** {@code isEyeInFluid(FluidType)}: NeoForge's answer, or eyes in a MinecraftForge mod's fluid in that type's tag. */
	public static boolean neoEyes(Object state, net.neoforged.neoforge.fluids.FluidType type, boolean neo) {
		if (neo) return true;
		Tracker t = tagged(state, type);
		return t != null && t.eyes;
	}
}
