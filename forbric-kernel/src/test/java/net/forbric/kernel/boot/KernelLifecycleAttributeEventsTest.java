/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.forbric.kernel.ExpectCaught;
import net.forbric.kernel.transform.InjectorExecution;

/**
 * The kernel's real {@code KernelLifecycle.postAttributeEvents} against stand-ins, when {@code DefaultAttributes}' class
 * initialiser registers an entity type — as a Fabric mod's wrap of its supplier-map build does (Create Fly with fabric-api),
 * when its own entities' attribute classes register their types as they initialise.
 *
 * <p>The stand-in {@code CommonHooks.modifyAttributes} builds its entity-type list as NeoForge's
 * {@code EntityAttributeModificationEvent} constructor does: the entity-type registry streamed through
 * {@code DefaultAttributes::hasSupplier}. A first touch of {@code DefaultAttributes} inside that stream changes the registry
 * under it: {@code ConcurrentModificationException}, the event never finishes (the Caught WARN "modifyAttributes() THREW",
 * which fails this test through CaughtErrorsExtension), and no entity gets NeoForge's attributes. The kernel initialises the
 * class first, as vanilla's {@code Bootstrap.validate} does before any mod event and NeoForge's no longer does.
 */
class KernelLifecycleAttributeEventsTest {
	private static final String HOOKS = "net.neoforged.neoforge.common.CommonHooks";
	private static final String ATTRIBUTES = "net.minecraft.world.entity.ai.attributes.DefaultAttributes";

	private static final Map<String, String> STAND_INS = Map.of(
			"net.minecraft.world.entity.EntityType", """
					package net.minecraft.world.entity;

					public class EntityType<T> {
						public static final java.util.List<EntityType<?>> REGISTRY = new java.util.ArrayList<>();
						public static final EntityType<Object> FIRST = new EntityType<>("first");
						public static final EntityType<Object> SECOND = new EntityType<>("second");
						public final String id;

						public EntityType(String id) {
							this.id = id;
							REGISTRY.add(this);
						}
					}
					""",
			ATTRIBUTES, """
					package net.minecraft.world.entity.ai.attributes;

					import net.minecraft.world.entity.EntityType;

					public class DefaultAttributes {
						/** A guest's wrap of the supplier-map build: its entities' attributes, whose type registers on first use. */
						static final EntityType<Object> LATE = new EntityType<>("late");

						public static boolean hasSupplier(EntityType<?> type) {
							return true;
						}

						public static void validate() {
						}
					}
					""",
			HOOKS, """
					package net.neoforged.neoforge.common;

					import java.util.List;
					import net.minecraft.world.entity.EntityType;
					import net.minecraft.world.entity.ai.attributes.DefaultAttributes;

					public class CommonHooks {
						public static List<String> seen = List.of();

						/** As EntityAttributeModificationEvent's constructor builds its list. */
						public static void modifyAttributes() {
							seen = EntityType.REGISTRY.stream().filter(DefaultAttributes::hasSupplier).map(type -> type.id).toList();
						}
					}
					""");

	private static ClassLoader load(Path work) throws Exception {
		return InjectorExecution.load(InjectorExecution.compile(work, STAND_INS));
	}

	@Test void theEventsSeeEveryEntityTypeWhenTheInitialiserRegistersOne(@TempDir Path work) throws Throwable {
		ClassLoader loader = load(work);
		KernelLifecycle.postAttributeEvents(loader);
		assertEquals(List.of("first", "second", "late"), InjectorExecution.getStatic(loader.loadClass(HOOKS), "seen"),
				"the event finished, and the type the initialiser registered is in its list");
	}

	/** The premise: posted as NeoForge posts it, the first touch is inside the walk, and the walk does not survive it. */
	@Test void premiseTheFirstTouchInsideTheWalkThrows(@TempDir Path work) throws Throwable {
		ClassLoader loader = load(work);
		assertThrows(ConcurrentModificationException.class,
				() -> InjectorExecution.invokeStatic(loader.loadClass(HOOKS), "modifyAttributes"));
	}

	@Test void anInitialisedClassIsLeftAsItIs(@TempDir Path work) throws Throwable {
		ClassLoader loader = load(work);
		InjectorExecution.invokeStatic(loader.loadClass(ATTRIBUTES), "validate");
		KernelLifecycle.postAttributeEvents(loader);
		KernelLifecycle.postAttributeEvents(loader);
		assertEquals(List.of("first", "second", "late"), InjectorExecution.getStatic(loader.loadClass(HOOKS), "seen"));
	}

	/** A DefaultAttributes that cannot initialise is reported under the attribute tag lib.sh's safety net reads. */
	@ExpectCaught({"DefaultAttributes could not initialise", "modifyAttributes() THREW"})
	@Test void anInitialiserThatFailsIsReportedAndTheEventsStillRun(@TempDir Path work) throws Throwable {
		Map<String, String> broken = new java.util.HashMap<>(STAND_INS);
		broken.put(ATTRIBUTES, STAND_INS.get(ATTRIBUTES).replace("new EntityType<>(\"late\");",
				"boom();\n\t\t\t\tstatic EntityType<Object> boom() { throw new IllegalStateException(\"guest initialiser broke\"); }"));
		ClassLoader loader = InjectorExecution.load(InjectorExecution.compile(work, broken));
		KernelLifecycle.postAttributeEvents(loader);
		assertEquals(List.of(), InjectorExecution.getStatic(loader.loadClass(HOOKS), "seen"),
				"the walk asked the class that failed to initialise, so the event did not finish either");
	}
}
