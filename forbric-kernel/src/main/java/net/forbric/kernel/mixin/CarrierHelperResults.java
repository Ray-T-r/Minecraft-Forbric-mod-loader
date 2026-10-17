/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.ForbricLog;

/**
 * An {@code @Inject} at a vanilla call the merged method no longer makes, moved onto the answer of the helper the carrier now
 * makes that call in, where the helper's answer says whether it made the call.
 *
 * <p>Vanilla's {@code ServerPlayerGameMode.destroyBlock} calls {@code Block.destroy} once, only when the block was removed;
 * NeoForge hands the removal to a private helper of its own ({@code removeBlock(pos, state, canHarvest, tool)}), called on two
 * exclusive paths (creative and survival), which calls {@code Block.destroy} exactly when it returns true. A mod's
 * {@code @Inject} at the vanilla call binds nothing in the merged method; the locals it reads with MixinExtras {@code @Local}
 * exist in the method, not in the helper; and inlining the helper at both of its calls ({@link NativeCallInlining}) would put
 * two calls where vanilla has one.
 *
 * <p>Nothing here knows a mod, a class or a method by name. The injector moves when, read off the native method (the class
 * the mod's own loader has) and the merged one:
 * <ul>
 *   <li>it is an {@code @Inject} with one {@code INVOKE} point (at the call, or shifted before or after it, by no count), no
 *       slice, no {@code @Group}, not cancellable and capturing no locals the old way; its handler takes the method's arguments
 *       (or none), its callback, and after that only MixinExtras {@code @Local}s;</li>
 *   <li>its selectors bind the same method natively and merged; the native method makes the call once (or the occurrence its
 *       ordinal names), on no loop, and the merged method never makes it;</li>
 *   <li>the merged method calls exactly one method of its own class the native method never calls — private, static or final
 *       (or of a final class), answering a boolean — whose body makes the call exactly once, and no execution of the method
 *       passes two of those calls (none is reachable from another, or from itself);</li>
 *   <li>the helper answers true on exactly the paths through it that make the call — the proof follows every path from its
 *       entry to a return, a test of a value already decided on the path taken only the way it was decided, and asks that each
 *       return hand back a constant or a value the path tested, true where the call was made and false where it was not; a
 *       helper with a loop, a handler or more paths than the proof follows is not read;</li>
 *   <li>each {@code @Local} names, at the native call, a local holding a value the merged method also holds, at every call
 *       of the helper, in exactly one local of the same type: the same expression over the method's own values, its producer
 *       ({@link NativeCallChanges#localsAt}).</li>
 * </ul>
 * The handler keeps its body under its name with {@code $forbricOriginal} appended, without the annotation; a new handler of
 * that name, a MixinExtras {@code @ModifyExpressionValue} on the helper's answer bound to the same methods, hands it — when the
 * answer is true — the method's arguments, a fresh callback that is not cancellable (as the injector's was not), and each
 * local from the merged slot that holds it, and returns the answer unchanged.
 *
 * <p>What differs from the native order is the merged method's, not this move's: the handler runs after the helper returned,
 * so after the call it was written to run before (a block that clears partner blocks in {@code destroy} has done so) and after
 * anything the helper does after the call. {@code -Dforbric.carrierHelperResults=off} leaves every injector as compiled.
 */
public final class CarrierHelperResults {
	public static final String PROPERTY = "forbric.carrierHelperResults";
	static final String MODIFY_EXPRESSION_VALUE = CarrierDefaultChains.MODIFY_EXPRESSION_VALUE;
	static final String CALLBACK = "org/spongepowered/asm/mixin/injection/callback/CallbackInfo";
	static final String RETURNABLE = "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable";
	private static final String SHIFT = "Lorg/spongepowered/asm/mixin/injection/At$Shift;";
	/** The most paths through a helper the proof follows. */
	private static final int PATHS = 256;

