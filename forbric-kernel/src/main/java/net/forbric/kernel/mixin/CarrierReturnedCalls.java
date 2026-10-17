/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Caught;

/**
 * A {@code @WrapOperation} of a vanilla call whose answer is the method's answer, moved to that answer where the carrier
 * computes it its own way.
 *
 * <p>Vanilla's {@code getBedOrientation()} returns {@code BedBlock.getBedOrientation(level, pos)} when the entity has a
 * sleeping position; NeoForge returns its own answer there (asking the block), and never makes the vanilla call. A mod's
 * wrap of that call — which takes the answer from its {@code Operation} and changes it — binds nowhere, although natively it
 * ran exactly when the method returned through that call.
 *
 * <p>This moves the wrap to the method's answer on exactly those ways: a {@code @ModifyReturnValue} at each return the
 * merged method reaches under the conditions the native call was made under (and on no way the native call was not made),
 * which hands the mod's handler, as written, the values the native call was handed and an {@code Operation} that answers
 * with the carrier's answer. It moves only where all of this is read off the classes, never off a name:
 * <ul>
 *   <li>the wrap has one {@code INVOKE} point, no slice, no {@code @Group}; its handler takes exactly the call's values and
 *       the {@code Operation}, and uses the {@code Operation} only to call it with those very values, unchanged;</li>
 *   <li>the native method makes the call once (or the occurrence the ordinal names), and the call's answer is returned at
 *       once (a return right after it, or a jump straight to one); the method returns the call's type; the merged method
 *       never makes the call;</li>
 *   <li>every return of the merged method is reached either only under the native call's conditions (then it moves there)
 *       or never under them; at least one is reached under them;</li>
 *   <li>every value the native call is handed is there at those returns: a local of the merged method of the same debug name
 *       and type holding the same expression, or the same getter asked of the instance, which the merged method itself asks
 *       under those conditions.</li>
 * </ul>
 * The handler keeps its name with {@code $forbricOriginal} appended. {@code -Dforbric.carrierReturnedCalls=off} leaves every
 * wrap as compiled.
 */
public final class CarrierReturnedCalls {
	public static final String PROPERTY = "forbric.carrierReturnedCalls";
	static final String WRAP = "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";
	static final String MODIFY_RETURN = "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;";
	static final String OPERATION = "com/llamalad7/mixinextras/injector/wrapoperation/Operation";
	static final String CONSTANT_OWNER = "net/forbric/kernel/runtime/KernelWrapOperations";
	private static final String AT = "Lorg/spongepowered/asm/mixin/injection/At;";

