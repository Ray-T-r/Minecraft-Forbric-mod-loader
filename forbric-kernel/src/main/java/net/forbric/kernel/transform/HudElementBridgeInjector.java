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
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.boot.KernelHudBridge;
import net.forbric.kernel.util.ForbricLog;

/**
 * Routes every layer registered with NeoForge's {@code GuiLayerManager} through {@link KernelHudBridge}, so
 * Fabric mods' HUD elements draw again. Why they stopped — and why re-anchoring Fabric's mixin is impossible on
 * this base — is in that class.
 *
 * <p>The wrap goes on the ARGUMENT of {@code add}, not on the layer list or on {@code NamedLayer}. The three-arg
 * overload wraps the incoming layer in a {@code BooleanSupplier} gate, and wrapping the argument puts the bridge
 * INSIDE that gate. That is the faithful placement: on stock Fabric the {@code @At(INVOKE)} anchors sit inside
 * vanilla's own conditionals, so a suppressed layer suppresses the elements attached to it. Wrapping further out
 * would draw Fabric elements over a HUD vanilla had decided to hide.
 *
 * <p>Only the two {@code GuiLayer}-taking overloads are touched. The {@code Consumer} overload delegates to the
 * three-arg one, so it is covered for free; {@code add(GuiLayerManager, BooleanSupplier)} re-adds a sub-manager's
 * already-wrapped layers through the same overload, which is why {@code wrap} checks for its own generated type
 * before wrapping again. NeoForge mods registering through {@code RegisterGuiLayersEvent} manipulate the layer
 * list directly and never reach {@code add}, so their layers are correctly left alone — a NeoForge layer has no
 * business dispatching Fabric roots.
 */
public final class HudElementBridgeInjector implements ClassTransformer {
	private static final String TARGET = "net.neoforged.neoforge.client.gui.GuiLayerManager";
	private static final String METHOD = "add";
	private static final String LAYER = "Lnet/neoforged/neoforge/client/gui/GuiLayer;";
	private static final String IDENTIFIER = "Lnet/minecraft/resources/Identifier;";

	private static final String HOOK_OWNER = "net/forbric/kernel/boot/KernelHudBridge";
	private static final String HOOK_NAME = "wrap";
	// Boot-side, so neither Identifier nor GuiLayer can be named at compile time; the CHECKCAST below puts the
	// declared type back. Same widening-reference trick as ClientPackHookInjector.HOOK_DESC.
	private static final String HOOK_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	private int routed;

	@Override
	public String name() {
		return "forbric-hud-element-bridge";
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0) return classBytes;
		if (!TARGET.equals(className)) return classBytes;

		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);

		int edited = 0;
		for (MethodNode method : node.methods) {
			if (!METHOD.equals(method.name) || !takesLayerAtSlotTwo(method.desc)) continue;
			if (alreadyRouted(method)) continue;

			Type layerType = Type.getArgumentTypes(method.desc)[1];
			InsnList prologue = new InsnList();
			prologue.add(new VarInsnNode(Opcodes.ALOAD, 1));
			prologue.add(new VarInsnNode(Opcodes.ALOAD, 2));
			prologue.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER, HOOK_NAME, HOOK_DESC, false));
			prologue.add(new TypeInsnNode(Opcodes.CHECKCAST, layerType.getInternalName()));
			prologue.add(new VarInsnNode(Opcodes.ASTORE, 2));
			method.instructions.insert(prologue);
			// Straight-line, no new locals and no branch: only the stack depth can grow, and only to two.
			method.maxStack = Math.max(method.maxStack, 2);
			edited++;
		}

		if (edited == 0) {
			ForbricLog.warn("[Forbric/HudBridge] %s has no add(Identifier, GuiLayer[, BooleanSupplier]) to route — the "
					+ "seam has drifted, so Fabric HUD elements will render nothing", className);
			return classBytes;
		}

		routed += edited;
		ForbricLog.info("[Forbric/HudBridge] routed %d GuiLayerManager.add overload(s) through the kernel — Fabric's "
				+ "HudElementRegistry dispatches off NeoForge's layer manager from here", edited);

		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** {@code add(Identifier, GuiLayer)} and {@code add(Identifier, GuiLayer, BooleanSupplier)}, and nothing else. */
	private static boolean takesLayerAtSlotTwo(String desc) {
		Type[] args = Type.getArgumentTypes(desc);
		return args.length >= 2 && IDENTIFIER.equals(args[0].getDescriptor()) && LAYER.equals(args[1].getDescriptor());
	}

	private static boolean alreadyRouted(MethodNode method) {
		for (var insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && HOOK_OWNER.equals(call.owner) && HOOK_NAME.equals(call.name)) {
				return true;
			}
		}
		return false;
	}

	/** How many overloads were routed, for the boot summary. */
	public int routedOverloads() {
		return routed;
	}
}
