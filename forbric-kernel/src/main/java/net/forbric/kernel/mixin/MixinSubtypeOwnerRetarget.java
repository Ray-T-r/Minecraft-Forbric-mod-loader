/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import net.forbric.kernel.transform.WidenedFieldTwinInjector;
import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

/**
 * An {@code @Inject} anchored on a call the merged body now makes through another owner of the same method.
 *
 * <p>Mixin matches an INVOKE target by its recorded owner, so a mod compiled against vanilla — where
 * {@code RegistryLoadTask.PendingRegistration.loadFromResource} calls {@code Decoder.parse} — finds nothing in NeoForge's
 * body, which decodes through {@code ConditionalOps.createConditionalCodec(…).parse}: {@code Codec.parse}, the very same
 * method ({@code Codec} extends {@code Decoder} and does not redeclare it). lithostitched's Fabric build reads a
 * resource's {@code "predicate"} there and skips entries whose predicate fails; with the anchor missing, entries gated on
 * another mod being installed always loaded (its NeoForge build already anchors on {@code Codec.parse}).
 *
 * <p>The anchor is moved to the subtype's call only where that is the same call: the pair is in {@link #SAME_METHOD}
 * (verified: the subtype does not redeclare it); the injector is an {@code @Inject}, which never sees the receiver; the
 * selected method makes no call with the recorded owner and exactly one with the subtype; and every {@code @Local} the
 * handler takes is named and covers that call. A transported source decode guard records its completed evaluation
 * within the parse scope, so the native condition funnel does not evaluate the same predicate/input twice.
 * {@code -Dforbric.mixinSubtypeOwner=off} leaves every injector as written.
 *
 * <h2>Through a field the merge widened</h2>
 *
 * <p>javac records a call's owner as the static type of its receiver, so when both carriers widened
 * {@code RangedBowAttackGoal.mob} from {@code Monster} to {@code Mob} (the goal takes any {@code Mob} there), every call
 * through the field changed owner with it: {@code this.mob.lookAt(target, 30, 30)} is {@code Monster.lookAt} in vanilla
 * and {@code Mob.lookAt} in the merged {@code tick}. debugify's MC-121706 fix injects AFTER {@code Monster.lookAt}; it
 * attached nowhere, and its {@code require=1} miss stopped a strict launch that native Fabric runs.
 *
 * <p>That is the same method only when the receiver is the field, so the point moves only for those calls, along a
 * NARROW row of {@link WidenedFieldTwinInjector} (the ledger of fields the merge widened): the recorded owner is the
 * row's vanilla type and no class between it and the merged type redeclares the method, so a receiver that IS a vanilla
 * type dispatches exactly as before; the selected method makes no call with the recorded owner; and each call through
 * the merged type is told apart by where its receiver comes from — a {@code getfield} of the widened field on
 * {@code this}, possibly through locals, or anything else. {@code tick} makes {@code Mob.lookAt} twice, once on the
 * vehicle and once on the field, and only the second was vanilla's {@code Monster.lookAt}: the point becomes
 * {@code Mob.lookAt} with the ordinal of that call (with no ordinal, exactly one such call; with ordinal n, the n-th).
 * A receiver that may come from either declines the move.
 *
 * <p>And then the handler runs only where vanilla's could. On the merged base the field may hold a {@code Mob} that is
 * no {@code Monster} — a NeoForge or MinecraftForge mod's archer built on the widened goal — and a vanilla-compiled
 * handler reads the vanilla-typed twin of the field, which is null for it. The handler is renamed aside and the name,
 * descriptor and annotation go to a guard that calls it only when the field holds the vanilla type, so the other
 * ecosystems' mobs run the goal exactly as before. Only an {@code @Inject} with that one point, an instance handler with
 * no sugar, on a final field. {@code -Dforbric.mixinSubtypeOwner.retypedField=off} leaves these points as compiled.
 */
