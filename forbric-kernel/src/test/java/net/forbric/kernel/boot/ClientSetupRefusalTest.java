/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;
import net.forbric.api.ModCatalog;
import net.forbric.kernel.ui.CompatibilityDecision;

/**
 * A refusal at the client's end of loading happens inside {@code Minecraft.<init>}, where the merged game's own
 * {@code Main.main} decides what a thrown exception becomes. These tests read that decision out of the real merged
 * base rather than assuming it: the catch-all there writes an "Initializing game" crash report and exits -1, and
 * exactly one earlier handler returns quietly.
 */
@ResourceLock("ModCatalog")
@ResourceLock("system-properties")
class ClientSetupRefusalTest {
	private static final String SILENT = "net/minecraft/client/main/SilentInitException";

	@BeforeEach @AfterEach void reset() {
		CompatibilityDecision.reset(); CompatibilityFindings.reset(); ModCatalog.publish(List.of());
		System.clearProperty(CompatibilityDecision.PROPERTY);
	}

	private static Path merged() {
		String old = System.getenv("FORBRIC_OLD");
		Path root = Path.of(old == null || old.isBlank() ? System.getProperty("user.dir") + "/../forbric-loader" : old, "run");
		Path jar = root.resolve("merged-base/patched-mc-merged-26.2.jar").normalize();
		boolean present = Files.isRegularFile(jar);
		if ("1".equals(System.getenv("FORBRIC_COMPAT_FIXTURES_REQUIRED"))) assertTrue(present, "staged merged base absent: " + jar);
		org.junit.jupiter.api.Assumptions.assumeTrue(present, "staged merged base absent");
		return jar;
	}

	@Test
	void aStrictRefusalInsideMinecraftInitReturnsThroughMainsQuietHandlerAndExitsWithThePolicyCode() throws Throwable {
		CompatibilityFindings.record(new CompatibilityFinding("initialization:entrypoint:client", "broken", "Mod initialization",
				"KernelFabricEcosystem client entrypoint", CompatibilityFinding.Confidence.CONFIRMED, true,
				"its client entrypoint threw", List.of("ModCatalog.Status.FAILED")));
		System.setProperty(CompatibilityDecision.PROPERTY, "strict");
		try (URLClassLoader game = new URLClassLoader(new URL[] {merged().toUri().toURL()}, null)) {
			Class<?> silent = Class.forName(SILENT.replace('/', '.'), false, game);
			List<Throwable> crashed = new ArrayList<>();
			int code = CompatibilityLaunchBoundary.run(() -> {
				// What the merged Main.main does around new Minecraft(..): the first matching handler wins, and the
				// test below pins that the quiet one is first and never crashes or exits.
				try {
					KernelLifecycle.requireClientContinuation(game);
				} catch (Throwable thrown) {
					if (silent.isInstance(thrown)) {
						assertTrue(CompatibilityDecision.isLaunchStop(thrown), "the typed stop stays the cause");
						return; // Util.shutdownExecutors(); LOGGER.warn(..); return
					}
					crashed.add(thrown); // CrashReport "Initializing game"; Minecraft.crash(.., -1) -> System.exit(-1)
					return;
				}
				fail("a strict refusal let the client continue");
			});
			assertEquals(List.of(), crashed, "reached Main's 'Initializing game' crash handler");
			assertEquals(CompatibilityLaunchBoundary.POLICY_STOP, code);
		}
		assertEquals(1, CompatibilityFindings.confirmedRequired().size(), "the evidence is kept");
	}

	@Test
	void anApprovedClientContinuesWithoutAnyException() throws Throwable {
		System.setProperty(CompatibilityDecision.PROPERTY, "continue");
		CompatibilityFindings.record(new CompatibilityFinding("x", "broken", "f", "s", CompatibilityFinding.Confidence.CONFIRMED,
				true, "d", List.of()));
		assertEquals(0, CompatibilityLaunchBoundary.run(() -> KernelLifecycle.requireClientContinuation(getClass().getClassLoader())));
	}