	private CarrierHelperResults() {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/** Moves every eligible injector of {@code mixin}; returns how many. {@code code} must return nodes WITH code. */
	public static int adapt(ClassNode mixin, Function<String, ClassNode> code, BiFunction<Ecosystem, String, ClassNode> natives) {
		if (!enabled() || mixin == null || mixin.methods == null || code == null || natives == null) return 0;
		Ecosystem ecosystem = MixinStubRebind.ecosystemOf(mixin.name);
		if (ecosystem == null) return 0;
		int moved = 0;
		for (String target : MixinFit.mixinTargets(mixin)) {
			ClassNode original = natives.apply(ecosystem, target), merged = null;
			if (original == null) continue;
			for (MethodNode handler : List.copyOf(mixin.methods)) {
				AnnotationNode inject = MixinFit.injectorOf(handler);
				if (inject == null || !CarrierDecisions.INJECT.equals(inject.desc) || annotated(handler, CarrierDecisions.GROUP)
						|| MixinFit.value(inject, "slice") != null || Boolean.TRUE.equals(MixinFit.value(inject, "cancellable"))
						|| MixinFit.value(inject, "locals") != null) continue;
				List<AnnotationNode> points = MixinFit.atNodes(inject);
				if (points.size() != 1) continue;
				AnnotationNode at = points.getFirst();
				if (!"INVOKE".equals(MixinFit.asString(MixinFit.value(at, "value"))) || MixinFit.value(at, "by") != null || !aroundTheCall(at)) continue;
				if (merged == null) merged = code.apply(target);
				if (merged == null) break;
				MethodNode was = MixinTargetSelectors.one(handler, original), selected = MixinTargetSelectors.one(handler, merged);
				if (was == null || selected == null || !was.name.equals(selected.name) || !was.desc.equals(selected.desc)) continue;
				MethodNode wrapper = move(mixin, handler, inject, at, original.name, was, merged, selected, code);
				if (wrapper == null) continue;
				moved++;
			}
		}
		return moved;
	}

	private static MethodNode move(ClassNode mixin, MethodNode handler, AnnotationNode inject, AnnotationNode at, String owner, MethodNode was,
			ClassNode merged, MethodNode selected, Function<String, ClassNode> code) {
		// The native call: once (or the ordinal's occurrence), on no loop; the merged method makes none.
		if (!MixinCallbackShape.calls(at, selected).isEmpty()) return null;
		List<MethodInsnNode> nativeCalls = MixinCallbackShape.calls(at, was);
		int ordinal = MixinFit.value(at, "ordinal") instanceof Number number ? number.intValue() : -1;
		MethodInsnNode call = ordinal >= 0 ? (ordinal < nativeCalls.size() ? nativeCalls.get(ordinal) : null)
				: nativeCalls.size() == 1 ? nativeCalls.getFirst() : null;
		if (call == null || reachable(was, successors(was, call), Set.of(call))) return null;

		// The handler: the method's arguments or none, its callback, then only @Locals.
		Type[] parameters = Type.getArgumentTypes(handler.desc), arguments = Type.getArgumentTypes(was.desc);
		int callback = -1;
		for (int i = 0; i < parameters.length; i++) if (parameters[i].getSort() == Type.OBJECT
				&& (parameters[i].getInternalName().equals(CALLBACK) || parameters[i].getInternalName().equals(RETURNABLE))) {
			callback = i;
			break;
		}
		if (callback != 0 && callback != arguments.length) return null;
		for (int i = 0; i < callback; i++) if (!parameters[i].equals(arguments[i])) return null;
		boolean returnable = parameters[callback].getInternalName().equals(RETURNABLE);
		if (returnable == Type.getReturnType(was.desc).equals(Type.VOID_TYPE) || !Type.getReturnType(handler.desc).equals(Type.VOID_TYPE)) return null;
		for (int i = 0; i < callback + 1; i++) if (MixinFit.sugar(handler, i)) return null;
		List<Integer> locals = new ArrayList<>();
		for (int i = callback + 1; i < parameters.length; i++) {
			AnnotationNode local = MixinStubRebind.sugar(handler, i, CarrierDecisions.LOCAL);
			if (local == null || sugarCount(handler, i) != 1 || parameters[i].getSort() == Type.OBJECT
					&& parameters[i].getInternalName().startsWith("com/llamalad7/mixinextras/sugar/ref/")) return null;
			locals.add(i);
		}

		// The helper: one method of the class itself the native method never calls, answering whether it made the call.
		MethodNode helper = null;
		List<MethodInsnNode> sites = new ArrayList<>();
		Set<String> nativeMembers = new HashSet<>();
		for (AbstractInsnNode instruction : was.instructions) if (instruction instanceof MethodInsnNode made) nativeMembers.add(member(made));
		for (AbstractInsnNode instruction : selected.instructions) {
			if (!(instruction instanceof MethodInsnNode made) || !made.owner.equals(merged.name) || nativeMembers.contains(member(made))
					|| !Type.getReturnType(made.desc).equals(Type.BOOLEAN_TYPE)) continue;
			MethodNode body = declared(merged, made.name, made.desc);
			if (body == null || !fixed(merged, body, made) || makes(body, call) != 1) continue;
			if (helper != null && helper != body) return null;
			helper = body;
			sites.add(made);
		}
		if (helper == null || sites.isEmpty()) return null;
		for (MethodInsnNode site : sites) if (reachable(selected, successors(selected, site), new HashSet<>(sites))) return null;
		if (!answersWhetherItCalled(merged.name, helper, call)) return null;

		// Each @Local: the value its native local holds, held by one merged local of the same type at every call of the helper.
		Map<Integer, Integer> slots = new HashMap<>();
		Map<Integer, NativeCallChanges.Expr> nativeValues = NativeCallChanges.localsAt(owner, was, call);
		if (nativeValues == null && !locals.isEmpty()) return null;
		for (int parameter : locals) {
			AnnotationNode local = MixinStubRebind.sugar(handler, parameter, CarrierDecisions.LOCAL);
			int nativeSlot = MixinLocalOriginProof.slot(local, parameters[parameter], was, was.instructions.indexOf(call));
			NativeCallChanges.Expr value = nativeSlot < 0 ? null : nativeValues.get(nativeSlot);
			if (value == null) return null;
			Integer found = null;
			for (MethodInsnNode site : sites) {
				Map<Integer, NativeCallChanges.Expr> held = NativeCallChanges.localsAt(merged.name, selected, site);
				if (held == null) return null;
				List<Integer> same = new ArrayList<>();
				for (int slot : MixinLocalOriginProof.typedSlots(selected, selected.instructions.indexOf(site), parameters[parameter], false))
					if (value.equals(held.get(slot))) same.add(slot);
				if (same.size() != 1 || found != null && !found.equals(same.getFirst())) return null;
				found = same.getFirst();
			}
			slots.put(parameter, found);
		}

		String name = handler.name, renamed = name + CarrierDecisions.SUFFIX;
		for (MethodNode other : mixin.methods) if (other.name.equals(renamed)) return null;
		boolean isStatic = (handler.access & Opcodes.ACC_STATIC) != 0;
		MethodNode wrapper = wrapper(mixin, handler, inject, merged.name, helper, selected, callback, returnable, locals, slots, isStatic, renamed, parameters);
		if (wrapper == null || !CarrierDefaultChains.framed(mixin, wrapper, code)) return null;

		handler.name = renamed;
		if (handler.visibleAnnotations != null) handler.visibleAnnotations.remove(inject);
		if (handler.invisibleAnnotations != null) handler.invisibleAnnotations.remove(inject);
		handler.visibleParameterAnnotations = null;
		handler.invisibleParameterAnnotations = null;
		handler.visibleAnnotableParameterCount = 0;
		handler.invisibleAnnotableParameterCount = 0;
		mixin.methods.add(wrapper);
		CurrentBodyOrdinals.mark(wrapper);
		ForbricLog.info("[Forbric/Mixin] %s.%s: %s is no longer called in %s.%s; the carrier makes it in %s, which answers true exactly when it"
				+ " made it — the injector runs on that answer, at its %d call(s), with %d local(s) read where the merged method keeps them",
				mixin.name.replace('/', '.'), name, call.owner.replace('/', '.') + "." + call.name, merged.name.replace('/', '.'), selected.name,
				merged.name.replace('/', '.') + "." + helper.name, sites.size(), locals.size());
		return wrapper;
	}

	/** The new handler: a {@code @ModifyExpressionValue} on the helper's answer that hands the original what it was written for. */
	private static MethodNode wrapper(ClassNode mixin, MethodNode handler, AnnotationNode inject, String target, MethodNode helper, MethodNode selected,
			int callback, boolean returnable, List<Integer> locals, Map<Integer, Integer> slots, boolean isStatic, String renamed, Type[] parameters) {
		List<Type> types = new ArrayList<>(List.of(Type.BOOLEAN_TYPE));
		List<AnnotationNode> sugar = new ArrayList<>(Collections.singletonList(null));
		int argumentSlot = (selected.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
		for (int i = 0; i < callback; i++) {
			types.add(parameters[i]);
			sugar.add(local(true, argumentSlot));
			argumentSlot += parameters[i].getSize();
		}
		for (int parameter : locals) {
			types.add(parameters[parameter]);
			sugar.add(local(false, slots.get(parameter)));
		}
		MethodNode wrapper = new MethodNode(Opcodes.ACC_PRIVATE | (isStatic ? Opcodes.ACC_STATIC : 0), handler.name,
				Type.getMethodDescriptor(Type.BOOLEAN_TYPE, types.toArray(Type[]::new)), null, null);
		@SuppressWarnings("unchecked")
		List<AnnotationNode>[] table = new List[types.size()];
		for (int i = 0; i < table.length; i++) table[i] = sugar.get(i) == null ? null : new ArrayList<>(List.of(sugar.get(i)));
		wrapper.invisibleParameterAnnotations = table;
		wrapper.invisibleAnnotableParameterCount = table.length;
		AnnotationNode point = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		point.values = new ArrayList<>(List.of("value", "INVOKE", "target", "L" + target + ";" + helper.name + helper.desc));
		AnnotationNode modify = new AnnotationNode(MODIFY_EXPRESSION_VALUE);
		modify.values = new ArrayList<>(List.of("method", MixinFit.value(inject, "method"), "at", new ArrayList<>(List.of(point))));
		for (String key : List.of("require", "expect", "allow")) if (MixinFit.value(inject, key) != null) {
			modify.values.add(key);
			modify.values.add(MixinFit.value(inject, key));
		}
		boolean visible = handler.visibleAnnotations != null && handler.visibleAnnotations.contains(inject);
		if (visible) wrapper.visibleAnnotations = new ArrayList<>(List.of(modify));
		else wrapper.invisibleAnnotations = new ArrayList<>(List.of(modify));

		int[] at = new int[types.size()];
		int next = isStatic ? 0 : 1;
		for (int i = 0; i < at.length; i++) {
			at[i] = next;
			next += types.get(i).getSize();
		}
		InsnList out = wrapper.instructions;
		LabelNode kept = new LabelNode();
		out.add(new VarInsnNode(Opcodes.ILOAD, at[0]));
		out.add(new JumpInsnNode(Opcodes.IFEQ, kept));       // the helper did not make the call: neither did vanilla reach it
		if (!isStatic) out.add(new VarInsnNode(Opcodes.ALOAD, 0));
		for (int i = 0; i < callback; i++) out.add(new VarInsnNode(types.get(1 + i).getOpcode(Opcodes.ILOAD), at[1 + i]));
		String callbackType = returnable ? RETURNABLE : CALLBACK;
		out.add(new TypeInsnNode(Opcodes.NEW, callbackType));
		out.add(new InsnNode(Opcodes.DUP));
		out.add(new LdcInsnNode(selected.name));
		out.add(new InsnNode(Opcodes.ICONST_0));
		out.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, callbackType, "<init>", "(Ljava/lang/String;Z)V", false));
		for (int i = 0; i < locals.size(); i++) out.add(new VarInsnNode(types.get(1 + callback + i).getOpcode(Opcodes.ILOAD), at[1 + callback + i]));
		out.add(MixinHandlerShim.callOwn(mixin, isStatic, renamed, handler.desc));
		out.add(kept);
		out.add(new VarInsnNode(Opcodes.ILOAD, at[0]));
		out.add(new InsnNode(Opcodes.IRETURN));
		wrapper.maxLocals = next;
		wrapper.maxStack = parameters.length + 4;
		try {
			new Analyzer<>(new BasicVerifier()).analyze(mixin.name, wrapper);
		} catch (AnalyzerException | RuntimeException unverifiable) {
			Caught.expected(unverifiable);
			return null;
		}
		return wrapper;
	}

