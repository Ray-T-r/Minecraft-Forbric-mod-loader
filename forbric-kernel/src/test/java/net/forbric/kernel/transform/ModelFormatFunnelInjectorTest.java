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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * The funnel against the REAL {@code UnbakedModelParser$Deserializer} out of the staged NeoForge carrier: one call,
 * in the one place that is ahead of every NeoForge decision about {@code "loader"} and behind its "is this an
 * object" check. What the call then DOES is {@code KernelModelFormatsTest}'s business.
 */
@org.junit.jupiter.api.parallel.ResourceLock("system-properties")
class ModelFormatFunnelInjectorTest {
	private static final Path STAGED =
			Path.of(System.getenv().getOrDefault("FORBRIC_OLD", System.getProperty("user.dir") + "/../forbric-loader"), "run").normalize();
	private static final Path NEO_CARRIER = STAGED.resolve("neoforge-runtime/neoforge-runtime.jar");
	private static final String ENTRY = ModelFormatFunnelInjector.TARGET + ".class";
	private static final String BINARY = ModelFormatFunnelInjector.TARGET.replace('/', '.');

	@AfterEach
	void reset() {
		System.clearProperty(ModelFormatFunnelInjector.PROPERTY);
	}

	@Test
	void theDeserializerAsksTheKernelOnceBeforeItReadsTheLoader() throws Exception {
		byte[] original = original();
		byte[] funnelled = new ModelFormatFunnelInjector().transform(BINARY, original, null);
		assertNotSame(original, funnelled, "the real NeoForge deserializer must be funnelled");

		AbstractInsnNode[] body = deserialize(funnelled).instructions.toArray();
		int calls = 0;
		int call = -1;
		int firstLoaderRead = -1;
		int objectStore = -1;
		for (int i = 0; i < body.length; i++) {
			if (body[i] instanceof MethodInsnNode m && ModelFormatFunnelInjector.FUNNEL_OWNER.equals(m.owner)) {
				assertEquals(Opcodes.INVOKESTATIC, m.getOpcode());
				assertEquals(ModelFormatFunnelInjector.FUNNEL, m.name);
				assertEquals(ModelFormatFunnelInjector.FUNNEL_DESC, m.desc);
				calls++;
				call = i;
			}
			if (firstLoaderRead < 0 && body[i] instanceof LdcInsnNode ldc && "loader".equals(ldc.cst)) firstLoaderRead = i;
			if (objectStore < 0 && body[i] instanceof MethodInsnNode m && "getAsJsonObject".equals(m.name)) {
				objectStore = java.util.Arrays.asList(body).indexOf(nextReal(m));
			}
		}
		assertEquals(1, calls, "exactly one question per model");
		assertTrue(objectStore < call && call < firstLoaderRead,
				"after NeoForge unwraps the object and before its first look at \"loader\" — anywhere later and an "
						+ "unknown loader has already thrown");
		assertEquals(Opcodes.ASTORE, body[objectStore].getOpcode());

		// The two arguments are the object NeoForge just stored and the context it was handed.
		VarInsnNode context = (VarInsnNode) previousReal(body[call]);
		VarInsnNode json = (VarInsnNode) previousReal(context);
		assertEquals(Opcodes.ALOAD, context.getOpcode());
		assertEquals(3, context.var, "the context NeoForge was handed");
		assertEquals(Opcodes.ALOAD, json.getOpcode());
		assertEquals(((VarInsnNode) body[objectStore]).var, json.var, "the object NeoForge just stored");

		// A non-null answer is returned; null leaves NeoForge's code exactly where it was.
		AbstractInsnNode dup = nextReal(body[call]);
		AbstractInsnNode ifNull = nextReal(dup);
		AbstractInsnNode ret = nextReal(ifNull);
		assertEquals(Opcodes.DUP, dup.getOpcode());
		assertEquals(Opcodes.IFNULL, ifNull.getOpcode());
		assertEquals(Opcodes.ARETURN, ret.getOpcode());
		assertEquals(Opcodes.POP, nextReal(ret).getOpcode());
	}

	/** Frames are the risky half of a branch insertion; the analyzer and the class writer both have to agree. */
	@Test
	void theFunnelledMethodStillVerifies() throws Exception {
		byte[] funnelled = new ModelFormatFunnelInjector().transform(BINARY, original(), null);
		ClassNode node = new ClassNode();
		new ClassReader(funnelled).accept(node, 0);
		for (MethodNode m : node.methods) new Analyzer<>(new BasicVerifier()).analyze(node.name, m);
		// Re-reading with frames and re-writing with none recomputed would throw on a malformed StackMapTable.
		new ClassReader(funnelled).accept(new ClassWriter(0), 0);
	}

	@Test
	void aSecondPassChangesNothing() throws Exception {
		byte[] once = new ModelFormatFunnelInjector().transform(BINARY, original(), null);
		assertSame(once, new ModelFormatFunnelInjector().transform(BINARY, once, null),
				"asking twice would parse a foreign model twice");
	}

	@Test
	void switchedOffTheDeserializerIsLeftAsShipped() throws Exception {
		System.setProperty(ModelFormatFunnelInjector.PROPERTY, "off");
		byte[] original = original();
		assertSame(original, new ModelFormatFunnelInjector().transform(BINARY, original, null));
	}

	/** A NeoForge that reads its key differently is not the method this was checked against: stand down whole. */
	@Test
	void aReshapedDeserializerIsLeftAlone() throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(original()).accept(node, 0);
		for (MethodNode m : node.methods) {
			for (AbstractInsnNode insn : m.instructions) {
				if (insn instanceof LdcInsnNode ldc && "loader".equals(ldc.cst)) ldc.cst = "neoforge:loader";
			}
		}
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		byte[] reshaped = writer.toByteArray();
		assertSame(reshaped, new ModelFormatFunnelInjector().transform(BINARY, reshaped, null));
	}

	@Test
	void otherClassesPassThrough() throws Exception {
		byte[] original = original();
		assertSame(original, new ModelFormatFunnelInjector().transform(
				"net.neoforged.neoforge.client.model.UnbakedModelParser", original, null));
	}

	private static MethodNode deserialize(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		for (MethodNode m : node.methods) {
			if (ModelFormatFunnelInjector.METHOD.equals(m.name) && ModelFormatFunnelInjector.DESC.equals(m.desc)) return m;
		}
		throw new AssertionError("no " + ModelFormatFunnelInjector.METHOD + ModelFormatFunnelInjector.DESC);
	}

	private static AbstractInsnNode nextReal(AbstractInsnNode insn) {
		AbstractInsnNode next = insn.getNext();
		while (next != null && next.getOpcode() < 0) next = next.getNext();
		return next;
	}

	private static AbstractInsnNode previousReal(AbstractInsnNode insn) {
		AbstractInsnNode previous = insn.getPrevious();
		while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
		return previous;
	}

	static byte[] original() throws IOException {
		assumeTrue(Files.isRegularFile(NEO_CARRIER), "staged NeoForge carrier absent");
		try (ZipFile zip = new ZipFile(NEO_CARRIER.toFile())) {
			ZipEntry entry = zip.getEntry(ENTRY);
			assumeTrue(entry != null, "UnbakedModelParser$Deserializer absent from this carrier");
			try (InputStream in = zip.getInputStream(entry)) {
				return in.readAllBytes();
			}
		}
	}
}