public final class MixinSubtypeOwnerRetarget {
	public static final String PROPERTY = "forbric.mixinSubtypeOwner";
	/** {@code -Dforbric.mixinSubtypeOwner.retypedField=off}: no point follows a call through a field the merge widened. */
	public static final String RETYPED_FIELD_PROPERTY = "forbric.mixinSubtypeOwner.retypedField";
	/**
	 * The suffix a guarded handler's own body moves to, under the guard that keeps its name and annotation, before the
	 * mixin's mark ({@link MixinHandlerShim#asideName}).
	 */
	static final String NARROW_SUFFIX = "$forbricnarrow";
	/** Recorded owner → a subtype through which the merged game calls the same method (name and descriptor). */
	static final Map<String, String> SAME_METHOD = Map.of("com/mojang/serialization/Decoder", "com/mojang/serialization/Codec");
	private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
	private static final String LOCAL = "Lcom/llamalad7/mixinextras/sugar/Local;";

	private MixinSubtypeOwnerRetarget() {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	static boolean retypedFieldEnabled() {
		return enabled() && !"off".equalsIgnoreCase(System.getProperty(RETYPED_FIELD_PROPERTY, "on"));
	}

	/** One decided move: the {@code @At} target it gets, the ordinal (or null to leave it), and the field to guard on. */
	record Move(String target, Integer ordinal, WidenedFieldTwinInjector.Narrowed field, String described) {
	}

	public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
		if (!enabled()) return 0;
		List<String> owners = MixinFit.mixinTargets(mixin);
		if (owners.size() != 1) return 0;
		ClassNode target = null;
		int moved = 0;
		List<String> described = new ArrayList<>();
		List<MethodNode> guards = new ArrayList<>();
		for (MethodNode handler : new ArrayList<>(mixin.methods)) {
			AnnotationNode injector = MixinFit.injectorOf(handler);
			if (injector == null || !injector.desc.equals(INJECT)) continue;
			List<String> selectors = MixinFit.stringList(MixinFit.value(injector, "method"));
			if (selectors.size() != 1) continue;
			for (AnnotationNode at : MixinFit.atNodes(injector)) {
				if (!"INVOKE".equals(MixinFit.value(at, "value"))) continue;
				String recorded = MixinFit.asString(MixinFit.value(at, "target"));
				if (!candidate(recorded, owners.getFirst())) continue;
				if (target == null) target = targets.apply(owners.getFirst());
				if (target == null) return moved;
				MethodNode host = select(target, selectors.getFirst());
				if (host == null) continue;
				Move move = decide(handler, injector, at, recorded, target, host, targets);
				if (move == null) continue;
				String name = handler.name;
				setValue(at, "target", move.target());
				if (move.ordinal() != null) setValue(at, "ordinal", move.ordinal());
				if (move.field() != null) guards.add(guard(mixin, handler, injector, move.field()));
				moved++;
				described.add(name + " → " + move.described());
			}
		}
		mixin.methods.addAll(guards);
		if (moved > 0) {
			ForbricLog.info("[Forbric/Mixin] %s: %s — the merged body makes the same call through another owner, which "
					+ "Mixin's owner match did not see", mixin.name.replace('/', '.'), String.join(", ", described));
		}
		return moved;
	}

	/**
	 * Where {@link #adapt} will point this {@code @At(INVOKE, recorded)} of {@code handler}, as an {@code @At} target, or
	 * null when it leaves the point as compiled — the same decision, nothing changed. {@link MixinFit} asks this so the
	 * verdict and the move cannot disagree. {@code classes} serves classes WITH instructions and local variable tables.
	 */
	static String wouldMove(String mixinName, MethodNode handler, AnnotationNode injector, String recorded,
			ClassNode target, Function<String, ClassNode> classes) {
		if (!enabled() || mixinName == null || injector == null || !INJECT.equals(injector.desc)
				|| target == null || !candidate(recorded, target.name)) return null;
		List<String> selectors = MixinFit.stringList(MixinFit.value(injector, "method"));
		if (selectors.size() != 1) return null;
		ClassNode full = classes.apply(target.name);
		if (full == null) return null;
		MethodNode host = select(full, selectors.getFirst());
		if (host == null) return null;
		for (AnnotationNode at : MixinFit.atNodes(injector)) {
			if (!"INVOKE".equals(MixinFit.value(at, "value")) || !recorded.equals(MixinFit.asString(MixinFit.value(at, "target")))) continue;
			Move move = decide(handler, injector, at, recorded, full, host, classes);
			if (move != null) return move.target();
		}
		return null;
	}

