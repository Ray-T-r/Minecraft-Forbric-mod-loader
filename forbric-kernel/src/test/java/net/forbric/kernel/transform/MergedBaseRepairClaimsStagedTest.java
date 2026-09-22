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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

/**
 * Every fixed-target claim of the compat transformer, fed its real target from the staged merged base or carrier:
 * the ledger is clean and every claim is a hit. Then one target is mutated so exactly one repair declines, and the
 * ledger names that claim and no other — which is the whole reason claims exist.
 */
class MergedBaseRepairClaimsStagedTest {
	private static final Path RUN = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", System.getProperty("user.dir") + "/../forbric-loader"), "run").normalize();
	private static final List<Path> JARS = List.of(RUN.resolve("merged-base/patched-mc-merged-26.2.jar"),
			RUN.resolve("neoforge-runtime/neoforge-runtime.jar"), RUN.resolve("forge-runtime/forge-runtime.jar"));
	private static final TransformContext CTX = new TransformContext(EnvType.CLIENT, false, "intermediary");
	private static final String KEY_MAPPING = "net.minecraft.client.KeyMapping";
	private static final String LOOKUP_CLAIM = "forbric-merged-base-compat#addMissingForgeKeyMappingLookupInitializer";

	@Test
	void everyClaimLandsOnItsStagedTargetAndTheLedgerIsClean() throws Exception {
		assumeTrue(JARS.stream().allMatch(Files::isRegularFile), "staged merged base or carriers absent");
		TransformChain chain = new TransformChain();
		ForbricMergedBaseCompatTransformer compat = new ForbricMergedBaseCompatTransformer(MergedBaseRepairClaimsStagedTest::bytesOf);
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
		assumeTrue(JARS.stream().allMatch(Files::isRegularFile), "staged merged base or carriers absent");
		TransformChain chain = new TransformChain();
		ForbricMergedBaseCompatTransformer compat = new ForbricMergedBaseCompatTransformer(MergedBaseRepairClaimsStagedTest::bytesOf);
		chain.register(TransformPhase.COREMOD, compat);

		byte[] real = bytesOf(KEY_MAPPING);
		assertNotNull(real);
		// The merge left MinecraftForge's KeyMapping.MAP without an initializer; give it one, and the repair that
		// adds the initializer has nothing to do while the other two KeyMapping repairs still apply.
		chain.applyBeforeMixin(KEY_MAPPING, withForgeLookupInitialised(real), CTX);

		AnchorLedger.Report report = chain.ledger().report();
		List<String> missed = new ArrayList<>();
		for (AnchorLedger.Miss miss : report.misses()) missed.add(miss.transformer());
		assertEquals(List.of(LOOKUP_CLAIM), missed, "exactly the one repair that declined, on its own line");
		assertEquals(KEY_MAPPING, report.misses().get(0).className());
		assertTrue(report.misses().get(0).cost().contains("never initialised"), report.misses().get(0).cost());
		assertEquals(2, report.hit(), "the other two KeyMapping claims are hits");
	}

	/** Binary name → class bytes for every fixed anchor of every claim. */
	private static Map<String, byte[]> targets(ClassTransformer transformer) {
		Map<String, byte[]> out = new LinkedHashMap<>();
		for (ClassTransformer.Claim claim : transformer.claims()) {
			for (AnchorSet.Anchor anchor : claim.anchors().anchors()) {
				out.computeIfAbsent(anchor.binaryName(), MergedBaseRepairClaimsStagedTest::bytesOf);
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

	private static byte[] withForgeLookupInitialised(byte[] classBytes) {
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		MethodNode clinit = null;
		for (MethodNode m : node.methods) if (m.name.equals("<clinit>")) clinit = m;
		assertNotNull(clinit, "KeyMapping has a <clinit>");
		clinit.instructions.insert(new FieldInsnNode(Opcodes.PUTSTATIC, "net/minecraft/client/KeyMapping", "MAP",
				"Lnet/minecraftforge/client/settings/KeyMappingLookup;"));
		clinit.instructions.insert(new InsnNode(Opcodes.ACONST_NULL));
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}
}
