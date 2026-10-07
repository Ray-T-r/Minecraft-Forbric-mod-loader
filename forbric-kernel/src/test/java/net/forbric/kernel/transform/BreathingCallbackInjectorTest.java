/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;
import net.forbric.kernel.interop.BreathingCallbackScope;

/**
 * {@link BreathingCallbackInjector}'s output, run: NeoForge's air-supply hook asks Create's diving gear, carried in
 * {@link BreathingCallbackScope}, after its own water-breathing check, and lets the native answer stand when no Create
 * call is in progress.
 *
 * <p>The stand-in {@code CommonHooks.onLivingBreathe} keeps the one shape the edit keys on: a single
 * {@code MobEffectUtil.hasWaterBreathing(entity)} deciding between refilling and consuming air. The scope is the
 * kernel's own class.
 */
@ExecutesInjector(BreathingCallbackInjector.class)
class BreathingCallbackInjectorTest {
	private static final String HOOKS_INTERNAL = "net/neoforged/neoforge/common/CommonHooks";
	private static final String ENTITY = "net.minecraft.world.entity.LivingEntity";

	private static final Map<String, String> STAND_INS = Map.of(
			ENTITY, """
					package net.minecraft.world.entity;

					public class LivingEntity {
						public boolean underWater;
						public boolean waterBreathingEffect;
						public int air = 100;
					}
					""",
			"net.minecraft.server.level.ServerLevel", """
					package net.minecraft.server.level;

					public class ServerLevel {
					}
					""",
			"net.minecraft.world.effect.MobEffectUtil", """
					package net.minecraft.world.effect;

					import net.minecraft.world.entity.LivingEntity;

					public class MobEffectUtil {
						public static boolean hasWaterBreathing(LivingEntity entity) {
							return entity.waterBreathingEffect;
						}
					}
					""",
			BreathingCallbackInjector.TARGET, """
					package net.neoforged.neoforge.common;

					import net.minecraft.server.level.ServerLevel;
					import net.minecraft.world.effect.MobEffectUtil;
					import net.minecraft.world.entity.LivingEntity;

					public class CommonHooks {
						public static void onLivingBreathe(LivingEntity entity, ServerLevel level, int consume, int refill) {
							boolean canBreathe = !entity.underWater || MobEffectUtil.hasWaterBreathing(entity);
							entity.air = canBreathe ? Math.min(entity.air + refill, 300) : entity.air - consume;
						}
					}
					""");

	private static ClassLoader transformed(Map<String, byte[]> original) {
		byte[] hooks = original.get(HOOKS_INTERNAL);
		byte[] edited = InjectorExecution.transform(new BreathingCallbackInjector(), BreathingCallbackInjector.TARGET, hooks,
				EnvType.SERVER);
		assertNotSame(hooks, edited, "the injector did not touch onLivingBreathe");
		assertSame(edited, InjectorExecution.transform(new BreathingCallbackInjector(), BreathingCallbackInjector.TARGET, edited,
				EnvType.SERVER), "a second pass adds nothing");
		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(HOOKS_INTERNAL, edited);
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(edited, loader));
		return loader;
	}

	private static Object diver(ClassLoader loader) throws Throwable {
		Object entity = InjectorExecution.construct(loader.loadClass(ENTITY));
		entity.getClass().getField("underWater").setBoolean(entity, true);
		return entity;
	}

	private static int breathe(ClassLoader loader, Object entity) throws Throwable {
		Object level = InjectorExecution.construct(loader.loadClass("net.minecraft.server.level.ServerLevel"));
		InjectorExecution.invokeStatic(loader.loadClass(BreathingCallbackInjector.TARGET), "onLivingBreathe", entity, level, 4, 10);
		return entity.getClass().getField("air").getInt(entity);
	}

	/**
	 * A diving helmet under water: native says "no water breathing", Create's callback says "breathes". Inside the
	 * scope Create's answer refills the air; the lava callback sees the same entity and level first.
	 */
	@Test void insideCreatesScopeItsWaterAnswerDecidesAndItsLavaCallbackRuns(@TempDir Path work) throws Throwable {
		ClassLoader loader = transformed(InjectorExecution.compile(work, STAND_INS));
		Object entity = diver(loader);
		List<String> seen = new ArrayList<>();
		Function<Object[], Object> lava = args -> {
			seen.add("lava " + (args[0] == entity) + " " + args[1].getClass().getSimpleName());
			return null;
		};
		Function<Object[], Object> water = args -> {
			seen.add("water " + (args[0] == entity) + " native=" + args[1] + " " + args[2].getClass().getSimpleName());
			return Boolean.TRUE;
		};
		Object previous = BreathingCallbackScope.enter(lava, water);
		try {
			assertEquals(110, breathe(loader, entity), "Create's diving gear lets the entity breathe: air is refilled");
		} finally {
			BreathingCallbackScope.leave(previous);
		}
		assertEquals(List.of("lava true ServerLevel", "water true native=false ServerLevel"), seen);
	}

	/** Outside any Create call the native answer stands, both ways. */
	@Test void outsideTheScopeTheNativeAnswerStands(@TempDir Path work) throws Throwable {
		ClassLoader loader = transformed(InjectorExecution.compile(work, STAND_INS));
		assertEquals(96, breathe(loader, diver(loader)), "no water breathing, no Create: air is consumed");
		Object potion = diver(loader);
		potion.getClass().getField("waterBreathingEffect").setBoolean(potion, true);
		assertEquals(110, breathe(loader, potion), "the native water-breathing effect still refills");
	}

	/** Premise: untransformed, NeoForge's hook never asks Create, so the diving helmet drowns its wearer. */
	@Test void untransformedTheScopeIsNeverAsked(@TempDir Path work) throws Throwable {
		ClassLoader loader = InjectorExecution.load(InjectorExecution.compile(work, STAND_INS));
		Object previous = BreathingCallbackScope.enter(args -> {
			throw new AssertionError("lava asked");
		}, args -> {
			throw new AssertionError("water asked");
		});
		try {
			assertEquals(96, breathe(loader, diver(loader)));
		} finally {
			BreathingCallbackScope.leave(previous);
		}
	}

	@Test void otherClassesAreLeftAlone(@TempDir Path work) throws Exception {
		Map<String, byte[]> original = InjectorExecution.compile(work, STAND_INS);
		byte[] effects = original.get("net/minecraft/world/effect/MobEffectUtil");
		assertSame(effects, InjectorExecution.transform(new BreathingCallbackInjector(), "net.minecraft.world.effect.MobEffectUtil",
				effects, EnvType.SERVER));
	}
}