	/**
	 * Whether a point naming {@code recorded} in a mixin on {@code targetName} is one either rule could move — asked before
	 * the target is read, which for most mixins it then never is.
	 */
	private static boolean candidate(String recorded, String targetName) {
		if (recorded == null || !recorded.startsWith("L") || recorded.indexOf(';') < 0) return false;
		String owner = recorded.substring(1, recorded.indexOf(';'));
		if (SAME_METHOD.containsKey(owner)) return true;
		if (!retypedFieldEnabled()) return false;
		for (WidenedFieldTwinInjector.Narrowed field : WidenedFieldTwinInjector.narrowed()) {
			if (field.owner().equals(targetName) && field.vanillaType().equals(owner)) return true;
		}
		return false;
	}

	/** The move for one point, or null: the subtype pair first, then a field the merge widened. */
	private static Move decide(MethodNode handler, AnnotationNode injector, AnnotationNode at, String recorded,
			ClassNode target, MethodNode host, Function<String, ClassNode> classes) {
		String owner = recorded.substring(1, recorded.indexOf(';'));
		String member = recorded.substring(recorded.indexOf(';') + 1);
		int paren = member.indexOf('(');
		if (paren < 0) return null;
		String name = member.substring(0, paren), desc = member.substring(paren);
		String subtype = SAME_METHOD.get(owner);
		if (subtype != null) {
			List<MethodInsnNode> through = new ArrayList<>();
			int direct = 0;
			for (AbstractInsnNode insn : host.instructions) {
				if (!(insn instanceof MethodInsnNode call) || !call.name.equals(name) || !call.desc.equals(desc)) continue;
				if (call.owner.equals(owner)) direct++;
				else if (call.owner.equals(subtype)) through.add(call);
			}
			if (direct != 0 || through.size() != 1 || !localsCover(handler, host, through.getFirst())) return null;
			return new Move("L" + subtype + ";" + member, null, null, simple(subtype) + "." + name);
		}
		return retypedField(handler, injector, at, owner, name, desc, target, host, classes);
	}

