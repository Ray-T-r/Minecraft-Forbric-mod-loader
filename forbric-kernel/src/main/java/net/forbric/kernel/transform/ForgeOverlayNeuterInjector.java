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
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Gives the kernel the one instant where MinecraftForge's overlay tree can be made safe to render.
 *
 * <p>{@link net.forbric.kernel.runtime.KernelForgeOverlayLayers} builds that tree because the merged base
 * carries no reference to {@code ForgeLayeredDraw} at all. It used to seed the 26 vanilla layer NAMES flat, so
 * the vanilla HUD would not be drawn a second time — and that is exactly why Xaero's minimap never appeared:
 * {@code locateStack} matches {@code this.name} and then recurses only through {@code subLayerStacks}
 * (disassembled: bci 0-8, then 11-20). It never reads {@code namedLayers}. A flat tree therefore has no STACKS
 * in it, so the four-argument {@code addBelow(HOTBAR_AND_DECOS, xaerohud:hud, SPECTATOR_HOTBAR, layer)} that
 * every real mod uses takes the {@code ifPresentOrElse} else-branch and the layer is dropped, with
 * {@code "Target stack minecraft:hotbar was not present anywhere"} as the only trace.
 *
 * <p>So the tree has to be built for real, by {@code ForgeLayeredDraw.init}, and the vanilla LEAVES neutered
 * instead of never being created. The difficulty is timing, and this seam is the whole answer to it.
 *
 * <p>{@code resolveLayers()} is:
 * <pre>
 *   0: if (!order.isEmpty())
 *  12:     ForgeEventFactoryClient.onComputeLayerOrder(this)   // posts AddGuiOverlayLayersEvent
 *  17:     resolveNested()                                     // bakes namedLayers into bakedLayers
 *  24:     order.clear()
 * </pre>
 * Entering at bci 0 is BEFORE the post and BEFORE the bake. That removes the need for any identity snapshot,
 * and both snapshot designs that were proposed are wrong for reasons that only show up later:
 * <ul>
 * <li>snapshot AFTER the event — {@code replace()} writes the MOD's object into {@code namedLayers} under the
 *     vanilla id, so the snapshot would neuter the mod's layer instead of vanilla's;</li>
 * <li>snapshot BEFORE the event, applied to {@code bakedLayers} by identity — {@code addConditionTo} bakes a
 *     freshly built wrapper that no earlier snapshot contains, so that vanilla leaf survives and draws twice.</li>
 * </ul>
 * Replacing map VALUES here, before anyone else can touch them, is immune to both: {@code resolveNested} reads
 * {@code namedLayers.get(name)} at bake time, which is after us.
 *
 * <p>A transformer rather than a {@code Priority.HIGHEST} listener on the event: a listener can be outranked by
 * a mod that registered at the same priority earlier (the bus re-sorts descending but the sort is stable), it
 * needs the bus before the kernel has it, and a drifted anchor would be silent. Here a drift is REPORTED,
 * because the anchor is REQUIRED.
 *
 * <p>Note the descriptor. {@code resolveLayers} returns {@code ForgeLayeredDraw}, not {@code void} — anchoring
 * on {@code ()V} matches nothing, loudly but uselessly.
 */
public final class ForgeOverlayNeuterInjector implements ClassTransformer {
	static final String TARGET = "net.minecraftforge.client.gui.overlay.ForgeLayeredDraw";
	static final String METHOD = "resolveLayers";
	/** Returns {@code this}, for chaining. Getting this wrong is an anchor that can never match. */
	static final String DESC = "()Lnet/minecraftforge/client/gui/overlay/ForgeLayeredDraw;";
	private static final String HOOK_OWNER = "net/forbric/kernel/runtime/KernelForgeOverlayLayers";
	private static final String HOOK_NAME = "neuterVanillaLeaves";
	private static final String HOOK_DESC = "(Ljava/lang/Object;)V";

	@Override
	public String name() {
		return "forbric-forge-overlay-neuter";
	}

	@Override
	public AnchorSet anchors() {
		return AnchorSet.of(new AnchorSet.Anchor(TARGET, AnchorSet.Severity.REQUIRED,
				"MinecraftForge's overlay tree cannot be made safe to render, so the kernel refuses to register "
						+ "it at all — every MinecraftForge HUD overlay (Xaero's minimap and world-map crosshair) "
						+ "draws nothing, which is the behaviour this repair exists to end"));
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0) return classBytes;
		if (!TARGET.equals(className)) return classBytes;

		ClassNode node = new ClassNode();
		// Flags 0, as HudElementBridgeInjector does: the frames are kept as nodes and written back. NOT
		// SKIP_FRAMES — that DISCARDS the StackMapTable, and with ClassWriter(0) it ships a frameless class;
		// resolveLayers has an `ifne` at bci 9, so the verifier rejects it inside Minecraft.<init>. Measured
		// this week on a different transformer, and it cost a boot.
		new ClassReader(classBytes).accept(node, 0);

		int edited = 0;
		for (MethodNode m : node.methods) {
			if (!METHOD.equals(m.name) || !DESC.equals(m.desc)) continue;
			if (alreadyHooked(m)) continue;
			InsnList prologue = new InsnList();
			prologue.add(new VarInsnNode(Opcodes.ALOAD, 0));
			prologue.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER, HOOK_NAME, HOOK_DESC, false));
			m.instructions.insert(prologue);
			// Straight-line, no new branch target, so no frame is authored. One reference on the stack, and the
			// method's existing max is already at least that.
			m.maxStack = Math.max(m.maxStack, 1);
			edited++;
		}

		if (edited == 0) {
			ForbricLog.warn("[Forbric/HudBridge] %s has no %s%s to enter — MinecraftForge's overlay tree cannot be "
					+ "neutered before it bakes, so the kernel will refuse to register it and its mods' HUD "
					+ "overlays will not draw", className, METHOD, DESC);
			return classBytes;
		}

		ForbricLog.info("[Forbric/HudBridge] entering %s.%s before it posts its registration event — the kernel "
				+ "neuters the vanilla leaves there, which is the one instant no mod has touched them yet",
				className, METHOD);
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** So a class offered twice — the pre-mixin read and the define — is not hooked twice. */
	private static boolean alreadyHooked(MethodNode m) {
		for (AbstractInsnNode insn : m.instructions) {
			if (insn instanceof MethodInsnNode call && HOOK_OWNER.equals(call.owner) && HOOK_NAME.equals(call.name)) {
				return true;
			}
		}
		return false;
	}
}
