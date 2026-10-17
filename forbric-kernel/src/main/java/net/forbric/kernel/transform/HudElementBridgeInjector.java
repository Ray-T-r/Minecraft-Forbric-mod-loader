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
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.boot.KernelHudBridge;
import net.forbric.kernel.util.ForbricLog;

/**
 * Puts MinecraftForge's overlay stack onto NeoForge's {@code GuiLayerManager}: a call to
 * {@link KernelHudBridge#addForgeOverlayLayers} at the end of {@code initModdedLayers}.
 *
 * <p>It once also routed every layer {@code add}ed to the manager through a kernel wrapper that redrew fabric-api's HUD
 * roots; fabric-rendering-v1's own {@code HudMixin} dispatches every root now, so that half was retired (see
 * {@link KernelHudBridge}).
 */
public final class HudElementBridgeInjector implements ClassTransformer {
	private static final String TARGET = "net.neoforged.neoforge.client.gui.GuiLayerManager";

	private static final String HOOK_OWNER = "net/forbric/kernel/boot/KernelHudBridge";
	private static final String INIT_METHOD = "initModdedLayers";
	private static final String OVERLAY_HOOK = "addForgeOverlayLayers";
	private static final String OVERLAY_DESC = "(Ljava/lang/Object;)V";

	@Override
	public String name() {
		return "forbric-hud-element-bridge";
	}

	@Override
	public AnchorSet anchors() {
		return AnchorSet.of(new AnchorSet.Anchor(TARGET, AnchorSet.Severity.REQUIRED,
				"a MinecraftForge mod's HUD overlay layers would be registered somewhere nothing reads, and simply not draw"));
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0) return classBytes;
		if (!TARGET.equals(className)) return classBytes;

		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);

		int overlays = appendForgeOverlayInstall(node);
		if (overlays == 0) return classBytes;
		ForbricLog.info("[Forbric/HudBridge] MinecraftForge's overlay stack goes onto %s at the end of %s", className, INIT_METHOD);
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	/**
	 * Calls the kernel at the END of {@code initModdedLayers}, with the manager.
	 *
	 * <p>That method is where NeoForge posts its own layer-registration event, so its end is the one moment where
	 * every mod has registered and the HUD has not yet drawn a frame — which is exactly where MinecraftForge's
	 * overlay stack has to go on. The merged base names {@code ForgeLayeredDraw} nowhere at all, so without this
	 * there is no seam: it is not a hook that lost a byte merge, it is a hook that is absent.
	 *
	 * <p>Appended before each RETURN rather than at the head, so a NeoForge layer never sits above the
	 * MinecraftForge stack by accident of ordering.
	 */
	private int appendForgeOverlayInstall(ClassNode node) {
		int appended = 0;
		for (MethodNode method : node.methods) {
			if (!INIT_METHOD.equals(method.name) || !"()V".equals(method.desc)) continue;
			if (alreadyInstalled(method)) continue;

			for (var insn : method.instructions.toArray()) {
				if (insn.getOpcode() != Opcodes.RETURN) continue;
				InsnList call = new InsnList();
				call.add(new VarInsnNode(Opcodes.ALOAD, 0));
				call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER, OVERLAY_HOOK, OVERLAY_DESC, false));
				method.instructions.insertBefore(insn, call);
				appended++;
			}
			method.maxStack = Math.max(method.maxStack, 1);
		}
		if (appended == 0) {
			ForbricLog.warn("[Forbric/HudBridge] %s has no %s()V to append to — a MinecraftForge mod's HUD overlay "
					+ "layers will render nothing", node.name, INIT_METHOD);
		}
		return appended;
	}

	private static boolean alreadyInstalled(MethodNode method) {
		for (var insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && HOOK_OWNER.equals(call.owner)
					&& OVERLAY_HOOK.equals(call.name)) {
				return true;
			}
		}
		return false;
	}

}
