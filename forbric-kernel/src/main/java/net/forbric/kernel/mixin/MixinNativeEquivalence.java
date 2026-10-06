/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

/**
 * A deliberately small, structural native-equivalence proof. The original handler must be one delegation
 * returning its result. The final selected method must contain that operation with the same receiver and
 * every argument, and consume its result. Its surrounding control flow belongs to the carrier and is retained.
 * A same-named invocation somewhere in a larger method is not a proof. No game or guest identity is listed.
 *
 * <p>Only precisely bound {@code @Local} captures and the target instance can supply handler operands.
 * Shared references, opaque helper calls, branches, writes, discarded results and ambiguous selectors remain
 * unproved. In particular, a copied shared value cannot borrow a proof about the live original value.
 * This class observes bytecode; it never changes an injector's requirement or its implementation.
 */
final class MixinNativeEquivalence {
    private static final String REDIRECT = "Lorg/spongepowered/asm/mixin/injection/Redirect;";
    private static final String WRAP = "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";
    private static final String LOCAL = "Lcom/llamalad7/mixinextras/sugar/Local;";
    private static final String LOCAL_REF = "com/llamalad7/mixinextras/sugar/ref/LocalRef";
    private static final Map<String, Plan> PLANS = new ConcurrentHashMap<>();

    private MixinNativeEquivalence() { }

    private sealed interface Expression permits Slot, Literal, Cast, Call, Origin { }
    private record Slot(int index, String type, boolean referenceRead) implements Expression { }
    private record Literal(Object value) implements Expression { }
    private record Cast(String type, Expression value) implements Expression { }
    private record Member(int opcode, String owner, String name, String descriptor, boolean itf) { }
    private record Call(Member member, List<Expression> operands) implements Expression { }
    /** SSA origin, compared by instruction identity: repeated field/helper reads are never silently equated. */
    private record Origin(AbstractInsnNode instruction) implements Expression { }
    private record Value(Expression expression, String type) { }
    private record Binding(Integer index, List<String> names, Integer ordinal, boolean argsOnly) { }
    private record Body(Value returned, Call call, MethodInsnNode instruction, Map<Integer, Value> locals) { }
    private record Plan(Set<String> targets, List<String> selectors, String fingerprint,
                        MethodNode original, Map<Integer, Binding> bindings, Body body) { }

    static void reset() { PLANS.clear(); }

    /** Called on the original adapter output which the final-application ledger also remembers. */
    static void remember(ClassNode mixin) {
        String prefix = mixin.name.replace('/', '.') + "#";
        PLANS.keySet().removeIf(key -> key.startsWith(prefix));
        Set<String> targets = new LinkedHashSet<>(MixinFit.mixinTargets(mixin));
        for (MethodNode handler : mixin.methods) {
            AnnotationNode injection = injection(handler);
            if (injection == null) continue;
            List<String> selectors = strings(value(injection, "method"));
            Map<Integer, Binding> bindings = bindings(handler);
            Body body = body(handler, mixin.name, bindings, true);
            if (selectors.isEmpty() || body == null || targets.isEmpty()) continue;
            PLANS.put(prefix + handler.name + handler.desc, new Plan(Set.copyOf(targets), selectors,
                    MixinInstructionFingerprint.hash(handler), handler, bindings, body));
        }
    }

    static boolean needsFingerprint(MethodNode handler) {
        return injection(handler) != null && body(handler, "original/Owner", bindings(handler), true) != null;
    }

    static String proof(String mixin, String name, String descriptor, String fingerprint, ClassNode target) {
        Plan plan = PLANS.get(mixin.replace('/', '.') + "#" + name + descriptor);
        if (plan == null || !plan.fingerprint().equals(fingerprint)
                || plan.targets().stream().noneMatch(t -> t.replace('.', '/').equals(target.name))) return null;
        List<MethodNode> selected = target.methods.stream()
                .filter(method -> plan.selectors().stream().anyMatch(selector -> selects(selector, target.name, method)))
                .toList();
        if (selected.size() != 1) return null;
        MethodNode method = selected.getFirst();
        if (knownDead(target, method)) return null;
        Body nativeBody = nativeRegion(method, target.name, plan.body().call().member());
        if (nativeBody == null) return null;
        Value expected = bind(plan.body().returned(), plan, method, nativeBody, target.name);
        if (expected == null || !expected.equals(nativeBody.returned())) return null;
        return "The final selected method already executes the original pure handler's exact operation, with "
                + "the same receiver and every argument bound from its original capture declarations; its unique "
                + "reachable invocation has a consumed result. Surrounding carrier control flow is retained";
    }

