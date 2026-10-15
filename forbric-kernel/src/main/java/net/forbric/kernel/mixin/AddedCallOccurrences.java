/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.util.ForbricLog;

/**
 * Numbers an unnumbered call or field point with the native occurrences when the carrier added more occurrences.
 *
 * <p>An {@code @At(INVOKE)} or {@code @At(FIELD)} without an ordinal takes every call (or access) of its target in the
 * method. A mod compiled against the
 * native body means the calls that body makes; when the surviving carrier added a call of the same method on another
 * path, the handler also runs there, on a path the native game does not have. NeoForge's
 * {@code ServerPlayerGameMode.useItemOn} asks {@code player.getMainHandItem().doesSneakBypassUse(...)} after vanilla's
 * {@code getMainHandItem().isEmpty()}: Create's cancellable {@code @Inject} at {@code getMainHandItem} ran twice for a
 * sneaking player with an item in hand, and its wrench, clipboard and manual-processing handling with it. And NeoForge's
 * {@code ClientPacketListener.handleCommands} assigns {@code commands} a second time (the server commands merged with
 * the client ones): Create's {@code @At(FIELD, opcode=PUTFIELD, shift=AFTER)} registered its commands twice. A
 * {@code @ModifyVariable(at = @At("STORE"))} is the same for a local the carrier writes again: NeoForge's
 * {@code Animal.spawnChildFromBreeding} stores {@code child = event.getChild()} after its {@code BabyEntitySpawnEvent},
 * and PuzzlesLib's breeding event ran a second time on the event's result (and so did its bow, fox-breeding and
 * entity-size events, the last two picking their local by ordinal).
 *
 * <p>The native occurrences are found in the current body by {@link CallOccurrenceAlignment#nativeAmongAdded}: each one
 * the single current occurrence with the same operand origins (and the same next operation, wherever both sides have
 * one, which also tells apart occurrences that share their operands), in the same order, every occurrence's operands
 * analysable, and no current occurrence two native ones would both describe. The point then counts exactly
 * those: one native occurrence becomes its ordinal; several, in an injector whose {@code at} is a list, become one
 * {@code @At} each; a numbered point, which counts the native occurrences, takes the number of the same call here
 * (Architectury's right-click-air hook names vanilla's second {@code ItemStack.isEmpty} in {@code Minecraft.startUseItem},
 * the one in front of {@code useItem}; MinecraftForge and NeoForge each put an empty-hand check in front of it). A point in a slice, an injector in a callback group or with a slice, a handler whose ordinals an
 * earlier pass already counted over the current body ({@link CurrentBodyOrdinals}), and a target the merged body selects
 * other calls with are left as written. {@code -Dforbric.addedCallOccurrences=off} leaves every point as compiled.
 */
public final class AddedCallOccurrences {
	public static final String PROPERTY = "forbric.addedCallOccurrences";

	private AddedCallOccurrences() {
	}

