/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * The two NeoForge worldgen call sites the kernel redirects instead of neutering.
 *
 * <p>Both used to be {@code MethodBodyNeuter} targets, and a neuter is a promise that the method cannot work
 * here. Both promises had expired, and one of them was expensive in a way nothing reported: neutering
 * {@code MonsterRoomFeature.place} means no dungeon — so no spawner and no dungeon chest — in EVERY world every
 * player generates, mods or no mods.
 *
 * <p>These assertions are about the CALL SITES, because that is what decides whether the guard is on the path at
 * all. Whether the guard then does the right thing is {@link net.forbric.kernel.runtime.KernelNeoWorldgen}'s
 * own business, and is visible in the log lines it prints with counts in them.
 */
class MergedBaseWorldgenDriversTest {
	private static final Path STAGED = TestFixtures.stagedRoot();
	private static final Path MERGED_BASE = STAGED.resolve(TestFixtures.BASE_JAR);
	private static final Path NEO_CARRIER = STAGED.resolve("neoforge-runtime/neoforge-runtime.jar");
	private static final String KERNEL = "net/forbric/kernel/runtime/KernelNeoWorldgen";

	@Test
	void theDungeonMobPickGoesThroughTheKernelAndNotStraightAtTheDataMap() throws Exception {
		ClassNode node = repaired(MERGED_BASE,
				"net/minecraft/world/level/levelgen/feature/MonsterRoomFeature.class");

		List<String> toKernel = new ArrayList<>();
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (!(insn instanceof MethodInsnNode call)) continue;
				assertTrue(!"net/neoforged/neoforge/common/MonsterRoomHooks".equals(call.owner),
						"MonsterRoomFeature still calls MonsterRoomHooks directly in " + method.name + method.desc
								+ " — that reads a WeightedList only a DataMapsUpdatedEvent listener fills, and it "
								+ "was null, which is why the whole feature used to be neutered");
				if (KERNEL.equals(call.owner) && "randomMonsterRoomMob".equals(call.name)) {
					assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
					assertEquals("(Lnet/minecraft/util/RandomSource;)Lnet/minecraft/world/entity/EntityType;",
							call.desc, "the redirect has to keep the descriptor it replaced, or the stack moves");
					toKernel.add(method.name + method.desc);
				}
			}
		}
		assertEquals(1, toKernel.size(), "exactly one mob pick, redirected once: " + toKernel);
	}

	/**
	 * {@code place} is what the neuter used to empty. Asserting it still HAS a body is the difference between
	 * "dungeons can generate" and "the redirect is installed in a method nobody reaches".
	 */
	@Test
	void thePlaceMethodStillHasItsBody() throws Exception {
		ClassNode node = repaired(MERGED_BASE,
				"net/minecraft/world/level/levelgen/feature/MonsterRoomFeature.class");
		MethodNode place = null;
		for (MethodNode method : node.methods) {
			if ("place".equals(method.name)) place = method;
		}
		assertTrue(place != null, "MonsterRoomFeature.place is gone");
		assertTrue(place.instructions.size() > 50,
				"MonsterRoomFeature.place has " + place.instructions.size() + " instruction(s) — that is a neutered "
						+ "body, and a neutered body means no dungeon in any world");
	}

	@Test
	void neoForgesModifierPassIsGuardedAtItsCallSite() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(NEO_CARRIER), "staged NeoForge carrier absent");
		ClassNode node = repaired(NEO_CARRIER, "net/neoforged/neoforge/server/ServerLifecycleHooks.class");

		boolean guarded = false;
		for (MethodNode method : node.methods) {
			if (!"handleServerAboutToStart".equals(method.name)) continue;
			for (AbstractInsnNode insn : method.instructions) {
				if (!(insn instanceof MethodInsnNode call)) continue;
				assertTrue(!"runModifiers".equals(call.name),
						"handleServerAboutToStart still calls runModifiers directly. Its first instruction is a "
								+ "lookupOrThrow for neoforge:biome_modifier, and both dedicated and integrated "
								+ "servers call this method — so a failure there costs the boot, not the modifiers");
				if (KERNEL.equals(call.owner) && "beforeServerStart".equals(call.name)) {
					assertEquals("(Lnet/minecraft/server/MinecraftServer;)V", call.desc);
					guarded = true;
				}
			}
		}
		assertTrue(guarded, "handleServerAboutToStart must reach the kernel's guard");
	}

	@Test
	void aSecondPassChangesNothingFurther() throws Exception {
		byte[] source = bytes(MERGED_BASE, "net/minecraft/world/level/levelgen/feature/MonsterRoomFeature.class");
		String binary = "net.minecraft.world.level.levelgen.feature.MonsterRoomFeature";
		byte[] once = new ForbricMergedBaseCompatTransformer().transform(binary, source, null);
		byte[] twice = new ForbricMergedBaseCompatTransformer().transform(binary, once, null);
		assertSame(once, twice, "the redirect must stand down once no MonsterRoomHooks call remains");
	}

	private static ClassNode repaired(Path jar, String entry) throws IOException {
		byte[] source = bytes(jar, entry);
		String binary = entry.substring(0, entry.length() - ".class".length()).replace('/', '.');
		ClassNode node = new ClassNode();
		new ClassReader(new ForbricMergedBaseCompatTransformer().transform(binary, source, null)).accept(node, 0);
		return node;
	}

	private static byte[] bytes(Path jar, String entry) throws IOException {
		return TestFixtures.requireEntry(Fixture.STAGED, jar, entry);
	}
}
