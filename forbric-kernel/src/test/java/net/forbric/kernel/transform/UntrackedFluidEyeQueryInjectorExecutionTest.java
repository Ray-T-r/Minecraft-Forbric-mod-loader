/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.fabricmc.api.EnvType;

/**
 * {@link UntrackedFluidEyeQueryInjector}'s output, run: asking an entity whether its eyes are in a fluid tag nothing
 * tracks and NeoForge has no type for answers {@code false}, as vanilla does, where as merged NeoForge's lookup throws
 * (a Fabric mod's query, every frame it renders). A tag NeoForge knows still goes through NeoForge's own answer.
 *
 * <p>The stand-ins keep the shape the edit keys on: both {@code isEyeInFluid(TagKey)} bodies go through
 * {@code EntityFluidInteraction.getFluidTypeByTag}, which throws for a tag without a fluid type. The kernel's
 * {@code KernelFluidTypes} is game-side, so a stand-in under its name answers {@code hasTagType}.
 */
@ExecutesInjector(UntrackedFluidEyeQueryInjector.class)
@ResourceLock("system-properties")
class UntrackedFluidEyeQueryInjectorExecutionTest {
	private static final String TAG = "net.minecraft.tags.TagKey";
	private static final String ENTITY = UntrackedFluidEyeQueryInjector.ENTITY;
	private static final String INTERACTION = UntrackedFluidEyeQueryInjector.INTERACTION;

	private static final Map<String, String> STAND_INS = Map.of(
			TAG, """
					package net.minecraft.tags;

					public record TagKey(String id) {
					}
					""",
			"net.forbric.kernel.runtime.KernelFluidTypes", """
					package net.forbric.kernel.runtime;

					import net.minecraft.tags.TagKey;

					public final class KernelFluidTypes {
						public static boolean hasTagType(TagKey tag) {
							return tag.id().startsWith("minecraft:");
						}
					}
					""",
			INTERACTION, """
					package net.minecraft.world.entity;

					import java.util.HashMap;
					import java.util.Map;
					import java.util.Set;
					import net.minecraft.tags.TagKey;

					public class EntityFluidInteraction {
						public static class Tracker {
						}

						final Map<TagKey, Tracker> trackerByFluid = new HashMap<>();
						Set<String> eyeIn = Set.of("water");

						public Tracker getTracker(TagKey tag) {
							return trackerByFluid.get(tag);
						}

						/** NeoForge's lookup: a tag with no fluid type is an error. */
						public static String getFluidTypeByTag(TagKey tag) {
							return switch (tag.id()) {
								case "minecraft:water" -> "water";
								case "minecraft:lava" -> "lava";
								default -> throw new IllegalStateException("no fluid type for " + tag.id());
							};
						}

						public boolean isEyeInType(String type) {
							return eyeIn.contains(type);
						}

						public boolean isEyeInFluid(TagKey tag) {
							return isEyeInType(getFluidTypeByTag(tag));
						}
					}
					""",
			ENTITY, """
					package net.minecraft.world.entity;

					import net.minecraft.tags.TagKey;

					public class Entity {
						final EntityFluidInteraction fluidInteraction = new EntityFluidInteraction();

						public boolean isEyeInFluid(TagKey tag) {
							return fluidInteraction.isEyeInType(EntityFluidInteraction.getFluidTypeByTag(tag));
						}
					}
					""");

	@AfterEach void reset() {
		System.clearProperty(UntrackedFluidEyeQueryInjector.PROPERTY);
	}

	private static Object tag(ClassLoader loader, String id) throws Throwable {
		return InjectorExecution.construct(loader.loadClass(TAG), id);
	}

