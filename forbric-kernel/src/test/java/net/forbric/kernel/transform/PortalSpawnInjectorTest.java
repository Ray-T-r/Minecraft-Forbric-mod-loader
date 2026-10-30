/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import org.objectweb.asm.util.TraceClassVisitor;

import net.fabricmc.api.EnvType;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

class PortalSpawnInjectorTest {
	private final PortalSpawnInjector injector = new PortalSpawnInjector();
	private final TransformContext context = new TransformContext(EnvType.CLIENT, false, "mojmap");
	@AfterEach void reset() { System.clearProperty(PortalSpawnInjector.PROPERTY); }

	@Test void onlyTheHookOwnerChangesOnTheRealMergedBase() throws Exception {
		byte[] original = staged(TestFixtures.BASE_JAR, PortalSpawnInjector.TARGET.replace('.', '/'));
		// A base whose merge restored MinecraftForge's own call carries the proved pair: each family's call is routed
		// through its own runtime entry, in the base's own order (NeoForge's first behind its barrier, or MinecraftForge's
		// first where the merge kept both sides' edits). A base that lost it gets the wrapper that forwards to Forge
		// itself. Either way only call targets change.
		List<String> nativeOrder = calls(host(parse(original))).stream().filter(c -> c.name.equals("onTrySpawnPortal")).map(c -> c.owner).toList();
		boolean restored = nativeOrder.contains(PortalSpawnInjector.FORGE);
		byte[] changed = injector.transform(PortalSpawnInjector.TARGET, original, context);
		assertNotSame(original, changed);
		ClassNode after = parse(changed);
		List<MethodInsnNode> runtime = calls(host(after)).stream().filter(c -> c.owner.equals(PortalSpawnInjector.RUNTIME)).toList();
		assertEquals(restored ? nativeOrder.stream().map(owner -> owner.equals(PortalSpawnInjector.FORGE) ? PortalSpawnInjector.FORGE_ONLY
				: PortalSpawnInjector.NEO_ONLY).toList() : List.of("onTrySpawnPortal"), runtime.stream().map(hook -> hook.name).toList());
		for (MethodInsnNode hook : runtime) assertEquals(PortalSpawnInjector.HOOK_DESC, hook.desc);
		new Analyzer<>(new BasicVerifier()).analyze(after.name, host(after));
		for (MethodInsnNode hook : runtime) {
			hook.owner = hook.name.equals(PortalSpawnInjector.FORGE_ONLY) ? PortalSpawnInjector.FORGE : PortalSpawnInjector.NEO;
			hook.name = "onTrySpawnPortal";
		}
		assertEquals(trace(parse(original)), trace(after), "all operands, frames, branches and the Optional consumer must be unchanged");
		assertSame(changed, injector.transform(PortalSpawnInjector.TARGET, changed, context));
	}

	@Test void switchedOffTheRealMergedBaseCarriesOnlyTheReviewedNeoForgeCaller() throws Exception {
		byte[] original = staged(TestFixtures.BASE_JAR, PortalSpawnInjector.TARGET.replace('.', '/'));
		boolean restored = calls(host(parse(original))).stream().anyMatch(c -> c.owner.equals(PortalSpawnInjector.FORGE));
		System.setProperty(PortalSpawnInjector.PROPERTY, "off");
		byte[] changed = injector.transform(PortalSpawnInjector.TARGET, original, context);
		if (!restored) assertSame(original, changed, "a base that lost MinecraftForge's call is left to the legacy forward");
		ClassNode after = parse(changed);
		assertEquals(List.of(PortalSpawnInjector.NEO), calls(host(after)).stream()
				.filter(c -> c.name.startsWith("onTrySpawnPortal")).map(c -> c.owner).toList());
		new Analyzer<>(new BasicVerifier()).analyze(after.name, host(after));
		assertEquals(PortalSpawnInjector.NATIVE_BODY, net.forbric.kernel.mixin.MixinInstructionFingerprint.hash(host(after)),
				"switched off, the caller is exactly the reviewed one the legacy forward was built for");
		assertSame(changed, injector.transform(PortalSpawnInjector.TARGET, changed, context));
	}

