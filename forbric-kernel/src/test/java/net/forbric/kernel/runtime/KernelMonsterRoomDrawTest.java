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

package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The monster-room draw's shape over the COMPILED runtime class.
 *
 * <p>What is being protected is a parity decision, not a crash. Vanilla draws its dungeon mob with
 * {@code nextInt(4)} over {@code {SKELETON, ZOMBIE, ZOMBIE, SPIDER}}; NeoForge's shipped data map is the same
 * distribution by weight (100/100/200) but is drawn with {@code nextInt(400)}, and the two do not agree on which
 * mob a given seed produces — measured with zero mods on one seed, all six dungeons in the compared area
 * disagreed with vanilla. So while nothing has changed the map, the vanilla draw is the one that reproduces
 * vanilla's world, and the data map takes over the moment a mod touches it.
 *
 * <p>The assertions are on shape because the alternative needs a loaded game: the weighted list is
 * {@code MonsterRoomHooks}' private static, filled from a server reload. gate-m31 carries the behavioural half —
 * it asserts every dungeon in the compared area spawns vanilla's mob, and that check reported eight differing
 * spawners before this existed.
 */
class KernelMonsterRoomDrawTest {
	private static final Path COMPILED = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "runtime",
			"net", "forbric", "kernel", "runtime", "KernelNeoWorldgen.class").normalize();
	private static final String HOOKS = "net/neoforged/neoforge/common/MonsterRoomHooks";
	private static final String GUARD = "monsterRoomMobsAreStillNeoForgesDefault";

	@Test
	void theDataMapDrawIsReachedOnlyThroughTheGuard() throws Exception {
		MethodNode draw = method("randomMonsterRoomMob");
		int guardAt = -1, dataMapAt = -1, index = 0;
		boolean branchedBetween = false;
		for (AbstractInsnNode insn = draw.instructions.getFirst(); insn != null; insn = insn.getNext(), index++) {
			if (insn instanceof MethodInsnNode call) {
				if (GUARD.equals(call.name)) guardAt = index;
				if (HOOKS.equals(call.owner) && "getRandomMonsterRoomMob".equals(call.name)) dataMapAt = index;
			}
			if (insn instanceof JumpInsnNode && guardAt >= 0 && dataMapAt < 0) branchedBetween = true;
		}
		assertTrue(guardAt >= 0, "the draw must ask whether the data map is still NeoForge's own");
		assertTrue(dataMapAt >= 0, "the data map must still be drawn from when a mod has changed it");
		assertTrue(guardAt < dataMapAt, "the guard must be asked BEFORE the data map is drawn from");
		assertTrue(branchedBetween, "the data-map draw must sit behind a branch on the guard, not run regardless");
	}

	@Test
	void bothPathsSpendExactlyOneDraw() throws Exception {
		MethodNode draw = method("randomMonsterRoomMob");
		List<String> draws = new ArrayList<>();
		for (AbstractInsnNode insn = draw.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof MethodInsnNode call && "nextInt".equals(call.name)) draws.add(call.owner);
			if (insn instanceof MethodInsnNode call && HOOKS.equals(call.owner)
					&& "getRandomMonsterRoomMob".equals(call.name)) {
				draws.add("(weighted list draws one)");
			}
		}
		// One on each path and no more: a path that spends two would shift every later feature in the chunk.
		assertEquals(2, draws.size(), "expected exactly one draw per path, saw " + draws);
	}

	@Test
	void theShippedWeightsAreTheOnesNeoForgeActuallyShips() throws Exception {
		MethodNode init = method("<clinit>");
		List<Integer> constants = new ArrayList<>();
		for (AbstractInsnNode insn = init.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof IntInsnNode push && push.getOpcode() == Opcodes.BIPUSH) constants.add(push.operand);
			if (insn instanceof IntInsnNode push && push.getOpcode() == Opcodes.SIPUSH) constants.add(push.operand);
			if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Integer value) constants.add(value);
		}
		assertTrue(constants.contains(100) && constants.contains(200),
				"the shipped weights are skeleton 100 / spider 100 / zombie 200; the class initialiser pushes "
						+ constants);
		assertEquals(2, constants.stream().filter(v -> v == 100).count(),
				"two entries weigh 100 (skeleton and spider); saw " + constants);
	}

	@Test
	void theGuardReadsTheHooksOwnFieldAndItsEntries() throws Exception {
		MethodNode guard = method(GUARD);
		List<String> named = new ArrayList<>();
		List<String> called = new ArrayList<>();
		for (AbstractInsnNode insn = guard.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String text) named.add(text);
			if (insn instanceof MethodInsnNode call) called.add(call.name);
		}
		assertTrue(named.contains("monsterRoomMobs"), "the guard must read the hooks' own list; strings seen: " + named);
		assertTrue(named.contains("unwrap") && named.contains("value") && named.contains("weight"),
				"the guard must compare entry BY ENTRY, not just count them; strings seen: " + named);
		assertTrue(called.contains("getDeclaredField") && called.contains("setAccessible"),
				"there is no accessor for that list, so the guard goes through reflection; calls seen: " + called);
	}

	private static MethodNode method(String name) throws Exception {
		assumeTrue(Files.isRegularFile(COMPILED), "runtime helper not compiled: " + COMPILED);
		ClassNode node = new ClassNode();
		new ClassReader(Files.readAllBytes(COMPILED)).accept(node, 0);
		for (MethodNode candidate : node.methods) {
			if (candidate.name.equals(name)) return candidate;
		}
		throw new AssertionError("no method " + name + " on " + node.name);
	}
}
