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
	private static final String BOOTSTRAP = "net.minecraft.server.Bootstrap";
	private static final String STREAMS_CLAIM = "forbric-merged-base-compat#wrapTheStreamsVanillaWraps";

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
		byte[] real = RepairDriftCensus.bytesOf(jars, BOOTSTRAP);
		assertNotNull(real, "the staged base carries Bootstrap");

		// The candidate: one class that already calls what a repair puts back (vanilla's wrapStreams() in bootStrap()), so
		// that repair has nothing left to do. This is the shape of an upstream bump that fixes something the kernel was
		// compensating for — which is a decline just as much as a break is.
		Path candidate = temporary.resolve("candidate.jar");
		try (OutputStream os = Files.newOutputStream(candidate); ZipOutputStream zos = new ZipOutputStream(os)) {
			zos.putNextEntry(new ZipEntry("net/minecraft/server/Bootstrap.class"));
			zos.write(withVanillasStreamWrap(real));
			zos.closeEntry();
		}

		List<Path> withCandidate = new ArrayList<>();
		withCandidate.add(candidate);
		withCandidate.addAll(jars);
		// Measured against the staged build's own declines, so the candidate's one class is all that differs.
		List<String> before = RepairDriftCensus.replay(jars).declined();
		RepairDriftCensus.Drift drift = RepairDriftCensus.replay(withCandidate);
		System.out.println(drift.summary());
		List<String> added = new ArrayList<>(drift.declined());
		added.removeAll(before);
		assertEquals(1, added.size(), "exactly the one repair that no longer applies: " + added);
		assertTrue(added.get(0).contains(STREAMS_CLAIM), "the decline must name the claim, not just count one: " + added.get(0));
		assertTrue(drift.declined().containsAll(before), "and nothing the staged build declined is hidden by it");
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
