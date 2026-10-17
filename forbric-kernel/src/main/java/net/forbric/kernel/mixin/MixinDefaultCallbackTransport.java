/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.*;
import java.util.function.Function;
import net.forbric.api.Ecosystem;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** A receiver-independent Boolean capability callback follows its native decision to the interface default a carrier moved
 * that decision into. The source callback stays intact. Native concrete overrides never enter the interface default or this
 * callback.
 *
 * <p>Nothing is read off the guest's instructions but their data flow ({@link HandlerFlow}): the callback reads neither its
 * instance nor the host's first argument (the transported call hands it neither), its only effect is setting or cancelling
 * the return through its callback info, and every other call it makes is a read on the host's state argument, the captured
 * local, or what such reads return. Which default parameter stands for which of those two values is read from the
 * accesses made on them: the native host's decision and the callback make some on each (a getter with a constant, an
 * instanceof test), and each default parameter shares them with exactly one. Candidates are the interface defaults that
 * the classes those decisions read constants from or test against forward to. No guest, game, field or handler name
 * decides, and no instruction sequence. */
public final class MixinDefaultCallbackTransport {
    public static final String PROPERTY = "forbric.mixinDefaultCallbacks";
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String LOCAL = "Lcom/llamalad7/mixinextras/sugar/Local;";
    private static final String CIR = "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable";
    private record Source(MethodNode handler, AnnotationNode inject, int below, int above, int callback) { }
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
        Source source = source(mixin.name, methods.getFirst()); if (source == null) return 0;
        List<String> targets = MixinFit.mixinTargets(mixin); if (targets.size() != 1) return 0;
        ClassNode current = classes.apply(targets.getFirst()), original = references.apply(targets.getFirst());
        if (current == null || original == null || !dormantSource(source, current, original)) return 0;
        MethodNode host = MixinCallbackShape.written(source.handler(), original);
        MethodInsnNode anchor = MixinCallbackShape.calls(MixinFit.atNodes(source.inject()).getFirst(), host).getFirst();
        Map<String, Set<String>> roots = roots(original.name, host, anchor, mixin.name, source);
        if (roots == null) return 0;
        // The interface defaults that the classes the decision reads constants from or tests against forward to: as the
        // native host makes it, and as the callback extends it.
        Set<String> atomics = new LinkedHashSet<>();
        for (MethodNode body : List.of(host, source.handler())) for (AbstractInsnNode instruction : body.instructions) {
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC) atomics.add(field.owner);
            if (instruction instanceof TypeInsnNode test && test.getOpcode() == Opcodes.INSTANCEOF) atomics.add(test.desc);
        }
        List<Plan> found = new ArrayList<>(); Set<String> seen = new HashSet<>();
        for (String name : atomics) {
            ClassNode atomic = classes.apply(name); if (atomic == null) continue;
            for (MethodNode forwarder : atomic.methods) {
                MethodInsnNode call = defaultForwarder(forwarder); if (call == null) continue;
                String key = call.owner + "." + call.name + call.desc; if (!seen.add(key)) continue;
                ClassNode owner = classes.apply(call.owner);
                if (owner == null || (owner.access & Opcodes.ACC_INTERFACE) == 0) continue;
                for (MethodNode method : owner.methods) {
                    if (!method.name.equals(call.name) || !method.desc.equals(call.desc)
                            || (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_STATIC | Opcodes.ACC_NATIVE)) != 0) continue;
                    Plan plan = predicate(source, owner, method, roots); if (plan != null) found.add(plan);
                }
            }
        }
        if (found.size() != 1) return 0;
        install(mixin, source, found.getFirst()); return 1;
    }

    private static Source source(String mixin, MethodNode method) {
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
        int[] slots = DefaultMethodOverloadBridge.slots(args, false);
        if (!pureCallback(mixin, method, slots[0], slots[1], slots[2], slots[3])) return null;
        return new Source(method, inject, slots[3], slots[1], slots[2]);
    }

    /**
     * Whether the callback reads neither its instance nor {@code first}, sets or cancels the return only through
     * {@code callback}, and otherwise only reads: every instance call it makes is on {@code state}, {@code local} or what
     * such a call returned, its only static call boxes a boolean, and it writes, links and throws nothing.
     */
    static boolean pureCallback(String owner, MethodNode handler, int first, int state, int callback, int local) {
        HandlerFlow flow = HandlerFlow.of(owner, handler);
        if (flow == null || flow.reads(0) || flow.reads(first)) return false;
        Set<AbstractInsnNode> reads = Collections.newSetFromMap(new IdentityHashMap<>());
        for (boolean grew = true; grew; ) {
            grew = false;
            for (MethodInsnNode call : flow.calls(c -> c.getOpcode() != Opcodes.INVOKESTATIC && !c.owner.equals(CIR))) {
                if (reads.contains(call)) continue;
                Set<AbstractInsnNode> receiver = flow.argument(call, 0);
                if (!receiver.isEmpty() && receiver.stream().allMatch(o -> HandlerFlow.isParameter(o, state) || HandlerFlow.isParameter(o, local) || reads.contains(o))) {
                    reads.add(call); grew = true;
                }
            }
        }
        boolean[] decided = {false};
        boolean allowed = flow.onlyCalls(c -> {
            if (c.owner.equals(CIR)) {
                if (!HandlerFlow.onlyParameter(flow.argument(c, 0), callback)) return false;
                if (c.name.equals("cancel") && c.desc.equals("()V")) { decided[0] = true; return true; }
                if (c.name.equals("setReturnValue") && c.desc.equals("(Ljava/lang/Object;)V")) { decided[0] = true; return true; }
                return false;
            }
            if (c.getOpcode() == Opcodes.INVOKESTATIC) return c.owner.equals("java/lang/Boolean") && c.name.equals("valueOf") && c.desc.equals("(Z)Ljava/lang/Boolean;");
            return reads.contains(c);
        });
        if (!allowed || !decided[0]) return false;
        // The callback info goes nowhere but those calls.
        for (HandlerFlow.Use use : flow.uses(parameterNode(flow, callback)))
            if (!(use.instruction() instanceof MethodInsnNode call && call.owner.equals(CIR) && use.operand() == 0)) return false;
        return true;
    }

    /** The parameter value of {@code slot} as the flow knows it, or null when the body never loads it. */
    private static AbstractInsnNode parameterNode(HandlerFlow flow, int slot) {
        for (int i = 0; i < flow.method.instructions.size(); i++) {
            AbstractInsnNode instruction = flow.method.instructions.get(i);
            if (instruction instanceof VarInsnNode load && load.var == slot && load.getOpcode() == Opcodes.ALOAD)
                for (AbstractInsnNode origin : flow.origins(new org.objectweb.asm.tree.analysis.SourceValue(1, instruction)))
                    if (HandlerFlow.isParameter(origin, slot)) return origin;
        }
        return null;
    }

    /**
     * The accesses the decision makes on its two values — the captured local and the state argument — keyed
     * {@code "local"} and {@code "state"}: as the native host makes them (on the anchor's result and on its argument) and as
     * the callback does (on its captured local and its state parameter). Null when a body cannot be read or a value has none.
     */
    private static Map<String, Set<String>> roots(String owner, MethodNode host, MethodInsnNode anchor, String mixin, Source source) {
        HandlerFlow flow = HandlerFlow.of(owner, host), callback = HandlerFlow.of(mixin, source.handler());
        if (flow == null || callback == null) return null;
        Type[] hostArgs = Type.getArgumentTypes(host.desc); int[] slots = DefaultMethodOverloadBridge.slots(hostArgs, (host.access & Opcodes.ACC_STATIC) != 0);
        Set<String> local = new LinkedHashSet<>(accesses(flow, o -> o == anchor)), state = new LinkedHashSet<>(accesses(flow, o -> HandlerFlow.isParameter(o, slots[1])));
        local.addAll(accesses(callback, o -> HandlerFlow.isParameter(o, source.below())));
        state.addAll(accesses(callback, o -> HandlerFlow.isParameter(o, source.above())));
        if (local.isEmpty() || state.isEmpty()) return null;
        return Map.of("local", local, "state", state);
    }

    /**
     * Every access a body makes on a value {@code root} accepts: each call on it, keyed by member and constant arguments
     * (a static field read, a literal; anything else "?"), and each such call's result tested with instanceof.
     */
    static Set<String> accesses(HandlerFlow flow, java.util.function.Predicate<AbstractInsnNode> root) {
        Set<String> out = new LinkedHashSet<>();
        Map<AbstractInsnNode, String> calls = new IdentityHashMap<>();
        for (MethodInsnNode call : flow.calls(c -> c.getOpcode() != Opcodes.INVOKESTATIC)) {
            Set<AbstractInsnNode> receiver = flow.argument(call, 0);
            if (receiver.isEmpty() || !receiver.stream().allMatch(root)) continue;
            StringBuilder key = new StringBuilder(call.name).append(call.desc).append('(');
            for (int k = 1; k <= Type.getArgumentTypes(call.desc).length; k++) {
                AbstractInsnNode argument = HandlerFlow.only(flow.argument(call, k));
                key.append(argument instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC ? field.owner + "." + field.name
                        : argument instanceof LdcInsnNode constant ? String.valueOf(constant.cst) : "?").append(',');
            }
            String access = key.append(')').toString(); out.add(access); calls.put(call, access);
        }
        for (AbstractInsnNode instruction : flow.method.instructions)
            if (instruction instanceof TypeInsnNode test && test.getOpcode() == Opcodes.INSTANCEOF) {
                AbstractInsnNode tested = HandlerFlow.only(flow.operand(test, 0, 1));
                if (calls.containsKey(tested)) out.add(calls.get(tested) + " instanceof " + test.desc);
            }
        return out;
    }

    private static boolean dormantSource(Source source, ClassNode current, ClassNode original) {
        // The host is the one method the callback binds in the class the mod was compiled against, as Mixin binds it.
        MethodNode host = MixinCallbackShape.written(source.handler(), original);
        if (host == null || (host.access & Opcodes.ACC_PRIVATE) == 0 || !Type.getReturnType(host.desc).equals(Type.BOOLEAN_TYPE)) return false;
        Type[] hostArgs = Type.getArgumentTypes(host.desc), handlerArgs = Type.getArgumentTypes(source.handler().desc);
        if (hostArgs.length != 2 || !hostArgs[0].equals(handlerArgs[0]) || !hostArgs[1].equals(handlerArgs[1])) return false;
        // The anchor, however written, selects one call there.
        List<MethodInsnNode> points = MixinCallbackShape.calls(MixinFit.atNodes(source.inject()).getFirst(), host);
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

    /**
     * The default as the native decision's new home: a public, receiver-independent boolean default whose parameters of the
     * state type are each accessed only as the decision accesses exactly one of its two values — one parameter standing for
     * the captured local, one for the state argument, and none for both or neither.
     */
    private static Plan predicate(Source source, ClassNode owner, MethodNode method, Map<String, Set<String>> roots) {
        if (!method.tryCatchBlocks.isEmpty() || (method.access & Opcodes.ACC_PUBLIC) == 0
                || !Type.getReturnType(method.desc).equals(Type.BOOLEAN_TYPE)) return null;
        HandlerFlow flow = HandlerFlow.of(owner.name, method);
        if (flow == null || flow.reads(0)) return null;
        Type[] args = Type.getArgumentTypes(method.desc); int[] slots = DefaultMethodOverloadBridge.slots(args, false);
        Type state = Type.getArgumentTypes(source.handler().desc)[1];
        int below = -1, above = -1;
        for (int i = 0; i < args.length; i++) {
            if (!args[i].equals(state)) continue;
            int slot = slots[i];
            Set<String> here = accesses(flow, o -> HandlerFlow.isParameter(o, slot));
            if (here.isEmpty()) continue;
            // Every access the default makes on it is one the decision makes on that value: the default decides on the
            // same members with the same constants, never on something else.
            boolean local = roots.get("local").containsAll(here), host = roots.get("state").containsAll(here);
            if (local == host) return null;
            if (local) { if (below >= 0) return null; below = i; }
            else { if (above >= 0) return null; above = i; }
        }
        return below >= 0 && above >= 0 ? new Plan(owner, method, below, above) : null;
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
