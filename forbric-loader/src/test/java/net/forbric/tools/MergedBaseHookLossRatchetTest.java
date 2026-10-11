/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.tools;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.json.JsonFormat;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Holds the merge to what the edit splice achieved, on the staged base it built.
 *
 * <p>The platform census ({@link LostHookAttribution#platformCensus}) counts every occurrence of a platform call a
 * patched game makes that the merged base no longer makes in the same caller. Keeping both ecosystems' edits in the
 * methods both patched brought that from 794 to 409 on the MinecraftForge side and from 16 to 15 on the NeoForge side.
 * The splice then stopped carrying runs that did the wrong thing in the base: an edit acting on a member both patches
 * added while the base's edit acts on it too (a second implementation), a copy of code whose other copies stayed out,
 * a read of a field nothing writes, a hook result the base overwrites before reading, a run the base reaches from
 * code that skipped it; and every lambda a spliced method captures went back to the base's side. That raised the
 * MinecraftForge count to 437 (the NeoForge count stayed 15), each added loss one of those changes. Counting a
 * lambda the splice carried under a fresh name as keeping its calls took 3 of them away (434). The splice then
 * stopped carrying runs whose answer their own body reads where the base no longer reads it (a hook result the base
 * overwrites or never consumes, a reader that was itself a conflict, a replacement of lines the base's own code
 * feeds), runs both bodies use to replace the vanilla code they dropped, and a run whose call reads a field nothing
 * that runs writes: 76 more calls in 34 callers, each one a hook that used to fire and have its answer dropped,
 * now reported instead, and one fewer where a run is now placed correctly (509). Leaving a lambda both sides compile
 * as one vanilla lambda on the side that edited it, instead of forcing its capturer's side back onto it, gave
 * MultiPlayerGameMode's finishing break its isLeftClickBlockCancelled back (508). A merge-tool change may lower these
 * numbers and never raise them; when one lowers them, lower the ceilings with it.
 * A caller the merged base no longer has is unobserved, not lost, so the census must observe every caller too, or a
 * merge that dropped a whole method would move its calls out of the count.
 *
 * <p>The report's hook-lost rows are checked against the same census: each must name a method all three games have
 * and a loss the merged base still has, and none may name a method the edit splice took. The report is only that
 * evidence when the same build wrote it with the jar, so the staged base has to carry the record build-merged-base.sh
 * writes beside it (evidence.py merge-provenance), which names both files by hash. Without the staged jars or that
 * record this skips like the other staged tests; FORBRIC_COMPAT_FIXTURES_REQUIRED=1 makes the absence a failure.
 */
class MergedBaseHookLossRatchetTest {
	static final long FORGE_RAW_LOST_CEILING = 508;
	static final long NEO_RAW_LOST_CEILING = 15;

	private record Staged(Path root, String build, Map<String, ClassNode> forge, Map<String, ClassNode> neo,
			Map<String, ClassNode> merged, Path reportFile, LostHookAttribution.Report report) {
		LostHookAttribution.PlatformCensus census(Map<String, ClassNode> side) {
			return LostHookAttribution.platformCensus(side, merged, report.conflicts(), report.splices());
		}
	}

	private static Staged staged;

	@Test
	void neitherSideLosesMorePlatformCallsThanTheEditSpliceLeft() throws Exception {
		Staged s = staged();
		var forge = s.census(s.forge());
		var neo = s.census(s.neo());
		assertEquals(0, LostHookAttribution.unobservedOccurrences(forge),
				"MinecraftForge platform calls in callers the merged base does not have, on " + s.build());
		assertEquals(0, LostHookAttribution.unobservedOccurrences(neo),
				"NeoForge platform calls in callers the merged base does not have, on " + s.build());
		long forgeLost = LostHookAttribution.rawLostOccurrences(forge), neoLost = LostHookAttribution.rawLostOccurrences(neo);
		assertTrue(forgeLost <= FORGE_RAW_LOST_CEILING, "the merge now loses " + forgeLost + " MinecraftForge platform"
				+ " calls, more than the " + FORGE_RAW_LOST_CEILING + " the edit splice left, on " + s.build());
		assertTrue(neoLost <= NEO_RAW_LOST_CEILING, "the merge now loses " + neoLost + " NeoForge platform calls, more"
				+ " than the " + NEO_RAW_LOST_CEILING + " the edit splice left, on " + s.build());
	}

	@Test
	void everyHookLostRowNamesALossTheMergedBaseStillHas() throws Exception {
		Staged s = staged();
		List<String> lines = Files.readAllLines(s.reportFile(), StandardCharsets.UTF_8);
		int section = lines.indexOf(lines.stream().filter(l -> l.startsWith(LostHookAttribution.SPLICE_SECTION))
				.findFirst().orElse(null));
		long written = (section < 0 ? lines : lines.subList(0, section)).stream()
				.filter(line -> line.trim().endsWith("hook lost)")).count();
		List<LostHookAttribution.Conflict> rows = s.report().conflicts();
		assertTrue(written > 0, "the staged report has hook-lost rows to check");
		assertEquals(written, rows.size(), "every hook-lost row the merge wrote is a row the census reads");

		Map<String, long[]> forge = byCaller(s.census(s.forge())), neo = byCaller(s.census(s.neo()));
		Set<String> taken = s.report().splices().stream().filter(LostHookAttribution.Splice::spliced)
				.map(splice -> splice.owner() + "#" + splice.method()).collect(Collectors.toSet());
		Set<String> judged = s.report().splices().stream().map(splice -> splice.owner() + "#" + splice.method())
				.collect(Collectors.toSet());
		int named = 0, displaced = 0;
		for (LostHookAttribution.Conflict row : rows) {
			String caller = row.owner() + "#" + row.method();
			for (var side : List.of(s.forge(), s.neo(), s.merged())) {
				ClassNode owner = side.get(row.owner());
				assertTrue(owner != null && owner.methods.stream().anyMatch(m -> (m.name + m.desc).equals(row.method())),
						caller + " is a hook-lost row for a method one of the three games does not have");
			}
			// A row whose losing body calls the platform names a call the merged body lacks; a row naming none of
			// them would be one the merge has repaired and the report still lists.
			long[] calls = (row.lostFamily().equals("forge") ? forge : neo).get(caller);
			if (calls != null && calls[0] > 0) {
				assertTrue(calls[1] > 0, caller + " is listed as losing its " + row.lostFamily() + " hook, yet the merged"
						+ " body makes every platform call that side's body makes");
				named++;
			}
			if (row.displacedLambda()) {
				// The capturer realignment's row: a lambda body no arbitration or splice judged, displaced from the merged
				// class by the other side's body under its name.
				ClassNode losing = (row.lostFamily().equals("forge") ? s.forge() : s.neo()).get(row.owner());
				MethodNode lost = losing.methods.stream().filter(m -> (m.name + m.desc).equals(row.method())).findFirst().orElseThrow();
				MethodNode kept = s.merged().get(row.owner()).methods.stream().filter(m -> (m.name + m.desc).equals(row.method()))
						.findFirst().orElseThrow();
				assertNotEquals(instructions(lost), instructions(kept), caller + " is listed as a displaced lambda body, yet the"
						+ " merged class still holds that side's body");
				displaced++;
			} else if (!judged.isEmpty()) {
				assertTrue(judged.contains(caller), caller + " is a hook-lost row the edit splice never judged");
				assertFalse(taken.contains(caller), caller + " is a hook-lost row for a method the edit splice took");
			}
		}
		assertTrue(named > 0, "no hook-lost row names a platform call; the census cannot have read these bodies");
		System.out.println("[ratchet] hook-lost rows: " + rows.size() + ", of them displaced lambda bodies: " + displaced);
	}

	/** Opcodes and operands, one entry per instruction: what two bodies of one name differ in. */
	private static List<String> instructions(MethodNode m) {
		List<String> out = new java.util.ArrayList<>();
		for (org.objectweb.asm.tree.AbstractInsnNode insn : m.instructions) {
			if (insn.getOpcode() < 0) continue;
			String operand = insn instanceof org.objectweb.asm.tree.MethodInsnNode c ? c.owner + "." + c.name + c.desc
					: insn instanceof org.objectweb.asm.tree.FieldInsnNode f ? f.owner + "." + f.name + f.desc
					: insn instanceof org.objectweb.asm.tree.TypeInsnNode t ? t.desc
					: insn instanceof org.objectweb.asm.tree.LdcInsnNode l ? String.valueOf(l.cst)
					: insn instanceof org.objectweb.asm.tree.IntInsnNode i ? String.valueOf(i.operand)
					: insn instanceof org.objectweb.asm.tree.VarInsnNode v ? String.valueOf(v.var)
					: insn instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode d ? d.name + d.desc + java.util.Arrays.toString(d.bsmArgs)
					: "";
			out.add(insn.getOpcode() + " " + operand);
		}
		return out;
	}

	private static final Pattern SUMMARY = Pattern.compile(
			"^\\[merge] edit splice: methods=(\\d+) runs carried=(\\d+) already-present=(\\d+) conflicting=(\\d+) refused=(\\d+)$");
	private static final Pattern NOT_TAKEN = Pattern.compile("^\\[merge] {3}splice not taken (\\d+): (.+)$");

	/** The section as the census reads it adds up to the counters the merge printed while writing it. */
	@Test
	void theEditSpliceSectionReadsBackToTheCountsTheMergeReported() throws Exception {
		Staged s = staged();
		List<String> lines = Files.readAllLines(s.reportFile(), StandardCharsets.UTF_8);
		Matcher summary = lines.stream().map(SUMMARY::matcher).filter(Matcher::matches).findFirst().orElse(null);
		List<LostHookAttribution.Splice> splices = s.report().splices();
		if (summary == null) {
			// Off (no userdev patches, or -Dforbric.editSplice=off), or a report from before the splice existed.
			assertTrue(splices.isEmpty(), "splice decisions without the counters the merge prints with them");
			return;
		}
		List<LostHookAttribution.SpliceRun> runs = splices.stream().filter(LostHookAttribution.Splice::spliced)
				.flatMap(splice -> splice.runs().stream()).toList();
		List<LostHookAttribution.SpliceRun> all = splices.stream().flatMap(splice -> splice.runs().stream()).toList();
		assertEquals(Long.parseLong(summary.group(1)), splices.stream().filter(LostHookAttribution.Splice::spliced).count(), "methods");
		assertEquals(Long.parseLong(summary.group(2)), runs.stream().filter(r -> r.mark() == '+').count(), "runs carried");
		assertEquals(0, all.stream().filter(r -> r.mark() == '+').count() - runs.stream().filter(r -> r.mark() == '+').count(),
				"a run is reported carried only into a method the splice took");
		assertEquals(Long.parseLong(summary.group(3)), all.stream().filter(r -> r.mark() == '=' && r.kind().equals("same-edit")).count(),
				"already-present");
		assertEquals(Long.parseLong(summary.group(4)), all.stream().filter(r -> r.mark() == '=' && !r.kind().equals("same-edit")).count(),
				"conflicting");
		assertEquals(Long.parseLong(summary.group(5)), all.stream().filter(r -> r.mark() == 'x').count(), "refused");
		Map<String, Long> notTaken = new TreeMap<>();
		lines.stream().map(NOT_TAKEN::matcher).filter(Matcher::matches)
				.forEach(m -> notTaken.put(m.group(2), Long.parseLong(m.group(1))));
		Map<String, Long> read = splices.stream().filter(splice -> !splice.spliced())
				.collect(Collectors.groupingBy(splice -> category(splice.reason()), TreeMap::new, Collectors.counting()));
		assertEquals(notTaken, read, "the methods the splice did not take, by reason");
	}

	/** The reason up to its first detail, as MergedBaseBuilder files a refusal under "splice not taken". */
	private static String category(String reason) {
		int colon = reason.indexOf(':'), paren = reason.indexOf(" (");
		int cut = colon < 0 ? paren : paren < 0 ? colon : Math.min(colon, paren);
		return cut < 0 ? reason : reason.substring(0, cut);
	}

	/** caller -> {platform-call occurrences of that side's body, of which the merged body lacks}. */
	private static Map<String, long[]> byCaller(LostHookAttribution.PlatformCensus census) {
		Map<String, long[]> out = new HashMap<>();
		for (var call : census.calls()) {
			long[] counts = out.computeIfAbsent(call.caller(), ignored -> new long[2]);
			counts[0] += call.originalOccurrences();
			counts[1] += call.lostOccurrences();
		}
		return out;
	}

	private static synchronized Staged staged() throws Exception {
		if (staged != null) return staged;
		Path forgeJar = AdditiveMethodMergerStagedTest.jar("forge"), neoJar = AdditiveMethodMergerStagedTest.jar("neo");
		Path base = AdditiveMethodMergerStagedTest.stagedRun().resolve("merged-base");
		Path mergedJar = base.resolve("patched-mc-merged-26.2.jar"), report = base.resolve("merge-conflicts.txt");
		Path record = base.resolve("patched-mc-merged-26.2.jar.provenance.json");
		boolean present = Files.isRegularFile(mergedJar) && Files.isRegularFile(report) && Files.isRegularFile(record);
		String absent = "staged merged base, its report and the build record pairing them absent under " + base.toAbsolutePath();
		if ("1".equals(System.getenv("FORBRIC_COMPAT_FIXTURES_REQUIRED"))) assertTrue(present, absent);
		assumeTrue(present, absent);
		Config provenance = JsonFormat.fancyInstance().createParser().parse(new StringReader(Files.readString(record)));
		assertEquals(provenance.get(List.of("outputs", "merged", "sha256")), sha256(mergedJar),
				record + " does not describe the staged merged jar; the staged base mixes builds");
		assertEquals(provenance.get(List.of("outputs", "report", "sha256")), sha256(report),
				record + " does not describe the staged report; the staged base mixes builds");
		String build = base.toAbsolutePath() + " (tools " + provenance.get(List.of("source", "commit"))
				+ (Boolean.TRUE.equals(provenance.get(List.of("source", "dirty"))) ? ", uncommitted" : "") + ")";
		staged = new Staged(base, build, LostHookAttribution.load(forgeJar.toString(), "forge-patched"),
				LostHookAttribution.load(neoJar.toString(), "neo-patched"),
				LostHookAttribution.load(mergedJar.toString(), "merged"), report, LostHookAttribution.report(report));
		return staged;
	}

	private static String sha256(Path file) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		try (var in = Files.newInputStream(file)) {
			byte[] buffer = new byte[1 << 16];
			for (int n; (n = in.read(buffer)) > 0; ) digest.update(buffer, 0, n);
		}
		return HexFormat.of().formatHex(digest.digest());
	}
}
