/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * Verifies against the REAL merged-base bytecode that {@link LifecycleHookInjector} excises the genuine
 * FancyModLoader server-loading trigger from {@code net.minecraft.server.Main.main} — the kernel owns the
 * lifecycle, no genuine loader runs.
 */
@ExecutesInjector(LifecycleHookInjector.class)
class LifecycleHookInjectorTest {
	private static final Path MERGED_BASE = TestFixtures.stagedBase();

	private final LifecycleHookInjector injector = new LifecycleHookInjector();

	@Test
	void excisesServerModLoaderFromRealMain() throws Exception {
		byte[] original = TestFixtures.requireEntry(Fixture.STAGED, MERGED_BASE, "net/minecraft/server/Main.class");

		// Precondition: the real Main.main really does call a genuine ModLoader.load (else the test proves nothing).
		assertTrue(callsAnyModLoaderLoad(original), "merged Main.main should reference a genuine ServerModLoader.load");

		byte[] out = injector.transform(LifecycleHookInjector.SERVER_MAIN, original, null);

		assertTrue(injector.transformedServerEntry(), "injector saw the server entry");
		assertFalse(injector.missedRequiredExcision(), "injector must not report a missed required excision");
		assertFalse(callsAnyModLoaderLoad(out),
				"after excision, Main.main must NOT reference any genuine ServerModLoader.load");

		// And the class must still be well-formed / verifiable (frames recomputed by re-reading).
		ClassNode check = new ClassNode();
		new ClassReader(out).accept(check, 0);
		MethodNode main = check.methods.stream().filter(m -> m.name.equals("main")).findFirst().orElseThrow();
		assertTrue(main.instructions.size() > 0, "main still has a body");
	}

	/**
	 * Fabric Loader patches {@code DUP; Hooks.setGameInstance} right after the dedicated server's constructor (the one
	 * whose first parameter is a Thread, built in {@code MinecraftServer.spin}'s lambda), before the server thread runs:
	 * {@code FabricLoader.getGameInstance()} answers the server from SERVER_STARTING on. The kernel set it only at
	 * NeoForge's ServerAboutToStartEvent, after initServer had run. The real Main gets the call straight after the
	 * constructor, in the lambda; with the Fabric hooks switched off it gets none.
	 */
	@Test
	void theRealServerEntryHandsTheServerToFabricAsItIsConstructed() throws Exception {
		byte[] original = TestFixtures.requireEntry(Fixture.STAGED, MERGED_BASE, "net/minecraft/server/Main.class");
		ClassNode out = node(new LifecycleHookInjector().transform(LifecycleHookInjector.SERVER_MAIN, original, null));
		int found = 0;
		for (MethodNode m : out.methods) {
			List<String> code = code(m);
			for (int i = 0; i + 2 < code.size(); i++) {
				if (!code.get(i).startsWith("INVOKESPECIAL ") || !code.get(i).contains(".<init>(Ljava/lang/Thread;")) continue;
				assertEquals("DUP", code.get(i + 1), m.name + ": " + code);
				assertEquals("INVOKESTATIC net/fabricmc/loader/impl/game/minecraft/Hooks.setGameInstance(Ljava/lang/Object;)V", code.get(i + 2),
						m.name + ": " + code);
				assertTrue(code.get(i).contains("net/minecraft/server/dedicated/DedicatedServer.<init>"), code.get(i));
				found++;
			}
		}
		assertEquals(1, found, "one capture, after the one server constructor");
		String old = System.setProperty(LifecycleHookInjector.FABRIC_HOOKS_SWITCH, "off");
		try {
			ClassNode off = node(new LifecycleHookInjector().transform(LifecycleHookInjector.SERVER_MAIN, original, null));
			for (MethodNode m : off.methods) assertFalse(code(m).stream().anyMatch(c -> c.contains("Hooks.setGameInstance")), m.name);
		} finally {
			if (old == null) System.clearProperty(LifecycleHookInjector.FABRIC_HOOKS_SWITCH); else System.setProperty(LifecycleHookInjector.FABRIC_HOOKS_SWITCH, old);
		}
	}

