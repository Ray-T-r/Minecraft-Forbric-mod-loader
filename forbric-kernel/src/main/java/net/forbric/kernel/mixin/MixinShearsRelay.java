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
 * harvesting a beehive, shearing a sheep, a snow golem and a mooshroom. NeoForge's bodies ask the stack instead in the
 * three blocks, {@code canPerformAction(ItemAbilities.SHEARS_*)}. BCLib wraps each vanilla call
 * ({@code original || tagged c:tools/shear or a mineable/shears tool}) so a modded shears item that only carries the tag
 * works; on NeoForge's blocks two of those wraps have nothing to bind to, and the beehive one binds only to that method's
 * other {@code is} call (glass bottle), where it does nothing. A tag-only shears item carved and disarmed nothing.
 *
 * <p>Of the three mobs, NeoForge 26.2.0.93 keeps vanilla's {@code is(SHEARS)} in the mooshroom (the wrap binds as compiled).
 * It shears a sheep or a snow golem through {@code ShearsItem.interactLivingEntity} instead, its {@code IShearable} path,
 * with no question left in {@code mobInteract} for a wrap to follow (javap: {@code getItemInHand; invokespecial
 * Animal.mobInteract; areturn}), so an item BCLib calls shears that is not a {@code ShearsItem} sheared neither mob, which
 * native Fabric does. For those two ({@link #MOB_ROWS}) the handler moves aside behind a new cancellable HEAD
 * {@code @Inject} of {@code mobInteract} that asks it vanilla's question about the held stack, with vanilla's
 * {@code is} as its {@code original} ({@code KernelMobShears.vanillaIs}); where it answers yes for a stack that is not a
 * {@code ShearsItem} (one NeoForge's own path shears after {@code mobInteract}), NeoForge's shearing entry is run with that
 * stack ({@code KernelMobShears.shearAsShears}: {@code ShearsItem.interactLivingEntity}, which reads only its arguments), and
 * its answer, if it sheared, is {@code mobInteract}'s. An item with an entity entry of its own ({@code interactLivingEntity})
 * that is not a Fabric mod's is left to that entry, which {@code Player.interactOn} asks after {@code mobInteract} passes, as
 * on NeoForge; a Fabric item's own entry is pre-empted, as native Fabric with BCLib pre-empts it (run/compat/regress-gate.py,
 * {@code shears.sheep.ownEntry.*}). A wrap that says no to real shears is not carried over there:
 * NeoForge's path still shears with them, as on NeoForge. {@code -Dforbric.shearsRelayMobs=off} leaves these two wraps as
 * compiled (bound nowhere). The merged base had put MinecraftForge's {@code canPerformAction(ToolActions.SHEARS_HARVEST)}
 * in all three, and three rows relayed to it; they went with MinecraftForge.
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
 * <p>Only a Fabric mod's handler — NeoForge's mods were written against the carrier call — and only
 * the reviewed shape: a {@code @WrapOperation} on {@code ItemStack.is(Object)Z} — its point read as Mixin reads a target, so
 * whitespace, a dotted owner, or (where the method it was written for, in the class the mod was compiled against, decides
 * it) no owner or descriptor name the same call — bound by its selectors, as Mixin binds them in the merged class, to a
 * row's method, no ordinal, slice or {@code @Group}, and a handler that is exactly {@code (ItemStack, Object, Operation) → boolean}
 * with no sugar, of the target method's static-ness. The merged method must make the row's carrier call exactly once,
 * with the row's constant. {@code -Dforbric.shearsRelay=off} relays nothing.
 */
public final class MixinShearsRelay {
	public static final String PROPERTY = "forbric.shearsRelay";
	/** Off: the sheep and snow golem wraps ({@link #MOB_ROWS}) stay as compiled; the block rows are {@link #PROPERTY}'s. */
	public static final String MOB_PROPERTY = "forbric.shearsRelayMobs";
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

	/**
	 * A vanilla {@code stack.is(Items.SHEARS)} the merge replaced with {@code stack.canPerformAction(constant)}.
	 *
	 * @param target  the class (internal name)
	 * @param method  the method's name; the merged class has one of that name
	 * @param owner   the constant's owner, {@link #ABILITIES}
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

	static final List<Row> ROWS = List.of(
			new Row("net/minecraft/world/level/block/PumpkinBlock", "useItemOn", ABILITIES, "SHEARS_CARVE", ABILITY, NEO),
			new Row("net/minecraft/world/level/block/TripWireBlock", "playerWillDestroy", ABILITIES, "SHEARS_DISARM", ABILITY, NEO),
			new Row("net/minecraft/world/level/block/BeehiveBlock", "useItemOn", ABILITIES, "SHEARS_HARVEST", ABILITY, NEO));

	/**
	 * A mob whose vanilla {@code mobInteract} shears on {@code stack.is(Items.SHEARS)} and whose base body asks nothing:
	 * NeoForge shears it through {@code ShearsItem.interactLivingEntity}.
	 */
	record MobRow(String target, String because) {
	}

	private static final String SHEARS_ENTRY = "NeoForge's patch removed the shears branch from mobInteract and shears the mob "
			+ "through ShearsItem.interactLivingEntity's IShearable path, which only a ShearsItem reaches";

	static final List<MobRow> MOB_ROWS = List.of(
			new MobRow("net/minecraft/world/entity/animal/sheep/Sheep", SHEARS_ENTRY),
			new MobRow("net/minecraft/world/entity/animal/golem/SnowGolem", SHEARS_ENTRY));
	static final String MOB_INTERACT = "mobInteract(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)"
			+ "Lnet/minecraft/world/InteractionResult;";
	static final String SHEARS_ITEM = "net/minecraft/world/item/ShearsItem";
	static final String SHEARS_INTERACT = "interactLivingEntity(L" + ITEM_STACK + ";Lnet/minecraft/world/entity/player/Player;"
			+ "Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;";
	private static final String SHEARABLE = "net/neoforged/neoforge/common/IShearable";
	private static final String CIR = "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable";
	static final String MOB_RUNTIME = "net/forbric/kernel/runtime/KernelMobShears";

	private MixinShearsRelay() {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	static boolean mobsEnabled() {
		return !"off".equalsIgnoreCase(System.getProperty(MOB_PROPERTY, "on"));
	}

	/** Relays every eligible handler of a Fabric mod's {@code mixin}; returns how many. {@code targets} must return nodes WITH code. */
	public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
		return adapt(mixin, targets, NativeGameReferences::reference);
	}

	/** {@code references} gives the class the mod was compiled against, where a point without its owner or descriptor is read. */
	static int adapt(ClassNode mixin, Function<String, ClassNode> targets, java.util.function.BiFunction<Ecosystem, String, ClassNode> references) {
		if (!enabled() || mixin == null || mixin.methods == null || targets == null) return 0;
		if (MixinStubRebind.ecosystemOf(mixin.name) != Ecosystem.FABRIC) return 0;
		List<String> targetNames = MixinOverloadPin.targetsOf(mixin);
		if (targetNames.size() != 1) return 0;
		String targetName = targetNames.getFirst();
		MobRow mob = MOB_ROWS.stream().filter(row -> row.target().equals(targetName)).findFirst().orElse(null);
		if (mob == null && ROWS.stream().noneMatch(row -> row.target().equals(targetName))) return 0;
		ClassNode target = targets.apply(targetName);
		if (target == null || target.methods == null) return 0;
		ClassNode source = references == null ? null : references.apply(Ecosystem.FABRIC, targetName);
		int relayed = 0;
		if (mob != null) {
			if (!mobsEnabled() || !shearsEntry(targets.apply(SHEARS_ITEM))) return 0;
			for (MethodNode handler : new ArrayList<>(mixin.methods)) {
				if (handler.name.contains(MixinHandlerShim.INNER_SUFFIX) || handler.name.endsWith(ADDED_SUFFIX)) continue;
				if (relayMob(mixin, handler, target, source, mob)) relayed++;
			}
			return relayed;
		}
		for (MethodNode handler : new ArrayList<>(mixin.methods)) {
			if (handler.name.contains(MixinHandlerShim.INNER_SUFFIX) || handler.name.endsWith(ADDED_SUFFIX)) continue;
			if (relay(mixin, handler, target, source)) relayed++;
		}
		return relayed;
	}

	private static boolean relay(ClassNode mixin, MethodNode handler, ClassNode target, ClassNode source) {
		if (!HANDLER_DESC.equals(handler.desc) || grouped(handler)) return false;
		AnnotationNode injector = MixinFit.injectorOf(handler);
		if (injector == null || !WRAP_OPERATION.equals(injector.desc) || MixinFit.value(injector, "slice") != null) return false;
		List<AnnotationNode> points = MixinFit.atNodes(injector);
		if (points.size() != 1) return false;
		AnnotationNode at = points.getFirst();
		if (!"INVOKE".equals(MixinFit.asString(MixinFit.value(at, "value")))
				|| !MixinCallbackShape.names(at, VANILLA_TARGET, MixinCallbackShape.written(handler, source))
				|| MixinFit.value(at, "ordinal") != null || MixinFit.value(at, "slice") != null) return false;
		// The one method Mixin binds the selectors to in the merged class, however they are written.
		MethodNode body = MixinTargetSelectors.one(handler, target);
		if (body == null) return false;
		Row row = ROWS.stream().filter(r -> r.target().equals(target.name) && r.method().equals(body.name)).findFirst().orElse(null);
		if (row == null || carrierCalls(body, row) != 1) return false;
		for (int i = 0; i < 3; i++) if (MixinFit.sugar(handler, i)) return false;
		boolean handlerStatic = (handler.access & Opcodes.ACC_STATIC) != 0;
		if (handlerStatic != ((body.access & Opcodes.ACC_STATIC) != 0)) return false;

		// Moved when the method has no vanilla `is` call left for the handler; added beside it when it has.
		boolean added = vanillaCalls(body) > 0;
		String innerName = added ? handler.name + ADDED_SUFFIX + "$inner" : MixinHandlerShim.asideName(mixin.name, handler.name, MixinHandlerShim.INNER_SUFFIX);
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
				+ "is(Items.SHEARS) it was written for (%s)", mixin.name.replace('/', '.'), outerName.replace(ADDED_SUFFIX, ""),
				target.name.substring(target.name.lastIndexOf('/') + 1), row.method(), row.owner().substring(row.owner().lastIndexOf('/') + 1),
				row.field(), added ? "beside its other is() calls" : "moved");
		return true;
	}

	/**
	 * A sheep's or snow golem's wrap: the reviewed shape bound to {@code mobInteract}, whose base body asks neither vanilla's
	 * nor the carrier's shears question. The handler moves aside behind a HEAD {@code @Inject}:
	 * {@code inner(stack, Items.SHEARS, vanillaIs)} for the held stack, then NeoForge's shearing entry for a yes.
	 */
	private static boolean relayMob(ClassNode mixin, MethodNode handler, ClassNode target, ClassNode source, MobRow row) {
		if (!reviewedShape(handler, source)) return false;
		MethodNode body = MixinTargetSelectors.one(handler, target);
		if (body == null || !MOB_INTERACT.equals(body.name + body.desc) || (body.access & Opcodes.ACC_STATIC) != 0
				|| (handler.access & Opcodes.ACC_STATIC) != 0) return false;
		if (vanillaCalls(body) != 0) return false;
		for (AbstractInsnNode insn : body.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(ITEM_STACK) && call.name.equals("canPerformAction")) return false;
		}
		String innerName = MixinHandlerShim.asideName(mixin.name, handler.name, MixinHandlerShim.INNER_SUFFIX);
		for (MethodNode existing : mixin.methods) if (existing.name.equals(innerName)) return false;   // done already
		AnnotationNode injector = MixinFit.injectorOf(handler);
		String outerName = handler.name;
		handler.name = innerName;
		handler.visibleAnnotations = without(handler.visibleAnnotations, injector);
		handler.invisibleAnnotations = without(handler.invisibleAnnotations, injector);

		// outer(player, hand, cir): stack = player.getItemInHand(hand);
		//   if (inner(stack, Items.SHEARS, KernelMobShears.vanillaIs())) { r = KernelMobShears.shearAsShears(stack, player, this, hand); if (r != null) cir.setReturnValue(r); }
		String player = "net/minecraft/world/entity/player/Player", hand = "net/minecraft/world/InteractionHand";
		MethodNode outer = new MethodNode(Opcodes.ASM9, (handler.access & ~Opcodes.ACC_SYNTHETIC), outerName,
				"(L" + player + ";L" + hand + ";L" + CIR + ";)V", null, null);
		AnnotationNode inject = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
		AnnotationNode head = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		head.values = new ArrayList<>(List.of("value", "HEAD"));
		inject.values = new ArrayList<>(List.of("method", List.of(MOB_INTERACT), "at", List.of(head), "cancellable", true, "require", 1));
		outer.visibleAnnotations = new ArrayList<>(List.of(inject));
		var code = outer.instructions;
		LabelNode done = new LabelNode();
		code.add(new VarInsnNode(Opcodes.ALOAD, 1));
		code.add(new VarInsnNode(Opcodes.ALOAD, 2));
		code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, player, "getItemInHand", "(L" + hand + ";)L" + ITEM_STACK + ";", false));
		code.add(new VarInsnNode(Opcodes.ASTORE, 4));
		code.add(new VarInsnNode(Opcodes.ALOAD, 0));
		code.add(new VarInsnNode(Opcodes.ALOAD, 4));
		code.add(new FieldInsnNode(Opcodes.GETSTATIC, "net/minecraft/world/item/Items", "SHEARS", "Lnet/minecraft/world/item/Item;"));
		code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MOB_RUNTIME, "vanillaIs", "()L" + OPERATION + ";", false));
		code.add(MixinHandlerShim.callOwn(mixin, false, innerName, handler.desc));
		code.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.IFEQ, done));
		code.add(new VarInsnNode(Opcodes.ALOAD, 4));
		code.add(new VarInsnNode(Opcodes.ALOAD, 1));
		code.add(new VarInsnNode(Opcodes.ALOAD, 0));
		code.add(new VarInsnNode(Opcodes.ALOAD, 2));
		code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MOB_RUNTIME, "shearAsShears",
				"(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false));
		code.add(new VarInsnNode(Opcodes.ASTORE, 5));
		code.add(new VarInsnNode(Opcodes.ALOAD, 5));
		code.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.IFNULL, done));
		code.add(new VarInsnNode(Opcodes.ALOAD, 3));
		code.add(new VarInsnNode(Opcodes.ALOAD, 5));
		code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CIR, "setReturnValue", "(Ljava/lang/Object;)V", false));
		code.add(done);
		code.add(new org.objectweb.asm.tree.FrameNode(Opcodes.F_SAME, 0, null, 0, null));
		code.add(new InsnNode(Opcodes.RETURN));
		outer.maxStack = 4;
		outer.maxLocals = 6;
		mixin.methods.add(outer);
		ForbricLog.info("[Forbric/Mixin] %s: %s now also answers %s.mobInteract, whose shears check NeoForge moved into "
				+ "ShearsItem.interactLivingEntity: a stack it calls shears that is not a ShearsItem is sheared through that entry (%s)",
				mixin.name.replace('/', '.'), outerName, target.name.substring(target.name.lastIndexOf('/') + 1), row.because());
		return true;
	}

	/** The handler shape both relays take: a lone ungrouped {@code @WrapOperation} of vanilla's {@code is}, no ordinal, slice or sugar. */
	private static boolean reviewedShape(MethodNode handler, ClassNode source) {
		if (!HANDLER_DESC.equals(handler.desc) || grouped(handler)) return false;
		AnnotationNode injector = MixinFit.injectorOf(handler);
		if (injector == null || !WRAP_OPERATION.equals(injector.desc) || MixinFit.value(injector, "slice") != null) return false;
		List<AnnotationNode> points = MixinFit.atNodes(injector);
		if (points.size() != 1) return false;
		AnnotationNode at = points.getFirst();
		if (!"INVOKE".equals(MixinFit.asString(MixinFit.value(at, "value")))
				|| !MixinCallbackShape.names(at, VANILLA_TARGET, MixinCallbackShape.written(handler, source))
				|| MixinFit.value(at, "ordinal") != null || MixinFit.value(at, "slice") != null) return false;
		for (int i = 0; i < 3; i++) if (MixinFit.sugar(handler, i)) return false;
		return true;
	}

	/** NeoForge's shearing entry: {@code ShearsItem.interactLivingEntity}, asking the entity whether it is {@code IShearable}. */
	static boolean shearsEntry(ClassNode shears) {
		if (shears == null || shears.methods == null) return false;
		for (MethodNode m : shears.methods) {
			if (!SHEARS_INTERACT.equals(m.name + m.desc)) continue;
			for (AbstractInsnNode insn : m.instructions) {
				if (insn instanceof org.objectweb.asm.tree.TypeInsnNode type && type.getOpcode() == Opcodes.INSTANCEOF && SHEARABLE.equals(type.desc)) return true;
			}
		}
		return false;
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