	private static AnnotationNode local(boolean argument, int index) {
		AnnotationNode local = new AnnotationNode(CarrierDecisions.LOCAL);
		local.values = new ArrayList<>();
		if (argument) {
			local.values.add("argsOnly");
			local.values.add(Boolean.TRUE);
		}
		local.values.add("index");
		local.values.add(index);
		return local;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// The helper's answer

	/**
	 * Whether {@code helper}'s boolean answer is true on exactly the paths through it that make {@code call}'s member: every
	 * path from its entry to a return is followed (each test of a value already decided on it, only the way it was decided),
	 * and each return hands back a constant or a value the path tested — true where the call was made, false where it was not.
	 */
	static boolean answersWhetherItCalled(String owner, MethodNode helper, MethodInsnNode call) {
		if (!Type.getReturnType(helper.desc).equals(Type.BOOLEAN_TYPE) || !CarrierDefaultChains.simple(helper)) return false;
		Frame<SourceValue>[] frames;
		try {
			frames = new Analyzer<>(new Identity()).analyze(owner, helper);
		} catch (AnalyzerException | RuntimeException unreadable) {
			Caught.expected(unreadable);
			return false;
		}
		int[] budget = {PATHS};
		return paths(helper.instructions, frames, call, first(helper.instructions), new HashMap<>(), false, budget) && budget[0] >= 0;
	}

	/** Follows every path from {@code at}; false at the first path whose answer does not say whether it made the call. */
	private static boolean paths(InsnList instructions, Frame<SourceValue>[] frames, MethodInsnNode call, AbstractInsnNode at,
			Map<List<Integer>, Boolean> decided, boolean called, int[] budget) {
		for (AbstractInsnNode instruction = at; instruction != null; instruction = instruction.getNext()) {
			int opcode = instruction.getOpcode();
			if (opcode < 0) continue;
			int index = instructions.indexOf(instruction);
			Frame<SourceValue> frame = frames[index];
			if (frame == null) return true;   // unreachable code
			if (instruction instanceof MethodInsnNode made && member(made).equals(member(call)) && made.getOpcode() == call.getOpcode()) {
				if (called) return false;    // twice on one path
				called = true;
				continue;
			}
			if (opcode == Opcodes.IRETURN) {
				Boolean answer = known(instructions, frame.getStack(frame.getStackSize() - 1), decided);
				if (--budget[0] < 0) return false;
				return answer != null && answer == called;
			}
			if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) return false;
			if (opcode == Opcodes.ATHROW) return --budget[0] >= 0;   // no answer on this path
			if (instruction instanceof JumpInsnNode jump) {
				if (opcode == Opcodes.GOTO) {
					instruction = jump.label;
					continue;
				}
				if (opcode == Opcodes.IFEQ || opcode == Opcodes.IFNE) {
					SourceValue tested = frame.getStack(frame.getStackSize() - 1);
					Boolean value = known(instructions, tested, decided);
					List<Integer> key = key(instructions, tested);
					// IFEQ jumps when the value is false (zero), IFNE when it is true.
					boolean jumpsWhen = opcode == Opcodes.IFNE;
					if (value != null) {
						if (value == jumpsWhen) instruction = jump.label;
						continue;
					}
					Map<List<Integer>, Boolean> taken = new HashMap<>(decided), fallen = new HashMap<>(decided);
					taken.put(key, jumpsWhen);
					fallen.put(key, !jumpsWhen);
					return paths(instructions, frames, call, jump.label, taken, called, budget)
							&& paths(instructions, frames, call, jump.getNext(), fallen, called, budget);
				}
				// Any other test: both ways, nothing learned.
				return paths(instructions, frames, call, jump.label, decided, called, budget)
						&& paths(instructions, frames, call, jump.getNext(), decided, called, budget);
			}
			if (instruction instanceof TableSwitchInsnNode table) {
				List<LabelNode> targets = new ArrayList<>(table.labels);
				targets.add(table.dflt);
				for (LabelNode label : targets) if (!paths(instructions, frames, call, label, decided, called, budget)) return false;
				return true;
			}
			if (instruction instanceof LookupSwitchInsnNode lookup) {
				List<LabelNode> targets = new ArrayList<>(lookup.labels);
				targets.add(lookup.dflt);
				for (LabelNode label : targets) if (!paths(instructions, frames, call, label, decided, called, budget)) return false;
				return true;
			}
		}
		return false;
	}

