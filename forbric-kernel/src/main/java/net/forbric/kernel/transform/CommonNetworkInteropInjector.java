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
import java.util.Set;

import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Arbitrates the common-networking channel that Fabric and NeoForge <em>both</em> claim on a tri-in-one instance.
 *
 * <p>Both ecosystems implement the cross-loader "Common Networking" spec — version + channel negotiation over the
 * {@code c:version}/{@code c:register} wire channels — each with its OWN payload class registered for the same id.
 * On a normal instance only one ecosystem is present, so only one registration exists; on Forbric both do, and the
 * decode registry hands a {@code net.neoforged.…CommonVersionPayload} to Fabric's addon, whose handler casts it to
 * {@code net.fabricmc.…CommonVersionPayload} → {@code ClassCastException} → the client is kicked "The server sent an
 * invalid packet" right after reaching the world.
 *
 * <p>The translation logic already lives in {@code ForbricCustomPayloadInterop} (reused from the differential
 * oracle, on the boot classpath): given the addon and the incoming payload it runs the negotiation for BOTH stacks
 * — extracting the version, feeding Fabric's {@code onCommonVersionPacket} and NeoForge's {@code checkCommonVersion}
 * — and reports whether it fully handled the packet. This injector installs the two call sites the oracle used to
 * reach via mixins, but as kernel-native head injections (the kernel authors no mixins of its own):
 * <ul>
 *   <li>{@code AbstractChanneledNetworkAddon.handle(CustomPacketPayload)} — a guest Fabric class the kernel's
 *       transforming loader also defines. If the interop reports the packet handled, return its verdict before
 *       Fabric's {@code receive} can miscast it. This is the one that stops the client CCE.</li>
 *   <li>{@code ServerConfigurationPacketListenerImpl.finishCurrentTask(ConfigurationTask.Type)} — the two stacks
 *       expose different {@code ConfigurationTask.Type}s for the same handshake, so accept either owner at the
 *       task-completion boundary.</li>
 * </ul>
 *
 * <p>The interop methods take {@code Object} parameters, so the injected {@code INVOKESTATIC} can pass {@code this}
 * and the argument as-is — no need for the boot-side hook to name {@code net.minecraft}/{@code net.fabricmc} types
 * (the same widening-reference trick {@link ClientPackHookInjector} relies on).
 */
public final class CommonNetworkInteropInjector implements ClassTransformer {
	private static final String INTEROP = "net/forbric/loader/impl/compat/ForbricCustomPayloadInterop";

	/**
	 * Every Fabric addon class that DECLARES its own {@code handle(CustomPacketPayload)}.
	 *
	 * <p>It was originally just {@code AbstractChanneledNetworkAddon}, on the reasonable assumption that one
	 * injection into the base class covers every addon. It does not. The play addons inherit {@code handle} and so
	 * were covered; both CONFIGURATION addons override it, and an override is not reached by a prologue spliced
	 * into the superclass — so the whole configuration phase ran with no cross-ecosystem translation at all.
	 *
	 * <p>Nothing caught it because the symptom this shim was written for ("invalid packet" right after reaching the
	 * world) is a PLAY-phase symptom, and until gate-m12 no test ever reached the configuration phase over a
	 * socket: singleplayer negotiates in memory, and {@code RegistrySyncManager.configureClient} returns early for
	 * the singleplayer owner. The configuration-phase cost was a server kicking its own client with "This server
	 * requires Fabric Loader and Fabric API installed on your client!" — because the server's
	 * {@code minecraft:register} reached the client as NeoForge's payload type, the client's Fabric addon did not
	 * recognise it, never replied, and Fabric scored the peer NOT_RECEIVED.
	 */
	private static final Set<String> FABRIC_ADDONS = Set.of(
			"net.fabricmc.fabric.impl.networking.AbstractChanneledNetworkAddon",
			"net.fabricmc.fabric.impl.networking.client.ClientConfigurationNetworkAddon",
			"net.fabricmc.fabric.impl.networking.server.ServerConfigurationNetworkAddon");
	private static final String HANDLE = "handle";
	private static final String HANDLE_DESC = "(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)Z";
	private static final String HANDLE_HOOK = "handleFabricChannelRegistrationAddon";
	private static final String HANDLE_HOOK_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Boolean;";

