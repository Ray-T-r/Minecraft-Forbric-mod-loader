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
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Keeps the HUD on one pipeline, NeoForge's layer manager, when the merged {@code Hud} also carries MinecraftForge's.
 *
 * <p>The kernel's design for MinecraftForge HUD overlays ({@link net.forbric.kernel.runtime.KernelForgeOverlayLayers})
 * is that NeoForge's {@code GuiLayerManager} draws the whole HUD — vanilla's layers, NeoForge's, and Fabric's elements
 * dispatched off it — and MinecraftForge's overlay tree is built by the kernel at the end of {@code initModdedLayers},
 * once every mod is loaded, with its vanilla leaves silenced, and added to that manager as one more layer.
 *
 * <p>A base that keeps both families' edits of {@code Hud} breaks both halves of that, from MinecraftForge's own code:
 * <ul>
 *   <li>{@code Hud.<init>} calls {@code ForgeLayeredDraw.init(this, minecraft)}, building MinecraftForge's tree and
 *       posting its registration event when the HUD is constructed. The kernel's build later finds the mods' leaves
 *       already in it, cannot tell them from vanilla's, and refuses to register the stack; the first build had
 *       already silenced the vanilla leaves.</li>
 *   <li>{@code Hud.extractRenderState} opens with MinecraftForge's {@code if (true) { ForgeLayeredDraw.extractRenderState
 *       (graphics, delta); return; }}, so NeoForge's {@code GuiLayerManager.render} after it never runs: no NeoForge
 *       layer and no Fabric HUD element draws, and with the vanilla leaves silenced neither does the vanilla HUD.</li>
 * </ul>
 * So, in a {@code Hud} that draws through NeoForge's layer manager, the native {@code ForgeLayeredDraw.init} call is
 * dropped (its two arguments popped — the kernel's build is the one build) and the native
 * {@code ForgeLayeredDraw.extractRenderState} call that returns is dropped with its {@code return}, so the method goes
 * on into NeoForge's draw, which now holds MinecraftForge's stack as one of its layers. Keyed on the calls alone; a
 * {@code Hud} that does not draw through NeoForge's manager, or that names neither call, is left as it is.
 *
 * <p>Both edits replace an instruction that consumes two references and leaves nothing with {@code POP2}, and the
 * dropped {@code return} falls through to the label its own guard jumps to, whose frame already has the state the
 * fall-through arrives with: no frame, branch or handler is authored.
 */
public final class ForgeHudPipelineInjector implements ClassTransformer {
	static final String TARGET = "net.minecraft.client.gui.Hud";
	private static final String FORGE_TREE = "net/minecraftforge/client/gui/overlay/ForgeLayeredDraw";
	private static final String INIT = "init";
	private static final String INIT_DESC = "(Lnet/minecraft/client/gui/Hud;Lnet/minecraft/client/Minecraft;)V";
	private static final String DRAW = "extractRenderState";
	private static final String DRAW_DESC = "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V";
	private static final String NEO_LAYER_MANAGER = "net/neoforged/neoforge/client/gui/GuiLayerManager";
	private static final String NEO_RENDER = "render";

	@Override
	public String name() {
		return "forbric-forge-hud-pipeline";
	}

	@Override
	public AnchorSet anchors() {
		// HEDGE: a base whose Hud is NeoForge's alone names neither call, and there is nothing to do.
		return AnchorSet.of(new AnchorSet.Anchor(TARGET, AnchorSet.Severity.HEDGE,
				"a merged Hud that also runs MinecraftForge's overlay tree draws only that tree: NeoForge's layers, "
						+ "Fabric's HUD elements and (once the kernel silenced its vanilla leaves) the vanilla HUD vanish"));
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0 || !TARGET.equals(className)) return classBytes;

		ClassNode node = new ClassNode();
		// Flags 0: frames kept as nodes and written back as they are, which is all these edits need.
		new ClassReader(classBytes).accept(node, 0);
		if (!drawsThroughNeoForgesManager(node)) return classBytes;

		int builds = 0, draws = 0;
		for (MethodNode m : node.methods) {
			for (AbstractInsnNode insn : m.instructions.toArray()) {
				if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC
						|| !FORGE_TREE.equals(call.owner)) continue;
				if (INIT.equals(call.name) && INIT_DESC.equals(call.desc)) {
					m.instructions.set(call, new InsnNode(Opcodes.POP2));
					builds++;
				} else if (DRAW.equals(call.name) && DRAW_DESC.equals(call.desc)) {
					AbstractInsnNode exit = nextOpcode(call);
					if (exit == null || exit.getOpcode() != Opcodes.RETURN || !reachesNeoForgesDrawAfter(exit)) continue;
					m.instructions.set(call, new InsnNode(Opcodes.POP2));
					m.instructions.remove(exit);
					draws++;
				}
			}
		}
		if (builds + draws == 0) return classBytes;

		ForbricLog.info("[Forbric/HudBridge] %s kept MinecraftForge's own overlay tree (built in %d place(s), drawn instead "
				+ "of NeoForge's layer manager in %d) — dropped: the kernel builds that tree once every mod is loaded and "
				+ "puts it on NeoForge's manager, the one HUD pipeline", className, builds, draws);
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** Whether any method of the class draws through NeoForge's {@code GuiLayerManager.render}. */
	private static boolean drawsThroughNeoForgesManager(ClassNode node) {
		for (MethodNode m : node.methods) {
			for (AbstractInsnNode insn : m.instructions) {
				if (isNeoForgesDraw(insn)) return true;
			}
		}
		return false;
	}

	/** Whether NeoForge's draw follows {@code from} in the same method, so dropping the return before it reaches it. */
	private static boolean reachesNeoForgesDrawAfter(AbstractInsnNode from) {
		for (AbstractInsnNode insn = from.getNext(); insn != null; insn = insn.getNext()) {
			if (isNeoForgesDraw(insn)) return true;
		}
		return false;
	}

	private static boolean isNeoForgesDraw(AbstractInsnNode insn) {
		return insn instanceof MethodInsnNode call && NEO_LAYER_MANAGER.equals(call.owner) && NEO_RENDER.equals(call.name);
	}

	private static AbstractInsnNode nextOpcode(AbstractInsnNode from) {
		for (AbstractInsnNode insn = from.getNext(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() >= 0) return insn;
		}
		return null;
	}
}
