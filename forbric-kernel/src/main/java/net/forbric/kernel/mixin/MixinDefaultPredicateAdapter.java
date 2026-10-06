/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

import net.forbric.kernel.boot.DefaultPredicateDispatch;
import net.forbric.kernel.boot.DefinedMethodContracts;

/**
 * Transports a missing redirect between structurally identical default bitmask-predicate families. The guest
 * handler keeps its original body and captures; the live native operation remains authoritative on a receiver
 * which overrides the native predicate, or when the final-defined default/body witnesses cannot be established.
 * No predicate, flags member, owner or guest identity is named by this rule.
 */
public final class MixinDefaultPredicateAdapter {
    public static final String PROPERTY = "forbric.mixinDefaultPredicates";
    private static final String REDIRECT = "Lorg/spongepowered/asm/mixin/injection/Redirect;";
    private static final String WRAP = "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";
    private static final String OPERATION = "com/llamalad7/mixinextras/injector/wrapoperation/Operation";
    private static final String DISPATCH = "net/forbric/kernel/boot/DefaultPredicateDispatch";
    private static final String GROUP = "Lorg/spongepowered/asm/mixin/injection/Group;";
    private static final String SUFFIX = "$forbricdefaultpredicate";
    private MixinDefaultPredicateAdapter() { }

    private record Plan(AnnotationNode injection, AnnotationNode at, DefaultMethodOverloadBridge.Member old,
                        DefaultMethodOverloadBridge.Member live, List<Integer> oldArguments,
                        DefinedMethodContracts.MethodContract nativeContract,
                        List<DefinedMethodContracts.MethodContract> sources, String token) { }

    public static int adapt(ClassNode mixin, Function<String, ClassNode> resolver) {
        if ("off".equalsIgnoreCase(System.getProperty(PROPERTY, "on")) || mixin == null || resolver == null) return 0;
        List<String> targets = MixinFit.mixinTargets(mixin);
        if (targets.size() != 1) return 0;
        ClassNode host = resolver.apply(targets.getFirst());
        if (host == null) return 0;
        List<MethodNode> added = new ArrayList<>();
        for (MethodNode handler : new ArrayList<>(mixin.methods)) {
            Plan plan = plan(mixin, handler, host, resolver);
            if (plan == null) continue;
            DefaultPredicateDispatch.register(plan.token(), plan.nativeContract(), plan.sources());
            added.add(wrap(mixin, handler, plan));
        }
        mixin.methods.addAll(added);
        return added.size();
    }