    private static Value bind(Value original, Plan plan, MethodNode host, Body nativeBody, String owner) {
        return bind(original.expression(), original.type(), plan, host, nativeBody, owner);
    }

    private static Value bind(Expression expression, String type, Plan plan, MethodNode host,
                              Body nativeBody, String owner) {
        if (expression instanceof Slot slot) {
            Binding binding = plan.bindings().get(slot.index());
            if (binding != null) return captured(binding, slot.type(), host, nativeBody);
            // The handler's instance is the actual target instance; no unannotated operation parameter is guessed.
            if (slot.index() == 0 && (plan.original().access & Opcodes.ACC_STATIC) == 0
                    && !slot.referenceRead() && (host.access & Opcodes.ACC_STATIC) == 0)
                return new Value(new Slot(0, "L" + owner + ";", false), "L" + owner + ";");
            return null;
        }
        if (expression instanceof Literal literal) return new Value(literal, type);
        if (expression instanceof Cast cast) {
            Value nested = bind(cast.value(), "L" + cast.type() + ";", plan, host, nativeBody, owner);
            if (nested == null) return null;
            // A cast on a precisely typed local is redundant; an uncertain reference is not widened by guessing.
            String castType = "L" + cast.type() + ";";
            return castType.equals(nested.type()) ? nested : new Value(new Cast(cast.type(), nested.expression()), castType);
        }
        if (!(expression instanceof Call call)) return null;
        List<Expression> operands = new ArrayList<>();
        Type[] arguments = Type.getArgumentTypes(call.member().descriptor());
        int receiver = call.member().opcode() == Opcodes.INVOKESTATIC ? 0 : 1;
        for (int index = 0; index < call.operands().size(); index++) {
            String operandType = index < receiver ? "L" + call.member().owner() + ";"
                    : arguments[index - receiver].getDescriptor();
            Value mapped = bind(call.operands().get(index), operandType, plan, host, nativeBody, owner);
            if (mapped == null) return null;
            operands.add(mapped.expression());
        }
        return new Value(new Call(call.member(), List.copyOf(operands)), type);
    }

    private static Value captured(Binding binding, String expectedType, MethodNode host, Body nativeBody) {
        if (binding.index() != null) {
            Value captured = nativeBody.locals().get(binding.index());
            return captured != null && captured.type().equals(expectedType) ? captured : null;
        }
        int position = host.instructions.indexOf(nativeBody.instruction());
        Set<Integer> slots = new LinkedHashSet<>();
        if (!binding.names().isEmpty()) {
            if (host.localVariables == null) return null;
            for (LocalVariableNode local : host.localVariables) {
                if (binding.names().contains(local.name) && local.desc.equals(expectedType)
                        && host.instructions.indexOf(local.start) <= position
                        && position < host.instructions.indexOf(local.end)) slots.add(local.index);
            }
        } else {
            Set<Integer> arguments = argumentSlots(host);
            for (var entry : nativeBody.locals().entrySet())
                if (entry.getValue().type().equals(expectedType)
                        && (!binding.argsOnly() || arguments.contains(entry.getKey()))) slots.add(entry.getKey());
        }
        List<Integer> ordered = slots.stream().sorted().toList();
        Integer ordinal = binding.ordinal();
        if (ordinal != null) return ordinal >= 0 && ordinal < ordered.size()
                ? nativeBody.locals().get(ordered.get(ordinal)) : null;
        return ordered.size() == 1 ? nativeBody.locals().get(ordered.getFirst()) : null;
    }