	/** The client entry builds no server from a Thread, and is never given the server capture. */
	@Test
	void theClientEntryIsNotGivenTheServerCapture() {
		byte[] client = entry(LifecycleHookInjector.CLIENT_MAIN, ForeignType.CLIENT_MOD_LOADER.internal(Ecosystem.NEOFORGE), "begin", "()V");
		ClassNode out = node(LifecycleHookInjector.forClient().transform(LifecycleHookInjector.CLIENT_MAIN, client, null));
		for (MethodNode m : out.methods) assertFalse(code(m).stream().anyMatch(c -> c.contains("Hooks.setGameInstance")), m.name);
	}

	@Test
	void passesThroughNonEntryClasses() {
		byte[] bogus = new byte[] {}; // never inspected — className gate short-circuits
		byte[] out = injector.transform("net.minecraft.world.level.block.Block", bogus, null);
		assertEquals(bogus, out, "non-entry classes returned unchanged");
		assertFalse(injector.transformedServerEntry());
	}

	/**
	 * The client's {@code Main.main} opens with three steps whose handler, {@code logEarlyException}, prints to
	 * stderr before {@code main} exits 249, 252 or 251 -- nothing leaves {@code main}. A carrier without NeoForge's FMLEnvironment ended
	 * there ({@code SharedConstants.<clinit>}) with nothing in latest.log. The real handler now reports first.
	 */
	@Test
	void theRealClientMainReportsItsEarlyFailuresToTheKernelFirst() throws Exception {
		byte[] original = TestFixtures.requireEntry(Fixture.STAGED, MERGED_BASE, "net/minecraft/client/main/Main.class");
		LifecycleHookInjector client = LifecycleHookInjector.forClient();

		ClassNode out = node(client.transform(LifecycleHookInjector.CLIENT_MAIN, original, null));

		assertFalse(client.missedRequiredExcision(), "the client trigger is still redirected");
		assertEquals(List.of("ALOAD 0", "INVOKESTATIC net/forbric/kernel/boot/KernelLifecycle.onEarlyStartupFailure"
				+ "(Ljava/lang/Throwable;)V", "ALOAD 0", "INVOKEVIRTUAL java/lang/Throwable.printStackTrace()V", "RETURN"),
				code(method(out, LifecycleHookInjector.EARLY_FAILURE)));
		// Vanilla's three call sites, and its exits after them, are left as they were.
		assertEquals(3, code(method(out, "main")).stream()
				.filter(i -> i.endsWith("/Main." + LifecycleHookInjector.EARLY_FAILURE + "(Ljava/lang/Throwable;)V"))
				.count());
	}

	/** The server's handler-less {@code tryDetectVersion} needs nothing: what it throws leaves {@code main}. */
	@Test
	void theServerEntryIsNotGivenAnEarlyFailureHook() {
		byte[] out = injector.transform(LifecycleHookInjector.SERVER_MAIN, entry(LifecycleHookInjector.SERVER_MAIN,
				ForeignType.SERVER_MOD_LOADER.internal(Ecosystem.NEOFORGE), "load", "(Z)V"), null);

		assertEquals(List.of("ALOAD 0", "INVOKEVIRTUAL java/lang/Throwable.printStackTrace()V", "RETURN"),
				code(method(node(out), LifecycleHookInjector.EARLY_FAILURE)));
	}

