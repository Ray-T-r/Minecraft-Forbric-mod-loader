/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;

class MergedBaseAdditiveIntegrationTest {
	@TempDir Path directory;
	private static final String OWNER = "net/minecraft/EntryHookProbe";
	/** The probe restorations these fixtures are allowed to make; the tool's own list names only reviewed pairs. */
	private static final Set<String> PROBES_REVIEWED = Set.of(
			"<entry> -> " + AdditiveMethodMergerTest.FORGE + ".observe(I)V",
			AdditiveMethodMergerTest.NEO + ".filter(Ljava/util/Optional;)Ljava/util/Optional; -> "
					+ AdditiveMethodMergerTest.FORGE + ".filter(Ljava/util/Optional;)Ljava/util/Optional;");

	@Test
	void builderKeepsBothProvenPrefixesAndReportsTheDecision() throws Exception {
		MethodNode vanilla = AdditiveMethodMergerTest.arithmetic();
		Build result = build(vanilla, AdditiveMethodMergerTest.prefix(vanilla, AdditiveMethodMergerTest.FORGE),
				AdditiveMethodMergerTest.prefix(vanilla, AdditiveMethodMergerTest.NEO));
		assertEquals(List.of(AdditiveMethodMergerTest.NEO, AdditiveMethodMergerTest.FORGE), calls(result.method));
		assertTrue(result.report.contains("restricted entry-hook merges: accepted=1 declined=0"));
		assertTrue(result.report.contains(OWNER + "#compute(I)I ACCEPTED"));
		assertFalse(result.report.contains("(forge hook lost)"));
	}

	@Test
	void emittedJarVerifiesWithThePreservedStackMapFramesAndExceptionTable() throws Exception {
		MethodNode vanilla = AdditiveMethodMergerTest.divisionWithHandler();
		Build result = build(vanilla, AdditiveMethodMergerTest.prefix(vanilla, AdditiveMethodMergerTest.FORGE),
				AdditiveMethodMergerTest.prefix(vanilla, AdditiveMethodMergerTest.NEO));
		assertTrue(result.report.contains("restricted entry-hook merges: accepted=1 declined=0"));
		assertEquals(5, AdditiveMethodMergerTest.invokeClass(OWNER, result.bytes, 2));
		assertEquals(-1, AdditiveMethodMergerTest.invokeClass(OWNER, result.bytes, 0));
	}

	@Test
	void unsupportedChangeKeepsThePreviousNeoBodyAndOriginalConflictFormat() throws Exception {
		MethodNode vanilla = AdditiveMethodMergerTest.arithmetic();
		MethodNode forge = AdditiveMethodMergerTest.prefix(vanilla, AdditiveMethodMergerTest.FORGE);
		for (AbstractInsnNode instruction : forge.instructions) {
			if (instruction instanceof IntInsnNode value) value.operand = 8;
		}
		Build result = build(vanilla, forge, AdditiveMethodMergerTest.prefix(vanilla, AdditiveMethodMergerTest.NEO));
		assertEquals(List.of(AdditiveMethodMergerTest.NEO), calls(result.method));
		assertTrue(result.report.contains(OWNER + "#compute(I)I (forge hook lost)"));
		assertTrue(result.report.contains("DECLINED other changes vanilla operands, control flow or handlers"));
		assertTrue(result.report.contains("restricted entry-hook merges: accepted=0 declined=1"));
	}

	@Test
	void theToolsOwnReviewedListHoldsBackAProbeRestorationAndKeepsTheLossReported() throws Exception {
		MethodNode vanilla = AdditiveMethodMergerTest.arithmetic();
		Build result = build(new MergedBaseBuilder(), vanilla, AdditiveMethodMergerTest.prefix(vanilla, AdditiveMethodMergerTest.FORGE),
				AdditiveMethodMergerTest.prefix(vanilla, AdditiveMethodMergerTest.NEO), true);
		assertEquals(List.of(AdditiveMethodMergerTest.NEO), calls(result.method));
		assertTrue(result.report.contains("DECLINED entry hooks compose, but no runtime stand-down is reviewed: restoring "
				+ AdditiveMethodMergerTest.FORGE + ".observe(I)V"), result.report);
		assertTrue(result.report.contains(OWNER + "#compute(I)I (forge hook lost)"));
		assertTrue(result.report.contains("[merge]   declined 1: entry hooks compose, but no runtime stand-down is reviewed"));
	}