	/** The move along a NARROW row of {@link WidenedFieldTwinInjector}, or null; see the class comment. */
	private static Move retypedField(MethodNode handler, AnnotationNode injector, AnnotationNode at, String owner, String name,
			String desc, ClassNode target, MethodNode host, Function<String, ClassNode> classes) {
		if (!retypedFieldEnabled()) return null;
		// The guard covers the whole handler, so the handler may have this one point and nothing else to be guarded on.
		if (MixinFit.atNodes(injector).size() != 1 || MixinFit.value(injector, "slice") != null) return null;
		if ((handler.access & Opcodes.ACC_STATIC) != 0 || !Type.VOID_TYPE.equals(Type.getReturnType(handler.desc))) return null;
		if (annotated(handler.visibleParameterAnnotations) || annotated(handler.invisibleParameterAnnotations)) return null;
		String shift = MixinFit.asString(MixinFit.value(at, "shift"));
		if (shift != null && !"BEFORE".equals(shift) && !"AFTER".equals(shift)) return null;
		if (MixinFit.value(at, "args") != null || MixinFit.value(at, "by") != null) return null;
		if ((host.access & Opcodes.ACC_STATIC) != 0) return null;
		for (WidenedFieldTwinInjector.Narrowed field : WidenedFieldTwinInjector.narrowed()) {
			if (!field.owner().equals(target.name) || !field.vanillaType().equals(owner)) continue;
			FieldNode declared = declared(target, field.name(), "L" + field.mergedType() + ";");
			if (declared == null || (declared.access & Opcodes.ACC_FINAL) == 0 || (declared.access & Opcodes.ACC_STATIC) != 0) continue;
			if (!sameMethod(owner, field.mergedType(), name, desc, classes)) continue;
			List<MethodInsnNode> calls = new ArrayList<>();
			for (AbstractInsnNode insn : host.instructions) {
				if (!(insn instanceof MethodInsnNode call) || !call.name.equals(name) || !call.desc.equals(desc)) continue;
				if (call.owner.equals(owner)) return null;    // vanilla's call is still here: Mixin binds it natively
				if (call.owner.equals(field.mergedType())) calls.add(call);
			}
			if (calls.isEmpty()) continue;
			Frame<SourceValue>[] frames = frames(target.name, host);
			if (frames == null) return null;
			List<Integer> throughField = new ArrayList<>();
			for (int i = 0; i < calls.size(); i++) {
				MethodInsnNode call = calls.get(i);
				Frame<SourceValue> frame = frames[host.instructions.indexOf(call)];
				if (frame == null) continue;    // unreachable: Mixin would match it, but it never runs
				SourceValue receiver = frame.getStack(frame.getStackSize() - Type.getArgumentTypes(desc).length - 1);
				Boolean fromField = fromField(host, frames, receiver, target.name, field, new HashMap<>());
				if (fromField == null) return null;    // the receiver may be the field or not: which was meant is unknowable
				if (fromField) throughField.add(i);
			}
			if (throughField.isEmpty()) continue;
			Object ordinal = MixinFit.value(at, "ordinal");
			int index;
			if (ordinal == null || Integer.valueOf(-1).equals(ordinal)) {
				if (throughField.size() != 1) return null;    // one point would have to become several
				index = throughField.getFirst();
			} else if (ordinal instanceof Integer n && n >= 0 && n < throughField.size()) {
				index = throughField.get(n);
			} else {
				return null;
			}
			return new Move("L" + field.mergedType() + ";" + name + desc, index, field, simple(field.mergedType()) + "." + name
					+ " (ordinal " + index + ", through " + field.name() + ", only while it holds a " + simple(owner) + ")");
		}
		return null;
	}

	/**
	 * Whether {@code name desc} resolves to the same declaration from {@code narrow} as from {@code wide}: {@code wide} is
	 * a superclass of {@code narrow}, nothing from {@code narrow} up to it redeclares the method, and {@code wide} or a
	 * superclass of it declares it.
	 */
	static boolean sameMethod(String narrow, String wide, String name, String desc, Function<String, ClassNode> classes) {
		String cursor = narrow;
		for (int depth = 0; !cursor.equals(wide); depth++) {
			ClassNode node = depth > 32 ? null : classes.apply(cursor);
			if (node == null || node.superName == null) return false;
			if (declaresVirtual(node, name, desc)) return false;
			cursor = node.superName;
		}
		for (int depth = 0; cursor != null && depth <= 32; depth++) {
			ClassNode node = classes.apply(cursor);
			if (node == null) return false;
			if (declaresVirtual(node, name, desc)) return true;
			cursor = node.superName;
		}
		return false;
	}

	private static boolean declaresVirtual(ClassNode node, String name, String desc) {
		if (node.methods == null) return false;
		for (MethodNode m : node.methods) {
			if (m.name.equals(name) && m.desc.equals(desc) && (m.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) == 0) return true;
		}
		return false;
	}

	/**
	 * Where a value comes from: TRUE when every producer is a {@code getfield} of {@code field} on {@code this} (through
	 * locals, casts and dups), FALSE when none is, null when it may be either. {@code memo} holds each producer already
	 * judged, and null for one being judged: a loop back to it adds nothing either way.
	 */
	private static Boolean fromField(MethodNode host, Frame<SourceValue>[] frames, SourceValue value, String owner,
			WidenedFieldTwinInjector.Narrowed field, Map<AbstractInsnNode, Boolean> memo) {
		if (value == null || value.insns.isEmpty()) return Boolean.FALSE;    // a parameter or the method's own receiver
		Boolean all = null;
		for (AbstractInsnNode producer : value.insns) {
			Boolean one;
			if (memo.containsKey(producer)) {
				one = memo.get(producer);
				if (one == null) continue;
			} else {
				memo.put(producer, null);
				one = producer(host, frames, producer, owner, field, memo);
				memo.put(producer, one);
				if (one == null) return null;
			}
			if (all == null) all = one;
			else if (!all.equals(one)) return null;
		}
		return all == null ? Boolean.FALSE : all;
	}