    private static Plan plan(ClassNode mixin, MethodNode handler, ClassNode host, Function<String, ClassNode> resolver) {
        if ((handler.access & Opcodes.ACC_SYNCHRONIZED) != 0 || handler.name.contains(SUFFIX)) return null;
        List<AnnotationNode> annotations = annotations(handler);
        if (annotations.stream().anyMatch(a -> a.desc.equals(GROUP))) return null;
        List<AnnotationNode> injecting = annotations.stream().filter(a -> FinalMixinApplications.isInjector(a.desc)).toList();
        if (injecting.size() != 1 || !injecting.getFirst().desc.equals(REDIRECT)) return null;
        AnnotationNode injection = injecting.getFirst();
        if (MixinFit.value(injection, "slice") != null) return null;
        List<AnnotationNode> points = MixinFit.atNodes(injection);
        if (points.size() != 1) return null;
        AnnotationNode at = points.getFirst();
        if (!"INVOKE".equals(MixinFit.value(at, "value")) || MixinFit.value(at, "shift") != null) return null;
        Object ordinal = MixinFit.value(at, "ordinal");
        if (ordinal instanceof Number number && number.intValue() >= 0) return null;
        Object raw = MixinFit.value(at, "target");
        DefaultMethodOverloadBridge.Member old = raw instanceof String target ? parse(target) : null;
        if (old == null || !Type.getReturnType(old.descriptor()).equals(Type.BOOLEAN_TYPE)) return null;
        List<String> selectors = strings(MixinFit.value(injection, "method"));
        List<MethodNode> bodies = host.methods.stream().filter(m -> selectors.stream().anyMatch(s -> select(s, m))).toList();
        if (bodies.size() != 1 || (handler.access & Opcodes.ACC_STATIC) == 0
                && (bodies.getFirst().access & Opcodes.ACC_STATIC) != 0) return null;
        List<MethodInsnNode> candidates = new ArrayList<>();
        for (AbstractInsnNode instruction : bodies.getFirst().instructions) {
            if (!(instruction instanceof MethodInsnNode call) || !call.owner.equals(old.owner()) || !call.name.equals(old.name())) continue;
            if (call.desc.equals(old.descriptor())) return null;
            if (!Type.getReturnType(call.desc).equals(Type.BOOLEAN_TYPE) || call.getOpcode() != Opcodes.INVOKEINTERFACE
                    && call.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
            candidates.add(call);
        }
        if (candidates.size() != 1) return null;
        DefaultMethodOverloadBridge.Member live = DefaultMethodOverloadBridge.Member.of(candidates.getFirst());
        Type[] oldArgs = Type.getArgumentTypes(old.descriptor()), liveArgs = Type.getArgumentTypes(live.descriptor());
        if (liveArgs.length <= oldArgs.length) return null;
        List<Integer> oldMapping = DefaultMethodOverloadBridge.subset(liveArgs, oldArgs);
        if (oldMapping == null) return null;
        Type[] handlerArgs = Type.getArgumentTypes(handler.desc);
        if (!Type.getReturnType(handler.desc).equals(Type.BOOLEAN_TYPE) || handlerArgs.length < oldArgs.length + 1
                || !handlerArgs[0].equals(Type.getObjectType(old.owner()))) return null;
        for (int index = 0; index < oldArgs.length; index++) if (!handlerArgs[index + 1].equals(oldArgs[index])) return null;
        for (AbstractInsnNode instruction : handler.instructions)
            if (instruction.getOpcode() == Opcodes.MONITORENTER || instruction.getOpcode() == Opcodes.MONITOREXIT) return null;
        ClassNode logicalOwner = resolver.apply(old.owner());
        if (logicalOwner == null) return null;
        var original = DefaultMethodOverloadBridge.declaration(logicalOwner, old.name(), old.descriptor(), resolver);
        var nativeDefault = DefaultMethodOverloadBridge.declaration(logicalOwner, live.name(), live.descriptor(), resolver);
        if (original == null || nativeDefault == null) return null;
        var oldPredicate = DefaultPredicateFamilies.predicate(original, resolver);
        var nativePredicate = DefaultPredicateFamilies.predicate(nativeDefault, resolver);
        if (oldPredicate == null || nativePredicate == null || !oldPredicate.flags().name().equals(nativePredicate.flags().name())) return null;
        List<MethodInsnNode> sourceCalls = new ArrayList<>();
        for (AbstractInsnNode instruction : handler.instructions)
            if (instruction instanceof MethodInsnNode call && call.owner.equals(old.owner()) && call.name.equals(old.name())
                    && !call.desc.equals(old.descriptor()) && !call.desc.equals(live.descriptor())) sourceCalls.add(call);
        if (sourceCalls.size() != 1) return null;
        MethodInsnNode sourceCall = sourceCalls.getFirst();
        var sourceDefault = DefaultMethodOverloadBridge.declaration(logicalOwner, sourceCall.name, sourceCall.desc, resolver);
        if (sourceDefault == null) return null;
        var sourcePredicate = DefaultPredicateFamilies.predicate(sourceDefault, resolver);
        if (sourcePredicate == null || !oldPredicate.flags().name().equals(sourcePredicate.flags().name())) return null;
        var sourceFlags = DefaultMethodOverloadBridge.declaration(logicalOwner, sourcePredicate.flags().name(),
                sourcePredicate.flags().descriptor(), resolver);
        if (sourceFlags == null) return null;
        var projection = DefaultMethodOverloadBridge.projection(sourceFlags.owner(), sourceFlags.method(), resolver);
        DefinedMethodContracts.MethodContract flagContract;
        List<Integer> projected;
        if (projection != null && projection.richer().descriptor().equals(nativePredicate.flags().descriptor())
                && projection.richer().owner().equals(nativePredicate.flags().owner())) {
            flagContract = DefaultMethodOverloadBridge.projectedContract(sourceFlags.owner(), sourceFlags.method(), projection);
            projected = projection.parameters();
        } else {
            // The ordinary API interface may already have passed the global forwarder before this mixin is read.
            MethodNode copy = sourceFlags.method();
            List<AbstractInsnNode> code = DefaultMethodOverloadBridge.real(copy);
            List<MethodInsnNode> forwards = code.stream().filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast).toList();
            if (forwards.size() != 1 || !DefaultMethodOverloadBridge.Member.of(forwards.getFirst()).equals(nativePredicate.flags())) return null;
            projected = DefaultMethodOverloadBridge.subset(Type.getArgumentTypes(copy.desc), Type.getArgumentTypes(forwards.getFirst().desc));
            if (projected == null || !pureAlreadyForwarded(sourceFlags, forwards.getFirst(), projected)) return null;
            flagContract = DefaultMethodOverloadBridge.contract(sourceFlags);
        }
        Type[] sourceArgs = Type.getArgumentTypes(sourceCall.desc);
        if (sourcePredicate.flagParameters().size() != Type.getArgumentTypes(sourcePredicate.flags().descriptor()).length
                || nativePredicate.flagParameters().size() != projected.size()) return null;
        for (int index = 0; index < projected.size(); index++) {
            int sourceIndex = sourcePredicate.flagParameters().get(projected.get(index));
            int nativeIndex = nativePredicate.flagParameters().get(index);
            if (!sourceArgs[sourceIndex].equals(liveArgs[nativeIndex])) return null;
        }
        int sourceMask = sourcePredicate.maskParameter(), nativeMask = nativePredicate.maskParameter();
        if (oldPredicate.maskParameter() >= oldMapping.size() || oldMapping.get(oldPredicate.maskParameter()) != nativeMask
                || !originalOperands(handler, mixin.name, sourceCall, sourceMask, oldPredicate.maskParameter())) return null;
        List<DefinedMethodContracts.MethodContract> witnesses = new ArrayList<>();
        witnesses.add(DefaultMethodOverloadBridge.contract(sourceDefault)); witnesses.add(flagContract);
        witnesses.addAll(nativePredicate.helpers()); witnesses.addAll(sourcePredicate.helpers());
        var nativeContract = DefaultMethodOverloadBridge.contract(nativeDefault);
        String token = mixin.name.replace('/', '.') + "#" + handler.name + handler.desc + ":" + witnessToken(nativeContract, witnesses);
        return new Plan(injection, at, old, live, oldMapping, nativeContract, List.copyOf(witnesses), token);
    }

