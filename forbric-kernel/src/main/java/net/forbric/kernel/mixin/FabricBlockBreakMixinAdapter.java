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

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.util.ForbricLog;

/**
 * Points fabric-api's {@code onBlockBroken} (PlayerBlockBreakEvents.AFTER) at what NeoForge's {@code destroyBlock} has; see
 * {@link #afterBreak}.
 *
 * <p>This adapter once also repaired two injections that name {@code destroyBlock}'s locals by position. Both are now done by
 * mechanisms that know no mod: architectury's {@code onBreak} locals capture by {@link MixinCapturedLocals} (each captured
 * local is the merged slot holding the same value), apoli-legacy's {@code modifyEffectiveTool} ordinal by
 * {@link NativeLocalPins} (the {@code @ModifyVariable}'s ordinal picks {@code canDestroy} natively, the merged local of the same
 * name and type is pinned by index). Both run before this adapter, so those branches had become dead code here.
 *
 * <p>Why AFTER is still named: vanilla calls {@code Block.destroy} once, in {@code destroyBlock}, only when the block was
 * removed. NeoForge moved it into its own {@code removeBlock}, which {@code destroyBlock} calls on two exclusive paths
 * (creative and survival) and which calls {@code Block.destroy} exactly when it returns true; fabric-api's handler reads the
 * block entity and adjusted state by MixinExtras {@code @Local}, which exist only in {@code destroyBlock}. Inlining either
 * call ({@link NativeCallInlining}) puts two calls where vanilla has one; following the helper's answer instead means proving
 * that the helper returns true exactly on the path where it makes the call — a relation of the helper's result to an effect
 * in its body that no general mechanism here reads yet. The repair stays pinned to fabric-api's mixin and handler.
 *
 * <p>Each injector is read as Mixin reads it, never by spelling: its selectors by the method they bind in the merged class
 * ({@link MixinCallbackShape#binds}), its point by the member it names ({@link MixinCallbackShape#names}).
 *
 * <p>{@code -Dforbric.fabricBlockBreak=off} leaves fabric-api's mixin as it was compiled.
 */
public final class FabricBlockBreakMixinAdapter {
	public static final String PROPERTY = "forbric.fabricBlockBreak";

	static final String TARGET = "net/minecraft/server/level/ServerPlayerGameMode";
	static final String FABRIC = "net/fabricmc/fabric/mixin/event/interaction/ServerPlayerGameModeMixin";
	static final String REMOVE_BLOCK_DESC = "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;"
			+ "ZLnet/minecraft/world/item/ItemStack;)Z";
	static final String BLOCK_DESTROY = "Lnet/minecraft/world/level/block/Block;destroy(Lnet/minecraft/world/level/LevelAccessor;"
			+ "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V";
	static final String MODIFY_EXPRESSION_VALUE = "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;";
	static final String LOCAL = "Lcom/llamalad7/mixinextras/sugar/Local;";

	private static final String DESTROY_DESC = "(Lnet/minecraft/core/BlockPos;)Z";
	private static final String LEVEL = "net/minecraft/server/level/ServerLevel";
	private static final String STATE = "net/minecraft/world/level/block/state/BlockState";
	private static final String BLOCK_ENTITY = "net/minecraft/world/level/block/entity/BlockEntity";
	private static final String CIR = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";
	private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
	private static final String GROUP = "Lorg/spongepowered/asm/mixin/injection/Group;";
	private static final String ON_BREAK_DESC = "(Lnet/minecraft/core/BlockPos;" + CIR + "L" + BLOCK_ENTITY + ";L" + STATE + ";)V";

	private FabricBlockBreakMixinAdapter() {
	}

