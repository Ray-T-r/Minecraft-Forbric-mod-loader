package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Replays the repair ledger against a candidate game build, and proves the replay can come back red.
 *
 * <p>A repair is calibrated against one pair of carrier versions, and "fixed" therefore has a half-life: the
 * 26.2.0.38-beta → .88 bump gave {@code RegistryDataLoader.load} a fifth parameter and silently took away every
 * datapack registry a Fabric mod declared. Nothing here measured that. The existing staged test proves the
 * repairs land on the base that IS here, which says nothing about the one that is coming.
 *
 * <p>The mutated jar goes FIRST in the list rather than being a doctored 35 MB copy: the census resolves a class
 * from the first jar that has it, so a one-entry jar is a candidate build that differs in exactly one class.
 */
class RepairDriftCensusStagedTest {
	private static final Path RUN =
			Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run").normalize();
	private static final String KEY_MAPPING = "net.minecraft.client.KeyMapping";
	private static final String LOOKUP_CLAIM = "forbric-merged-base-compat#addMissingForgeKeyMappingLookupInitializer";

	@TempDir Path temporary;

	private static List<Path> stagedJars() {
		String override = System.getenv("FORBRIC_OLD");
		Path run = override == null || override.isBlank() ? RUN : Path.of(override, "run").normalize();
		return List.of(run.resolve("merged-base/patched-mc-merged-26.2.jar"),
				run.resolve("neoforge-runtime/neoforge-runtime.jar"),
				run.resolve("forge-runtime/forge-runtime.jar"));
	}

	@Test
	void theBuildThisTreeIsCalibratedAgainstMovesNoAnchor() throws Exception {
		List<Path> jars = stagedJars();
		assumeTrue(jars.stream().allMatch(Files::isRegularFile), "staged merged base or carriers absent");
		RepairDriftCensus.Drift drift = RepairDriftCensus.replay(jars);
		System.out.println(drift.summary());
		assertTrue(drift.declared() > 0, "no claims declared — the census did not run");
		assertEquals(List.of(), drift.declined(), "a repair declined on the build this tree is calibrated against");
		assertEquals(List.of(), drift.absent(), "a claim's target is not in the staged jars at all");
		assertEquals(drift.declared(), drift.hit());
	}

	@Test
	void aCandidateThatMovesOneAnchorIsNamedBeforeItIsAdopted() throws Exception {
		List<Path> jars = stagedJars();
		assumeTrue(jars.stream().allMatch(Files::isRegularFile), "staged merged base or carriers absent");
		byte[] real = RepairDriftCensus.bytesOf(jars, KEY_MAPPING);
		assertNotNull(real, "the staged base carries KeyMapping");

		// The candidate: one class in which MinecraftForge's KeyMapping.MAP already has an initializer, so the
		// repair that adds one has nothing left to do. This is the shape of an upstream bump that fixes
		// something the kernel was compensating for — which is a decline just as much as a break is.
		Path candidate = temporary.resolve("candidate.jar");
		try (OutputStream os = Files.newOutputStream(candidate); ZipOutputStream zos = new ZipOutputStream(os)) {
			zos.putNextEntry(new ZipEntry("net/minecraft/client/KeyMapping.class"));
			zos.write(withForgeLookupInitialised(real));
			zos.closeEntry();
		}

		List<Path> withCandidate = new ArrayList<>();
		withCandidate.add(candidate);
		withCandidate.addAll(jars);
		RepairDriftCensus.Drift drift = RepairDriftCensus.replay(withCandidate);
		System.out.println(drift.summary());
		assertEquals(1, drift.declined().size(), "exactly the one repair that no longer applies: " + drift.declined());
		assertTrue(drift.declined().get(0).contains(LOOKUP_CLAIM),
				"the decline must name the claim, not just count one: " + drift.declined().get(0));
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
