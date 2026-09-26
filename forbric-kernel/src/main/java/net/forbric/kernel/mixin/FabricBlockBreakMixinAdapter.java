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
import java.util.List;
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

import net.forbric.kernel.util.ForbricLog;

/**
 * Points three Fabric {@code ServerPlayerGameMode.destroyBlock} injections at what NeoForge's body has.
 *
 * <p>The merge kept NeoForge's {@code destroyBlock}. It posts {@code BreakBlockEvent} first and keeps the event in
 * a local, reads the state into slot 2 instead of vanilla's slot 5, and replaces vanilla's
 * {@code player.hasCorrectToolForDrops(state)} with {@code state.canHarvestBlock(level, pos, player)}, stored BEFORE
 * the block is removed instead of after. The values are the same values; only where they sit moved, and two
 * injections that name them by position found nothing:
 *
 * <ul>
 *   <li>architectury's {@code onBreak} captures {@code (BlockEntity, BlockState)} at the first {@code getBlock()},
 *       vanilla's order. NeoForge's frame there is {@code (BlockState, BreakBlockEvent, BlockEntity)}. The handler
 *       is wrapped, as {@link MixinHandlerShim} does for arguments: a new one takes the merged frame, carries the
 *       annotation, and hands the original the two values it asked for. Without it architectury's
 *       {@code BlockEvent.BREAK} never fires on a server.</li>
 *   <li>apoli-legacy's {@code modifyEffectiveTool} is a {@code @ModifyVariable} of the SECOND boolean in scope at
 *       {@code mineBlock} — vanilla's first is "the block was removed", its second "the tool is right". In NeoForge's
 *       body the removal comes after {@code mineBlock}, so the harvest check is the only boolean there, and it
 *       becomes ordinal 0. Without it origins that allow or deny harvesting do nothing.</li>
 * </ul>
 *
 * <p>And fabric-api's {@code onBlockBroken} (PlayerBlockBreakEvents.AFTER), whose {@code Block.destroy} anchor NeoForge
 * moved into its own {@code removeBlock}; see {@link #afterBreak}.
 *
 * <p>None is a rule about locals in general — {@link MixinHandlerShim} refuses exactly that, because a mapping by
 * type can hand over a different value of the same type. Each fires only when the merged body PROVES the values:
 * the one {@code BlockState} in scope is the one read from {@code level.getBlockState(pos)}, the one
 * {@code BlockEntity} is {@code level.getBlockEntity(pos)}, the one boolean is stored straight from
 * {@code canHarvestBlock} and vanilla's harvest call is gone; the AFTER wrapper needs the two {@code removeBlock}
 * calls, the named block entity and adjusted state in scope at both, and the one {@code Block.destroy} inside. On
 * vanilla's own body each finds what it was written for and changes nothing.
 *
 * <p>Apoli's other {@code destroyBlock} handler, {@code actionOnBlockBreak}, reads both booleans by MixinExtras
 * {@code @Local} ordinal and gets them in the other order on the merged body. It binds, and it only ever ANDs the
 * two, so it is left alone.
 *
 * <p>{@code -Dforbric.fabricBlockBreak=off} leaves all three mixins as they were compiled.
 */
public final class FabricBlockBreakMixinAdapter {
	public static final String PROPERTY = "forbric.fabricBlockBreak";

	static final String TARGET = "net/minecraft/server/level/ServerPlayerGameMode";
	static final String ARCHITECTURY = "dev/architectury/mixin/fabric/MixinServerPlayerGameMode";
	static final String APOLI = "io/github/apace100/apoli/mixin/ServerPlayerInteractionManagerMixin";
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
	private static final String MODIFY_VARIABLE = "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;";
	private static final String GROUP = "Lorg/spongepowered/asm/mixin/injection/Group;";
	private static final String GET_BLOCK = "L" + STATE + ";getBlock()Lnet/minecraft/world/level/block/Block;";
	private static final String MINE_BLOCK = "Lnet/minecraft/world/item/ItemStack;mineBlock(Lnet/minecraft/world/level/Level;"
			+ "L" + STATE + ";Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/player/Player;)V";
	private static final String ON_BREAK_DESC = "(Lnet/minecraft/core/BlockPos;" + CIR + "L" + BLOCK_ENTITY + ";L" + STATE + ";)V";

	private FabricBlockBreakMixinAdapter() {
	}