	/** A boolean value's truth on this path: a constant, or a value the path already tested; null when unknown. */
	private static Boolean known(InsnList instructions, SourceValue value, Map<List<Integer>, Boolean> decided) {
		if (value.insns.size() == 1) {
			int opcode = value.insns.iterator().next().getOpcode();
			if (opcode == Opcodes.ICONST_0) return false;
			if (opcode >= Opcodes.ICONST_1 && opcode <= Opcodes.ICONST_5 || opcode == Opcodes.ICONST_M1) return true;
		}
		return decided.get(key(instructions, value));
	}

	/** A value's identity: the instructions that produce it, by position, whatever locals and copies carried it. */
	private static List<Integer> key(InsnList instructions, SourceValue value) {
		List<Integer> key = new ArrayList<>();
		for (AbstractInsnNode producer : value.insns) key.add(instructions.indexOf(producer));
		Collections.sort(key);
		return key;
	}

	/** Source values that a load, a store or a copy hands on unchanged, so a value keeps its producers through locals. */
	private static final class Identity extends SourceInterpreter {
		Identity() {
			super(Opcodes.ASM9);
		}

		@Override
		public SourceValue copyOperation(AbstractInsnNode instruction, SourceValue value) {
			return value;
		}
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Control flow

	/** The instructions control can pass to right after {@code from}. */
	private static List<AbstractInsnNode> successors(MethodNode method, AbstractInsnNode from) {
		List<AbstractInsnNode> next = new ArrayList<>();
		int opcode = from.getOpcode();
		if (from instanceof JumpInsnNode jump) {
			next.add(jump.label);
			if (opcode != Opcodes.GOTO) next.add(from.getNext());
		} else if (from instanceof TableSwitchInsnNode table) {
			next.add(table.dflt);
			next.addAll(table.labels);
		} else if (from instanceof LookupSwitchInsnNode lookup) {
			next.add(lookup.dflt);
			next.addAll(lookup.labels);
		} else if (!(opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN || opcode == Opcodes.ATHROW)) next.add(from.getNext());
		return next;
	}