	@Test void bothActualCarrierHooksHaveTheRedirectedStaticDescriptor() throws Exception {
		for (String[] entry : List.of(new String[] {TestFixtures.FORGE_CARRIER_JAR, PortalSpawnInjector.FORGE},
				new String[] {TestFixtures.NEO_CARRIER_JAR, PortalSpawnInjector.NEO})) {
			ClassNode carrier = parse(staged(entry[0], entry[1]));
			MethodNode method = carrier.methods.stream().filter(m -> m.name.equals("onTrySpawnPortal") && m.desc.equals(PortalSpawnInjector.HOOK_DESC)).findFirst().orElseThrow();
			assertTrue((method.access & Opcodes.ACC_STATIC) != 0);
		}
	}

	@Test void unexpectedOrAlreadyComposedCallSitesRetainTheirOriginalBytes() {
		for (Consumer<ClassNode> change : List.<Consumer<ClassNode>>of(
				n -> host(n).access |= Opcodes.ACC_STATIC,
				n -> host(n).desc = "()V",
				n -> hook(host(n)).desc = "()V",
				n -> hook(host(n)).setOpcode(Opcodes.INVOKEVIRTUAL),
				n -> hook(host(n)).itf = true,
				n -> hook(host(n)).owner = PortalSpawnInjector.FORGE,
				n -> hook(host(n)).owner = PortalSpawnInjector.RUNTIME,
				n -> host(n).instructions.insert(hook(host(n)), new InsnNode(Opcodes.POP)),
				n -> host(n).instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, PortalSpawnInjector.NEO, "onTrySpawnPortal", PortalSpawnInjector.HOOK_DESC, false)),
				n -> host(n).instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, PortalSpawnInjector.FORGE, "onTrySpawnPortal", PortalSpawnInjector.HOOK_DESC, false)),
				n -> n.name = "other/Type")) {
			ClassNode node = fixture(); change.accept(node); byte[] bytes = write(node);
			assertSame(bytes, injector.transform(PortalSpawnInjector.TARGET, bytes, context));
		}
		byte[] bytes = write(fixture()); System.setProperty(PortalSpawnInjector.PROPERTY, "off");
		assertSame(bytes, injector.transform(PortalSpawnInjector.TARGET, bytes, context));
		assertTrue(injector.anchors().anchors().isEmpty());
	}

	private static ClassNode fixture() {
		ClassNode n = new ClassNode(); n.name = PortalSpawnInjector.TARGET.replace('.', '/'); n.version = Opcodes.V21; n.access = Opcodes.ACC_PUBLIC; n.superName = "java/lang/Object";
		MethodNode m = new MethodNode(Opcodes.ACC_PROTECTED, "onPlace", PortalSpawnInjector.HOST_DESC, null, null);
		m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2)); m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3)); m.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
		m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, PortalSpawnInjector.NEO, "onTrySpawnPortal", PortalSpawnInjector.HOOK_DESC, false));
		m.instructions.add(new VarInsnNode(Opcodes.ASTORE, 6)); m.instructions.add(new InsnNode(Opcodes.RETURN));
		m.maxStack = 3; m.maxLocals = 7; n.methods.add(m); return n;
	}
	private static MethodNode host(ClassNode n) { return n.methods.stream().filter(m -> m.name.equals("onPlace")).findFirst().orElseThrow(); }
	private static List<MethodInsnNode> calls(MethodNode m) {
		List<MethodInsnNode> out = new java.util.ArrayList<>();
		for (AbstractInsnNode instruction : m.instructions) if (instruction instanceof MethodInsnNode c) out.add(c);
		return out;
	}
	private static MethodInsnNode hook(MethodNode m) {
		for (AbstractInsnNode instruction : m.instructions) if (instruction instanceof MethodInsnNode c && c.name.equals("onTrySpawnPortal")) return c;
		throw new AssertionError("missing portal hook");
	}
	private static ClassNode parse(byte[] bytes) { ClassNode n = new ClassNode(); new ClassReader(bytes).accept(n, 0); return n; }
	private static byte[] write(ClassNode n) { ClassWriter w = new ClassWriter(0); n.accept(w); return w.toByteArray(); }
	private static String trace(ClassNode n) { StringWriter out = new StringWriter(); n.accept(new TraceClassVisitor(new PrintWriter(out))); return out.toString(); }
	private static byte[] staged(String jar, String entry) throws Exception {
		Path run = TestFixtures.stagedRoot();
		Path path = run.resolve(jar); TestFixtures.requireFile(Fixture.STAGED, path, "staged artifact absent: " + path);
		try (ZipFile zip = new ZipFile(path.toFile())) { return zip.getInputStream(zip.getEntry(entry + ".class")).readAllBytes(); }
	}
}