    private static boolean knownDead(ClassNode target, MethodNode method) {
        if (!MergedBaseUncalledMethods.lists(target.name) && !CarrierRenames.listsUncalled(target.name)) return false;
        // Ownership is not needed to accept a proof: any established dead route is reason to refuse it.
        for (var ecosystem : net.forbric.api.Ecosystem.values()) {
            if (MergedBaseUncalledMethods.neverRuns(target, method, ecosystem) != null
                    || CarrierRenames.neverRuns(target, method, ecosystem) != null) return true;
        }
        return false;
    }

    /** Finds the original operation in a live region, carrying exact SSA operand identities through aliases. */
    private static Body nativeRegion(MethodNode method, String owner, Member wanted) {
        List<MethodInsnNode> candidates = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions)
            if (instruction instanceof MethodInsnNode call && member(call).equals(wanted)) candidates.add(call);
        if (candidates.size() != 1) return null;
        MethodInsnNode call = candidates.getFirst();
        Frame<BasicValue>[] frames;
        try { frames = new Analyzer<BasicValue>(new FlowInterpreter()).analyze(owner, method); }
        catch (AnalyzerException | RuntimeException malformed) { return null; }
        int position = method.instructions.indexOf(call);
        Frame<BasicValue> frame = frames[position];
        if (frame == null) return null;
        int operands = Type.getArgumentTypes(call.desc).length + (call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1);
        if (frame.getStackSize() < operands) return null;
        List<Expression> expressions = new ArrayList<>();
        for (int index = frame.getStackSize() - operands; index < frame.getStackSize(); index++) {
            Value value = flow(frame.getStack(index));
            if (value == null) return null;
            expressions.add(value.expression());
        }
        Call operation = new Call(wanted, List.copyOf(expressions));
        Value returned = new Value(operation, Type.getReturnType(call.desc).getDescriptor());
        AbstractInsnNode next = call.getNext();
        while (next != null && next.getOpcode() < 0) next = next.getNext();
        if (next instanceof TypeInsnNode cast && next.getOpcode() == Opcodes.CHECKCAST) {
            String type = "L" + cast.desc + ";";
            if (!type.equals(returned.type())) returned = new Value(new Cast(cast.desc, operation), type);
        }
        if (!consumed(method, frames, call)) return null;
        Map<Integer, Value> locals = new HashMap<>();
        for (int index = 0; index < frame.getLocals(); index++) {
            Value value = flow(frame.getLocal(index));
            if (value != null) locals.put(index, value);
        }
        return new Body(returned, operation, call, Map.copyOf(locals));
    }

    private static Member member(MethodInsnNode call) {
        return new Member(call.getOpcode(), call.owner, call.name, call.desc, call.itf);
    }

    private static Value flow(BasicValue value) {
        return value instanceof FlowValue flow && flow.expression != null && flow.getType() != null
                ? new Value(flow.expression, flow.getType().getDescriptor()) : null;
    }

    private static boolean containsOrigin(Expression expression, MethodInsnNode call) {
        return expression instanceof Origin origin && origin.instruction() == call
                || expression instanceof Cast cast && containsOrigin(cast.value(), call);
    }

    private static boolean consumed(MethodNode method, Frame<BasicValue>[] frames, MethodInsnNode call) {
        int index = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            Frame<BasicValue> frame = frames[index++];
            int opcode = instruction.getOpcode();
            if (frame == null || opcode < 0 || instruction == call) continue;
            // Copies and stores preserve a value but do not establish its use. An ignored result stays unproved.
            if (instruction instanceof VarInsnNode || opcode == Opcodes.NOP || opcode == Opcodes.POP
                    || opcode == Opcodes.POP2 || opcode >= Opcodes.DUP && opcode <= Opcodes.SWAP
                    || opcode == Opcodes.CHECKCAST) continue;
            int inputs = inputs(instruction);
            if (inputs < 1 || frame.getStackSize() < inputs) continue;
            for (int slot = frame.getStackSize() - inputs; slot < frame.getStackSize(); slot++) {
                Value value = flow(frame.getStack(slot));
                if (value != null && containsOrigin(value.expression(), call)) return true;
            }
        }
        return false;
    }

    private static int inputs(AbstractInsnNode instruction) {
        int opcode = instruction.getOpcode();
        if (instruction instanceof MethodInsnNode call)
            return Type.getArgumentTypes(call.desc).length + (opcode == Opcodes.INVOKESTATIC ? 0 : 1);
        if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.ARETURN || opcode == Opcodes.ATHROW
                || opcode >= Opcodes.IFEQ && opcode <= Opcodes.IFLE || opcode == Opcodes.IFNULL
                || opcode == Opcodes.IFNONNULL || opcode == Opcodes.PUTSTATIC) return 1;
        if (opcode >= Opcodes.IF_ICMPEQ && opcode <= Opcodes.IF_ACMPNE || opcode == Opcodes.PUTFIELD) return 2;
        // A calculation or read whose result is discarded is not an effect witness. Do not infer one from it.
        if (opcode >= Opcodes.IASTORE && opcode <= Opcodes.SASTORE) return 3;
        return 0;
    }

    private static final class FlowValue extends BasicValue {
        private final Expression expression;
        FlowValue(Type type, Expression expression) { super(type); this.expression = expression; }
        @Override public boolean equals(Object other) {
            return other instanceof FlowValue value && java.util.Objects.equals(getType(), value.getType())
                    && java.util.Objects.equals(expression, value.expression);
        }
        @Override public int hashCode() { return java.util.Objects.hash(getType(), expression); }
    }

    /** No type resolution or name heuristics: same SSA origin survives a copy; a merge of values is unknown. */
    private static final class FlowInterpreter extends BasicInterpreter {
        FlowInterpreter() { super(Opcodes.ASM9); }
        @Override public BasicValue newValue(Type type) {
            return type == Type.VOID_TYPE ? null : new FlowValue(type, null);
        }
        @Override public BasicValue newParameterValue(boolean instance, int local, Type type) {
            return new FlowValue(type, new Slot(local, type.getDescriptor(), false));
        }
        private BasicValue produced(BasicValue value, AbstractInsnNode instruction) {
            return value == null ? null : new FlowValue(value.getType(), new Origin(instruction));
        }
        @Override public BasicValue newOperation(AbstractInsnNode instruction) throws AnalyzerException {
            BasicValue value = super.newOperation(instruction);
            int opcode = instruction.getOpcode();
            if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5)
                return new FlowValue(value.getType(), new Literal(opcode - Opcodes.ICONST_0));
            if (instruction instanceof LdcInsnNode literal && !(literal.cst instanceof Type))
                return new FlowValue(value.getType(), new Literal(literal.cst));
            return produced(value, instruction);
        }
        @Override public BasicValue copyOperation(AbstractInsnNode instruction, BasicValue value) { return value; }
        @Override public BasicValue unaryOperation(AbstractInsnNode instruction, BasicValue value) throws AnalyzerException {
            BasicValue result = super.unaryOperation(instruction, value);
            if (instruction instanceof TypeInsnNode cast && instruction.getOpcode() == Opcodes.CHECKCAST
                    && value instanceof FlowValue flow) {
                Expression expression = flow.expression;
                if (!java.util.Objects.equals(value.getType(), result.getType())) expression = new Cast(cast.desc, expression);
                return new FlowValue(result.getType(), expression);
            }
            return produced(result, instruction);
        }
        @Override public BasicValue binaryOperation(AbstractInsnNode instruction, BasicValue left, BasicValue right) throws AnalyzerException {
            return produced(super.binaryOperation(instruction, left, right), instruction);
        }
        @Override public BasicValue ternaryOperation(AbstractInsnNode instruction, BasicValue a, BasicValue b, BasicValue c) throws AnalyzerException {
            return produced(super.ternaryOperation(instruction, a, b, c), instruction);
        }
        @Override public BasicValue naryOperation(AbstractInsnNode instruction, List<? extends BasicValue> values) throws AnalyzerException {
            return produced(super.naryOperation(instruction, values), instruction);
        }
        @Override public BasicValue merge(BasicValue left, BasicValue right) {
            if (left.equals(right)) return left;
            BasicValue type = super.merge(left, right);
            return new FlowValue(type.getType(), null);
        }
    }

    /** A symbolic, straight-line evaluator. Its unsupported cases fail closed rather than losing effects. */
    private static Body body(MethodNode method, String owner, Map<Integer, Binding> bindings, boolean handler) {
        if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()) return null;
        Map<Integer, Value> locals = new HashMap<>();
        int slot = 0;
        if ((method.access & Opcodes.ACC_STATIC) == 0) {
            locals.put(slot, new Value(new Slot(slot, "L" + owner + ";", false), "L" + owner + ";")); slot++;
        }
        for (Type argument : Type.getArgumentTypes(method.desc)) {
            locals.put(slot, new Value(new Slot(slot, argument.getDescriptor(), false), argument.getDescriptor()));
            slot += argument.getSize();
        }
        List<Value> stack = new ArrayList<>();
        Call operation = null;
        MethodInsnNode operationInstruction = null;
        Map<Integer, Value> operationLocals = null;
        boolean returned = false;
        Value result = null;
        try {
            for (AbstractInsnNode instruction : method.instructions) {
                int opcode = instruction.getOpcode();
                if (opcode < 0 || opcode == Opcodes.NOP) continue;
                if (returned) return null;
                if (instruction instanceof VarInsnNode variable) {
                    if (opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD) {
                        Value value = locals.get(variable.var); if (value == null) return null; stack.add(value);
                    } else if (opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE) locals.put(variable.var, pop(stack));
                    else return null;
                } else if (instruction instanceof TypeInsnNode cast && opcode == Opcodes.CHECKCAST) {
                    Value value = pop(stack); String castType = "L" + cast.desc + ";";
                    Expression expression = value.expression();
                    if (expression instanceof Slot local && local.referenceRead())
                        expression = new Slot(local.index(), castType, true);
                    else if (!castType.equals(value.type())) expression = new Cast(cast.desc, expression);
                    stack.add(new Value(expression, castType));
                } else if (instruction instanceof MethodInsnNode call) {
                    Type[] parameters = Type.getArgumentTypes(call.desc);
                    List<Value> operands = new ArrayList<>();
                    for (int index = parameters.length - 1; index >= 0; index--) operands.addFirst(pop(stack));
                    if (opcode != Opcodes.INVOKESTATIC) operands.addFirst(pop(stack));
                    Type returnType = Type.getReturnType(call.desc);
                    boolean captureRead = handler && call.owner.equals(LOCAL_REF) && call.name.equals("get")
                            && call.desc.equals("()Ljava/lang/Object;") && operands.size() == 1
                            && operands.getFirst().expression() instanceof Slot local && bindings.containsKey(local.index());
                    if (captureRead) {
                        Slot local = (Slot) operands.getFirst().expression();
                        stack.add(new Value(new Slot(local.index(), returnType.getDescriptor(), true), returnType.getDescriptor()));
                    } else {
                        if (operation != null || returnType.getSort() == Type.VOID || call.name.equals("<init>")) return null;
                        operation = new Call(new Member(opcode, call.owner, call.name, call.desc, call.itf),
                                operands.stream().map(Value::expression).toList());
                        operationInstruction = call; operationLocals = new HashMap<>(locals);
                        stack.add(new Value(operation, returnType.getDescriptor()));
                    }
                } else if (instruction instanceof LdcInsnNode literal) {
                    String type = literal.cst instanceof Integer ? "I" : literal.cst instanceof Long ? "J"
                            : literal.cst instanceof Float ? "F" : literal.cst instanceof Double ? "D"
                            : literal.cst instanceof String ? "Ljava/lang/String;" : null;
                    if (type == null) return null; stack.add(new Value(new Literal(literal.cst), type));
                } else if (instruction instanceof InsnNode && opcode >= Opcodes.IRETURN && opcode <= Opcodes.ARETURN) {
                    result = pop(stack); if (!stack.isEmpty()) return null; returned = true;
                } else if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5)
                    stack.add(new Value(new Literal(opcode - Opcodes.ICONST_0), "I"));
                else return null;
            }
        } catch (IndexOutOfBoundsException unavailable) { return null; }
        if (!returned || operation == null || result == null || !contains(result.expression(), operation)) return null;
        return new Body(result, operation, operationInstruction, Map.copyOf(operationLocals));
    }

    private static boolean contains(Expression expression, Call call) {
        return expression.equals(call) || expression instanceof Cast cast && contains(cast.value(), call);
    }

    private static Value pop(List<Value> stack) { return stack.removeLast(); }

    private static Map<Integer, Binding> bindings(MethodNode method) {
        Map<Integer, Binding> out = new HashMap<>();
        Type[] arguments = Type.getArgumentTypes(method.desc);
        int slot = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        for (int argument = 0; argument < arguments.length; argument++) {
            List<AnnotationNode> annotations = new ArrayList<>();
            add(annotations, method.visibleParameterAnnotations, argument);
            add(annotations, method.invisibleParameterAnnotations, argument);
            List<AnnotationNode> local = annotations.stream().filter(annotation -> annotation.desc.equals(LOCAL)).toList();
            if (local.size() == 1) {
                AnnotationNode annotation = local.getFirst();
                Integer index = integer(value(annotation, "index"));
                Integer ordinal = integer(value(annotation, "ordinal"));
                if (index != null && index < 0) index = null;
                if (ordinal != null && ordinal < 0) ordinal = null;
                out.put(slot, new Binding(index, strings(value(annotation, "name")), ordinal,
                        Boolean.TRUE.equals(value(annotation, "argsOnly"))));
            }
            slot += arguments[argument].getSize();
        }
        return Map.copyOf(out);
    }

    private static Set<Integer> argumentSlots(MethodNode method) {
        Set<Integer> slots = new LinkedHashSet<>();
        int slot = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        for (Type type : Type.getArgumentTypes(method.desc)) { slots.add(slot); slot += type.getSize(); }
        return slots;
    }

    private static AnnotationNode injection(MethodNode method) {
        List<AnnotationNode> annotations = new ArrayList<>();
        if (method.visibleAnnotations != null) annotations.addAll(method.visibleAnnotations);
        if (method.invisibleAnnotations != null) annotations.addAll(method.invisibleAnnotations);
        List<AnnotationNode> matches = annotations.stream().filter(a -> a.desc.equals(REDIRECT) || a.desc.equals(WRAP)).toList();
        if (matches.size() != 1) return null;
        AnnotationNode injection = matches.getFirst();
        Object atValue = value(injection, "at");
        List<?> points = atValue instanceof List<?> list ? list : List.of(atValue == null ? "" : atValue);
        if (points.size() != 1 || !(points.getFirst() instanceof AnnotationNode at)
                || !"INVOKE".equals(value(at, "value"))) return null;
        Object member = value(at, "target");
        if (!(member instanceof String target) || !target.startsWith("L") || target.indexOf(';') < 0
                || target.indexOf('(') < 0) return null;
        try {
            if (!Type.getReturnType(target.substring(target.indexOf('('))).equals(Type.getReturnType(method.desc))) return null;
        } catch (IllegalArgumentException malformed) { return null; }
        // A constrained occurrence/slice cannot be replaced by a proof about the unconstrained function.
        Integer ordinal = integer(value(at, "ordinal"));
        if (ordinal != null && ordinal >= 0 || value(at, "shift") != null || value(injection, "slice") != null) return null;
        return injection;
    }

    private static boolean selects(String selector, String owner, MethodNode method) {
        if (selector.startsWith("L")) {
            int separator = selector.indexOf(';');
            if (separator < 0 || !selector.substring(1, separator).equals(owner)) return false;
            selector = selector.substring(separator + 1);
        }
        int descriptor = selector.indexOf('(');
        return descriptor < 0 ? selector.equals(method.name)
                : selector.substring(0, descriptor).equals(method.name) && selector.substring(descriptor).equals(method.desc);
    }

    private static void add(List<AnnotationNode> out, List<AnnotationNode>[] annotations, int index) {
        if (annotations != null && index < annotations.length && annotations[index] != null) out.addAll(annotations[index]);
    }

    private static Object value(AnnotationNode annotation, String key) {
        if (annotation.values != null) for (int index = 0; index + 1 < annotation.values.size(); index += 2)
            if (key.equals(annotation.values.get(index))) return annotation.values.get(index + 1);
        return null;
    }

    private static Integer integer(Object value) { return value instanceof Number number ? number.intValue() : null; }

    private static List<String> strings(Object value) {
        if (value instanceof String string) return List.of(string);
        if (value instanceof List<?> list) return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
        return List.of();
    }
}
