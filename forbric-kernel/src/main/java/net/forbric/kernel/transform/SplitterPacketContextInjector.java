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

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Runs NeoForge's packet splitter inside the connection's Fabric packet context.
 *
 * <p>Fabric's networking binds a {@code PacketContext} as a {@code ScopedValue} around
 * {@code PacketEncoder.encode}, and a Fabric codec reads it with {@code PacketContext.get()}. NeoForge's
 * {@code GenericPacketSplitter} is a SECOND encoder, earlier in the same pipeline, which encodes each packet
 * itself to measure it — outside that scope, where {@code get()} answers null.
 *
 * <p>On a 97-jar pack that was the last thing between the client and a world. Polymer's ingredient codec reads
 * the context for every recipe it writes, so {@code update_recipes} failed to encode on an NPE thrown from inside
 * NeoForge's splitter, and the player saw "Internal Exception" and a disconnect. Neither ecosystem is wrong on
 * its own; the pipeline that contains both is where the hole is.
 *
 * <p>The original body is moved aside rather than edited, and a new {@code encode} delegates to
 * {@code KernelPacketContext}, which binds the context the connection's own {@code PacketEncoder} already holds
 * and then calls the body. Moving rather than wrapping in place is what lets the scope be established in Java,
 * where a {@code ScopedValue} carrier and its {@code Runnable} are one expression instead of a synthetic class.
 *
 * <p>{@code -Dforbric.splitterPacketContext=off} leaves the splitter encoding exactly where it did.
 */
public final class SplitterPacketContextInjector implements ClassTransformer {
	private static final String SPLITTER = "net.neoforged.neoforge.network.filters.GenericPacketSplitter";
	private static final String HOOK_OWNER = "net/forbric/kernel/runtime/KernelPacketContext";
	private static final String HOOK_NAME = "encodeInFabricContext";
	private static final String BODY_NAME = "encode$forbriccontext";
	private static final String ENCODE_DESC =
			"(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Ljava/util/List;)V";
	// Every parameter as Object: netty is not on the runtime source set's compile path, and the hook only hands
	// these straight back to the body it moved aside.
	private static final String HOOK_DESC =
			"(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V";

	@Override
	public String name() {
		return "forbric-splitter-packet-context";
	}

	@Override
	public AnchorSet anchors() {
		return AnchorSet.of(new AnchorSet.Anchor(SPLITTER, AnchorSet.Severity.REQUIRED,
				"a Fabric codec that reads PacketContext.get() sees null inside NeoForge's splitter — Polymer's "
						+ "ingredient codec does, and the recipe packet then fails to encode at world join"));
	}

	/** The switch, read here as well as in the hook so that off means the class is not touched at all. */
	public static final String PROPERTY = "forbric.splitterPacketContext";

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (!enabled() || classBytes == null || classBytes.length == 0 || !SPLITTER.equals(className)) {
			return classBytes;
		}

		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);

		MethodNode encode = null;
		for (MethodNode method : node.methods) {
			// Already moved: a second pass must not wrap the wrapper.
			if (BODY_NAME.equals(method.name)) return classBytes;
			if ("encode".equals(method.name) && ENCODE_DESC.equals(method.desc)) encode = method;
		}
		if (encode == null) return classBytes;

		// The body keeps its code, its exceptions and its access, under a name only the kernel calls.
		encode.name = BODY_NAME;

		MethodNode wrapper = new MethodNode(Opcodes.ACC_PROTECTED, "encode", ENCODE_DESC, null,
				new String[] { "java/lang/Exception" });
		wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
		wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
		wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
		wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER, HOOK_NAME, HOOK_DESC, false));
		wrapper.instructions.add(new InsnNode(Opcodes.RETURN));
		wrapper.maxStack = 4;
		wrapper.maxLocals = 4;
		node.methods.add(wrapper);

		ForbricLog.info("[Forbric/Net] %s.encode now runs inside the connection's Fabric packet context — it is a "
				+ "second encoder in the same pipeline as PacketEncoder, and a Fabric codec reading "
				+ "PacketContext.get() from in here got null", className);

		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}
}
