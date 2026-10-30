/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * Every constructor of the merged ServerStatus sets forgeData, and {@link MergedRecordOptionalDefaults#TARGETS} names
 * every record on the staged merged base that has a constructor leaving an Optional component null.
 */
@ResourceLock("system-properties")
class MergedRecordOptionalDefaultsTest {
	private static final Path MERGED = TestFixtures.stagedRoot()
			.resolve(TestFixtures.BASE_JAR);
	private static final String STATUS = "net.minecraft.network.protocol.status.ServerStatus";

	@AfterEach void reset() { System.clearProperty(MergedRecordOptionalDefaults.PROPERTY); }

	@Test void everyServerStatusConstructorSetsForgeData() throws Exception {
		TestFixtures.requireFile(Fixture.STAGED, MERGED, "merged base not staged: " + MERGED);
		byte[] original = NativeCoremodParityTest.read(MERGED, "net/minecraft/network/protocol/status/ServerStatus");
		assertEquals(List.of("forgeData in (Lnet/minecraft/network/chat/Component;Ljava/util/Optional;Ljava/util/Optional;"
				+ "Ljava/util/Optional;ZZ)V"), MergedRecordOptionalDefaults.unsetOptionals(node(original)),
				"the base this tree is calibrated against: NeoForge's constructor (vanilla's delegates to it) leaves forgeData null");

		byte[] out = new MergedRecordOptionalDefaults().transform(STATUS, original, null);
		assertNotSame(original, out);
		ClassNode repaired = node(out);
		assertEquals(List.of(), MergedRecordOptionalDefaults.unsetOptionals(repaired));
		for (MethodNode m : repaired.methods) {
			if (m.name.equals("<init>")) new Analyzer<>(new BasicVerifier()).analyze(repaired.name, m);
		}
		assertSame(out, new MergedRecordOptionalDefaults().transform(STATUS, out, null), "a second pass adds nothing");
		assertSame(original, new MergedRecordOptionalDefaults().transform("net.minecraft.server.MinecraftServer", original, null));
		System.setProperty(MergedRecordOptionalDefaults.PROPERTY, "off");
		assertSame(original, new MergedRecordOptionalDefaults().transform(STATUS, original, null));
	}

	@Test void targetsNameEveryMergedRecordLeavingAnOptionalNull() throws Exception {
		TestFixtures.requireFile(Fixture.STAGED, MERGED, "merged base not staged: " + MERGED);
		TreeSet<String> found = new TreeSet<>();
		try (ZipFile zip = new ZipFile(MERGED.toFile())) {
			for (ZipEntry entry : Collections.list(zip.entries())) {
				if (!entry.getName().endsWith(".class")) continue;
				ClassReader reader = new ClassReader(zip.getInputStream(entry).readAllBytes());
				if (!"java/lang/Record".equals(reader.getSuperName())) continue;
				ClassNode node = new ClassNode();
				reader.accept(node, 0);
				if (!MergedRecordOptionalDefaults.unsetOptionals(node).isEmpty()) found.add(node.name.replace('/', '.'));
			}
		}
		assertEquals(new TreeSet<>(MergedRecordOptionalDefaults.TARGETS), found,
				"a merged record with a constructor that leaves an Optional component null must be a target");
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}
}