	/** Numbers every eligible point of {@code mixin}; returns how many. {@code targets} must return nodes WITH code. */
	public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
		return adapt(mixin, targets, NativeGameReferences::reference);
	}

	/** The source seam supplies hash-verified native bytes in production and explicit original bytes in tests. */
	static int adapt(ClassNode mixin, Function<String, ClassNode> targets, BiFunction<Ecosystem, String, ClassNode> references) {
		if ("off".equalsIgnoreCase(net.forbric.kernel.util.ForbricSwitches.get(PROPERTY, "on")) || mixin == null || mixin.methods == null
				|| targets == null || references == null) return 0;
		Ecosystem ecosystem = MixinStubRebind.ecosystemOf(mixin.name);
		List<String> owners = MixinOverloadPin.targetsOf(mixin);
		if (ecosystem == null || owners.size() != 1) return 0;
		ClassNode current = null, original = null;
		int numbered = 0;
		for (MethodNode handler : mixin.methods) {
			if (CurrentBodyOrdinals.counted(handler)) continue;
			AnnotationNode injector = MixinFit.injectorOf(handler);
			if (injector == null || MixinFit.value(injector, "slice") != null || MixinFit.groupOf(handler) != null) continue;
			List<AnnotationNode> points = MixinFit.atNodes(injector);
			if (points.stream().noneMatch(AddedCallOccurrences::countedPoint)) continue;
			if (current == null) {
				current = targets.apply(owners.getFirst());
				original = references.apply(ecosystem, owners.getFirst());
				if (current == null || original == null) return numbered;
			}
			MethodNode before = MixinTargetSelectors.one(handler, original), after = MixinTargetSelectors.one(handler, current);
			if (before == null || after == null || !before.name.equals(after.name) || !before.desc.equals(after.desc)) continue;
			for (AnnotationNode at : List.copyOf(points)) {
				if (!countedPoint(at)) continue;
				String member;
				int[] counterparts;
				if ("FIELD".equals(MixinFit.asString(MixinFit.value(at, "value")))) {
					MixinFit.Member field = MixinFit.parseMember(MixinFit.asString(MixinFit.value(at, "target")));
					if (field == null || field.owner() == null || field.desc() == null || field.desc().startsWith("(")) continue;
					Integer opcode = MixinFit.value(at, "opcode") instanceof Integer o && o >= 0 ? o : null;
					member = "L" + field.owner() + ";" + field.name() + ":" + field.desc() + (opcode == null ? "" : " opcode " + opcode);
					counterparts = CallOccurrenceAlignment.nativeAmongAdded(original, before, current, after,
							instruction -> instruction instanceof FieldInsnNode access && access.owner.equals(field.owner())
									&& access.name.equals(field.name()) && access.desc.equals(field.desc())
									&& (opcode == null || access.getOpcode() == opcode));
				} else if ("STORE".equals(MixinFit.asString(MixinFit.value(at, "value")))) {
					if (!MODIFY_VARIABLE.equals(injector.desc) || Boolean.TRUE.equals(MixinFit.value(injector, "argsOnly"))
							|| before.localVariables == null || after.localVariables == null) continue;
					Type type = Type.getReturnType(handler.desc);
					if (type.getSort() == Type.VOID) continue;
					int storeOpcode = type.getOpcode(Opcodes.ISTORE);
					MethodNode nativeBody = before, liveBody = after;
					member = "the " + type.getClassName() + " local's stores";
					// A store of the local the discriminator picks right after it, as the injector's own ordinal, index or
					// name (or the type alone) picks it there — read off each body's local variable table.
					counterparts = CallOccurrenceAlignment.nativeAmongAdded(original, before, current, after,
							instruction -> instruction instanceof VarInsnNode store && store.getOpcode() == storeOpcode
									&& store.var == pickedAfter(injector, type, nativeBody.instructions.contains(store) ? nativeBody : liveBody, store));
				} else {
					member = MixinCallbackShape.invoked(at, before);
					if (member == null || !onlyCallsOf(MixinCallbackShape.calls(at, after), member)) continue;
					counterparts = CallOccurrenceAlignment.nativeAmongAdded(original, before, current, after, member);
				}
				if (counterparts == null) continue;
				if (MixinFit.value(at, "ordinal") instanceof Integer ordinal && ordinal >= 0) {
					// A numbered point counts the native occurrences: the k-th of them is the same call here.
					if (ordinal >= counterparts.length || counterparts[ordinal] == ordinal) continue;
					set(at, "ordinal", counterparts[ordinal]);
					counterparts = new int[] {counterparts[ordinal]};
				} else if (counterparts.length == 1) {
					set(at, "ordinal", counterparts[0]);
				} else if (MixinFit.value(injector, "at") instanceof List<?> list) {
					@SuppressWarnings("unchecked") List<Object> each = (List<Object>) list;
					int index = each.indexOf(at);
					if (index < 0) continue;
					each.remove(index);
					for (int k = counterparts.length - 1; k >= 0; k--) {
						AnnotationNode copy = new AnnotationNode(at.desc);
						copy.values = new ArrayList<>(at.values);
						set(copy, "ordinal", counterparts[k]);
						each.add(index, copy);
					}
				} else {
					continue;
				}
				numbered++;
				CurrentBodyOrdinals.mark(handler);
				ForbricLog.info("[Forbric/Mixin] %s: %s's point at %s now counts only the native occurrence(s) %s of %s.%s — the "
						+ "carrier added the rest, where the native game never ran it (same operand origins and next operation)",
						mixin.name.replace('/', '.'), handler.name, member, Arrays.toString(counterparts), current.name.replace('/', '.'),
						after.name);
			}
		}
		return numbered;
	}

	private static final String MODIFY_VARIABLE = "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;";

	/**
	 * The slot the {@code @ModifyVariable}'s discriminator picks just after {@code store} in {@code method}: its ordinal,
	 * index or name, or the type alone, among the parameters and the locals the table has live there
	 * ({@link MixinLocalOriginProof#slot}); -1 when none or more than one answers.
	 */
	private static int pickedAfter(AnnotationNode injector, Type type, MethodNode method, VarInsnNode store) {
		AbstractInsnNode after = store.getNext();
		return after == null ? -1 : MixinLocalOriginProof.slot(injector, type, method, method.instructions.indexOf(after));
	}

	private static boolean countedPoint(AnnotationNode at) {
		String value = MixinFit.asString(MixinFit.value(at, "value"));
		return ("INVOKE".equals(value) || "FIELD".equals(value) || "STORE".equals(value)) && MixinFit.value(at, "slice") == null;
	}

	private static boolean onlyCallsOf(List<MethodInsnNode> calls, String member) {
		if (calls == null || calls.isEmpty()) return false;
		for (MethodInsnNode call : calls) if (!CallOccurrenceAlignment.member(call).equals(member)) return false;
		return true;
	}

	private static void set(AnnotationNode at, String key, Object value) {
		if (at.values == null) at.values = new ArrayList<>();
		for (int i = 0; i + 1 < at.values.size(); i += 2) {
			if (key.equals(at.values.get(i))) {
				at.values.set(i + 1, value);
				return;
			}
		}
		at.values.add(key);
		at.values.add(value);
	}
}