	private static final String CUSTOM_PAYLOAD = "net/minecraft/network/protocol/common/custom/CustomPacketPayload";

	private static final String CLIENT_CONFIG_LISTENER = "net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl";
	private static final String HANDLE_PAYLOAD = "handleCustomPayload";
	private static final String HANDLE_PAYLOAD_DESC = "(Lnet/minecraft/network/protocol/common/ClientboundCustomPayloadPacket;)V";
	private static final String NEO_PACKAGE = "net/neoforged/";
	private static final String NEO_SEND_INITIAL_CHANNELS = "sendInitialListeningChannels";

	private static final String SERVER_CONFIG = "net.minecraft.server.network.ServerConfigurationPacketListenerImpl";
	private static final String FINISH_TASK = "finishCurrentTask";
	private static final String FINISH_TASK_DESC = "(Lnet/minecraft/server/network/ConfigurationTask$Type;)V";
	private static final String CONFIG_TASK_TYPE = "net/minecraft/server/network/ConfigurationTask$Type";
	private static final String FINISH_HOOK = "finishEquivalentCommonTask";
	private static final String FINISH_HOOK_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Z";

	@Override
	public String name() {
		return "forbric-common-network-interop";
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0) return classBytes;
		boolean fabricAddon = FABRIC_ADDONS.contains(className);
		boolean serverConfig = SERVER_CONFIG.equals(className);
		boolean clientConfig = CLIENT_CONFIG_LISTENER.equals(className);
		if (!fabricAddon && !serverConfig && !clientConfig) return classBytes;

		ClassNode node = new ClassNode();
		// EXPAND_FRAMES so every original frame is an absolute F_NEW node; the explicit frames we author at our own
		// branch targets then slot in consistently, and ClassWriter can serialize the StackMapTable WITHOUT
		// COMPUTE_FRAMES — whose getCommonSuperClass would try to load game classes through the wrong loader.
		new ClassReader(classBytes).accept(node, ClassReader.EXPAND_FRAMES);

