/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;

/**
 * {@link NeoConversionPostInjector}'s output, run: a zombie drowning (one of the conversions the merge gave
 * MinecraftForge's lambdas) tells NeoForge mods, once, where as merged only MinecraftForge heard of it.
 *
 * <p>The hook is the kernel's real {@code KernelConversions}, compiled from {@code src/runtime/java} against stand-ins
 * for the entity and the two event factories (the merged lambda still names MinecraftForge's).
 */
@ExecutesInjector(NeoConversionPostInjector.class)
class NeoConversionPostInjectorExecutionTest {
	private static final Path HOOK_SOURCE = Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelConversions.java");
	private static final String ZOMBIE = NeoConversionPostInjector.ZOMBIE;

	private static final Map<String, String> STAND_INS = Map.of(
			"fixture.Told", """
					package fixture;
					public final class Told {
						public static final java.util.List<String> who = new java.util.ArrayList<>();
					}
					""",
			"net.minecraft.world.entity.LivingEntity", """
					package net.minecraft.world.entity;
					public class LivingEntity {
						public final String kind;

						public LivingEntity(String kind) {
							this.kind = kind;
						}
					}
					""",
			"net.minecraftforge.event.ForgeEventFactory", """
					package net.minecraftforge.event;

					import fixture.Told;
					import net.minecraft.world.entity.LivingEntity;

					public class ForgeEventFactory {
						public static void onLivingConvert(LivingEntity from, LivingEntity to) {
							Told.who.add("MinecraftForge " + from.kind + "->" + to.kind);
						}
					}
					""",
			"net.neoforged.neoforge.event.EventHooks", """
					package net.neoforged.neoforge.event;

					import fixture.Told;
					import net.minecraft.world.entity.LivingEntity;

					public class EventHooks {
						public static void onLivingConvert(LivingEntity from, LivingEntity to) {
							Told.who.add("NeoForge " + from.kind + "->" + to.kind);
						}
					}
					""",
			ZOMBIE, """
					package net.minecraft.world.entity.monster.zombie;

					import java.util.function.Consumer;
					import net.minecraft.world.entity.LivingEntity;
					import net.minecraftforge.event.ForgeEventFactory;

					public class Zombie extends LivingEntity {
						public Zombie() {
							super("zombie");
						}

						/** MinecraftForge's lambda: the converted mob is handed on, and MinecraftForge alone is told. */
						public void doUnderWaterConversion(Consumer<LivingEntity> spawn) {
							convertTo(drowned -> {
								ForgeEventFactory.onLivingConvert(this, drowned);
								spawn.accept(drowned);
							});
						}

						private void convertTo(Consumer<LivingEntity> after) {
							after.accept(new LivingEntity("drowned"));
						}
					}
					""");

	private static List<?> drown(ClassLoader loader) throws Throwable {
		Object zombie = InjectorExecution.construct(loader.loadClass(ZOMBIE));
		java.util.function.Consumer<Object> spawn = drowned -> { };
		InjectorExecution.invoke(zombie, "doUnderWaterConversion", spawn);
		return (List<?>) InjectorExecution.getStatic(loader.loadClass("fixture.Told"), "who");
	}

	@Test void aDrowningZombieTellsNeoForgeOnce(@TempDir Path work) throws Throwable {
		assertTrue(Files.isRegularFile(HOOK_SOURCE), "the game-side hook's source is part of the checkout: " + HOOK_SOURCE.toAbsolutePath());
		Map<String, String> sources = new HashMap<>(STAND_INS);
		sources.put("net/forbric/kernel/runtime/KernelConversions.java", Files.readString(HOOK_SOURCE));
		Map<String, byte[]> original = InjectorExecution.compile(work, sources);
		String internal = ZOMBIE.replace('.', '/');
		byte[] posted = InjectorExecution.transform(new NeoConversionPostInjector(), ZOMBIE, original.get(internal), EnvType.SERVER);
		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(internal, posted);
		assertEquals("", InjectorExecution.verify(posted, InjectorExecution.load(classes)));

		assertEquals(List.of("NeoForge zombie->drowned"), drown(InjectorExecution.load(classes)),
				"the moved call tells NeoForge, and nobody tells MinecraftForge: Forbric loads no MinecraftForge mod");

		assertEquals(List.of("MinecraftForge zombie->drowned"), drown(InjectorExecution.load(original)),
				"premise: as merged, NeoForge mods never hear of it");
		assertSame(posted, InjectorExecution.transform(new NeoConversionPostInjector(), ZOMBIE, posted, EnvType.SERVER),
				"nothing left to move");
	}
}