	/** Whether control from any of {@code starts} reaches one of {@code targets}, exception handlers included. */
	private static boolean reachable(MethodNode method, List<AbstractInsnNode> starts, Set<? extends AbstractInsnNode> targets) {
		Set<AbstractInsnNode> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		List<AbstractInsnNode> pending = new ArrayList<>(starts);
		while (!pending.isEmpty()) {
			AbstractInsnNode at = pending.removeLast();
			if (at == null || !seen.add(at)) continue;
			if (targets.contains(at)) return true;
			if (method.tryCatchBlocks != null) for (TryCatchBlockNode block : method.tryCatchBlocks) {
				int index = method.instructions.indexOf(at);
				if (index >= method.instructions.indexOf(block.start) && index < method.instructions.indexOf(block.end)) pending.add(block.handler);
			}
			pending.addAll(successors(method, at));
		}
		return false;
	}

	// ---------------------------------------------------------------------------------------------------------------

	/** How many times {@code body} makes {@code call}'s member. */
	private static int makes(MethodNode body, MethodInsnNode call) {
		int made = 0;
		for (AbstractInsnNode instruction : body.instructions)
			if (instruction instanceof MethodInsnNode each && member(each).equals(member(call)) && each.getOpcode() == call.getOpcode()) made++;
		return made;
	}

