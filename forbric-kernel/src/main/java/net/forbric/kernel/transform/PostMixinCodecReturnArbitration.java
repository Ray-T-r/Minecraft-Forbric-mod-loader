/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/** Reconciles all returns around a proved existing heterogeneous-codec seam, including a guest's early return. */
public final class PostMixinCodecReturnArbitration implements ClassTransformer {
	public static final String PROPERTY = "forbric.postMixinCodecArbitration";
	private static final String OWNER = "net/forbric/kernel/interop/PayloadInterop";
	private static final String FIND = "(Ljava/util/Map;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
	private static final String AFTER = "(Ljava/util/Map;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
	private static final String OPERATION = "com/llamalad7/mixinextras/injector/wrapoperation/Operation";
	private record Recipe(List<AbstractInsnNode> instructions, String identity) { }
	private record Seam(MethodNode method, List<Recipe> context) { }
	private record Bridge(Seam seam, Map<Integer,Integer> operandIndices) { }

	@Override public AnchorSet anchors() { return AnchorSet.scanned("all reference returns of an existing heterogeneous codec lookup with immutable pure context loads"); }
	@Override public byte[] transform(String name, byte[] bytes, TransformContext context) {
		if (bytes == null || bytes.length == 0 || "off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"))) return bytes;
		ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
		List<Seam> seams = new ArrayList<>();
		for (MethodNode method : node.methods) { List<Recipe> recipes = context(node, method); if (recipes != null) seams.add(new Seam(method, recipes)); }
		boolean changed = arbitrateWrappedCalls(node, seams);
		for (MethodNode method : node.methods) changed |= arbitrate(node, method);
		if (!changed) return bytes;
		ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray();
	}

	private static boolean arbitrate(ClassNode owner, MethodNode method) {
		if (Type.getReturnType(method.desc).getSort() != Type.OBJECT) return false;
		List<MethodInsnNode> seams = new ArrayList<>(); List<AbstractInsnNode> returns = new ArrayList<>();
		for (AbstractInsnNode instruction : method.instructions) {
			if (instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC && call.owner.equals(OWNER)
					&& call.name.equals("findCodec") && call.desc.equals(FIND)) seams.add(call);
			if (instruction.getOpcode() == Opcodes.ARETURN && !alreadyArbitrated(instruction)) returns.add(instruction);
		}
		if (seams.size() != 1 || returns.isEmpty()) return false;
		List<Recipe> context = context(owner, method);
		if (context == null) return false;
		int chosen = method.maxLocals;
		for (AbstractInsnNode exit : returns) {
			InsnList code = new InsnList(); code.add(new VarInsnNode(Opcodes.ASTORE, chosen));
			for (Recipe recipe : context) for (AbstractInsnNode instruction : recipe.instructions()) code.add(instruction.clone(new HashMap<>()));
			code.add(new VarInsnNode(Opcodes.ALOAD, chosen));
			code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, OWNER, "afterGuestCodec", AFTER, false));
			String returned = Type.getReturnType(method.desc).getInternalName();
			if (!returned.equals("java/lang/Object")) code.add(new TypeInsnNode(Opcodes.CHECKCAST, returned));
			method.instructions.insertBefore(exit, code);
		}
		method.maxLocals = chosen + 1; method.maxStack = Math.max(method.maxStack, 6);
		return true;
	}

	private static List<Recipe> context(ClassNode owner, MethodNode method) {
		List<MethodInsnNode> seams = new ArrayList<>();
		for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
				&& call.owner.equals(OWNER) && call.name.equals("findCodec") && call.desc.equals(FIND)) seams.add(call);
		if (seams.size() != 1) return null;
		Frame<SourceValue>[] frames;
		try { frames = new Analyzer<>(new SourceInterpreter()).analyze(owner.name, method); }
		catch (AnalyzerException | RuntimeException invalid) { return null; }
		Frame<SourceValue> at = frames[method.instructions.indexOf(seams.getFirst())];
		if (at == null || at.getStackSize() < 5) return null;
		List<Recipe> context = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			Recipe recipe = recipe(owner, method, frames, at.getStack(at.getStackSize() - 5 + i), new HashSet<>());
			if (recipe == null) return null;
			context.add(recipe);
		}
		return List.copyOf(context);
	}

	/** A wrapper can skip its Operation entirely. Follow that Operation's actual bridge, not its generated name. */
	private static boolean arbitrateWrappedCalls(ClassNode owner, List<Seam> seams) {
		if (seams.isEmpty()) return false;
		boolean changed = false;
		for (MethodNode caller : owner.methods) {
			Origins origins = origins(owner, caller); if (origins == null) continue;
			List<MethodInsnNode> calls = new ArrayList<>();
			for (AbstractInsnNode instruction : caller.instructions) if (instruction instanceof MethodInsnNode call
					&& call.owner.equals(owner.name) && !callWrapped(call)) calls.add(call);
			for (MethodInsnNode call : calls) {
				MethodNode wrapper = owner.methods.stream().filter(m -> m.name.equals(call.name) && m.desc.equals(call.desc)).findFirst().orElse(null);
				if (wrapper == null || Type.getReturnType(call.desc).getSort() != Type.OBJECT) continue;
				Type[] arguments = Type.getArgumentTypes(call.desc); int operationArgument = -1;
				for (int i = 0; i < arguments.length; i++) if (arguments[i].getSort() == Type.OBJECT && arguments[i].getInternalName().equals(OPERATION)) {
					if (operationArgument != -1) { operationArgument = -2; break; } operationArgument = i;
				}
				if (operationArgument < 0) continue;
				Frame<SourceValue> frame = origins.at(call); if (frame == null || frame.getStackSize() < arguments.length) continue;
				int start = frame.getStackSize() - arguments.length;
				AbstractInsnNode operation = one(frame.getStack(start + operationArgument));
				if (!(operation instanceof InvokeDynamicInsnNode dynamic) || !dynamic.desc.equals("()L" + OPERATION + ";")
						|| !dynamic.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory") || !dynamic.bsm.getName().equals("metafactory")
						|| dynamic.bsmArgs.length != 3 || !(dynamic.bsmArgs[1] instanceof Handle implementation)
						|| implementation.getTag() != Opcodes.H_INVOKESTATIC || !implementation.getOwner().equals(owner.name)) continue;
				MethodNode bridgeMethod = owner.methods.stream().filter(m -> m.name.equals(implementation.getName()) && m.desc.equals(implementation.getDesc())).findFirst().orElse(null);
				Bridge bridge = bridge(owner, bridgeMethod, seams); if (bridge == null || !Type.getReturnType(bridge.seam.method.desc).equals(Type.getReturnType(call.desc))) continue;
				Map<Integer,Integer> parameterArguments = originalInvocationArguments(owner, wrapper, operationArgument, bridge);
				if (parameterArguments == null || !receiverIsOwner(owner, caller, origins, frame, start, bridge, parameterArguments)) continue;
				boolean valid = true;
				// Capture the already-evaluated invocation inputs. The guest and its Operation still run exactly once.
				int[] saved = new int[arguments.length]; int next = caller.maxLocals;
				for (int i = 0; i < arguments.length; i++) { saved[i] = next; next += arguments[i].getSize(); }
				int receiver = call.getOpcode() == Opcodes.INVOKESTATIC ? -1 : next++;
				int chosen = next++; InsnList before = new InsnList();
				for (int i = arguments.length - 1; i >= 0; i--) before.add(new VarInsnNode(arguments[i].getOpcode(Opcodes.ISTORE), saved[i]));
				if (receiver >= 0) before.add(new VarInsnNode(Opcodes.ASTORE, receiver));
				if (receiver >= 0) before.add(new VarInsnNode(Opcodes.ALOAD, receiver));
				for (int i = 0; i < arguments.length; i++) before.add(new VarInsnNode(arguments[i].getOpcode(Opcodes.ILOAD), saved[i]));
				InsnList after = new InsnList(); after.add(new VarInsnNode(Opcodes.ASTORE, chosen));
				for (Recipe recipe : bridge.seam.context) for (AbstractInsnNode instruction : recipe.instructions) {
					if (instruction instanceof VarInsnNode load) {
						Integer argument = parameterArguments.get(load.var); if (argument == null) { valid = false; break; }
						after.add(new VarInsnNode(Opcodes.ALOAD, saved[argument]));
						Type expected = parameterType(owner, bridge.seam.method, load.var);
						if (expected == null) { valid = false; break; }
						if (!arguments[argument].equals(expected)) after.add(new TypeInsnNode(Opcodes.CHECKCAST, expected.getInternalName()));
					} else after.add(instruction.clone(new HashMap<>()));
				}
				if (!valid) continue;
				after.add(new VarInsnNode(Opcodes.ALOAD, chosen)); after.add(new MethodInsnNode(Opcodes.INVOKESTATIC, OWNER, "afterGuestCodec", AFTER, false));
				after.add(new TypeInsnNode(Opcodes.CHECKCAST, Type.getReturnType(call.desc).getInternalName()));
				caller.instructions.insertBefore(call, before); caller.instructions.insert(call, after);
				caller.maxLocals = next; caller.maxStack += 6; changed = true;
			}
		}
		return changed;
	}

	private static Bridge bridge(ClassNode owner, MethodNode method, List<Seam> seams) {
		if (method == null || (method.access & Opcodes.ACC_STATIC) == 0 || !Arrays.equals(Type.getArgumentTypes(method.desc), new Type[]{Type.getType("[Ljava/lang/Object;")})) return null;
		Origins origins = origins(owner, method); if (origins == null) return null;
		MethodInsnNode call = null; Seam selected = null; int returns = 0;
		for (AbstractInsnNode instruction : method.instructions) {
			if (instruction instanceof MethodInsnNode candidate && candidate.owner.equals(owner.name)) for (Seam seam : seams)
				if (candidate.name.equals(seam.method.name) && candidate.desc.equals(seam.method.desc)) { if (call != null) return null; call = candidate; selected = seam; }
			if (instruction.getOpcode() == Opcodes.AASTORE) return null;
			if (instruction.getOpcode() == Opcodes.ARETURN) returns++;
		}
		if (call == null || returns != 1 || !Type.getReturnType(method.desc).equals(Type.getReturnType(call.desc))) return null;
		for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof MethodInsnNode other && other != call
				&& !(other.getOpcode() == Opcodes.INVOKESTATIC && other.owner.equals("com/llamalad7/mixinextras/injector/wrapoperation/WrapOperationRuntime")
				&& other.name.equals("checkArgumentCount") && other.desc.equals("([Ljava/lang/Object;ILjava/lang/String;)V"))) return null;
		for (AbstractInsnNode instruction : method.instructions) if (instruction.getOpcode() == Opcodes.ARETURN) {
			Frame<SourceValue> returned = origins.at(instruction); if (returned == null || one(returned.getStack(returned.getStackSize() - 1)) != call) return null;
		}
		Frame<SourceValue> frame = origins.at(call); if (frame == null) return null;
		Type[] arguments = Type.getArgumentTypes(call.desc); boolean instance = call.getOpcode() != Opcodes.INVOKESTATIC;
		int start = frame.getStackSize() - arguments.length - (instance ? 1 : 0); if (start < 0) return null;
		Map<Integer,Integer> operands = new HashMap<>(); int offset = 0, slot = 0;
		if (instance) { Integer index = arrayIndex(origins, frame.getStack(start)); if (index == null) return null; operands.put(0, index); offset++; slot++; }
		for (Type argument : arguments) {
			if (argument.getSort() != Type.OBJECT && argument.getSort() != Type.ARRAY) return null;
			Integer index = arrayIndex(origins, frame.getStack(start + offset++)); if (index == null) return null;
			operands.put(slot, index); slot += argument.getSize();
		}
		return new Bridge(selected, Map.copyOf(operands));
	}

	private static Integer arrayIndex(Origins origins, SourceValue value) {
		AbstractInsnNode source = one(value); if (source == null || source.getOpcode() != Opcodes.AALOAD) return null;
		Frame<SourceValue> frame = origins.at(source); if (frame == null || frame.getStackSize() < 2
				|| origins.parameter(frame.getStack(frame.getStackSize() - 2)) != 0) return null;
		return integer(one(frame.getStack(frame.getStackSize() - 1)));
	}

	/** The Operation ABI prefixes its parameter with the original receiver/arguments in bridge-array order.
	 * The handler is free to give Operation.call different arguments, or never call it at all. Those are effects
	 * of the guest, not a recipe for the original invocation's context. */
	private static Map<Integer,Integer> originalInvocationArguments(ClassNode owner, MethodNode wrapper, int operationArgument, Bridge bridge) {
		Map<Integer,Integer> operands = bridge.operandIndices; Set<Integer> indices = new HashSet<>(operands.values());
		if (operationArgument != operands.size() || indices.size() != operands.size()) return null;
		Type[] arguments = Type.getArgumentTypes(wrapper.desc); boolean instance = (bridge.seam.method.access & Opcodes.ACC_STATIC) == 0;
		for (var operand : operands.entrySet()) {
			int argument = operand.getValue(); if (argument < 0 || argument >= operationArgument) return null;
			Type expected = parameterType(owner, bridge.seam.method, operand.getKey()); if (expected == null) return null;
			if (instance && operand.getKey() == 0) {
				// A coerced receiver can use a supertype, but the caller must separately prove the actual owner.
				if (arguments[argument].getSort() != Type.OBJECT) return null;
			} else if (!arguments[argument].equals(expected)) return null;
		}
		return Map.copyOf(operands);
	}

	private static boolean receiverIsOwner(ClassNode owner, MethodNode caller, Origins origins, Frame<SourceValue> frame, int start, Bridge bridge, Map<Integer,Integer> operands) {
		if ((bridge.seam.method.access & Opcodes.ACC_STATIC) != 0) return true;
		Integer receiver = operands.get(0); if (receiver == null) return false;
		int parameter = origins.parameter(frame.getStack(start + receiver));
		Type type = parameter < 0 ? null : parameterType(owner, caller, parameter);
		return type != null && type.getSort() == Type.OBJECT && type.getInternalName().equals(owner.name);
	}

	private static Type parameterType(ClassNode owner, MethodNode method, int wanted) {
		int slot = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
		if (slot == 1 && wanted == 0) return Type.getObjectType(owner.name);
		for (Type argument : Type.getArgumentTypes(method.desc)) { if (slot == wanted) return argument; slot += argument.getSize(); }
		return null;
	}
	private static AbstractInsnNode one(SourceValue value) { return value != null && value.insns.size() == 1 ? value.insns.iterator().next() : null; }
	private static Integer integer(AbstractInsnNode instruction) {
		if (instruction == null) return null; int opcode = instruction.getOpcode();
		if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) return opcode - Opcodes.ICONST_0;
		if (instruction instanceof IntInsnNode constant && (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH)) return constant.operand;
		if (instruction instanceof LdcInsnNode constant && constant.cst instanceof Integer value) return value; return null;
	}
	private static boolean callWrapped(MethodInsnNode call) {
		AbstractInsnNode next = call.getNext(); int instructions = 0;
		while (next != null && instructions++ < 24) {
			if (next instanceof MethodInsnNode method) return method.owner.equals(OWNER) && method.name.equals("afterGuestCodec") && method.desc.equals(AFTER);
			if (next.getOpcode() == Opcodes.ARETURN || next instanceof JumpInsnNode) return false; next = next.getNext();
		}
		return false;
	}
	private static final class OriginInterpreter extends SourceInterpreter {
		private final Map<AbstractInsnNode,Integer> parameters = new IdentityHashMap<>();
		OriginInterpreter() { super(Opcodes.ASM9); }
		@Override public SourceValue newParameterValue(boolean instance, int local, Type type) {
			VarInsnNode marker = new VarInsnNode(Opcodes.ALOAD, local); parameters.put(marker, local); return new SourceValue(type.getSize(), marker);
		}
		@Override public SourceValue copyOperation(AbstractInsnNode instruction, SourceValue value) { return value; }
		@Override public SourceValue unaryOperation(AbstractInsnNode instruction, SourceValue value) {
			return instruction.getOpcode() == Opcodes.CHECKCAST ? value : super.unaryOperation(instruction, value);
		}
	}
	private record Origins(Map<AbstractInsnNode,Frame<SourceValue>> frames, OriginInterpreter interpreter) {
		Frame<SourceValue> at(AbstractInsnNode instruction) { return frames.get(instruction); }
		int parameter(SourceValue value) { return interpreter.parameters.getOrDefault(one(value), -1); }
	}
	private static Origins origins(ClassNode owner, MethodNode method) {
		try {
			OriginInterpreter interpreter = new OriginInterpreter(); Frame<SourceValue>[] frames = new Analyzer<>(interpreter).analyze(owner.name, method);
			Map<AbstractInsnNode,Frame<SourceValue>> byInstruction = new IdentityHashMap<>();
			for (int i = 0; i < method.instructions.size(); i++) byInstruction.put(method.instructions.get(i), frames[i]);
			return new Origins(byInstruction, interpreter);
		}
		catch (AnalyzerException | RuntimeException invalid) { return null; }
	}

	/** Only unmodified parameters and fields that this declaring class marks final; no arbitrary getters or phi values. */
	private static Recipe recipe(ClassNode owner, MethodNode method, Frame<SourceValue>[] frames, SourceValue value, Set<AbstractInsnNode> seen) {
		if (value == null || value.insns.size() != 1) return null;
		AbstractInsnNode instruction = value.insns.iterator().next(); if (!seen.add(instruction)) return null;
		Frame<SourceValue> frame = frames[method.instructions.indexOf(instruction)]; if (frame == null) return null;
		if (instruction instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && parameter(method, load.var)
				&& !written(method, load.var)) return new Recipe(List.of(new VarInsnNode(Opcodes.ALOAD, load.var)), "parameter:" + load.var);
		if (instruction instanceof FieldInsnNode field && field.owner.equals(owner.name)
				&& (field.getOpcode() == Opcodes.GETFIELD || field.getOpcode() == Opcodes.GETSTATIC)) {
			FieldNode declaration = owner.fields.stream().filter(f -> f.name.equals(field.name) && f.desc.equals(field.desc)).findFirst().orElse(null);
			if (declaration == null || (declaration.access & Opcodes.ACC_FINAL) == 0
					|| ((declaration.access & Opcodes.ACC_STATIC) != 0) != (field.getOpcode() == Opcodes.GETSTATIC)
					|| fieldWritten(method, field)) return null;
			List<AbstractInsnNode> loads = new ArrayList<>(); String prefix = "";
			if (field.getOpcode() == Opcodes.GETFIELD) {
				if (frame.getStackSize() == 0) return null;
				Recipe receiver = recipe(owner, method, frames, frame.getStack(frame.getStackSize() - 1), seen);
				if (receiver == null || !receiver.identity().equals("parameter:0") || (method.access & Opcodes.ACC_STATIC) != 0) return null;
				loads.addAll(receiver.instructions()); prefix = receiver.identity() + "/";
			}
			loads.add(new FieldInsnNode(field.getOpcode(), field.owner, field.name, field.desc));
			return new Recipe(List.copyOf(loads), prefix + field.owner + "." + field.name + field.desc);
		}
		if (instruction instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST && frame.getStackSize() > 0) {
			Recipe original = recipe(owner, method, frames, frame.getStack(frame.getStackSize() - 1), seen); if (original == null) return null;
			List<AbstractInsnNode> code = new ArrayList<>(original.instructions()); code.add(new TypeInsnNode(Opcodes.CHECKCAST, cast.desc));
			return new Recipe(List.copyOf(code), original.identity() + " cast:" + cast.desc);
		}
		return null;
	}
	private static boolean parameter(MethodNode method, int wanted) {
		int slot = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
		if (slot == 1 && wanted == 0) return true;
		for (Type parameter : Type.getArgumentTypes(method.desc)) { if (wanted == slot && (parameter.getSort() == Type.OBJECT || parameter.getSort() == Type.ARRAY)) return true; slot += parameter.getSize(); }
		return false;
	}
	private static boolean written(MethodNode method, int slot) {
		for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof VarInsnNode store && store.var == slot
				&& store.getOpcode() >= Opcodes.ISTORE && store.getOpcode() <= Opcodes.ASTORE || instruction instanceof IincInsnNode increment && increment.var == slot) return true;
		return false;
	}
	private static boolean fieldWritten(MethodNode method, FieldInsnNode value) {
		for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof FieldInsnNode write && write.owner.equals(value.owner)
				&& write.name.equals(value.name) && write.desc.equals(value.desc) && (write.getOpcode() == Opcodes.PUTFIELD || write.getOpcode() == Opcodes.PUTSTATIC)) return true;
		return false;
	}
	private static boolean alreadyArbitrated(AbstractInsnNode exit) {
		AbstractInsnNode previous = exit.getPrevious(); while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
		if (previous instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST) { previous = previous.getPrevious(); while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious(); }
		return previous instanceof MethodInsnNode call && call.owner.equals(OWNER) && call.name.equals("afterGuestCodec") && call.desc.equals(AFTER);
	}
}
