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
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * MinecraftForge's {@code ForgeRegistries.<clinit>} calls {@code Bootstrap.bootStrap()}, and on the merged game it is
 * first reached from inside bootstrap itself. Vanilla's one {@code return} is then reached twice — once by the nested
 * call half-way through bootstrap and once by the real one — and a mixin's TAIL handler runs twice. cristellib
 * freezes its registries there and throws the second time, so the server never starts.
 *
 * <p>The behavioural half runs a stand-in with vanilla's exact guard shape that re-enters itself from its body, as
 * {@code ForgeRegistries} does, and counts TAIL and RETURN the way Mixin places them (TAIL: before the last
 * {@code return}; RETURN: before every one). The shape half runs on the real merged base.
 */
class MergedBaseNestedBootstrapTest {
	private static final Path MERGED_BASE =
			Path.of(System.getenv().getOrDefault("FORBRIC_OLD", System.getProperty("user.dir") + "/../forbric-loader"), "run", "merged-base",
					"patched-mc-merged-26.2.jar").normalize();
	private static final String BOOTSTRAP = "net/minecraft/server/Bootstrap";

	@Test
	void unrepairedTheNestedCallRunsTheTailHandlerHalfWayThroughBootstrap() throws Exception {
		Class<?> bootstrap = load(withTailAndReturnCounters(standIn()));
		bootstrap.getMethod("bootStrap").invoke(null);
		assertEquals(1, get(bootstrap, "bodies"), "the body runs once either way");
		assertEquals(2, get(bootstrap, "tails"), "this is the double TAIL that costs cristellib the server");
	}

	@Test
	void repairedTheTailHandlerRunsOnceAndOnlyAfterTheBody() throws Exception {
		Class<?> bootstrap = load(withTailAndReturnCounters(transform(standIn())));
		bootstrap.getMethod("bootStrap").invoke(null);
		assertEquals(1, get(bootstrap, "bodies"));
		assertEquals(1, get(bootstrap, "tails"), "TAIL belongs to the call that bootstrapped");
		assertEquals(1, get(bootstrap, "bodiesSeenByTail"), "and it runs after the body, not half-way through");
		assertEquals(2, get(bootstrap, "returns"), "an @At(\"RETURN\") handler still sees every call");

		bootstrap.getMethod("bootStrap").invoke(null);
		assertEquals(1, get(bootstrap, "tails"), "a later, already-bootstrapped call does not reach TAIL either");
		assertEquals(3, get(bootstrap, "returns"));
	}

	@Test
	void aSecondPassLeavesTheRepairedClassAlone() {
		byte[] once = transform(standIn());
		assertTrue(once != standIn(), "the stand-in has the shape the repair is about");
		assertSame(once, transform(once), "an already-early return must not get a second one");
	}

	@Test
	void theRealMergedBaseStillNeedsItAndStaysVerifiable() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent — skipping real-bytecode check");
		byte[] in = readClass(BOOTSTRAP + ".class");
		byte[] out = transform(in);
		assertTrue(out != in, "the staged merged base must still need the repair — if it stopped, re-derive the test");