	/** Whether {@code body}, called by {@code made}, is the code that runs: private, static or final, or of a final class. */
	private static boolean fixed(ClassNode owner, MethodNode body, MethodInsnNode made) {
		boolean isStatic = (body.access & Opcodes.ACC_STATIC) != 0;
		if (isStatic != (made.getOpcode() == Opcodes.INVOKESTATIC)) return false;
		return isStatic || (body.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL)) != 0 || (owner.access & Opcodes.ACC_FINAL) != 0;
	}

	/** Whether the point is at the call or shifted just before or after it (not by a count). */
	private static boolean aroundTheCall(AnnotationNode at) {
		Object shift = MixinFit.value(at, "shift");
		if (shift == null) return true;
		return shift instanceof String[] pair && pair.length == 2 && SHIFT.equals(pair[0])
				&& (pair[1].equals("NONE") || pair[1].equals("BEFORE") || pair[1].equals("AFTER"));
	}

	private static int sugarCount(MethodNode handler, int parameter) {
		int count = 0;
		for (List<AnnotationNode>[] table : Arrays.asList(handler.visibleParameterAnnotations, handler.invisibleParameterAnnotations)) {
			if (table == null || parameter >= table.length || table[parameter] == null) continue;
			for (AnnotationNode annotation : table[parameter]) if (annotation.desc.startsWith("Lcom/llamalad7/mixinextras/sugar/")) count++;
		}
		return count;
	}

	private static AbstractInsnNode first(InsnList instructions) {
		return instructions.getFirst();
	}

	private static MethodNode declared(ClassNode type, String name, String desc) {
		for (MethodNode method : type.methods) if (method.name.equals(name) && method.desc.equals(desc)) return method;
		return null;
	}

	private static String member(MethodInsnNode call) {
		return "L" + call.owner + ";" + call.name + call.desc;
	}

	private static boolean annotated(MethodNode method, String desc) {
		for (List<AnnotationNode> list : Arrays.asList(method.visibleAnnotations, method.invisibleAnnotations))
			if (list != null) for (AnnotationNode annotation : list) if (annotation.desc.equals(desc)) return true;
		return false;
	}
}
