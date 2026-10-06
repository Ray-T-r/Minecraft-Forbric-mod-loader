/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.*;
import java.util.function.Function;
import net.forbric.api.Ecosystem;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/**
 * Moves an unconditional pure shared-value redirect to the carrier's corresponding result operation. The
 * original shared producer and handler body are retained: a snapshot is never declared equal to a live value.
 * Correspondence requires the complete same result-consumer CFG and destination operand provenance in the
 * indexed original game. Surrounding carrier guards are retained; no equivalence between those guards is claimed.
 */
public final class MixinSharedResultTransport {
    public static final String PROPERTY = "forbric.mixinSharedResults";
    private static final String REDIRECT = "Lorg/spongepowered/asm/mixin/injection/Redirect;";
    private static final String WRAP = "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";
    private static final String OPERATION = "com/llamalad7/mixinextras/injector/wrapoperation/Operation";
    private static final String SHARE = "Lcom/llamalad7/mixinextras/sugar/Share;";
    private static final String REF = "com/llamalad7/mixinextras/sugar/ref/LocalRef";
    private static final String SUFFIX = "$forbricsharedresult";
    private MixinSharedResultTransport() { }

    public static int adapt(ClassNode mixin, Ecosystem ecosystem, Function<String, ClassNode> classes) {
        return adapt(mixin, classes, owner -> NativeGameReferences.reference(ecosystem, owner));
    }