    private static boolean pureAlreadyForwarded(DefaultMethodOverloadBridge.Declaration source, MethodInsnNode call, List<Integer> mapping) {
        List<AbstractInsnNode> code = DefaultMethodOverloadBridge.real(source.method());
        Type[] arguments = Type.getArgumentTypes(source.method().desc);
        int[] slots = DefaultMethodOverloadBridge.slots(arguments, false);
        if (code.size() != mapping.size() + 4 || !(code.getFirst() instanceof VarInsnNode self) || self.var != 0
                || !(code.get(1) instanceof TypeInsnNode cast) || !cast.desc.equals(call.owner)
                || code.get(code.size() - 2) != call || code.getLast().getOpcode() != Opcodes.IRETURN) return false;
        for (int index = 0; index < mapping.size(); index++)
            if (!(code.get(index + 2) instanceof VarInsnNode load) || load.var != slots[mapping.get(index)]
                    || load.getOpcode() != arguments[mapping.get(index)].getOpcode(Opcodes.ILOAD)) return false;
        return source.method().tryCatchBlocks.isEmpty();
    }

    private static boolean originalOperands(MethodNode handler, String owner, MethodInsnNode call, int mask, int oldMask) {
        try {
            Frame<BasicValue>[] frames = new Analyzer<BasicValue>(new Parameters()).analyze(owner, handler);
            Frame<BasicValue> frame = frames[handler.instructions.indexOf(call)];
            Type[] called = Type.getArgumentTypes(call.desc), arguments = Type.getArgumentTypes(handler.desc);
            int first = frame.getStackSize() - called.length - 1;
            int[] slots = DefaultMethodOverloadBridge.slots(arguments, (handler.access & Opcodes.ACC_STATIC) != 0);
            return parameter(frame.getStack(first)) == slots[0] && parameter(frame.getStack(first + mask + 1)) == slots[oldMask + 1];
        } catch (AnalyzerException | RuntimeException invalid) { return false; }
    }

