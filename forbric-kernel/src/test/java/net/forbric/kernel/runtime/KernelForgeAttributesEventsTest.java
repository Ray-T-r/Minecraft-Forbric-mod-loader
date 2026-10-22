/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.forbric.kernel.transform.InjectorExecution;

/**
 * The kernel's real {@code KernelForgeAttributes.fireForgeAttributeEvents}, compiled from {@code src/runtime/java} against
 * stand-ins, when {@code DefaultAttributes}' class initialiser registers an entity type — as a mod's wrap of its supplier-map
 * build does, when its own entities' attribute classes register their types as they initialise.
 *
 * <p>The stand-in {@code ForgeHooks.modifyAttributes} builds its entity-type list as both ecosystems'
 * {@code EntityAttributeModificationEvent} constructors do: the entity-type registry streamed through
 * {@code DefaultAttributes::hasSupplier}. A first touch of {@code DefaultAttributes} inside that stream changes the registry
 * under it: {@code ConcurrentModificationException}, the event never finishes, and every entity is left without the
 * ecosystem's attributes (the Caught WARN "did not finish", which fails this test through CaughtErrorsExtension). The kernel
 * initialises the class first, as vanilla's {@code Bootstrap.validate} did before any mod event, and the walk sees every
 * type — the one registered during the initialisation included.
 */
class KernelForgeAttributesEventsTest {
	private static final Path HOOK_SOURCE = Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelForgeAttributes.java");
	private static final String RUNTIME = "net.forbric.kernel.runtime.KernelForgeAttributes";
	private static final String HOOKS = "net.minecraftforge.common.ForgeHooks";

	private static final Map<String, String> STAND_INS = Map.of(
			"net.minecraft.world.entity.LivingEntity", "package net.minecraft.world.entity; public class LivingEntity { }",
			"net.minecraft.world.entity.EntityType", """
					package net.minecraft.world.entity;

					public class EntityType<T> {
						public static final java.util.List<EntityType<?>> REGISTRY = new java.util.ArrayList<>();
						public static final EntityType<LivingEntity> FIRST = new EntityType<>("first");
						public static final EntityType<LivingEntity> SECOND = new EntityType<>("second");
						public final String id;

						public EntityType(String id) {
							this.id = id;
							REGISTRY.add(this);
						}
					}
					""",
			"net.minecraft.world.entity.ai.attributes.AttributeSupplier",
			"package net.minecraft.world.entity.ai.attributes; public class AttributeSupplier { }",
			"net.minecraft.world.entity.ai.attributes.DefaultAttributes", """
					package net.minecraft.world.entity.ai.attributes;

					import net.minecraft.world.entity.EntityType;
					import net.minecraft.world.entity.LivingEntity;

					public class DefaultAttributes {
						/** A guest's wrap of the supplier-map build: its entities' attributes, whose type registers on first use. */
						static final EntityType<LivingEntity> LATE = new EntityType<>("late");

						public static boolean hasSupplier(EntityType<?> type) {
							return true;
						}

						public static void validate() {
						}
					}
					""",
			"net.minecraftforge.common.ForgeHooks", """
					package net.minecraftforge.common;

					import java.util.List;
					import java.util.Map;
					import net.minecraft.world.entity.EntityType;
					import net.minecraft.world.entity.LivingEntity;
					import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
					import net.minecraft.world.entity.ai.attributes.DefaultAttributes;

					public class ForgeHooks {
						public static List<String> seen = List.of();

						/** As EntityAttributeModificationEvent's constructor builds its list. */
						public static void modifyAttributes() {
							seen = EntityType.REGISTRY.stream().filter(DefaultAttributes::hasSupplier).map(type -> type.id).toList();
						}

						public static Map<EntityType<? extends LivingEntity>, AttributeSupplier> getAttributesView() {
							return Map.of();
						}
					}
					""",
			"net.neoforged.neoforge.common.CommonHooks", """
					package net.neoforged.neoforge.common;

					import java.util.Map;
					import net.minecraft.world.entity.EntityType;
					import net.minecraft.world.entity.LivingEntity;
					import net.minecraft.world.entity.ai.attributes.AttributeSupplier;

					public class CommonHooks {
						public static Map<EntityType<? extends LivingEntity>, AttributeSupplier> getAttributesView() {
							return Map.of();
						}
					}
					""");

	private static ClassLoader load(Path work) throws Exception {
		assertTrue(Files.isRegularFile(HOOK_SOURCE), "the game-side hook's source is part of the checkout: " + HOOK_SOURCE.toAbsolutePath());
		Map<String, String> sources = new HashMap<>(STAND_INS);
		sources.put("net/forbric/kernel/runtime/KernelForgeAttributes.java", Files.readString(HOOK_SOURCE));
		return InjectorExecution.load(InjectorExecution.compile(work, sources));
	}

	@Test void theEventsSeeEveryEntityTypeWhenTheInitialiserRegistersOne(@TempDir Path work) throws Throwable {
		ClassLoader loader = load(work);
		InjectorExecution.invokeStatic(loader.loadClass(RUNTIME), "fireForgeAttributeEvents");
		assertEquals(List.of("first", "second", "late"), InjectorExecution.getStatic(loader.loadClass(HOOKS), "seen"),
				"the event finished, and the type the initialiser registered is in its list");
	}

	/** The premise: posted as merged, the first touch is inside the walk, and the walk does not survive it. */
	@Test void premiseTheFirstTouchInsideTheWalkThrows(@TempDir Path work) throws Throwable {
		ClassLoader loader = load(work);
		assertThrows(ConcurrentModificationException.class,
				() -> InjectorExecution.invokeStatic(loader.loadClass(HOOKS), "modifyAttributes"));
	}

	@Test void anInitialisedClassIsLeftAsItIs(@TempDir Path work) throws Throwable {
		ClassLoader loader = load(work);
		InjectorExecution.invokeStatic(loader.loadClass("net.minecraft.world.entity.ai.attributes.DefaultAttributes"), "validate");
		InjectorExecution.invokeStatic(loader.loadClass(RUNTIME), "fireForgeAttributeEvents");
		InjectorExecution.invokeStatic(loader.loadClass(RUNTIME), "fireForgeAttributeEvents");
		assertEquals(List.of("first", "second", "late"), InjectorExecution.getStatic(loader.loadClass(HOOKS), "seen"));
	}
}
