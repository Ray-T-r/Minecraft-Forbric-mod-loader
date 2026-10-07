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
import net.forbric.kernel.interop.BlockSoundCallbackScope;

/**
 * {@link BlockSoundQueryInjector}'s output, run: NeoForge's native step and fall sounds ask Create's sound-group
 * override, carried in {@link BlockSoundCallbackScope}, for the block's sound type; with no scope the block's own sound type
 * is used.
 *
 * <p>The hook is the kernel's real {@code KernelBlockSoundQuery}, compiled from {@code src/runtime/java} against
 * stand-ins for the five game types it names; the stand-in {@code IBlockExtension} keeps the two
 * {@code BlockState.getSoundType(level, pos, entity)} calls the edit keys on.
 */
@ExecutesInjector(BlockSoundQueryInjector.class)
class BlockSoundQueryInjectorExecutionTest {
	private static final Path HOOK_SOURCE = Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelBlockSoundQuery.java");
	private static final String EXTENSION = BlockSoundQueryInjector.TARGET;
	private static final String STATE = "net.minecraft.world.level.block.state.BlockState";

	private static final Map<String, String> STAND_INS = Map.of(
			"net.minecraft.world.level.block.SoundType", "package net.minecraft.world.level.block; public record SoundType(String name) { }",
			"net.minecraft.world.level.LevelReader", "package net.minecraft.world.level; public interface LevelReader { }",
			"net.minecraft.core.BlockPos", "package net.minecraft.core; public record BlockPos(int x, int y, int z) { }",
			"net.minecraft.world.entity.Entity", "package net.minecraft.world.entity; public class Entity { }",
			STATE, """
					package net.minecraft.world.level.block.state;

					import net.minecraft.core.BlockPos;
					import net.minecraft.world.entity.Entity;
					import net.minecraft.world.level.LevelReader;
					import net.minecraft.world.level.block.SoundType;

					public class BlockState {
						public SoundType getSoundType(LevelReader level, BlockPos pos, Entity entity) {
							return new SoundType("metal");
						}
					}
					""",
			EXTENSION, """
					package net.neoforged.neoforge.common.extensions;

					import net.minecraft.core.BlockPos;
					import net.minecraft.world.entity.Entity;
					import net.minecraft.world.level.LevelReader;
					import net.minecraft.world.level.block.SoundType;
					import net.minecraft.world.level.block.state.BlockState;

					public interface IBlockExtension {
						default String playStepSound(BlockState state, LevelReader level, BlockPos pos, Entity entity) {
							SoundType sound = state.getSoundType(level, pos, entity);
							return "step " + sound.name();
						}

						default String playFallSound(BlockState state, LevelReader level, BlockPos pos, Entity entity) {
							SoundType sound = state.getSoundType(level, pos, entity);
							return "fall " + sound.name();
						}
					}
					""",
			"fixture.Block", "package fixture; public class Block implements net.neoforged.neoforge.common.extensions.IBlockExtension { }");

	private static List<String> sounds(ClassLoader loader) throws Throwable {
		Object block = InjectorExecution.construct(loader.loadClass("fixture.Block"));
		Object state = InjectorExecution.construct(loader.loadClass(STATE));
		Class<?> levelType = loader.loadClass("net.minecraft.world.level.LevelReader");
		Object level = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[] {levelType}, (proxy, method, args) -> "level");
		Object pos = InjectorExecution.construct(loader.loadClass("net.minecraft.core.BlockPos"), 1, 64, 2);
		Object entity = InjectorExecution.construct(loader.loadClass("net.minecraft.world.entity.Entity"));
		// Default methods: called through the interface, which is where they are declared.
		Class<?>[] parameters = {state.getClass(), levelType, pos.getClass(), entity.getClass()};
		Class<?> extension = loader.loadClass(EXTENSION);
		return List.of(String.valueOf(extension.getMethod("playStepSound", parameters).invoke(block, state, level, pos, entity)),
				String.valueOf(extension.getMethod("playFallSound", parameters).invoke(block, state, level, pos, entity)));
	}

	@Test void createsSoundGroupOverrideIsAskedOnBothNativeSounds(@TempDir Path work) throws Throwable {
		assertTrue(Files.isRegularFile(HOOK_SOURCE), "the game-side hook's source is part of the checkout: " + HOOK_SOURCE.toAbsolutePath());
		Map<String, String> sources = new HashMap<>(STAND_INS);
		sources.put("net/forbric/kernel/runtime/KernelBlockSoundQuery.java", Files.readString(HOOK_SOURCE));
		Map<String, byte[]> original = InjectorExecution.compile(work, sources);
		String internal = EXTENSION.replace('.', '/');
		byte[] routed = InjectorExecution.transform(new BlockSoundQueryInjector(), EXTENSION, original.get(internal), EnvType.CLIENT);
		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(internal, routed);
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(routed, loader));

		assertEquals(List.of("step metal", "fall metal"), sounds(loader), "no Create scope: the block's own sound type");
		Class<?> soundType = loader.loadClass("net.minecraft.world.level.block.SoundType");
		Object previous = BlockSoundCallbackScope.enter(args -> {
			try {
				return InjectorExecution.construct(soundType, "copper casing from " + args[1]);
			} catch (Throwable failed) {
				throw new AssertionError(failed);
			}
		});
		try {
			assertEquals(List.of("step copper casing from BlockPos[x=1, y=64, z=2]", "fall copper casing from BlockPos[x=1, y=64, z=2]"),
					sounds(loader), "Create's override answers for the position it was asked about");
		} finally {
			BlockSoundCallbackScope.leave(previous);
		}

		Object stillPrevious = BlockSoundCallbackScope.enter(args -> {
			throw new AssertionError("asked");
		});
		try {
			assertEquals(List.of("step metal", "fall metal"), sounds(InjectorExecution.load(original)),
					"premise: as shipped, Create's step and landing sound groups are lost");
		} finally {
			BlockSoundCallbackScope.leave(stillPrevious);
		}
	}
}
