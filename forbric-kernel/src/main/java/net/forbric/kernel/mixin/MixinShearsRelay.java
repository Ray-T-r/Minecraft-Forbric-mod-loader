/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * A Fabric mod's wrap of vanilla's "is this shears?" question follows it to the carrier's "can this shear?" question.
 *
 * <p>Vanilla decides shearing with {@code stack.is(Items.SHEARS)} in six places: carving a pumpkin, disarming tripwire,
 * harvesting a beehive, shearing a sheep, a snow golem and a mooshroom. The merged bodies ask the stack instead —
 * NeoForge's {@code canPerformAction(ItemAbilities.SHEARS_*)} in the three blocks, MinecraftForge's
 * {@code canPerformAction(ToolActions.SHEARS_HARVEST)} in the three mobs. BCLib wraps each vanilla call
 * ({@code original || tagged c:tools/shear or a mineable/shears tool}) so a modded shears item that only carries the tag
 * works; on the merged base four of those wraps had nothing to bind to, and the beehive and mooshroom ones bound only to
 * those methods' other {@code is} calls (glass bottle, bowl), where they do nothing. A tag-only shears item carved,
 * sheared and disarmed nothing.
 *
 * <p>For a handler in a row of {@link #ROWS}, a new {@code @WrapOperation} on the carrier's call hands the handler the
 * question it was written for: its receiver, {@code Items.SHEARS}, and an {@code Operation} ({@code KernelShears.relay})
 * that asks the carrier's question when the handler calls it about shears. So the carrier's answer is the handler's
 * {@code original}, and the handler's extension — or its veto — is the site's answer, as it was on vanilla. Restoring
 * the vanilla call next to the carrier's ({@code is(SHEARS) || canPerformAction(...)}) would have been the smaller edit,
 * and would have let the carrier's disjunct overrule a wrap that says no. Where no vanilla {@code is} call is left in the
 * method the handler moves (renamed aside, its injector removed, like MixinWrapOperationShim); where one is, the handler
 * stays bound to it and a copy of its body answers the carrier's call.
 *
 * <p>Only a Fabric mod's handler — NeoForge's and MinecraftForge's mods were written against the carrier call — and only
 * the reviewed shape: a {@code @WrapOperation} on {@code ItemStack.is(Object)Z} with one selector naming a row's method,
 * no ordinal, slice or {@code @Group}, and a handler that is exactly {@code (ItemStack, Object, Operation) → boolean}
 * with no sugar, of the target method's static-ness. The merged method must make the row's carrier call exactly once,
 * with the row's constant. {@code -Dforbric.shearsRelay=off} relays nothing.
 */
public final class MixinShearsRelay {
	public static final String PROPERTY = "forbric.shearsRelay";
	static final String RUNTIME = "net/forbric/kernel/runtime/KernelShears";
	static final String ITEM_STACK = "net/minecraft/world/item/ItemStack";
	static final String VANILLA_TARGET = "L" + ITEM_STACK + ";is(Ljava/lang/Object;)Z";
	static final String OPERATION = "com/llamalad7/mixinextras/injector/wrapoperation/Operation";
	static final String HANDLER_DESC = "(L" + ITEM_STACK + ";Ljava/lang/Object;L" + OPERATION + ";)Z";
	static final String ADDED_SUFFIX = "$forbricshears";
	private static final String WRAP_OPERATION = "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";
	private static final String GROUP = "Lorg/spongepowered/asm/mixin/injection/Group;";
	private static final String ABILITIES = "net/neoforged/neoforge/common/ItemAbilities";
	private static final String ABILITY = "Lnet/neoforged/neoforge/common/ItemAbility;";
	private static final String TOOL_ACTIONS = "net/minecraftforge/common/ToolActions";
	private static final String TOOL_ACTION = "Lnet/minecraftforge/common/ToolAction;";

	/**
	 * A vanilla {@code stack.is(Items.SHEARS)} the merge replaced with {@code stack.canPerformAction(constant)}.
	 *
	 * @param target  the class (internal name)
	 * @param method  the method's name; the merged class has one of that name
	 * @param owner   the constant's owner, {@link #ABILITIES} or {@link #TOOL_ACTIONS}
	 * @param field   the constant
	 * @param type    the constant's descriptor, which is also the carrier call's one parameter
	 * @param because why the carrier's question is the vanilla one, asked the carrier's way
	 */
	record Row(String target, String method, String owner, String field, String type, String because) {
		String carrierDesc() {
			return "(" + type + ")Z";
		}
	}

	private static final String NEO = "NeoForge's patch asks the stack for the shears ability ShearsItem declares, the same "
			+ "question vanilla's is(SHEARS) asked, extended to any item that declares it";
	private static final String FORGE = "MinecraftForge's patch asks the stack for SHEARS_HARVEST, the ToolAction form of vanilla's "
			+ "is(SHEARS). The merge dropped MinecraftForge's ShearsItem override, so the carrier says no even for vanilla shears "
			+ "(they shear through NeoForge's ShearsItem.interactLivingEntity instead); a relayed handler that says yes — BCLib's "
			+ "does for vanilla shears too — shears here, on vanilla's path";

	static final List<Row> ROWS = List.of(
			new Row("net/minecraft/world/level/block/PumpkinBlock", "useItemOn", ABILITIES, "SHEARS_CARVE", ABILITY, NEO),
			new Row("net/minecraft/world/level/block/TripWireBlock", "playerWillDestroy", ABILITIES, "SHEARS_DISARM", ABILITY, NEO),
			new Row("net/minecraft/world/level/block/BeehiveBlock", "useItemOn", ABILITIES, "SHEARS_HARVEST", ABILITY, NEO),
			new Row("net/minecraft/world/entity/animal/sheep/Sheep", "mobInteract", TOOL_ACTIONS, "SHEARS_HARVEST", TOOL_ACTION, FORGE),
			new Row("net/minecraft/world/entity/animal/golem/SnowGolem", "mobInteract", TOOL_ACTIONS, "SHEARS_HARVEST", TOOL_ACTION, FORGE),
			new Row("net/minecraft/world/entity/animal/cow/MushroomCow", "mobInteract", TOOL_ACTIONS, "SHEARS_HARVEST", TOOL_ACTION, FORGE));

	private MixinShearsRelay() {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/** Relays every eligible handler of a Fabric mod's {@code mixin}; returns how many. {@code targets} must return nodes WITH code. */
	public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
		if (!enabled() || mixin == null || mixin.methods == null || targets == null) return 0;
		if (MixinStubRebind.ecosystemOf(mixin.name) != Ecosystem.FABRIC) return 0;
		List<String> targetNames = MixinOverloadPin.targetsOf(mixin);
		if (targetNames.size() != 1) return 0;
		String targetName = targetNames.getFirst();
		if (ROWS.stream().noneMatch(row -> row.target().equals(targetName))) return 0;
		ClassNode target = targets.apply(targetName);
		if (target == null || target.methods == null) return 0;
		int relayed = 0;
		for (MethodNode handler : new ArrayList<>(mixin.methods)) {
			if (handler.name.endsWith(MixinHandlerShim.INNER_SUFFIX) || handler.name.endsWith(ADDED_SUFFIX)) continue;
			if (relay(mixin, handler, target)) relayed++;
		}
		return relayed;
	}

	private static boolean relay(ClassNode mixin, MethodNode handler, ClassNode target) {
		if (!HANDLER_DESC.equals(handler.desc) || grouped(handler)) return false;
		AnnotationNode injector = MixinFit.injectorOf(handler);
		if (injector == null || !WRAP_OPERATION.equals(injector.desc) || MixinFit.value(injector, "slice") != null) return false;
		List<AnnotationNode> points = MixinFit.atNodes(injector);
		if (points.size() != 1) return false;
		AnnotationNode at = points.getFirst();
		if (!"INVOKE".equals(MixinFit.asString(MixinFit.value(at, "value"))) || !VANILLA_TARGET.equals(MixinFit.asString(MixinFit.value(at, "target")))
				|| MixinFit.value(at, "ordinal") != null || MixinFit.value(at, "slice") != null) return false;
		List<String> selectors = MixinFit.stringList(MixinFit.value(injector, "method"));
		if (selectors.size() != 1) return false;
		MethodNode body = selected(target, selectors.getFirst());
		if (body == null) return false;
		Row row = ROWS.stream().filter(r -> r.target().equals(target.name) && r.method().equals(body.name)).findFirst().orElse(null);
		if (row == null || carrierCalls(body, row) != 1) return false;
		for (int i = 0; i < 3; i++) if (MixinFit.sugar(handler, i)) return false;
		boolean handlerStatic = (handler.access & Opcodes.ACC_STATIC) != 0;
		if (handlerStatic != ((body.access & Opcodes.ACC_STATIC) != 0)) return false;

		// Moved when the method has no vanilla `is` call left for the handler; added beside it when it has.
		boolean added = vanillaCalls(body) > 0;
		String innerName = handler.name + (added ? ADDED_SUFFIX + "$inner" : MixinHandlerShim.INNER_SUFFIX);
		String outerName = added ? handler.name + ADDED_SUFFIX : handler.name;
		for (MethodNode existing : mixin.methods) if (existing.name.equals(innerName)) return false;   // done already
		AnnotationNode moved = new AnnotationNode(injector.desc);
		injector.accept(moved);
		for (AnnotationNode point : MixinFit.atNodes(moved)) {
			for (int i = 0; i + 1 < point.values.size(); i += 2) {
				if ("target".equals(point.values.get(i))) point.values.set(i + 1, "L" + ITEM_STACK + ";canPerformAction" + row.carrierDesc());
			}
		}
		if (added) {
			mixin.methods.add(copyOf(handler, innerName));
		} else {
			handler.name = innerName;
			handler.visibleAnnotations = without(handler.visibleAnnotations, injector);
			handler.invisibleAnnotations = without(handler.invisibleAnnotations, injector);
		}

		// outer(stack, ability, operation) → inner(stack, Items.SHEARS, KernelShears.relay(operation, ability))
		MethodNode outer = new MethodNode(Opcodes.ASM9, handler.access & ~Opcodes.ACC_SYNTHETIC, outerName,
				"(L" + ITEM_STACK + ";" + row.type() + "L" + OPERATION + ";)Z", null, null);
		outer.visibleAnnotations = new ArrayList<>(List.of(moved));
		int first = handlerStatic ? 0 : 1;
		if (!handlerStatic) outer.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		outer.instructions.add(new VarInsnNode(Opcodes.ALOAD, first));
		outer.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, "net/minecraft/world/item/Items", "SHEARS", "Lnet/minecraft/world/item/Item;"));
		outer.instructions.add(new VarInsnNode(Opcodes.ALOAD, first + 2));
		outer.instructions.add(new VarInsnNode(Opcodes.ALOAD, first + 1));
		outer.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "relay",
				"(L" + OPERATION + ";Ljava/lang/Object;)L" + OPERATION + ";", false));
		outer.instructions.add(MixinHandlerShim.callOwn(mixin, handlerStatic, innerName, handler.desc));
		outer.instructions.add(new InsnNode(Opcodes.IRETURN));
		outer.maxStack = 5;
		outer.maxLocals = first + 3;
		mixin.methods.add(outer);
		ForbricLog.info("[Forbric/Mixin] %s: %s now also answers %s.%s's %s.%s check — the carrier's form of vanilla's "
				+ "is(Items.SHEARS) it was written for (%s)", mixin.name.replace('/', '.'), handler.name.replace(ADDED_SUFFIX, ""),
				target.name.substring(target.name.lastIndexOf('/') + 1), row.method(), row.owner().substring(row.owner().lastIndexOf('/') + 1),
				row.field(), added ? "beside its other is() calls" : "moved");
		return true;
	}

	/** Calls of the row's carrier method, each right after the row's constant. Any other carrier call counts against. */
	static int carrierCalls(MethodNode body, Row row) {
		int calls = 0;
		for (AbstractInsnNode insn : body.instructions) {
			if (!(insn instanceof MethodInsnNode call) || !call.owner.equals(ITEM_STACK) || !call.name.equals("canPerformAction")
					|| !call.desc.equals(row.carrierDesc())) continue;
			AbstractInsnNode previous = call.getPrevious();
			while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
			if (!(previous instanceof FieldInsnNode constant) || constant.getOpcode() != Opcodes.GETSTATIC || !constant.owner.equals(row.owner())
					|| !constant.name.equals(row.field()) || !constant.desc.equals(row.type())) return -1;
			calls++;
		}
		return calls;
	}

	static int vanillaCalls(MethodNode body) {
		int calls = 0;
		for (AbstractInsnNode insn : body.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(ITEM_STACK) && call.name.equals("is") && call.desc.equals("(Ljava/lang/Object;)Z")) calls++;
		}
		return calls;
	}

	/** The one method of {@code target} a selector names: {@code name} or {@code name + desc}. Null when none or several. */
	private static MethodNode selected(ClassNode target, String selector) {
		MethodNode found = null;
		for (MethodNode method : target.methods) {
			if (!selector.equals(method.name) && !selector.equals(method.name + method.desc)) continue;
			if (found != null) return null;
			found = method;
		}
		return found;
	}

	private static boolean grouped(MethodNode handler) {
		for (List<AnnotationNode> annotations : java.util.Arrays.asList(handler.visibleAnnotations, handler.invisibleAnnotations)) {
			if (annotations != null && annotations.stream().anyMatch(a -> GROUP.equals(a.desc))) return true;
		}
		return false;
	}

	private static List<AnnotationNode> without(List<AnnotationNode> annotations, AnnotationNode removed) {
		if (annotations == null) return null;
		List<AnnotationNode> kept = new ArrayList<>(annotations);
		kept.remove(removed);
		return kept;
	}

	/**
	 * The handler's body as a plain method named {@code name}: no annotation, fresh labels. Not {@code MethodNode.accept}
	 * into a new node — that can hand the copy the original's {@code LabelNode}s, and one node in two lists corrupts both.
	 */
	static MethodNode copyOf(MethodNode handler, String name) {
		MethodNode copy = new MethodNode(Opcodes.ASM9, handler.access, name, handler.desc, handler.signature,
				handler.exceptions == null ? null : handler.exceptions.toArray(String[]::new));
		java.util.Map<LabelNode, LabelNode> labels = new java.util.IdentityHashMap<>();
		for (AbstractInsnNode insn : handler.instructions) if (insn instanceof LabelNode label) labels.put(label, new LabelNode());
		for (AbstractInsnNode insn : handler.instructions) copy.instructions.add(insn.clone(labels));
		for (TryCatchBlockNode block : handler.tryCatchBlocks) {
			copy.tryCatchBlocks.add(new TryCatchBlockNode(labels.get(block.start), labels.get(block.end), labels.get(block.handler), block.type));
		}
		copy.maxStack = handler.maxStack;
		copy.maxLocals = handler.maxLocals;
		return copy;
	}

	/** For tests: the carrier target the row's handler is moved to. */
	static String carrierTarget(Row row) {
		return "L" + ITEM_STACK + ";canPerformAction" + row.carrierDesc();
	}
}
