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
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * Covers the {@code Connection.setupOutboundProtocol} repair: MinecraftForge's channels read a Forge-added
 * {@code outboundProtocol} field to pick the packet type for an outgoing payload, the merge dropped the lambda that
 * kept it current, and a client Connection therefore reported HANDSHAKING for its whole life — every Forge channel
 * send from a Forbric client threw. The kernel stores the protocol at the head of the method instead.
 */
class MergedBaseConnectionProtocolTest {
	private static final Path MERGED_BASE =
			Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run", "merged-base",
					"patched-mc-merged-26.2.jar").normalize();

	private static final String CONNECTION = "net/minecraft/network/Connection";
	private static final String PROTOCOL_INFO = "Lnet/minecraft/network/ProtocolInfo;";
	private static final String SETUP = "setupOutboundProtocol";
	private static final String SETUP_DESC = "(" + PROTOCOL_INFO + ")V";

	@Test
	void theMergedSetupStoresForgesOutboundProtocolFirst() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent — skipping real-bytecode check");
		byte[] in = readClass(CONNECTION + ".class");
		byte[] out = transform(in);
		assertTrue(out != in, "the staged merged base must still need the repair — if it stopped, re-derive the test");

		ClassNode node = parse(out);
		MethodNode setup = method(node, SETUP, SETUP_DESC);
		assertNotNull(setup);
		assertStoresFieldFirst(setup);
		assertTrue(setup.maxStack >= 2, "the store needs two stack slots");
		new Analyzer<>(new BasicVerifier()).analyze(node.name, setup);
	}

	@Test
	void aSecondPassLeavesTheRepairedClassAlone() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent — skipping real-bytecode check");
		byte[] once = transform(readClass(CONNECTION + ".class"));
		assertSame(once, transform(once), "a body that already stores the field is coherent and must not be touched");
	}

	@Test
	void aBodyWithNoStoreGetsOne() {
		byte[] in = connection(setupWithBody(new InsnNode(Opcodes.RETURN)), null);
		byte[] out = transform(in);
		assertTrue(out != in);
		assertStoresFieldFirst(method(parse(out), SETUP, SETUP_DESC));
	}

	@Test
	void aBodyThatStillStoresTheFieldIsLeftAlone() {
		byte[] in = connection(setupWithBody(
				new VarInsnNode(Opcodes.ALOAD, 0), new VarInsnNode(Opcodes.ALOAD, 1),
				new FieldInsnNode(Opcodes.PUTFIELD, CONNECTION, "outboundProtocol", PROTOCOL_INFO),
				new InsnNode(Opcodes.RETURN)), null);
		assertSame(in, transform(in));
	}

	@Test
	void aBodyThatStillChainsForgesLambdaIsLeftAlone() {
		// Forge's own shape: the store lives in a lambda the method chains onto the pipeline task.
		MethodNode lambda = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC, "lambda$setupOutboundProtocol$0",
				"(" + PROTOCOL_INFO + "Lio/netty/channel/ChannelHandlerContext;)V", null, null);
		lambda.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		lambda.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
		lambda.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, CONNECTION, "outboundProtocol", PROTOCOL_INFO));
		lambda.instructions.add(new InsnNode(Opcodes.RETURN));
		lambda.maxStack = 2;
		lambda.maxLocals = 3;

		Handle metafactory = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
				"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
						+ "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
						+ "Ljava/lang/invoke/CallSite;", false);
		Handle impl = new Handle(Opcodes.H_INVOKESPECIAL, CONNECTION, lambda.name, lambda.desc, false);
		InvokeDynamicInsnNode chain = new InvokeDynamicInsnNode("run",
				"(L" + CONNECTION + ";" + PROTOCOL_INFO + ")Ljava/util/function/Consumer;", metafactory,
				Type.getMethodType("(Ljava/lang/Object;)V"), impl,
				Type.getMethodType("(Lio/netty/channel/ChannelHandlerContext;)V"));
		byte[] in = connection(setupWithBody(
				new VarInsnNode(Opcodes.ALOAD, 0), new VarInsnNode(Opcodes.ALOAD, 1), chain,
				new InsnNode(Opcodes.POP), new InsnNode(Opcodes.RETURN)), lambda);
		assertSame(in, transform(in));
	}

	@Test
	void otherClassesAreNotTouched() {
		ClassNode node = new ClassNode();
		node.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "net/minecraft/network/NotConnection", null, "java/lang/Object", null);
		node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "outboundProtocol", PROTOCOL_INFO, null, null));
		node.methods.add(setupWithBody(new InsnNode(Opcodes.RETURN)));
		byte[] in = write(node);
		assertSame(in, new ForbricMergedBaseCompatTransformer().transform(node.name.replace('/', '.'), in, null));
	}

	// --- helpers -------------------------------------------------------------------------------------------------

	private static void assertStoresFieldFirst(MethodNode setup) {
		List<AbstractInsnNode> head = realInsns(setup, 3);
		assertEquals(3, head.size());
		assertEquals(Opcodes.ALOAD, head.get(0).getOpcode());
		assertEquals(0, ((VarInsnNode) head.get(0)).var);
		assertEquals(Opcodes.ALOAD, head.get(1).getOpcode());
		assertEquals(1, ((VarInsnNode) head.get(1)).var);
		assertEquals(Opcodes.PUTFIELD, head.get(2).getOpcode());
		FieldInsnNode store = (FieldInsnNode) head.get(2);
		assertEquals(CONNECTION, store.owner);
		assertEquals("outboundProtocol", store.name);
		assertEquals(PROTOCOL_INFO, store.desc);
	}

	private static List<AbstractInsnNode> realInsns(MethodNode method, int count) {
		List<AbstractInsnNode> out = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) {
			if (insn.getOpcode() < 0) continue;
			out.add(insn);
			if (out.size() == count) break;
		}
		return out;
	}

	private static MethodNode setupWithBody(AbstractInsnNode... body) {
		MethodNode setup = new MethodNode(Opcodes.ACC_PUBLIC, SETUP, SETUP_DESC, null, null);
		for (AbstractInsnNode insn : body) setup.instructions.add(insn);
		setup.maxStack = 2;
		setup.maxLocals = 2;
		return setup;
	}

	/** A stand-in {@code Connection}: the Forge field, the method under test, optionally Forge's lambda. */
	private static byte[] connection(MethodNode setup, MethodNode lambda) {
		ClassNode node = new ClassNode();
		node.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, CONNECTION, null, "java/lang/Object", null);
		node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "outboundProtocol", PROTOCOL_INFO, null, null));
		node.methods.add(setup);
		if (lambda != null) node.methods.add(lambda);
		return write(node);
	}

	private static byte[] write(ClassNode node) {
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static byte[] transform(byte[] bytes) {
		return new ForbricMergedBaseCompatTransformer().transform(CONNECTION.replace('/', '.'), bytes, null);
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
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

	private static MethodNode method(ClassNode node, String name, String desc) {
		for (MethodNode m : node.methods) {
			if (m.name.equals(name) && m.desc.equals(desc)) return m;
		}
		return null;
	}
}
