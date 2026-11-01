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

import java.util.List;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.util.ForbricLog;

/**
 * Sends a received payload on a channel another ecosystem negotiated down vanilla's path, not into NeoForge's
 * dispatcher, which disconnects on any channel NeoForge did not register.
 *
 * <p>NeoForge's {@code ClientCommonPacketListenerImpl.handleCustomPayload} and its server twin are NeoForge's: a
 * payload outside the {@code minecraft} namespace ({@code NetworkRegistry.isModdedPayload}) goes to
 * {@code handleModdedPayload}, which looks the channel up in NeoForge's registry and closes the connection when it
 * is not there; anything else goes on to vanilla's {@code handleCustomPayload(CustomPacketPayload)}. A Fabric mod
 * with its own payload handling — Carpet, whose client takes {@code carpet:hello} at
 * {@code ClientPacketListener.handleUnknownCustomPayload} — was disconnected ("No Channel for carpet:hello")
 * before reaching it. Each {@code isModdedPayload} call there is followed by
 * {@code PayloadInterop.neoForgeDispatches(payload, answer)}, which keeps NeoForge's answer for every channel
 * NeoForge registered and says no for a channel only another ecosystem knows. {@code -Dforbric.foreignPayloadReceive=off}.
 */
public final class ForeignPayloadReceiveInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.foreignPayloadReceive";
	static final List<String> TARGETS = List.of("net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl",
			"net.minecraft.server.network.ServerCommonPacketListenerImpl");
	static final String INTEROP = "net/forbric/kernel/interop/PayloadInterop";
	static final String HOOK = "neoForgeDispatches";
	private static final String REGISTRY = ForeignType.NETWORK_REGISTRY.internal(Ecosystem.NEOFORGE);
	private static final String IS_MODDED_DESC = "(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)Z";

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override
	public String name() {
		return "forbric-foreign-payload-receive";
	}

	@Override
	public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("switched off by -D" + PROPERTY);
		return AnchorSet.of(
				new AnchorSet.Anchor(TARGETS.get(0), AnchorSet.Severity.REQUIRED,
						"a client receiving a payload on a channel only another ecosystem negotiated is disconnected"),
				new AnchorSet.Anchor(TARGETS.get(1), AnchorSet.Severity.REQUIRED,
						"a server receiving a payload on a channel only another ecosystem negotiated disconnects the player"));
	}

	@Override
	public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || bytes == null || bytes.length == 0 || !TARGETS.contains(className)) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		int sites = repair(node);
		if (sites == 0) return bytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		ForbricLog.info("[Forbric/Net] %s.handleCustomPayload sends a payload on a channel only another ecosystem "
				+ "negotiated down vanilla's path, where its mod listens, instead of into NeoForge's dispatcher, which "
				+ "disconnects on it (%d site(s))", className.substring(className.lastIndexOf('.') + 1), sites);
		return writer.toByteArray();
	}

	static int repair(ClassNode node) {
		int sites = 0;
		for (MethodNode m : node.methods) {
			if (!m.name.equals("handleCustomPayload") || m.desc.contains("CustomPacketPayload;)")) continue;
			for (AbstractInsnNode insn : m.instructions) {
				if (insn instanceof MethodInsnNode call && call.owner.equals(INTEROP) && call.name.equals(HOOK)) return 0;
			}
			for (AbstractInsnNode insn : m.instructions.toArray()) {
				if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC
						|| !call.owner.equals(REGISTRY) || !call.name.equals("isModdedPayload") || !call.desc.equals(IS_MODDED_DESC)) continue;
				m.instructions.insertBefore(call, new InsnNode(Opcodes.DUP));
				m.instructions.insert(call, new MethodInsnNode(Opcodes.INVOKESTATIC, INTEROP, HOOK, "(Ljava/lang/Object;Z)Z", false));
				sites++;
			}
		}
		return sites;
	}
}