	private CarrierReturnedCalls() {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/** One value the native call is handed, found at the merged returns: a merged local, or a getter of the instance. */
	private record Value(Type type, int local, MethodInsnNode getter) {
	}

	/** Moves every eligible wrap of {@code mixin}; returns how many. {@code code} must return nodes WITH code and tables. */
	public static int adapt(ClassNode mixin, Function<String, ClassNode> code, BiFunction<Ecosystem, String, ClassNode> natives) {
		if (!enabled() || mixin == null || mixin.methods == null) return 0;
		Ecosystem ecosystem = MixinStubRebind.ecosystemOf(mixin.name);
		if (ecosystem == null) return 0;
		int moved = 0;
		for (String target : MixinFit.mixinTargets(mixin)) {
			ClassNode original = natives.apply(ecosystem, target), merged = null;
			if (original == null) continue;
			for (MethodNode handler : List.copyOf(mixin.methods)) {
				AnnotationNode injector = MixinFit.injectorOf(handler);
				if (injector == null || !WRAP.equals(injector.desc) || annotated(handler, CarrierDecisions.GROUP) || MixinFit.value(injector, "slice") != null) continue;
				List<AnnotationNode> points = MixinFit.atNodes(injector);
				if (points.size() != 1 || !"INVOKE".equals(MixinFit.asString(MixinFit.value(points.getFirst(), "value")))) continue;
				AnnotationNode at = points.getFirst();
				if (merged == null) merged = code.apply(target);
				if (merged == null) break;
				MethodNode was = MixinTargetSelectors.one(handler, original), selected = MixinTargetSelectors.one(handler, merged);
				if (was == null || selected == null || !was.name.equals(selected.name) || !was.desc.equals(selected.desc)) continue;
				if (!MixinCallbackShape.calls(at, selected).isEmpty()) continue;
				List<MethodInsnNode> nativeCalls = MixinCallbackShape.calls(at, was);
				int ordinal = MixinFit.value(at, "ordinal") instanceof Number number ? number.intValue() : -1;
				MethodInsnNode call = ordinal >= 0 ? (ordinal < nativeCalls.size() ? nativeCalls.get(ordinal) : null)
						: nativeCalls.size() == 1 ? nativeCalls.getFirst() : null;
				if (call == null) continue;
				List<Integer> returns = moveTo(original, was, merged, selected, call);
				if (returns == null) continue;
				List<Value> values = values(original, was, merged, selected, call, returns);
				if (values == null || !handsItsValues(mixin, handler, call)) continue;
				MethodNode wrapper = relocate(mixin, handler, injector, selected, merged, call, returns, values);
				if (wrapper == null) continue;
				CurrentBodyOrdinals.mark(wrapper);
				moved++;
				ForbricLog.info("[Forbric/Mixin] %s.%s: %s is no longer called in %s, whose answer it was; the carrier answers its own way there — the wrap"
						+ " moves to %s of the method's answer, its Operation answering with the carrier's", mixin.name.replace('/', '.'), wrapper.name,
						call.owner.replace('/', '.') + "." + call.name, target.replace('/', '.') + "." + selected.name,
						returns.size() == 1 ? "return " + returns.getFirst() : "returns " + returns);
			}
		}
		return moved;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Where

	/**
	 * The ordinals (among the merged method's returns) of the returns the merged method reaches under the native call's
	 * conditions; null when the call's answer is not the native method's answer at once, or a merged return is reached both
	 * under those conditions and not, or none is reached under them.
	 */
	static List<Integer> moveTo(ClassNode original, MethodNode was, ClassNode merged, MethodNode selected, MethodInsnNode call) {
		Type answer = Type.getReturnType(call.desc);
		if (answer.getSort() == Type.VOID || !answer.equals(Type.getReturnType(selected.desc))) return null;
		AbstractInsnNode after = nextReal(call);
		if (after instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.GOTO) after = nextReal(jump.label);
		if (after == null || after.getOpcode() != answer.getOpcode(Opcodes.IRETURN)) return null;
		NativeCallInlining.Conditions nativeConditions = NativeCallInlining.Conditions.of(original.name, was, Integer.MAX_VALUE),
				mergedConditions = NativeCallInlining.Conditions.of(merged.name, selected, Integer.MAX_VALUE);
		if (nativeConditions == null || mergedConditions == null) return null;
		Set<String> wanted = nativeConditions.of(call);
		List<Integer> out = new ArrayList<>();
		int ordinal = 0;
		for (AbstractInsnNode instruction : selected.instructions) {
			int opcode = instruction.getOpcode();
			if (opcode < Opcodes.IRETURN || opcode > Opcodes.RETURN) continue;
			Set<String> found = mergedConditions.of(instruction);
			if (found.containsAll(wanted)) out.add(ordinal);
			else if (!contradicts(found, wanted)) return null;
			ordinal++;
		}
		return out.isEmpty() ? null : out;
	}

	/** Whether a return reached under {@code found} is never reached under {@code wanted}: one decides the other way. */
	private static boolean contradicts(Set<String> found, Set<String> wanted) {
		for (String condition : wanted) {
			// "<opcode><+|->(<operands>)": the sign right after the opcode's digits.
			int sign = 0;
			while (sign < condition.length() && Character.isDigit(condition.charAt(sign))) sign++;
			if (sign >= condition.length() || condition.charAt(sign) != '+' && condition.charAt(sign) != '-') continue;
			String opposite = condition.substring(0, sign) + (condition.charAt(sign) == '+' ? '-' : '+') + condition.substring(sign + 1);
			if (found.contains(opposite)) return true;
		}
		return false;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// What the native call was handed

	/** Each value the native call is handed, as found at every one of the merged returns; null when one is not there. */
	static List<Value> values(ClassNode original, MethodNode was, ClassNode merged, MethodNode selected, MethodInsnNode call, List<Integer> returns) {
		Frame<SourceValue>[] nativeFrames = frames(original.name, was), mergedFrames = frames(merged.name, selected);
		if (nativeFrames == null || mergedFrames == null) return null;
		NativeCallInlining.Conditions nativeConditions = NativeCallInlining.Conditions.of(original.name, was, Integer.MAX_VALUE),
				mergedConditions = NativeCallInlining.Conditions.of(merged.name, selected, Integer.MAX_VALUE);
		Frame<SourceValue> frame = nativeFrames[was.instructions.indexOf(call)];
		List<Type> operands = CarrierDecisions.operandTypes(call);
		if (frame == null || frame.getStackSize() < operands.size()) return null;
		List<String> nativeExpressions = nativeConditions.operands(call);
		List<AbstractInsnNode> mergedReturns = returnsAt(selected, returns);
		boolean instance = (selected.access & Opcodes.ACC_STATIC) == 0;
		List<Value> out = new ArrayList<>();
		for (int k = 0; k < operands.size(); k++) {
			SourceValue value = frame.getStack(frame.getStackSize() - operands.size() + k);
			if (value.insns.size() != 1) return null;
			AbstractInsnNode producer = value.insns.iterator().next();
			if (producer instanceof VarInsnNode load && load.getOpcode() >= Opcodes.ILOAD && load.getOpcode() <= Opcodes.ALOAD && !(instance && load.var == 0)) {
				LocalVariableNode named = local(was, load.var, call);
				if (named == null) return null;
				int slot = -1;
				for (AbstractInsnNode ret : mergedReturns) {
					LocalVariableNode same = sameLocal(selected, named, ret);
					if (same == null || slot >= 0 && slot != same.index) return null;
					if (!stored(mergedConditions, mergedFrames, selected, same.index, ret).equals(nativeExpressions.get(k))) return null;
					slot = same.index;
				}
				out.add(new Value(operands.get(k), slot, null));
			} else if (producer instanceof MethodInsnNode getter && instance && Type.getArgumentTypes(getter.desc).length == 0 && getter.getOpcode() != Opcodes.INVOKESTATIC
					&& thisReceiver(nativeFrames, was, getter)) {
				if (!asksToo(mergedConditions, mergedFrames, selected, getter, nativeConditions.of(call))) return null;
				out.add(new Value(operands.get(k), -1, getter));
			} else return null;
		}
		return out;
	}

	/** Whether the merged method asks {@code getter} of its own instance under (at least) {@code wanted}. */
	private static boolean asksToo(NativeCallInlining.Conditions conditions, Frame<SourceValue>[] frames, MethodNode selected, MethodInsnNode getter, Set<String> wanted) {
		for (AbstractInsnNode instruction : selected.instructions)
			if (instruction instanceof MethodInsnNode asked && asked.name.equals(getter.name) && asked.desc.equals(getter.desc) && asked.getOpcode() == getter.getOpcode()
					&& thisReceiver(frames, selected, asked) && conditions.of(asked).containsAll(wanted)) return true;
		return false;
	}

	private static boolean thisReceiver(Frame<SourceValue>[] frames, MethodNode method, MethodInsnNode call) {
		Frame<SourceValue> frame = frames[method.instructions.indexOf(call)];
		if (frame == null || frame.getStackSize() < 1) return false;
		SourceValue receiver = frame.getStack(frame.getStackSize() - 1);
		if (receiver.insns.size() != 1 || !(receiver.insns.iterator().next() instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD || load.var != 0) return false;
		return frame.getLocal(0).insns.isEmpty();
	}

	/** The expression the merged local {@code slot} holds at {@code at}: its one store's value. */
	private static String stored(NativeCallInlining.Conditions conditions, Frame<SourceValue>[] frames, MethodNode method, int slot, AbstractInsnNode at) {
		Frame<SourceValue> frame = frames[method.instructions.indexOf(at)];
		if (frame == null) return "?";
		SourceValue local = frame.getLocal(slot);
		if (local.insns.size() != 1) return "?";
		String stored = conditions.stored(local.insns.iterator().next());
		return stored == null ? "?" : stored;
	}

	/** The debug-named local {@code slot} of {@code method} where {@code at} is. */
	private static LocalVariableNode local(MethodNode method, int slot, AbstractInsnNode at) {
		if (method.localVariables == null) return null;
		int index = method.instructions.indexOf(at);
		for (LocalVariableNode local : method.localVariables)
			if (local.index == slot && method.instructions.indexOf(local.start) <= index && index < method.instructions.indexOf(local.end)) return local;
		return null;
	}

	/** The local of {@code method} live at {@code at} with {@code named}'s name and type. */
	private static LocalVariableNode sameLocal(MethodNode method, LocalVariableNode named, AbstractInsnNode at) {
		if (method.localVariables == null) return null;
		int index = method.instructions.indexOf(at);
		LocalVariableNode found = null;
		for (LocalVariableNode local : method.localVariables)
			if (local.name.equals(named.name) && local.desc.equals(named.desc) && method.instructions.indexOf(local.start) <= index
					&& index < method.instructions.indexOf(local.end)) {
				if (found != null && found.index != local.index) return null;
				found = local;
			}
		return found;
	}

	private static List<AbstractInsnNode> returnsAt(MethodNode method, List<Integer> ordinals) {
		List<AbstractInsnNode> out = new ArrayList<>();
		int ordinal = 0;
		for (AbstractInsnNode instruction : method.instructions) {
			int opcode = instruction.getOpcode();
			if (opcode < Opcodes.IRETURN || opcode > Opcodes.RETURN) continue;
			if (ordinals.contains(ordinal)) out.add(instruction);
			ordinal++;
		}
		return out;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// The handler

	/**
	 * Whether the handler takes exactly the call's values and the {@code Operation}, and uses the {@code Operation} only as
	 * the receiver of {@code call(Object...)} handed a fresh array of exactly its own values, in order and unchanged.
	 */
	static boolean handsItsValues(ClassNode mixin, MethodNode handler, MethodInsnNode call) {
		List<Type> operands = CarrierDecisions.operandTypes(call);
		Type[] parameters = Type.getArgumentTypes(handler.desc);
		if (parameters.length != operands.size() + 1 || !parameters[operands.size()].getInternalName().equals(OPERATION)) return false;
		for (int i = 0; i < operands.size(); i++) if (!parameters[i].equals(operands.get(i))) return false;
		if (!Type.getReturnType(handler.desc).equals(Type.getReturnType(call.desc))) return false;
		for (List<AnnotationNode>[] table : Arrays.asList(handler.visibleParameterAnnotations, handler.invisibleParameterAnnotations))
			if (table != null) for (List<AnnotationNode> annotations : table) if (annotations != null && !annotations.isEmpty()) return false;
		boolean isStatic = (handler.access & Opcodes.ACC_STATIC) != 0;
		int[] slots = new int[parameters.length];
		int next = isStatic ? 0 : 1;
		for (int i = 0; i < parameters.length; i++) {
			slots[i] = next;
			next += parameters[i].getSize();
		}
		Frame<SourceValue>[] frames;
		try {
			frames = new Analyzer<>(new SourceInterpreter()).analyze(mixin.name, handler);
		} catch (AnalyzerException | RuntimeException unanalysable) {
			Caught.expected(unanalysable);
			return false;
		}
		int operation = slots[operands.size()];
		for (AbstractInsnNode instruction : handler.instructions) {
			if (instruction instanceof VarInsnNode load && load.var == operation) {
				if (load.getOpcode() != Opcodes.ALOAD) return false;
				// The Operation's only use: the receiver of call(Object[]) — read off where the loaded value goes.
				MethodInsnNode use = consumer(handler, frames, load);
				if (use == null || !use.owner.equals(OPERATION) || !use.name.equals("call") || !use.desc.equals("([Ljava/lang/Object;)Ljava/lang/Object;")) return false;
				if (!handsParameters(handler, frames, use, slots, operands)) return false;
			}
			if (instruction instanceof VarInsnNode store && store.getOpcode() >= Opcodes.ISTORE && store.getOpcode() <= Opcodes.ASTORE) {
				for (int i = 0; i < parameters.length; i++) if (store.var == slots[i]) return false;
			}
			if (instruction instanceof org.objectweb.asm.tree.IincInsnNode iinc) for (int slot : slots) if (iinc.var == slot) return false;
		}
		return true;
	}

	/** The call that consumes the value {@code load} pushes as its receiver; null when it is anything else. */
	private static MethodInsnNode consumer(MethodNode handler, Frame<SourceValue>[] frames, VarInsnNode load) {
		for (AbstractInsnNode instruction : handler.instructions) {
			if (!(instruction instanceof MethodInsnNode use) || use.getOpcode() == Opcodes.INVOKESTATIC) continue;
			Frame<SourceValue> frame = frames[handler.instructions.indexOf(use)];
			if (frame == null) continue;
			int arguments = Type.getArgumentTypes(use.desc).length;
			SourceValue receiver = frame.getStack(frame.getStackSize() - 1 - arguments);
			if (receiver.insns.contains(load)) return receiver.insns.size() == 1 ? use : null;
			for (int k = 0; k < arguments; k++) if (frame.getStack(frame.getStackSize() - 1 - k).insns.contains(load)) return null;
		}
		return null;
	}

	/** Whether {@code use} is handed a fresh {@code Object[]} whose elements are the handler's own values, in order. */
	private static boolean handsParameters(MethodNode handler, Frame<SourceValue>[] frames, MethodInsnNode use, int[] slots, List<Type> operands) {
		Frame<SourceValue> frame = frames[handler.instructions.indexOf(use)];
		SourceValue array = frame.getStack(frame.getStackSize() - 1);
		if (array.insns.size() != 1) return false;
		AbstractInsnNode made = array.insns.iterator().next();
		// ANEWARRAY's own frame: its length is the constant on top.
		if (made.getOpcode() != Opcodes.ANEWARRAY) {
			// A DUP of the array after its stores leaves the DUP as the producer; follow it back.
			while (made != null && made.getOpcode() == Opcodes.DUP) {
				Frame<SourceValue> before = frames[handler.instructions.indexOf(made)];
				SourceValue duplicated = before.getStack(before.getStackSize() - 1);
				if (duplicated.insns.size() != 1) return false;
				made = duplicated.insns.iterator().next();
			}
			if (made == null || made.getOpcode() != Opcodes.ANEWARRAY) return false;
		}
		int stored = 0;
		for (AbstractInsnNode instruction = made.getNext(); instruction != null && instruction != use; instruction = instruction.getNext()) {
			if (instruction.getOpcode() != Opcodes.AASTORE) continue;
			Frame<SourceValue> before = frames[handler.instructions.indexOf(instruction)];
			SourceValue element = before.getStack(before.getStackSize() - 1), index = before.getStack(before.getStackSize() - 2);
			if (element.insns.size() != 1 || index.insns.size() != 1) return false;
			int position = constant(index.insns.iterator().next());
			if (position != stored || position >= operands.size()) return false;
			AbstractInsnNode value = element.insns.iterator().next();
			// A boxed primitive is the box of its own parameter.
			if (value instanceof MethodInsnNode box && box.getOpcode() == Opcodes.INVOKESTATIC && box.name.equals("valueOf")) {
				Frame<SourceValue> boxed = frames[handler.instructions.indexOf(box)];
				SourceValue primitive = boxed.getStack(boxed.getStackSize() - 1);
				if (primitive.insns.size() != 1) return false;
				value = primitive.insns.iterator().next();
			}
			if (!(value instanceof VarInsnNode read) || read.var != slots[position] || read.getOpcode() != operands.get(position).getOpcode(Opcodes.ILOAD)) return false;
			stored++;
		}
		return stored == operands.size();
	}

	private static int constant(AbstractInsnNode instruction) {
		int opcode = instruction.getOpcode();
		if (opcode >= Opcodes.ICONST_0 && opcode <= Opcodes.ICONST_5) return opcode - Opcodes.ICONST_0;
		if (instruction instanceof org.objectweb.asm.tree.IntInsnNode number && opcode != Opcodes.NEWARRAY) return number.operand;
		return -1;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// The move

	private static MethodNode relocate(ClassNode mixin, MethodNode handler, AnnotationNode injector, MethodNode selected, ClassNode merged, MethodInsnNode call,
			List<Integer> returns, List<Value> values) {
		boolean isStatic = (handler.access & Opcodes.ACC_STATIC) != 0;
		if (isStatic != ((selected.access & Opcodes.ACC_STATIC) != 0)) return null;
		String name = handler.name;
		for (MethodNode other : mixin.methods) if (other.name.equals(name + CarrierDecisions.SUFFIX)) return null;
		Type answer = Type.getReturnType(call.desc);
		// The carrier's answer is handed on as an Operation's: a reference.
		if (answer.getSort() != Type.OBJECT && answer.getSort() != Type.ARRAY) return null;
		List<Type> parameters = new ArrayList<>(List.of(answer));
		List<Integer> localSlots = new ArrayList<>();
		for (Value value : values) if (value.local() >= 0) {
			parameters.add(value.type());
			localSlots.add(value.local());
		}
		MethodNode wrapper = new MethodNode(Opcodes.ACC_PRIVATE | (isStatic ? Opcodes.ACC_STATIC : 0), name,
				Type.getMethodDescriptor(answer, parameters.toArray(Type[]::new)), null, null);
		int[] slots = new int[parameters.size()];
		int next = isStatic ? 0 : 1;
		for (int i = 0; i < slots.length; i++) {
			slots[i] = next;
			next += parameters.get(i).getSize();
		}
		InsnList body = wrapper.instructions;
		if (!isStatic) body.add(new VarInsnNode(Opcodes.ALOAD, 0));
		int local = 1;
		for (Value value : values) {
			if (value.local() >= 0) {
				body.add(new VarInsnNode(value.type().getOpcode(Opcodes.ILOAD), slots[local++]));
			} else {
				body.add(new VarInsnNode(Opcodes.ALOAD, 0));
				body.add(new TypeInsnNode(Opcodes.CHECKCAST, merged.name));
				MethodInsnNode getter = value.getter();
				body.add(new MethodInsnNode(getter.getOpcode(), getter.owner, getter.name, getter.desc, getter.itf));
				Type got = Type.getReturnType(getter.desc);
				if (!got.equals(value.type()) && value.type().getSort() == Type.OBJECT) body.add(new TypeInsnNode(Opcodes.CHECKCAST, value.type().getInternalName()));
			}
		}
		body.add(new VarInsnNode(answer.getOpcode(Opcodes.ILOAD), slots[0]));
		body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CONSTANT_OWNER, "constant", "(Ljava/lang/Object;)L" + OPERATION + ";", false));
		handler.name = name + CarrierDecisions.SUFFIX;
		body.add(MixinHandlerShim.callOwn(mixin, isStatic, handler.name, handler.desc));
		body.add(new InsnNode(answer.getOpcode(Opcodes.IRETURN)));
		wrapper.maxLocals = next;
		wrapper.maxStack = values.size() + 4;
		try {
			new Analyzer<>(new BasicVerifier()).analyze(mixin.name, wrapper);
		} catch (AnalyzerException | RuntimeException unverifiable) {
			Caught.expected(unverifiable);
			handler.name = name;
			return null;
		}
		if (!localSlots.isEmpty()) {
			@SuppressWarnings("unchecked") List<AnnotationNode>[] table = new List[parameters.size()];
			for (int i = 0; i < localSlots.size(); i++) {
				AnnotationNode sugar = new AnnotationNode(CarrierDecisions.LOCAL);
				sugar.values = new ArrayList<>(List.of("index", localSlots.get(i)));
				table[i + 1] = new ArrayList<>(List.of(sugar));
			}
			wrapper.invisibleParameterAnnotations = table;
			wrapper.invisibleAnnotableParameterCount = table.length;
		}
		List<AnnotationNode> ats = new ArrayList<>();
		for (int ordinal : returns) {
			AnnotationNode at = new AnnotationNode(AT);
			at.values = new ArrayList<>(List.of("value", "RETURN", "ordinal", ordinal));
			ats.add(at);
		}
		AnnotationNode modify = new AnnotationNode(MODIFY_RETURN);
		modify.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of(selected.name + selected.desc)), "at", ats));
		boolean visible = handler.visibleAnnotations != null && handler.visibleAnnotations.remove(injector);
		if (!visible && handler.invisibleAnnotations != null) handler.invisibleAnnotations.remove(injector);
		wrapper.visibleAnnotations = new ArrayList<>(List.of(modify));
		mixin.methods.add(wrapper);
		return wrapper;
	}

	// ---------------------------------------------------------------------------------------------------------------

	@SuppressWarnings("unchecked")
	private static Frame<SourceValue>[] frames(String owner, MethodNode method) {
		try {
			return new Analyzer<>(new SourceInterpreter()).analyze(owner, method);
		} catch (AnalyzerException | RuntimeException unanalysable) {
			Caught.expected(unanalysable);
			return null;
		}
	}

	private static AbstractInsnNode nextReal(AbstractInsnNode instruction) {
		for (AbstractInsnNode at = instruction.getNext(); at != null; at = at.getNext()) if (at.getOpcode() >= 0) return at;
		return null;
	}

	private static boolean annotated(MethodNode method, String desc) {
		for (List<AnnotationNode> list : Arrays.asList(method.visibleAnnotations, method.invisibleAnnotations))
			if (list != null) for (AnnotationNode annotation : list) if (annotation.desc.equals(desc)) return true;
		return false;
	}
}
