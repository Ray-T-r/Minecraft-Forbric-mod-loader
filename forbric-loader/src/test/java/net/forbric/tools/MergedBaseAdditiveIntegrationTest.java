/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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

	private Build build(MethodNode vanilla, MethodNode forge, MethodNode neo) throws Exception {
		Path v = jar("vanilla.jar", vanilla), f = jar("forge.jar", forge), n = jar("neo.jar", neo);
		Path merged = directory.resolve("merged.jar"), report = directory.resolve("report.txt");
		new MergedBaseBuilder().run(v, f, n, merged, report, null, null);
		try (ZipFile zip = new ZipFile(merged.toFile())) {
			ClassNode output = new ClassNode();
			byte[] bytes = zip.getInputStream(zip.getEntry(OWNER + ".class")).readAllBytes();
			new ClassReader(bytes).accept(output, 0);
			return new Build(output.methods.get(0), Files.readString(report), bytes);
		}
	}

	private Path jar(String name, MethodNode method) throws Exception {
		Path path = directory.resolve(name);
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
			zip.putNextEntry(new ZipEntry(OWNER + ".class"));
			zip.write(AdditiveMethodMergerTest.classBytes(OWNER, method));
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
