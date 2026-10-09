/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.*;
import java.util.function.Function;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import net.forbric.kernel.util.ByteScan;
import net.forbric.kernel.util.ForbricLog;

/**
 * Lets an element registered after a mod's whole-registry pass receive the per-element callback that pass gave every
 * element it saw.
 *
 * <p>The kernel registers Forge-family content in more than one wave; on the client the last one is inside
 * {@code Minecraft.<init>}, after the point where a Fabric instance has already registered everything. A Fabric mod that
 * initialises per-element state in one pass at "everything is registered now" therefore misses the later elements, and
 * a mod that throws rather than computing a missed element later crashes on the first one it meets.
 *
 * <p>{@link RegistryWalkProof} decides, from data flow and control flow, which methods are complete walks of a platform
 * registry giving each element an unconditional interface callback; it does not care how the walk was written. Each
 * proved root records, in a batch local to that call, which elements received which callback, and publishes the batch
 * only at a normal return. {@code RegistryElementCallbacks.completeLateRegistrations()} later gives each recorded
 * callback, once, to every element of that registry the walk did not see.
 */
public final class RegistryElementCallbackInjector implements ClassTransformer {
    /** {@code -Dforbric.registryElementCallbacks=off} leaves every walk, and its late completion, where the mod put it. */
    public static final String PROPERTY = "forbric.registryElementCallbacks";
    /**
     * A walk reads a registry field, so its type is one of the class's constant-pool names, and calls a walk method,
     * so that method's descriptor is one too. Asked of the pool alone: this runs for every class the game loads.
     */
    private static final List<String> POOL_NAMES = new ArrayList<>(RegistryWalkProof.REGISTRY_TYPES);
    private static final int REGISTRY_NAMES = POOL_NAMES.size();
    static { POOL_NAMES.addAll(List.of("()Ljava/util/Iterator;", "(Ljava/util/function/Consumer;)V", "(I)Ljava/lang/Object;")); }
    private static final byte[][] POOL_ENTRIES = POOL_NAMES.stream().map(ByteScan::poolEntry).toArray(byte[][]::new);
    private static final String HOOK = "net/forbric/kernel/interop/RegistryElementCallbacks";
    private static final String CONSUMER = "Ljava/util/function/Consumer;";
    private final Function<String, ClassNode> declarations;

    public RegistryElementCallbackInjector(Function<String, ClassNode> declarations) { this.declarations = declarations; }

    @Override public AnchorSet anchors() { return AnchorSet.scanned("complete per-element walks of platform registries"); }

    @Override public byte[] transform(String name, byte[] bytes, TransformContext context) {
        if (bytes == null || !namesAWalk(bytes) || "off".equalsIgnoreCase(net.forbric.kernel.util.ForbricSwitches.get(PROPERTY, "on"))) return bytes;
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        boolean changed = false;
        for (MethodNode method : node.methods) {
            RegistryWalkProof.Proof proof = RegistryWalkProof.prove(node, method, declarations);
            if (proof == null || !instrument(node, method, proof)) continue;
            changed = true;
            // The only trace this mechanism leaves at transform time; the late completion logs its own count.
            Map<RegistryWalkProof.Field, Integer> callbacks = new LinkedHashMap<>();
            for (RegistryWalkProof.Walk walk : proof.walks()) callbacks.merge(walk.registry(), walk.callbacks().size(), Integer::sum);
            callbacks.forEach((registry, count) -> ForbricLog.info("[Forbric/RegistryCallbacks] %s.%s is a closed walk of the %s "
                    + "registry with %d per-element callback(s) — an element registered after the walk will receive the same "
                    + "callback(s) once", node.name.replace('/', '.'), method.name, registry.label(), count));
        }
        if (!changed) return bytes;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static boolean namesAWalk(byte[] bytes) {
        boolean[] found = ByteScan.constantPoolNames(bytes, POOL_ENTRIES);
        boolean registry = false, walk = false;
        for (int i = 0; i < found.length; i++) if (found[i]) { if (i < REGISTRY_NAMES) registry = true; else walk = true; }
        return registry && walk;
    }

    /** Gives the root a batch token in a new local, then records each walk's declaration, completions and commit. */
    private static boolean instrument(ClassNode owner, MethodNode method, RegistryWalkProof.Proof proof) {
        boolean instance = (method.access & Opcodes.ACC_STATIC) == 0;
        int token = Type.getArgumentsAndReturnSizes(method.desc) >> 2;
        if (!instance) token--;
        if (!openSlot(method, token)) return false;

        InsnList begin = new InsnList();
        begin.add(new LdcInsnNode(Type.getObjectType(owner.name)));
        begin.add(new LdcInsnNode(method.name));
        begin.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "begin", "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Object;", false));
        begin.add(new VarInsnNode(Opcodes.ASTORE, token));
        method.instructions.insert(begin);

