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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.util.ForbricLog;

/**
 * Repairs class-local bytecode invariants that can drift when two patched Minecraft bases are merged.
 */
public final class ForbricMergedBaseCompatTransformer implements ClassTransformer {
	@Override
	public String name() {
		return "forbric-merged-base-compat";
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0) return classBytes;
		try {
			ClassNode node = new ClassNode();
			new ClassReader(classBytes).accept(node, 0);
			boolean changed = repairLambdaBootstrapHandles(node);
			changed |= addBlockStateModelConflictResolvers(node);
			changed |= addMissingForgeFluidTypeBridge(node);
			changed |= addMissingForgeKeyMappingLookupInitializer(node);
			changed |= routeKeyMappingClickToPopulatedLookup(node);
			changed |= dropInterfaceDefaultShadowingOverrides(node);
			changed |= tolerateEmptyCreativeTabStacks(node);
			changed |= routePlaceItemHookToNeoForge(node);
			changed |= bridgeOrphanedPipRenderers(node);
			changed |= keepForgeOutboundProtocolCurrent(node);
			changed |= surviveTheMissingForgeModelDataManager(node);
			changed |= dropTheWindowTitlesLoaderBrand(node);
			if (!changed) return classBytes;

			ClassWriter writer = new ClassWriter(0);
			node.accept(writer);
			return writer.toByteArray();
		} catch (RuntimeException e) {
			ForbricLog.warn("[Forbric/MergedBaseCompat] could not inspect " + className, e);
			return classBytes;
		}
	}

	private static boolean repairLambdaBootstrapHandles(ClassNode node) {
		Map<String, MethodNode> methods = new HashMap<>();
		for (MethodNode method : node.methods) {
			methods.put(method.name + method.desc, method);
		}

		boolean changed = false;
		for (MethodNode caller : node.methods) {
			for (AbstractInsnNode insn = caller.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof InvokeDynamicInsnNode indy) || indy.bsmArgs == null) continue;
				for (int i = 0; i < indy.bsmArgs.length; i++) {
					if (!(indy.bsmArgs[i] instanceof Handle handle)) continue;
					Handle repaired = repairLambdaHandle(node, methods, caller, indy, handle);
					if (repaired == handle) continue;
					indy.bsmArgs[i] = repaired;
					changed = true;
				}
			}
		}
		return changed;
	}

	private static boolean addBlockStateModelConflictResolvers(ClassNode node) {
		if (!"net/minecraft/client/renderer/block/dispatch/BlockStateModel".equals(node.name)) return false;

		boolean changed = false;
		if (!hasMethod(node, "createGeometryKey",
				"(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;"
						+ "Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/util/RandomSource;)"
						+ "Ljava/lang/Object;")) {
			MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "createGeometryKey",
					"(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;"
							+ "Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/util/RandomSource;)"
							+ "Ljava/lang/Object;",
					null, null);
			method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
			method.instructions.add(new InsnNode(Opcodes.ARETURN));
			method.maxStack = 1;
			method.maxLocals = 5;
			node.methods.add(method);
			changed = true;
		}

		if (!hasMethod(node, "particleMaterial",
				"(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;"
						+ "Lnet/minecraft/world/level/block/state/BlockState;)"
						+ "Lnet/minecraft/client/resources/model/sprite/Material$Baked;")) {
			MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "particleMaterial",
					"(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;"
							+ "Lnet/minecraft/world/level/block/state/BlockState;)"
							+ "Lnet/minecraft/client/resources/model/sprite/Material$Baked;",
					null, null);
			method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
			method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
					"net/minecraft/client/renderer/block/dispatch/BlockStateModel",
					"particleMaterial",
					"()Lnet/minecraft/client/resources/model/sprite/Material$Baked;",
					true));
			method.instructions.add(new InsnNode(Opcodes.ARETURN));
			method.maxStack = 1;
			method.maxLocals = 4;
			node.methods.add(method);
			changed = true;
		}

		if (!hasMethod(node, "materialFlags",
				"(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;"
						+ "Lnet/minecraft/world/level/block/state/BlockState;)I")) {
			MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "materialFlags",
					"(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;"
							+ "Lnet/minecraft/world/level/block/state/BlockState;)I",
					null, null);
			method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
			method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
					"net/minecraft/client/renderer/block/dispatch/BlockStateModel",
					"materialFlags", "()I", true));
			method.instructions.add(new InsnNode(Opcodes.IRETURN));
			method.maxStack = 1;
			method.maxLocals = 4;
			node.methods.add(method);
			changed = true;
		}

		if (changed) {
			ForbricLog.warn("[Forbric/MergedBaseCompat] added BlockStateModel default-method conflict resolvers");
		}
		return changed;
	}

	private static boolean addMissingForgeFluidTypeBridge(ClassNode node) {
		if ((node.access & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT)) != 0) return false;
		if (!node.name.startsWith("net/minecraft/world/level/material/")) return false;
		if (!node.interfaces.contains("net/neoforged/neoforge/common/extensions/IFluidExtension")) return false;
		if (hasMethod(node, "getFluidType", "()Lnet/minecraftforge/fluids/FluidType;")) return false;

		MethodNode bridge = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
				"getFluidType", "()Lnet/minecraftforge/fluids/FluidType;", null, null);
		bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
				"net/forbric/loader/impl/forge/runtime/ForbricForgeRuntimeInterop",
				"forgeFluidType", "(Ljava/lang/Object;)Ljava/lang/Object;", false));
		bridge.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraftforge/fluids/FluidType"));
		bridge.instructions.add(new InsnNode(Opcodes.ARETURN));
		bridge.maxStack = 1;
		bridge.maxLocals = 1;
		node.methods.add(bridge);
		ForbricLog.warn("[Forbric/MergedBaseCompat] added Forge FluidType bridge to %s",
				node.name.replace('/', '.'));
		return true;
	}

	private static boolean addMissingForgeKeyMappingLookupInitializer(ClassNode node) {
		if (!"net/minecraft/client/KeyMapping".equals(node.name)) return false;
		String forgeLookup = "Lnet/minecraftforge/client/settings/KeyMappingLookup;";
		if (!hasField(node, "MAP", forgeLookup) || initializesStaticField(node, "MAP", forgeLookup)) return false;

		MethodNode clinit = findMethod(node, "<clinit>", "()V");
		if (clinit == null) {
			clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
			clinit.instructions.add(new InsnNode(Opcodes.RETURN));
			clinit.maxLocals = 0;
			node.methods.add(clinit);
		}

		boolean inserted = false;
		for (AbstractInsnNode insn = clinit.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() != Opcodes.RETURN) continue;
			clinit.instructions.insertBefore(insn, new TypeInsnNode(Opcodes.NEW,
					ForeignType.KEY_MAPPING_LOOKUP.internal(Ecosystem.FORGE)));
			clinit.instructions.insertBefore(insn, new InsnNode(Opcodes.DUP));
			clinit.instructions.insertBefore(insn, new MethodInsnNode(Opcodes.INVOKESPECIAL,
					ForeignType.KEY_MAPPING_LOOKUP.internal(Ecosystem.FORGE), "<init>", "()V", false));
			clinit.instructions.insertBefore(insn, new FieldInsnNode(Opcodes.PUTSTATIC,
					"net/minecraft/client/KeyMapping", "MAP", forgeLookup));
			inserted = true;
		}
		if (!inserted) return false;

		clinit.maxStack = Math.max(clinit.maxStack, 2);
		ForbricLog.warn("[Forbric/MergedBaseCompat] initialized Forge KeyMapping lookup on merged client base");
		return true;
	}

	/**
	 * Points {@code KeyMapping.click} at the key lookup that registration actually populates.
	 *
	 * <p>The byte-merge left {@code KeyMapping} with TWO static fields both named {@code MAP} — NeoForge's
	 * {@code KeyMappingLookup} and MinecraftForge's (same name, different descriptor: legal in bytecode, unwritable
	 * in Java source). Every WRITE goes to the NeoForge one ({@code registerMapping}, the constructors,
	 * {@code setKeyModifierAndCode}, {@code resetMapping}), and {@code <clinit>} only ever assigned that one. The
	 * merge also kept BOTH {@code forAllKeyMappings} overloads, and they READ different maps: the 3-arg one — used
	 * by {@code KeyMapping.set}, which drives {@code isDown} — reads NeoForge's, while the 2-arg one, whose single
	 * caller is {@code KeyMapping.click} (it drives {@code clickCount}), reads MinecraftForge's.
	 *
	 * <p>So the Forge lookup is permanently EMPTY and {@code click} matches nothing: {@code clickCount} never
	 * increments and {@code consumeClick()} is forever false. That kills every {@code consumeClick}-driven key for
	 * vanilla AND every mod — inventory (E), chat (T), command ({@code /}), drop (Q) — while {@code isDown} keys
	 * (WASD, sneak, attack) keep working, because {@code set} reads the populated map. ESC still opens the pause
	 * menu, because that is a direct key-code check in {@code KeyboardHandler}, not a {@code KeyMapping} — which is
	 * exactly the "ESC pauses but E does nothing" shape this presents as.
	 *
	 * <p>Both {@code getAll(InputConstants$Key)} overloads return {@code List<KeyMapping>}, so redirecting the field
	 * read and the call is descriptor-identical. {@link #addMissingForgeKeyMappingLookupInitializer} still runs, so
	 * the Forge lookup stays non-null for any Forge code that reaches for it directly.
	 */
	private static boolean routeKeyMappingClickToPopulatedLookup(ClassNode node) {
		if (!"net/minecraft/client/KeyMapping".equals(node.name)) return false;

		String forgeLookup = "Lnet/minecraftforge/client/settings/KeyMappingLookup;";
		String neoLookup = "Lnet/neoforged/neoforge/client/settings/KeyMappingLookup;";
		// Only meaningful when the merge actually produced BOTH lookups; a single-ecosystem base is already coherent.
		if (!hasField(node, "MAP", forgeLookup) || !hasField(node, "MAP", neoLookup)) return false;

		MethodNode lookup = findMethod(node, "forAllKeyMappings",
				"(Lcom/mojang/blaze3d/platform/InputConstants$Key;Ljava/util/function/Consumer;)V");
		if (lookup == null) return false;

		boolean changed = false;
		for (AbstractInsnNode insn = lookup.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() == Opcodes.GETSTATIC && insn instanceof FieldInsnNode field
					&& "MAP".equals(field.name) && forgeLookup.equals(field.desc)) {
				field.desc = neoLookup;
				changed = true;
			} else if (insn instanceof MethodInsnNode call
					&& ForeignType.KEY_MAPPING_LOOKUP.internal(Ecosystem.FORGE).equals(call.owner)
					&& "getAll".equals(call.name)) {
				call.owner = ForeignType.KEY_MAPPING_LOOKUP.internal(Ecosystem.NEOFORGE);
				changed = true;
			}
		}
		if (!changed) return false;

		ForbricLog.warn("[Forbric/MergedBaseCompat] routed KeyMapping.click to the populated (NeoForge) key lookup "
				+ "— the merge left it reading the Forge-side MAP, which is never written, so every consumeClick key "
				+ "(inventory/chat/command/drop) was dead");
		return true;
	}

	/**
	 * Removes GUI overrides whose whole body is {@code SomeInterface.super.sameMethod(args)}.
	 *
	 * <p>The byte-merge gives many client GUI classes an {@code implements ContainerEventHandler} they do not have in
	 * vanilla, plus a {@code keyPressed(KeyEvent)} override that does nothing but call the INTERFACE DEFAULT. On a
	 * plain widget that is harmless — nothing in its superclass chain declares {@code keyPressed}, so the default
	 * applies either way. On a {@code Screen} subclass it is a silent functional break: a class method beats an
	 * interface default, so the injected override SHADOWS {@code Screen.keyPressed} — and {@code Screen.keyPressed}
	 * is the only place the {@code isEscape() -> shouldCloseOnEsc() -> onClose()} branch lives.
	 *
	 * <p>Symptom: ESC cannot close the pause menu (or the options/world-selection screens), while ESC still closes
	 * the inventory, because {@code AbstractContainerScreen} carries its own ESC handling. Verified against the
	 * unmerged 26.2 client: vanilla {@code PauseScreen} is {@code extends Screen} with NO {@code keyPressed} override
	 * and no {@code ContainerEventHandler}, so the override is purely a merge artifact.
	 *
	 * <p>Deleting it is safe in BOTH shapes for THIS method, which is why this needs no class-hierarchy walk: for a
	 * {@code Screen} subclass the inherited {@code Screen.keyPressed} takes over (exactly vanilla dispatch), and for
	 * a widget with no superclass declaration the interface default still resolves — the same method that was being
	 * called explicitly. {@code ContainerEventHandler} extends {@code GuiEventListener}, so the two {@code keyPressed}
	 * defaults are ordered by specificity and deleting the override cannot create an ambiguity.
	 *
	 * <p><b>Restricted to methods {@code ContainerEventHandler} itself refines, and that restriction is
	 * load-bearing.</b> The same "body is only {@code Iface.super.same()}" shape ALSO expresses Java's mandatory
	 * diamond disambiguation: when two UNRELATED interfaces each supply the default, the class must override to pick
	 * one, and deleting that is not a no-op but unresolvable. Generalising by shape alone removed
	 * {@code getRectangle} — supplied by both {@code LayoutElement} and {@code GuiEventListener} — and every GUI
	 * screen died at the title screen on {@code IncompatibleClassChangeError: Conflicting default methods}.
	 *
	 * <p>{@link #SHADOWABLE} is exactly the set where that cannot happen: each entry is declared {@code default} by
	 * BOTH {@code ContainerEventHandler} and {@code GuiEventListener}, and since
	 * {@code ContainerEventHandler extends GuiEventListener} its version is strictly more specific, so removing an
	 * override always resolves to one winner. Verified against the merged jar: no other interface anywhere in
	 * {@code net/minecraft/client/gui/} declares any of them — whereas {@code getRectangle}, the one that broke, is
	 * NOT refined by {@code ContainerEventHandler} and so is correctly excluded by this rule.
	 *
	 * <p>Why the whole set and not just the one method that was reported: the merge injects these blindly, and each
	 * one silently shadows whatever real implementation the superclass chain had. {@code keyPressed} cost ESC on the
	 * pause menu; {@code mouseScrolled} cost ALL list scrolling ({@code AbstractContainerWidget} shadowed
	 * {@code AbstractScrollArea}'s real wheel handling, and {@code AbstractSelectionList} sits under it, so every
	 * scrollable list — mod list, world list, options — was dead); the click/drag/char entries are the same latent
	 * bug on paths nobody has exercised yet. Removing a delegate whose superclass chain has no real implementation
	 * is a no-op, so applying this to the whole set costs nothing and closes the rest of the family.
	 */
	private static final String CONTAINER_EVENT_HANDLER =
			"net/minecraft/client/gui/components/events/ContainerEventHandler";

	/** name+descriptor of every {@code ContainerEventHandler} default that also refines a {@code GuiEventListener} one. */
	private static final java.util.Set<String> SHADOWABLE = java.util.Set.of(
			"keyPressed(Lnet/minecraft/client/input/KeyEvent;)Z",
			"keyReleased(Lnet/minecraft/client/input/KeyEvent;)Z",
			"charTyped(Lnet/minecraft/client/input/CharacterEvent;)Z",
			"preeditUpdated(Lnet/minecraft/client/input/PreeditEvent;)Z",
			"mouseScrolled(DDDD)Z",
			"mouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z",
			"mouseReleased(Lnet/minecraft/client/input/MouseButtonEvent;)Z",
			"mouseDragged(Lnet/minecraft/client/input/MouseButtonEvent;DD)Z");

	private static boolean dropInterfaceDefaultShadowingOverrides(ClassNode node) {
		if (!node.name.startsWith("net/minecraft/client/gui/")) return false;
		if (node.interfaces == null || !node.interfaces.contains(CONTAINER_EVENT_HANDLER) || node.methods == null) {
			return false;
		}

		int before = node.methods.size();
		node.methods.removeIf(method -> isPureInterfaceDefaultDelegate(node, method));
		int removed = before - node.methods.size();
		if (removed == 0) return false;

		ForbricLog.debug("[Forbric/MergedBaseCompat] dropped %d interface-default-shadowing override(s) from %s",
				removed, node.name.replace('/', '.'));
		return true;
	}

	/**
	 * Whether {@code method}'s entire body is {@code ContainerEventHandler.super.<same method>(args…)}, for one of
	 * the {@link #SHADOWABLE} methods. Keyed to that set on purpose — see
	 * {@link #dropInterfaceDefaultShadowingOverrides} for why matching on body shape alone is unsafe.
	 */
	private static boolean isPureInterfaceDefaultDelegate(ClassNode node, MethodNode method) {
		if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0) return false;
		if (!SHADOWABLE.contains(method.name + method.desc)) return false;
		if (method.instructions == null) return false;

		java.util.List<AbstractInsnNode> body = new java.util.ArrayList<>();
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() >= 0) body.add(insn);
		}

		Type[] args = Type.getArgumentTypes(method.desc);
		// this + one load per parameter + the interface-default call + the return, and NOTHING else.
		if (body.size() != args.length + 3) return false;

		if (!(body.get(0) instanceof VarInsnNode self) || self.getOpcode() != Opcodes.ALOAD || self.var != 0) {
			return false;
		}

		int slot = 1;
		for (int i = 0; i < args.length; i++) {
			if (!(body.get(1 + i) instanceof VarInsnNode load)
					|| load.getOpcode() != args[i].getOpcode(Opcodes.ILOAD) || load.var != slot) {
				return false;
			}
			slot += args[i].getSize();
		}

		if (!(body.get(args.length + 1) instanceof MethodInsnNode call)) return false;
		if (call.getOpcode() != Opcodes.INVOKESPECIAL || !call.itf) return false;
		if (!call.name.equals(method.name) || !call.desc.equals(method.desc)) return false;
		if (!CONTAINER_EVENT_HANDLER.equals(call.owner)) return false;

		return body.get(args.length + 2).getOpcode() == Type.getReturnType(method.desc).getOpcode(Opcodes.IRETURN);
	}

	/**
	 * Sends the block-placement hook to NeoForge, whose type the merged snapshot list actually has.
	 *
	 * <p>{@code Level.capturedBlockSnapshots} survived the merge as
	 * {@code ArrayList<net.neoforged.neoforge.common.util.BlockSnapshot>} — NeoForge's element type won, and there is
	 * only ONE such field. But {@code ItemStack.useOn} kept calling MINECRAFTFORGE's
	 * {@code ForgeHooks.onPlaceItemIntoWorld}, which drains that same list expecting
	 * {@code net.minecraftforge.common.util.BlockSnapshot}. So placing ANY block threw
	 * {@code ClassCastException: neoforge…BlockSnapshot cannot be cast to minecraftforge…BlockSnapshot} on the
	 * server thread while handling {@code use_item_on} — the integrated server died the instant you right-clicked.
	 *
	 * <p>{@code CommonHooks.onPlaceItemIntoWorld(UseOnContext)} is NeoForge's counterpart with an IDENTICAL
	 * descriptor, so retargeting the {@code invokestatic} is type-exact and makes the consumer match the producer.
	 * Cost: MinecraftForge mods' {@code BlockEvent.EntityPlaceEvent} no longer fires (NeoForge's does). That is the
	 * same trade the merge already made for the snapshot type itself — the alternative is that nobody can place
	 * anything at all.
	 */
	private static boolean routePlaceItemHookToNeoForge(ClassNode node) {
		if (!node.name.startsWith("net/minecraft/") || node.methods == null) return false;

		boolean changed = false;
		for (MethodNode method : node.methods) {
			if (method.instructions == null) continue;

			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC) continue;
				if (!"net/minecraftforge/common/ForgeHooks".equals(call.owner)
						|| !"onPlaceItemIntoWorld".equals(call.name)) {
					continue;
				}
				call.owner = "net/neoforged/neoforge/common/CommonHooks";
				changed = true;
			}
		}
		if (!changed) return false;

		ForbricLog.warn("[Forbric/MergedBaseCompat] routed %s's block-placement hook to NeoForge — the merged "
				+ "Level.capturedBlockSnapshots holds NeoForge BlockSnapshots, so MinecraftForge's hook threw "
				+ "ClassCastException on every block placed", node.name.replace('/', '.'));
		return true;
	}

	private static final String STACK_COUNT_MESSAGE = "The stack count must be 1";

	/**
	 * Lets a creative tab SKIP an empty stack instead of aborting the whole creative menu.
	 *
	 * <p>{@code CreativeModeTab.Output.accept(ItemLike)} turns its argument into {@code new ItemStack(itemLike)},
	 * which collapses to {@code ItemStack.EMPTY} (count 0) whenever the block has no item form. NeoForge's output
	 * wrapper treats that as a programming error and throws {@code IllegalArgumentException: The stack count must
	 * be 1}; MinecraftForge's path just drops the entry.
	 *
	 * <p>On the merged base NeoForge won {@code CreativeModeTab.buildContents}, so a MINECRAFTFORGE mod's tab is
	 * validated by NEOFORGE's stricter contract — a cross-ecosystem split like the Forge/NeoForge {@code FluidType}
	 * one. Macaw's Bridges feeds its blocks in with {@code accept(ItemLike)}, one of them has no item, and the throw
	 * propagated out of {@code CreativeModeTabs.buildAllTabContents} into
	 * {@code CreativeModeInventoryScreen.<init>} — so opening the creative menu at all crashed the client, and NO
	 * tab (vanilla or modded) was reachable.
	 *
	 * <p>Rewriting the throw to a {@code return} makes the wrapper drop that one entry and keep building, which is
	 * the MinecraftForge behaviour the mod was written against. Only the throw is replaced; the count==1 fast path
	 * is untouched, so well-formed stacks still take the normal route.
	 */
	private static boolean tolerateEmptyCreativeTabStacks(ClassNode node) {
		if (!"net/neoforged/neoforge/event/EventHooks".equals(node.name) || node.methods == null) return false;

		boolean changed = false;
		for (MethodNode method : node.methods) {
			if (!method.name.startsWith("lambda$onCreativeModeTabBuildContents$")) continue;
			changed |= replaceStackCountThrowWithReturn(method);
		}
		if (!changed) return false;

		ForbricLog.warn("[Forbric/MergedBaseCompat] creative-tab output now SKIPS empty stacks instead of throwing "
				+ "— NeoForge won CreativeModeTab.buildContents on the merged base and its stricter contract was "
				+ "aborting the whole creative menu for MinecraftForge mods (Macaw's Bridges)");
		return true;
	}

	/** Replaces {@code throw new IllegalArgumentException("The stack count must be 1")} with a plain {@code return}. */
	private static boolean replaceStackCountThrowWithReturn(MethodNode method) {
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (!(insn instanceof LdcInsnNode ldc) || !STACK_COUNT_MESSAGE.equals(ldc.cst)) continue;

			AbstractInsnNode start = insn;
			while (start != null && !(start.getOpcode() == Opcodes.NEW && start instanceof TypeInsnNode type
					&& "java/lang/IllegalArgumentException".equals(type.desc))) {
				start = start.getPrevious();
			}
			AbstractInsnNode end = insn;
			while (end != null && end.getOpcode() != Opcodes.ATHROW) {
				end = end.getNext();
			}
			if (start == null || end == null) continue;

			// The whole new/dup/ldc/<init>/athrow run pushes and consumes only its own operands, so swapping it for a
			// RETURN leaves the stack exactly as the following frames already describe it.
			method.instructions.insertBefore(start, new MethodInsnNode(Opcodes.INVOKESTATIC,
					"net/forbric/kernel/boot/KernelLifecycle", "onCreativeTabEntrySkipped", "()V", false));
			method.instructions.insertBefore(start, new InsnNode(Opcodes.RETURN));
			for (AbstractInsnNode cur = start; cur != null;) {
				AbstractInsnNode next = cur == end ? null : cur.getNext();
				method.instructions.remove(cur);
				cur = next;
			}
			return true;
		}
		return false;
	}

	private static boolean hasMethod(ClassNode node, String name, String desc) {
		for (MethodNode method : node.methods) {
			if (method.name.equals(name) && method.desc.equals(desc)) return true;
		}
		return false;
	}

	/**
	 * MinecraftForge's network channels pick the vanilla packet type for an outgoing payload from
	 * {@code Connection.getProtocol()}, which reads a Forge-added {@code outboundProtocol} field. Forge's patch keeps
	 * that field current from inside {@code setupOutboundProtocol} — a lambda chained onto the pipeline task — and
	 * NeoForge won the merge of that method, so the lambda survives in the class and nothing calls it. The
	 * constructor still seeds the field with the handshake protocol on the CLIENT flow (the server flow leaves it
	 * null, and {@code getProtocol} then falls back to the inbound field, which the merged
	 * {@code setupInboundProtocol} does maintain — so the server side never showed this). A Forbric client thus
	 * reports HANDSHAKING for the life of the connection and every Forge channel send from the client throws
	 * "Unsupported protocol HANDSHAKING in Forge Networking Channel" — its own channel declaration
	 * ({@code ChannelListManager.addChannels}) first of all, so the server's {@code Channel.isRemotePresent} never
	 * saw the client's channels.
	 *
	 * <p>Store the new protocol at the head of {@code setupOutboundProtocol}. Synchronous rather than
	 * pipeline-ordered, which for this field's one reader is the better contract: a payload built after the switch
	 * must already be a packet of the new protocol, because the pipeline task is queued ahead of it.
	 */
	private static boolean keepForgeOutboundProtocolCurrent(ClassNode node) {
		if (!"net/minecraft/network/Connection".equals(node.name)) return false;
		String protocolInfo = "Lnet/minecraft/network/ProtocolInfo;";
		if (!hasField(node, "outboundProtocol", protocolInfo)) return false;
		MethodNode setup = findMethod(node, "setupOutboundProtocol", "(" + protocolInfo + ")V");
		if (setup == null) return false;

		// Coherent already (a single-ecosystem base, or a merge that kept Forge's body): the method stores the field
		// itself, or still chains the lambda that does.
		if (writesField(setup, "outboundProtocol")) return false;
		for (AbstractInsnNode insn = setup.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (!(insn instanceof InvokeDynamicInsnNode indy)) continue;
			for (Object arg : indy.bsmArgs) {
				if (arg instanceof Handle handle && node.name.equals(handle.getOwner())) {
					MethodNode lambda = findMethod(node, handle.getName(), handle.getDesc());
					if (lambda != null && writesField(lambda, "outboundProtocol")) return false;
				}
			}
		}

		InsnList store = new InsnList();
		store.add(new VarInsnNode(Opcodes.ALOAD, 0));
		store.add(new VarInsnNode(Opcodes.ALOAD, 1));
		store.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, "outboundProtocol", protocolInfo));
		setup.instructions.insert(store);
		setup.maxStack = Math.max(setup.maxStack, 2);
		ForbricLog.warn("[Forbric/MergedBaseCompat] Connection.setupOutboundProtocol now updates MinecraftForge's "
				+ "outboundProtocol — the merge dropped the lambda that did, so a client Connection reported HANDSHAKING "
				+ "forever and every Forge channel send from the client threw");
		return true;
	}

	private static boolean writesField(MethodNode method, String fieldName) {
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() == Opcodes.PUTFIELD && insn instanceof FieldInsnNode field && fieldName.equals(field.name)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Stops the block-breaking overlay from crashing the render frame.
	 *
	 * <p>{@code LevelExtractor.extractBlockDestroyAnimation} asks the level for MinecraftForge's
	 * {@code ModelDataManager} and dereferences it without a check. On a single-ecosystem base that is safe, because
	 * Forge's own {@code ClientLevel} patch overrides the accessor; on the merged base NeoForge's override won, and
	 * because the two return different types it does not override Forge's at all — so the call lands on Forge's
	 * interface default, whose whole body is {@code return null}. Every frame drawn while any block is being broken
	 * then dies with "Description: Render Frame", which is why this only showed up once, in a run where a break
	 * animation happened to be on screen.
	 *
	 * <p>There is nothing to route it to: no path on this base ever builds a Forge-typed manager, so no Forge-typed
	 * model data exists to find. The call therefore becomes the value Forge's own lookup returns for a position it
	 * is not tracking — {@code ModelData.EMPTY} — which is what the overlay would have drawn with anyway. A mod's
	 * dynamic model data still reaches the block itself through NeoForge's manager, which the level does have; only
	 * the break overlay draws with defaults.
	 */
	private static boolean surviveTheMissingForgeModelDataManager(ClassNode node) {
		if (!"net/minecraft/client/renderer/extract/LevelExtractor".equals(node.name)) return false;

		boolean changed = false;
		for (MethodNode m : node.methods) {
			List<MethodInsnNode> lookups = new ArrayList<>();
			for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn.getOpcode() == Opcodes.INVOKEVIRTUAL && insn instanceof MethodInsnNode call
						&& FORGE_MODEL_DATA_MANAGER.equals(call.owner) && "getAtOrEmpty".equals(call.name)) {
					lookups.add(call);
				}
			}
			for (MethodInsnNode lookup : lookups) {
				// The receiver expression, exactly: ALOAD this; GETFIELD level; INVOKEVIRTUAL getModelDataManager;
				// then the position argument. Anything else means the method was rewritten upstream — leave it be.
				AbstractInsnNode pos = previousRealInsn(lookup);
				AbstractInsnNode manager = previousRealInsn(pos);
				AbstractInsnNode level = previousRealInsn(manager);
				AbstractInsnNode self = previousRealInsn(level);
				if (pos == null || pos.getOpcode() != Opcodes.ALOAD
						|| !(manager instanceof MethodInsnNode get) || !"getModelDataManager".equals(get.name)
						|| level == null || level.getOpcode() != Opcodes.GETFIELD
						|| self == null || self.getOpcode() != Opcodes.ALOAD) {
					continue;
				}
				// The constant goes in where the receiver expression began, BEFORE the five are unlinked: a removed
				// node's neighbours are no longer a usable anchor.
				m.instructions.insertBefore(self,
						new FieldInsnNode(Opcodes.GETSTATIC, FORGE_MODEL_DATA, "EMPTY", "L" + FORGE_MODEL_DATA + ";"));
				for (AbstractInsnNode dead : new AbstractInsnNode[] {self, level, manager, pos, lookup}) {
					m.instructions.remove(dead);
				}
				changed = true;
			}
		}
		if (!changed) return false;
		ForbricLog.warn("[Forbric/MergedBaseCompat] the block-breaking overlay no longer asks for MinecraftForge's "
				+ "model-data manager — NeoForge won the level's accessor, so Forge's returned null and every frame "
				+ "drawn while a block was being broken crashed the game");
		return true;
	}

	private static final String FORGE_MODEL_DATA_MANAGER = "net/minecraftforge/client/model/data/ModelDataManager";
	private static final String FORGE_MODEL_DATA = "net/minecraftforge/client/model/data/ModelData";

	/**
	 * Takes the loader brand out of the window title.
	 *
	 * <p>{@code Minecraft.createTitle} builds "Minecraft" and then, when the game reports itself as modified, splices
	 * in a space, the loader's name and an asterisk before the version — so the merged base, whose title patch is
	 * NeoForge's, puts "NeoForge" on the window of an instance that is running Fabric, MinecraftForge and NeoForge
	 * mods side by side. Naming one of the three is worse than naming none.
	 *
	 * <p>The brand and its leading space go; the asterisk stays, which is vanilla's own mark for a modified game and
	 * leaves the title reading "Minecraft* 26.2". Only that one append chain is touched, so a title patch that
	 * changes shape is left alone rather than half-rewritten.
	 */
	private static boolean dropTheWindowTitlesLoaderBrand(ClassNode node) {
		if (!"net/minecraft/client/Minecraft".equals(node.name)) return false;
		MethodNode createTitle = findMethod(node, "createTitle", "()Ljava/lang/String;");
		if (createTitle == null) return false;

		for (AbstractInsnNode insn = createTitle.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (!(insn instanceof LdcInsnNode brand) || !LOADER_BRANDS.contains(brand.cst)) continue;
			AbstractInsnNode appendBrand = insn.getNext();
			if (!isStringBuilderAppend(appendBrand, "(Ljava/lang/String;)Ljava/lang/StringBuilder;")) continue;
			// The separator the brand arrives with: BIPUSH ' '; append(char). Without it the shape is not the one
			// this fixup was written for.
			AbstractInsnNode appendSpace = previousRealInsn(insn);
			AbstractInsnNode space = previousRealInsn(appendSpace);
			if (!isStringBuilderAppend(appendSpace, "(C)Ljava/lang/StringBuilder;")
					|| space == null || space.getOpcode() != Opcodes.BIPUSH
					|| ((org.objectweb.asm.tree.IntInsnNode) space).operand != ' ') {
				continue;
			}
			for (AbstractInsnNode dead : new AbstractInsnNode[] {space, appendSpace, insn, appendBrand}) {
				createTitle.instructions.remove(dead);
			}
			ForbricLog.info("[Forbric/MergedBaseCompat] took \"%s\" out of the window title — the merged base carries "
					+ "one loader's title patch, and this instance runs all three ecosystems", brand.cst);
			return true;
		}
		return false;
	}

	private static boolean isStringBuilderAppend(AbstractInsnNode insn, String desc) {
		return insn instanceof MethodInsnNode call && "java/lang/StringBuilder".equals(call.owner)
				&& "append".equals(call.name) && desc.equals(call.desc);
	}

	private static final java.util.Set<Object> LOADER_BRANDS = java.util.Set.of("NeoForge", "Forge", "Fabric");

	private static AbstractInsnNode previousRealInsn(AbstractInsnNode from) {
		if (from == null) return null;
		for (AbstractInsnNode insn = from.getPrevious(); insn != null; insn = insn.getPrevious()) {
			if (insn.getOpcode() >= 0) return insn;
		}
		return null;
	}

	private static MethodNode findMethod(ClassNode node, String name, String desc) {
		for (MethodNode method : node.methods) {
			if (method.name.equals(name) && method.desc.equals(desc)) return method;
		}
		return null;
	}

	private static boolean hasField(ClassNode node, String name, String desc) {
		for (FieldNode field : node.fields) {
			if (field.name.equals(name) && field.desc.equals(desc)) return true;
		}
		return false;
	}

	private static boolean initializesStaticField(ClassNode node, String name, String desc) {
		for (MethodNode method : node.methods) {
			if (!method.name.equals("<clinit>") || !method.desc.equals("()V")) continue;
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC
						&& field.owner.equals(node.name) && field.name.equals(name) && field.desc.equals(desc)) {
					return true;
				}
			}
		}
		return false;
	}

	private static Handle repairLambdaHandle(ClassNode owner, Map<String, MethodNode> methods, MethodNode caller,
			InvokeDynamicInsnNode indy, Handle handle) {
		if (!owner.name.equals(handle.getOwner()) || !handle.getName().startsWith("lambda$")) return handle;

		MethodNode target = methods.get(handle.getName() + handle.getDesc());
		if (target == null) return handle;

		boolean methodStatic = (target.access & Opcodes.ACC_STATIC) != 0;
		boolean handleStatic = handle.getTag() == Opcodes.H_INVOKESTATIC;
		if (methodStatic == handleStatic) return handle;

		if (!methodStatic && handleStatic) {
			if (!capturesOwner(owner, indy.desc)) {
				if ((caller.access & Opcodes.ACC_STATIC) != 0 || !prependThisCapture(owner, caller, indy)) {
					return handle;
				}
			}
			ForbricLog.warn("[Forbric/MergedBaseCompat] repaired lambda bootstrap handle %s.%s%s "
					+ "from static to instance; invokedynamic is now %s",
					owner.name.replace('/', '.'), handle.getName(), handle.getDesc(), indy.desc);
			return new Handle(Opcodes.H_INVOKEVIRTUAL, handle.getOwner(), handle.getName(), handle.getDesc(), false);
		}

		ForbricLog.warn("[Forbric/MergedBaseCompat] repaired lambda bootstrap handle %s.%s%s from tag %d to %d",
				owner.name.replace('/', '.'), handle.getName(), handle.getDesc(), handle.getTag(), Opcodes.H_INVOKESTATIC);
		return new Handle(Opcodes.H_INVOKESTATIC, handle.getOwner(), handle.getName(), handle.getDesc(), false);
	}

	private static boolean capturesOwner(ClassNode owner, String invokedynamicDesc) {
		Type[] args = Type.getArgumentTypes(invokedynamicDesc);
		return args.length > 0 && args[0].getSort() == Type.OBJECT && owner.name.equals(args[0].getInternalName());
	}

	private static boolean prependThisCapture(ClassNode owner, MethodNode caller, InvokeDynamicInsnNode indy) {
		AbstractInsnNode insertionPoint = capturedArgsStart(indy);
		if (insertionPoint == null) return false;
		caller.instructions.insertBefore(insertionPoint, new VarInsnNode(Opcodes.ALOAD, 0));
		indy.desc = prependArgument(Type.getObjectType(owner.name), indy.desc);
		caller.maxStack = Math.max(caller.maxStack, caller.maxStack + 1);
		return true;
	}

	private static AbstractInsnNode capturedArgsStart(InvokeDynamicInsnNode indy) {
		Type[] args = Type.getArgumentTypes(indy.desc);
		if (args.length == 0) return indy;

		AbstractInsnNode cursor = indy.getPrevious();
		AbstractInsnNode first = null;
		for (int i = args.length - 1; i >= 0; i--) {
			cursor = previousReal(cursor);
			if (!isLocalLoadFor(args[i], cursor)) return null;
			first = cursor;
			cursor = cursor.getPrevious();
		}
		return first;
	}

	private static AbstractInsnNode previousReal(AbstractInsnNode cursor) {
		while (cursor != null && cursor.getOpcode() < 0) {
			cursor = cursor.getPrevious();
		}
		return cursor;
	}

	private static boolean isLocalLoadFor(Type type, AbstractInsnNode insn) {
		return insn instanceof VarInsnNode var && var.getOpcode() == loadOpcode(type);
	}

	private static int loadOpcode(Type type) {
		return switch (type.getSort()) {
			case Type.LONG -> Opcodes.LLOAD;
			case Type.FLOAT -> Opcodes.FLOAD;
			case Type.DOUBLE -> Opcodes.DLOAD;
			case Type.ARRAY, Type.OBJECT -> Opcodes.ALOAD;
			default -> Opcodes.ILOAD;
		};
	}

	private static String prependArgument(Type argument, String methodDesc) {
		Type[] oldArgs = Type.getArgumentTypes(methodDesc);
		Type[] newArgs = new Type[oldArgs.length + 1];
		newArgs[0] = argument;
		System.arraycopy(oldArgs, 0, newArgs, 1, oldArgs.length);
		return Type.getMethodDescriptor(Type.getReturnType(methodDesc), newArgs);
	}

	private static final String GUI_RENDERER = "net/minecraft/client/gui/render/GuiRenderer";
	private static final String PIP_RENDERERS = "pictureInPictureRenderers";
	private static final String PIP_POOLS = "pictureInPictureRendererPools";
	private static final String PIP_PREPARE = "preparePictureInPictureState";
	private static final String PIP_BRIDGE = "forbric$prepareOrphanedPip";

	/**
	 * Makes a picture-in-picture renderer registered the VANILLA way draw again, by giving NeoForge's pooled lookup
	 * a fallback to the map the merge orphaned.
	 *
	 * <p>{@code GuiRenderer} ends up with BOTH ecosystems' versions of the same job:
	 *
	 * <ul>
	 *   <li>{@code preparePictureInPictureState(T, int)} — vanilla's. Reads {@code pictureInPictureRenderers}, a
	 *       {@code Class -> PictureInPictureRenderer} map, and calls {@code prepare} on the one it finds. <b>Nothing
	 *       calls it.</b></li>
	 *   <li>{@code preparePictureInPictureState(T, int, boolean)} — NeoForge's, and the one {@code render()} calls.
	 *       Reads {@code pictureInPictureRendererPools} instead, and returns false for a state class with no pool.</li>
	 * </ul>
	 *
	 * <p>Every guest mod registers into the first map, because that is the only one vanilla has: Xaero's Minimap puts
	 * its {@code MinimapPipRenderer} there, malilib its block-state element renderer. Both then draw nothing at all —
	 * no exception, no log, the element is simply absent. Chasing it from the symptom is brutal, because every link
	 * before this one is intact: the mixins apply, the hooks are called every frame, the mod's own state is live. The
	 * lookup misses one map over.
	 *
	 * <p>So the null-pool branch now falls through to the orphaned map instead of returning false. Guest renderers get
	 * exactly vanilla's contract — one instance per state class, {@code prepare} called directly — and NeoForge's
	 * pooled renderers are untouched, which matters: a pool CLOSES the renderers a frame did not use, so handing a
	 * guest's single long-lived instance to one would free its GL target out from under it.
	 *
	 * <p>The bridge method is synthesized from the descriptors of the orphaned overload itself rather than from
	 * hard-coded names, so it stays correct if the merge shifts.
	 */
	private static boolean bridgeOrphanedPipRenderers(ClassNode node) {
		if (!GUI_RENDERER.equals(node.name)) return false;
		if (findField(node, PIP_RENDERERS) == null || findField(node, PIP_POOLS) == null) return false;
		if (findMethodByName(node, PIP_BRIDGE) != null) return false;

		MethodNode orphaned = null;
		MethodNode live = null;
		for (MethodNode method : node.methods) {
			if (!PIP_PREPARE.equals(method.name)) continue;
			if (Type.getReturnType(method.desc).getSort() == Type.BOOLEAN) live = method; else orphaned = method;
		}
		if (orphaned == null || live == null) return false;

		// One source for the state type: the CALL SITE's. Deriving the bridge's descriptor from the orphaned overload
		// instead would let the two drift apart if a future merge narrows one of them, and the only symptom would be a
		// NoSuchMethodError on the first frame that actually reaches an orphaned renderer.
		String stateDesc = Type.getArgumentTypes(live.desc)[0].getDescriptor();
		MethodNode bridge = buildPipBridge(node, orphaned, stateDesc);
		if (bridge == null || !redirectMissingPoolToBridge(node, live, stateDesc)) return false;

		node.methods.add(bridge);
		ForbricLog.warn("[Forbric/MergedBaseCompat] gave GuiRenderer's pooled picture-in-picture lookup a fallback to "
				+ "the orphaned vanilla map — NeoForge won preparePictureInPictureState, so every guest-registered "
				+ "GUI element (Xaero's minimap, malilib's overlays) was registered where nothing reads");
		return true;
	}

	/**
	 * Builds {@code boolean forbric$prepareOrphanedPip(state, i)} — vanilla's lookup, with a boolean saying whether
	 * it found anything. Every field and call is cloned out of the orphaned overload, so nothing here is spelled twice;
	 * {@code stateDesc} comes from the CALL SITE so the two cannot disagree.
	 */
	private static MethodNode buildPipBridge(ClassNode node, MethodNode orphaned, String stateDesc) {
		FieldInsnNode renderers = null;
		FieldInsnNode renderState = null;
		FieldInsnNode dispatcher = null;
		TypeInsnNode rendererCast = null;
		MethodInsnNode mapGet = null;
		MethodInsnNode prepare = null;

		for (AbstractInsnNode insn = orphaned.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD) {
				if (PIP_RENDERERS.equals(field.name)) renderers = field;
				else if (renderState == null) renderState = field;
				else if (dispatcher == null) dispatcher = field;
			} else if (insn instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST) {
				rendererCast = cast;
			} else if (insn instanceof MethodInsnNode call) {
				if ("get".equals(call.name)) mapGet = call;
				else if ("prepare".equals(call.name)) prepare = call;
			}
		}
		if (renderers == null || renderState == null || dispatcher == null
				|| rendererCast == null || mapGet == null || prepare == null) {
			ForbricLog.debug("[Forbric/MergedBaseCompat] GuiRenderer's orphaned pip overload has an unexpected shape "
					+ "— leaving the pooled lookup alone");
			return null;
		}

		MethodNode bridge = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
				PIP_BRIDGE, "(" + stateDesc + "I)Z", null, null);
		LabelNode miss = new LabelNode();
		InsnList code = bridge.instructions;

		code.add(new VarInsnNode(Opcodes.ALOAD, 0));
		code.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, renderers.name, renderers.desc));
		code.add(new VarInsnNode(Opcodes.ALOAD, 1));
		// Object.getClass rather than the interface's, so this holds however the state type is declared.
		code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;",
				false));
		code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, mapGet.owner, mapGet.name, mapGet.desc, true));
		code.add(new TypeInsnNode(Opcodes.CHECKCAST, rendererCast.desc));
		code.add(new VarInsnNode(Opcodes.ASTORE, 3));
		code.add(new VarInsnNode(Opcodes.ALOAD, 3));
		code.add(new JumpInsnNode(Opcodes.IFNULL, miss));

		code.add(new VarInsnNode(Opcodes.ALOAD, 3));
		code.add(new VarInsnNode(Opcodes.ALOAD, 1));
		code.add(new VarInsnNode(Opcodes.ALOAD, 0));
		code.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, renderState.name, renderState.desc));
		code.add(new VarInsnNode(Opcodes.ALOAD, 0));
		code.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, dispatcher.name, dispatcher.desc));
		code.add(new VarInsnNode(Opcodes.ILOAD, 2));
		code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, prepare.owner, prepare.name, prepare.desc, false));
		code.add(new InsnNode(Opcodes.ICONST_1));
		code.add(new InsnNode(Opcodes.IRETURN));

		code.add(miss);
		// Both paths reach here with slot 3 holding the (null) renderer, so the frame simply appends it.
		code.add(new FrameNode(Opcodes.F_APPEND, 1, new Object[] {rendererCast.desc}, 0, null));
		code.add(new InsnNode(Opcodes.ICONST_0));
		code.add(new InsnNode(Opcodes.IRETURN));

		bridge.maxStack = 5;
		bridge.maxLocals = 4;
		return bridge;
	}

	/** Rewrites the live overload's "no pool for this state class" early return into a call to the bridge. */
	private static boolean redirectMissingPoolToBridge(ClassNode node, MethodNode live, String stateDesc) {
		for (AbstractInsnNode insn = live.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (!(insn instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETFIELD
					|| !PIP_POOLS.equals(field.name)) {
				continue;
			}

			AbstractInsnNode jump = insn;
			while (jump != null && !(jump instanceof JumpInsnNode)) jump = jump.getNext();
			if (jump == null || jump.getOpcode() != Opcodes.IFNONNULL) break;

			AbstractInsnNode falsy = jump.getNext();
			while (falsy != null && falsy.getOpcode() == -1) falsy = falsy.getNext();   // labels / line numbers
			if (falsy == null || falsy.getOpcode() != Opcodes.ICONST_0) break;

			AbstractInsnNode ret = falsy.getNext();
			while (ret != null && ret.getOpcode() == -1) ret = ret.getNext();
			if (ret == null || ret.getOpcode() != Opcodes.IRETURN) break;

			InsnList call = new InsnList();
			call.add(new VarInsnNode(Opcodes.ALOAD, 0));
			call.add(new VarInsnNode(Opcodes.ALOAD, 1));
			call.add(new VarInsnNode(Opcodes.ILOAD, 2));
			call.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.name, PIP_BRIDGE, "(" + stateDesc + "I)Z", false));
			live.instructions.insertBefore(falsy, call);
			live.instructions.remove(falsy);
			live.maxStack = Math.max(live.maxStack, 3);
			return true;
		}

		ForbricLog.debug("[Forbric/MergedBaseCompat] GuiRenderer's pooled pip lookup has an unexpected shape "
				+ "— leaving it alone");
		return false;
	}

	private static FieldNode findField(ClassNode node, String name) {
		if (node.fields == null) return null;
		for (FieldNode field : node.fields) {
			if (field.name.equals(name)) return field;
		}
		return null;
	}

	/** First method with this name, whatever its descriptor — distinct from {@link #findMethod(ClassNode,String,String)}. */
	private static MethodNode findMethodByName(ClassNode node, String name) {
		for (MethodNode method : node.methods) {
			if (method.name.equals(name)) return method;
		}
		return null;
	}
}
