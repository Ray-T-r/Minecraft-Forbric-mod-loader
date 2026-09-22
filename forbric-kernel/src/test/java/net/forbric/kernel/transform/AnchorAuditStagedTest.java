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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import net.fabricmc.api.EnvType;

/**
 * The anchor books against the real merged base, and against a copy of it with the anchor taken away.
 *
 * <p>The second half is the point. Asserting that a repair applies today proves only that today is fine; the
 * failure this whole mechanism exists for is the one that arrives with a carrier upgrade, months from now, and
 * is silent. So the upgrade is simulated here: every {@code NEW net/minecraft/client/Options} in
 * {@code Minecraft.<init>} is rewritten to {@code NEW java/lang/Object}, which is exactly the shape of "Mojang
 * moved Options out of the constructor", and the audit has to notice.
 *
 * <p>No fixture file is needed for that, and deliberately so. A checked-in copy of a mutated class would rot
 * against the very carrier bumps it is meant to model.
 */
class AnchorAuditStagedTest {
	private static final Path STAGED =
			Path.of(System.getenv().getOrDefault("FORBRIC_OLD", System.getProperty("user.dir") + "/../forbric-loader"), "run").normalize();
	private static final Path MERGED_BASE = STAGED.resolve("merged-base/patched-mc-merged-26.2.jar");
	private static final String MINECRAFT = "net.minecraft.client.Minecraft";
	private static final String ENTRY = "net/minecraft/client/Minecraft.class";
	private static final String OPTIONS = "net/minecraft/client/Options";
	private static final TransformContext CTX = new TransformContext(EnvType.CLIENT, false, "intermediary");

	@Test
	void theClientEntrypointAnchorIsStillThereAndTheLedgerSaysSo() throws Exception {
		byte[] real = read(ENTRY);
		assumeTrue(real != null, "staged merged base absent at " + MERGED_BASE);

		TransformChain chain = chainWithTheInjector();
		byte[] out = chain.applyBeforeMixin(MINECRAFT, real, CTX);

		assertNotSame(real, out, "the injector must still find its anchor in the base as staged");
		AnchorLedger.Report r = chain.ledger().report();
		assertTrue(r.clean(), () -> "unexpected miss: " + r.misses());
		assertEquals(1, r.hit());
	}

	@Test
	void whenTheAnchorMovesTheInjectorGoesSilentAndTheLedgerCatchesIt() throws Exception {
		byte[] real = read(ENTRY);
		assumeTrue(real != null, "staged merged base absent at " + MERGED_BASE);

		byte[] moved = withOptionsConstructionRemoved(real);
		assertNotSame(real, moved, "the mutation must actually change the class, or it proves nothing");

		TransformChain chain = chainWithTheInjector();
		byte[] out = chain.applyBeforeMixin(MINECRAFT, moved, CTX);

		// This is the silence the mechanism exists for: same array back, no throw, nothing in the log from the
		// transformer itself. Before the ledger, that was the whole of what happened.
		assertSame(moved, out, "the injector returns its input untouched when the anchor is gone -- silently");

		AnchorLedger.Report r = chain.ledger().report();
		assertFalse(r.clean(), "and THAT is what the books have to turn into a finding");
		assertEquals(1, r.misses().size());
		assertEquals(MINECRAFT, r.misses().get(0).className());
		assertEquals(AnchorSet.Severity.REQUIRED, r.misses().get(0).severity());
		assertTrue(r.misses().get(0).cost().contains("client entrypoints"),
				"the finding has to carry what it costs, or it is just a name");
	}

	private static TransformChain chainWithTheInjector() {
		TransformChain chain = new TransformChain();
		chain.register(TransformPhase.COREMOD, new ClientEntrypointHookInjector());
		return chain;
	}

	/** Rewrites every {@code NEW net/minecraft/client/Options} to {@code NEW java/lang/Object}. */
	private static byte[] withOptionsConstructionRemoved(byte[] classBytes) {
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);

		int moved = 0;
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn.getOpcode() == Opcodes.NEW && insn instanceof TypeInsnNode type
						&& OPTIONS.equals(type.desc)) {
					type.desc = "java/lang/Object";
					moved++;
				}
			}
		}
		if (moved == 0) return classBytes;

		// COMPUTE_MAXS only: the class is never loaded, it is only read back by the injector, and recomputing
		// frames here would need a class hierarchy the test does not have.
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static byte[] read(String entry) throws Exception {
		if (!Files.isRegularFile(MERGED_BASE)) return null;
		try (ZipFile zip = new ZipFile(MERGED_BASE.toFile())) {
			ZipEntry e = zip.getEntry(entry);
			if (e == null) return null;
			try (InputStream in = zip.getInputStream(e)) {
				return in.readAllBytes();
			}
		}
	}
}
