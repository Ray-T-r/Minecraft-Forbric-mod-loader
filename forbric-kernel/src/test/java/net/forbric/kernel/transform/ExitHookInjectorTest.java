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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/** Every return of {@code Minecraft.close()} and {@code DedicatedServer.onServerExit()} calls the loader's watcher sweep; nothing else is touched. */
class ExitHookInjectorTest {
	private static final Path MERGED_BASE =
			Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run", "merged-base",
					"patched-mc-merged-26.2.jar").normalize();
	private static final String MINECRAFT = "net/minecraft/client/Minecraft";
	private static final String MINECRAFT_NAME = "net.minecraft.client.Minecraft";
	private static final String HOOK_OWNER = "net/forbric/kernel/interop/ClientShutdown";

	@Test
	void hooksEveryReturnOfASyntheticCloseWithTwoExits() throws Exception {
		byte[] in = minecraftWithClose(true);
		byte[] out = transform(MINECRAFT_NAME, in);
		assertTrue(out != in);
		ClassNode node = parse(out);
		MethodNode close = method(node, "close");
		assertEquals(2, hookCallsBeforeReturns(close), "both exits hooked");
		assertEquals(2, countCalls(close, HOOK_OWNER, "stopLeakedBackgroundExecutors"), "and no extra call anywhere");
		InteropHookAssertions.assertEveryInteropCallResolves(out);
		new Analyzer<>(new BasicVerifier()).analyze(node.name, close);
	}

	@Test
	void theRealMergedCloseIsHookedAndStillVerifies() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent — skipping real-bytecode check");
		byte[] in = readClass(MINECRAFT + ".class");
		byte[] out = transform(MINECRAFT_NAME, in);
		assertTrue(out != in);
		ClassNode node = parse(out);
		MethodNode close = method(node, "close");
		assertTrue(hookCallsBeforeReturns(close) >= 1);
		assertEquals(hookCallsBeforeReturns(close), countCalls(close, HOOK_OWNER, "stopLeakedBackgroundExecutors"));
		InteropHookAssertions.assertEveryInteropCallResolves(out);
		new Analyzer<>(new BasicVerifier()).analyze(node.name, close);
	}

	@Test
	void theRealMergedDedicatedServerExitIsHookedAndStillVerifies() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent — skipping real-bytecode check");
		String server = "net/minecraft/server/dedicated/DedicatedServer";
		byte[] in = readClass(server + ".class");
		byte[] out = transform(server.replace('/', '.'), in);
		assertTrue(out != in);
		ClassNode node = parse(out);
		MethodNode exit = method(node, "onServerExit");
		assertTrue(hookCallsBeforeReturns(exit) >= 1);
		assertEquals(hookCallsBeforeReturns(exit), countCalls(exit, HOOK_OWNER, "stopLeakedBackgroundExecutors"));
		InteropHookAssertions.assertEveryInteropCallResolves(out);
		new Analyzer<>(new BasicVerifier()).analyze(node.name, exit);
	}

	@Test
	void otherClassesAndOtherMethodsAreLeftAlone() {
		byte[] other = minecraftWithClose(false);
		assertSame(other, transform("net.minecraft.client.NotMinecraft", other));
		ClassNode node = parse(transform(MINECRAFT_NAME, minecraftWithClose(false)));
		assertEquals(0, countCalls(method(node, "tick"), HOOK_OWNER, "stopLeakedBackgroundExecutors"));
	}

	// --- helpers -------------------------------------------------------------------------------------------------

	private static byte[] transform(String className, byte[] bytes) {
		return new ExitHookInjector().transform(className, bytes, null);
	}

	/** A stand-in Minecraft: close() with an if/else (two returns) when asked, plus an unrelated tick(). */
	private static byte[] minecraftWithClose(boolean twoExits) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MINECRAFT, null, "java/lang/Object", null);
		cw.visitField(Opcodes.ACC_PRIVATE, "flag", "Z", null, null).visitEnd();
		MethodVisitor close = cw.visitMethod(Opcodes.ACC_PUBLIC, "close", "()V", null, null);
		close.visitCode();
		if (twoExits) {
			Label other = new Label();
			close.visitVarInsn(Opcodes.ALOAD, 0);
			close.visitFieldInsn(Opcodes.GETFIELD, MINECRAFT, "flag", "Z");
			close.visitJumpInsn(Opcodes.IFEQ, other);
			close.visitInsn(Opcodes.RETURN);
			close.visitLabel(other);
		}
		close.visitInsn(Opcodes.RETURN);
		close.visitMaxs(0, 0);
		close.visitEnd();
		MethodVisitor tick = cw.visitMethod(Opcodes.ACC_PUBLIC, "tick", "()V", null, null);
		tick.visitCode();
		tick.visitInsn(Opcodes.RETURN);
		tick.visitMaxs(0, 0);
		tick.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static int hookCallsBeforeReturns(MethodNode m) {
		int hooked = 0;
		for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() != Opcodes.RETURN) continue;
			AbstractInsnNode prev = insn.getPrevious();
			while (prev != null && prev.getOpcode() < 0) prev = prev.getPrevious();
			if (prev instanceof MethodInsnNode call && HOOK_OWNER.equals(call.owner)) hooked++;
		}
		return hooked;
	}

	private static int countCalls(MethodNode m, String owner, String name) {
		int n = 0;
		for (AbstractInsnNode insn : m.instructions) {
			if (insn instanceof MethodInsnNode call && owner.equals(call.owner) && name.equals(call.name)) n++;
		}
		return n;
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static MethodNode method(ClassNode node, String name) {
		for (MethodNode m : node.methods) {
			if (m.name.equals(name)) return m;
		}
		throw new AssertionError("no method " + name + " in " + node.name + " (have " + names(node) + ")");
	}

	private static List<String> names(ClassNode node) {
		List<String> out = new ArrayList<>();
		for (MethodNode m : node.methods) out.add(m.name);
		return out;
	}

	private static byte[] readClass(String entry) throws Exception {
		try (ZipFile zip = new ZipFile(MERGED_BASE.toFile())) {
			ZipEntry found = zip.getEntry(entry);
			assertNotNull(found, entry + " missing from the staged merged base");
			try (InputStream in = zip.getInputStream(found)) {
				return in.readAllBytes();
			}
		}
	}
}
