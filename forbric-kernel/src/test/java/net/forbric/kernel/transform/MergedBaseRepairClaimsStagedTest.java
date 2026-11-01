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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.api.EnvType;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * Every fixed-target claim of the compat transformer, fed its real target from the staged merged base or carrier:
 * the ledger is clean and every claim is a hit. Then one target is mutated so exactly one repair declines, and the
 * ledger names that claim and no other — which is the whole reason claims exist.
 */
class MergedBaseRepairClaimsStagedTest {
	private static final Path RUN = TestFixtures.stagedRoot();
	private static final List<Path> JARS = List.of(RUN.resolve(TestFixtures.BASE_JAR), RUN.resolve(TestFixtures.NEO_CARRIER_JAR));
	private static final TransformContext CTX = new TransformContext(EnvType.CLIENT, false, "intermediary");
	private static final String BOOTSTRAP = "net.minecraft.server.Bootstrap";
	private static final String MINECRAFT = "net.minecraft.client.Minecraft";
	private static final String STREAMS_CLAIM = "forbric-merged-base-compat#wrapTheStreamsVanillaWraps";

	@Test
	void everyClaimLandsOnItsStagedTargetAndTheLedgerIsClean() throws Exception {
		TestFixtures.requireFiles(Fixture.STAGED, "staged merged base or carriers absent", JARS.toArray(Path[]::new));
		TransformChain chain = new TransformChain();
		ForbricMergedBaseCompatTransformer compat = new ForbricMergedBaseCompatTransformer();
		chain.register(TransformPhase.COREMOD, compat);

		Map<String, byte[]> targets = targets(compat);
		for (Map.Entry<String, byte[]> target : targets.entrySet()) {
			assertNotNull(target.getValue(), "a claim names " + target.getKey() + ", which no staged jar carries");
			chain.applyBeforeMixin(target.getKey(), target.getValue(), CTX);
		}
		AnchorLedger.Report report = chain.ledger().report();
		assertTrue(report.clean(), () -> "claims that declined their staged target: " + report.misses());
		assertEquals(0, report.absent().size(), () -> "claims whose target was never fed: " + report.absent());
		assertEquals(report.declared(), report.hit(), "every declared claim anchor is a hit");
	}

	@Test
	void whenOneRepairDeclinesTheLedgerNamesExactlyThatClaim() throws Exception {
		TestFixtures.requireFiles(Fixture.STAGED, "staged merged base or carriers absent", JARS.toArray(Path[]::new));
		TransformChain chain = new TransformChain();
		ForbricMergedBaseCompatTransformer compat = new ForbricMergedBaseCompatTransformer();
		chain.register(TransformPhase.COREMOD, compat);

		byte[] real = bytesOf(BOOTSTRAP);
		assertNotNull(real);
		// Give Bootstrap the wrapStreams() call vanilla makes: the repair that puts it back has nothing to do. A second
		// class with its own claim goes through the same chain, so the ledger has to tell the two apart.
		chain.applyBeforeMixin(BOOTSTRAP, withVanillasStreamWrap(real), CTX);
		chain.applyBeforeMixin(MINECRAFT, bytesOf(MINECRAFT), CTX);

		AnchorLedger.Report report = chain.ledger().report();
		List<String> missed = new ArrayList<>();
		for (AnchorLedger.Miss miss : report.misses()) missed.add(miss.transformer());
		assertEquals(List.of(STREAMS_CLAIM), missed, "exactly the one repair that declined, on its own line");
		assertEquals(BOOTSTRAP, report.misses().get(0).className());
		assertTrue(report.misses().get(0).cost().contains("latest.log"), report.misses().get(0).cost());
		assertEquals(1, report.hit(), "the other class's claim (the window title) is a hit");
	}

	/** Binary name → class bytes for every fixed anchor of every claim. */
	private static Map<String, byte[]> targets(ClassTransformer transformer) {
		Map<String, byte[]> out = new LinkedHashMap<>();
		for (ClassTransformer.Claim claim : transformer.claims()) {
			for (AnchorSet.Anchor anchor : claim.anchors().anchors()) {
				// As the chain feeds the compat transformer: after the vanilla member twins (the PiP map's lookup the
				// bridge wires exists only once they have run on the genuine base).
				out.computeIfAbsent(anchor.binaryName(), n -> PreMixinView.twinned(n.replace('.', '/'), bytesOf(n)));
			}
		}
		return out;
	}

	private static byte[] bytesOf(String binaryName) {
		String entryName = binaryName.replace('.', '/') + ".class";
		for (Path jar : JARS) {
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				ZipEntry entry = zip.getEntry(entryName);
				if (entry == null) continue;
				try (InputStream in = zip.getInputStream(entry)) {
					return in.readAllBytes();
				}
			} catch (java.io.IOException unreadable) {
				return null;
			}
		}
		return null;
	}

	/**
	 * {@code Bootstrap} as an upstream that kept vanilla's line would ship it: {@code bootStrap()} calls its own
	 * {@code wrapStreams()} right before it records {@code bootstrapDuration}, which is the call
	 * wrapTheStreamsVanillaWraps puts back on NeoForge's base, so that repair has nothing left to do.
	 */
	private static byte[] withVanillasStreamWrap(byte[] classBytes) {
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		MethodNode bootStrap = node.methods.stream().filter(m -> m.name.equals("bootStrap") && m.desc.equals("()V"))
				.findFirst().orElseThrow();
		for (org.objectweb.asm.tree.AbstractInsnNode insn : bootStrap.instructions) {
			assertTrue(!(insn instanceof org.objectweb.asm.tree.MethodInsnNode c && c.name.equals("wrapStreams")),
					"premise: the staged Bootstrap.bootStrap() does not call wrapStreams() (NeoForge's patch replaced that call)");
		}
		FieldInsnNode duration = null;
		for (org.objectweb.asm.tree.AbstractInsnNode insn : bootStrap.instructions) {
			if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC && f.name.equals("bootstrapDuration")) {
				duration = f;
				break;
			}
		}
		assertNotNull(duration, "premise: bootStrap() records bootstrapDuration");
		bootStrap.instructions.insertBefore(duration, new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESTATIC,
				"net/minecraft/server/Bootstrap", "wrapStreams", "()V", false));
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

}
