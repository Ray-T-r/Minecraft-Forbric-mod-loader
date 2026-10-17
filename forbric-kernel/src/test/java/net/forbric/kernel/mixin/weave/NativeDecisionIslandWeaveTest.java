package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.NativeDecisionIsland;

/**
 * {@code NativeDecisionIsland} through the real weave, on classes that share no name with any game or mod.
 *
 * <p>Natively {@code org.brook.fen.Wader.step} decides the air itself: it asks {@code isSubmerged(WATER)} and, only with the
 * head in water outside foam, the static {@code Charms.canHoldBreath(this)}. As merged, the step hands the whole calculation
 * to the carrier's {@code Lungs.breathe}, which asks the charm with the head in any liquid (lava too) and decides the air its
 * own way. The guest, {@code com.heron.snorkel}, wraps both native calls (a lava notice, a tank that holds the breath) and
 * turns slime into water at the head check with a value modifier.
 *
 * <p>With the island, the guest binds the native calls in {@code step} and every guest line is the native run's, in every
 * liquid; under water and in slime the carrier is answered what the guest answered, so the air is the native air too. In
 * lava the carrier asks its own charm question, unwrapped, as merged (its own line), and decides the air its own way — and
 * the tank is never consulted there, as natively. Switched off, the guest is never asked.
 *
 * <p>The value modifier alone (no wrap of the charm, so nothing the carrier asks again) gets no island: its answer would steer
 * no decision anyone hears, so it is left unbound, as merged. Beside the wraps it binds the head check the island holds, and
 * that is said: its answer steers the native decisions after it, not the carrier's own.
 */
class NativeDecisionIslandWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/decisionisland");
	private static final String CONFIG = "decisionisland.mixins.json", SLIME = "decisionisland-slime.mixins.json";
	private static final String MOD = "snorkel";
	private static final String TARGET = "org/brook/fen/Wader";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result island, off, natively, slimeAlone;

	@BeforeAll static void weave() throws Exception {
		List<Path> sources;
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			sources = walk.filter(p -> p.toString().endsWith(".java") && !SOURCES.relativize(p).startsWith("native")).sorted().toList();
		}
		assertEquals(9, sources.size(), "the fixture's sources changed; update this test with it: " + sources);
		List<Path> nativeSources = new ArrayList<>(sources);
		nativeSources.remove(SOURCES.resolve("org/brook/fen/Wader.java"));
		nativeSources.add(SOURCES.resolve("native/org/brook/fen/Wader.java"));
		Path nativeFixture = WeaveHarness.fixture(work, "native", nativeSources, Map.of(CONFIG, SOURCES.resolve(CONFIG)), List.of("-g"));
		fixture = NativeWeaveReferences.with(work, WeaveHarness.fixture(work, "decisionisland", sources, Map.of(CONFIG, SOURCES.resolve(CONFIG),
				SLIME, SOURCES.resolve(SLIME)), List.of("-g")), NativeWeaveReferences.classes(nativeFixture));
		island = run(fixture, "island", Map.of());
		off = run(fixture, "off", Map.of(NativeDecisionIsland.PROPERTY, "off"));
		natively = run(nativeFixture, "native", Map.of());
		slimeAlone = WeaveHarness.run(work, "slime-alone", fixture, SLIME, MOD, Ecosystem.FABRIC, EnvType.SERVER, "fixture.decisionisland.Probe", "probe",
				Map.of());
	}

	@Test void theGuestBindsTheNativeCallsAndTheClassVerifies() throws Exception {
		assertTrue(island.printed(WeaveHarnessMain.DONE), island.describe());
		assertTrue(island.printed("[Forbric/Mixin] com.heron.snorkel.mixin.WaderMixin.snorkel$lava: org.brook.fen.Wader.isSubmerged is asked in "
				+ "org.brook.fen.Wader.step again"), island.describe());
		assertTrue(island.printed("[Forbric/Mixin] com.heron.snorkel.mixin.WaderMixin.snorkel$tank: org.brook.fen.Charms.canHoldBreath, asked in "
				+ "org.brook.fen.Wader.step again by its native decision island, answers the carrier what it answered there"), island.describe());
		assertEquals(List.of(), island.findings().stream().filter(f -> f.modId().equals(MOD) && !f.confidence().equals("RESOLVED")).toList(),
				island.describe());
		WeaveHarness.assertWovenAndVerified(island, TARGET, fixture);
		ClassNode node = new ClassNode();
		new ClassReader(island.defined(TARGET)).accept(node, 0);
		assertTrue(calls(node, "step", "snorkel$lava") && calls(node, "step", "snorkel$tank") && calls(node, "step", "breathe"), island.describe());
	}

	/** Beside the wraps, the value modifier binds the head check the island holds, and the log says whose decisions its answer steers. */
	@Test void theValueModifierBesideTheWrapsIsToldWhoseDecisionsItSteers() throws Exception {
		assertTrue(island.printed("[Forbric/Mixin] com.heron.snorkel.mixin.SlimeMixin.slime$drown may change what org.brook.fen.Wader.isSubmerged "
				+ "answers in org.brook.fen.Wader.step: that answer steers the native decisions the island repeats there"), island.describe());
		ClassNode node = new ClassNode();
		new ClassReader(island.defined(TARGET)).accept(node, 0);
		assertTrue(calls(node, "step", "slime$drown"), island.describe());
	}

	/** Alone, the value modifier's answer would steer nothing anyone hears: no island, and it binds nothing, as merged. */
	@Test void theValueModifierAloneGetsNoIsland() throws Exception {
		assertTrue(slimeAlone.printed(WeaveHarnessMain.DONE), slimeAlone.describe());
		assertFalse(slimeAlone.output().contains("is asked in org.brook.fen.Wader.step again"), slimeAlone.describe());
		ClassNode node = new ClassNode();
		new ClassReader(slimeAlone.defined(TARGET)).accept(node, 0);
		assertFalse(calls(node, "step", "slime$drown") || calls(node, "step", "isSubmerged"), slimeAlone.describe());
		assertFalse(done(slimeAlone).contains("slime:"), slimeAlone.describe());
	}

	/** Under water, in open air and in slime, every line and every air value is the native run's, step by step. */
	@Test void underWaterInAirAndInSlimeTheRunIsTheNativeOne() {
		for (String liquid : List.of("water", "air", "slime"))
			assertEquals(steps(natively, liquid), steps(island, liquid), liquid + "\n" + natively.describe() + "\n" + island.describe());
	}

	/** In lava the guest's lines are the native ones — the lava notice, never the tank — while the carrier decides the air. */
	@Test void inLavaTheGuestLinesAreTheNativeOnesAndTheTankIsNeverAsked() {
		assertEquals(guest(steps(natively, "lava")), guest(steps(island, "lava")), island.describe());
		assertTrue(steps(island, "lava").stream().noneMatch(step -> step.contains("snorkel:tank")), island.describe());
		assertTrue(steps(island, "lava").stream().allMatch(step -> step.contains("snorkel:lava")), island.describe());
	}

	@Test void switchedOffTheGuestIsNeverAsked() {
		assertTrue(off.printed(WeaveHarnessMain.DONE), off.describe());
		String line = done(off);
		assertFalse(line.contains("snorkel:") || line.contains("slime:"), off.describe());
	}

	@Test void theControlFlipsTheAssertions() {
		assertNotEquals(steps(natively, "water"), steps(off, "water"));
		assertEquals(steps(natively, "water"), steps(island, "water"));
	}

	private static List<String> steps(WeaveHarness.Result run, String liquid) {
		return Arrays.stream(done(run).split(" \\| ")).filter(step -> step.startsWith(liquid)).toList();
	}

	private static List<String> guest(List<String> steps) {
		return steps.stream().map(step -> step.substring(0, step.indexOf(": ") + 2) + Arrays.stream(step.substring(step.indexOf(": ") + 2).split(", "))
				.filter(line -> line.startsWith("snorkel:") || line.startsWith("slime:")).collect(Collectors.joining(", "))).toList();
	}

	private static String done(WeaveHarness.Result run) {
		String line = run.output().lines().filter(l -> l.startsWith(WeaveHarnessMain.DONE + " ")).findFirst().orElse("");
		return line.substring(Math.min(line.length(), WeaveHarnessMain.DONE.length() + 1));
	}

	private static boolean calls(ClassNode node, String method, String callee) {
		for (MethodNode body : node.methods) {
			if (!body.name.equals(method)) continue;
			for (AbstractInsnNode insn : body.instructions)
				if (insn instanceof MethodInsnNode call && call.name.contains(callee)) return true;
		}
		return false;
	}

	private static WeaveHarness.Result run(Path fixture, String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, MOD, Ecosystem.FABRIC, EnvType.SERVER, "fixture.decisionisland.Probe", "probe", properties);
	}
}