		ClassNode node = parse(out);
		MethodNode bootStrap = method(node, "bootStrap", "()V");
		assertNotNull(bootStrap);
		List<AbstractInsnNode> real = new ArrayList<>();
		for (AbstractInsnNode insn : bootStrap.instructions) if (insn.getOpcode() >= 0) real.add(insn);
		assertEquals(Opcodes.GETSTATIC, real.get(0).getOpcode());
		assertEquals("isBootstrapped", ((FieldInsnNode) real.get(0)).name);
		assertEquals(Opcodes.IFEQ, real.get(1).getOpcode(), "the guard now jumps INTO the body");
		assertEquals(Opcodes.RETURN, real.get(2).getOpcode(), "and falls through to its own early return");
		long returns = real.stream().filter(i -> i.getOpcode() == Opcodes.RETURN).count();
		assertEquals(2, returns, "the early return, and the one TAIL names");
		new Analyzer<>(new BasicVerifier()).analyze(node.name, bootStrap);
	}

	@Test
	void otherClassesAreNotTouched() {
		ClassNode node = parse(standIn());
		node.name = "net/minecraft/server/NotBootstrap";
		byte[] in = write(node, 0);
		assertSame(in, new ForbricMergedBaseCompatTransformer().transform(node.name.replace('/', '.'), in, null));
	}

	// --- helpers -------------------------------------------------------------------------------------------------

	/**
	 * Vanilla's guard, a body that counts itself and then re-enters {@code bootStrap()} the way
	 * {@code ForgeRegistries.init()} does, and vanilla's single return.
	 */
	private static byte[] standIn() {
		ClassNode node = new ClassNode();
		node.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, BOOTSTRAP, null, "java/lang/Object", null);
		for (String field : new String[] {"bodies", "tails", "returns", "bodiesSeenByTail"}) {
			node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, field, "I", null, null));
		}
		node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE,
				"isBootstrapped", "Z", null, null));
		MethodNode bootStrap = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "bootStrap", "()V", null, null);
		LabelNode done = new LabelNode();
		InsnList code = bootStrap.instructions;
		code.add(new FieldInsnNode(Opcodes.GETSTATIC, BOOTSTRAP, "isBootstrapped", "Z"));
		code.add(new JumpInsnNode(Opcodes.IFNE, done));
		code.add(new InsnNode(Opcodes.ICONST_1));
		code.add(new FieldInsnNode(Opcodes.PUTSTATIC, BOOTSTRAP, "isBootstrapped", "Z"));
		code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BOOTSTRAP, "bootStrap", "()V", false));
		code.add(increment("bodies"));
		code.add(done);
		code.add(new InsnNode(Opcodes.RETURN));
		node.methods.add(bootStrap);
		return write(node, ClassWriter.COMPUTE_FRAMES);
	}

	/** Mixin's placement: TAIL before the last return only, RETURN before each. Frames recomputed, as Mixin does. */
	private static byte[] withTailAndReturnCounters(byte[] bytes) {
		ClassNode node = parse(bytes);
		MethodNode bootStrap = method(node, "bootStrap", "()V");
		List<AbstractInsnNode> returns = new ArrayList<>();
		for (AbstractInsnNode insn : bootStrap.instructions) if (insn.getOpcode() == Opcodes.RETURN) returns.add(insn);
		for (AbstractInsnNode ret : returns) bootStrap.instructions.insertBefore(ret, increment("returns"));
		AbstractInsnNode tail = returns.get(returns.size() - 1);
		InsnList handler = increment("tails");
		handler.add(new FieldInsnNode(Opcodes.GETSTATIC, BOOTSTRAP, "bodies", "I"));
		handler.add(new FieldInsnNode(Opcodes.PUTSTATIC, BOOTSTRAP, "bodiesSeenByTail", "I"));
		bootStrap.instructions.insertBefore(tail, handler);
		return write(node, ClassWriter.COMPUTE_FRAMES);
	}

	private static InsnList increment(String field) {
		InsnList list = new InsnList();
		list.add(new FieldInsnNode(Opcodes.GETSTATIC, BOOTSTRAP, field, "I"));
		list.add(new InsnNode(Opcodes.ICONST_1));
		list.add(new InsnNode(Opcodes.IADD));
		list.add(new FieldInsnNode(Opcodes.PUTSTATIC, BOOTSTRAP, field, "I"));
		return list;
	}

	private static Class<?> load(byte[] bytes) throws Exception {
		ClassLoader loader = new ClassLoader(MergedBaseNestedBootstrapTest.class.getClassLoader()) {
			@Override
			protected Class<?> findClass(String binary) throws ClassNotFoundException {
				if (!binary.equals(BOOTSTRAP.replace('/', '.'))) throw new ClassNotFoundException(binary);
				return defineClass(binary, bytes, 0, bytes.length);
			}
		};
		return loader.loadClass(BOOTSTRAP.replace('/', '.'));
	}

	private static int get(Class<?> type, String name) throws Exception {
		Field field = type.getField(name);
		return field.getInt(null);
	}

	private static byte[] transform(byte[] bytes) {
		return new ForbricMergedBaseCompatTransformer().transform(BOOTSTRAP.replace('/', '.'), bytes, null);
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		for (MethodNode m : node.methods) if (m.name.equals(name) && m.desc.equals(desc)) return m;
		return null;
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] write(ClassNode node, int flags) {
		ClassWriter writer = new ClassWriter(flags);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static byte[] readClass(String entry) throws Exception {
		try (ZipFile zip = new ZipFile(MERGED_BASE.toFile())) {
			ZipEntry found = zip.getEntry(entry);
			assertNotNull(found, entry);
			try (InputStream in = zip.getInputStream(found)) {
				return in.readAllBytes();
			}
		}
	}
}
