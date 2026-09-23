/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

class PortalSpawnInjectorTest {
	private final PortalSpawnInjector injector = new PortalSpawnInjector();
	private final TransformContext context = new TransformContext(EnvType.CLIENT, false, "mojmap");
	@AfterEach void reset() { System.clearProperty(PortalSpawnInjector.PROPERTY); }

	@Test void onlyTheHookOwnerChangesOnTheRealMergedBase() throws Exception {
		byte[] original = staged("merged-base/patched-mc-merged-26.2.jar", PortalSpawnInjector.TARGET.replace('.', '/'));
		// A base whose merge restored MinecraftForge's own call carries the proved pair: each family's call is routed
		// through its own runtime entry. A base that lost it gets the wrapper that forwards to Forge itself. Either
		// way only call targets change.
		boolean restored = calls(host(parse(original))).stream().anyMatch(c -> c.owner.equals(PortalSpawnInjector.FORGE));
		byte[] changed = injector.transform(PortalSpawnInjector.TARGET, original, context);
		assertNotSame(original, changed);
		ClassNode after = parse(changed);
		List<MethodInsnNode> runtime = calls(host(after)).stream().filter(c -> c.owner.equals(PortalSpawnInjector.RUNTIME)).toList();
		assertEquals(restored ? List.of(PortalSpawnInjector.NEO_ONLY, PortalSpawnInjector.FORGE_ONLY) : List.of("onTrySpawnPortal"),
				runtime.stream().map(hook -> hook.name).toList());
		for (MethodInsnNode hook : runtime) assertEquals(PortalSpawnInjector.HOOK_DESC, hook.desc);
		new Analyzer<>(new BasicVerifier()).analyze(after.name, host(after));
		for (MethodInsnNode hook : runtime) {
			hook.owner = hook.name.equals(PortalSpawnInjector.FORGE_ONLY) ? PortalSpawnInjector.FORGE : PortalSpawnInjector.NEO;
			hook.name = "onTrySpawnPortal";
		}
		assertEquals(trace(parse(original)), trace(after), "all operands, frames, branches and the Optional consumer must be unchanged");
		assertSame(changed, injector.transform(PortalSpawnInjector.TARGET, changed, context));
	}

	@Test void bothActualCarrierHooksHaveTheRedirectedStaticDescriptor() throws Exception {
		for (String[] entry : List.of(new String[] {"forge-runtime/forge-runtime.jar", PortalSpawnInjector.FORGE},
				new String[] {"neoforge-runtime/neoforge-runtime.jar", PortalSpawnInjector.NEO})) {
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
		String old = System.getenv("FORBRIC_OLD"); Path run = old == null ? Path.of("..", "forbric-loader", "run") : Path.of(old, "run");
		Path path = run.resolve(jar); assumeTrue(Files.isRegularFile(path), "staged artifact absent: " + path);
		try (ZipFile zip = new ZipFile(path.toFile())) { return zip.getInputStream(zip.getEntry(entry + ".class")).readAllBytes(); }
	}
}