    private static int parameter(BasicValue value) { return value instanceof Parameter parameter ? parameter.slot : -1; }
    private static final class Parameter extends BasicValue {
        final int slot;
        Parameter(Type type, int slot) { super(type); this.slot = slot; }
        @Override public boolean equals(Object other) { return other instanceof Parameter p && slot == p.slot && java.util.Objects.equals(getType(), p.getType()); }
        @Override public int hashCode() { return 31 * super.hashCode() + slot; }
    }
    private static final class Parameters extends BasicInterpreter {
        Parameters() { super(Opcodes.ASM9); }
        @Override public BasicValue newParameterValue(boolean instance, int local, Type type) { return new Parameter(type, local); }
        @Override public BasicValue copyOperation(AbstractInsnNode instruction, BasicValue value) { return value; }
        @Override public BasicValue unaryOperation(AbstractInsnNode instruction, BasicValue value) throws AnalyzerException {
            BasicValue result = super.unaryOperation(instruction, value);
            return instruction.getOpcode() == Opcodes.CHECKCAST && value instanceof Parameter p ? new Parameter(result.getType(), p.slot) : result;
        }
    }

    private static MethodNode wrap(ClassNode mixin, MethodNode handler, Plan plan) {
        Type[] old = Type.getArgumentTypes(handler.desc), live = Type.getArgumentTypes(plan.live().descriptor());
        int oldInputs = Type.getArgumentTypes(plan.old().descriptor()).length + 1;
        Type[] inputs = new Type[live.length + 1]; inputs[0] = Type.getObjectType(plan.live().owner());
        System.arraycopy(live, 0, inputs, 1, live.length);
        Type[] outerArguments = Arrays.copyOf(inputs, inputs.length + 1 + old.length - oldInputs);
        outerArguments[inputs.length] = Type.getObjectType(OPERATION);
        System.arraycopy(old, oldInputs, outerArguments, inputs.length + 1, old.length - oldInputs);
        String originalName = handler.name, innerName = MixinHandlerShim.asideName(mixin.name, originalName, SUFFIX);
        MethodNode outer = new MethodNode(handler.access, originalName, Type.getMethodDescriptor(Type.BOOLEAN_TYPE, outerArguments), null,
                handler.exceptions.toArray(String[]::new));
        AnnotationNode annotation = new AnnotationNode(WRAP); plan.injection().accept(annotation);
        annotation.desc = WRAP;
        AnnotationNode at = new AnnotationNode(plan.at().desc); plan.at().accept(at);
        put(at, "target", plan.live().atTarget()); put(annotation, "at", List.of(at));
        boolean visible = handler.visibleAnnotations != null && handler.visibleAnnotations.remove(plan.injection());
        if (!visible) handler.invisibleAnnotations.remove(plan.injection());
        if (visible) outer.visibleAnnotations = new ArrayList<>(List.of(annotation)); else outer.invisibleAnnotations = new ArrayList<>(List.of(annotation));
        outer.visibleParameterAnnotations = parameters(handler.visibleParameterAnnotations, oldInputs, outerArguments.length, inputs.length + 1, plan.oldArguments());
        outer.invisibleParameterAnnotations = parameters(handler.invisibleParameterAnnotations, oldInputs, outerArguments.length, inputs.length + 1, plan.oldArguments());
        boolean isStatic = (handler.access & Opcodes.ACC_STATIC) != 0;
        int[] slots = DefaultMethodOverloadBridge.slots(outerArguments, isStatic);
        LabelNode nativeRoute = new LabelNode();
        outer.instructions.add(new VarInsnNode(Opcodes.ALOAD, slots[0])); outer.instructions.add(new LdcInsnNode(plan.token()));
        outer.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, DISPATCH, "allows", "(Ljava/lang/Object;Ljava/lang/String;)Z", false));
        outer.instructions.add(new JumpInsnNode(Opcodes.IFEQ, nativeRoute));
        if (!isStatic) outer.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        outer.instructions.add(new VarInsnNode(Opcodes.ALOAD, slots[0]));
        for (int index : plan.oldArguments()) outer.instructions.add(new VarInsnNode(live[index].getOpcode(Opcodes.ILOAD), slots[index + 1]));
        for (int index = oldInputs; index < old.length; index++) {
            int parameter = inputs.length + 1 + index - oldInputs;
            outer.instructions.add(new VarInsnNode(old[index].getOpcode(Opcodes.ILOAD), slots[parameter]));
        }
        outer.instructions.add(MixinHandlerShim.callOwn(mixin, isStatic, innerName, handler.desc));
        outer.instructions.add(new InsnNode(Opcodes.IRETURN));
        outer.instructions.add(nativeRoute); outer.instructions.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        outer.instructions.add(new VarInsnNode(Opcodes.ALOAD, slots[inputs.length]));
        constant(outer, inputs.length); outer.instructions.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        for (int index = 0; index < inputs.length; index++) {
            outer.instructions.add(new InsnNode(Opcodes.DUP)); constant(outer, index);
            outer.instructions.add(new VarInsnNode(inputs[index].getOpcode(Opcodes.ILOAD), slots[index]));
            box(outer, inputs[index]); outer.instructions.add(new InsnNode(Opcodes.AASTORE));
        }
        outer.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, OPERATION, "call", "([Ljava/lang/Object;)Ljava/lang/Object;", true));
        outer.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Boolean"));
        outer.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false));
        outer.instructions.add(new InsnNode(Opcodes.IRETURN));
        outer.maxLocals = isStatic ? 0 : 1; int originalStack = isStatic ? 0 : 1;
        for (Type argument : outerArguments) outer.maxLocals += argument.getSize();
        for (Type argument : old) originalStack += argument.getSize();
        outer.maxStack = Math.max(7, originalStack); handler.name = innerName;
        return outer;
    }

    @SuppressWarnings("unchecked")
    private static List<AnnotationNode>[] parameters(List<AnnotationNode>[] original, int start, int size, int movedStart,
                                                   List<Integer> oldArguments) {
        if (original == null) return null;
        List<AnnotationNode>[] out = new List[size];
        if (original.length > 0) out[0] = original[0];
        for (int index = 1; index < start && index < original.length; index++) out[1 + oldArguments.get(index - 1)] = original[index];
        for (int index = start; index < original.length; index++) out[movedStart + index - start] = original[index];
        return out;
    }
    private static void constant(MethodNode method, int value) {
        if (value >= 0 && value <= 5) method.instructions.add(new InsnNode(Opcodes.ICONST_0 + value));
        else method.instructions.add(new LdcInsnNode(value));
    }
    private static void box(MethodNode method, Type type) {
        String owner = switch (type.getSort()) {
            case Type.BOOLEAN -> "java/lang/Boolean"; case Type.BYTE -> "java/lang/Byte"; case Type.CHAR -> "java/lang/Character";
            case Type.SHORT -> "java/lang/Short"; case Type.INT -> "java/lang/Integer"; case Type.FLOAT -> "java/lang/Float";
            case Type.LONG -> "java/lang/Long"; case Type.DOUBLE -> "java/lang/Double"; default -> null;
        };
        if (owner != null) method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, "valueOf", "(" + type.getDescriptor() + ")L" + owner + ";", false));
    }
    private static DefaultMethodOverloadBridge.Member parse(String value) {
        try {
            int semicolon = value.indexOf(';'), descriptor = value.indexOf('(', semicolon);
            if (!value.startsWith("L") || semicolon < 2 || descriptor <= semicolon) return null;
            Type.getMethodType(value.substring(descriptor));
            return new DefaultMethodOverloadBridge.Member(Opcodes.INVOKEINTERFACE, value.substring(1, semicolon),
                    value.substring(semicolon + 1, descriptor), value.substring(descriptor), true);
        } catch (RuntimeException invalid) { return null; }
    }
    private static List<AnnotationNode> annotations(MethodNode method) {
        List<AnnotationNode> out = new ArrayList<>();
        if (method.visibleAnnotations != null) out.addAll(method.visibleAnnotations);
        if (method.invisibleAnnotations != null) out.addAll(method.invisibleAnnotations); return out;
    }
    private static List<String> strings(Object value) {
        if (value instanceof String string) return List.of(string);
        return value instanceof List<?> list ? list.stream().filter(String.class::isInstance).map(String.class::cast).toList() : List.of();
    }
    private static boolean select(String selector, MethodNode method) { return selector.equals(method.name) || selector.equals(method.name + method.desc); }
    private static void put(AnnotationNode annotation, String key, Object value) {
        if (annotation.values == null) annotation.values = new ArrayList<>();
        for (int index = 0; index + 1 < annotation.values.size(); index += 2) if (key.equals(annotation.values.get(index))) {
            annotation.values.set(index + 1, value); return;
        }
        annotation.values.add(key); annotation.values.add(value);
    }
    private static String witnessToken(DefinedMethodContracts.MethodContract nativeContract,
                                       List<DefinedMethodContracts.MethodContract> witnesses) {
        try {
            byte[] bytes = (nativeContract.toString() + witnesses).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