	/**
	 * Adapts whichever of the three handlers {@code mixin} carries (architectury's {@code onBreak}, apoli-legacy's
	 * {@code modifyEffectiveTool}, fabric-api's {@code onBlockBroken}); returns how many were changed.
	 */
	public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
		if ("off".equalsIgnoreCase(System.getProperty(PROPERTY, "on")) || mixin == null || targets == null) return 0;
		if (!mixin.name.equals(ARCHITECTURY) && !mixin.name.equals(APOLI) && !mixin.name.equals(FABRIC)) return 0;
		ClassNode target = targets.apply(TARGET);
		MethodNode destroy = target == null ? null : method(target, "destroyBlock", DESTROY_DESC);
		if (destroy == null || destroy.localVariables == null || destroy.localVariables.isEmpty()) return 0;
		if (mixin.name.equals(FABRIC)) {
			int wrapped = afterBreak(mixin, target, destroy);
			if (wrapped > 0) {
				ForbricLog.info("[Forbric/Mixin] %s: PlayerBlockBreakEvents.AFTER now fires when NeoForge's removeBlock reports the "
						+ "block removed — the Block.destroy call it anchored on moved there", mixin.name.replace('/', '.'));
			}
			return wrapped;
		}
		int changed = mixin.name.equals(ARCHITECTURY) ? breakEventLocals(mixin, destroy) : harvestOrdinal(mixin, destroy);
		if (changed > 0) {
			ForbricLog.info("[Forbric/Mixin] %s: its destroyBlock injection now reads the locals NeoForge's merged "
					+ "destroyBlock keeps, not vanilla's slots", mixin.name.replace('/', '.'));
		}
		return changed;
	}

	/** architectury: wrap {@code onBreak} so it captures NeoForge's frame and still receives (BlockEntity, BlockState). */
	private static int breakEventLocals(ClassNode mixin, MethodNode destroy) {
		MethodNode handler = method(mixin, "onBreak", ON_BREAK_DESC);
		if (handler == null || grouped(handler) || (handler.access & Opcodes.ACC_STATIC) != 0) return 0;
		AnnotationNode inject = MixinFit.injectorOf(handler);
		if (inject == null || !INJECT.equals(inject.desc) || !only(inject, "destroyBlock")
				|| MixinFit.value(inject, "locals") == null) return 0;
		List<AnnotationNode> points = MixinFit.atNodes(inject);
		if (points.size() != 1 || !"INVOKE".equals(MixinFit.value(points.getFirst(), "value"))
				|| !GET_BLOCK.equals(MixinFit.value(points.getFirst(), "target"))
				|| !Integer.valueOf(0).equals(MixinFit.value(points.getFirst(), "ordinal"))) return 0;

		AbstractInsnNode point = null;
		for (AbstractInsnNode insn : destroy.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(STATE) && call.name.equals("getBlock")) { point = insn; break; }
		}
		if (point == null) return 0;
		List<LocalVariableNode> frame = inScope(destroy, point);
		if (frame == null) return 0;
		List<Type> captured = new ArrayList<>();
		for (LocalVariableNode local : frame) captured.add(Type.getType(local.desc));
		List<Type> wanted = List.of(Type.getObjectType(BLOCK_ENTITY), Type.getObjectType(STATE));
		if (captured.size() >= 2 && captured.subList(0, 2).equals(wanted)) return 0;    // vanilla's frame: it binds

		LocalVariableNode entity = onlyOfType(frame, BLOCK_ENTITY), state = onlyOfType(frame, STATE);
		if (entity == null || state == null || !readFromLevel(destroy, entity.index, "getBlockEntity")
				|| !readFromLevel(destroy, state.index, "getBlockState")) return 0;
		int last = Math.max(frame.indexOf(entity), frame.indexOf(state));
		for (int i = 0; i <= last; i++) {
			if (captured.get(i).getSize() != 1) return 0;    // wide locals would shift the slots below; none here
		}

		// The outer handler: pos, the callback, then the merged frame up to the last value the original wants.
		List<Type> outerParams = new ArrayList<>(List.of(Type.getObjectType("net/minecraft/core/BlockPos"), Type.getType(CIR)));
		outerParams.addAll(captured.subList(0, last + 1));
		MethodNode outer = new MethodNode(Opcodes.ASM9, handler.access, handler.name,
				Type.getMethodDescriptor(Type.VOID_TYPE, outerParams.toArray(Type[]::new)), null, null);
		outer.visibleAnnotations = new ArrayList<>(List.of(inject));
		outer.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		outer.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
		outer.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
		outer.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3 + frame.indexOf(entity)));
		outer.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3 + frame.indexOf(state)));
		outer.instructions.add(MixinHandlerShim.callOwn(mixin, false, handler.name + MixinHandlerShim.INNER_SUFFIX,
				handler.desc));
		outer.instructions.add(new InsnNode(Opcodes.RETURN));
		outer.maxStack = 5;
		outer.maxLocals = outerParams.size() + 1;

		handler.name = handler.name + MixinHandlerShim.INNER_SUFFIX;
		handler.visibleAnnotations = without(handler.visibleAnnotations, INJECT);
		handler.invisibleAnnotations = without(handler.invisibleAnnotations, INJECT);
		mixin.methods.add(outer);
		return 1;
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
	private static int afterBreak(ClassNode mixin, ClassNode target, MethodNode destroy) {
		MethodNode handler = method(mixin, "onBlockBroken", ON_BREAK_DESC);
		if (handler == null || grouped(handler) || (handler.access & Opcodes.ACC_STATIC) != 0) return 0;
		AnnotationNode inject = MixinFit.injectorOf(handler);
		if (inject == null || !INJECT.equals(inject.desc) || !only(inject, "destroyBlock")) return 0;
		List<AnnotationNode> points = MixinFit.atNodes(inject);
		if (points.size() != 1 || !"INVOKE".equals(MixinFit.value(points.getFirst(), "value"))
				|| !BLOCK_DESTROY.equals(MixinFit.value(points.getFirst(), "target"))) return 0;
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

	/** apoli-legacy: the harvest check is the only boolean at {@code mineBlock} in NeoForge's body — ordinal 0. */
	private static int harvestOrdinal(ClassNode mixin, MethodNode destroy) {
		MethodNode handler = method(mixin, "modifyEffectiveTool", "(Z)Z");
		if (handler == null || grouped(handler)) return 0;
		AnnotationNode modify = MixinFit.injectorOf(handler);
		if (modify == null || !MODIFY_VARIABLE.equals(modify.desc) || !only(modify, "destroyBlock")
				|| !Integer.valueOf(1).equals(MixinFit.value(modify, "ordinal"))
				|| MixinFit.value(modify, "index") != null || MixinFit.value(modify, "name") != null
				|| MixinFit.value(modify, "argsOnly") != null) return 0;
		List<AnnotationNode> points = MixinFit.atNodes(modify);
		if (points.size() != 1 || !"INVOKE".equals(MixinFit.value(points.getFirst(), "value"))
				|| !MINE_BLOCK.equals(MixinFit.value(points.getFirst(), "target"))
				|| MixinFit.value(points.getFirst(), "ordinal") != null) return 0;

		AbstractInsnNode point = null;
		for (AbstractInsnNode insn : destroy.instructions) {
			if (!(insn instanceof MethodInsnNode call)) continue;
			if (call.name.equals("hasCorrectToolForDrops")) return 0;    // vanilla's check is still here
			if (call.name.equals("mineBlock") && ("L" + call.owner + ";" + call.name + call.desc).equals(MINE_BLOCK)) {
				if (point != null) return 0;
				point = insn;
			}
		}
		if (point == null) return 0;
		List<LocalVariableNode> frame = inScope(destroy, point);
		if (frame == null) return 0;
		List<LocalVariableNode> booleans = frame.stream().filter(local -> local.desc.equals("Z")).toList();
		if (booleans.size() != 1 || !storedFrom(destroy, booleans.getFirst().index, Opcodes.ISTORE, STATE, "canHarvestBlock",
				"(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/player/Player;)Z")) return 0;

		for (int i = 0; i + 1 < modify.values.size(); i += 2) {
			if ("ordinal".equals(modify.values.get(i))) modify.values.set(i + 1, 0);
		}
		return 1;
	}

	/**
	 * The method's locals after its arguments that the local variable table has in scope at {@code point}, by slot.
	 * Null when two entries claim one slot there — the table is not one this can read.
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
		found.sort((a, b) -> Integer.compare(a.index, b.index));
		return found;
	}

	private static LocalVariableNode onlyOfType(List<LocalVariableNode> frame, String internalName) {
		LocalVariableNode only = null;
		for (LocalVariableNode local : frame) {
			if (!local.desc.equals("L" + internalName + ";")) continue;
			if (only != null) return null;
			only = local;
		}
		return only;
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

	private static boolean only(AnnotationNode injector, String method) {
		return MixinFit.stringList(MixinFit.value(injector, "method")).equals(List.of(method));
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