        for (RegistryWalkProof.Walk walk : proof.walks()) {
            // Declared where the walk starts, so an empty registry still declares it and a skipped walk does not.
            InsnList start = new InsnList();
            for (RegistryWalkProof.Callback callback : walk.callbacks()) {
                if (walk.form() != RegistryWalkProof.Form.FOR_EACH) { start.add(declare(token, callback, walk.registry())); continue; }
                // The consumer is on the stack: wrap it so each element it returns from normally is recorded.
                start.add(row(token, callback, walk.registry()));
                start.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "recording", "(" + CONSUMER
                        + "Ljava/lang/Object;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Object;)" + CONSUMER, false));
            }
            if (walk.form() == RegistryWalkProof.Form.ITERATOR) method.instructions.insert(walk.start(), start);
            else method.instructions.insertBefore(walk.start(), start);
            for (RegistryWalkProof.Callback callback : walk.callbacks())
                if (callback.call() != null) completed(method, token, callback, walk.registry());
        }
        for (AbstractInsnNode exit : proof.returns()) {
            InsnList commit = new InsnList();
            commit.add(new VarInsnNode(Opcodes.ALOAD, token));
            commit.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "commit", "(Ljava/lang/Object;)V", false));
            method.instructions.insertBefore(exit, commit);
        }
        return true;
    }

    private static InsnList row(int token, RegistryWalkProof.Callback callback, RegistryWalkProof.Field registry) {
        InsnList row = new InsnList();
        row.add(new VarInsnNode(Opcodes.ALOAD, token));
        row.add(new LdcInsnNode(Type.getObjectType(callback.contract())));
        row.add(new LdcInsnNode(callback.member()));
        row.add(new FieldInsnNode(Opcodes.GETSTATIC, registry.owner(), registry.name(), registry.desc()));
        return row;
    }

    private static InsnList declare(int token, RegistryWalkProof.Callback callback, RegistryWalkProof.Field registry) {
        InsnList declare = row(token, callback, registry);
        declare.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "declare", "(Ljava/lang/Object;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Object;)V", false));
        return declare;
    }

    /** Keeps the callback's receiver, and once the callback returned, records that element against it. */
    private static void completed(MethodNode method, int token, RegistryWalkProof.Callback callback, RegistryWalkProof.Field registry) {
        MethodInsnNode call = callback.call();
        method.instructions.insertBefore(call, new InsnNode(Opcodes.DUP));
        InsnList record = new InsnList();
        int result = Type.getReturnType(call.desc).getSize();
        // A callback's result is discarded by the mod; move it above the kept element so the element is consumed first.
        if (result == 1) record.add(new InsnNode(Opcodes.SWAP));
        else if (result == 2) { record.add(new InsnNode(Opcodes.DUP2_X1)); record.add(new InsnNode(Opcodes.POP2)); }
        InsnList row = row(token, callback, registry);
        record.add(row);
        record.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "completed",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Object;)V", false));
        method.instructions.insert(call, record);
    }

    /**
     * Opens local {@code slot} (the first after the parameters) for the token: every later local moves up by one, and each
     * expanded stack map frame gains the token's entry where that slot starts. Moving the method's own locals rather than
     * appending avoids computing frames, which would load arbitrary guest types.
     */
    private static boolean openSlot(MethodNode method, int slot) {
        List<FrameNode> frames = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (!(insn instanceof FrameNode frame)) continue;
            if (frame.type != Opcodes.F_NEW) return false;
            frames.add(frame);
            if (position(frame.local, slot) < 0) return false;
        }
        for (FrameNode frame : frames) {
            List<Object> local = frame.local == null ? new ArrayList<>() : new ArrayList<>(frame.local);
            int at = position(local, slot);
            int covered = 0;
            for (int i = 0; i < at; i++) covered += local.get(i) == Opcodes.LONG || local.get(i) == Opcodes.DOUBLE ? 2 : 1;
            while (covered < slot) { local.add(at++, Opcodes.TOP); covered++; }
            local.add(at, "java/lang/Object");
            frame.local = local;
        }
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof VarInsnNode variable && variable.var >= slot) variable.var++;
            else if (insn instanceof IincInsnNode increment && increment.var >= slot) increment.var++;
        }
        if (method.localVariables != null) for (LocalVariableNode local : method.localVariables) if (local.index >= slot) local.index++;
        for (List<LocalVariableAnnotationNode> annotations : Arrays.asList(method.visibleLocalVariableAnnotations, method.invisibleLocalVariableAnnotations))
            if (annotations != null) for (LocalVariableAnnotationNode annotation : annotations)
                for (int j = 0; j < annotation.index.size(); j++) if (annotation.index.get(j) >= slot) annotation.index.set(j, annotation.index.get(j) + 1);
        method.maxLocals++;
        return true;
    }

    /** The list position whose entry starts at {@code slot}, or the end when the frame stops before it; -1 if an entry straddles it. */
    private static int position(List<Object> local, int slot) {
        if (local == null) return 0;
        int covered = 0, at = 0;
        while (at < local.size() && covered < slot) {
            Object type = local.get(at++);
            covered += type == Opcodes.LONG || type == Opcodes.DOUBLE ? 2 : 1;
        }
        return covered > slot ? -1 : at;
    }
}
