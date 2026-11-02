package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
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

	/** The staged base and carrier, checked present before anything is built from them. */
	private static List<Path> stagedBaseAndCarrier() {
		List<Path> jars = List.of(RUN.resolve(TestFixtures.BASE_JAR), RUN.resolve(TestFixtures.NEO_CARRIER_JAR));
		TestFixtures.requireFiles(Fixture.STAGED, "staged base or carrier absent", jars.toArray(Path[]::new));
		return jars;
	}

	/**
	 * The staged base and carrier, then the vanilla-reference sidecar a launch on that base stages beside it: the vanilla
	 * twins, which run before the compat transformer, copy vanilla's bodies from it. The sidecar is built from Minecraft's
	 * own jar, so only the tests that read it need that fixture.
	 */
	private static List<Path> stagedJars() {
		List<Path> jars = new ArrayList<>(stagedBaseAndCarrier());
		jars.add(TestFixtures.vanillaReference(jars.get(0)));
		return List.copyOf(jars);
	}

	@Test
	void theBuildThisTreeIsCalibratedAgainstMovesNoAnchor() throws Exception {
		List<Path> jars = stagedJars();
		RepairDriftCensus.Drift drift = RepairDriftCensus.replay(jars);
		System.out.println(drift.summary());
		assertTrue(drift.declared() > 0, "no claims declared — the census did not run");
		assertEquals(List.of(), drift.declined(), "a repair declined on the build this tree is calibrated against");
		assertEquals(List.of(), drift.hedged(), "a vanilla twin stood down on the build this tree is calibrated against");
		assertEquals(List.of(), drift.absent(), "a claim's target is not in the staged jars at all");
		assertEquals(drift.declared(), drift.hit());
		assertTrue(drift.everyClaimLanded(), drift.summary());
	}

	/**
	 * The replay is only as good as its copy of the launch's chain. Without the vanilla reference the twins that copy
	 * vanilla's bodies stand down, and the repair that stands on one — GuiRenderer's picture-in-picture bridge, which wires
	 * the vanilla {@code preparePictureInPictureState} body a twin puts back — must then decline by name: a replay that
	 * dropped the twin pass (as this one did until Stage 4 C4) reads as drift on the calibrated build, never as a pass.
	 */
	@Test
	void withoutTheVanillaReferenceARepairStandingOnACopiedBodyDeclinesByName() throws Exception {
		List<Path> jars = stagedBaseAndCarrier();
		RepairDriftCensus.Drift drift = RepairDriftCensus.replay(jars);
		System.out.println(drift.summary());
		assertTrue(drift.declined().stream().anyMatch(d -> d.contains("forbric-merged-base-compat#bridgeOrphanedPipRenderers")),
				"the picture-in-picture bridge needs the twin's vanilla body: " + drift.declined());
		assertTrue(drift.hit() < drift.declared(), "fewer claims land without the vanilla reference: " + drift.summary());
		assertFalse(drift.vanillaReference(), "neither the base nor the carrier carries a FABRIC index");
		assertTrue(drift.lines().stream().anyMatch(l -> l.contains("no vanilla reference among these jars")),
				"the census says it had no vanilla reference: " + drift.lines());
	}

	/**
	 * A stale sidecar, the shape a base bump leaves behind: its index has no row for a class a vanilla twin copies a body
	 * from (here {@code Hud}, whose one twin row is a vanilla body no compat repair stands on), so that twin stands down.
	 * The ledger files it as a hedge, not a miss, so nothing declines; the census must still name it and withhold the
	 * all-clear, since at launch the vanilla member Fabric mods call would be missing.
	 */
	@Test
	void aTwinThatStandsDownOnAStaleSidecarIsNamedAndWithholdsTheAllClear() throws Exception {
		List<Path> jars = stagedJars();
		Path stale = temporary.resolve("stale-vanilla-reference.jar");
		int dropped = 0;
		try (ZipFile sidecar = new ZipFile(jars.get(2).toFile());
				OutputStream os = Files.newOutputStream(stale); ZipOutputStream zos = new ZipOutputStream(os)) {
			for (Enumeration<? extends ZipEntry> e = sidecar.entries(); e.hasMoreElements();) {
				ZipEntry entry = e.nextElement();
				byte[] bytes;
				try (InputStream in = sidecar.getInputStream(entry)) {
					bytes = in.readAllBytes();
				}
				if (entry.getName().equals(RepairDriftCensus.FABRIC_INDEX)) {
					StringBuilder kept = new StringBuilder();
					for (String line : new String(bytes, StandardCharsets.UTF_8).split("\\R")) {
						if (line.startsWith(VanillaMemberTwinInjector.HUD + "\t")) {
							dropped++;
							continue;
						}
						kept.append(line).append('\n');
					}
					bytes = kept.toString().getBytes(StandardCharsets.UTF_8);
				}
				zos.putNextEntry(new ZipEntry(entry.getName()));
				zos.write(bytes);
				zos.closeEntry();
			}
		}
		assertEquals(1, dropped, "premise: the staged sidecar's FABRIC index has one row for " + VanillaMemberTwinInjector.HUD);

		RepairDriftCensus.Drift drift = RepairDriftCensus.replay(List.of(jars.get(0), jars.get(1), stale));
		System.out.println(drift.summary());
		assertTrue(drift.vanillaReference(), "the stale sidecar still carries a FABRIC index");
		assertEquals(List.of(), drift.declined(), "no repair stands on the Hud twin: " + drift.declined());
		assertEquals(List.of(), drift.absent());
		assertEquals(1, drift.hedged().size(), "exactly the Hud twin stood down: " + drift.hedged());
		assertTrue(drift.hedged().get(0).contains("forbric:vanilla-member-twins")
				&& drift.hedged().get(0).contains("net.minecraft.client.gui.Hud"), drift.hedged().get(0));
		assertEquals(drift.declared() - 1, drift.hit(), drift.summary());
		assertFalse(drift.everyClaimLanded(), "a twin that stood down is not a landing: " + drift.summary());
		assertTrue(drift.lines().stream().anyMatch(l -> l.startsWith("    HEDGED    ") && l.contains("Hud")),
				"the census names the hedge: " + drift.lines());
		assertTrue(drift.lines().stream().noneMatch(l -> l.contains("every repair still lands")),
				"no all-clear while a claim did not land: " + drift.lines());
	}

	@Test
	void aCandidateThatMovesOneAnchorIsNamedBeforeItIsAdopted() throws Exception {
		List<Path> jars = stagedJars();
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