	@Test
	void mainsFirstHandlerAroundTheConstructorIsTheQuietOneAndItNeitherCrashesNorExits() throws Exception {
		ClassNode main = new ClassNode();
		try (ZipFile jar = new ZipFile(merged().toFile())) {
			new ClassReader(jar.getInputStream(jar.getEntry("net/minecraft/client/main/Main.class")).readAllBytes()).accept(main, 0);
		}
		MethodNode method = main.methods.stream().filter(m -> m.name.equals("main")).findFirst().orElseThrow();
		List<AbstractInsnNode> code = List.of(method.instructions.toArray());
		int construct = -1;
		for (int i = 0; i < code.size(); i++) {
			if (code.get(i) instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
					&& call.owner.equals("net/minecraft/client/Minecraft") && call.name.equals("<init>")) construct = i;
		}
		assertTrue(construct >= 0, "Main.main constructs Minecraft");
		// The JVM takes the first entry, in table order, whose range covers the pc and whose type the exception is.
		Set<String> catchesSilent = Set.of(SILENT, "java/lang/RuntimeException", "java/lang/Exception", "java/lang/Throwable");
		TryCatchBlockNode quiet = null;
		TryCatchBlockNode crash = null;
		for (TryCatchBlockNode block : method.tryCatchBlocks) {
			if (code.indexOf(block.start) > construct || code.indexOf(block.end) <= construct) continue;
			if (quiet == null && (block.type == null || catchesSilent.contains(block.type))) quiet = block;
			if ("java/lang/Throwable".equals(block.type)) crash = block;
		}
		assertNotNull(quiet, "something handles an exception from the constructor");
		assertEquals(SILENT, quiet.type, "SilentInitException is matched before the catch-all");
		List<String> quietCalls = handlerCalls(code, quiet);
		assertTrue(quietCalls.stream().noneMatch(ClientSetupRefusalTest::crashesOrExits), "quiet handler: " + quietCalls);
		// The same reader sees the crash path, so the assertion above is not passing on a handler it cannot read.
		assertNotNull(crash, "the catch-all is still there");
		assertTrue(handlerCalls(code, crash).stream().anyMatch(ClientSetupRefusalTest::crashesOrExits),
				"the catch-all is the crash report path: " + handlerCalls(code, crash));
	}

	@Test
	void theClientSetupLifecycleDecidesThroughTheQuietRefusal() throws Exception {
		Path compiled = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "main", "net", "forbric",
				"kernel", "boot", "KernelLifecycle.class").normalize();
		assertTrue(Files.isRegularFile(compiled), "KernelLifecycle not compiled");
		ClassNode node = new ClassNode();
		new ClassReader(Files.readAllBytes(compiled)).accept(node, 0);
		MethodNode setup = node.methods.stream().filter(m -> m.name.equals("fireClientSetupLifecycle")).findFirst().orElseThrow();
		List<String> calls = new ArrayList<>();
		for (AbstractInsnNode insn : setup.instructions) if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name);
		assertTrue(calls.contains("net/forbric/kernel/boot/KernelLifecycle.requireClientContinuation"), calls.toString());
		assertFalse(calls.contains("net/forbric/kernel/ui/CompatibilityDecision.requireContinuation"),
				"a raw LaunchStopped from inside Minecraft.<init> becomes a game crash");
	}

	private static boolean crashesOrExits(String call) {
		return call.endsWith(".crash") || call.equals("java/lang/System.exit") || call.endsWith("CrashReport.forThrowable")
				|| call.endsWith(".handleExit") || call.equals("java/lang/Runtime.halt");
	}

	/** The calls a handler makes before it returns or throws. */
	private static List<String> handlerCalls(List<AbstractInsnNode> code, TryCatchBlockNode block) {
		List<String> calls = new ArrayList<>();
		for (int i = code.indexOf(block.handler); i < code.size(); i++) {
			AbstractInsnNode insn = code.get(i);
			if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name);
			int op = insn.getOpcode();
			if (op == Opcodes.RETURN || op == Opcodes.ATHROW || op == Opcodes.GOTO) break;
		}
		return calls;
	}
}