	/**
	 * Adapts handlers by their target, annotation, descriptor and proven local producers; handler names and
	 * the supplying mod do not select the local repairs. Returns how many were changed.
	 */
	public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
		return adapt(mixin, targets, NativeGameReferences::reference);
	}

	/**
	 * {@code references} gives the class the mod was compiled against: an {@code @At} target written without its owner
	 * or descriptor names a member only as the method the handler was written for there decides it
	 * ({@link MixinCallbackShape#names}); without it, only a target spelled with both does.
	 */
	static int adapt(ClassNode mixin, Function<String, ClassNode> targets, BiFunction<Ecosystem, String, ClassNode> references) {
		if ("off".equalsIgnoreCase(System.getProperty(PROPERTY, "on")) || mixin == null || targets == null) return 0;
		if (!MixinFit.mixinTargets(mixin).equals(List.of(TARGET))) return 0;
		ClassNode target = targets.apply(TARGET);
		MethodNode destroy = target == null ? null : method(target, "destroyBlock", DESTROY_DESC);
		if (destroy == null || destroy.localVariables == null || destroy.localVariables.isEmpty()) return 0;
		ClassNode source = references == null ? null : references.apply(MixinStubRebind.ecosystemOf(mixin.name), TARGET);
		if (mixin.name.equals(FABRIC)) {
			int wrapped = afterBreak(mixin, target, destroy, source);
			if (wrapped > 0) {
				ForbricLog.info("[Forbric/Mixin] %s: PlayerBlockBreakEvents.AFTER now fires when NeoForge's removeBlock reports the "
						+ "block removed — the Block.destroy call it anchored on moved there", mixin.name.replace('/', '.'));
			}
			return wrapped;
		}
		return 0;
	}

	/**
	 * fabric-api: {@code onBlockBroken} fires PlayerBlockBreakEvents.AFTER at vanilla's {@code Block.destroy} call, which
	 * vanilla makes only when the block was removed. NeoForge's {@code destroyBlock} has no such call: it hands the
	 * removal to its own {@code removeBlock(pos, state, canHarvest, tool)}, which calls {@code Block.destroy} exactly
	 * when it removed the block and returns that same answer — once on the creative path, once on the survival path.
	 * So fabric-api's handler is wrapped in a {@code @ModifyExpressionValue} on that call's result: when it is true the
	 * original handler runs, with the block entity read before the break and the state {@code playerWillDestroy}
	 * returned, exactly the values vanilla hands it. fabric-api's own handler stays the only thing that fires AFTER.
	 *
	 * <p>Two orderings differ from vanilla and are the merged body's, not this wrapper's: AFTER runs after
	 * {@code Block.destroy} (a block that clears partner blocks there, like a multiblock, has done so already), and
	 * the tool and harvest decision are fixed before the removal, so an AFTER listener changing the held item no longer
	 * changes the drops or the tool damage.
	 */
	private static int afterBreak(ClassNode mixin, ClassNode target, MethodNode destroy, ClassNode source) {
		MethodNode handler = method(mixin, "onBlockBroken", ON_BREAK_DESC);
		if (handler == null || grouped(handler) || (handler.access & Opcodes.ACC_STATIC) != 0) return 0;
		AnnotationNode inject = MixinFit.injectorOf(handler);
		if (inject == null || !INJECT.equals(inject.desc) || !MixinCallbackShape.binds(handler, target, "destroyBlock" + DESTROY_DESC)) return 0;
		List<AnnotationNode> points = MixinFit.atNodes(inject);
		if (points.size() != 1 || !"INVOKE".equals(MixinFit.value(points.getFirst(), "value"))
				|| !MixinCallbackShape.names(points.getFirst(), BLOCK_DESTROY, MixinCallbackShape.written(handler, source))) return 0;
		for (AbstractInsnNode insn : handler.instructions) {
			if (insn instanceof VarInsnNode load && load.var == 2) return 0;   // the wrapper has no callback to hand over
		}
		List<AnnotationNode>[] sugar = handler.invisibleParameterAnnotations;
		AnnotationNode entityLocal = sugar == null || sugar.length < 4 ? null : local(sugar[2]);
		AnnotationNode stateLocal = sugar == null || sugar.length < 4 ? null : local(sugar[3]);
		if (entityLocal == null || stateLocal == null || !names(entityLocal, "blockEntity") || !names(stateLocal, "adjustedState")) return 0;

		// The merged body: no Block.destroy of its own, two removeBlock calls on the named values, the one removal helper.
		List<MethodInsnNode> removals = new ArrayList<>();
		for (AbstractInsnNode insn : destroy.instructions) {
			if (!(insn instanceof MethodInsnNode call)) continue;
			if (("L" + call.owner + ";" + call.name + call.desc).equals(BLOCK_DESTROY)) return 0;   // vanilla's shape: it binds
			if (call.owner.equals(TARGET) && call.name.equals("removeBlock") && call.desc.equals(REMOVE_BLOCK_DESC)) removals.add(call);
		}
		if (removals.size() != 2) return 0;
		for (MethodInsnNode removal : removals) {
			List<LocalVariableNode> frame = inScope(destroy, removal);
			if (frame == null || named(frame, "blockEntity", BLOCK_ENTITY) == null || named(frame, "adjustedState", STATE) == null) return 0;
		}
		LocalVariableNode entity = named(inScope(destroy, removals.getFirst()), "blockEntity", BLOCK_ENTITY);
		if (!readFromLevel(destroy, entity.index, "getBlockEntity")) return 0;
		MethodNode helper = method(target, "removeBlock", REMOVE_BLOCK_DESC);
		if (helper == null || destroyCalls(helper) != 1) return 0;

		MethodNode outer = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PRIVATE, handler.name,
				"(ZLnet/minecraft/core/BlockPos;L" + BLOCK_ENTITY + ";L" + STATE + ";)Z", null, null);
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", "INVOKE", "target", "L" + TARGET + ";removeBlock" + REMOVE_BLOCK_DESC));
		AnnotationNode modify = new AnnotationNode(MODIFY_EXPRESSION_VALUE);
		modify.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of("destroyBlock")), "at", new ArrayList<>(List.of(at))));
		outer.visibleAnnotations = new ArrayList<>(List.of(modify));
		AnnotationNode position = new AnnotationNode(LOCAL);
		position.values = new ArrayList<>(List.of("argsOnly", Boolean.TRUE));
		@SuppressWarnings("unchecked")
		List<AnnotationNode>[] outerSugar = new List[] { null, new ArrayList<>(List.of(position)),
				new ArrayList<>(List.of(entityLocal)), new ArrayList<>(List.of(stateLocal)) };
		outer.invisibleParameterAnnotations = outerSugar;
		LabelNode kept = new LabelNode();
		outer.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
		outer.instructions.add(new JumpInsnNode(Opcodes.IFEQ, kept));        // not removed: vanilla never reached Block.destroy
		outer.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		outer.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
		outer.instructions.add(new InsnNode(Opcodes.ACONST_NULL));            // the handler never reads its callback
		outer.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
		outer.instructions.add(new VarInsnNode(Opcodes.ALOAD, 4));
		outer.instructions.add(MixinHandlerShim.callOwn(mixin, false, handler.name + MixinHandlerShim.INNER_SUFFIX,
				handler.desc));
		outer.instructions.add(kept);
		outer.instructions.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
		outer.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
		outer.instructions.add(new InsnNode(Opcodes.IRETURN));
		outer.maxStack = 5;
		outer.maxLocals = 5;

		handler.name = handler.name + MixinHandlerShim.INNER_SUFFIX;
		handler.visibleAnnotations = without(handler.visibleAnnotations, INJECT);
		handler.invisibleAnnotations = without(handler.invisibleAnnotations, INJECT);
		mixin.methods.add(outer);
		return 1;
	}

	private static AnnotationNode local(List<AnnotationNode> annotations) {
		if (annotations == null) return null;
		for (AnnotationNode annotation : annotations) if (LOCAL.equals(annotation.desc)) return annotation;
		return null;
	}

	private static boolean names(AnnotationNode local, String name) {
		return MixinFit.stringList(MixinFit.value(local, "name")).equals(List.of(name));
	}

	/** The one in-scope local with that name and type, or null when there is none or more than one. */
	private static LocalVariableNode named(List<LocalVariableNode> frame, String name, String internalName) {
		if (frame == null) return null;
		LocalVariableNode only = null;
		for (LocalVariableNode local : frame) {
			if (!local.name.equals(name)) continue;
			if (only != null || !local.desc.equals("L" + internalName + ";")) return null;
			only = local;
		}
		return only;
	}

	private static int destroyCalls(MethodNode method) {
		int calls = 0;
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && ("L" + call.owner + ";" + call.name + call.desc).equals(BLOCK_DESTROY)) calls++;
		}
		return calls;
	}

	/**
	 * The method's locals after its arguments that Mixin's frame holds at {@code point}, by slot: those the local variable
	 * table has in scope there, less any whose slot holds no value anywhere above it. Null when two entries claim one slot
	 * there — the table is not one this can read.
	 *
	 * <p>Mixin reads a method's locals top to bottom ({@code Locals.getLocalsAt}): a slot enters its frame at a store or a
	 * load, or at a frame that lists it, and the table only names it. A table entry in scope before anything was stored in
	 * its slot is therefore no local of Mixin's there — which is how a merged body declares a local it took from the other
	 * ecosystem's patch: over the whole method, wider than the slot is ever written. Only a slot that no instruction above
	 * {@code point} touches and that the data flow leaves empty at every instruction up to it is dropped, so a slot Mixin
	 * might still hold there (a zombie of an earlier block, a value a back edge carries) stays counted.
	 */
	private static List<LocalVariableNode> inScope(MethodNode method, AbstractInsnNode point) {
		int at = method.instructions.indexOf(point);
		int first = Type.getArgumentsAndReturnSizes(method.desc) >> 2;    // includes `this`
		if ((method.access & Opcodes.ACC_STATIC) != 0) first--;
		List<LocalVariableNode> found = new ArrayList<>();
		for (LocalVariableNode local : method.localVariables) {
			if (local.index < first) continue;
			if (method.instructions.indexOf(local.start) > at || method.instructions.indexOf(local.end) <= at) continue;
			for (LocalVariableNode other : found) if (other.index == local.index) return null;
			found.add(local);
		}
		BitSet held = heldAbove(method, at);
		if (held != null) found.removeIf(local -> !held.get(local.index));
		found.sort((a, b) -> Integer.compare(a.index, b.index));
		return found;
	}

	/**
	 * Every slot that holds a value somewhere at or above instruction {@code at}: one an instruction above it stores or
	 * loads, or one the data flow has filled before any instruction up to it. Null when the body does not analyze; the
	 * table alone is read then.
	 */
	private static BitSet heldAbove(MethodNode method, int at) {
		Frame<BasicValue>[] frames;
		try {
			frames = new Analyzer<>(new BasicInterpreter()).analyze(TARGET, method);
		} catch (AnalyzerException | RuntimeException unreadable) {
			return null;
		}
		BitSet held = new BitSet();
		for (int i = 0; i <= at && i < frames.length; i++) {
			if (i < at && method.instructions.get(i) instanceof VarInsnNode access) held.set(access.var);
			if (frames[i] == null) continue;
			for (int slot = 0; slot < frames[i].getLocals(); slot++) {
				if (!BasicValue.UNINITIALIZED_VALUE.equals(frames[i].getLocal(slot))) held.set(slot);
			}
		}
		return held;
	}


	/** Whether {@code slot} is written exactly once, from {@code this.level.<name>(pos)}. */
	private static boolean readFromLevel(MethodNode method, int slot, String name) {
		if (!storedFrom(method, slot, Opcodes.ASTORE, LEVEL, name, "(Lnet/minecraft/core/BlockPos;)L"
				+ (name.equals("getBlockState") ? STATE : BLOCK_ENTITY) + ";")) return false;
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof VarInsnNode store && store.getOpcode() == Opcodes.ASTORE && store.var == slot) {
				AbstractInsnNode argument = real(real(store.getPrevious()).getPrevious());
				return argument instanceof VarInsnNode pos && pos.getOpcode() == Opcodes.ALOAD && pos.var == 1;
			}
		}
		return false;
	}

	/** Whether the only {@code opcode} store to {@code slot} takes the result of the named call straight off the stack. */
	private static boolean storedFrom(MethodNode method, int slot, int opcode, String owner, String name, String desc) {
		VarInsnNode only = null;
		for (AbstractInsnNode insn : method.instructions) {
			if (!(insn instanceof VarInsnNode store) || store.var != slot || store.getOpcode() != opcode) continue;
			if (only != null) return false;
			only = store;
		}
		return only != null && real(only.getPrevious()) instanceof MethodInsnNode call
				&& call.owner.equals(owner) && call.name.equals(name) && call.desc.equals(desc);
	}

	/** {@code insn} or the nearest real instruction before it (labels, frames and line numbers skipped). */
	private static AbstractInsnNode real(AbstractInsnNode insn) {
		while (insn != null && insn.getOpcode() < 0) insn = insn.getPrevious();
		return insn;
	}


	private static boolean grouped(MethodNode handler) {
		for (List<AnnotationNode> annotations : java.util.Arrays.asList(handler.visibleAnnotations, handler.invisibleAnnotations)) {
			if (annotations != null && annotations.stream().anyMatch(a -> GROUP.equals(a.desc))) return true;
		}
		return false;
	}

	private static MethodNode method(ClassNode owner, String name, String desc) {
		if (owner.methods == null) return null;
		for (MethodNode method : owner.methods) {
			if (method.name.equals(name) && method.desc.equals(desc)) return method;
		}
		return null;
	}

	private static List<AnnotationNode> without(List<AnnotationNode> annotations, String desc) {
		if (annotations == null) return null;
		List<AnnotationNode> kept = new ArrayList<>();
		for (AnnotationNode annotation : annotations) {
			if (!desc.equals(annotation.desc)) kept.add(annotation);
		}
		return kept;
	}
}
