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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * MinecraftForge writes a modified binding into options.txt as {@code key.keyboard.o:CONTROL_OR_COMMAND} and then
 * hands that whole string to {@code InputConstants.getKey} before splitting the modifier off — so vanilla's
 * {@code Integer.parseInt("o:CONTROL_OR_COMMAND")} throws. {@code Options.load} wraps the entire file in one
 * try/catch, so ONE modded binding with a modifier costs the player every setting they have. JEI binds three.
 *
 * <p>The behavioural half runs on a stand-in whose {@code getKey} does exactly what vanilla's does with the
 * suffix — {@code Integer.parseInt} of everything after the prefix — because loading the real one would drag in
 * GLFW. The shape half runs on the real merged base, so the method really is the one the repair claims.
 */
@org.junit.jupiter.api.parallel.ResourceLock("system-properties")
class MergedBaseKeyModifierSuffixTest {
	private static final Path MERGED_BASE =
			Path.of(System.getenv().getOrDefault("FORBRIC_OLD", System.getProperty("user.dir") + "/../forbric-loader"), "run", "merged-base",
					"patched-mc-merged-26.2.jar").normalize();
	private static final String INPUT_CONSTANTS = "com/mojang/blaze3d/platform/InputConstants";
	private static final String KEY = INPUT_CONSTANTS + "$Key";
	private static final String GET_KEY_DESC = "(Ljava/lang/String;)L" + KEY + ";";
	// A NUMERIC key name on purpose. The real getKey checks Key.NAME_MAP first, so "key.keyboard.o" resolves
	// there and only the SUFFIXED form falls through to parseInt; the stand-in has no map, so the numeric form is
	// the one that models both paths — suffix present, parse throws; suffix dropped, parse succeeds.
	private static final String MODIFIED = "key.keyboard.65:CONTROL_OR_COMMAND";

	@Test
	void unrepairedTheModifierSuffixThrowsTheWayVanillasParseDoes() throws Exception {
		Method getKey = load(standIn(), "getKey");
		assertNull(getKey.invoke(null, "key.keyboard.65"), "a plain name must still parse");
		InvocationTargetException boom = assertThrows(InvocationTargetException.class,
				() -> getKey.invoke(null, MODIFIED));
		assertEquals(NumberFormatException.class, boom.getCause().getClass(),
				"this is the throw that costs the player their whole options.txt");
	}

	@Test
	void repairedTheSuffixIsDroppedAndTheNameParses() throws Exception {
		Method getKey = load(transform(standIn()), "getKey");
		assertNull(getKey.invoke(null, MODIFIED), "the repaired method must reach vanilla's own parse");
		assertNull(getKey.invoke(null, "key.keyboard.65"), "a name without a modifier is unchanged");
	}

	@Test
	void theRealMergedBaseStillNeedsItAndStaysVerifiable() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent — skipping real-bytecode check");
		byte[] in = readClass(INPUT_CONSTANTS + ".class");
		byte[] out = transform(in);
		assertTrue(out != in, "the staged merged base must still need the repair — if it stopped, re-derive the test");

		ClassNode node = parse(out);
		MethodNode getKey = method(node, "getKey", GET_KEY_DESC);
		assertNotNull(getKey, "the repair names a method the merged base must declare");
		MethodInsnNode split = null;
		for (var insn : getKey.instructions) {
			if (insn instanceof MethodInsnNode call && "split".equals(call.name)) { split = call; break; }
		}
		assertNotNull(split, "the prologue must be there");
		assertEquals("java/lang/String", split.owner);
		assertEquals("(Ljava/lang/String;)[Ljava/lang/String;", split.desc);
		new Analyzer<>(new BasicVerifier()).analyze(node.name, getKey);
	}

	@Test
	void aSecondPassLeavesTheRepairedClassAlone() throws Exception {
		byte[] once = transform(standIn());
		assertSame(once, transform(once), "a method that already splits must not get a second prologue");
	}

	@Test
	void theSwitchHandsTheWholeValueBackToVanillasParse() {
		byte[] in = standIn();
		System.setProperty(ForbricMergedBaseCompatTransformer.KEY_SUFFIX_PROPERTY, "off");
		try {
			assertSame(in, transform(in), "-Dforbric.keyModifierSuffix=off must leave the method alone");
		} finally {
			System.clearProperty(ForbricMergedBaseCompatTransformer.KEY_SUFFIX_PROPERTY);
		}
	}

	@Test
	void otherClassesAreNotTouched() {
		ClassNode node = new ClassNode();
		node.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/mojang/blaze3d/platform/NotInputConstants", null,
				"java/lang/Object", null);
		node.methods.add(vanillaShapedGetKey());
		byte[] in = write(node);
		assertSame(in, new ForbricMergedBaseCompatTransformer()
				.transform(node.name.replace('/', '.'), in, null));
	}

	// --- helpers -------------------------------------------------------------------------------------------------

	/** {@code InputConstants} with vanilla's own shape: parseInt of everything after the "key.keyboard." prefix. */
	private static byte[] standIn() {
		ClassNode node = new ClassNode();
		node.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, INPUT_CONSTANTS, null, "java/lang/Object", null);
		node.methods.add(vanillaShapedGetKey());
		return write(node);
	}

	private static MethodNode vanillaShapedGetKey() {
		MethodNode getKey = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getKey", GET_KEY_DESC, null, null);
		getKey.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		getKey.instructions.add(new org.objectweb.asm.tree.IntInsnNode(Opcodes.BIPUSH, 13));
		getKey.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring",
				"(I)Ljava/lang/String;", false));
		getKey.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "parseInt",
				"(Ljava/lang/String;)I", false));
		getKey.instructions.add(new InsnNode(Opcodes.POP));
		getKey.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
		getKey.instructions.add(new InsnNode(Opcodes.ARETURN));
		getKey.maxStack = 2;
		getKey.maxLocals = 1;
		return getKey;
	}

	private static Method load(byte[] bytes, String name) throws Exception {
		Map<String, byte[]> defined = new HashMap<>();
		defined.put(INPUT_CONSTANTS.replace('/', '.'), bytes);
		// The return type has to resolve for getDeclaredMethod; an empty class is enough.
		ClassNode key = new ClassNode();
		key.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, KEY, null, "java/lang/Object", null);
		defined.put(KEY.replace('/', '.'), write(key));

		ClassLoader loader = new ClassLoader(MergedBaseKeyModifierSuffixTest.class.getClassLoader()) {
			@Override
			protected Class<?> findClass(String binary) throws ClassNotFoundException {
				byte[] body = defined.get(binary);
				if (body == null) throw new ClassNotFoundException(binary);
				return defineClass(binary, body, 0, body.length);
			}
		};
		return loader.loadClass(INPUT_CONSTANTS.replace('/', '.')).getDeclaredMethod(name, String.class);
	}

	private static byte[] transform(byte[] bytes) {
		return new ForbricMergedBaseCompatTransformer()
				.transform(INPUT_CONSTANTS.replace('/', '.'), bytes, null);
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

	private static byte[] write(ClassNode node) {
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static byte[] readClass(String entry) throws Exception {
		try (ZipFile zip = new ZipFile(MERGED_BASE.toFile())) {
			ZipEntry found = zip.getEntry(entry);
			assertNotNull(found, entry + " is not in the merged base");
			try (InputStream in = zip.getInputStream(found)) {
				return in.readAllBytes();
			}
		}
	}
}