	@Test void anUntrackedTagWithoutAFluidTypeAnswersFalseOnBothClasses(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = InjectorExecution.compile(work, STAND_INS);
		Map<String, byte[]> classes = new HashMap<>(original);
		for (String target : List.of(ENTITY, INTERACTION)) {
			String internal = target.replace('.', '/');
			classes.put(internal, InjectorExecution.transform(new UntrackedFluidEyeQueryInjector(), target, original.get(internal),
					EnvType.CLIENT));
		}
		ClassLoader loader = InjectorExecution.load(classes);
		for (String target : List.of(ENTITY, INTERACTION)) {
			assertEquals("", InjectorExecution.verify(classes.get(target.replace('.', '/')), loader), target);
		}

		Object entity = InjectorExecution.construct(loader.loadClass(ENTITY));
		Object interaction = InjectorExecution.construct(loader.loadClass(INTERACTION));
		for (Object asked : List.of(entity, interaction)) {
			assertEquals(false, InjectorExecution.invoke(asked, "isEyeInFluid", tag(loader, "c:oil")),
					asked.getClass().getSimpleName() + ": an untracked tag with no fluid type answers false, as on vanilla");
			assertEquals(true, InjectorExecution.invoke(asked, "isEyeInFluid", tag(loader, "minecraft:water")),
					"a tag NeoForge has a type for keeps NeoForge's answer");
			assertEquals(false, InjectorExecution.invoke(asked, "isEyeInFluid", tag(loader, "minecraft:lava")));
		}

		ClassLoader merged = InjectorExecution.load(original);
		Object stock = InjectorExecution.construct(merged.loadClass(ENTITY));
		Object oil = tag(merged, "c:oil");
		assertThrows(IllegalStateException.class, () -> InjectorExecution.invoke(stock, "isEyeInFluid", oil),
				"premise: as merged, the query throws");
		for (String target : List.of(ENTITY, INTERACTION)) {
			byte[] once = classes.get(target.replace('.', '/'));
			assertSame(once, InjectorExecution.transform(new UntrackedFluidEyeQueryInjector(), target, once, EnvType.CLIENT),
					"a guarded query is left alone");
		}
	}

	/**
	 * NeoForge's own interaction, the genuine base's: trackers per fluid type and no {@code getTracker(TagKey)}. The guards
	 * still answer false for an untracked tag and leave NeoForge's answer for one it has a type for — and the entity's guard
	 * links, which it did not while it called {@code getTracker} itself.
	 */
	@Test void onNeoForgesOwnInteractionTheEntityGuardLinksAndAnswersFalse(@TempDir Path work) throws Throwable {
		Map<String, String> sources = new HashMap<>(STAND_INS);
		sources.put(INTERACTION, """
				package net.minecraft.world.entity;

				import java.util.HashMap;
				import java.util.Map;
				import java.util.Set;
				import net.minecraft.tags.TagKey;

				public class EntityFluidInteraction {
					public static class Tracker {
					}

					/** NeoForge's: keyed by fluid type, never by tag. */
					private final Map<String, Tracker> trackerByFluid = new HashMap<>();
					Set<String> eyeIn = Set.of("water");

					public static String getFluidTypeByTag(TagKey tag) {
						return switch (tag.id()) {
							case "minecraft:water" -> "water";
							case "minecraft:lava" -> "lava";
							default -> throw new IllegalStateException("no fluid type for " + tag.id());
						};
					}

					public boolean isEyeInType(String type) {
						return eyeIn.contains(type);
					}

					public boolean isEyeInFluid(TagKey tag) {
						return isEyeInType(getFluidTypeByTag(tag));
					}
				}
				""");
		Map<String, byte[]> original = InjectorExecution.compile(work, sources);
		Map<String, byte[]> classes = new HashMap<>(original);
		for (String target : List.of(ENTITY, INTERACTION)) {
			String internal = target.replace('.', '/');
			classes.put(internal, InjectorExecution.transform(new UntrackedFluidEyeQueryInjector(), target, original.get(internal),
					EnvType.CLIENT));
		}
		ClassLoader loader = InjectorExecution.load(classes);
		for (String target : List.of(ENTITY, INTERACTION)) {
			assertEquals("", InjectorExecution.verify(classes.get(target.replace('.', '/')), loader), target);
		}
		Object entity = InjectorExecution.construct(loader.loadClass(ENTITY));
		assertEquals(false, InjectorExecution.invoke(entity, "isEyeInFluid", tag(loader, "c:oil")),
				"an untracked tag answers false, as on vanilla — no NoSuchMethodError from a getTracker NeoForge lacks");
		assertEquals(true, InjectorExecution.invoke(entity, "isEyeInFluid", tag(loader, "minecraft:water")));
		Object interaction = InjectorExecution.construct(loader.loadClass(INTERACTION));
		assertEquals(false, InjectorExecution.invoke(interaction, "isEyeInFluid", tag(loader, "c:oil")));
		assertEquals(false, InjectorExecution.invoke(interaction, UntrackedFluidEyeQueryInjector.TRACKS_TAG, tag(loader, "minecraft:water")),
				"NeoForge's interaction tracks no tag");
	}

	@Test void switchedOffBothClassesAreLeftNative(@TempDir Path work) throws Exception {
		Map<String, byte[]> original = InjectorExecution.compile(work, STAND_INS);
		System.setProperty(UntrackedFluidEyeQueryInjector.PROPERTY, "off");
		for (String target : List.of(ENTITY, INTERACTION)) {
			byte[] bytes = original.get(target.replace('.', '/'));
			assertSame(bytes, InjectorExecution.transform(new UntrackedFluidEyeQueryInjector(), target, bytes, EnvType.CLIENT));
		}
	}
}
