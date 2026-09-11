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
 * given the addon and the incoming payload it runs the negotiation for BOTH stacks
 * — extracting the version, feeding Fabric's {@code onCommonVersionPacket} and NeoForge's {@code checkCommonVersion}
 * — and reports whether it fully handled the packet. This injector installs the two call sites the old loader
 * reached via mixins, but as kernel-native head injections (the kernel authors no mixins of its own):
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
	private static final String INTEROP = "net/forbric/kernel/interop/PayloadInterop";

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
	/**
	 * Where MinecraftForge's own dispatch used to sit. Forge patches {@code handleCustomPayload} on both common
	 * listeners to ask {@code ForgeHooks.onCustomPayload} first; NeoForge patched the same methods and won the
	 * byte-merge, so the only surviving Forge call is the one in {@code ServerGamePacketListenerImpl}'s override.
	 * A Forge mod's packet reaching the client, or the server in the configuration phase, therefore had no
	 * dispatcher at all. These two get a prologue that hands a ForgePayload to Forge's hook and returns when it
	 * took it.
	 */
	private static final String CLIENT_COMMON_LISTENER = "net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl";
	private static final String SERVER_COMMON_LISTENER = "net.minecraft.server.network.ServerCommonPacketListenerImpl";
	private static final String CLIENT_HANDLE_PAYLOAD_DESC = "(Lnet/minecraft/network/protocol/common/ClientboundCustomPayloadPacket;)V";
	private static final String SERVER_HANDLE_PAYLOAD_DESC = "(Lnet/minecraft/network/protocol/common/ServerboundCustomPayloadPacket;)V";
	private static final String FORGE_DISPATCH_HOOK = "dispatchForgePayload";
	private static final String FORGE_DISPATCH_HOOK_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Z";

	/**
	 * NeoForge's channel police and its register bookkeeping. {@code checkPacket} (client and server overloads)
	 * refuses any payload whose channel NeoForge did not negotiate — which is every MinecraftForge channel, so a Forge
	 * mod's client could not send on its own channel. Forge payloads are exempted. {@code onMinecraftRegister} /
	 * {@code onMinecraftUnregister} are where every peer channel declaration lands on this base, with or without
	 * fabric-api; Forge's own listener for that channel never runs here, so its bookkeeping is fed from there.
	 */
	private static final String NEO_NETWORK_REGISTRY = ForeignType.NETWORK_REGISTRY.binary(Ecosystem.NEOFORGE);
	private static final String CHECK_PACKET = "checkPacket";
	private static final String ON_REGISTER = "onMinecraftRegister";
	private static final String ON_UNREGISTER = "onMinecraftUnregister";
	private static final String REGISTER_DESC = "(Lnet/minecraft/network/Connection;Ljava/util/Set;)V";
	private static final String IS_FORGE_PACKET = "isForgePayloadPacket";
	private static final String IS_FORGE_PACKET_DESC = "(Ljava/lang/Object;)Z";
	private static final String ON_NEO_REGISTRATION = "onNeoChannelRegistration";
	private static final String ON_NEO_REGISTRATION_DESC = "(Ljava/lang/Object;Ljava/lang/Object;Z)V";
	private static final String HANDLE_PAYLOAD = "handleCustomPayload";
	private static final String HANDLE_PAYLOAD_DESC = "(Lnet/minecraft/network/protocol/common/ClientboundCustomPayloadPacket;)V";
	private static final String NEO_PACKAGE = "net/neoforged/";
	private static final String NEO_SEND_INITIAL_CHANNELS = "sendInitialListeningChannels";

	/**
	 * NeoForge's client-side initialisation of a connection to a NON-NeoForge server, {@code ClientNetworkRegistry
	 * .initializeOtherConnection}. Three call sites in {@code ClientConfigurationPacketListenerImpl} fire per join
	 * (the brand payload on the Netty thread, the enabled-features packet, and the brand payload again on the render
	 * thread after vanilla's re-dispatch) and only {@code handleConfigurationFinished} checks the listener's own
	 * {@code initializedConnection} flag first; the other two run the whole thing again — every NeoForge mod's
	 * default server config rebuilt ("Overwriting non-null config ..." twice per join), the payload filters
	 * re-injected, the register payload re-sent. The two unguarded sites get the same flag check the third has,
	 * so the flag keeps its NeoForge meaning: once per configuration phase, a reconfiguration starts afresh.
	 */
	private static final String INITIALIZE_OTHER = "initializeOtherConnection";
	private static final String INITIALIZED_FLAG = "initializedConnection";
	private static final String IS_OTHER = "isOther";

	/**
	 * MinecraftForge's login/configuration handshake — the five tasks that exchange mod and channel lists and push
	 * the server's SERVER-type configs to the client. Three links lost the byte-merge and are re-tied here:
	 * <ul>
	 * <li>{@code Connection.channelActive} no longer invokes the activation handler that installs Forge's
	 *     per-connection packet handler, so on a client the {@code forge:handshake} channel attribute was null and
	 *     every handler on that channel would have died on it;</li>
	 * <li>the merged {@code runConfiguration} is NeoForge's body, which never posts Forge's
	 *     {@code GatherLoginConfigurationTasksEvent} — the sole producer of Forge's tasks;</li>
	 * <li>the merged {@code startNextTask} dispatches through the vanilla {@code start(Consumer)} overload, and
	 *     Forge's tasks answer it by throwing: they want the {@code ConfigurationTaskContext} overload, whose
	 *     default implementation on the same interface delegates straight back to the Consumer one, so every
	 *     vanilla, NeoForge and Fabric task is unaffected by the swap.</li>
	 * </ul>
	 * The fourth is the client's "configuration complete" hook, which Forge only reaches from a code-of-conduct
	 * handler vanilla rarely calls.
	 */
	private static final String CONNECTION = "net.minecraft.network.Connection";
	private static final String CHANNEL_ACTIVE = "channelActive";
	private static final String CHANNEL_ACTIVE_DESC = "(Lio/netty/channel/ChannelHandlerContext;)V";
	private static final String DELAYED_DISCONNECT = "delayedDisconnect";
	private static final String ON_CONNECTION_ACTIVE = "onConnectionActive";
	private static final String RUN_CONFIGURATION = "runConfiguration";
	private static final String NEO_EARLY_TASKS = "configureEarlyTasks";
	private static final String GATHER_FORGE_TASKS = "gatherForgeConfigurationTasks";
	private static final String START_NEXT_TASK = "startNextTask";
	private static final String CONFIGURATION_TASK = "net/minecraft/server/network/ConfigurationTask";
	private static final String TASK_START = "start";
	private static final String TASK_START_CONSUMER_DESC = "(Ljava/util/function/Consumer;)V";
	private static final String FORGE_TASK_CONTEXT = "Lnet/minecraftforge/network/config/ConfigurationTaskContext;";
	private static final String TASK_CONTEXT_FIELD = "taskContext";
	private static final String HANDLE_CONFIG_FINISHED = "handleConfigurationFinished";
	private static final String NEO_CONFIG_FINISHED = "onConfigurationFinished";
	private static final String ON_CLIENT_CONFIG_FINISHED = "onClientConfigurationFinished";
	private static final String OBJECT_HOOK_DESC = "(Ljava/lang/Object;)V";

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
		boolean clientCommon = CLIENT_COMMON_LISTENER.equals(className);
		boolean serverCommon = SERVER_COMMON_LISTENER.equals(className);
		boolean neoRegistry = NEO_NETWORK_REGISTRY.equals(className);
		boolean connection = CONNECTION.equals(className);
		if (!fabricAddon && !serverConfig && !clientConfig && !clientCommon && !serverCommon && !neoRegistry
				&& !connection) return classBytes;

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
			} else if ((clientCommon && m.name.equals(HANDLE_PAYLOAD) && m.desc.equals(CLIENT_HANDLE_PAYLOAD_DESC))
					|| (serverCommon && m.name.equals(HANDLE_PAYLOAD) && m.desc.equals(SERVER_HANDLE_PAYLOAD_DESC))) {
				String packetType = org.objectweb.asm.Type.getArgumentTypes(m.desc)[0].getInternalName();
				m.instructions.insert(forgeDispatchPrologue(node.name, packetType));
				bumpStack(m, 2);
				changed = true;
				ForbricLog.info("[Forbric/Net] handing MinecraftForge's payloads to ForgeHooks.onCustomPayload at %s.%s — "
						+ "NeoForge won this method in the merge and Forge's dispatch went with it", className, HANDLE_PAYLOAD);
			} else if (neoRegistry && m.name.equals(CHECK_PACKET) && (m.access & Opcodes.ACC_STATIC) != 0
					&& org.objectweb.asm.Type.getArgumentTypes(m.desc).length == 2) {
				m.instructions.insert(forgePacketExemptionPrologue(m.desc));
				bumpStack(m, 1);
				changed = true;
				ForbricLog.info("[Forbric/Net] exempting MinecraftForge payloads from NeoForge's channel check at %s.%s%s",
						className, CHECK_PACKET, m.desc);
			} else if (neoRegistry && (m.name.equals(ON_REGISTER) || m.name.equals(ON_UNREGISTER)) && m.desc.equals(REGISTER_DESC)) {
				m.instructions.insert(neoRegistrationPrologue(m.name.equals(ON_REGISTER)));
				bumpStack(m, 3);
				changed = true;
				ForbricLog.info("[Forbric/Net] keeping MinecraftForge's channel bookkeeping in step at %s.%s", className, m.name);
			} else if (connection && m.name.equals(CHANNEL_ACTIVE) && m.desc.equals(CHANNEL_ACTIVE_DESC)) {
				if (startForgeNetworkingOnActivation(m)) {
					bumpStack(m, 1);
					changed = true;
					ForbricLog.info("[Forbric/Net] %s.%s now starts MinecraftForge's networking for the connection — the "
							+ "merge dropped the activation handler that installed its per-connection packet handler, so a "
							+ "client had none and Forge's handshake could not be answered", className, CHANNEL_ACTIVE);
				}
			} else if (serverConfig && m.name.equals(RUN_CONFIGURATION)) {
				if (gatherForgeTasksWithNeoForges(m)) {
					bumpStack(m, 1);
					changed = true;
					ForbricLog.info("[Forbric/Net] %s.%s now gathers MinecraftForge's configuration tasks alongside "
							+ "NeoForge's — the merged body is NeoForge's and never posted Forge's gather event, so its "
							+ "mod list, channel list and server-config sync never ran", className, RUN_CONFIGURATION);
				}
			} else if (serverConfig && m.name.equals(START_NEXT_TASK)) {
				if (startTasksThroughForgesContext(node, m)) {
					changed = true;
					ForbricLog.info("[Forbric/Net] %s.%s now starts configuration tasks through MinecraftForge's task "
							+ "context — its own tasks refuse the vanilla overload, and every other task reaches it "
							+ "through the interface default that delegates back", className, START_NEXT_TASK);
				}
			} else if (clientConfig && m.name.equals(HANDLE_CONFIG_FINISHED)) {
				if (completeForgeConfiguration(m)) {
					bumpStack(m, 1);
					changed = true;
					ForbricLog.info("[Forbric/Net] %s.%s now runs MinecraftForge's configuration-complete hook — Forge "
							+ "reaches it only from a code-of-conduct handler vanilla rarely calls, so a Forge mod never "
							+ "learned whether the server was modded", className, HANDLE_CONFIG_FINISHED);
				}
			}
			if (clientConfig) {
				int guarded = guardOtherConnectionInitialisation(node, m);
				if (guarded > 0) {
					changed = true;
					ForbricLog.info("[Forbric/Net] %s.%s now initialises a non-NeoForge connection once per configuration "
							+ "phase — NeoForge re-entered ClientNetworkRegistry.initializeOtherConnection from here and "
							+ "rebuilt every mod's default server config each time", className, m.name);
				}
			}
			if (clientConfig && m.name.equals(HANDLE_PAYLOAD) && m.desc.equals(HANDLE_PAYLOAD_DESC)) {
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
	 * {@code if (interop.isForgePayloadPacket(packet)) return;} at the head of a static
	 * {@code checkPacket(Packet, <listener>)V}. Fall-through frame: the two parameters, empty stack — the entry frame.
	 */
	private static InsnList forgePacketExemptionPrologue(String desc) {
		org.objectweb.asm.Type[] args = org.objectweb.asm.Type.getArgumentTypes(desc);
		InsnList body = new InsnList();
		LabelNode notForge = new LabelNode();
		body.add(new VarInsnNode(Opcodes.ALOAD, 0)); // the packet
		body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, INTEROP, IS_FORGE_PACKET, IS_FORGE_PACKET_DESC, false));
		body.add(new JumpInsnNode(Opcodes.IFEQ, notForge));
		body.add(new InsnNode(Opcodes.RETURN));
		body.add(notForge);
		body.add(new FrameNode(Opcodes.F_NEW, 2, new Object[] {args[0].getInternalName(), args[1].getInternalName()}, 0,
				new Object[] {}));
		return body;
	}

	/**
	 * {@code interop.onConnectionActive(this);} once {@code channelActive} has stored the channel — Forge's own
	 * moment, before any packet. Anchored on the first read of {@code delayedDisconnect}, which directly follows the
	 * channel/address stores in both the merged and the pristine bodies; the insertion is straight-line, so no frame
	 * is authored and no existing branch target moves.
	 */
	private static boolean startForgeNetworkingOnActivation(MethodNode m) {
		for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() != Opcodes.GETFIELD) continue;
			if (!DELAYED_DISCONNECT.equals(((org.objectweb.asm.tree.FieldInsnNode) insn).name)) continue;
			AbstractInsnNode loadThis = previousOpcode(insn);
			if (loadThis == null || loadThis.getOpcode() != Opcodes.ALOAD || ((VarInsnNode) loadThis).var != 0) return false;
			InsnList call = new InsnList();
			call.add(new VarInsnNode(Opcodes.ALOAD, 0));
			call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, INTEROP, ON_CONNECTION_ACTIVE, OBJECT_HOOK_DESC, false));
			m.instructions.insertBefore(loadThis, call);
			return true;
		}
		return false;
	}

	/** {@code interop.gatherForgeConfigurationTasks(this);} right after NeoForge queues its own early tasks. */
	private static boolean gatherForgeTasksWithNeoForges(MethodNode m) {
		for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() != Opcodes.INVOKESTATIC) continue;
			MethodInsnNode call = (MethodInsnNode) insn;
			if (!NEO_EARLY_TASKS.equals(call.name) || !call.owner.startsWith(NEO_PACKAGE)) continue;
			InsnList hook = new InsnList();
			hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
			hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, INTEROP, GATHER_FORGE_TASKS, OBJECT_HOOK_DESC, false));
			m.instructions.insert(call, hook);
			return true;
		}
		return false;
	}

	/**
	 * Swaps {@code task.start(this::send)} for {@code task.start(this.taskContext)} — the dispatch the pristine
	 * Forge body uses. Both are one interface call on the same task reference, so the stack depth is unchanged; the
	 * lambda that built the consumer becomes unreachable and is removed with it.
	 */
	private static boolean startTasksThroughForgesContext(ClassNode node, MethodNode m) {
		boolean hasContext = false;
		for (org.objectweb.asm.tree.FieldNode f : node.fields) {
			if (TASK_CONTEXT_FIELD.equals(f.name) && FORGE_TASK_CONTEXT.equals(f.desc)) hasContext = true;
		}
		if (!hasContext) return false;
		for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() != Opcodes.INVOKEINTERFACE) continue;
			MethodInsnNode call = (MethodInsnNode) insn;
			if (!CONFIGURATION_TASK.equals(call.owner) || !TASK_START.equals(call.name)
					|| !TASK_START_CONSUMER_DESC.equals(call.desc)) continue;
			AbstractInsnNode consumer = previousOpcode(call);
			if (!(consumer instanceof InvokeDynamicInsnNode)) return false;
			AbstractInsnNode loadThis = previousOpcode(consumer);
			if (loadThis == null || loadThis.getOpcode() != Opcodes.ALOAD || ((VarInsnNode) loadThis).var != 0) return false;
			m.instructions.set(consumer, new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETFIELD, node.name,
					TASK_CONTEXT_FIELD, FORGE_TASK_CONTEXT));
			call.desc = "(" + FORGE_TASK_CONTEXT + ")V";
			return true;
		}
		return false;
	}

	/** {@code interop.onClientConfigurationFinished(this);} after NeoForge's own finish, before the reply goes out. */
	private static boolean completeForgeConfiguration(MethodNode m) {
		for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() != Opcodes.INVOKESTATIC) continue;
			MethodInsnNode call = (MethodInsnNode) insn;
			if (!NEO_CONFIG_FINISHED.equals(call.name) || !call.owner.startsWith(NEO_PACKAGE)) continue;
			InsnList hook = new InsnList();
			hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
			hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, INTEROP, ON_CLIENT_CONFIG_FINISHED, OBJECT_HOOK_DESC, false));
			m.instructions.insert(call, hook);
			return true;
		}
		return false;
	}

	/**
	 * For every call to NeoForge's {@code initializeOtherConnection} in {@code m} that sits behind the shape
	 * <pre>  ALOAD 0; GETFIELD connectionType; INVOKEVIRTUAL isOther; IFEQ L; ... INVOKESTATIC initializeOtherConnection</pre>
	 * and is not already preceded by a check of {@code initializedConnection}, splice
	 * <pre>  ALOAD 0; GETFIELD initializedConnection; IFNE L</pre>
	 * in front of that guard. {@code L} is an existing branch target (it carries its own frame after EXPAND_FRAMES),
	 * so no frame is authored. Returns how many sites were guarded.
	 */
	private static int guardOtherConnectionInitialisation(ClassNode node, MethodNode m) {
		boolean hasFlag = false;
		for (org.objectweb.asm.tree.FieldNode f : node.fields) {
			if (INITIALIZED_FLAG.equals(f.name) && "Z".equals(f.desc)) hasFlag = true;
		}
		if (!hasFlag) return 0;
		int guarded = 0;
		for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() != Opcodes.INVOKESTATIC) continue;
			MethodInsnNode call = (MethodInsnNode) insn;
			if (!INITIALIZE_OTHER.equals(call.name) || !call.owner.startsWith(NEO_PACKAGE)) continue;
			// Walk back to the nearest `isOther` guard: INVOKEVIRTUAL isOther followed by IFEQ.
			JumpInsnNode ifeq = null;
			for (AbstractInsnNode back = call.getPrevious(); back != null; back = back.getPrevious()) {
				if (back.getOpcode() == Opcodes.IFEQ) {
					AbstractInsnNode test = previousOpcode(back);
					if (test instanceof MethodInsnNode t && IS_OTHER.equals(t.name)) {
						ifeq = (JumpInsnNode) back;
						break;
					}
				}
			}
			if (ifeq == null) continue;
			AbstractInsnNode isOther = previousOpcode(ifeq);
			AbstractInsnNode getType = previousOpcode(isOther);
			AbstractInsnNode loadThis = previousOpcode(getType);
			if (getType == null || getType.getOpcode() != Opcodes.GETFIELD || loadThis == null
					|| loadThis.getOpcode() != Opcodes.ALOAD || ((VarInsnNode) loadThis).var != 0) continue;
			// Already guarded (NeoForge's own third site checks the flag right before): leave it.
			AbstractInsnNode before = previousOpcode(loadThis);
			AbstractInsnNode beforeThat = before == null ? null : previousOpcode(before);
			if (beforeThat instanceof org.objectweb.asm.tree.FieldInsnNode f && INITIALIZED_FLAG.equals(f.name)) continue;
			InsnList guard = new InsnList();
			guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
			guard.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETFIELD, node.name, INITIALIZED_FLAG, "Z"));
			guard.add(new JumpInsnNode(Opcodes.IFNE, ifeq.label));
			m.instructions.insertBefore(loadThis, guard);
			bumpStack(m, 1);
			guarded++;
		}
		return guarded;
	}

	private static AbstractInsnNode previousOpcode(AbstractInsnNode from) {
		if (from == null) return null;
		for (AbstractInsnNode insn = from.getPrevious(); insn != null; insn = insn.getPrevious()) {
			if (insn.getOpcode() >= 0) return insn;
		}
		return null;
	}

	/** {@code interop.onNeoChannelRegistration(connection, set, register);} at the head of the static register hooks — no branch. */
	private static InsnList neoRegistrationPrologue(boolean register) {
		InsnList body = new InsnList();
		body.add(new VarInsnNode(Opcodes.ALOAD, 0)); // the connection
		body.add(new VarInsnNode(Opcodes.ALOAD, 1)); // the channel set
		body.add(new InsnNode(register ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
		body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, INTEROP, ON_NEO_REGISTRATION, ON_NEO_REGISTRATION_DESC, false));
		return body;
	}

	/**
	 * {@code if (interop.dispatchForgePayload(this, packet)) return;} — prepended to the void method. At the
	 * fall-through label the stack is empty and both params live: locals=[this, packet] / stack=[], the entry frame.
	 */
	private static InsnList forgeDispatchPrologue(String owner, String packetType) {
		InsnList body = new InsnList();
		LabelNode notForge = new LabelNode();
		body.add(new VarInsnNode(Opcodes.ALOAD, 0)); // this (the listener; its `connection` field is what Forge needs)
		body.add(new VarInsnNode(Opcodes.ALOAD, 1)); // the packet
		body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, INTEROP, FORGE_DISPATCH_HOOK, FORGE_DISPATCH_HOOK_DESC, false));
		body.add(new JumpInsnNode(Opcodes.IFEQ, notForge));
		body.add(new InsnNode(Opcodes.RETURN));
		body.add(notForge);
		body.add(new FrameNode(Opcodes.F_NEW, 2, new Object[] {owner, packetType}, 0, new Object[] {}));
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
