/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.ExpectCaught;
import net.forbric.kernel.util.Caught;

/**
 * The check after every freeze the kernel drives (lane K1's review, finding A-3): NeoForge's blockstate→id map must hold
 * each registered block state exactly once, numbered 0..n-1, or a peer decodes the wrong blocks. Lane K1's defect broke
 * that silently: a dedicated server whose map had fabric-registry-sync's 2026 window states appended before NeoForge's
 * bake appended the whole registry (34392 states, next id 36418) was visible only on a socket join with a mod's block in
 * view. The game-side red control (the clear taken out, the line missing and a CAUGHT in its place) is gate-m12's.
 */
class BlockStateIdInvariantTest {
	@Test
	void oneIdPerStateIsNoProblem() {
		assertNull(KernelLifecycle.blockStateIdProblem(34392, 34392, 34392));
		assertNull(KernelLifecycle.blockStateIdProblem(0, 0, 0));
	}

	/** Lane K1's dedicated server: every state mapped, but 2026 stale slots below the bake's numbering. */
	@Test
	void aNumberingAppendedAfterAnotherIsAProblemThoughEveryStateIsMapped() {
		String why = KernelLifecycle.blockStateIdProblem(36418, 34392, 34392);
		assertNotNull(why, "the size alone matches the registry; only next id shows the offset");
		assertTrue(why.contains("2026 id(s) are stale"), why);
	}

	/** A clear whose freeze never baked (the registry already frozen, or freezeData throwing first): an empty map. */
	@Test
	void anEmptyMapAfterTheFreezeIsAProblem() {
		String why = KernelLifecycle.blockStateIdProblem(0, 0, 34392);
		assertNotNull(why);
		assertTrue(why.contains("34392 registered state(s) have no id"), why);
	}

	@Test
	void statesOfNoRegisteredBlockAreAProblem() {
		String why = KernelLifecycle.blockStateIdProblem(34400, 34400, 34392);
		assertNotNull(why);
		assertTrue(why.contains("8 mapped state(s) belong to no registered block"), why);
	}

	/** Off-game nothing resolves: the check reports through Caught and never throws out of the window's close. */
	@Test
	@ExpectCaught("is not one id per block state")
	void theCheckNeverThrows() {
		long before = Caught.errorCount();
		ClassLoader empty = new ClassLoader(null) {
		};
		assertDoesNotThrow(() -> KernelLifecycle.verifyBlockStateIds(empty, "test"));
		assertTrue(Caught.errorCount() > before, "an unreadable map is reported as a failure, not passed over");
	}

	/** Both closes check the map, after their freeze (whose bake is what numbers it), whatever that freeze did. */
	@Test
	void bothWindowClosesCheckTheMapAfterTheirFreeze() throws Exception {
		ClassNode node = compiled();
		assertNotNull(node, "KernelLifecycle not found in the compiled src/main classes, which exist before any test runs");
		for (String close : new String[] {"closeRegistrationWindow", "closeClientEntrypointWindow"}) {
			MethodNode m = node.methods.stream().filter(x -> x.name.equals(close)).findFirst().orElseThrow();
			int freeze = -1, verify = -1;
			AbstractInsnNode[] insns = m.instructions.toArray();
			for (int i = 0; i < insns.length; i++) {
				if (!(insns[i] instanceof MethodInsnNode call) || !call.owner.equals(node.name)) continue;
				if (call.name.equals("freeze") && freeze < 0) freeze = i;
				if (call.name.equals("verifyBlockStateIds")) verify = i;
			}
			assertTrue(freeze >= 0, close + " freezes");
			assertTrue(verify > freeze, close + " checks the blockstate->id map after its freeze");
			// Outside the try whose catch a failed freeze lands in, so a freeze that threw is checked too.
			int at = verify;
			boolean insideTry = m.tryCatchBlocks.stream().anyMatch(b -> m.instructions.indexOf(b.start) <= at
					&& at < m.instructions.indexOf(b.end));
			assertTrue(!insideTry, close + " checks the map outside the freeze's try");
		}
	}

	private static ClassNode compiled() throws Exception {
		Path root = Path.of(System.getProperty("user.dir"));
		for (Path dir : new Path[] {root.resolve("build/classes/java/main"), root.resolve("forbric-kernel/build/classes/java/main")}) {
			Path cls = dir.resolve("net/forbric/kernel/boot/KernelLifecycle.class");
			if (Files.isRegularFile(cls)) {
				ClassNode node = new ClassNode();
				new ClassReader(Files.readAllBytes(cls)).accept(node, 0);
				return node;
			}
		}
		return null;
	}
}
