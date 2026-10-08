/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Covers calling vanilla's {@code FriendlyByteBuf.writeByte(int)} again where NeoForge's recompile bound the call to its
 * extension's {@code writeByte(byte)}.
 *
 * <p>The decisive assertions read the other jars: the extension's overload only forwards to vanilla's, and after the
 * repair every merged method that made the extension call makes exactly the {@code writeByte} calls vanilla's makes —
 * the same owners, the same descriptors, as often.
 */
class MergedBaseVanillaWriteByteTest {
	private static final Path MERGED_BASE = TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar");
	private static final Path NEO_RUNTIME = TestFixtures.stagedRoot().resolve("neoforge-runtime/neoforge-runtime.jar");
	private static final Path VANILLA = TestFixtures.vanillaJar();
	private static final String EXTENSION = "net/neoforged/neoforge/common/extensions/IFriendlyByteBufExtension";
	private static final String BUF = "net/minecraft/network/FriendlyByteBuf";
	private static final String BYTE = "(B)Lnet/minecraft/network/FriendlyByteBuf;";
	private static final String INT = "(I)Lnet/minecraft/network/FriendlyByteBuf;";
	private static final String ABILITIES = "net/minecraft/network/protocol/game/ServerboundPlayerAbilitiesPacket";
	private static final String FIXTURE = "net/minecraft/network/ForbricWriteByteFixture";

	@AfterEach void reset() {
		System.clearProperty(ForbricMergedBaseCompatTransformer.VANILLA_WRITE_BYTE_PROPERTY);
	}

	/** The swap is behaviour-preserving only because NeoForge's overload is nothing but the forward. */
	@Test void theExtensionsOverloadOnlyForwardsToVanillas() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(NEO_RUNTIME), "NeoForge runtime not staged: " + NEO_RUNTIME);
		ClassNode extension = read(entry(NEO_RUNTIME, EXTENSION + ".class"));
		MethodNode overload = extension.methods.stream().filter(m -> m.name.equals("writeByte") && m.desc.equals(BYTE))
				.findFirst().orElseThrow(() -> new AssertionError("NeoForge's extension no longer declares writeByte(byte); "
						+ "delete the repair rather than leaving a claim on a shape that is gone"));
		List<String> code = new ArrayList<>();
		for (AbstractInsnNode insn : overload.instructions) {
			if (insn.getOpcode() < 0) continue;
			code.add(insn instanceof MethodInsnNode call ? call.owner + "." + call.name + call.desc : Integer.toString(insn.getOpcode()));
		}
		assertEquals(List.of(Integer.toString(Opcodes.ALOAD), EXTENSION + ".self()L" + BUF + ";", Integer.toString(Opcodes.ILOAD),
				BUF + ".writeByte" + INT, Integer.toString(Opcodes.ARETURN)), code);
	}

	@Test void everyMergedSiteBecomesTheCallVanillaMakes() throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE), "merged base not staged: " + MERGED_BASE);
		TestFixtures.require(Fixture.MC_LIBRARIES, Files.isRegularFile(VANILLA), "no vanilla 26.2 jar at " + VANILLA);
		Map<String, Integer> repaired = new TreeMap<>();
		try (ZipFile merged = new ZipFile(MERGED_BASE.toFile()); ZipFile vanilla = new ZipFile(VANILLA.toFile())) {
			for (ZipEntry entry : Collections.list(merged.entries())) {
				if (!entry.getName().startsWith("net/minecraft/") || !entry.getName().endsWith(".class")) continue;
				byte[] before = merged.getInputStream(entry).readAllBytes();
				ClassNode node = read(before);
				Map<String, List<String>> calls = writeBytes(node);
				if (calls.values().stream().noneMatch(c -> c.stream().anyMatch(s -> s.endsWith(BYTE)))) continue;
				ZipEntry original = vanilla.getEntry(entry.getName());
				assertNotNull(original, entry.getName() + " is not a vanilla class");
				Map<String, List<String>> vanillaCalls = writeBytes(read(vanilla.getInputStream(original).readAllBytes()));
				String name = node.name.replace('/', '.');
				Map<String, List<String>> after = writeBytes(read(new ForbricMergedBaseCompatTransformer().transform(name, before, null)));
				for (Map.Entry<String, List<String>> method : calls.entrySet()) {
					if (method.getValue().stream().noneMatch(s -> s.endsWith(BYTE))) continue;
					assertEquals(vanillaCalls.get(method.getKey()), after.get(method.getKey()), name + "." + method.getKey());
					repaired.merge(name, (int) method.getValue().stream().filter(s -> s.endsWith(BYTE)).count(), Integer::sum);
				}
			}
		}
		assertEquals(15, repaired.values().stream().mapToInt(Integer::intValue).sum(), "source-coherent native packet bodies retain this additional vanilla-equivalent byte site: " + repaired);
		assertTrue(repaired.containsKey(ABILITIES.replace('/', '.')), "ViaFabricPlus' packet: " + repaired);
	}

	@Test void switchedOffItLeavesNeoForgesCall() {
		System.setProperty(ForbricMergedBaseCompatTransformer.VANILLA_WRITE_BYTE_PROPERTY, "off");
		byte[] fixture = fixture(FIXTURE);
		assertSame(fixture, new ForbricMergedBaseCompatTransformer().transform(FIXTURE.replace('/', '.'), fixture, null));
	}

	@Test void aGameClassCallsVanillasOverloadAndOneOutsideTheGameIsLeftAlone() {
		byte[] fixture = fixture(FIXTURE);
		ClassNode repaired = read(new ForbricMergedBaseCompatTransformer().transform(FIXTURE.replace('/', '.'), fixture, null));
		assertEquals(Map.of("write(L" + BUF + ";B)V", List.of(BUF + ".writeByte" + INT)), writeBytes(repaired));
		byte[] outside = fixture("net/forbric/other/WriteByteFixture");
		assertSame(outside, new ForbricMergedBaseCompatTransformer().transform("net.forbric.other.WriteByteFixture", outside, null));
	}

	/** Each method's {@code writeByte} calls on either buffer, as {@code owner.name+desc}, in order. */
	private static Map<String, List<String>> writeBytes(ClassNode node) {
		Map<String, List<String>> out = new TreeMap<>();
		for (MethodNode method : node.methods) {
			List<String> calls = new ArrayList<>();
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call && call.name.equals("writeByte")
						&& (call.owner.equals(BUF) || call.owner.equals("net/minecraft/network/RegistryFriendlyByteBuf"))) {
					calls.add(call.owner + "." + call.name + call.desc);
				}
			}
			if (!calls.isEmpty()) out.put(method.name + method.desc, calls);
		}
		return out;
	}

	/** {@code static void write(FriendlyByteBuf, byte)} making NeoForge's call, as its recompile emits it. */
	private static byte[] fixture(String name) {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
		MethodVisitor write = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "write", "(L" + BUF + ";B)V", null, null);
		write.visitCode();
		write.visitVarInsn(Opcodes.ALOAD, 0);
		write.visitVarInsn(Opcodes.ILOAD, 1);
		write.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUF, "writeByte", BYTE, false);
		write.visitInsn(Opcodes.POP);
		write.visitInsn(Opcodes.RETURN);
		write.visitMaxs(2, 2);
		write.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}

	private static ClassNode read(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] entry(Path jar, String name) throws Exception {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry(name);
			assertNotNull(entry, name + " in " + jar);
			return zip.getInputStream(entry).readAllBytes();
		}
	}
}
