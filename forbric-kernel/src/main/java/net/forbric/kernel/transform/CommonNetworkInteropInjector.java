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
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.util.ForbricLog;
import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;

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
 * <p>The translation logic lives in {@link net.forbric.kernel.interop.PayloadInterop} — boot-side, purely
 * reflective, and a kernel class since the interop hooks were brought over from the previous-generation loader:
 * given a Fabric payload receiver and the incoming payload it tells NeoForge's stack, then hands the receiver the
 * payload as Fabric's own codec reads the same bytes, so the receiver's own code handles it. This injector installs
 * the two call sites the old loader reached via mixins, but as kernel-native injections (the kernel authors no
 * mixins of its own):
 * <ul>
 *   <li>{@code handle(CustomPacketPayload)Z} of every class that implements fabric-api's public {@code PacketSender}
 *       (itself or through a superclass) and declares that method: its receivers, whatever their implementation calls
 *       them. Its payload is replaced at the head by the one the interop hands back, and the interop is told before
 *       each return. This is the one that stops the client CCE.</li>
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
	private static final String INTEROP = "net/forbric/kernel/interop/PayloadInterop";

	/**
	 * The public interface every Fabric payload receiver implements: fabric-api's {@code PacketSender}, the contract its
	 * networking hands mods. A class that implements it, itself or through a superclass, and DECLARES its own
	 * {@code handle(CustomPacketPayload)Z} is a receiver whose {@code handle} the interop goes in front of.
	 *
	 * <p>Declares, not inherits: the play receivers inherit {@code handle} and are covered by the class that declares it;
	 * both CONFIGURATION receivers override it, and an override is not reached by a prologue spliced into the
	 * superclass — so it was once the case that the whole configuration phase ran with no cross-ecosystem translation,
	 * and a server kicked its own client with "This server requires Fabric Loader and Fabric API installed on your
	 * client!" because the server's {@code minecraft:register} reached the client as NeoForge's payload type.
	 */
	static final String PACKET_SENDER = "net/fabricmc/fabric/api/networking/v1/PacketSender";
	private static final byte[] HANDLE_NEEDLE = net.forbric.kernel.util.ByteScan.needle(
			"(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)Z");
	/** Class bytes by internal name, for the superclass walk; null reads only the class itself. */
	private final java.util.function.Function<String, byte[]> classBytes;

	public CommonNetworkInteropInjector() {
		this(null);
	}

	/** @param classBytes a class's bytes by internal name (a mod's included), or null; read to walk a receiver's superclasses */
	public CommonNetworkInteropInjector(java.util.function.Function<String, byte[]> classBytes) {
		this.classBytes = classBytes;
	}

	private static final String HANDLE = "handle";
	private static final String HANDLE_DESC = "(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)Z";
	private static final String HANDLE_HOOK = "fabricInbound";
	private static final String HANDLE_HOOK_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
	private static final String HANDLE_DONE_HOOK = "fabricInboundDone";
	private static final String HANDLE_DONE_HOOK_DESC = "(Ljava/lang/Object;)V";
	/** fabric-api's public {@code PacketSender.sendPacket(Packet, ChannelFutureListener)}: where a receiver's answers go out. */
	private static final String SEND = "sendPacket";
	private static final String SEND_DESC = "(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V";
	private static final String SEND_HOOK = "fabricSendStandsAside";
	private static final String SEND_HOOK_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Z";

	private static final String CUSTOM_PAYLOAD = "net/minecraft/network/protocol/common/custom/CustomPacketPayload";

	private static final String CLIENT_CONFIG_LISTENER = "net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl";
	/**
	 * NeoForge's network registry: its {@code checkPacket} (client and server overloads) is NeoForge's channel police,
	 * which refuses to send any payload whose channel NeoForge did not negotiate — including every channel another
	 * ecosystem negotiated on the same connection (Polymer's {@code polymer:handshake}, Carpet's {@code carpet:hello}).
	 * Those are exempted at its head.
	 */
	private static final String NEO_NETWORK_REGISTRY = ForeignType.NETWORK_REGISTRY.binary(Ecosystem.NEOFORGE);
	private static final String CHECK_PACKET = "checkPacket";
	private static final String EXEMPT_HOOK = "exemptFromNeoChannelCheck";
	private static final String EXEMPT_HOOK_DESC = "(Ljava/lang/Object;)Z";
	private static final String HANDLE_PAYLOAD = "handleCustomPayload";
	private static final String HANDLE_PAYLOAD_DESC = "(Lnet/minecraft/network/protocol/common/ClientboundCustomPayloadPacket;)V";
	private static final String NEO_PACKAGE = "net/neoforged/";
	private static final String NEO_SEND_INITIAL_CHANNELS = "sendInitialListeningChannels";

	/*
	 * NeoForge's client-side initialisation of a connection to a non-NeoForge server (ClientNetworkRegistry
	 * .initializeOtherConnection) used to be spliced here: the 26.2.0.38 beta listener ran it from three call sites per
	 * join and only one checked its own initializedConnection flag. NeoForge fixed that upstream in 26.2.0.88 (every
	 * site goes through runConnectionInitialization, which takes a per-connection lock and returns once the
	 * CONNECTION_INITIALIZED channel attribute is set), the splice matched nothing from then on, on the merged base and
	 * on NeoForge's own base alike, and it went in Stage 4. CommonNetworkInteropInjectorTest keeps NeoForge's own guard
	 * pinned on the staged carrier, so a carrier that drops it goes red.
	 */

	private static final String CONNECTION = "net.minecraft.network.Connection";
	/**
	 * {@code Connection.sendPacket(Packet, ChannelFutureListener, boolean)}: every packet the connection puts on the wire
	 * comes through it, a packet sent while connected straight from {@code send} and a queued one when the queue is
	 * flushed, in the order they go out.
	 */
	private static final String CONNECTION_SEND = "sendPacket";
	private static final String CONNECTION_SEND_DESC = "(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V";
	private static final String DECLARATION_HOOK = "declarationAlreadyMade";
	/** {@code off}: every channel declaration goes out as its ecosystem sends it, repeats included. */
	public static final String DECLARATIONS_ONCE_PROPERTY = "forbric.channelDeclarationsOnce";

	static boolean declarationsOnceEnabled() {
		return !"off".equalsIgnoreCase(System.getProperty(DECLARATIONS_ONCE_PROPERTY, "on"));
	}

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
	public AnchorSet anchors() {
		// Four fixed target classes plus every Fabric receiver, and as many independent repairs behind one `changed`
		// flag, some of them unclaimed and allowed to find their target already shaped right (the register sharing
		// says so in its own log line). So a matched class that comes back unedited is not yet evidence of anything;
		// these need per-repair claims.
		return AnchorSet.scanned("several independent repairs across four classes and every Fabric receiver, some of "
				+ "which leave a target alone when the carrier already ships the shape they need");
	}

	/**
	 * Claim ids, one per claimed branch of {@link #transform}; each is reported beside its {@code changed = true}. The
	 * Fabric send stand-aside, the register sharing and the repeated-declaration filter are unclaimed.
	 */
	static final String CLAIM_FABRIC_ADDON = "forbric-common-network-interop#fabricAddonHandle";
	static final String CLAIM_FINISH_TASK = "forbric-common-network-interop#finishCurrentTask";
	static final String CLAIM_CHECK_PACKET = "forbric-common-network-interop#neoCheckPacket";

	/**
	 * One claim per branch. (The merged base's play-phase fall-through, a REQUIRED claim on MinecraftForge's
	 * {@code ServerGamePacketListenerImpl.handleCustomPayload} override, went in Stage 4: NeoForge's own play listener
	 * hands every payload to its super, where {@code FabricCommonPayloadMixinAdapter} keeps fabric-api's handler from
	 * throwing on it. So did the HEDGE claim on the client listener's connection-initialisation guard, which had matched
	 * nothing since NeoForge 26.2.0.88 guarded that itself.)
	 */
	@Override
	public List<Claim> claims() {
		return List.of(
				new Claim(CLAIM_FABRIC_ADDON, AnchorSet.scanned("every class implementing fabric-api's PacketSender that declares "
						+ "handle(CustomPacketPayload): a Fabric receiver would miscast a NeoForge payload of a shared channel")),
				new Claim(CLAIM_FINISH_TASK, AnchorSet.of(required(SERVER_CONFIG,
						"Fabric and NeoForge common-networking configuration tasks are not treated as equivalent — one family's configuration never finishes"))),
				new Claim(CLAIM_CHECK_PACKET, AnchorSet.of(required(NEO_NETWORK_REGISTRY,
						"NeoForge's channel check refuses to send on a channel another ecosystem negotiated — a Polymer or Carpet "
								+ "player is disconnected with \"Payload … may not be sent\""))));
	}

	private static AnchorSet.Anchor required(String binaryName, String cost) {
		return new AnchorSet.Anchor(binaryName, AnchorSet.Severity.REQUIRED, cost);
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		return transform(className, classBytes, context, ClaimReporter.NONE);
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context, ClaimReporter reporter) {
		if (classBytes == null || classBytes.length == 0) return classBytes;
		boolean fabricAddon = receivesFabricPayloads(classBytes);
		boolean serverConfig = SERVER_CONFIG.equals(className);
		boolean clientConfig = CLIENT_CONFIG_LISTENER.equals(className);
		boolean connection = CONNECTION.equals(className);
		boolean neoRegistry = NEO_NETWORK_REGISTRY.equals(className);
		if (!fabricAddon && !serverConfig && !clientConfig && !connection && !neoRegistry) return classBytes;

		ClassNode node = new ClassNode();
		// EXPAND_FRAMES so every original frame is an absolute F_NEW node; the explicit frames we author at our own
		// branch targets then slot in consistently, and ClassWriter can serialize the StackMapTable WITHOUT
		// COMPUTE_FRAMES — whose getCommonSuperClass would try to load game classes through the wrong loader.
		new ClassReader(classBytes).accept(node, ClassReader.EXPAND_FRAMES);

		boolean changed = false;
		for (MethodNode m : node.methods) {
			if (fabricAddon && m.name.equals(HANDLE) && m.desc.equals(HANDLE_DESC)
					&& (m.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) == 0 && implementsPacketSender(node)) {
				m.instructions.insert(handleAddonPrologue());
				for (AbstractInsnNode insn : m.instructions.toArray()) {
					if (insn.getOpcode() == Opcodes.IRETURN) m.instructions.insertBefore(insn, handleAddonEpilogue());
				}
				bumpStack(m, 2);
				changed = true;
				reporter.hit(CLAIM_FABRIC_ADDON);
				ForbricLog.info("[Forbric/Net] arbitrating shared channels at %s.%s — the Fabric receiver gets each "
						+ "payload as Fabric's own codec reads it, after NeoForge has been told", className, HANDLE);
			} else if (fabricAddon && m.name.equals(SEND) && m.desc.equals(SEND_DESC)
					&& (m.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) == 0 && implementsPacketSender(node)) {
				m.instructions.insert(sendStandsAsidePrologue(node.name));
				bumpStack(m, 2);
				changed = true;
				ForbricLog.info("[Forbric/Net] %s.%s lets a common-negotiation answer stand aside while NeoForge's handler "
						+ "is the one to answer it", className, SEND);
			} else if (serverConfig && m.name.equals(FINISH_TASK) && m.desc.equals(FINISH_TASK_DESC)) {
				m.instructions.insert(finishTaskPrologue(node.name));
				bumpStack(m, 2);
				changed = true;
				reporter.hit(CLAIM_FINISH_TASK);
				ForbricLog.info("[Forbric/Net] treating Fabric/NeoForge common-networking tasks as equivalent at %s.%s",
						className, FINISH_TASK);
			} else if (neoRegistry && m.name.equals(CHECK_PACKET) && (m.access & Opcodes.ACC_STATIC) != 0
					&& org.objectweb.asm.Type.getArgumentTypes(m.desc).length == 2) {
				m.instructions.insert(channelCheckExemptionPrologue(m.desc));
				bumpStack(m, 1);
				changed = true;
				reporter.hit(CLAIM_CHECK_PACKET);
				ForbricLog.info("[Forbric/Net] exempting channels another ecosystem negotiated from NeoForge's channel check "
						+ "at %s.%s%s", className, CHECK_PACKET, m.desc);
			} else if (connection && m.name.equals(CONNECTION_SEND) && m.desc.equals(CONNECTION_SEND_DESC) && declarationsOnceEnabled()) {
				if (dropRepeatedDeclarations(node, m)) {
					bumpStack(m, 2);
					changed = true;
					ForbricLog.info("[Forbric/Net] %s.%s now leaves out a channel declaration (minecraft:register) that names only "
							+ "channels this side already declared on the connection in this phase — three ecosystems each declare, "
							+ "and a peer's Fabric listeners heard the same channels again for every repeat", className, CONNECTION_SEND);
				}
			}
			if (clientConfig && m.name.equals(HANDLE_PAYLOAD) && m.desc.equals(HANDLE_PAYLOAD_DESC)) {
				int shared = shareMinecraftRegisterWithSuper(m);
				if (shared > 0) {
					changed = true;
					ForbricLog.info("[Forbric/Net] letting minecraft:register reach BOTH stacks at %s.%s, Fabric first — "
							+ "NeoForge's override answered it alone and returned, and Fabric's server treats the first "
							+ "register as the whole declaration", className, HANDLE_PAYLOAD);
				} else if (shared == 0) {
					ForbricLog.info("[Forbric/Net] %s.%s already hands minecraft:register to super ahead of NeoForge's reply "
							+ "— both stacks see it once, Fabric first, as shipped", className, HANDLE_PAYLOAD);
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
	 * {@code payload = (CustomPacketPayload) interop.fabricInbound(this, payload);} — prepended so NeoForge is told and the
	 * receiver's own body goes on with the payload as Fabric reads it. Straight-line: no branch, no frame.
	 */
	private static InsnList handleAddonPrologue() {
		InsnList body = new InsnList();
		body.add(new VarInsnNode(Opcodes.ALOAD, 0)); // this (the receiver)
		body.add(new VarInsnNode(Opcodes.ALOAD, 1)); // the payload
		body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, INTEROP, HANDLE_HOOK, HANDLE_HOOK_DESC, false));
		body.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.CHECKCAST, CUSTOM_PAYLOAD));
		body.add(new VarInsnNode(Opcodes.ASTORE, 1));
		return body;
	}

	/**
	 * {@code if (interop.fabricSendStandsAside(this, packet)) return;} at the head of a void {@code sendPacket}: the
	 * fall-through frame is the entry frame — the receiver, its two parameters, an empty stack.
	 */
	private static InsnList sendStandsAsidePrologue(String owner) {
		InsnList body = new InsnList();
		LabelNode send = new LabelNode();
		body.add(new VarInsnNode(Opcodes.ALOAD, 0));
		body.add(new VarInsnNode(Opcodes.ALOAD, 1));
		body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, INTEROP, SEND_HOOK, SEND_HOOK_DESC, false));
		body.add(new JumpInsnNode(Opcodes.IFEQ, send));
		body.add(new InsnNode(Opcodes.RETURN));
		body.add(send);
		body.add(new FrameNode(Opcodes.F_NEW, 3, new Object[] {owner, "net/minecraft/network/protocol/Packet",
				"io/netty/channel/ChannelFutureListener"}, 0, new Object[] {}));
		return body;
	}

	/**
	 * {@code if (interop.exemptFromNeoChannelCheck(packet)) return;} at the head of a static
	 * {@code checkPacket(Packet, <listener>)V}. Fall-through frame: the two parameters, empty stack — the entry frame.
	 */
	private static InsnList channelCheckExemptionPrologue(String desc) {
		org.objectweb.asm.Type[] args = org.objectweb.asm.Type.getArgumentTypes(desc);
		InsnList body = new InsnList();
		LabelNode policed = new LabelNode();
		body.add(new VarInsnNode(Opcodes.ALOAD, 0)); // the packet
		body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, INTEROP, EXEMPT_HOOK, EXEMPT_HOOK_DESC, false));
		body.add(new JumpInsnNode(Opcodes.IFEQ, policed));
		body.add(new InsnNode(Opcodes.RETURN));
		body.add(policed);
		body.add(new FrameNode(Opcodes.F_NEW, 2, new Object[] {args[0].getInternalName(), args[1].getInternalName()}, 0,
				new Object[] {}));
		return body;
	}

	/** {@code interop.fabricInboundDone(this);} in front of a return: the stack keeps the answer, untouched. */
	private static InsnList handleAddonEpilogue() {
		InsnList body = new InsnList();
		body.add(new VarInsnNode(Opcodes.ALOAD, 0));
		body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, INTEROP, HANDLE_DONE_HOOK, HANDLE_DONE_HOOK_DESC, false));
		return body;
	}

	/** Whether {@code bytes} may declare a receiver's {@code handle}: the descriptor is in its constant pool. */
	private static boolean receivesFabricPayloads(byte[] bytes) {
		return net.forbric.kernel.util.ByteScan.contains(bytes, HANDLE_NEEDLE);
	}

	/** Whether {@code node} implements fabric-api's {@code PacketSender}, itself or through its superclasses. */
	boolean implementsPacketSender(ClassNode node) {
		java.util.Set<String> seen = new java.util.HashSet<>();
		java.util.ArrayDeque<String> pending = new java.util.ArrayDeque<>();
		if (node.interfaces != null) pending.addAll(node.interfaces);
		if (node.superName != null) pending.add(node.superName);
		while (!pending.isEmpty()) {
			String type = pending.pop();
			if (!seen.add(type) || type.startsWith("java/")) continue;
			if (PACKET_SENDER.equals(type)) return true;
			byte[] bytes = classBytes == null ? null : classBytes.apply(type);
			if (bytes == null) continue;
			ClassReader reader = new ClassReader(bytes);
			java.util.Collections.addAll(pending, reader.getInterfaces());
			if (reader.getSuperName() != null) pending.add(reader.getSuperName());
		}
		return false;
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
	 *
	 * <p>A carrier that already hands the packet to super right before its reply (NeoForge 26.2.0.88 does) gets nothing:
	 * a second super call there handed the same register to Fabric's addon twice, and every Fabric listener on the
	 * client's configuration channel events heard each of the server's declarations twice.
	 *
	 * @return 1 when the super call was spliced in, 0 when every early exit already makes it ahead of the reply, -1 when
	 *         the method is not the shape this repair knows
	 */
	static int shareMinecraftRegisterWithSuper(MethodNode m) {
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
		if (superCall == null) return -1;

		boolean patched = false, alreadyShared = false;
		for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() != Opcodes.INVOKESTATIC) continue;
			MethodInsnNode call = (MethodInsnNode) insn;
			if (!call.name.equals(NEO_SEND_INITIAL_CHANNELS) || !call.owner.startsWith(NEO_PACKAGE)) continue;
			// Only the early-exit branch: the NeoForge reply immediately followed by the return that skips super.
			AbstractInsnNode exit = nextOpcode(call);
			if (exit == null || exit.getOpcode() != Opcodes.RETURN) continue;
			if (superCallAhead(call)) {
				alreadyShared = true;
				continue;
			}
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
		return patched ? 1 : alreadyShared ? 0 : -1;
	}

	/**
	 * Whether the straight-line run of code that ends in {@code reply} already passes the packet to super: walking back
	 * from the reply, within its basic block (up to a merge point's frame, a jump or an exit; line-number labels are not
	 * block boundaries), a {@code handleCustomPayload} {@code INVOKESPECIAL} comes first.
	 */
	private static boolean superCallAhead(MethodInsnNode reply) {
		for (AbstractInsnNode insn = reply.getPrevious(); insn != null; insn = insn.getPrevious()) {
			if (insn instanceof org.objectweb.asm.tree.FrameNode) return false;
			int opcode = insn.getOpcode();
			if (insn instanceof org.objectweb.asm.tree.JumpInsnNode || opcode == Opcodes.ATHROW
					|| opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) return false;
			if (opcode == Opcodes.INVOKESPECIAL && insn instanceof MethodInsnNode call && call.name.equals(HANDLE_PAYLOAD)
					&& call.desc.equals(HANDLE_PAYLOAD_DESC)) return true;
		}
		return false;
	}

	/**
	 * Puts {@code if (PayloadInterop.declarationAlreadyMade(this, packet)) return;} at the head of
	 * {@code Connection.sendPacket(Packet, ChannelFutureListener, boolean)}, the method every packet the connection puts on
	 * the wire reaches, in the order it goes out.
	 *
	 * <p>Fabric, NeoForge and MinecraftForge each declare the channels they listen on ({@code minecraft:register}) at
	 * their own point of the handshake, and NeoForge's client answers every declaration a server makes before its own
	 * network is set up with its full list again; the peer's Fabric addon fired its channel-registration event for each,
	 * the same channels three times over. A declaration naming only channels this side has already declared on the
	 * connection in the current phase tells no receiver anything (each keeps a set) and is left out; the first
	 * declaration, any carrying a new channel, and every {@code unregister} still go out, in their order.
	 */
	static boolean dropRepeatedDeclarations(ClassNode owner, MethodNode m) {
		for (AbstractInsnNode insn : m.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(INTEROP) && call.name.equals(DECLARATION_HOOK)) return false;
		}
		LabelNode send = new LabelNode();
		InsnList head = new InsnList();
		head.add(new VarInsnNode(Opcodes.ALOAD, 0));
		head.add(new VarInsnNode(Opcodes.ALOAD, 1));
		head.add(new MethodInsnNode(Opcodes.INVOKESTATIC, INTEROP, DECLARATION_HOOK, "(Ljava/lang/Object;Ljava/lang/Object;)Z", false));
		head.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.IFEQ, send));
		head.add(new InsnNode(Opcodes.RETURN));
		head.add(send);
		// The class is read with EXPAND_FRAMES: the frame at the join is absolute, the method's own arguments.
		head.add(new FrameNode(Opcodes.F_NEW, 4, new Object[] {owner.name, "net/minecraft/network/protocol/Packet",
				"io/netty/channel/ChannelFutureListener", Opcodes.INTEGER}, 0, new Object[] {}));
		m.instructions.insert(head);
		return true;
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
