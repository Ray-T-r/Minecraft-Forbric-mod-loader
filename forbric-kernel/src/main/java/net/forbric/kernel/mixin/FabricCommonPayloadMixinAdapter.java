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

package net.forbric.kernel.mixin;

import java.util.List;
import java.util.function.Function;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Lets a play-phase payload that fabric-api's play receiver declined go on to NeoForge, as it does on NeoForge, instead of
 * reaching fabric-api's configuration-only handler and disconnecting the player.
 *
 * <p>fabric-api receives a serverbound payload in two places: a HEAD {@code @Inject} into
 * {@code ServerGamePacketListenerImpl.handleCustomPayload} for the play phase, and one into
 * {@code ServerCommonPacketListenerImpl.handleCustomPayload} that serves only the configuration listener and throws
 * {@code IllegalStateException("Unknown addon")} for any other. On vanilla no play payload reaches the second one: the
 * play listener's override is empty and never calls {@code super}. NeoForge's patch makes that override
 * {@code super.handleCustomPayload(packet)} (javap, NeoForge 26.2.0.93: {@code aload_0, aload_1, invokespecial
 * ServerCommonPacketListenerImpl.handleCustomPayload, return}), because NeoForge's whole dispatcher — its
 * {@code minecraft:register}/{@code unregister} and common-version handling and {@code NetworkRegistry.handleModdedPayload}
 * — lives in that super. So with fabric-api installed, the first play payload fabric-api's play receiver does not take
 * runs into the configuration handler and the server ends the connection: "Internal Exception:
 * java.lang.IllegalStateException: Unknown addon", measured on the 97-mod pack the moment it joined its world.
 *
 * <p>The handler now returns at its head for a play listener, the one listener this base sends there that vanilla never
 * does: fabric-api has already offered that payload to its play receiver at the play listener's own HEAD, and NeoForge's
 * body then runs as written. The configuration listener is untouched. Recognised by what the handler does — a non-static
 * HEAD {@code @Inject} of {@code handleCustomPayload} on {@code ServerCommonPacketListenerImpl} that tests its listener's
 * network addon once, against a configuration addon, and throws an {@code IllegalStateException} for every other addon
 * (the else-branch of that one test) — and only against a base whose play listener calls that super; on vanilla's empty
 * override nothing changes. A handler that serves more than one kind of addon, or one that is not a configuration addon,
 * is left as written: returning before it would silently drop the play payloads it serves, where leaving it shows as the
 * disconnect. {@code -Dforbric.playPayloadFallThrough=off} leaves the handler as written; it is the switch that on the
 * merged base turned off the same delivery ({@code CommonNetworkInteropInjector}'s play-phase hand-over, which went in
 * Stage 4), and this adapter is now its only reader.
 */
public final class FabricCommonPayloadMixinAdapter {
	/** The off switch (the name the merged base's play-phase hand-over in {@code CommonNetworkInteropInjector} had). */
	public static final String PROPERTY = "forbric.playPayloadFallThrough";
	static final String COMMON_LISTENER = "net/minecraft/server/network/ServerCommonPacketListenerImpl";
	static final String GAME_LISTENER = "net/minecraft/server/network/ServerGamePacketListenerImpl";
	static final String HANDLE = "handleCustomPayload";
	static final String HANDLE_DESC = "(Lnet/minecraft/network/protocol/common/ServerboundCustomPayloadPacket;)V";
	static final String HANDLER_DESC = "(Lnet/minecraft/network/protocol/common/ServerboundCustomPayloadPacket;"
			+ "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V";
	private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
	private static final String REFUSAL = "java/lang/IllegalStateException";

