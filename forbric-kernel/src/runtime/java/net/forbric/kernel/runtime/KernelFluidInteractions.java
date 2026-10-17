/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.ForbricLog;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraftforge.fluids.FluidInteractionRegistry.InteractionInformation;

/**
 * MinecraftForge mods' fluid interactions, asked from NeoForge's walk when a block next to a liquid changes
 * (FluidInteractionsInjector).
 *
 * <p>The merged {@code LiquidBlock.onPlace} is MinecraftForge's and asks MinecraftForge's registry itself, as on
 * MinecraftForge. The merged {@code neighborChanged} is NeoForge's and asks NeoForge's registry, which walks the
 * neighbours and, at each, its rules — vanilla's two, then NeoForge mods'. Where those run out at one neighbour, it asks
 * {@link #minecraftForge} about the same neighbour: MinecraftForge's walk has a mod's rule at a neighbour beat vanilla's
 * at a later one, and so does this. MinecraftForge's own copies of vanilla's rules are skipped, since NeoForge's identical
 * ones were just asked about that neighbour; what can match here is a MinecraftForge mod's rule.
 *
 * <p>MinecraftForge's registry hands its map over at the end of its initializer, when it holds only those copies
 * ({@link #minecraftForgeRegistry}), and its {@code addInteraction} reports every later add ({@link #minecraftForgeInUse}).
 * Until a MinecraftForge mod adds a rule, a neighbour change is answered without a lookup — a game with no such mod pays
 * nothing for it, and never loads the class from here.
 *
 * <p>A rule that does not link — a MinecraftForge class or member the merged game lacks — is reported once and then left
 * out, so liquids keep NeoForge's answer rather than failing every neighbour change. Anything a mod's own rule throws is
 * the mod's, and propagates as it would on MinecraftForge.
 */
public final class KernelFluidInteractions {
	/** MinecraftForge's INTERACTIONS (its fluid type to its rules), from the end of its initializer. */
	private static volatile Map<?, ? extends List<?>> minecraftForgeRules;
	/** Its copies of vanilla's rules: everything in the map when it was handed over. */
	private static volatile Set<Object> vanillaCopies = Set.of();
	private static volatile boolean inUse;
	private static final AtomicBoolean BROKE = new AtomicBoolean();

	private KernelFluidInteractions() {
	}

	/** Last thing in MinecraftForge's registry initializer: its map, holding its copies of vanilla's rules so far. */
	public static void minecraftForgeRegistry(Map<?, ? extends List<?>> interactions) {
		Set<Object> copies = Collections.newSetFromMap(new IdentityHashMap<>());
		for (List<?> rules : interactions.values()) copies.addAll(rules);
		vanillaCopies = copies;
		minecraftForgeRules = interactions;
	}

	/** First thing in MinecraftForge's {@code addInteraction}: after its initializer, every add is a mod's. */
	public static void minecraftForgeInUse() {
		if (inUse || minecraftForgeRules == null) return;   // its initializer adding vanilla's rules
		inUse = true;
		ForbricLog.info("[Forbric/Fluid] a MinecraftForge mod added a fluid interaction — a block changing next to a liquid asks "
				+ "MinecraftForge mods' interactions at each neighbour after NeoForge's");
	}

	/**
	 * Whether a MinecraftForge mod's rule for the liquid at {@code pos} matched at {@code relative} — and ran, as
	 * MinecraftForge's walk runs it. Asked when none of NeoForge's rules matched that neighbour.
	 */
	public static boolean minecraftForge(Level level, BlockPos pos, BlockPos relative) {
		if (!inUse || BROKE.get()) return false;
		try {
			FluidState state = level.getFluidState(pos);
			List<?> rules = minecraftForgeRules.get(MinecraftForgeType.of(state));
			if (rules == null) return false;
			Set<Object> copies = vanillaCopies;
			for (Object rule : rules) {
				if (copies.contains(rule)) continue;
				InteractionInformation information = (InteractionInformation) rule;
				if (information.predicate().test(level, pos, relative, state)) {
					information.interaction().interact(level, pos, relative, state);
					return true;
				}
			}
			return false;
		} catch (LinkageError broken) {
			BROKE.set(true);
			Caught.warn(broken, "[Forbric/Fluid] MinecraftForge's FluidInteractionRegistry (or a rule in it) failed to link on the "
					+ "merged game — MinecraftForge mods' fluid interactions are left out of neighbour changes from now on; "
					+ "vanilla's and NeoForge mods' still run");
			return false;
		}
	}

	/** The liquid's MinecraftForge {@code getFluidType()}: the merged FluidState also has NeoForge's, by the same name. */
	private static final class MinecraftForgeType {
		private static final MethodHandle TYPE;

		static {
			try {
				TYPE = MethodHandles.publicLookup().findVirtual(FluidState.class, "getFluidType",
						MethodType.methodType(net.minecraftforge.fluids.FluidType.class));
			} catch (ReflectiveOperationException missing) {
				throw new NoSuchMethodError("FluidState.getFluidType()Lnet/minecraftforge/fluids/FluidType; " + missing);
			}
		}

		static Object of(FluidState state) {
			try {
				return (net.minecraftforge.fluids.FluidType) TYPE.invokeExact(state);
			} catch (RuntimeException | Error thrown) {
				throw thrown;
			} catch (Throwable checked) {
				throw new IllegalStateException(checked);
			}
		}
	}
}
