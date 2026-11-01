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
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Routes every custom-payload codec build through vanilla's {@code CustomPacketPayload.codec(fallback, types)}, so
 * a mod hooking it is heard, while the codec built stays NeoForge's.
 *
 * <p>NeoForge's {@code CustomPacketPayload} has both overloads, each with its own body: vanilla's
 * {@code codec(FallbackProvider, List)} and NeoForge's {@code codec(FallbackProvider, List, ConnectionProtocol,
 * PacketFlow)}, which builds the protocol-aware codec NeoForge's networking needs. Every merged caller calls
 * NeoForge's, so vanilla's is declared and never run. A mod's {@code @At(INVOKE)} on the call is moved by
 * {@code MixinAtWidenedCall}; an injector INTO vanilla's method has nothing to move to. Carpet's is one: at its HEAD
 * it adds {@code carpet:hello} to the type list and re-calls the method with the longer list — so the type was in
 * no codec, and a dedicated server running Carpet failed to encode the hello and disconnected every player at login.
 *
 * <p>Two prologues, each a branch on whether a build is in progress on this thread
 * ({@code KernelPayloadCodecs.protocol() != null}):
 * <ul>
 *   <li>NeoForge's overload, entered from outside: {@code return KernelPayloadCodecs.through(...)}, which records
 *       the protocol and flow and calls vanilla's overload — where vanilla's injectors run, as on Fabric;</li>
 *   <li>vanilla's overload, inside such a build: {@code return codec(fallback, types, protocol, flow)} — NeoForge's,
 *       re-entered, which now falls through to its own body. Carpet's re-call lands here with its longer list.</li>
 * </ul>
 * Outside a build, vanilla's overload keeps its own body, as a mod calling it directly has always had. Both bodies
 * stay where they are, so an injector into either still finds its anchors. {@code -Dforbric.payloadCodecFunnel=off}.
 */
public final class PayloadCodecFunnelInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.payloadCodecFunnel";
	static final String TARGET = "net.minecraft.network.protocol.common.custom.CustomPacketPayload";
	static final String OWNER = "net/minecraft/network/protocol/common/custom/CustomPacketPayload";
	static final String RUNTIME = "net/forbric/kernel/runtime/KernelPayloadCodecs";
	private static final String FALLBACK = "L" + OWNER + "$FallbackProvider;";
	private static final String CODEC = "Lnet/minecraft/network/codec/StreamCodec;";
	private static final String PROTOCOL = "Lnet/minecraft/network/ConnectionProtocol;";
	private static final String FLOW = "Lnet/minecraft/network/protocol/PacketFlow;";
	static final String VANILLA_DESC = "(" + FALLBACK + "Ljava/util/List;)" + CODEC;
	static final String NEO_DESC = "(" + FALLBACK + "Ljava/util/List;" + PROTOCOL + FLOW + ")" + CODEC;

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override
	public String name() {
		return "forbric-payload-codec-funnel";
	}

	@Override
	public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("switched off by -D" + PROPERTY);
		return AnchorSet.of(new AnchorSet.Anchor(TARGET, AnchorSet.Severity.REQUIRED,
				"a mod hooking vanilla's CustomPacketPayload.codec is never called (Carpet's carpet:hello cannot be "
						+ "encoded and a dedicated server disconnects every player at login)"));
	}

	@Override
	public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || bytes == null || bytes.length == 0 || !TARGET.equals(className)) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
		if (!repair(node)) return bytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		ForbricLog.info("[Forbric/Network] custom-payload codecs are built through vanilla's CustomPacketPayload.codec "
				+ "again, so a mod hooking it adds its payload types (NeoForge's codec is still the one built)");
		return writer.toByteArray();
	}

	static boolean repair(ClassNode node) {
		MethodNode vanilla = null, neo = null;
		for (MethodNode m : node.methods) {
			if (!m.name.equals("codec") || (m.access & Opcodes.ACC_STATIC) == 0) continue;
			if (m.desc.equals(VANILLA_DESC)) vanilla = m;
			else if (m.desc.equals(NEO_DESC)) neo = m;
		}
		if (vanilla == null || neo == null || calls(vanilla, RUNTIME) || calls(neo, RUNTIME)) return false;
		// Each must build its own codec: one already delegating to the other is not the shape this was written for.
		if (calls(vanilla, OWNER, "codec") || calls(neo, OWNER, "codec")) return false;

		// NeoForge's: outside a build, go through vanilla's.
		InsnList outer = new InsnList();
		LabelNode own = new LabelNode();
		outer.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "protocol", "()" + PROTOCOL, false));
		outer.add(new JumpInsnNode(Opcodes.IFNONNULL, own));
		for (int slot = 0; slot < 4; slot++) outer.add(new VarInsnNode(Opcodes.ALOAD, slot));
		outer.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "through", NEO_DESC, false));
		outer.add(new InsnNode(Opcodes.ARETURN));
		outer.add(own);
		outer.add(entryFrame(PROTOCOL, FLOW));
		neo.instructions.insert(outer);

		// Vanilla's: inside a build, hand back to NeoForge's with the build's protocol and flow.
		InsnList inner = new InsnList();
		LabelNode plain = new LabelNode();
		inner.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "protocol", "()" + PROTOCOL, false));
		inner.add(new JumpInsnNode(Opcodes.IFNULL, plain));
		inner.add(new VarInsnNode(Opcodes.ALOAD, 0));
		inner.add(new VarInsnNode(Opcodes.ALOAD, 1));
		inner.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "protocol", "()" + PROTOCOL, false));
		inner.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "flow", "()" + FLOW, false));
		inner.add(new MethodInsnNode(Opcodes.INVOKESTATIC, OWNER, "codec", NEO_DESC, true));
		inner.add(new InsnNode(Opcodes.ARETURN));
		inner.add(plain);
		inner.add(entryFrame());
		vanilla.instructions.insert(inner);
		return true;
	}

	/** The method's entry state: the fallback, the type list and {@code more} as locals, nothing on the stack. */
	private static FrameNode entryFrame(String... more) {
		Object[] locals = new Object[2 + more.length];
		locals[0] = OWNER + "$FallbackProvider";
		locals[1] = "java/util/List";
		for (int i = 0; i < more.length; i++) locals[2 + i] = more[i].substring(1, more[i].length() - 1);
		return new FrameNode(Opcodes.F_NEW, locals.length, locals, 0, new Object[0]);
	}

	private static boolean calls(MethodNode method, String owner) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(owner)) return true;
		}
		return false;
	}

	private static boolean calls(MethodNode method, String owner, String name) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) return true;
		}
		return false;
	}
}
