package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipFile;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

class ForgeBlockTintInjectorTest {
	private static final ForgeBlockTintInjector INJECTOR = new ForgeBlockTintInjector();
	@AfterEach void clear() { System.clearProperty("forbric.forgeClientInit"); }

	@Test void redirectsOnePostWithoutChangingTheStackOrInstructionSequence() throws Exception {
		byte[] before = fixture(1, true);
		byte[] after = INJECTOR.transform(ForgeBlockTintInjector.TARGET, before, null);
		assertNotSame(before, after);
		MethodNode original = createDefault(before), changed = createDefault(after);
		assertEquals(opcodes(original), opcodes(changed));
		assertEquals(0, posts(changed, ForeignType.FML_MOD_LOADER.internal(Ecosystem.NEOFORGE)));
		assertEquals(1, posts(changed, ForgeBlockTintInjector.HOOK));
		new Analyzer<>(new BasicVerifier()).analyze(ForgeBlockTintInjector.TARGET.replace('.', '/'), changed);
		assertSame(after, INJECTOR.transform(ForgeBlockTintInjector.TARGET, after, null));
	}

	@Test void ambiguousMissingOrDifferentEventsStandDown() {
		for (byte[] bytes : List.of(fixture(0, true), fixture(2, true), fixture(1, false))) {
			assertSame(bytes, INJECTOR.transform(ForgeBlockTintInjector.TARGET, bytes, null));
		}
		byte[] other = {1, 2};
		assertSame(other, INJECTOR.transform("example.Other", other, null));
	}

	@Test void clientInitControlRestoresTheOriginalCall() {
		byte[] bytes = fixture(1, true);
		System.setProperty("forbric.forgeClientInit", "off");
		assertSame(bytes, INJECTOR.transform(ForgeBlockTintInjector.TARGET, bytes, null));
	}

	@Test void theActualMergedClassHasExactlyThisSeam() throws Exception {
		Path stage = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", "../forbric-loader"));
		Path jar = stage.resolve("run/merged-base/patched-mc-merged-26.2.jar");
		assumeTrue(Files.isRegularFile(jar), "requires merged base");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			byte[] before = zip.getInputStream(zip.getEntry(ForgeBlockTintInjector.TARGET.replace('.', '/') + ".class")).readAllBytes();
			assertEquals(1, posts(createDefault(before), ForeignType.FML_MOD_LOADER.internal(Ecosystem.NEOFORGE)));
			byte[] after = INJECTOR.transform(ForgeBlockTintInjector.TARGET, before, null);
			assertNotSame(before, after);
			assertEquals(opcodes(createDefault(before)), opcodes(createDefault(after)));
			assertEquals(1, posts(createDefault(after), ForgeBlockTintInjector.HOOK));
			assertSame(after, INJECTOR.transform(ForgeBlockTintInjector.TARGET, after, null));
		}
	}

	@Test void typedFunnelPostsNeoBeforeAskingForgeWithTheEventsBlockColors() throws Exception {
		Path runtime = Path.of("build/classes/java/runtime", ForgeBlockTintInjector.HOOK + ".class");
		assumeTrue(Files.isRegularFile(runtime), "requires runtime source set");
		ClassNode node = parse(Files.readAllBytes(runtime));
		MethodNode method = node.methods.stream().filter(m -> m.name.equals("postBlockTintSources")).findFirst().orElseThrow();
		assertEquals(ForgeBlockTintInjector.POST, method.desc);
		int neo = -1, getter = -1, forge = -1;
		for (int i = 0; i < method.instructions.size(); i++) {
			if (!(method.instructions.get(i) instanceof MethodInsnNode call)) continue;
			if (call.owner.equals(ForeignType.FML_MOD_LOADER.internal(Ecosystem.NEOFORGE)) && call.name.equals("postEvent")) neo = i;
			if (call.owner.equals(ForeignType.BLOCK_TINT_EVENT.internal(Ecosystem.NEOFORGE)) && call.name.equals("getBlockColors")) getter = i;
			if (call.owner.equals(ForeignType.CLIENT_HOOKS.internal(Ecosystem.FORGE)) && call.name.equals("onBlockColorsInit")) forge = i;
		}
		assertTrue(neo >= 0 && getter > neo && forge > getter, "one live instance must flow through both registration APIs");
	}

	private static ClassNode parse(byte[] bytes) { ClassNode n = new ClassNode(); new ClassReader(bytes).accept(n, 0); return n; }
	private static MethodNode createDefault(byte[] bytes) {
		return parse(bytes).methods.stream().filter(m -> m.name.equals("createDefault")).findFirst().orElseThrow();
	}
	private static List<Integer> opcodes(MethodNode m) {
		return Arrays.stream(m.instructions.toArray()).filter(i -> i.getOpcode() >= 0).map(i -> i.getOpcode()).toList();
	}
	private static long posts(MethodNode method, String owner) {
		return Arrays.stream(method.instructions.toArray()).filter(i -> i instanceof MethodInsnNode call
				&& call.owner.equals(owner) && call.desc.equals(ForgeBlockTintInjector.POST)).count();
	}
	private static byte[] fixture(int posts, boolean expectedEvent) {
		ClassWriter out = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		String target = ForgeBlockTintInjector.TARGET.replace('.', '/');
		out.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, target, null, "java/lang/Object", null);
		var method = out.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "createDefault", "()L" + target + ";", null, null);
		method.visitCode();
		for (int i = 0; i < posts; i++) {
			String event = expectedEvent ? ForeignType.BLOCK_TINT_EVENT.internal(Ecosystem.NEOFORGE) : "example/OtherEvent";
			method.visitTypeInsn(Opcodes.NEW, event); method.visitInsn(Opcodes.DUP); method.visitInsn(Opcodes.ACONST_NULL);
			method.visitMethodInsn(Opcodes.INVOKESPECIAL, event, "<init>", "(L" + target + ";)V", false);
			method.visitMethodInsn(Opcodes.INVOKESTATIC, ForeignType.FML_MOD_LOADER.internal(Ecosystem.NEOFORGE),
					"postEvent", ForgeBlockTintInjector.POST, false);
		}
		method.visitInsn(Opcodes.ACONST_NULL); method.visitInsn(Opcodes.ARETURN); method.visitMaxs(0, 0); method.visitEnd();
		out.visitEnd(); return out.toByteArray();
	}
}