	/**
	 * Run, not just read: the rewritten handler is loaded (so verified) and called the way vanilla's catch calls it.
	 * Without log4j on the test classpath ForbricLog's ERROR goes to stderr; in the game it is a line in latest.log.
	 */
	@Test
	void anEarlyFailureReachesTheLogBeforeVanillaPrintsItAndIsStillPrinted() throws Exception {
		byte[] out = LifecycleHookInjector.forClient().transform(LifecycleHookInjector.CLIENT_MAIN,
				entry(LifecycleHookInjector.CLIENT_MAIN, ForeignType.CLIENT_MOD_LOADER.internal(Ecosystem.NEOFORGE),
						"begin", "()V"), null);
		Class<?> main = new ClassLoader(getClass().getClassLoader()) {
			Class<?> define() {
				return defineClass(LifecycleHookInjector.CLIENT_MAIN, out, 0, out.length);
			}
		}.define();
		Method handler = main.getDeclaredMethod(LifecycleHookInjector.EARLY_FAILURE, Throwable.class);
		handler.setAccessible(true);
		Throwable failure = new NoClassDefFoundError("net/neoforged/fml/loading/FMLEnvironment");

		PrintStream originalOut = System.out, originalErr = System.err;
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		PrintStream sink = new PrintStream(buffer, true, StandardCharsets.UTF_8);
		System.setOut(sink);
		System.setErr(sink);
		try {
			handler.invoke(null, failure);
		} finally {
			System.setOut(originalOut);
			System.setErr(originalErr);
		}
		String said = buffer.toString(StandardCharsets.UTF_8);

		assertTrue(said.startsWith("[Forbric/ERROR] [Forbric/Boot] the game stopped during startup on "
				+ "java.lang.NoClassDefFoundError: net/neoforged/fml/loading/FMLEnvironment"), said);
		// Two traces after that line: the log's, then vanilla's own print, which still happens.
		String trace = System.lineSeparator() + "java.lang.NoClassDefFoundError: net/neoforged/fml/loading/FMLEnvironment"
				+ System.lineSeparator();
		assertEquals(2, said.split(java.util.regex.Pattern.quote(trace), -1).length - 1, said);
	}

	/**
	 * An entry shaped like vanilla's for what is read here: {@code main} calls the genuine trigger, and
	 * {@code private static logEarlyException(Throwable)} prints the stack trace.
	 */
	private static byte[] entry(String binaryName, String triggerOwner, String trigger, String triggerDesc) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, binaryName.replace('.', '/'), null, "java/lang/Object", null);
		MethodVisitor main = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "main", "([Ljava/lang/String;)V",
				null, null);
		main.visitCode();
		if (triggerDesc.equals("(Z)V")) main.visitInsn(Opcodes.ICONST_1);
		main.visitMethodInsn(Opcodes.INVOKESTATIC, triggerOwner, trigger, triggerDesc, false);
		main.visitInsn(Opcodes.RETURN);
		main.visitMaxs(0, 0);
		main.visitEnd();
		MethodVisitor handler = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
				LifecycleHookInjector.EARLY_FAILURE, LifecycleHookInjector.EARLY_FAILURE_DESC, null, null);
		handler.visitCode();
		handler.visitVarInsn(Opcodes.ALOAD, 0);
		handler.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Throwable", "printStackTrace", "()V", false);
		handler.visitInsn(Opcodes.RETURN);
		handler.visitMaxs(0, 0);
		handler.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static ClassNode node(byte[] classBytes) {
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		return node;
	}

	private static MethodNode method(ClassNode node, String name) {
		return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
	}

	/** The method's instructions as text, without labels, line numbers and frames. */
	private static List<String> code(MethodNode method) {
		List<String> out = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) {
			if (insn.getOpcode() < 0) continue;
			String op = org.objectweb.asm.util.Printer.OPCODES[insn.getOpcode()];
			if (insn instanceof MethodInsnNode call) out.add(op + " " + call.owner + "." + call.name + call.desc);
			else if (insn instanceof VarInsnNode local) out.add(op + " " + local.var);
			else out.add(op);
		}
		return out;
	}

	private static boolean callsAnyModLoaderLoad(byte[] classBytes) {
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		for (MethodNode m : node.methods) {
			if (!m.name.equals("main")) continue;
			for (var insn : m.instructions.toArray()) {
				if (insn instanceof MethodInsnNode c
						&& c.owner.endsWith("server/loading/ServerModLoader") && c.name.equals("load")) {
					return true;
				}
			}
		}
		return false;
	}
}
