package net.forbric.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import static org.junit.jupiter.api.Assertions.*;

class LostHookAttributionTest {
	@TempDir Path temporary;

	@Test void sameNamedHooksFromDifferentOwnersDoNotCancelEachOther() {
		MethodNode original = new MethodNode(), merged = new MethodNode();
		original.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
				"net/minecraftforge/common/ForgeHooks", "tick", "()V", false));
		merged.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
				"net/neoforged/neoforge/event/EventHooks", "tick", "()V", false));
		Set<String> lost = LostHookAttribution.hookCalls(original);
		lost.removeAll(LostHookAttribution.hookCalls(merged));
		assertEquals(Set.of("net/minecraftforge/common/ForgeHooks#tick()V"), lost);
	}

	@Test void bothLosingFamiliesAreInventoried() throws Exception {
		Path report = temporary.resolve("conflicts.txt");
		Files.writeString(report, "game/A#tick()V (forge hook lost)\ngame/B#tick()V (neo hook lost)\n"
				+ "game/C#tick()V DECLINED another reason\n");
		var rows = LostHookAttribution.conflicts(report);
		assertEquals(2, rows.size());
		assertEquals("forge", rows.get(0).lostFamily());
		assertEquals("neo", rows.get(1).lostFamily());
		assertEquals("game/B", rows.get(1).owner());
	}

	/** MergedBaseBuilder's second row form: the hooking side lost because only the other body inits a field. */
	@Test void fieldInitKeptRowsAreInventoriedWithTheFamilyThatLostTheHook() throws Exception {
		Path report = temporary.resolve("conflicts.txt");
		Files.writeString(report, "game/A#tick()V (forge hook lost)\n"
				+ "net/minecraft/world/entity/LivingEntity#<init>(Lnet/minecraft/world/entity/EntityType;"
				+ "Lnet/minecraft/world/level/Level;)V (kept neo body to preserve base-added field init; forge hook lost)\n"
				+ "game/B$Inner#<init>()V (kept forge body to preserve base-added field init; neo hook lost)\n");
		var rows = LostHookAttribution.conflicts(report);
		assertEquals(3, rows.size());
		assertFalse(rows.get(0).fieldInitKept());
		assertEquals("net/minecraft/world/entity/LivingEntity", rows.get(1).owner());
		assertEquals("<init>(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/level/Level;)V", rows.get(1).method());
		assertEquals("forge", rows.get(1).lostFamily());
		assertTrue(rows.get(1).fieldInitKept());
		assertEquals("game/B$Inner", rows.get(2).owner());
		assertEquals("neo", rows.get(2).lostFamily());
		assertTrue(rows.get(2).fieldInitKept());
		assertEquals(List.of("forge", "forge", "neo"),
				MergeabilityCensus.conflicts(report).stream().map(c -> c[2]).toList());
	}

	/** The capturer realignment's row form is read with the family that lost the lambda body, and marked as such. */
	@Test void displacedLambdaRowsAreInventoriedWithTheFamilyThatLostTheBody() throws Exception {
		Path report = temporary.resolve("conflicts.txt");
		Files.writeString(report, "game/A#tick()V (forge hook lost)\n"
				+ "game/B#lambda$init$0(Lgame/Button;)V (" + MergedBaseBuilder.DISPLACED_LAMBDA + "forge hook lost)\n"
				+ "game/C#lambda$static$6(Lgame/C;)Ljava/util/Optional; (" + MergedBaseBuilder.DISPLACED_LAMBDA + "neo hook lost)\n");
		var rows = LostHookAttribution.conflicts(report);
		assertEquals(3, rows.size());
		assertFalse(rows.get(0).displacedLambda());
		assertTrue(rows.get(1).displacedLambda());
		assertEquals("lambda$init$0(Lgame/Button;)V", rows.get(1).method());
		assertEquals("forge", rows.get(1).lostFamily());
		assertFalse(rows.get(1).fieldInitKept());
		assertTrue(rows.get(2).displacedLambda());
		assertEquals("neo", rows.get(2).lostFamily());
		assertEquals(List.of("forge", "forge", "neo"), MergeabilityCensus.conflicts(report).stream().map(c -> c[2]).toList());
	}

	/** A form nobody taught the parser must stop the run, not shrink the denominator. */
	@Test void anUnrecognisedHookLostRowFailsInsteadOfDisappearing() throws Exception {
		Path report = temporary.resolve("conflicts.txt");
		for (String row : List.of("game/A#tick()V (kept forge body for some new reason; neo hook lost)",
				"game/A#tick()V (fabric hook lost)", "game/A (forge hook lost)",
				"game/A#tick()V (kept forge body to preserve base-added field init; forge hook lost)")) {
			Files.writeString(report, "game/Z#ok()V (neo hook lost)\n" + row + "\n");
			var failure = assertThrows(java.io.IOException.class, () -> LostHookAttribution.conflicts(report), row);
			assertTrue(failure.getMessage().contains(row), failure.getMessage());
			assertThrows(java.io.IOException.class, () -> MergeabilityCensus.conflicts(report), row);
		}
	}

	/**
	 * The edit splice appends a section of per-method decisions. It is read whole -- every entry and every run -- and
	 * nothing in it is a hook-lost row, not even an entry whose text happens to end the way a row does.
	 */
	@Test void theEditSpliceSectionIsReadAndNeverCountedAsAHookLostRow() throws Exception {
		Path report = temporary.resolve("conflicts.txt");
		Files.writeString(report, String.join("\n",
				"[merge] edit splice: methods=3 runs carried=3 already-present=1 conflicting=2 refused=1",
				"",
				"=== both-modified methods where one side's hook is LOST (see suffix for which) ===",
				"game/A#tick()V (forge hook lost)",
				"",
				"=== restricted entry-hook merges (declined methods retain the existing arbitration) ===",
				"game/A#tick()V DECLINED both sides must add an entry prefix",
				"",
				LostHookAttribution.SPLICE_SECTION + " both ecosystems' edits kept in one body (per run; lines are the other"
						+ " side's patched source lines) ===",
				"game/A#tick()V NOT SPLICED (no run could be carried over)",
				"    = both-changed L10-11 the base changed the same vanilla lines differently; lost [ForgeHooks.tick], kept [EventHooks.tick]",
				"game/B#run()V SPLICED",
				"    + insert L20-21",
				"    = same-edit L30-30 the base already has this inserted code",
				"game/C#<init>()V SPLICED",
				"    + replace L5-6",
				"    x insert L7-7 run is not a statement in the other body",
				"game/E#use(I)Z SPLICED",
				"    + insert L1143-1126",
				"    = both-changed L40-40 the base changed the same vanilla lines differently; lost [ForgeHooks.use], kept [no calls]",
				"game/D#odd()V NOT SPLICED (vanilla parts do not align (3/9) like a row (forge hook lost)",
				""));
		var parsed = LostHookAttribution.report(report);
		assertEquals(List.of(new LostHookAttribution.Conflict("game/A", "tick()V", "forge")), parsed.conflicts());
		assertEquals(1, MergeabilityCensus.conflicts(report).size());
		var splices = parsed.splices();
		assertEquals(List.of("game/A#tick()V", "game/B#run()V", "game/C#<init>()V", "game/E#use(I)Z", "game/D#odd()V"),
				splices.stream().map(s -> s.owner() + "#" + s.method()).toList());
		assertEquals(List.of(false, true, true, true, false), splices.stream().map(LostHookAttribution.Splice::spliced).toList());
		// Only a method the splice took can be residual, and only through a competing or refused run.
		assertEquals(List.of(false, false, true, true, false), splices.stream().map(LostHookAttribution.Splice::residual).toList());
		assertEquals("no run could be carried over", splices.get(0).reason());
		assertEquals("vanilla parts do not align (3/9) like a row (forge hook lost", splices.get(4).reason());
		var run = splices.get(3).runs().get(0);
		assertEquals(List.of('+', "insert", 1143, 1126), List.of(run.mark(), run.kind(), run.firstLine(), run.lastLine()));
		assertEquals(List.of(1, 2, 2, 2, 0), splices.stream().map(s -> s.runs().size()).toList());
	}

	/** A line of the section nobody taught the parser stops the run, as an unknown hook-lost row does. */
	@Test void anUnrecognisedEditSpliceRowFailsInsteadOfBeingMisfiled() throws Exception {
		Path report = temporary.resolve("conflicts.txt");
		String heading = LostHookAttribution.SPLICE_SECTION + " both ecosystems' edits kept in one body ===\n";
		for (String body : List.of("    + insert L1-2\n", "game/Z#ok()V SPLICED\ngame/A#tick()V MAYBE SPLICED\n",
				"game/Z#ok()V SPLICED\n    ? insert L1-2\n", "game/Z#ok()V SPLICED\n    + insert 1-2\n",
				"game/Z#ok()V SPLICED\ngame/A#tick()V (forge hook lost)\n", "game/Z#ok()V SPLICED\n\tx insert L1-1 tab\n")) {
			Files.writeString(report, "game/Y#ok()V (neo hook lost)\n" + heading + body);
			var failure = assertThrows(java.io.IOException.class, () -> LostHookAttribution.report(report), body);
			assertTrue(failure.getMessage().contains("unrecognised edit-splice row"), failure.getMessage());
			assertThrows(java.io.IOException.class, () -> LostHookAttribution.conflicts(report), body);
			assertThrows(java.io.IOException.class, () -> MergeabilityCensus.conflicts(report), body);
		}
	}

	/**
	 * A method the splice took is no hook-lost row; when the splice reports one of its runs lost, the census says so
	 * instead of calling the caller UNLISTED. A clean splice and a method the splice did not take stay as they were.
	 */
	@Test void aSplicedMethodThatLostARunIsSpliceResidualInTheCensus() {
		String hook = "net/minecraftforge/common/ForgeHooks#tick()V";
		ClassNode source = classNode("game/A", methodNode("residual", "()V", hook), methodNode("clean", "()V", hook),
				methodNode("refused", "()V", hook), methodNode("listed", "()V", hook));
		ClassNode merged = classNode("game/A", methodNode("residual", "()V"), methodNode("clean", "()V", hook),
				methodNode("refused", "()V"), methodNode("listed", "()V"));
		var carried = new LostHookAttribution.SpliceRun('+', "insert", 1, 1, "");
		var splices = List.of(
				new LostHookAttribution.Splice("game/A", "residual()V", true, null,
						List.of(carried, new LostHookAttribution.SpliceRun('x', "insert", 2, 2, "run is not a statement"))),
				new LostHookAttribution.Splice("game/A", "clean()V", true, null,
						List.of(carried, new LostHookAttribution.SpliceRun('=', "same-edit", 3, 3, "already there"))),
				new LostHookAttribution.Splice("game/A", "refused()V", false, "no run could be carried over",
						List.of(new LostHookAttribution.SpliceRun('=', "both-changed", 4, 4, "lost [ForgeHooks.tick]"))),
				new LostHookAttribution.Splice("game/A", "listed()V", false, "no shared vanilla code", List.of()));
		var census = LostHookAttribution.platformCensus(Map.of(source.name, source), Map.of(merged.name, merged),
				List.of(new LostHookAttribution.Conflict("game/A", "listed()V", "forge")), splices);
		assertEquals(4, census.callers());
		assertEquals(1, census.listedConflictCallers());
		assertEquals(1, census.spliceResidualCallers());
		Map<String, String> membership = census.calls().stream().collect(java.util.stream.Collectors.toMap(
				LostHookAttribution.PlatformCall::caller, LostHookAttribution.PlatformCall::membership));
		assertEquals(Map.of("game/A#residual()V", "SPLICE_RESIDUAL", "game/A#clean()V", "UNLISTED",
				"game/A#refused()V", "UNLISTED", "game/A#listed()V", "LISTED"), membership);
		// Membership labels; it never changes what is counted.
		assertEquals(3, LostHookAttribution.rawLostOccurrences(census));
		assertEquals(LostHookAttribution.rawLostOccurrences(census), LostHookAttribution.rawLostOccurrences(
				LostHookAttribution.platformCensus(Map.of(source.name, source), Map.of(merged.name, merged), List.of())));
		assertEquals(0, LostHookAttribution.unobservedOccurrences(census));
	}

	/**
	 * A lambda the splice carried under a fresh name keeps its calls; a merged lambda of the same name holding another
	 * body does not. Counting by caller name alone rewarded a lambda collision (MinecraftForge's body under NeoForge's
	 * name) and charged the correct rename as a loss.
	 */
	@Test void aLambdaCarriedUnderAFreshNameKeepsItsCalls() {
		String hook = "net/minecraftforge/client/gui/ModListScreen#open()V", neoCall = "net/neoforged/neoforge/client/ClientHooks#report()V";
		ClassNode source = classNode("game/Pause", methodNode("lambda$menu$11", "(I)V", hook));
		ClassNode renamed = classNode("game/Pause", methodNode("lambda$menu$11", "(I)V", neoCall),
				methodNode("lambda$menu$11$forbricforge", "(I)V", hook));
		var kept = LostHookAttribution.platformCensus(Map.of(source.name, source), Map.of(renamed.name, renamed), List.of());
		assertEquals(0, LostHookAttribution.rawLostOccurrences(kept), "moved to its own name, not lost");
		// Negative control: without the carried copy, the merged lambda of that name is another body and the call is lost.
		ClassNode collided = classNode("game/Pause", methodNode("lambda$menu$11", "(I)V", neoCall));
		var lost = LostHookAttribution.platformCensus(Map.of(source.name, source), Map.of(collided.name, collided), List.of());
		assertEquals(1, LostHookAttribution.rawLostOccurrences(lost));
		// And the other family's census sees its own lambda's call lost when the name holds MinecraftForge's body.
		ClassNode neoSource = classNode("game/Pause", methodNode("lambda$menu$11", "(I)V", neoCall));
		ClassNode forgeUnderNeoName = classNode("game/Pause", methodNode("lambda$menu$11", "(I)V", hook));
		assertEquals(1, LostHookAttribution.rawLostOccurrences(LostHookAttribution.platformCensus(Map.of(neoSource.name, neoSource),
				Map.of(forgeUnderNeoName.name, forgeUnderNeoName), List.of())));
	}

	/** The hooks a residual splice still lacks are attributed like a row's, by the bodies, to the family that lost them. */
	@Test void aResidualSpliceIsAttributedToTheFamilyWhoseHookTheMergedBodyLacks() throws Exception {
		String forge = "net/minecraftforge/common/ForgeHooks", neo = "net/neoforged/neoforge/event/EventHooks";
		Path f = jar("forge.jar", forge, forge), n = jar("neo.jar", neo, neo);
		Path merged = jar("merged.jar", neo, forge), carrier = temporary.resolve("empty.jar");
		try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(carrier))) { }
		Path report = temporary.resolve("report.txt"), mods = Files.createDirectory(temporary.resolve("mods"));
		Files.writeString(report, "game/B#tick()V (neo hook lost)\n\n" + LostHookAttribution.SPLICE_SECTION + " ... ===\n"
				+ "game/B#tick()V NOT SPLICED (no shared vanilla code)\n"
				+ "game/A#tick()V SPLICED\n    + insert L1-1\n    = both-changed L2-2 the base changed the same vanilla"
				+ " lines differently; lost [ForgeHooks.tick], kept [EventHooks.tick]\n");
		String output = run(new String[] {f.toString(), n.toString(), merged.toString(), carrier.toString(),
				carrier.toString(), report.toString(), mods.toString()});
		assertTrue(output.contains("conflicts=1 judged=1 no-modelled-direct-hook=0 unobserved=0"), output);
		assertTrue(output.contains("game/A#tick()V lost-family=forge kind=SPLICE_RESIDUAL RAW-LOST [" + forge + "#tick()V]"), output);
		assertFalse(output.contains("game/A#tick()V lost-family=neo"), "the base family's own hook is kept: " + output);
		assertTrue(output.contains("spliced-residual=1 judged=1 no-modelled-direct-hook=0 unobserved=0"), output);
		assertTrue(output.contains("caller=game/A#tick()V symbol=" + forge + "#tick()V scope=MODELLED_FACADE"
				+ " conflict=SPLICE_RESIDUAL state=RAW_LOST"), output);
		assertTrue(output.contains("caller=game/B#tick()V symbol=" + neo + "#tick()V scope=MODELLED_FACADE"
				+ " conflict=LISTED state=RAW_LOST"), output);
		assertTrue(output.contains("listed-conflict-callers=1 splice-residual-callers=1 unlisted-callers=0"), output);
	}

	/**
	 * The tracked report, the one beside the merged base in forbric-loader/run: every row the builder wrote as a hook
	 * loss is a row the tools judge, read back in the form it was written. Which methods the report lists, and how many,
	 * is the build's answer and changes whenever the base is rebuilt (the edit splice took the old report's 1 006 rows
	 * to 922, its six field-init rows to two, and LivingEntity's constructor off the list), so nothing here pins them:
	 * each row the text has must come back as that row, the field-init and displaced-lambda forms included.
	 */
	@Test void everyHookLostRowOfTheTrackedReportEntersTheDenominator() throws Exception {
		Path report = Path.of(System.getProperty("user.dir"), "run", "merged-base", "merge-conflicts.txt");
		List<String> lines = Files.readAllLines(report);
		assertTrue(!lines.isEmpty() && lines.get(0).startsWith("[merge] classes:"),
				"the tracked report is the fixture this test reads, as MergedBaseBuilder writes it: " + report);
		List<String> written = lines.stream().map(String::trim).filter(line -> line.endsWith("hook lost)")).toList();
		assertFalse(written.isEmpty(), "the tracked report lists no hook loss: " + report);
		var rows = LostHookAttribution.conflicts(report);
		assertEquals(written.size(), rows.size());
		for (int i = 0; i < written.size(); i++) {
			String line = written.get(i);
			var row = rows.get(i);
			int hash = line.indexOf('#'), space = line.indexOf(' ', hash);
			assertEquals(line.substring(0, hash), row.owner(), line);
			assertEquals(line.substring(hash + 1, space), row.method(), line);
			assertEquals(line.endsWith("forge hook lost)") ? "forge" : line.endsWith("neo hook lost)") ? "neo" : "?",
					row.lostFamily(), line);
			assertEquals(line.contains(" to preserve base-added field init; "), row.fieldInitKept(), line);
			assertEquals(line.contains("(lambda body displaced by its capturer's side; "), row.displacedLambda(), line);
		}
		assertEquals(written.size(), MergeabilityCensus.conflicts(report).size());
	}

	@Test void realReportTraversalAttributesBothSidesAndNamesUnobservedConflicts() throws Exception {
		String forge = "net/minecraftforge/common/ForgeHooks", neo = "net/neoforged/neoforge/event/EventHooks";
		Path f = jar("forge.jar", forge, forge), n = jar("neo.jar", neo, neo);
		Path merged = jar("merged.jar", neo, forge), carrier = temporary.resolve("empty.jar");
		try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(carrier))) { }
		Path report = temporary.resolve("report.txt"), mods = Files.createDirectory(temporary.resolve("mods"));
		Files.writeString(report, "game/A#tick()V (forge hook lost)\ngame/B#tick()V (neo hook lost)\n"
				+ "game/Missing#tick()V (forge hook lost)\n");
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		PrintStream previous = System.out;
		try (PrintStream out = new PrintStream(bytes)) {
			System.setOut(out);
			LostHookAttribution.main(new String[] {f.toString(), n.toString(), merged.toString(), carrier.toString(),
					carrier.toString(), report.toString(), mods.toString()});
		} finally { System.setOut(previous); }
		String output = bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
		assertTrue(output.contains("[attribution] mods name 0 ecosystem class references (candidate filter; not event/subscriber count)"));
		assertFalse(output.contains("ecosystem event class(es)"));
		assertTrue(output.contains("game/A#tick()V lost-family=forge RAW-LOST [" + forge + "#tick()V]"));
		assertTrue(output.contains("game/B#tick()V lost-family=neo RAW-LOST [" + neo + "#tick()V]"));
		assertTrue(output.contains("conflicts=3 judged=2 no-modelled-direct-hook=0 unobserved=1"));
		assertTrue(output.contains("runtime restoration=NOT_ASSESSED"));
		assertTrue(output.contains("[platform-census] denominator side=forge classes=2 methods=2"));
		assertTrue(output.contains("[platform-census] denominator side=neo classes=2 methods=2"));
		assertTrue(output.contains("OUTSIDE_EVENT_MODEL symbols are not classified as event hooks"));
		assertTrue(output.contains("raw absence is not proof of a behavior defect"));
	}

	@Test void outsideFacadesAndBothNamespacesAreInventoriedWithoutClaimingEventCoverage() {
		String forge = "net/minecraftforge/registries/RegistryManager#tick()V";
		String neo = "net/neoforged/fml/ModLoader#tick()V";
		ClassNode source = classNode("game/A", methodNode("tick", "()V", forge, neo), methodNode("plain", "()V"));
		var census = LostHookAttribution.platformCensus(Map.of(source.name, source), Map.of(source.name, source), List.of());
		assertEquals(1, census.classes());
		assertEquals(2, census.methods());
		assertEquals(1, census.callers());
		assertEquals(2, census.calls().size());
		assertEquals(Set.of(forge, neo), census.calls().stream().map(LostHookAttribution.PlatformCall::symbol)
				.collect(java.util.stream.Collectors.toSet()));
		assertTrue(census.calls().stream().noneMatch(LostHookAttribution.PlatformCall::modelledFacade));
		assertTrue(census.calls().stream().allMatch(c -> c.state() == LostHookAttribution.RawCallState.RAW_RETAINED));
	}

	@Test void callersOutsideConflictListHaveTheSameRetentionAndLossCoverage() {
		String hook = "net/minecraftforge/common/ForgeHooks#tick()V";
		ClassNode source = classNode("game/A", methodNode("listed", "()V", hook), methodNode("unlisted", "()V", hook));
		ClassNode merged = classNode("game/A", methodNode("listed", "()V", hook), methodNode("unlisted", "()V"));
		var census = LostHookAttribution.platformCensus(Map.of(source.name, source), Map.of(merged.name, merged),
				List.of(new LostHookAttribution.Conflict("game/A", "listed()V", "forge")));
		assertEquals(2, census.callers());
		assertEquals(1, census.listedConflictCallers());
		assertEquals(2, census.calls().size());
		var listed = census.calls().get(0);
		var unlisted = census.calls().get(1);
		assertTrue(listed.listedConflict());
		assertEquals(LostHookAttribution.RawCallState.RAW_RETAINED, listed.state());
		assertFalse(unlisted.listedConflict());
		assertTrue(unlisted.modelledFacade());
		assertEquals("game/A#unlisted()V", unlisted.caller());
		assertEquals(LostHookAttribution.RawCallState.RAW_LOST, unlisted.state());
		assertEquals(1, unlisted.lostOccurrences());
	}

	@Test void ownerDescriptorAndRepeatedOccurrencesCannotMaskOtherRawLosses() {
		String hook = "net/minecraftforge/common/ForgeHooks#tick()V";
		String overload = "net/minecraftforge/common/ForgeHooks#tick(I)V";
		String otherOwner = "net/neoforged/neoforge/event/EventHooks#tick()V";
		ClassNode source = classNode("game/A", methodNode("tick", "()V", hook, hook, overload, otherOwner));
		ClassNode merged = classNode("game/A", methodNode("tick", "()V", hook));
		var census = LostHookAttribution.platformCensus(Map.of(source.name, source), Map.of(merged.name, merged), List.of());
		assertEquals(3, census.calls().size());
		var rows = census.calls().stream().collect(java.util.stream.Collectors.toMap(
				LostHookAttribution.PlatformCall::symbol, c -> c));
		assertEquals(LostHookAttribution.RawCallState.RAW_PARTIAL_LOSS, rows.get(hook).state());
		assertEquals(2, rows.get(hook).originalOccurrences());
		assertEquals(1, rows.get(hook).retainedOccurrences());
		assertEquals(1, rows.get(hook).lostOccurrences());
		assertEquals(LostHookAttribution.RawCallState.RAW_LOST, rows.get(overload).state());
		assertEquals(LostHookAttribution.RawCallState.RAW_LOST, rows.get(otherOwner).state());
	}

	@Test void missingMergedClassesAndOverloadsAreUnobservedOnBothSides() {
		for (String platform : List.of("net/minecraftforge/common/ForgeHooks", "net/neoforged/neoforge/event/EventHooks")) {
			String hook = platform + "#tick()V";
			ClassNode missingClass = classNode("game/Missing", methodNode("tick", "()V", hook));
			ClassNode missingMethod = classNode("game/Present", methodNode("tick", "(I)V", hook), methodNode("plain", "()V"));
			ClassNode merged = classNode("game/Present", methodNode("tick", "()V", hook));
			var census = LostHookAttribution.platformCensus(Map.of(missingClass.name, missingClass, missingMethod.name, missingMethod),
					Map.of(merged.name, merged), List.of());
			assertEquals(3, census.methods());
			assertEquals(3, census.missingMergedMethods());
			assertEquals(2, census.missingMergedCallers());
			assertEquals(2, census.calls().size());
			for (var row : census.calls()) {
				assertEquals(LostHookAttribution.RawCallState.MERGED_CALLER_MISSING, row.state());
				assertEquals(-1, row.mergedOccurrences());
				assertEquals(0, row.retainedOccurrences());
				assertEquals(0, row.lostOccurrences());
			}
		}
	}

	/**
	 * Every raw loss in the census gets an effective state, not only the conflict rows: an UNLISTED caller whose
	 * final definition restored the call is DIRECT_RESTORED, and a listed one that stayed lost is residual.
	 */
	@Test void everyCensusRawLossIsJoinedToTheFinalDefinitions() throws Exception {
		String hook = "net/minecraftforge/common/ForgeHooks#tick()V";
		String outside = "net/minecraftforge/registries/RegistryManager#tick()V";
		ClassNode source = classNode("game/A", methodNode("listed", "()V", hook), methodNode("unlisted", "()V", hook, outside));
		ClassNode merged = classNode("game/A", methodNode("listed", "()V"), methodNode("unlisted", "()V"));
		var census = LostHookAttribution.platformCensus(Map.of(source.name, source), Map.of(merged.name, merged),
				List.of(new LostHookAttribution.Conflict("game/A", "listed()V", "forge")));
		Path evidence = evidence(classNode("game/A", methodNode("listed", "()V"), methodNode("unlisted", "()V", hook)));
		var states = LostHookAttribution.effectiveStates(census, new EffectiveHookEvidence(evidence));
		assertEquals(3, states.size(), "every raw loss, listed or not, modelled facade or not: " + states);
		var bySymbol = new java.util.HashMap<String, EffectiveHookEvidence.State>();
		states.forEach((call, state) -> bySymbol.put(call.caller() + " " + call.symbol(), state));
		assertEquals(EffectiveHookEvidence.State.OBSERVED_WITHOUT_HOOK, bySymbol.get("game/A#listed()V " + hook));
		assertEquals(EffectiveHookEvidence.State.DIRECT_RESTORED, bySymbol.get("game/A#unlisted()V " + hook));
		assertEquals(EffectiveHookEvidence.State.OBSERVED_WITHOUT_HOOK, bySymbol.get("game/A#unlisted()V " + outside));
	}

	@Test void theCensusPrintsEffectiveTotalsPerSideOnlyFromSuppliedEvidence() throws Exception {
		String forge = "net/minecraftforge/common/ForgeHooks", neo = "net/neoforged/neoforge/event/EventHooks";
		Path f = jar("forge.jar", forge, forge), n = jar("neo.jar", neo, neo);
		Path merged = jar("merged.jar", neo, forge), carrier = temporary.resolve("empty.jar");
		try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(carrier))) { }
		Path report = temporary.resolve("report.txt"), mods = Files.createDirectory(temporary.resolve("mods"));
		Files.writeString(report, "game/A#tick()V (forge hook lost)\n");
		String[] args = {f.toString(), n.toString(), merged.toString(), carrier.toString(), carrier.toString(),
				report.toString(), mods.toString()};
		String without = run(args);
		assertTrue(without.contains("[platform-census] effective side=forge raw-loss-pairs=1 NOT_ASSESSED"), without);
		assertTrue(without.contains("state=RAW_LOST") && without.contains("effective=NOT_ASSESSED"), without);
		Path evidence = evidence(classNode("game/A", methodNode("tick", "()V", forge + "#tick()V")),
				classNode("game/B", methodNode("tick", "()V")));
		String[] withEvidence = java.util.Arrays.copyOf(args, 8);
		withEvidence[7] = evidence.toString();
		String with = run(withEvidence);
		assertTrue(with.contains("[platform-census] effective side=forge raw-loss-pairs=1 DIRECT_RESTORED=1"
				+ " VIA_DEFINED_HELPER=0 VIA_KERNEL_BRIDGE=0 OBSERVED_WITHOUT_HOOK=0 UNOBSERVED=0"), with);
		assertTrue(with.contains("[platform-census] effective side=neo raw-loss-pairs=1 DIRECT_RESTORED=0"
				+ " VIA_DEFINED_HELPER=0 VIA_KERNEL_BRIDGE=0 OBSERVED_WITHOUT_HOOK=1 UNOBSERVED=0"), with);
	}

	private static String run(String[] args) throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		PrintStream previous = System.out;
		try (PrintStream out = new PrintStream(bytes)) {
			System.setOut(out);
			LostHookAttribution.main(args);
		} finally { System.setOut(previous); }
		return bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
	}

	/** A defined-class evidence session in the kernel's content-addressed layout. */
	private Path evidence(ClassNode... defined) throws Exception {
		Path root = Files.createTempDirectory(temporary, "definitions-");
		StringBuilder manifest = new StringBuilder(EffectiveHookEvidence.HEADER + "\n");
		for (ClassNode node : defined) {
			node.version = Opcodes.V17;
			node.superName = "java/lang/Object";
			for (MethodNode method : node.methods) {
				if (method.instructions.getLast() == null || method.instructions.getLast().getOpcode() != Opcodes.RETURN)
					method.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
				method.maxStack = 1;
			}
			ClassWriter writer = new ClassWriter(0);
			node.accept(writer);
			byte[] bytes = writer.toByteArray();
			String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
			Files.createDirectories(root.resolve("blobs"));
			Files.write(root.resolve("blobs").resolve(hash + ".class"), bytes);
			manifest.append(node.name).append('\t').append(hash).append('\n');
		}
		Files.writeString(root.resolve("definitions.tsv"), manifest);
		Files.createFile(root.resolve(EffectiveHookEvidence.INTACT));
		return root;
	}

	@Test void opcodeAndInterfaceOwnerChangesAreNotReportedAsPreservedInvocations() {
		String owner = "net/minecraftforge/common/ForgeHooks", symbol = owner + "#tick()V";
		for (var changed : List.of(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "tick", "()V", false),
				new MethodInsnNode(Opcodes.INVOKESTATIC, owner, "tick", "()V", true))) {
			ClassNode source = classNode("game/A", methodNode("tick", "()V", symbol));
			MethodNode changedMethod = methodNode("tick", "()V");
			changedMethod.instructions.add(changed);
			ClassNode merged = classNode("game/A", changedMethod);
			var row = LostHookAttribution.platformCensus(Map.of(source.name, source), Map.of(merged.name, merged),
					List.of()).calls().get(0);
			assertEquals(LostHookAttribution.RawCallState.RAW_INVOCATION_CHANGED, row.state());
			assertEquals(1, row.mergedOccurrences());
			assertEquals(0, row.retainedOccurrences());
			assertEquals(1, row.lostOccurrences());
			assertEquals(Map.of("INVOKESTATIC/itf=false", 1), row.originalForms());
			assertNotEquals(row.originalForms(), row.mergedForms());
		}
	}

	private static ClassNode classNode(String owner, MethodNode... methods) {
		ClassNode node = new ClassNode();
		node.name = owner;
		node.methods.addAll(List.of(methods));
		return node;
	}

	private static MethodNode methodNode(String name, String descriptor, String... calls) {
		MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, descriptor, null, null);
		for (String call : calls) {
			int hash = call.indexOf('#'), arguments = call.indexOf('(', hash);
			method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, call.substring(0, hash),
					call.substring(hash + 1, arguments), call.substring(arguments), false));
		}
		return method;
	}

	private Path jar(String name, String first, String second) throws Exception {
		Path file = temporary.resolve(name);
		try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file))) {
			String[] hooks = {first, second};
			for (int i = 0; i < 2; i++) {
				String owner = "game/" + (i == 0 ? "A" : "B");
				ClassWriter writer = new ClassWriter(0);
				writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
				var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "tick", "()V", null, null);
				method.visitCode();
				method.visitMethodInsn(Opcodes.INVOKESTATIC, hooks[i], "tick", "()V", false);
				method.visitInsn(Opcodes.RETURN);
				method.visitMaxs(0, 0);
				method.visitEnd();
				writer.visitEnd();
				out.putNextEntry(new JarEntry(owner + ".class"));
				out.write(writer.toByteArray());
				out.closeEntry();
			}
		}
		return file;
	}
}
