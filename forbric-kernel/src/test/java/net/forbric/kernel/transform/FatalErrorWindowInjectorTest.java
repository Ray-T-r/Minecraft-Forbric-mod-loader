/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * Pins {@link FatalErrorWindowInjector}: NeoForge's client reports an error that stops it through
 * {@code ClientModLoader.reportFatalError(Throwable, Path, CrashReport)}, and the kernel's hook must run first there, so
 * NeoForge's own report finds the early window it draws its error screen on (D87b). If the carrier renamed or reshaped
 * the method, the hook would silently stop applying and a macOS player would read "INVALID MESSAGE WITH QUOTES" again.
 */
@ExecutesInjector(FatalErrorWindowInjector.class)
class FatalErrorWindowInjectorTest {
	private static final String OWNER = ForeignType.CLIENT_MOD_LOADER.binary(Ecosystem.NEOFORGE);
	private final FatalErrorWindowInjector injector = new FatalErrorWindowInjector();

	@Test
	void theCarrierReportsThroughAStaticMethodOfThatShape() throws Exception {
		MethodNode m = method(parse(carrier()), FatalErrorWindowInjector.METHOD, FatalErrorWindowInjector.DESC);
		assertNotNull(m, "NeoForge's ClientModLoader no longer declares reportFatalError" + FatalErrorWindowInjector.DESC);
		assertTrue((m.access & Opcodes.ACC_STATIC) != 0, "reportFatalError is no longer static");
		assertTrue(calls(m, "net/neoforged/fml/startup/FatalErrorReporting", "reportFatalError"),
				"the carrier's reportFatalError no longer hands the error to FML's FatalErrorReporting");
	}

	@Test
	void theCarrierRunsTheKernelHookFirstAndKeepsItsOwnReport() throws Exception {
		byte[] real = carrier();
		byte[] out = injector.transform(OWNER, real, ctx());
		assertNotSame(real, out, "not rewritten");
		ClassNode node = parse(out);
		MethodNode m = method(node, FatalErrorWindowInjector.METHOD, FatalErrorWindowInjector.DESC);
		AbstractInsnNode first = m.instructions.getFirst();
		while (first != null && first.getOpcode() < 0) first = first.getNext();
		assertTrue(first instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
				&& FatalErrorWindowInjector.HOOK_OWNER.equals(call.owner)
				&& FatalErrorWindowInjector.HOOK_NAME.equals(call.name) && "()V".equals(call.desc),
				"the kernel hook must be the first thing reportFatalError does");
		assertTrue(calls(m, "net/neoforged/fml/startup/FatalErrorReporting", "reportFatalError"),
				"NeoForge's own report must still run after the hook");
		new Analyzer<>(new BasicVerifier()).analyze(node.name, m);
		assertEquals(1, count(node), "one hook, in that one method");
	}

	@Test
	void anotherShapeOrClassIsHandedBackUntouched() {
		byte[] otherDesc = synthetic("(Ljava/lang/Throwable;)V");
		assertSame(otherDesc, injector.transform(OWNER, otherDesc, ctx()));
		byte[] same = synthetic(FatalErrorWindowInjector.DESC);
		assertSame(same, injector.transform("net.example.Other", same, ctx()));
	}

	/** Stand-ins under the carrier's names: the report records itself, and the kernel hook records itself. */
	private static final Map<String, String> STAND_INS = Map.of(
			"fixture.Trace", """
					package fixture;
					public final class Trace {
						public static final java.util.List<String> events = new java.util.ArrayList<>();
					}
					""",
			"net.minecraft.CrashReport", """
					package net.minecraft;
					public class CrashReport {
						public java.nio.file.Path getSaveFile() { return java.nio.file.Path.of("crash-reports/crash.txt"); }
					}
					""",
			"net.neoforged.fml.startup.FatalErrorReporting", """
					package net.neoforged.fml.startup;
					public final class FatalErrorReporting {
						public static void reportFatalError(Throwable t, java.nio.file.Path game, java.nio.file.Path log,
								java.nio.file.Path crash) {
							fixture.Trace.events.add("NeoForge reports " + t.getMessage() + " crash=" + crash);
						}
					}
					""",
			OWNER, """
					package net.neoforged.neoforge.client.loading;
					public class ClientModLoader {
						public static void reportFatalError(Throwable t, java.nio.file.Path game, net.minecraft.CrashReport report) {
							net.neoforged.fml.startup.FatalErrorReporting.reportFatalError(t, game, game.resolve("logs/latest.log"),
									report.getSaveFile());
						}
					}
					""",
			"net.forbric.kernel.boot.KernelLifecycle", """
					package net.forbric.kernel.boot;
					public final class KernelLifecycle {
						public static void onClientFatalError() {
							fixture.Trace.events.add("kernel opens NeoForge's early window");
						}
					}
					""");

	@Test
	void theKernelHookRunsBeforeNeoForgesReport(@TempDir Path work) throws Throwable {
		Map<String, byte[]> classes = new java.util.HashMap<>(InjectorExecution.compile(work, STAND_INS));
		String internal = OWNER.replace('.', '/');
		classes.put(internal, InjectorExecution.transform(injector, OWNER, classes.get(internal), EnvType.CLIENT));
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(classes.get(internal), loader));
		Object report = InjectorExecution.construct(loader.loadClass("net.minecraft.CrashReport"));
		InjectorExecution.invokeStatic(loader.loadClass(OWNER), "reportFatalError", new IllegalStateException("boom"),
				Path.of("game"), report);
		assertEquals(List.of("kernel opens NeoForge's early window", "NeoForge reports boom crash=crash-reports/crash.txt"),
				InjectorExecution.getStatic(loader.loadClass("fixture.Trace"), "events"));
	}

	private static TransformContext ctx() {
		return new TransformContext(EnvType.CLIENT, false, "intermediary");
	}

	private static byte[] carrier() throws Exception {
		Path jar = TestFixtures.stagedRoot().resolve(TestFixtures.NEO_CARRIER_JAR);
		return TestFixtures.requireEntry(Fixture.STAGED, jar,
				ForeignType.CLIENT_MOD_LOADER.internal(Ecosystem.NEOFORGE) + ".class");
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		for (MethodNode m : node.methods) if (m.name.equals(name) && m.desc.equals(desc)) return m;
		return null;
	}

	private static boolean calls(MethodNode m, String owner, String name) {
		for (AbstractInsnNode insn : m.instructions) {
			if (insn instanceof MethodInsnNode call && owner.equals(call.owner) && name.equals(call.name)) return true;
		}
		return false;
	}

	private static int count(ClassNode node) {
		int n = 0;
		for (MethodNode m : node.methods) {
			for (AbstractInsnNode insn : m.instructions) {
				if (insn instanceof MethodInsnNode call && FatalErrorWindowInjector.HOOK_OWNER.equals(call.owner)
						&& FatalErrorWindowInjector.HOOK_NAME.equals(call.name)) n++;
			}
		}
		return n;
	}

	private static byte[] synthetic(String desc) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, ForeignType.CLIENT_MOD_LOADER.internal(Ecosystem.NEOFORGE), null,
				"java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, FatalErrorWindowInjector.METHOD, desc,
				null, null);
		mv.visitCode();
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}
}