	private static Boolean producer(MethodNode host, Frame<SourceValue>[] frames, AbstractInsnNode producer, String owner,
			WidenedFieldTwinInjector.Narrowed field, Map<AbstractInsnNode, Boolean> memo) {
		if (producer instanceof FieldInsnNode get && get.getOpcode() == Opcodes.GETFIELD) {
			return get.owner.equals(owner) && get.name.equals(field.name()) && get.desc.equals("L" + field.mergedType() + ";")
					&& onThis(host, frames, get);
		}
		Frame<SourceValue> frame = frames[host.instructions.indexOf(producer)];
		if (frame == null) return Boolean.FALSE;
		if (producer.getOpcode() == Opcodes.CHECKCAST || producer.getOpcode() == Opcodes.DUP || producer.getOpcode() == Opcodes.ASTORE) {
			return fromField(host, frames, frame.getStack(frame.getStackSize() - 1), owner, field, memo);
		}
		if (producer instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD) {
			return fromField(host, frames, frame.getLocal(load.var), owner, field, memo);
		}
		return Boolean.FALSE;
	}

	/** The {@code getfield}'s object is the method's own receiver: slot 0, never stored to. */
	private static boolean onThis(MethodNode host, Frame<SourceValue>[] frames, FieldInsnNode get) {
		Frame<SourceValue> frame = frames[host.instructions.indexOf(get)];
		if (frame == null) return false;
		SourceValue object = frame.getStack(frame.getStackSize() - 1);
		if (object.insns.isEmpty()) return false;
		for (AbstractInsnNode producer : object.insns) {
			if (!(producer instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD || load.var != 0) return false;
		}
		for (AbstractInsnNode insn : host.instructions) {
			if (insn instanceof VarInsnNode store && store.getOpcode() == Opcodes.ASTORE && store.var == 0) return false;
		}
		return true;
	}

	@SuppressWarnings("unchecked")
	private static Frame<SourceValue>[] frames(String owner, MethodNode host) {
		try {
			return new Analyzer<>(new SourceInterpreter()).analyze(owner, host);
		} catch (AnalyzerException | RuntimeException unanalysable) {
			return null;
		}
	}

	/**
	 * The handler renamed aside, and a method with its name, descriptor and injector annotation that calls it only while
	 * {@code field} holds the vanilla type — the only receivers the point could ever have had in vanilla.
	 */
	private static MethodNode guard(ClassNode mixin, MethodNode handler, AnnotationNode injector,
			WidenedFieldTwinInjector.Narrowed field) {
		MethodNode outer = new MethodNode(Opcodes.ASM9, handler.access, handler.name, handler.desc, handler.signature,
				handler.exceptions == null ? null : handler.exceptions.toArray(new String[0]));
		boolean visible = handler.visibleAnnotations != null && handler.visibleAnnotations.remove(injector);
		if (!visible && handler.invisibleAnnotations != null) handler.invisibleAnnotations.remove(injector);
		if (visible) outer.visibleAnnotations = new ArrayList<>(List.of(injector));
		else outer.invisibleAnnotations = new ArrayList<>(List.of(injector));

		String inner = MixinHandlerShim.asideName(mixin.name, handler.name, NARROW_SUFFIX);
		LabelNode skip = new LabelNode();
		// The mixin's own field reference: Mixin points it at the target class, which declares the widened field.
		outer.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		outer.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, mixin.name, field.name(), "L" + field.mergedType() + ";"));
		outer.instructions.add(new TypeInsnNode(Opcodes.INSTANCEOF, field.vanillaType()));
		outer.instructions.add(new JumpInsnNode(Opcodes.IFEQ, skip));
		outer.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		int slot = 1;
		for (Type arg : Type.getArgumentTypes(handler.desc)) {
			outer.instructions.add(new VarInsnNode(arg.getOpcode(Opcodes.ILOAD), slot));
			slot += arg.getSize();
		}
		outer.instructions.add(MixinHandlerShim.callOwn(mixin, false, inner, handler.desc));
		outer.instructions.add(skip);
		outer.instructions.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
		outer.instructions.add(new InsnNode(Opcodes.RETURN));
		outer.maxLocals = slot;
		outer.maxStack = Math.max(slot, 2);
		handler.name = inner;
		return outer;
	}

	/** The one method a name-only (or name+descriptor) selector picks in the target, as Mixin would: the first declared. */
	private static MethodNode select(ClassNode target, String selector) {
		int paren = selector.indexOf('(');
		String name = paren < 0 ? selector : selector.substring(0, paren), desc = paren < 0 ? null : selector.substring(paren);
		for (MethodNode method : target.methods) {
			if (method.name.equals(name) && (desc == null || method.desc.equals(desc))) return method;
		}
		return null;
	}

	private static FieldNode declared(ClassNode target, String name, String desc) {
		if (target.fields == null) return null;
		for (FieldNode f : target.fields) if (f.name.equals(name) && f.desc.equals(desc)) return f;
		return null;
	}

	private static boolean annotated(List<AnnotationNode>[] parameters) {
		if (parameters == null) return false;
		for (List<AnnotationNode> list : parameters) if (list != null && !list.isEmpty()) return true;
		return false;
	}

	private static String simple(String internal) {
		return internal.substring(internal.lastIndexOf('/') + 1);
	}

	/** Every {@code @Local} the handler takes is by name and names a local of that type that is live at the call. */
	static boolean localsCover(MethodNode handler, MethodNode host, MethodInsnNode call) {
		Type[] params = Type.getArgumentTypes(handler.desc);
		List<AnnotationNode>[][] sets = handlerParameterAnnotations(handler);
		int at = host.instructions.indexOf(call);
		for (int i = 0; i < params.length; i++) {
			for (List<AnnotationNode>[] set : sets) {
				if (set == null || i >= set.length || set[i] == null) continue;
				for (AnnotationNode annotation : set[i]) {
					if (!annotation.desc.equals(LOCAL)) continue;
					Object named = MixinFit.value(annotation, "name");
					List<String> names = named == null ? List.of() : MixinFit.stringList(named);
					if (names.size() != 1 || MixinFit.value(annotation, "ordinal") != null) return false;
					if (!covers(host, names.getFirst(), params[i].getDescriptor(), at)) return false;
				}
			}
		}
		return true;
	}

	@SuppressWarnings("unchecked")
	private static List<AnnotationNode>[][] handlerParameterAnnotations(MethodNode handler) {
		return new List[][] {handler.visibleParameterAnnotations, handler.invisibleParameterAnnotations};
	}

	private static boolean covers(MethodNode host, String name, String desc, int at) {
		if (host.localVariables == null) return false;
		for (LocalVariableNode local : host.localVariables) {
			if (!local.name.equals(name) || !local.desc.equals(desc)) continue;
			if (index(host, local.start) <= at && at < index(host, local.end)) return true;
		}
		return false;
	}

	private static int index(MethodNode host, LabelNode label) {
		return host.instructions.indexOf(label);
	}

	/** Sets {@code key} on an {@code @At}, adding it when the mod left it at its default. */
	private static void setValue(AnnotationNode at, String key, Object value) {
		if (at.values == null) at.values = new ArrayList<>();
		for (int i = 0; i < at.values.size(); i += 2) {
			if (key.equals(at.values.get(i))) {
				at.values.set(i + 1, value);
				return;
			}
		}
		at.values.add(key);
		at.values.add(value);
	}
}
