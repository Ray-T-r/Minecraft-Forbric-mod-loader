/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.boot.DefinedMethodContracts;
import net.forbric.kernel.boot.DefinedMethodContracts.MethodContract;
import net.forbric.kernel.boot.KernelDefaultPredicates;
import net.forbric.kernel.util.ForbricLog;

/** Transports an atomic expression modifier into an unchanged pure default behind a native virtual hook.
 * A hash-pinned native host proves the original OR, capture and control site. Current carrier defaults must
 * reproduce that exact OR through the same immutable getter; final-definition witnesses guard every dispatch. */
public final class MixinDefaultPredicateTransport {
	public static final String PROPERTY = "forbric.mixinDefaultPredicates";
	private static final String MODIFY = "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;";
	private static final String WRAP = "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";
	private static final String EXPRESSION = "Lcom/llamalad7/mixinextras/expression/Expression;";
	private static final String DEFINITION = "Lcom/llamalad7/mixinextras/expression/Definition;";
	private static final String LOCAL = "Lcom/llamalad7/mixinextras/sugar/Local;";
	private static final String GROUP = "Lorg/spongepowered/asm/mixin/injection/Group;";
	private static final String OPERATION = "com/llamalad7/mixinextras/injector/wrapoperation/Operation";
	private static final String RUNTIME = "net/forbric/kernel/boot/KernelDefaultPredicates";
	private static final String MODIFIER = RUNTIME + "$PredicateModifier";
	private static final Handle LAMBDA = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
			"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);
	private record Member(String owner, String name, String desc) { }
	private record Decl(String owner, MethodNode method) { MethodContract contract() { return new MethodContract(owner, method.name, method.desc, DefinedMethodContracts.fingerprint(method)); } }
	private record Lookup(Decl declaration, boolean known) { }
	private record FieldDecl(String owner, FieldNode field) { }
	private record Getter(Member member, Decl declaration, FieldDecl field) { }
	private record Expr(int local, Getter getter) {
		static Expr variable(int local) { return new Expr(local, null); }
	}
	private record Delegate(Decl entry, Getter getter, Decl fallback, int stateArgument, List<MethodContract> helpers) { }
	private record Region(TypeInsnNode first, TypeInsnNode second, AbstractInsnNode success, AbstractInsnNode failure) { }
	private record Plan(MethodInsnNode call, String key) { }
	private MixinDefaultPredicateTransport() { }
	public static boolean enabled() { return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on")); }

	public static int adapt(ClassNode mixin, Ecosystem ecosystem, Function<String, ClassNode> classes) {
		return adapt(mixin, classes, name -> NativeGameReferences.reference(ecosystem, name));
	}

	static int adapt(ClassNode mixin, Function<String, ClassNode> classes, Function<String, ClassNode> nativeClasses) {
		if (!enabled() || mixin == null) return 0;
		List<String> targets = MixinFit.mixinTargets(mixin);
		if (targets.size() != 1) return 0;
		ClassNode current = classes.apply(targets.getFirst()), original = nativeClasses.apply(targets.getFirst());
		if (current == null || original == null || !current.name.equals(original.name)) return 0;
		List<MethodNode> wrappers = new ArrayList<>();
		for (MethodNode handler : new ArrayList<>(mixin.methods)) {
			AnnotationNode injector = MixinFit.injectorOf(handler);
			Plan plan = plan(handler, injector, original, current, classes, nativeClasses);
			if (plan == null) continue;
			wrappers.add(wrap(mixin, handler, injector, plan));
			ForbricLog.info("[Forbric/Mixin] %s.%s follows a native-proved default predicate through a guarded virtual hook; native overrides retain precedence",
					mixin.name.replace('/', '.'), wrappers.getLast().name);
		}
		mixin.methods.addAll(wrappers); return wrappers.size();
	}

	private static Plan plan(MethodNode handler, AnnotationNode injector, ClassNode original, ClassNode current,
			Function<String, ClassNode> classes, Function<String,ClassNode> nativeClasses) {
		if (injector == null || !MODIFY.equals(injector.desc) || (handler.access & (Opcodes.ACC_SYNCHRONIZED | Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT)) != 0
				|| annotated(handler, GROUP) || MixinFit.value(injector, "slice") != null
				|| annotations(handler).stream().filter(a->a.desc.startsWith("Lorg/spongepowered/asm/mixin/injection/")||a.desc.startsWith("Lcom/llamalad7/mixinextras/injector/")).count()!=1) return null;
		Type[] parameters = Type.getArgumentTypes(handler.desc);
		if (parameters.length != 2 || !parameters[0].equals(Type.BOOLEAN_TYPE) || parameters[1].getSort() != Type.OBJECT
				|| !Type.getReturnType(handler.desc).equals(Type.BOOLEAN_TYPE)) return null;
		String capture = namedCapture(handler, 1);
		String atomicType = expressionType(handler);
		if (capture == null || atomicType == null) return null;
		List<AnnotationNode> ats = MixinFit.atNodes(injector);
		if (ats.size() != 1 || !"MIXINEXTRAS:EXPRESSION".equals(MixinFit.value(ats.getFirst(), "value"))
				|| MixinFit.value(ats.getFirst(), "ordinal") != null || MixinFit.value(ats.getFirst(), "shift") != null) return null;
		List<String> selectors = MixinFit.stringList(MixinFit.value(injector, "method"));
		if (selectors.size() != 1) return null;
		List<MethodNode> live = current.methods.stream().filter(m -> selectors.getFirst().equals(m.name) || selectors.getFirst().equals(m.name + m.desc)).toList();
		if (live.size() != 1) return null;
		MethodNode body = live.getFirst();
		MethodNode nativeBody = original.methods.stream().filter(m -> m.name.equals(body.name) && m.desc.equals(body.desc)).findFirst().orElse(null);
		if (nativeBody == null) return null;
		List<TypeInsnNode> oldAtoms = atoms(nativeBody, atomicType);
		if (oldAtoms.size() != 1 || !atoms(body, atomicType).isEmpty()) return null;
		Region region = region(nativeBody, oldAtoms.getFirst());
		if (region == null) return null;
		try {
			Sources nativeSources = new Sources(original.name, nativeBody, nativeClasses), liveSources = new Sources(current.name, body, classes);
			SourceValue operand = nativeSources.input(region.first(), 0);
			LocalVariableNode local = capture(nativeBody, region.first(), capture, parameters[1].getDescriptor());
			if (local == null || !nativeSources.local(region.first(), local.index).insns.equals(operand.insns) || operand.insns.size() != 1) return null;
			SourceValue projected = nativeSources.unstore(operand);
			if (projected == null || projected.insns.size() != 1) return null;
			AbstractInsnNode from = projected.insns.iterator().next();
			if (!(from instanceof MethodInsnNode call) || Type.getArgumentTypes(call.desc).length != 0
					|| !Type.getReturnType(call.desc).equals(parameters[1])) return null;
			Getter originalGetter = getter(new Member(call.owner, call.name, call.desc), classes);
			if (originalGetter == null || !nativeSources.signature(operand).equals(nativeSources.signature(nativeSources.input(region.second(), 0)))) return null;
			String nativeState = nativeSources.signature(nativeSources.input(call, 0));
			if (nativeState == null) return null;
			List<Plan> matches = new ArrayList<>();
			for (var instruction : body.instructions) {
				if (!(instruction instanceof MethodInsnNode candidate) || !Type.getReturnType(candidate.desc).equals(Type.BOOLEAN_TYPE)
						|| candidate.getOpcode() != Opcodes.INVOKEVIRTUAL && candidate.getOpcode() != Opcodes.INVOKEINTERFACE) continue;
				JumpInsnNode branch = branchAfter(candidate);
				if (branch == null || !continuations(nativeBody, region, body, branch)) continue;
				SourceValue receiver = liveSources.input(candidate, Type.getArgumentTypes(candidate.desc).length);
				if (!nativeState.equals(liveSources.signature(receiver))) continue;
				Delegate delegate = delegate(new Member(candidate.owner, candidate.name, candidate.desc), classes);
				if (delegate == null || !sameGetter(originalGetter, delegate.getter())) continue;
				if (!defaultExpression(delegate, region.first().desc, region.second().desc, classes)) continue;
				Getter projection = delegate.getter();
				var field = new KernelDefaultPredicates.ImmutableField(projection.field().owner(), projection.field().field().name, projection.field().field().desc);
				var contract = new KernelDefaultPredicates.Contract(delegate.entry().contract(), projection.declaration().contract(), delegate.fallback().contract(),
						delegate.helpers(), field, region.first().desc, region.second().desc);
				matches.add(new Plan(candidate, KernelDefaultPredicates.register(contract)));
			}
			return matches.size() == 1 ? matches.getFirst() : null;
		} catch (AnalyzerException | RuntimeException unavailable) { return null; }
	}

	private static boolean sameGetter(Getter a, Getter b) { return a.declaration().contract().equals(b.declaration().contract())
			&& a.field().owner().equals(b.field().owner()) && a.field().field().name.equals(b.field().field().name) && a.field().field().desc.equals(b.field().field().desc); }

	/** The only supported current forwarding grammar: this (possibly private identity casts), a pure final-field
	 * getter, unchanged arguments, one virtual Boolean call and return. No effects or context-dependent transforms. */
	private static Delegate delegate(Member entry, Function<String, ClassNode> classes) {
		Decl declaration = resolve(entry, classes, new HashSet<>());
		if (declaration == null || (declaration.method().access & Opcodes.ACC_PUBLIC) == 0 || !declaration.method().tryCatchBlocks.isEmpty()) return null;
		List<Expr> stack = new ArrayList<>(); List<MethodContract> helpers = new ArrayList<>();
		Type[] arguments = Type.getArgumentTypes(entry.desc());
		Map<Integer,Integer> argumentSlots = argumentSlots(arguments, false);
		Delegate result = null;
		try {
			for (var insn : declaration.method().instructions) {
				if (insn.getOpcode() < 0) continue;
				if (insn instanceof VarInsnNode load && load.getOpcode() >= Opcodes.ILOAD && load.getOpcode() <= Opcodes.ALOAD) stack.add(Expr.variable(load.var));
				else if (insn instanceof MethodInsnNode call && call.getOpcode() != Opcodes.INVOKESTATIC) {
					Type[] types = Type.getArgumentTypes(call.desc); List<Expr> passed = new ArrayList<>();
					for (int i = types.length - 1; i >= 0; i--) passed.add(0, pop(stack));
					Expr receiver = pop(stack); Decl method = resolve(new Member(call.owner, call.name, call.desc), classes, new HashSet<>());
					if (method == null) return null;
					if (Type.getReturnType(call.desc).equals(Type.BOOLEAN_TYPE)) {
						if (result != null || receiver.local() != 0 || receiver.getter() == null || passed.size() != arguments.length + 1) return null;
						int state = -1;
						for (int i = 0; i < passed.size(); i++) if (passed.get(i).local() == 0 && passed.get(i).getter() == null) { if (state >= 0) return null; state = i; }
						if (state < 0) return null;
						int argument = 0;
						for (int i = 0; i < passed.size(); i++) if (i != state) {
							Integer index = argumentSlots.get(passed.get(i).local());
							if (passed.get(i).getter() != null || index == null || index != argument || !types[i].equals(arguments[argument++])) return null;
						}
						result = new Delegate(declaration, receiver.getter(), method, state, List.copyOf(helpers)); stack.add(Expr.variable(-1));
					} else if (types.length == 0 && receiver.getter() == null && receiver.local() == 0 && identity(method)) {
						helpers.add(method.contract()); stack.add(receiver);
					} else if (types.length == 0 && receiver.getter() == null && receiver.local() == 0) {
						Getter projection = getter(new Member(call.owner, call.name, call.desc), classes);
						if (projection == null) return null; stack.add(new Expr(0, projection));
					} else return null;
				} else if (insn.getOpcode() == Opcodes.IRETURN) {
					if (result == null || stack.size() != 1 || pop(stack).local() != -1 || next(insn) != null) return null;
					return result;
				} else return null;
			}
		} catch (IndexOutOfBoundsException malformed) { return null; }
		return null;
	}

	private static boolean defaultExpression(Delegate delegate, String firstType, String secondType, Function<String, ClassNode> classes) {
		MethodNode method = delegate.fallback().method();
		if ((method.access & Opcodes.ACC_PUBLIC) == 0 || !method.tryCatchBlocks.isEmpty()) return false;
		List<AbstractInsnNode> code = code(method);
		if (code.size() != 12 || !(code.get(0) instanceof VarInsnNode a) || a.getOpcode() != Opcodes.ALOAD
				|| !(code.get(1) instanceof MethodInsnNode firstGetter) || !(code.get(2) instanceof TypeInsnNode first) || first.getOpcode() != Opcodes.INSTANCEOF
				|| !(code.get(3) instanceof JumpInsnNode yes) || yes.getOpcode() != Opcodes.IFNE
				|| !(code.get(4) instanceof VarInsnNode b) || b.getOpcode() != Opcodes.ALOAD || a.var != b.var
				|| !(code.get(5) instanceof MethodInsnNode secondGetter) || !(code.get(6) instanceof TypeInsnNode second) || second.getOpcode() != Opcodes.INSTANCEOF
				|| !(code.get(7) instanceof JumpInsnNode no) || no.getOpcode() != Opcodes.IFEQ
				|| code.get(8).getOpcode() != Opcodes.ICONST_1 || !(code.get(9) instanceof JumpInsnNode end) || end.getOpcode() != Opcodes.GOTO
				|| code.get(10).getOpcode() != Opcodes.ICONST_0 || code.get(11).getOpcode() != Opcodes.IRETURN
				|| next(yes.label) != code.get(8) || next(no.label) != code.get(10) || next(end.label) != code.get(11)
				|| !first.desc.equals(firstType) || !second.desc.equals(secondType)) return false;
		Map<Integer,Integer> slots = argumentSlots(Type.getArgumentTypes(method.desc), false);
		if (!Integer.valueOf(delegate.stateArgument()).equals(slots.get(a.var))) return false;
		Getter x = getter(new Member(firstGetter.owner, firstGetter.name, firstGetter.desc), classes);
		Getter y = getter(new Member(secondGetter.owner, secondGetter.name, secondGetter.desc), classes);
		return x != null && y != null && sameGetter(x, delegate.getter()) && sameGetter(y, delegate.getter());
	}

	private static Getter getter(Member member, Function<String, ClassNode> classes) {
		Decl declaration = resolve(member, classes, new HashSet<>());
		if (declaration == null || Type.getArgumentTypes(member.desc()).length != 0 || Type.getReturnType(member.desc()).getSort() != Type.OBJECT
				|| (declaration.method().access & Opcodes.ACC_PUBLIC) == 0 || !declaration.method().tryCatchBlocks.isEmpty()) return null;
		List<AbstractInsnNode> code = code(declaration.method());
		if (code.size() != 3 && code.size() != 4 || !(code.get(0) instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD || load.var != 0
				|| !(code.get(1) instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETFIELD || code.getLast().getOpcode() != Opcodes.ARETURN) return null;
		Type returned = Type.getReturnType(member.desc());
		if (code.size() == 4) {
			if (!(code.get(2) instanceof TypeInsnNode cast) || cast.getOpcode() != Opcodes.CHECKCAST || !cast.desc.equals(returned.getInternalName())) return null;
		} else if (!field.desc.equals(returned.getDescriptor())) return null;
		FieldDecl definition = field(field.owner, field.name, field.desc, classes, new HashSet<>());
		if (definition == null || (definition.field().access & Opcodes.ACC_FINAL) == 0 || (definition.field().access & Opcodes.ACC_STATIC) != 0) return null;
		return new Getter(member, declaration, definition);
	}
	private static boolean identity(Decl declaration) {
		MethodNode method = declaration.method(); List<AbstractInsnNode> code = code(method);
		return (method.access & Opcodes.ACC_PRIVATE) != 0 && Type.getArgumentTypes(method.desc).length == 0 && method.tryCatchBlocks.isEmpty()
				&& (code.size() == 2 || code.size() == 3) && code.getFirst() instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == 0
				&& (code.size() == 2 || code.get(1) instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST
						&& Type.getReturnType(method.desc).getSort() == Type.OBJECT && cast.desc.equals(Type.getReturnType(method.desc).getInternalName()))
				&& code.getLast().getOpcode() == Opcodes.ARETURN;
	}

	private static Region region(MethodNode method, TypeInsnNode first) {
		JumpInsnNode yes = branchAfter(first); if (yes == null || yes.getOpcode() != Opcodes.IFNE) return null;
		AbstractInsnNode success = next(yes.label), operand = next(yes);
		// The residual atom reads the existing captured local. A call/effect between the OR atoms is not
		// transported into a pure default and must not silently disappear.
		if (!(operand instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD) return null;
		AbstractInsnNode expression = next(operand);
		TypeInsnNode second = expression instanceof TypeInsnNode type && type.getOpcode() == Opcodes.INSTANCEOF ? type : null;
		JumpInsnNode no = second == null ? null : branchAfter(second);
		return no != null && no.getOpcode() == Opcodes.IFEQ && next(no) == success ? new Region(first, second, success, next(no.label)) : null;
	}
	private static boolean continuations(MethodNode original, Region region, MethodNode current, JumpInsnNode branch) {
		AbstractInsnNode success = branch.getOpcode() == Opcodes.IFEQ ? next(branch) : next(branch.label);
		AbstractInsnNode failure = branch.getOpcode() == Opcodes.IFEQ ? next(branch.label) : next(branch);
		List<String> a = continuation(original, region.success(), region.failure(), 24), b = continuation(current, success, failure, 24);
		if (a == null || !a.equals(b)) return false;
		List<String> x = continuation(original, region.failure(), null, 12), y = continuation(current, failure, null, 12);
		return x != null && x.equals(y);
	}
	private static List<String> continuation(MethodNode method, AbstractInsnNode start, AbstractInsnNode stop, int maximum) {
		if (start == null) return null; List<String> result = new ArrayList<>();
		AbstractInsnNode insn = start;
		for (; insn != null && insn != stop && result.size() < maximum; insn = next(insn)) {
			if (insn instanceof JumpInsnNode || insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode) { if(stop!=null)return null;break; }
			String value = Integer.toString(insn.getOpcode());
			if (insn instanceof VarInsnNode var) {
				int position = method.instructions.indexOf(insn);
				List<LocalVariableNode> names = method.localVariables == null ? List.of() : method.localVariables.stream().filter(l -> l.index == var.var
						&& method.instructions.indexOf(l.start) <= position && position < method.instructions.indexOf(l.end)).toList();
				// A new SSA local's debug scope starts immediately after its defining store.
				if (names.isEmpty() && var.getOpcode() >= Opcodes.ISTORE && var.getOpcode() <= Opcodes.ASTORE && method.localVariables != null) {
					int after = next(insn) == null ? position : method.instructions.indexOf(next(insn));
					names = method.localVariables.stream().filter(l -> l.index == var.var && position < method.instructions.indexOf(l.start)
							&& method.instructions.indexOf(l.start) <= after).toList();
				}
				if (names.size() != 1) return null; value += ":" + names.getFirst().name + ":" + names.getFirst().desc;
			} else if (insn instanceof MethodInsnNode call) value += ":" + call.owner + ":" + call.name + call.desc;
			else if (insn instanceof FieldInsnNode field) value += ":" + field.owner + ":" + field.name + field.desc;
			else if (insn instanceof LdcInsnNode constant) value += ":" + constant.cst;
			else if (insn instanceof TypeInsnNode type) value += ":" + type.desc;
			else if (insn instanceof IntInsnNode integer) value += ":" + integer.operand;
			else if (insn instanceof IincInsnNode || insn instanceof InvokeDynamicInsnNode) return null;
			result.add(value);
		}
		if(stop!=null&&insn!=stop)return null;
		return result.size() >= 3 ? List.copyOf(result) : null;
	}

	private static final class Parameter extends LabelNode { final String key; Parameter(String key) { this.key = key; } }
	private static final class TrackedSources extends SourceInterpreter {
		private final Map<Integer,String> parameters;
		TrackedSources(MethodNode method) {
			super(Opcodes.ASM9); parameters = new HashMap<>(); int slot = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
			if (slot == 1) parameters.put(0, "this"); int position = 0;
			for (Type type : Type.getArgumentTypes(method.desc)) { parameters.put(slot, "argument:" + position++ + ":" + type.getDescriptor()); slot += type.getSize(); }
		}
		@Override public SourceValue newParameterValue(boolean instance, int local, Type type) { return new SourceValue(type.getSize(), new Parameter(parameters.get(local))); }
		@Override public SourceValue copyOperation(AbstractInsnNode insn, SourceValue value) {
			// Retain store definitions so a phi carries the conditions of the assignments, not merely the
			// unconditionally computed inputs those assignments happened to load.
			return insn.getOpcode() >= Opcodes.ISTORE && insn.getOpcode() <= Opcodes.ASTORE ? new SourceValue(value.getSize(),insn) : value;
		}
	}
	private static final class Sources {
		final MethodNode method; final Frame<SourceValue>[] frames; final Function<String,ClassNode> classes;
		Sources(String owner, MethodNode method) throws AnalyzerException { this(owner,method,name->null); }
		Sources(String owner, MethodNode method, Function<String,ClassNode> classes) throws AnalyzerException { this.method = method; this.classes=classes;frames = new Analyzer<>(new TrackedSources(method)).analyze(owner, method); }
		SourceValue input(AbstractInsnNode insn, int fromTop) { Frame<SourceValue> f = frames[method.instructions.indexOf(insn)]; return f.getStack(f.getStackSize() - 1 - fromTop); }
		SourceValue local(AbstractInsnNode insn, int slot) { return frames[method.instructions.indexOf(insn)].getLocal(slot); }
		SourceValue unstore(SourceValue value) {
			Set<AbstractInsnNode> seen=new HashSet<>();
			while(value!=null&&value.insns.size()==1){AbstractInsnNode producer=value.insns.iterator().next();if(!(producer instanceof VarInsnNode store)||store.getOpcode()<Opcodes.ISTORE||store.getOpcode()>Opcodes.ASTORE)return value;if(!seen.add(producer))return null;value=input(producer,0);}return value;
		}
		String signature(SourceValue value) { return signature(value, new HashSet<>(), 0); }
		private String signature(SourceValue value, Set<AbstractInsnNode> visited, int depth) {
			if (value == null || value.insns.isEmpty() || depth > 24) return null;
			if (value.insns.size() != 1) {
				// A phi is a set of value AND incoming-condition pairs, not merely a set of possible values.
				// Swapping the assignments on two branches must change its identity.
				List<String> alternatives = new ArrayList<>();
				for (AbstractInsnNode producer : value.insns) {
					String result = signature(new SourceValue(value.getSize(), producer), visited, depth + 1);
					String guard = incomingGuard(producer, visited, depth + 1);
					if (result == null || guard == null) return null;
					alternatives.add(guard + "=>" + result);
				}
				alternatives.sort(String::compareTo);
				return "phi{" + String.join(";", alternatives) + "}";
			}
			AbstractInsnNode insn = value.insns.iterator().next(); if (!visited.add(insn)) return null;
			try {
				if (insn instanceof Parameter parameter) return parameter.key;
				if (insn instanceof VarInsnNode store && store.getOpcode() >= Opcodes.ISTORE && store.getOpcode() <= Opcodes.ASTORE)
					return signature(input(store,0),visited,depth+1);
				if (insn instanceof FieldInsnNode field) {
					String receiver = field.getOpcode() == Opcodes.GETSTATIC ? "static" : signature(input(field,0),visited,depth+1);
					return receiver == null ? null : "field:" + field.owner + ":" + field.name + field.desc + "(" + receiver + ")";
				}
				if (insn instanceof MethodInsnNode call) {
					List<String> inputs = new ArrayList<>(); int count = Type.getArgumentTypes(call.desc).length + (call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1);
					for (int i=count-1;i>=0;i--) { String argument=signature(input(call,i),visited,depth+1); if(argument==null)return null;inputs.add(argument); }
					return "call:" + call.owner + ":" + call.name + call.desc + "(" + String.join(",",inputs) + ")";
				}
				if (insn instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST) { String inner=signature(input(cast,0),visited,depth+1);return inner==null?null:"cast:"+cast.desc+"("+inner+")"; }
				if(insn.getOpcode()>=Opcodes.IALOAD&&insn.getOpcode()<=Opcodes.SALOAD){
					SourceValue array=unstore(input(insn,1));String index=signature(input(insn,0),visited,depth+1);
					if(array==null||array.insns.size()!=1||index==null||!(array.insns.iterator().next() instanceof FieldInsnNode field)||field.getOpcode()!=Opcodes.GETSTATIC)return null;
					ClassNode owner=classes.apply(field.owner);if(owner==null)return null;MethodNode initializer=owner.methods.stream().filter(m->m.name.equals("<clinit>")).findFirst().orElse(null);if(initializer==null)return null;
					return "array:"+field.owner+":"+field.name+field.desc+":"+DefinedMethodContracts.fingerprint(initializer)+"["+index+"]";
				}
				if (insn instanceof TypeInsnNode allocation && allocation.getOpcode() == Opcodes.NEW) {
					// An allocation is aligned only when its type occurs once and its unique constructor's complete
					// argument provenance matches; a type name alone cannot identify one of several mutable objects.
					long allocations = code(method).stream().filter(n -> n instanceof TypeInsnNode t && t.getOpcode() == Opcodes.NEW && t.desc.equals(allocation.desc)).count();
					if (allocations != 1) return null;
					List<MethodInsnNode> constructors = new ArrayList<>();
					for (var n : method.instructions) if (n instanceof MethodInsnNode c && c.getOpcode() == Opcodes.INVOKESPECIAL && c.owner.equals(allocation.desc) && c.name.equals("<init>")) {
						SourceValue receiver = input(c, Type.getArgumentTypes(c.desc).length);
						if (receiver.insns.equals(Set.of(allocation))) constructors.add(c);
					}
					if (constructors.size() != 1) return null;
					MethodInsnNode constructor = constructors.getFirst(); List<String> inputs = new ArrayList<>(); int count = Type.getArgumentTypes(constructor.desc).length;
					for (int i = count - 1; i >= 0; i--) { String argument = signature(input(constructor,i),visited,depth+1); if (argument == null) return null; inputs.add(argument); }
					return "allocation:" + allocation.desc + constructor.desc + "(" + String.join(",",inputs) + ")";
				}
				if (insn instanceof LdcInsnNode constant) return "constant:" + constant.cst;
				if (insn.getOpcode() >= Opcodes.ICONST_M1 && insn.getOpcode() <= Opcodes.DCONST_1) return "constant-op:" + insn.getOpcode();
				return null;
			} finally { visited.remove(insn); }
		}

		private String incomingGuard(AbstractInsnNode producer, Set<AbstractInsnNode> visited, int depth) {
			List<AbstractInsnNode> operations = code(method);
			Map<AbstractInsnNode,Integer> indexes = new java.util.IdentityHashMap<>();
			for (int i=0;i<operations.size();i++) indexes.put(operations.get(i),i);
			Set<Integer> leaders = new java.util.TreeSet<>(); leaders.add(0);
			for(int i=0;i<operations.size();i++){
				AbstractInsnNode operation=operations.get(i);
				if(operation instanceof TableSwitchInsnNode table){Integer target=indexes.get(next(table.dflt));if(target==null)return null;leaders.add(target);for(LabelNode label:table.labels){target=indexes.get(next(label));if(target==null)return null;leaders.add(target);}if(i+1<operations.size())leaders.add(i+1);}
				else if(operation instanceof LookupSwitchInsnNode lookup){Integer target=indexes.get(next(lookup.dflt));if(target==null)return null;leaders.add(target);for(LabelNode label:lookup.labels){target=indexes.get(next(label));if(target==null)return null;leaders.add(target);}if(i+1<operations.size())leaders.add(i+1);}
				if(operation instanceof JumpInsnNode jump){Integer target=indexes.get(next(jump.label));if(target==null)return null;leaders.add(target);if(i+1<operations.size())leaders.add(i+1);}
				else if(operation.getOpcode()>=Opcodes.IRETURN&&operation.getOpcode()<=Opcodes.RETURN&&i+1<operations.size())leaders.add(i+1);
			}
			List<Integer> starts=new ArrayList<>(leaders);Map<Integer,Integer> containing=new HashMap<>();
			for(int b=0;b<starts.size();b++)for(int i=starts.get(b);i<(b+1<starts.size()?starts.get(b+1):operations.size());i++)containing.put(i,b);
			record Edge(int from,AbstractInsnNode condition,String outcome){}
			Map<Integer,List<Edge>> predecessors=new HashMap<>();
			for(int b=0;b<starts.size();b++){
				int end=(b+1<starts.size()?starts.get(b+1):operations.size())-1;AbstractInsnNode terminal=operations.get(end);
				if(terminal instanceof JumpInsnNode jump){int target=containing.get(indexes.get(next(jump.label)));predecessors.computeIfAbsent(target,k->new ArrayList<>()).add(new Edge(b,jump,"taken"));
					if(jump.getOpcode()!=Opcodes.GOTO&&b+1<starts.size())predecessors.computeIfAbsent(b+1,k->new ArrayList<>()).add(new Edge(b,jump,"fallthrough"));
				}else if(terminal instanceof TableSwitchInsnNode table){predecessors.computeIfAbsent(containing.get(indexes.get(next(table.dflt))),k->new ArrayList<>()).add(new Edge(b,table,"default"));for(int i=0;i<table.labels.size();i++)predecessors.computeIfAbsent(containing.get(indexes.get(next(table.labels.get(i)))),k->new ArrayList<>()).add(new Edge(b,table,"case="+(table.min+i)));}
				else if(terminal instanceof LookupSwitchInsnNode lookup){predecessors.computeIfAbsent(containing.get(indexes.get(next(lookup.dflt))),k->new ArrayList<>()).add(new Edge(b,lookup,"default"));for(int i=0;i<lookup.labels.size();i++)predecessors.computeIfAbsent(containing.get(indexes.get(next(lookup.labels.get(i)))),k->new ArrayList<>()).add(new Edge(b,lookup,"case="+lookup.keys.get(i)));}
				else if(!(terminal.getOpcode()>=Opcodes.IRETURN&&terminal.getOpcode()<=Opcodes.RETURN)&&terminal.getOpcode()!=Opcodes.ATHROW&&b+1<starts.size())predecessors.computeIfAbsent(b+1,k->new ArrayList<>()).add(new Edge(b,null,"next"));
			}
			Integer at=indexes.get(producer);if(at==null)return null;int block=containing.get(at);Set<Integer>seen=new HashSet<>();
			while(seen.add(block)){
				List<Edge> edges=predecessors.getOrDefault(block,List.of());if(edges.size()!=1)return null;Edge edge=edges.getFirst();
				if(edge.condition()!=null&&edge.condition().getOpcode()!=Opcodes.GOTO){
					int opcode=edge.condition().getOpcode();int inputs=opcode>=Opcodes.IF_ICMPEQ&&opcode<=Opcodes.IF_ACMPNE?2:1;List<String>values=new ArrayList<>();
					for(int i=inputs-1;i>=0;i--){String v=signature(input(edge.condition(),i),visited,depth+1);if(v==null)return null;values.add(v);}
					return "guard:"+opcode+":"+edge.outcome()+"("+String.join(",",values)+")";
				}block=edge.from();
			}
			return null;
		}
	}

	private static Decl resolve(Member member, Function<String, ClassNode> classes, Set<String> seen) {
		Lookup result=lookup(member,classes,seen);return result.known()?result.declaration():null;
	}
	private static Lookup lookup(Member member,Function<String,ClassNode>classes,Set<String>seen){
		if(!seen.add(member.owner()))return new Lookup(null,false);ClassNode owner=classes.apply(member.owner());if(owner==null)return new Lookup(null,false);
		for(MethodNode method:owner.methods)if(method.name.equals(member.name())&&method.desc.equals(member.desc()))return new Lookup(
				concreteVirtual(method)?new Decl(owner.name,method):null,concreteVirtual(method));
		if((owner.access&Opcodes.ACC_INTERFACE)==0&&owner.superName!=null){Lookup parent=lookup(new Member(owner.superName,member.name(),member.desc()),classes,new HashSet<>(seen));if(!parent.known()||parent.declaration()!=null)return parent;}
		List<Decl>found=new ArrayList<>();for(String iface:owner.interfaces){Lookup candidate=lookup(new Member(iface,member.name(),member.desc()),classes,new HashSet<>(seen));if(!candidate.known())return candidate;if(candidate.declaration()!=null&&found.stream().noneMatch(d->d.owner().equals(candidate.declaration().owner())))found.add(candidate.declaration());}
		return new Lookup(found.size()==1?found.getFirst():null,found.size()<=1);
	}
	private static boolean concreteVirtual(MethodNode method){return(method.access&(Opcodes.ACC_STATIC|Opcodes.ACC_ABSTRACT|Opcodes.ACC_NATIVE|Opcodes.ACC_SYNCHRONIZED))==0;}
	private static FieldDecl field(String owner,String name,String desc,Function<String,ClassNode> classes,Set<String> seen) {
		if(!seen.add(owner))return null;ClassNode node=classes.apply(owner);if(node==null)return null;
		for(FieldNode field:node.fields)if(field.name.equals(name)&&field.desc.equals(desc))return new FieldDecl(owner,field);
		return node.superName==null?null:field(node.superName,name,desc,classes,seen);
	}
	private static Map<Integer,Integer> argumentSlots(Type[] types,boolean isStatic){Map<Integer,Integer>slots=new HashMap<>();int slot=isStatic?0:1;for(int i=0;i<types.length;i++){slots.put(slot,i);slot+=types[i].getSize();}return slots;}
	private static Expr pop(List<Expr> stack){return stack.removeLast();}
	private static List<AbstractInsnNode> code(MethodNode method){List<AbstractInsnNode>out=new ArrayList<>();for(var insn:method.instructions)if(insn.getOpcode()>=0)out.add(insn);return out;}
	private static AbstractInsnNode next(AbstractInsnNode insn){if(insn==null)return null;for(var n=insn.getNext();n!=null;n=n.getNext())if(n.getOpcode()>=0)return n;return null;}
	private static JumpInsnNode branchAfter(AbstractInsnNode insn){AbstractInsnNode next=next(insn);return next instanceof JumpInsnNode jump&&(jump.getOpcode()==Opcodes.IFEQ||jump.getOpcode()==Opcodes.IFNE)?jump:null;}
	private static List<TypeInsnNode> atoms(MethodNode method,String type){List<TypeInsnNode>result=new ArrayList<>();for(var insn:method.instructions)if(insn instanceof TypeInsnNode atom&&atom.getOpcode()==Opcodes.INSTANCEOF&&atom.desc.equals(type))result.add(atom);return result;}
	private static LocalVariableNode capture(MethodNode method,AbstractInsnNode at,String name,String desc){if(method.localVariables==null)return null;List<LocalVariableNode>matches=method.localVariables.stream().filter(l->l.name.equals(name)&&l.desc.equals(desc)&&method.instructions.indexOf(l.start)<=method.instructions.indexOf(at)&&method.instructions.indexOf(at)<method.instructions.indexOf(l.end)).toList();return matches.size()==1?matches.getFirst():null;}
	private static boolean annotated(MethodNode method,String desc){return annotations(method).stream().anyMatch(a->a.desc.equals(desc));}
	private static List<AnnotationNode> annotations(MethodNode method){List<AnnotationNode>all=new ArrayList<>();if(method.visibleAnnotations!=null)all.addAll(method.visibleAnnotations);if(method.invisibleAnnotations!=null)all.addAll(method.invisibleAnnotations);return all;}
	private static String expressionType(MethodNode handler){
		List<AnnotationNode>expressions=annotations(handler).stream().filter(a->a.desc.equals(EXPRESSION)).toList();if(expressions.size()!=1)return null;List<String>values=MixinFit.stringList(MixinFit.value(expressions.getFirst(),"value"));if(values.size()!=1)return null;
		var matcher=java.util.regex.Pattern.compile("\\?\\s+instanceof\\s+([\\w$]+)").matcher(values.getFirst().trim());if(!matcher.matches())return null;String id=matcher.group(1);
		List<AnnotationNode>defs=annotations(handler).stream().filter(a->a.desc.equals(DEFINITION)&&id.equals(MixinFit.value(a,"id"))).toList();if(defs.size()!=1)return null;Object types=MixinFit.value(defs.getFirst(),"type");return types instanceof List<?> list&&list.size()==1&&list.getFirst() instanceof Type type&&type.getSort()==Type.OBJECT?type.getInternalName():null;
	}
	private static String namedCapture(MethodNode handler,int parameter){List<AnnotationNode>all=new ArrayList<>();for(List<AnnotationNode>[]sets:List.of(nonNull(handler.visibleParameterAnnotations),nonNull(handler.invisibleParameterAnnotations)))if(parameter<sets.length&&sets[parameter]!=null)all.addAll(sets[parameter]);if(all.size()!=1||!LOCAL.equals(all.getFirst().desc))return null;List<String>names=MixinFit.stringList(MixinFit.value(all.getFirst(),"name"));return names.size()==1&&MixinFit.value(all.getFirst(),"ordinal")==null&&MixinFit.value(all.getFirst(),"index")==null?names.getFirst():null;}
	@SuppressWarnings("unchecked") private static List<AnnotationNode>[] nonNull(List<AnnotationNode>[]value){return value==null?new List[0]:value;}

	private static MethodNode wrap(ClassNode mixin,MethodNode handler,AnnotationNode injector,Plan plan){
		String name=handler.name,aside=MixinHandlerShim.asideName(mixin.name,name,"$forbricpredicate");boolean isStatic=(handler.access&Opcodes.ACC_STATIC)!=0;
		Type[] arguments=Type.getArgumentTypes(plan.call().desc);List<Type>parameters=new ArrayList<>();parameters.add(Type.getObjectType(plan.call().owner));parameters.addAll(List.of(arguments));parameters.add(Type.getObjectType(OPERATION));
		MethodNode wrapper=new MethodNode(handler.access,name,Type.getMethodDescriptor(Type.BOOLEAN_TYPE,parameters.toArray(Type[]::new)),null,handler.exceptions.toArray(String[]::new));
		AnnotationNode wrap=new AnnotationNode(WRAP);wrap.values=new ArrayList<>(injector.values);AnnotationNode at=new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");at.values=new ArrayList<>(List.of("value","INVOKE","target","L"+plan.call().owner+";"+plan.call().name+plan.call().desc));set(wrap,"at",at);
		wrapper.visibleAnnotations=transportAnnotations(handler.visibleAnnotations,injector,wrap);wrapper.invisibleAnnotations=transportAnnotations(handler.invisibleAnnotations,injector,wrap);
		int slot=isStatic?0:1;int[]slots=new int[parameters.size()];for(int i=0;i<parameters.size();i++){slots[i]=slot;slot+=parameters.get(i).getSize();}
		wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD,slots[slots.length-1]));push(wrapper,parameters.size()-1);wrapper.instructions.add(new TypeInsnNode(Opcodes.ANEWARRAY,"java/lang/Object"));
		for(int i=0;i<parameters.size()-1;i++){wrapper.instructions.add(new InsnNode(Opcodes.DUP));push(wrapper,i);wrapper.instructions.add(new VarInsnNode(parameters.get(i).getOpcode(Opcodes.ILOAD),slots[i]));box(wrapper,parameters.get(i));wrapper.instructions.add(new InsnNode(Opcodes.AASTORE));}
		wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,OPERATION,"call","([Ljava/lang/Object;)Ljava/lang/Object;",true));wrapper.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST,"java/lang/Boolean"));wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,"java/lang/Boolean","booleanValue","()Z",false));
		wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD,slots[0]));wrapper.instructions.add(new LdcInsnNode(plan.key()));if(!isStatic)wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));
		wrapper.instructions.add(new InvokeDynamicInsnNode("modify",isStatic?"()L"+MODIFIER+";":"(L"+mixin.name+";)L"+MODIFIER+";",LAMBDA,
				Type.getMethodType("(ZLjava/lang/Object;)Z"),new Handle(isStatic?Opcodes.H_INVOKESTATIC:Opcodes.H_INVOKESPECIAL,mixin.name,aside,handler.desc,(mixin.access&Opcodes.ACC_INTERFACE)!=0),Type.getMethodType(handler.desc)));
		wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,RUNTIME,"modifyDefault","(ZLjava/lang/Object;Ljava/lang/String;L"+MODIFIER+";)Z",false));wrapper.instructions.add(new InsnNode(Opcodes.IRETURN));wrapper.maxLocals=slot;wrapper.maxStack=10+slot;
		handler.name=aside;handler.visibleAnnotations=without(handler.visibleAnnotations,injector);handler.invisibleAnnotations=without(handler.invisibleAnnotations,injector);handler.visibleParameterAnnotations=null;handler.invisibleParameterAnnotations=null;return wrapper;
	}
	private static List<AnnotationNode>transportAnnotations(List<AnnotationNode>annotations,AnnotationNode original,AnnotationNode replacement){if(annotations==null)return null;List<AnnotationNode>out=new ArrayList<>();for(AnnotationNode a:annotations)if(a==original)out.add(replacement);else if(!a.desc.equals(EXPRESSION)&&!a.desc.equals(DEFINITION))out.add(a);return out;}
	private static List<AnnotationNode>without(List<AnnotationNode>annotations,AnnotationNode original){
		if(annotations==null)return null;List<AnnotationNode>remaining=new ArrayList<>();
		for(AnnotationNode annotation:annotations)if(annotation!=original&&!annotation.desc.equals(EXPRESSION)&&!annotation.desc.equals(DEFINITION))remaining.add(annotation);
		return remaining;
	}
	private static void set(AnnotationNode annotation,String key,Object value){for(int i=0;i<annotation.values.size();i+=2)if(key.equals(annotation.values.get(i))){annotation.values.set(i+1,value);return;}annotation.values.add(key);annotation.values.add(value);}
	private static void push(MethodNode node,int value){if(value<=5)node.instructions.add(new InsnNode(Opcodes.ICONST_0+value));else node.instructions.add(new IntInsnNode(Opcodes.BIPUSH,value));}
	private static void box(MethodNode node,Type type){String owner=switch(type.getSort()){case Type.BOOLEAN->"java/lang/Boolean";case Type.BYTE->"java/lang/Byte";case Type.CHAR->"java/lang/Character";case Type.SHORT->"java/lang/Short";case Type.INT->"java/lang/Integer";case Type.FLOAT->"java/lang/Float";case Type.LONG->"java/lang/Long";case Type.DOUBLE->"java/lang/Double";default->null;};if(owner!=null)node.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,owner,"valueOf","("+type.getDescriptor()+")L"+owner+";",false));}
}
