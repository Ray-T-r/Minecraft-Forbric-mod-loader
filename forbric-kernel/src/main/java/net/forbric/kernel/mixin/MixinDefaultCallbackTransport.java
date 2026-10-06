/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.*;
import java.util.function.Function;
import net.forbric.api.Ecosystem;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** A receiver-independent Boolean capability callback follows its exact operand predicate to a native default.
 * The source callback stays intact. Native concrete overrides never enter the interface default or this callback.
 * The admission grammar is structural: it does not contain guest, game, field or handler names. */
public final class MixinDefaultCallbackTransport {
    public static final String PROPERTY = "forbric.mixinDefaultCallbacks";
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String LOCAL = "Lcom/llamalad7/mixinextras/sugar/Local;";
    private static final String CIR = "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable";
    private record Source(MethodNode handler, AnnotationNode inject, List<AbstractInsnNode> code, String atomic,
                          int below, int above, int callback) { }
    private record Plan(ClassNode owner, MethodNode method, int below, int above) { }
    private MixinDefaultCallbackTransport() { }

    public static int adapt(ClassNode mixin, Ecosystem ecosystem, Function<String, ClassNode> classes) {
        return adapt(mixin, classes, name -> NativeGameReferences.reference(ecosystem, name));
    }
    static int adapt(ClassNode mixin, Function<String, ClassNode> classes, Function<String, ClassNode> references) {
        if ("off".equalsIgnoreCase(System.getProperty(PROPERTY, "on")) || mixin.fields.size() != 0 || !mixin.interfaces.isEmpty()
                || !"java/lang/Object".equals(mixin.superName)) return 0;
        for (MethodNode method : mixin.methods) if (method.name.equals("<init>") && !emptyConstructor(method)) return 0;
        List<MethodNode> methods = mixin.methods.stream().filter(method -> !method.name.equals("<init>")).toList();
        if (methods.size() != 1) return 0;
        Source source = source(methods.getFirst()); if (source == null) return 0;
        List<String> targets = MixinFit.mixinTargets(mixin); if (targets.size() != 1) return 0;
        ClassNode current = classes.apply(targets.getFirst()), original = references.apply(targets.getFirst());
        if (current == null || original == null || !dormantSource(source, current, original)) return 0;
        ClassNode atomic = classes.apply(source.atomic()); if (atomic == null) return 0;
        List<Plan> found = new ArrayList<>(); Set<String> seen = new HashSet<>();
        for (MethodNode forwarder : atomic.methods) {
            MethodInsnNode call = defaultForwarder(forwarder); if (call == null) continue;
            String key = call.owner + "." + call.name + call.desc; if (!seen.add(key)) continue;
            ClassNode owner = classes.apply(call.owner);
            if (owner == null || (owner.access & Opcodes.ACC_INTERFACE) == 0) continue;
            for (MethodNode method : owner.methods) {
                if (!method.name.equals(call.name) || !method.desc.equals(call.desc)
                        || (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_STATIC | Opcodes.ACC_NATIVE)) != 0) continue;
                Plan plan = predicate(source, owner, method); if (plan != null) found.add(plan);
            }
        }
        if (found.size() != 1) return 0;
        install(mixin, source, found.getFirst()); return 1;
    }

    private static Source source(MethodNode method) {
        AnnotationNode inject = MixinFit.injectorOf(method);
        if (inject == null || !inject.desc.equals(INJECT) || !Boolean.TRUE.equals(MixinFit.value(inject, "cancellable"))
                || MixinFit.value(inject, "slice") != null || !method.tryCatchBlocks.isEmpty()
                || (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_SYNCHRONIZED)) != 0
                || annotations(method).stream().anyMatch(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Group;"))
                || annotations(method).stream().filter(a -> FinalMixinApplications.isInjector(a.desc)).count() != 1) return null;
        List<AnnotationNode> ats = MixinFit.atNodes(inject);
        if (ats.size() != 1 || !"INVOKE_ASSIGN".equals(MixinFit.value(ats.getFirst(), "value"))
                || MixinFit.value(ats.getFirst(), "shift") != null || MixinFit.value(ats.getFirst(), "ordinal") != null) return null;
        Type[] args = Type.getArgumentTypes(method.desc);
        if (args.length != 4 || args[0].getSort() != Type.OBJECT || args[1].getSort() != Type.OBJECT
                || !args[1].equals(args[3]) || !args[2].equals(Type.getObjectType(CIR))
                || !Type.getReturnType(method.desc).equals(Type.VOID_TYPE)) return null;
        AnnotationNode local = parameter(method, 3);
        if (local == null || !local.desc.equals(LOCAL) || MixinFit.stringList(MixinFit.value(local, "name")).size() != 1) return null;
        List<AbstractInsnNode> c = DefaultMethodOverloadBridge.real(method);
        int[] slots = DefaultMethodOverloadBridge.slots(args, false);
        if (c.size() != 25 || !load(c.get(0), slots[3]) || !(c.get(1) instanceof FieldInsnNode tag)
                || tag.getOpcode() != Opcodes.GETSTATIC || !(c.get(2) instanceof MethodInsnNode test)
                || test.getOpcode() != Opcodes.INVOKEVIRTUAL || !test.owner.equals(args[3].getInternalName())
                || !test.desc.equals("(" + tag.desc + ")Z") || !jump(c.get(3), Opcodes.IFEQ, c.get(24))
                || !load(c.get(4), slots[3]) || !(c.get(5) instanceof MethodInsnNode getter)
                || getter.getOpcode() != Opcodes.INVOKEVIRTUAL || !getter.owner.equals(args[3].getInternalName())
                || Type.getArgumentTypes(getter.desc).length != 0 || Type.getReturnType(getter.desc).getSort() != Type.OBJECT
                || !(c.get(6) instanceof TypeInsnNode atom) || atom.getOpcode() != Opcodes.INSTANCEOF
                || !jump(c.get(7), Opcodes.IFEQ, c.get(20)) || !load(c.get(8), slots[3])
                || !property(c, 9, args[3]) || !load(c.get(11), slots[1]) || !property(c, 12, args[1])
                || !jump(c.get(14), Opcodes.IF_ACMPNE, c.get(24)) || !success(c, 15, slots[2])
                || !jump(c.get(19), Opcodes.GOTO, c.get(24)) || !success(c, 20, slots[2])
                || c.get(24).getOpcode() != Opcodes.RETURN) return null;
        return new Source(method, inject, c, atom.desc, slots[3], slots[1], slots[2]);
    }

    private static boolean dormantSource(Source source, ClassNode current, ClassNode original) {
        List<String> selectors = MixinFit.stringList(MixinFit.value(source.inject(), "method"));
        if (selectors.size() != 1) return false;
        List<MethodNode> old = original.methods.stream().filter(m -> selectors.contains(m.name) || selectors.contains(m.name + m.desc)).toList();
        if (old.size() != 1) return false;
        MethodNode host = old.getFirst();
        if ((host.access & Opcodes.ACC_PRIVATE) == 0 || !Type.getReturnType(host.desc).equals(Type.BOOLEAN_TYPE)) return false;
        Type[] hostArgs = Type.getArgumentTypes(host.desc), handlerArgs = Type.getArgumentTypes(source.handler().desc);
        if (hostArgs.length != 2 || !hostArgs[0].equals(handlerArgs[0]) || !hostArgs[1].equals(handlerArgs[1])) return false;
        String anchor = MixinFit.asString(MixinFit.value(MixinFit.atNodes(source.inject()).getFirst(), "target"));
        List<MethodInsnNode> points = calls(host).stream().filter(call -> member(call).equals(anchor)).toList();
        if (points.size() != 1 || !Type.getReturnType(points.getFirst().desc).equals(handlerArgs[3])) return false;
        AbstractInsnNode stored = next(points.getFirst());
        if (!(stored instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ASTORE) return false;
        String localName = MixinFit.stringList(MixinFit.value(parameter(source.handler(), 3), "name")).getFirst();
        int position = host.instructions.indexOf(next(store));
        if (host.localVariables == null || host.localVariables.stream().filter(v -> v.index == store.var
                && v.name.equals(localName) && v.desc.equals(handlerArgs[3].getDescriptor())
                && host.instructions.indexOf(v.start) <= position && position < host.instructions.indexOf(v.end)).count() != 1) return false;
        boolean wasCalled = original.methods.stream().flatMap(m -> calls(m).stream()).anyMatch(call -> call.owner.equals(original.name)
                && call.name.equals(host.name) && call.desc.equals(host.desc));
        return wasCalled && !referenced(current, host);
    }

    /** Private methods may remain live through lambda/constant bootstrap handles without a direct CALL instruction.
     * A method-name literal may also be used by reflective lookup; without proving its consumer, retain the source. */
    private static boolean referenced(ClassNode owner, MethodNode method) {
        for (FieldNode field : owner.fields) if (mentions(field.value, owner.name, method)) return true;
        for (MethodNode body : owner.methods) for (AbstractInsnNode instruction : body.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(owner.name)
                    && call.name.equals(method.name) && call.desc.equals(method.desc)) return true;
            if (instruction instanceof LdcInsnNode constant && mentions(constant.cst, owner.name, method)) return true;
            if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                if (mentions(dynamic.name, owner.name, method)) return true;
                if (mentions(dynamic.bsm, owner.name, method)) return true;
                for (Object argument : dynamic.bsmArgs) if (mentions(argument, owner.name, method)) return true;
            }
        }
        return false;
    }
    private static boolean mentions(Object value, String owner, MethodNode method) {
        if (value == null) return false;
        ArrayDeque<Object> pending = new ArrayDeque<>(); pending.add(value);
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (!pending.isEmpty()) {
            Object item = pending.removeLast(); if (!visited.add(item)) continue;
            if (item instanceof Handle handle && handle.getOwner().equals(owner) && handle.getName().equals(method.name)
                    && handle.getDesc().equals(method.desc)) return true;
            if (item instanceof String literal && literal.contains(method.name)) return true;
            if (item instanceof ConstantDynamic dynamic) {
                pending.add(dynamic.getName());
                pending.add(dynamic.getBootstrapMethod());
                for (int i = 0; i < dynamic.getBootstrapMethodArgumentCount(); i++) {
                    Object argument = dynamic.getBootstrapMethodArgument(i); if (argument != null) pending.add(argument);
                }
            }
        }
        return false;
    }

    private static Plan predicate(Source source, ClassNode owner, MethodNode method) {
        List<AbstractInsnNode> c = DefaultMethodOverloadBridge.real(method), s = source.code();
        if (!method.tryCatchBlocks.isEmpty() || (method.access & Opcodes.ACC_PUBLIC) == 0
                || !Type.getReturnType(method.desc).equals(Type.BOOLEAN_TYPE)
                || c.size() != 15 || !(c.get(0) instanceof VarInsnNode below) || below.getOpcode() != Opcodes.ALOAD
                || !sameMember(c.get(1), s.get(5)) || !sameMember(c.get(2), s.get(6))
                || !jump(c.get(3), Opcodes.IFEQ, c.get(13)) || !load(c.get(4), below.var)
                || !sameMember(c.get(5), s.get(9)) || !sameMember(c.get(6), s.get(10))
                || !(c.get(7) instanceof VarInsnNode above) || above.getOpcode() != Opcodes.ALOAD
                || !sameMember(c.get(8), s.get(12)) || !sameMember(c.get(9), s.get(13))
                || !jump(c.get(10), Opcodes.IF_ACMPNE, c.get(13)) || c.get(11).getOpcode() != Opcodes.ICONST_1
                || !jump(c.get(12), Opcodes.GOTO, c.get(14)) || c.get(13).getOpcode() != Opcodes.ICONST_0
                || c.get(14).getOpcode() != Opcodes.IRETURN || below.var == above.var) return null;
        Type[] args = Type.getArgumentTypes(method.desc); int[] slots = DefaultMethodOverloadBridge.slots(args, false);
        Type state = Type.getArgumentTypes(source.handler().desc)[1];
        int b = -1, a = -1;
        for (int i = 0; i < args.length; i++) { if (slots[i] == below.var && args[i].equals(state)) b = i;
            if (slots[i] == above.var && args[i].equals(state)) a = i; }
        return b >= 0 && a >= 0 ? new Plan(owner, method, b, a) : null;
    }

    private static MethodInsnNode defaultForwarder(MethodNode method) {
        if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0 || !method.tryCatchBlocks.isEmpty()
                || !Type.getReturnType(method.desc).equals(Type.BOOLEAN_TYPE)) return null;
        List<AbstractInsnNode> c = DefaultMethodOverloadBridge.real(method); Type[] args = Type.getArgumentTypes(method.desc);
        int[] slots = DefaultMethodOverloadBridge.slots(args, false);
        if (c.size() != args.length + 3 || !load(c.getFirst(), 0) || c.getLast().getOpcode() != Opcodes.IRETURN) return null;
        for (int i = 0; i < args.length; i++) if (!(c.get(i + 1) instanceof VarInsnNode load)
                || load.getOpcode() != args[i].getOpcode(Opcodes.ILOAD) || load.var != slots[i]) return null;
        if (!(c.get(c.size() - 2) instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESPECIAL || !call.itf
                || !call.name.equals(method.name) || !call.desc.equals(method.desc)) return null;
        return call;
    }

    private static void install(ClassNode mixin, Source source, Plan plan) {
        MethodNode original = source.handler(); String name = original.name;
        original.name = MixinHandlerShim.asideName(mixin.name, name, "$forbricdefaultcallback");
        Type[] nativeArgs = Type.getArgumentTypes(plan.method().desc), args = Arrays.copyOf(nativeArgs, nativeArgs.length + 1);
        args[args.length - 1] = Type.getObjectType(CIR);
        MethodNode outer = new MethodNode(Opcodes.ACC_PRIVATE, name, Type.getMethodDescriptor(Type.VOID_TYPE, args), null, null);
        boolean visible = original.visibleAnnotations != null && original.visibleAnnotations.remove(source.inject());
        if (!visible) original.invisibleAnnotations.remove(source.inject());
        if (visible) outer.visibleAnnotations = new ArrayList<>(List.of(source.inject())); else outer.invisibleAnnotations = new ArrayList<>(List.of(source.inject()));
        put(source.inject(), "method", new ArrayList<>(List.of(plan.method().name + plan.method().desc)));
        AnnotationNode head = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;"); head.values = new ArrayList<>(List.of("value", "HEAD"));
        put(source.inject(), "at", new ArrayList<>(List.of(head)));
        int[] slots = DefaultMethodOverloadBridge.slots(args, false);
        outer.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0)); outer.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        outer.instructions.add(new VarInsnNode(Opcodes.ALOAD, slots[plan.above()]));
        outer.instructions.add(new VarInsnNode(Opcodes.ALOAD, slots[args.length - 1]));
        outer.instructions.add(new VarInsnNode(Opcodes.ALOAD, slots[plan.below()]));
        mixin.access = (mixin.access | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT) & ~Opcodes.ACC_SUPER;
        outer.instructions.add(MixinHandlerShim.callOwn(mixin, false, original.name, original.desc));
        outer.instructions.add(new InsnNode(Opcodes.RETURN)); outer.maxStack = 5; outer.maxLocals = slots[args.length - 1] + 1;
        mixin.methods.removeIf(method -> method.name.equals("<init>")); mixin.methods.add(outer);
        for (AnnotationNode annotation : classAnnotations(mixin)) if (annotation.desc.equals(MIXIN)) {
            put(annotation, "value", new ArrayList<>(List.of(Type.getObjectType(plan.owner().name))));
            if (annotation.values != null) for (int i = annotation.values.size() - 2; i >= 0; i -= 2)
                if ("targets".equals(annotation.values.get(i))) { annotation.values.remove(i + 1); annotation.values.remove(i); }
        }
    }
    private static boolean property(List<AbstractInsnNode> code, int start, Type state) {
        return code.get(start) instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                && code.get(start + 1) instanceof MethodInsnNode getter && getter.getOpcode() == Opcodes.INVOKEVIRTUAL
                && getter.owner.equals(state.getInternalName()) && Type.getArgumentTypes(getter.desc).length == 1
                && Type.getReturnType(getter.desc).getSort() == Type.OBJECT;
    }
    private static boolean success(List<AbstractInsnNode> c, int start, int callback) {
        return load(c.get(start), callback) && c.get(start + 1).getOpcode() == Opcodes.ICONST_1
                && c.get(start + 2) instanceof MethodInsnNode box && box.getOpcode() == Opcodes.INVOKESTATIC
                && box.owner.equals("java/lang/Boolean") && box.name.equals("valueOf") && box.desc.equals("(Z)Ljava/lang/Boolean;")
                && c.get(start + 3) instanceof MethodInsnNode set && set.getOpcode() == Opcodes.INVOKEVIRTUAL
                && set.owner.equals(CIR) && set.name.equals("setReturnValue") && set.desc.equals("(Ljava/lang/Object;)V");
    }
    private static boolean sameMember(AbstractInsnNode a, AbstractInsnNode b) {
        if (a.getOpcode() != b.getOpcode()) return false;
        if (a instanceof MethodInsnNode x && b instanceof MethodInsnNode y) return x.owner.equals(y.owner) && x.name.equals(y.name) && x.desc.equals(y.desc) && x.itf == y.itf;
        if (a instanceof FieldInsnNode x && b instanceof FieldInsnNode y) return x.owner.equals(y.owner) && x.name.equals(y.name) && x.desc.equals(y.desc);
        return a instanceof TypeInsnNode x && b instanceof TypeInsnNode y && x.desc.equals(y.desc);
    }
    private static boolean load(AbstractInsnNode instruction, int slot) { return instruction instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == slot; }
    private static boolean emptyConstructor(MethodNode method) {
        List<AbstractInsnNode> code = DefaultMethodOverloadBridge.real(method);
        return code.size() == 3 && load(code.get(0), 0) && code.get(1) instanceof MethodInsnNode call
                && call.getOpcode() == Opcodes.INVOKESPECIAL && call.owner.equals("java/lang/Object") && call.name.equals("<init>")
                && call.desc.equals("()V") && code.get(2).getOpcode() == Opcodes.RETURN;
    }
    private static boolean jump(AbstractInsnNode instruction, int opcode, AbstractInsnNode target) { return instruction instanceof JumpInsnNode jump && jump.getOpcode() == opcode && next(jump.label) == target; }
    private static AbstractInsnNode next(AbstractInsnNode instruction) { do { instruction = instruction.getNext(); } while (instruction != null && instruction.getOpcode() < 0); return instruction; }
    private static List<MethodInsnNode> calls(MethodNode method) { return Arrays.stream(method.instructions.toArray()).filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast).toList(); }
    private static String member(MethodInsnNode call) { return "L" + call.owner + ";" + call.name + call.desc; }
    private static AnnotationNode parameter(MethodNode method, int index) {
        List<AnnotationNode> all = new ArrayList<>();
        if (method.visibleParameterAnnotations != null && index < method.visibleParameterAnnotations.length && method.visibleParameterAnnotations[index] != null) all.addAll(method.visibleParameterAnnotations[index]);
        if (method.invisibleParameterAnnotations != null && index < method.invisibleParameterAnnotations.length && method.invisibleParameterAnnotations[index] != null) all.addAll(method.invisibleParameterAnnotations[index]);
        return all.size() == 1 ? all.getFirst() : null;
    }
    private static List<AnnotationNode> annotations(MethodNode method) { List<AnnotationNode> all = new ArrayList<>(); if (method.visibleAnnotations != null) all.addAll(method.visibleAnnotations); if (method.invisibleAnnotations != null) all.addAll(method.invisibleAnnotations); return all; }
    private static List<AnnotationNode> classAnnotations(ClassNode type) { List<AnnotationNode> all = new ArrayList<>(); if (type.visibleAnnotations != null) all.addAll(type.visibleAnnotations); if (type.invisibleAnnotations != null) all.addAll(type.invisibleAnnotations); return all; }
    private static void put(AnnotationNode annotation, String key, Object value) { for (int i = 0; i + 1 < annotation.values.size(); i += 2) if (key.equals(annotation.values.get(i))) { annotation.values.set(i + 1, value); return; } annotation.values.add(key); annotation.values.add(value); }
}