		boolean changed = false;
		for (MethodNode m : node.methods) {
			if (fabricAddon && m.name.equals(HANDLE) && m.desc.equals(HANDLE_DESC)) {
				m.instructions.insert(handleAddonPrologue(node.name));
				bumpStack(m, 2);
				changed = true;
				ForbricLog.info("[Forbric/Net] arbitrating common-networking channel at %s.%s — Fabric addon defers to "
						+ "the cross-ecosystem negotiator before it can miscast a NeoForge payload", className, HANDLE);
			} else if (serverConfig && m.name.equals(FINISH_TASK) && m.desc.equals(FINISH_TASK_DESC)) {
				m.instructions.insert(finishTaskPrologue(node.name));
				bumpStack(m, 2);
				changed = true;
				ForbricLog.info("[Forbric/Net] treating Fabric/NeoForge common-networking tasks as equivalent at %s.%s",
						className, FINISH_TASK);
			} else if (clientConfig && m.name.equals(HANDLE_PAYLOAD) && m.desc.equals(HANDLE_PAYLOAD_DESC)) {
				if (shareMinecraftRegisterWithSuper(m)) {
					changed = true;
					ForbricLog.info("[Forbric/Net] letting minecraft:register reach BOTH stacks at %s.%s, Fabric first — "
							+ "NeoForge's override answered it alone and returned, and Fabric's server treats the first "
							+ "register as the whole declaration", className, HANDLE_PAYLOAD);
				} else {
					ForbricLog.warn("[Forbric/Net] %s.%s no longer swallows minecraft:register the way this fix "
							+ "expects; leaving it alone", className, HANDLE_PAYLOAD);
				}
			}
		}
		if (!changed) return classBytes;

		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	/**
	 * {@code Boolean r = interop.handleFabricChannelRegistrationAddon(this, payload); if (r != null) return
	 * r.booleanValue();} — prepended so the negotiator sees the packet before Fabric's own {@code receive} casts it.
	 * At the fall-through label the stack still holds the (null) Boolean and both params are live, so the frame is
	 * locals=[this, CustomPacketPayload] / stack=[Boolean]; POP it and the original body runs at its entry frame.
	 */
	private static InsnList handleAddonPrologue(String owner) {
		InsnList body = new InsnList();
		LabelNode notHandled = new LabelNode();
		body.add(new VarInsnNode(Opcodes.ALOAD, 0)); // this (the addon)
		body.add(new VarInsnNode(Opcodes.ALOAD, 1)); // the payload
		body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, INTEROP, HANDLE_HOOK, HANDLE_HOOK_DESC, false));
		body.add(new InsnNode(Opcodes.DUP));                       // [Boolean, Boolean]
		body.add(new JumpInsnNode(Opcodes.IFNULL, notHandled));    // null -> fall through to original body
		body.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false));
		body.add(new InsnNode(Opcodes.IRETURN));
		body.add(notHandled);
		body.add(new FrameNode(Opcodes.F_NEW, 2, new Object[] {owner, CUSTOM_PAYLOAD}, 1,
				new Object[] {"java/lang/Boolean"}));
		body.add(new InsnNode(Opcodes.POP));                       // discard the null Boolean, run the original body
		return body;
	}

	/**
	 * {@code if (interop.finishEquivalentCommonTask(this, type)) return;} — prepended to the void method. At the
	 * fall-through label the stack is empty and both params live, so the frame is locals=[this, ConfigurationTask$Type]
	 * / stack=[] — identical to the method's own entry frame.
	 */
	private static InsnList finishTaskPrologue(String owner) {
		InsnList body = new InsnList();
		LabelNode notEquivalent = new LabelNode();
		body.add(new VarInsnNode(Opcodes.ALOAD, 0)); // this (the listener)
		body.add(new VarInsnNode(Opcodes.ALOAD, 1)); // the requested task type
		body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, INTEROP, FINISH_HOOK, FINISH_HOOK_DESC, false));
		body.add(new JumpInsnNode(Opcodes.IFEQ, notEquivalent)); // false -> run the original body
		body.add(new InsnNode(Opcodes.RETURN));
		body.add(notEquivalent);
		body.add(new FrameNode(Opcodes.F_NEW, 2, new Object[] {owner, CONFIG_TASK_TYPE}, 0, new Object[] {}));
		return body;
	}

	/**
	 * Lets Fabric answer the server's {@code minecraft:register} — and answer it FIRST.
	 *
	 * <p>{@code minecraft:register} is the one channel BOTH ecosystems claim, and on the merged base they claim it
	 * at two different depths of the same call chain. NeoForge patched the vanilla override:
	 * <pre>
	 *   ClientConfigurationPacketListenerImpl.handleCustomPayload(packet) {
	 *       if (!initializedConnection &amp;&amp; packet.payload() instanceof MinecraftRegisterPayload) {
	 *           ClientNetworkRegistry.sendInitialListeningChannels(this);   // NeoForge's channel list goes out
	 *           return;                                                     // ← never reaches super
	 *       }
	 *       ...
	 *       super.handleCustomPayload(packet);
	 *   }
	 * </pre>
	 * while Fabric injects its dispatch at the HEAD of the SUPERCLASS's {@code handleCustomPayload}. On a real
	 * NeoForge instance nothing is downstream of that {@code return}; on a real Fabric instance the override does
	 * not exist. Only on a merged base does one ecosystem's early exit starve the other's entry point — and
	 * Fabric's {@code ClientConfigurationNetworkAddon.receiveRegistration} is the SOLE caller of
	 * {@code sendInitialChannelRegistrationPacket()}, so the client never declared a single Fabric channel.
	 *
	 * <p>Merely appending the super call before that {@code return} is not enough, and the reason is a contract on
	 * the OTHER end of the wire. Fabric's {@code ServerConfigurationNetworkAddon.receiveRegistration} treats the
	 * client's FIRST {@code minecraft:register} as its complete declaration: it flips {@code SENT → RECEIVED} and
	 * calls {@code startConfiguration()} synchronously, which runs {@code RegistrySyncManager.configureClient} and
	 * its {@code canSend(fabric:registry/sync)} check right there. If NeoForge's list has gone out first, that check
	 * sees seven NeoForge channels and kicks with "This server requires Fabric Loader and Fabric API installed on
	 * your client!" — while Fabric's list is one packet behind on the same socket. A pure Fabric server would do the
	 * same, so this is the merged CLIENT's obligation: be a well-formed Fabric client, whose first register is
	 * Fabric's. NeoForge's {@code NetworkRegistry.onMinecraftRegister} is purely additive and order-blind, so a
	 * NeoForge server is indifferent to which list arrives first.
	 *
	 * <p>Hence the super call is spliced in BEFORE {@code sendInitialListeningChannels}, not before the
	 * {@code return}: Fabric sees the payload (once — our addon prologue translates it and cancels the vanilla body)
	 * and replies, then NeoForge replies, then the override returns as it always did. NeoForge's own guard stays
	 * the condition — no duplicate of {@code initializedConnection} to drift — and no branch target is added, so
	 * every original stack map frame stays valid.
	 */
	private static boolean shareMinecraftRegisterWithSuper(MethodNode m) {
		// Follow the method's OWN super call rather than naming the superclass, so this tracks a renamed base class.
		MethodInsnNode superCall = null;
		for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() != Opcodes.INVOKESPECIAL) continue;
			MethodInsnNode call = (MethodInsnNode) insn;
			if (call.name.equals(HANDLE_PAYLOAD) && call.desc.equals(HANDLE_PAYLOAD_DESC)) {
				superCall = call;
				break;
			}
		}
		if (superCall == null) return false;

		boolean patched = false;
		for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() != Opcodes.INVOKESTATIC) continue;
			MethodInsnNode call = (MethodInsnNode) insn;
			if (!call.name.equals(NEO_SEND_INITIAL_CHANNELS) || !call.owner.startsWith(NEO_PACKAGE)) continue;
			// Only the early-exit branch: the NeoForge reply immediately followed by the return that skips super.
			AbstractInsnNode exit = nextOpcode(call);
			if (exit == null || exit.getOpcode() != Opcodes.RETURN) continue;
			// Spliced between the argument push and the static call: the stack holds NeoForge's listener argument,
			// we push two more and the invokespecial consumes exactly those two, leaving the argument in place.
			InsnList first = new InsnList();
			first.add(new VarInsnNode(Opcodes.ALOAD, 0)); // this
			first.add(new VarInsnNode(Opcodes.ALOAD, 1)); // the packet
			first.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, superCall.owner, superCall.name, superCall.desc, false));
			m.instructions.insertBefore(call, first);
			patched = true;
		}
		if (patched) bumpStack(m, 3);
		return patched;
	}

	/** The next node that is a real instruction — labels, frames and line numbers all report opcode -1. */
	private static AbstractInsnNode nextOpcode(AbstractInsnNode from) {
		for (AbstractInsnNode insn = from.getNext(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() >= 0) return insn;
		}
		return null;
	}

	/** Our prologue pushes two references before the call; COMPUTE_MAXS still recomputes, this only raises the floor. */
	private static void bumpStack(MethodNode m, int extra) {
		m.maxStack = Math.max(m.maxStack, extra);
	}
}
