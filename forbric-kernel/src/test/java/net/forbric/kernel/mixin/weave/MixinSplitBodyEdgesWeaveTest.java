/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.MergedBaseUncalledMethods;
import net.forbric.kernel.mixin.MixinSplitBodyEdges;
import net.forbric.kernel.transform.VanillaEarlyReturns;

/**
 * {@code MixinSplitBodyEdges} through the real Mixin weave, on classes that share no name with any game, loader or mod.
 *
 * <p>{@code org.weft.loom.Tapestry}, as the guest's loader has it, {@code show}s by calling {@code weave} — one guard for a null
 * thread, then the three steps — and then its own {@code border}. As merged, the carrier split {@code weave} into {@code warp},
 * {@code weft} and {@code fringe}, each repeating the guard, and shows each as a layer, with {@code border} between the last two;
 * {@code weave} survives as a dispatcher nothing calls. The guest {@code io.lark.hem} vetoes at the start of {@code weave} and
 * logs at its end.
 *
 * <p>Moved, the guest's log is the native one, call for call (the carrier's own {@code border} sits elsewhere in the order,
 * which is the carrier's): the start once per show, the end only after the three steps ran, never for the null thread
 * (fringe's guard is the native guard, given back by VanillaEarlyReturns from the row the census writes for the last piece),
 * and the veto skipping all three steps and the end. Switched off, the guest is bound to the dispatcher and never heard.
 */
class MixinSplitBodyEdgesWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/splitbody");
	private static final String CONFIG = "splitbody.mixins.json";
	private static final String TARGET = "org/weft/loom/Tapestry";
	private static final String NATIVE_GUEST = "[before:a, wind:a, dye:a, knot:a, after:a, before:null, before:veto, before:b, wind:b, dye:b, knot:b, after:b]";
	private static final String UNHEARD = "[wind:a, dye:a, knot:a, wind:veto, dye:veto, knot:veto, wind:b, dye:b, knot:b]";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result moved, off, unsplit, natively;

	@BeforeAll static void weave() throws Exception {
		List<Path> sources;
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			sources = new ArrayList<>(walk.filter(p -> p.toString().endsWith(".java") && !SOURCES.relativize(p).startsWith("native")).sorted().toList());
		}
		List<Path> nativeSources = new ArrayList<>(sources);
		nativeSources.remove(SOURCES.resolve("org/weft/loom/Tapestry.java"));
		nativeSources.add(SOURCES.resolve("native/org/weft/loom/Tapestry.java"));
		Path nativeFixture = WeaveHarness.fixture(work, "native", nativeSources, Map.of(CONFIG, SOURCES.resolve(CONFIG)), List.of("-g"));
		Path merged = WeaveHarness.fixture(work, "splitbody", sources, Map.of(CONFIG, SOURCES.resolve(CONFIG)), List.of("-g"));
		fixture = NativeWeaveReferences.with(work, merged, NativeWeaveReferences.classes(nativeFixture));

		// The two census rows this stand-in game's own census would write: nothing calls weave, which the native show calls;
		// and fringe, the last piece, keyed against the native weave.
		Path uncalled = work.resolve("uncalled-extra.txt");
		Files.writeString(uncalled, TARGET + "#weave(Ljava/lang/String;)V | FABRIC | " + TARGET + "#show(Ljava/lang/String;)V\n");
		Path earlyReturns = work.resolve("early-returns-extra.txt");
		List<String> rows = VanillaEarlyReturns.pieceRows(node(NativeWeaveReferences.classes(merged).get(TARGET)),
				node(NativeWeaveReferences.classes(nativeFixture).get(TARGET)));
		assertEquals(1, rows.size(), "the census keys the last piece against the native method: " + rows);
		assertTrue(rows.getFirst().startsWith(TARGET + "#fringe(Ljava/lang/String;)V "), rows.toString());
		Files.writeString(earlyReturns, String.join("\n", rows) + "\n");

		Map<String, String> tables = Map.of(MergedBaseUncalledMethods.EXTRA_PROPERTY, uncalled.toString(),
				VanillaEarlyReturns.EXTRA_PROPERTY, earlyReturns.toString());
		moved = run(fixture, "moved", tables);
		Map<String, String> switchedOff = new java.util.HashMap<>(tables);
		switchedOff.put(MixinSplitBodyEdges.PROPERTY, "off");
		off = run(fixture, "off", switchedOff);
		Map<String, String> noEarlyReturns = new java.util.HashMap<>(tables);
		noEarlyReturns.put(VanillaEarlyReturns.PROPERTY, "off");
		unsplit = run(fixture, "unsplit", noEarlyReturns);
		natively = run(nativeFixture, "native", Map.of());
	}

	@Test void natively() throws Exception {
		assertEquals(NATIVE_GUEST, withoutBorder(log(natively)), natively.describe());
		assertEquals(4, borders(log(natively)), natively.describe());
	}

	@Test void movedTheGuestHearsWhatItHearsNatively() throws Exception {
		List<String> log = log(moved);
		assertEquals(NATIVE_GUEST, withoutBorder(log), "the start, the end and the veto as on the guest's own loader — " + moved.describe());
		assertEquals(4, borders(log), "the carrier's own layer is untouched — " + moved.describe());
		assertTrue(moved.printed("hem$before's start of org.weft.loom.Tapestry.weave(Ljava/lang/String;)V moves to the start of warp"),
				moved.describe());
		assertTrue(moved.printed("hem$after's end of org.weft.loom.Tapestry.weave(Ljava/lang/String;)V moves to the end of fringe"),
				moved.describe());
		WeaveHarness.assertWovenAndVerified(moved, TARGET, fixture);
	}

	@Test void switchedOffTheGuestIsNeverHeard() throws Exception {
		assertEquals(UNHEARD, withoutBorder(log(off)), off.describe());
		assertFalse(off.printed("moves to the start of"), off.describe());
		WeaveHarness.assertWovenAndVerified(off, TARGET, fixture);
	}

	/**
	 * Without the last piece's guard given back, its end runs for the null thread too: the end is not moved (and says why),
	 * the start still is.
	 */
	@Test void withoutTheNativeGuardTheEndStaysWhereItWasWritten() throws Exception {
		assertEquals("[before:a, wind:a, dye:a, knot:a, before:null, before:veto, before:b, wind:b, dye:b, knot:b]", withoutBorder(log(unsplit)),
				unsplit.describe());
		assertTrue(unsplit.printed("its native method returns early, and that piece's tail was not given the same early return"),
				unsplit.describe());
		WeaveHarness.assertWovenAndVerified(unsplit, TARGET, fixture);
	}

	private static WeaveHarness.Result run(Path fixture, String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, List.of(new WeaveHarness.Config(CONFIG, "hem", Ecosystem.FABRIC)),
				List.of(VanillaEarlyReturns.class), EnvType.CLIENT, "io.lark.hem.Probe", "probe", properties);
	}

	private static List<String> log(WeaveHarness.Result run) {
		for (String line : run.output().split("\n")) {
			int at = line.indexOf("[SplitBody] log=[");
			if (at < 0) continue;
			String body = line.substring(at + "[SplitBody] log=[".length(), line.lastIndexOf(']'));
			return body.isEmpty() ? List.of() : List.of(body.split(", "));
		}
		throw new AssertionError("no log printed — " + run.describe());
	}

	private static String withoutBorder(List<String> log) {
		return log.stream().filter(e -> !e.startsWith("border:")).toList().toString();
	}

	private static long borders(List<String> log) {
		return log.stream().filter(e -> e.startsWith("border:")).count();
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}
}
