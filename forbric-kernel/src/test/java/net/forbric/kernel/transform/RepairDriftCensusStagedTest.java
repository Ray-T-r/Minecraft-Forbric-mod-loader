package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

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
	private static final Path RUN = TestFixtures.stagedRoot();
	private static final String KEY_MAPPING = "net.minecraft.client.KeyMapping";
	private static final String LOOKUP_CLAIM = "forbric-merged-base-compat#routeKeyMappingClickToPopulatedLookup";

	@TempDir Path temporary;

	private static List<Path> stagedJars() {
		return List.of(RUN.resolve(TestFixtures.BASE_JAR), RUN.resolve(TestFixtures.NEO_CARRIER_JAR));
	}

	@Test
	void theBuildThisTreeIsCalibratedAgainstMovesNoAnchor() throws Exception {
		List<Path> jars = stagedJars();
		TestFixtures.requireFiles(Fixture.STAGED, "staged merged base or carriers absent", jars.toArray(Path[]::new));
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
		TestFixtures.requireFiles(Fixture.STAGED, "staged merged base or carriers absent", jars.toArray(Path[]::new));
		byte[] real = RepairDriftCensus.bytesOf(jars, KEY_MAPPING);
		assertNotNull(real, "the staged base carries KeyMapping");

		// The candidate: one class whose click already reads the populated key lookup, so the repair that routes it
		// there has nothing left to do. This is the shape of an upstream bump that fixes
		// something the kernel was compensating for — which is a decline just as much as a break is.
		Path candidate = temporary.resolve("candidate.jar");
		try (OutputStream os = Files.newOutputStream(candidate); ZipOutputStream zos = new ZipOutputStream(os)) {
			zos.putNextEntry(new ZipEntry("net/minecraft/client/KeyMapping.class"));
			zos.write(withClickReadingTheLiveLookup(real));
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

	/**
	 * {@code KeyMapping} as an upstream that fixed the merge's {@code click} would ship it: every read of the Forge-side
	 * {@code MAP} outside the initialisers already goes to NeoForge's lookup, which is the edit
	 * routeKeyMappingClickToPopulatedLookup makes, so that repair has nothing left to do.
	 */
	private static byte[] withClickReadingTheLiveLookup(byte[] classBytes) {
		String forgeLookup = "Lnet/minecraftforge/client/settings/KeyMappingLookup;";
		String neoLookup = "Lnet/neoforged/neoforge/client/settings/KeyMappingLookup;";
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		int edited = 0;
		for (MethodNode m : node.methods) {
			if (m.name.equals("<init>") || m.name.equals("<clinit>")) continue;
			for (org.objectweb.asm.tree.AbstractInsnNode insn : m.instructions) {
				if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC && f.name.equals("MAP") && f.desc.equals(forgeLookup)) {
					f.desc = neoLookup;
					edited++;
				} else if (insn instanceof org.objectweb.asm.tree.MethodInsnNode c && c.name.equals("getAll")
						&& c.owner.equals(forgeLookup.substring(1, forgeLookup.length() - 1))) {
					c.owner = neoLookup.substring(1, neoLookup.length() - 1);
					edited++;
				}
			}
		}
		assertTrue(edited > 0, "premise: the staged KeyMapping still reads the Forge-side MAP outside its initialisers");
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}
}