	private FabricCommonPayloadMixinAdapter() { }

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/** @return the number of handlers that now let a play listener's payload through, or 0 */
	public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
		if (!enabled() || !MixinFit.mixinTargets(mixin).equals(List.of(COMMON_LISTENER))) return 0;
		int adapted = 0;
		for (MethodNode handler : mixin.methods) {
			if (!refusesOtherListeners(handler)) continue;
			if (adapted == 0 && !playListenerCallsSuper(targets.apply(GAME_LISTENER))) return 0;
			handler.instructions.insert(playListenerReturns());
			handler.maxStack = Math.max(handler.maxStack, 1);
			adapted++;
		}
		if (adapted > 0) ForbricLog.info("[Forbric/Net] %s's configuration-phase payload handler on %s lets a play listener's "
				+ "payload go on to NeoForge's dispatcher there — the base's play listener hands what fabric-api's play receiver "
				+ "declined to super, where the handler threw \"Unknown addon\" and disconnected the player",
				mixin.name.replace('/', '.'), COMMON_LISTENER.replace('/', '.'));
		return adapted;
	}

	/**
	 * A non-static HEAD {@code @Inject} of {@code handleCustomPayload} that serves one kind of network addon, a configuration
	 * one, and throws an {@code IllegalStateException} for every other: exactly one {@code INSTANCEOF} in the handler, of a
	 * type whose simple name says configuration, whose false branch goes straight to {@code new IllegalStateException}.
	 */
	static boolean refusesOtherListeners(MethodNode handler) {
		if ((handler.access & Opcodes.ACC_STATIC) != 0 || !HANDLER_DESC.equals(handler.desc)) return false;
		AnnotationNode inject = MixinFit.injectorOf(handler);
		if (inject == null || !INJECT.equals(inject.desc) || MixinFit.value(inject, "slice") != null) return false;
		List<String> methods = MixinFit.stringList(MixinFit.value(inject, "method"));
		if (methods.size() != 1 || !(methods.getFirst().equals(HANDLE) || methods.getFirst().equals(HANDLE + HANDLE_DESC))) return false;
		List<AnnotationNode> at = MixinFit.atNodes(inject);
		if (at.size() != 1 || !"HEAD".equals(MixinFit.value(at.getFirst(), "value"))) return false;
		TypeInsnNode test = null;
		for (AbstractInsnNode insn : handler.instructions) {
			if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.INSTANCEOF) {
				if (test != null) return false;   // serves more than one kind: not the configuration-only handler
				test = type;
			}
		}
		if (test == null || !servesConfiguration(test.desc)) return false;
		AbstractInsnNode branch = nextReal(test);
		if (!(branch instanceof JumpInsnNode jump) || jump.getOpcode() != Opcodes.IFEQ) return false;
		return nextReal(jump.label) instanceof TypeInsnNode refusal && refusal.getOpcode() == Opcodes.NEW && REFUSAL.equals(refusal.desc);
	}

	/** The addon the handler serves is a configuration addon, never the play listener or a play addon. */
	static boolean servesConfiguration(String type) {
		String simple = type.substring(type.lastIndexOf('/') + 1);
		return simple.contains("Configuration") && !simple.contains("Play") && !GAME_LISTENER.equals(type);
	}

	private static AbstractInsnNode nextReal(AbstractInsnNode insn) {
		AbstractInsnNode next = insn.getNext();
		while (next != null && next.getOpcode() < 0) next = next.getNext();
		return next;
	}

	/** The base's play listener declares {@code handleCustomPayload} and hands the packet to its super's. */
	static boolean playListenerCallsSuper(ClassNode game) {
		if (game == null) return false;
		for (MethodNode m : game.methods) {
			if (!HANDLE.equals(m.name) || !HANDLE_DESC.equals(m.desc)) continue;
			for (AbstractInsnNode insn : m.instructions) {
				if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
						&& COMMON_LISTENER.equals(call.owner) && HANDLE.equals(call.name) && HANDLE_DESC.equals(call.desc)) return true;
			}
		}
		return false;
	}

	/** {@code if (this instanceof ServerGamePacketListenerImpl) return;} — at the head, so the frame after it is the entry frame. */
	private static InsnList playListenerReturns() {
		InsnList guard = new InsnList();
		LabelNode configuration = new LabelNode();
		guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
		guard.add(new TypeInsnNode(Opcodes.INSTANCEOF, GAME_LISTENER));
		guard.add(new JumpInsnNode(Opcodes.IFEQ, configuration));
		guard.add(new InsnNode(Opcodes.RETURN));
		guard.add(configuration);
		guard.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
		return guard;
	}
}
