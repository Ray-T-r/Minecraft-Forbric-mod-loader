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

import java.util.ArrayList;


import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Covers the repair that got a client with Polymer into a world.
 *
 * <p>Fabric binds its {@code PacketContext} around {@code PacketEncoder.encode}. NeoForge's
 * {@code GenericPacketSplitter} is a second encoder earlier in the same pipeline, so a Fabric codec reading the
 * context from inside it gets null — Polymer's ingredient codec does, for every recipe, and
 * {@code update_recipes} failed to encode with "Internal Exception" on the disconnect screen.
 *
 * <p>The body is MOVED rather than edited, which is the part worth pinning: a {@code ScopedValue} carrier and its
 * {@code Runnable} are one expression in Java and a synthetic class in bytecode, so the wrapper delegates and the
 * scope is established where it can be read.
 */
class SplitterPacketContextInjectorTest {
	private static final String SPLITTER = "net.neoforged.neoforge.network.filters.GenericPacketSplitter";
	private static final String ENCODE_DESC =
			"(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Ljava/util/List;)V";
	private static final String BODY = "encode$forbriccontext";

	@Test
	void theBodyIsMovedAsideAndTheNewEncodeDelegates() {
		ClassNode after = parse(transform(splitter()));

		MethodNode body = method(after, BODY);
		assertNotNull(body, "the original encode must survive under the kernel's name");
		assertEquals(ENCODE_DESC, body.desc, "with its own descriptor, so the hook can find it by arity");
		assertTrue(calls(body, "net/minecraft/network/protocol/Packet"), "and with its own code");

		MethodNode wrapper = method(after, "encode");
		assertNotNull(wrapper, "and a new encode must take its place");
		MethodInsnNode hook = null;
		for (AbstractInsnNode insn : wrapper.instructions.toArray()) {
			if (insn instanceof MethodInsnNode call
					&& "net/forbric/kernel/runtime/KernelPacketContext".equals(call.owner)) {
				hook = call;
			}
		}
		assertNotNull(hook, "which delegates to the kernel");
		assertEquals("encodeInFabricContext", hook.name);
		assertEquals("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V", hook.desc,
				"every parameter as Object — netty is not on the runtime source set's compile path");
	}

	/** Twice would wrap the wrapper, and the splitter would encode each packet through two scopes. */
	@Test
	void aSecondPassLeavesTheMovedClassAlone() {
		byte[] once = transform(splitter());
		assertSame(once, transform(once), "a class whose body has already been moved must not be wrapped again");
	}

	@Test
	void anyOtherClassIsUntouched() {
		byte[] bytes = splitter();
		assertSame(bytes, new SplitterPacketContextInjector()
				.transform("net.minecraft.network.PacketEncoder", bytes, null));
	}

	private static byte[] transform(byte[] bytes) {
		return new SplitterPacketContextInjector().transform(SPLITTER, bytes, null);
	}

	/** A stand-in with the same class name and the same encode signature. */
	private static byte[] splitter() {
		ClassNode node = new ClassNode();
		node.version = Opcodes.V21;
		node.access = Opcodes.ACC_PUBLIC;
		node.name = SPLITTER.replace('.', '/');
		node.superName = "java/lang/Object";
		node.methods = new ArrayList<>();

		MethodNode encode = new MethodNode(Opcodes.ACC_PROTECTED, "encode", ENCODE_DESC, null,
				new String[] { "java/lang/Exception" });
		encode.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "net/minecraft/network/protocol/Packet",
				"marker", "()V", true));
		encode.instructions.add(new InsnNode(Opcodes.RETURN));
		encode.maxStack = 1;
		encode.maxLocals = 4;
		node.methods.add(encode);

		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static MethodNode method(ClassNode node, String name) {
		for (MethodNode method : node.methods) {
			if (name.equals(method.name)) return method;
		}
		return null;
	}

	private static boolean calls(MethodNode method, String owner) {
		for (AbstractInsnNode insn : method.instructions.toArray()) {
			if (insn instanceof MethodInsnNode call && owner.equals(call.owner)) return true;
		}
		return false;
	}

}