	@Test
	void aHookThatDoesNotLinkInTheRuntimeJarsIsNotRestored() throws Exception {
		MethodNode vanilla = AdditiveMethodMergerTest.arithmetic();
		Build result = build(new MergedBaseBuilder(PROBES_REVIEWED), vanilla,
				AdditiveMethodMergerTest.prefix(vanilla, AdditiveMethodMergerTest.FORGE),
				AdditiveMethodMergerTest.prefix(vanilla, AdditiveMethodMergerTest.NEO), false);
		assertEquals(List.of(AdditiveMethodMergerTest.NEO), calls(result.method));
		assertTrue(result.report.contains("DECLINED entry hook does not resolve: "), result.report);
		assertTrue(result.report.contains(OWNER + "#compute(I)I (forge hook lost)"));
	}

	@Test
	void pairedResultHooksComposeThroughTheBuilderAndRunOnTheBasesOwnFrames() throws Exception {
		MethodNode vanilla = AdditiveMethodMergerTest.choosing();
		Build result = build(new MergedBaseBuilder(PROBES_REVIEWED), vanilla,
				AdditiveMethodMergerTest.filtered(vanilla, AdditiveMethodMergerTest.FORGE),
				AdditiveMethodMergerTest.filtered(vanilla, AdditiveMethodMergerTest.NEO), true);
		assertEquals(List.of(AdditiveMethodMergerTest.NEO, "java/util/Optional", AdditiveMethodMergerTest.FORGE,
				"java/util/Optional"), calls(result.method));
		assertTrue(result.report.contains(OWNER + "#choose(Ljava/util/Optional;)I ACCEPTED paired hooks"), result.report);
		assertFalse(result.report.contains("(forge hook lost)"));
		// The builder writes with COMPUTE_MAXS only: the added guard jumps to a label whose frame the base already
		// has, so defining and running the class is the proof those frames still hold.
		assertEquals(1, AdditiveMethodMergerTest.chooseClass(OWNER, result.bytes, Optional.of("portal")));
		assertEquals(0, AdditiveMethodMergerTest.chooseClass(OWNER, result.bytes, Optional.empty()));
	}

	private Build build(MethodNode vanilla, MethodNode forge, MethodNode neo) throws Exception {
		return build(new MergedBaseBuilder(PROBES_REVIEWED), vanilla, forge, neo, true);
	}

	private Build build(MergedBaseBuilder builder, MethodNode vanilla, MethodNode forge, MethodNode neo,
			boolean runtimes) throws Exception {
		Path v = jar("vanilla.jar", OWNER, AdditiveMethodMergerTest.classBytes(OWNER, vanilla));
		Path f = jar("forge.jar", OWNER, AdditiveMethodMergerTest.classBytes(OWNER, forge));
		Path n = jar("neo.jar", OWNER, AdditiveMethodMergerTest.classBytes(OWNER, neo));
		Path forgeRuntime = runtimes ? jar("forge-runtime.jar", AdditiveMethodMergerTest.FORGE,
				AdditiveMethodMergerTest.hookClass(AdditiveMethodMergerTest.FORGE)) : null;
		Path neoRuntime = runtimes ? jar("neo-runtime.jar", AdditiveMethodMergerTest.NEO,
				AdditiveMethodMergerTest.hookClass(AdditiveMethodMergerTest.NEO)) : null;
		Path merged = directory.resolve("merged.jar"), report = directory.resolve("report.txt");
		builder.run(v, f, n, merged, report, forgeRuntime, neoRuntime);
		try (ZipFile zip = new ZipFile(merged.toFile())) {
			ClassNode output = new ClassNode();
			byte[] bytes = zip.getInputStream(zip.getEntry(OWNER + ".class")).readAllBytes();
			new ClassReader(bytes).accept(output, 0);
			return new Build(output.methods.get(0), Files.readString(report), bytes);
		}
	}

	private Path jar(String name, String owner, byte[] bytes) throws Exception {
		Path path = directory.resolve(name);
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
			zip.putNextEntry(new ZipEntry(owner + ".class"));
			zip.write(bytes);
			zip.closeEntry();
		}
		return path;
	}

	private static List<String> calls(MethodNode method) {
		List<String> out = new ArrayList<>();
		for (AbstractInsnNode instruction : method.instructions) {
			if (instruction instanceof MethodInsnNode call) out.add(call.owner);
		}
		return out;
	}

	private record Build(MethodNode method, String report, byte[] bytes) { }
}