    static int adapt(ClassNode mixin, Function<String, ClassNode> classes, Function<String, ClassNode> references) {
        if ("off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"))) return 0;
        List<String> targets = MixinFit.mixinTargets(mixin);
        if (targets.size() != 1) return 0;
        ClassNode current = classes.apply(targets.getFirst()), reference = references.apply(targets.getFirst());
        if (current == null || reference == null) return 0;
        List<MethodNode> added = new ArrayList<>();
        for (MethodNode handler : new ArrayList<>(mixin.methods)) {
            List<AnnotationNode> annotations = annotations(handler);
            List<AnnotationNode> injectors = annotations.stream().filter(a -> FinalMixinApplications.isInjector(a.desc)).toList();
            if (injectors.size() != 1 || !injectors.getFirst().desc.equals(REDIRECT) || handler.name.contains(SUFFIX)
                    || (handler.access & Opcodes.ACC_SYNCHRONIZED) != 0
                    || annotations.stream().anyMatch(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Group;"))) continue;
            AnnotationNode injection = injectors.getFirst();
            if (MixinFit.value(injection, "slice") != null) continue;
            List<AnnotationNode> points = MixinFit.atNodes(injection);
            if (points.size() != 1) continue;
            AnnotationNode at = points.getFirst();
            if (!"INVOKE".equals(MixinFit.value(at, "value")) || MixinFit.value(at, "shift") != null
                    || MixinFit.value(at, "ordinal") instanceof Number n && n.intValue() >= 0) continue;
            Object oldTarget = MixinFit.value(at, "target");
            if (!(oldTarget instanceof String member)) continue;
            List<MethodNode> hosts = selected(current, injection);
            if (hosts.size() != 1) continue;
            MethodNode host = hosts.getFirst();
            List<MethodNode> originals = reference.methods.stream().filter(m -> m.name.equals(host.name) && m.desc.equals(host.desc)).toList();
            if (originals.size() != 1) continue;
            List<MethodInsnNode> originalCalls = calls(originals.getFirst(), member), stillOriginal = calls(host, member);
            if (originalCalls.size() != 1 || !stillOriginal.isEmpty()) continue;
            MethodInsnNode originalCall = originalCalls.getFirst();
            Source source = source(handler, originalCall);
            if (source == null) continue;
            List<MethodInsnNode> candidates = calls(host, target(source.operation()));
            if (candidates.size() != 1 || candidates.getFirst().getOpcode() == Opcodes.INVOKESTATIC
                    || !ConsumedResultRegions.same(current.name, originals.getFirst(), originalCall, host, candidates.getFirst())) continue;
            if (hasHeadProducer(mixin, injection, source.share()))
                added.add(wrap(mixin, handler, injection, at, originalCall, candidates.getFirst(), source.capture()));
            else {
                Closed closed = closed(mixin, handler, injection, source, originals.getFirst(), originalCall, host, candidates.getFirst(), current.name);
                if (closed != null) added.addAll(wrapClosed(mixin, handler, injection, at, candidates.getFirst(), closed));
            }
        }
        mixin.methods.addAll(added); return added.size();
    }

    private record Source(MethodInsnNode operation, int capture, String share) { }
    private record Closed(MethodNode producer, AnnotationNode injection, MethodInsnNode projection) { }

    private static Closed closed(ClassNode mixin, MethodNode consumer, AnnotationNode consumerInjection, Source source,
                                 MethodNode original, MethodInsnNode originalConsumer, MethodNode host,
                                 MethodInsnNode live, String owner) {
        if (Type.getArgumentTypes(originalConsumer.desc).length != 0) return null;
        List<Closed> matches = new ArrayList<>();
        for (MethodNode producer : mixin.methods) {
            if (producer == consumer || ((producer.access ^ consumer.access) & Opcodes.ACC_STATIC) != 0
                    || (producer.access & Opcodes.ACC_SYNCHRONIZED) != 0 || !producer.tryCatchBlocks.isEmpty()) continue;
            List<AnnotationNode> all = annotations(producer);
            List<AnnotationNode> injecting = all.stream().filter(a -> FinalMixinApplications.isInjector(a.desc)).toList();
            if (injecting.size() != 1 || !injecting.getFirst().desc.equals(WRAP)
                    || all.stream().anyMatch(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Group;"))) continue;
            AnnotationNode injection = injecting.getFirst();
            if (!Objects.equals(MixinFit.value(injection, "method"), MixinFit.value(consumerInjection, "method"))
                    || MixinFit.value(injection, "slice") != null) continue;
            List<AnnotationNode> points = MixinFit.atNodes(injection);
            if (points.size() != 1) continue;
            AnnotationNode at = points.getFirst();
            if (!"INVOKE".equals(MixinFit.value(at, "value")) || MixinFit.value(at, "shift") != null
                    || MixinFit.value(at, "ordinal") instanceof Number number && number.intValue() >= 0) continue;
            Object raw = MixinFit.value(at, "target"); if (!(raw instanceof String target)) continue;
            List<MethodInsnNode> projections = calls(original, target);
            if (projections.size() != 1 || !calls(host, target).isEmpty()) continue;
            MethodInsnNode projection = projections.getFirst();
            if (projection.getOpcode() == Opcodes.INVOKESTATIC || Type.getArgumentTypes(projection.desc).length != 0
                    || !Type.getReturnType(projection.desc).equals(Type.getObjectType(originalConsumer.owner))
                    || !projection.owner.equals(live.owner) || !pureProducer(producer, projection, source.share())) continue;
            if (!ConsumedResultRegions.closedReceiver(owner, original, projection, originalConsumer, host, live)) continue;
            matches.add(new Closed(producer, injection, projection));
        }
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private static boolean pureProducer(MethodNode method, MethodInsnNode projection, String key) {
        Type[] args = Type.getArgumentTypes(method.desc);
        if (args.length != 3 || !args[0].equals(Type.getObjectType(projection.owner))
                || !args[1].equals(Type.getObjectType(OPERATION)) || !args[2].equals(Type.getObjectType(REF))
                || !Type.getReturnType(method.desc).equals(Type.getReturnType(projection.desc))) return false;
        AnnotationNode share = annotation(method.visibleParameterAnnotations, 2);
        if (share == null) share = annotation(method.invisibleParameterAnnotations, 2);
        if (share == null || !key.equals(MixinFit.value(share, "value"))) return false;
        List<AbstractInsnNode> code = DefaultMethodOverloadBridge.real(method);
        int[] slots = DefaultMethodOverloadBridge.slots(args, (method.access & Opcodes.ACC_STATIC) != 0);
        return code.size() == 13 && load(code.get(0), slots[2]) && load(code.get(1), slots[0])
                && call(code.get(2), REF, "set", "(Ljava/lang/Object;)V") && load(code.get(3), slots[1])
                && code.get(4).getOpcode() == Opcodes.ICONST_1
                && code.get(5) instanceof TypeInsnNode array && array.getOpcode() == Opcodes.ANEWARRAY && array.desc.equals("java/lang/Object")
                && code.get(6).getOpcode() == Opcodes.DUP && code.get(7).getOpcode() == Opcodes.ICONST_0
                && load(code.get(8), slots[0]) && code.get(9).getOpcode() == Opcodes.AASTORE
                && call(code.get(10), OPERATION, "call", "([Ljava/lang/Object;)Ljava/lang/Object;")
                && code.get(11) instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST
                && cast.desc.equals(Type.getReturnType(projection.desc).getInternalName()) && code.get(12).getOpcode() == Opcodes.ARETURN;
    }

    private static boolean load(AbstractInsnNode instruction, int slot) {
        return instruction instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == slot;
    }
    private static boolean call(AbstractInsnNode instruction, String owner, String name, String descriptor) {
        return instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEINTERFACE
                && call.owner.equals(owner) && call.name.equals(name) && call.desc.equals(descriptor);
    }

    private static List<MethodNode> wrapClosed(ClassNode mixin, MethodNode consumer, AnnotationNode injection,
                                              AnnotationNode at, MethodInsnNode live, Closed closed) {
        MethodNode producer = closed.producer();
        boolean isStatic = (consumer.access & Opcodes.ACC_STATIC) != 0;
        String consumerName = MixinHandlerShim.asideName(mixin.name, consumer.name, SUFFIX);
        String producerName = MixinHandlerShim.asideName(mixin.name, producer.name, "$forbricsharedproducer");
        String projectionName = MixinHandlerShim.asideName(mixin.name, producer.name, "$forbricshareprojection");
        Type[] args = {Type.getObjectType(live.owner), Type.getObjectType(REF)};
        MethodNode outer = new MethodNode(consumer.access, consumer.name,
                Type.getMethodDescriptor(Type.getReturnType(consumer.desc), args), null, consumer.exceptions.toArray(String[]::new));
        boolean visible = consumer.visibleAnnotations != null && consumer.visibleAnnotations.remove(injection);
        if (!visible) consumer.invisibleAnnotations.remove(injection);
        if (visible) outer.visibleAnnotations = new ArrayList<>(List.of(injection)); else outer.invisibleAnnotations = new ArrayList<>(List.of(injection));
        put(at, "target", target(live));
        outer.visibleParameterAnnotations = moved(consumer.visibleParameterAnnotations, 1, 2);
        outer.invisibleParameterAnnotations = moved(consumer.invisibleParameterAnnotations, 1, 2);
        if (producer.visibleAnnotations != null) producer.visibleAnnotations.remove(closed.injection());
        if (producer.invisibleAnnotations != null) producer.invisibleAnnotations.remove(closed.injection());
        int[] slots = DefaultMethodOverloadBridge.slots(args, isStatic);
        if (!isStatic) outer.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        outer.instructions.add(new VarInsnNode(Opcodes.ALOAD, slots[0]));
        Handle metafactory = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);
        Type signature = Type.getMethodType("([Ljava/lang/Object;)Ljava/lang/Object;");
        outer.instructions.add(new InvokeDynamicInsnNode("call", "()L" + OPERATION + ";", metafactory, signature,
                new Handle(Opcodes.H_INVOKESTATIC, mixin.name, projectionName, signature.getDescriptor(), (mixin.access & Opcodes.ACC_INTERFACE) != 0), signature));
        outer.instructions.add(new VarInsnNode(Opcodes.ALOAD, slots[1]));
        outer.instructions.add(MixinHandlerShim.callOwn(mixin, isStatic, producerName, producer.desc));
        int projected = (isStatic ? 0 : 1) + 2;
        outer.instructions.add(new VarInsnNode(Opcodes.ASTORE, projected));
        if (!isStatic) outer.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        outer.instructions.add(new VarInsnNode(Opcodes.ALOAD, projected));
        outer.instructions.add(new VarInsnNode(Opcodes.ALOAD, slots[1]));
        outer.instructions.add(MixinHandlerShim.callOwn(mixin, isStatic, consumerName, consumer.desc));
        outer.instructions.add(new InsnNode(Type.getReturnType(consumer.desc).getOpcode(Opcodes.IRETURN)));
        outer.maxLocals = projected + 1; outer.maxStack = 4;
        MethodNode projection = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                projectionName, signature.getDescriptor(), null, null);
        projection.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0)); projection.instructions.add(new InsnNode(Opcodes.ICONST_0));
        projection.instructions.add(new InsnNode(Opcodes.AALOAD));
        projection.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, closed.projection().owner));
        MethodInsnNode call = closed.projection();
        projection.instructions.add(new MethodInsnNode(call.getOpcode(), call.owner, call.name, call.desc, call.itf));
        projection.instructions.add(new InsnNode(Opcodes.ARETURN)); projection.maxLocals = 1; projection.maxStack = 2;
        consumer.name = consumerName; producer.name = producerName;
        return List.of(outer, projection);
    }

    private static Source source(MethodNode handler, MethodInsnNode old) {
        if (!handler.tryCatchBlocks.isEmpty()) return null;
        Type[] args = Type.getArgumentTypes(handler.desc), oldArgs = Type.getArgumentTypes(old.desc);
        int inputs = oldArgs.length + (old.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1);
        if (args.length != inputs + 1 || !Type.getReturnType(handler.desc).equals(Type.getReturnType(old.desc))) return null;
        if (old.getOpcode() != Opcodes.INVOKESTATIC && !args[0].equals(Type.getObjectType(old.owner))) return null;
        for (int index = 0; index < oldArgs.length; index++)
            if (!args[index + (inputs > oldArgs.length ? 1 : 0)].equals(oldArgs[index])) return null;
        if (!args[inputs].equals(Type.getObjectType(REF))) return null;
        AnnotationNode share = annotation(handler.visibleParameterAnnotations, inputs);
        if (share == null) share = annotation(handler.invisibleParameterAnnotations, inputs);
        if (share == null || !(MixinFit.value(share, "value") instanceof String key)) return null;
        List<AbstractInsnNode> code = DefaultMethodOverloadBridge.real(handler);
        int[] slots = DefaultMethodOverloadBridge.slots(args, (handler.access & Opcodes.ACC_STATIC) != 0);
        if (code.size() != 5 || !(code.getFirst() instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD
                || load.var != slots[inputs] || !(code.get(1) instanceof MethodInsnNode read)
                || !read.owner.equals(REF) || !read.name.equals("get") || !read.desc.equals("()Ljava/lang/Object;")
                || !(code.get(2) instanceof TypeInsnNode cast) || cast.getOpcode() != Opcodes.CHECKCAST
                || !(code.get(3) instanceof MethodInsnNode operation)
                || operation.getOpcode() != Opcodes.INVOKEVIRTUAL && operation.getOpcode() != Opcodes.INVOKEINTERFACE
                || !cast.desc.equals(operation.owner) || Type.getArgumentTypes(operation.desc).length != 0
                || !Type.getReturnType(operation.desc).equals(Type.getReturnType(handler.desc))
                || code.getLast().getOpcode() != Type.getReturnType(handler.desc).getOpcode(Opcodes.IRETURN)) return null;
        // This exact body never loads any old operation argument. Null/zero placeholders cannot affect its result.
        return new Source(operation, inputs, key);
    }

    private static boolean hasHeadProducer(ClassNode mixin, AnnotationNode consumer, String share) {
        for (MethodNode producer : mixin.methods) {
            AnnotationNode injection = MixinFit.injectorOf(producer);
            if (injection == null || !injection.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")
                    || !Objects.equals(MixinFit.value(injection, "method"), MixinFit.value(consumer, "method"))) continue;
            List<AnnotationNode> at = MixinFit.atNodes(injection);
            if (at.size() != 1 || !"HEAD".equals(MixinFit.value(at.getFirst(), "value"))) continue;
            for (int index = 0; index < Type.getArgumentTypes(producer.desc).length; index++) {
                AnnotationNode capture = annotation(producer.visibleParameterAnnotations, index);
                if (capture == null) capture = annotation(producer.invisibleParameterAnnotations, index);
                if (capture != null && share.equals(MixinFit.value(capture, "value"))) return true;
            }
        }
        return false;
    }

    private static MethodNode wrap(ClassNode mixin, MethodNode original, AnnotationNode injection, AnnotationNode at,
                                   MethodInsnNode old, MethodInsnNode live, int capture) {
        Type[] nativeArgs = Type.getArgumentTypes(live.desc), sourceArgs = Type.getArgumentTypes(original.desc);
        Type[] args = new Type[nativeArgs.length + 2]; args[0] = Type.getObjectType(live.owner);
        System.arraycopy(nativeArgs, 0, args, 1, nativeArgs.length); args[args.length - 1] = sourceArgs[capture];
        boolean isStatic = (original.access & Opcodes.ACC_STATIC) != 0;
        String inner = MixinHandlerShim.asideName(mixin.name, original.name, SUFFIX);
        MethodNode wrapper = new MethodNode(original.access, original.name,
                Type.getMethodDescriptor(Type.getReturnType(original.desc), args), null, original.exceptions.toArray(String[]::new));
        boolean visible = original.visibleAnnotations != null && original.visibleAnnotations.remove(injection);
        if (!visible) original.invisibleAnnotations.remove(injection);
        if (visible) wrapper.visibleAnnotations = new ArrayList<>(List.of(injection)); else wrapper.invisibleAnnotations = new ArrayList<>(List.of(injection));
        put(at, "target", target(live));
        wrapper.visibleParameterAnnotations = moved(original.visibleParameterAnnotations, capture, args.length);
        wrapper.invisibleParameterAnnotations = moved(original.invisibleParameterAnnotations, capture, args.length);
        if (!isStatic) wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        for (int index = 0; index < capture; index++) placeholder(wrapper, sourceArgs[index]);
        int[] slots = DefaultMethodOverloadBridge.slots(args, isStatic);
        wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, slots[args.length - 1]));
        wrapper.instructions.add(MixinHandlerShim.callOwn(mixin, isStatic, inner, original.desc));
        wrapper.instructions.add(new InsnNode(Type.getReturnType(original.desc).getOpcode(Opcodes.IRETURN)));
        wrapper.maxLocals = isStatic ? 0 : 1; for (Type argument : args) wrapper.maxLocals += argument.getSize();
        wrapper.maxStack = isStatic ? 0 : 1; for (Type argument : sourceArgs) wrapper.maxStack += argument.getSize();
        original.name = inner; return wrapper;
    }

    private static void placeholder(MethodNode method, Type type) {
        int opcode = switch (type.getSort()) {
            case Type.LONG -> Opcodes.LCONST_0; case Type.DOUBLE -> Opcodes.DCONST_0; case Type.FLOAT -> Opcodes.FCONST_0;
            case Type.ARRAY, Type.OBJECT -> Opcodes.ACONST_NULL; default -> Opcodes.ICONST_0;
        }; method.instructions.add(new InsnNode(opcode));
    }
    @SuppressWarnings("unchecked") private static List<AnnotationNode>[] moved(List<AnnotationNode>[] source, int index, int count) {
        if (source == null) return null; List<AnnotationNode>[] out = new List[count];
        if (source[index] != null) out[count - 1] = new ArrayList<>(source[index]); return out;
    }
    private static AnnotationNode annotation(List<AnnotationNode>[] source, int index) {
        if (source == null || index >= source.length || source[index] == null) return null;
        List<AnnotationNode> shares = source[index].stream().filter(a -> a.desc.equals(SHARE)).toList();
        return shares.size() == 1 ? shares.getFirst() : null;
    }
    private static List<AnnotationNode> annotations(MethodNode method) {
        List<AnnotationNode> out = new ArrayList<>(); if (method.visibleAnnotations != null) out.addAll(method.visibleAnnotations);
        if (method.invisibleAnnotations != null) out.addAll(method.invisibleAnnotations); return out;
    }
    private static List<MethodNode> selected(ClassNode owner, AnnotationNode injection) {
        Object raw = MixinFit.value(injection, "method"); List<?> selectors = raw instanceof List<?> list ? list : List.of(raw);
        return owner.methods.stream().filter(m -> selectors.contains(m.name) || selectors.contains(m.name + m.desc)).toList();
    }
    private static List<MethodInsnNode> calls(MethodNode host, String target) {
        List<MethodInsnNode> out = new ArrayList<>(); for (var instruction : host.instructions)
            if (instruction instanceof MethodInsnNode call && target(call).equals(target)) out.add(call); return out;
    }
    private static String target(MethodInsnNode call) { return "L" + call.owner + ";" + call.name + call.desc; }
    private static void put(AnnotationNode node, String key, Object value) {
        for (int index = 0; index + 1 < node.values.size(); index += 2) if (key.equals(node.values.get(index))) { node.values.set(index + 1, value); return; }
        node.values.add(key); node.values.add(value);
    }
}
