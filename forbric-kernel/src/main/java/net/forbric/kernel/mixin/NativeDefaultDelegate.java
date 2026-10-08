/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.*;
import java.util.function.Function;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Composes native pure providers/default delegates, then compares the complete terminal body with a handler. */
final class NativeDefaultDelegate {
    private NativeDefaultDelegate() { }
    private record Body(ClassNode owner, MethodNode method) { }
    static boolean equivalent(MethodNode handler, MethodInsnNode replacement, Function<String, ClassNode> nativeClasses) {
        List<Type> operands = operandTypes(replacement);
        if (!Type.getReturnType(handler.desc).equals(Type.getReturnType(replacement.desc))
                || !List.of(Type.getArgumentTypes(handler.desc)).equals(operands)) return false;
        Map<Integer, NativeCallChanges.Expr> inputs = new HashMap<>();
        for (int i = 0; i < operands.size(); i++) inputs.put(i, new NativeCallChanges.Expr("input", Integer.toString(i)));
        Body body = resolve(replacement.owner, replacement.name, replacement.desc, nativeClasses, new HashSet<>());
        if (body == null) return false;
        List<NativeCallChanges.Expr> arguments = new ArrayList<>(); for (int i = 0; i < operands.size(); i++) arguments.add(inputs.get(i));
        String expected = terminal(body, arguments, operands, Type.getReturnType(replacement.desc), nativeClasses, new HashSet<>());
        if (expected == null) return false;
        Map<Integer, NativeCallChanges.Expr> handlerSlots = new HashMap<>(); int slot = (handler.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        for (int i = 0; i < operands.size(); i++) { handlerSlots.put(slot, inputs.get(i)); slot += operands.get(i).getSize(); }
        String actual=canonical(handler, handlerSlots, operands, Type.getReturnType(replacement.desc));
        return expected.equals(actual);
    }
    private static String terminal(Body body, List<NativeCallChanges.Expr> inputs, List<Type> rootTypes, Type returned,
            Function<String, ClassNode> classes, Set<String> visiting) {
        String key = body.owner.name + "." + body.method.name + body.method.desc; if (!visiting.add(key)) return null;
        Map<Integer, NativeCallChanges.Expr> slots = slots(body.method, inputs);
        if (slots == null) return null;
        List<AbstractInsnNode> real = real(body.method);
        List<NativeCallChanges.Site> calls = NativeCallChanges.sites(body.owner, body.method);
        if (real.size() >= 2 && real.get(real.size() - 2) instanceof MethodInsnNode forwarded
                && real.getLast().getOpcode() == Type.getReturnType(body.method.desc).getOpcode(Opcodes.IRETURN)
                && body.method.tryCatchBlocks.isEmpty()) {
            boolean pure = true;
            for (AbstractInsnNode i : real) {
                int opcode = i.getOpcode();
                if (i instanceof VarInsnNode variable && opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD) continue;
                if (i instanceof FieldInsnNode && (opcode == Opcodes.GETFIELD || opcode == Opcodes.GETSTATIC)) continue;
                if (i instanceof TypeInsnNode && opcode == Opcodes.CHECKCAST || i instanceof MethodInsnNode || opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) continue;
                pure = false; break;
            }
            NativeCallChanges.Site edge = calls.stream().filter(call -> call.call() == forwarded).findFirst().orElse(null);
            if (pure && edge != null) {
                List<NativeCallChanges.Expr> projected = new ArrayList<>();
                for (NativeCallChanges.Expr expression : edge.operands()) {
                    NativeCallChanges.Expr value = simplify(substitute(expression, slots), classes, new HashSet<>());
                    if (value == null) { pure = false; break; } projected.add(value);
                }
                for (NativeCallChanges.Site provider : calls) if (provider.call() != forwarded) {
                    Body getter = resolve(provider.call().owner, provider.call().name, provider.call().desc, classes, new HashSet<>());
                    if (getter == null || provider(getter, provider.operands()) == null) { pure = false; break; }
                }
                Body next = pure ? resolve(forwarded.owner, forwarded.name, forwarded.desc, classes, new HashSet<>()) : null;
                if (next != null) return terminal(next, projected, rootTypes, returned, classes, visiting);
            }
        }
        return canonical(body.method, slots, rootTypes, returned);
    }
    private static NativeCallChanges.Expr substitute(NativeCallChanges.Expr expression, Map<Integer, NativeCallChanges.Expr> slots) {
        if (expression == null) return null;
        if (expression.kind().equals("parameter")) return slots.get(Integer.parseInt(expression.symbol()));
        List<NativeCallChanges.Expr> args = new ArrayList<>();
        for (var input : expression.inputs()) { var value = substitute(input, slots); if (value == null) return null; args.add(value); }
        return new NativeCallChanges.Expr(expression.kind(), expression.symbol(), args);
    }
    private static NativeCallChanges.Expr simplify(NativeCallChanges.Expr expression, Function<String, ClassNode> classes, Set<String> seen) {
        if (expression == null) return null;
        List<NativeCallChanges.Expr> args = new ArrayList<>();
        for (var input : expression.inputs()) { var value = simplify(input, classes, new HashSet<>(seen)); if (value == null) return null; args.add(value); }
        if (!expression.kind().equals("call")) return new NativeCallChanges.Expr(expression.kind(), expression.symbol(), args);
        MixinFit.Member call = MixinFit.parseMember(expression.symbol());
        if (call == null || !seen.add(expression.symbol())) return null;
        Body body = resolve(call.owner(), call.name(), call.desc(), classes, new HashSet<>());
        return body == null ? null : provider(body, args);
    }
    /** A checked self cast or a single field read has no observer, mutation, extra call or control flow. */
    private static NativeCallChanges.Expr provider(Body body, List<NativeCallChanges.Expr> args) {
        if (!body.method.tryCatchBlocks.isEmpty()) return null;
        List<AbstractInsnNode> code = real(body.method); Map<Integer, NativeCallChanges.Expr> slots = slots(body.method, args);
        if (slots == null || code.size() < 2 || code.size() > 4 || !(code.getFirst() instanceof VarInsnNode load)
                || load.getOpcode() != Opcodes.ALOAD || !slots.containsKey(load.var)
                || code.getLast().getOpcode() != Type.getReturnType(body.method.desc).getOpcode(Opcodes.IRETURN)) return null;
        if (code.size() == 2 || code.size()==3 && code.get(1) instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST) return slots.get(load.var);
        if (code.get(1) instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                && (code.size()==3 || code.get(2) instanceof TypeInsnNode cast && cast.getOpcode()==Opcodes.CHECKCAST))
            return new NativeCallChanges.Expr("field", "L" + field.owner + ";" + field.name + ":" + field.desc, List.of(slots.get(load.var)));
        return null;
    }
    private static String canonical(MethodNode method, Map<Integer, NativeCallChanges.Expr> inputs, List<Type> rootTypes, Type returned) {
        if (!method.tryCatchBlocks.isEmpty() || (method.access & (Opcodes.ACC_SYNCHRONIZED | Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT)) != 0) return null;
        MethodNode canonical = new MethodNode(Opcodes.ACC_STATIC, "body", Type.getMethodDescriptor(returned, rootTypes.toArray(Type[]::new)), null, null);
        Map<LabelNode, LabelNode> labels = new IdentityHashMap<>(); for (AbstractInsnNode i : method.instructions) if (i instanceof LabelNode label) labels.put(label, new LabelNode());
        int[] slots = new int[rootTypes.size()]; int slot = 0; for (int p = 0; p < slots.length; p++) { slots[p] = slot; slot += rootTypes.get(p).getSize(); }
        for (AbstractInsnNode i : method.instructions) {
            if (i.getOpcode() < 0 && !(i instanceof LabelNode)) continue;
            if (i instanceof VarInsnNode variable) {
                NativeCallChanges.Expr value = inputs.get(variable.var);
                if (variable.getOpcode() < Opcodes.ILOAD || variable.getOpcode() > Opcodes.ALOAD || value == null || !value.kind().equals("input")) return null;
                int input = Integer.parseInt(value.symbol()); if (input < 0 || input >= slots.length || variable.getOpcode() != rootTypes.get(input).getOpcode(Opcodes.ILOAD)) return null;
                canonical.instructions.add(new VarInsnNode(variable.getOpcode(), slots[input]));
            } else canonical.instructions.add(i.clone(labels));
        }
        return MixinInstructionFingerprint.hash(canonical);
    }
    private static Map<Integer, NativeCallChanges.Expr> slots(MethodNode method, List<NativeCallChanges.Expr> inputs) {
        Type[] params = Type.getArgumentTypes(method.desc); boolean stat = (method.access & Opcodes.ACC_STATIC) != 0;
        if (inputs.size() != params.length + (stat ? 0 : 1)) return null;
        Map<Integer, NativeCallChanges.Expr> slots = new HashMap<>(); int slot = 0, first = 0;
        if (!stat) { slots.put(0, inputs.get(0)); slot = 1; first = 1; }
        for (int p = 0; p < params.length; p++) { slots.put(slot, inputs.get(p + first)); slot += params[p].getSize(); }
        return slots;
    }
    private static Body resolve(String owner, String name, String desc, Function<String, ClassNode> classes, Set<String> visiting) {
        if (!visiting.add(owner)) return null; ClassNode node = classes.apply(owner); if (node == null) return null;
        MethodNode own = NativeCallChanges.method(node, name + desc);
        if (own != null && (own.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0) return new Body(node, own);
        if (node.superName != null) { Body inherited = resolve(node.superName, name, desc, classes, new HashSet<>(visiting)); if (inherited != null) return inherited; }
        List<Body> defaults = new ArrayList<>();
        for (String iface : node.interfaces) { Body implementation = resolve(iface, name, desc, classes, new HashSet<>(visiting)); if (implementation != null) defaults.add(implementation); }
        Map<String, Body> unique = new LinkedHashMap<>(); for (Body candidate : defaults) unique.put(candidate.owner.name, candidate);
        return unique.size() == 1 ? unique.values().iterator().next() : null;
    }
    private static List<Type> operandTypes(MethodInsnNode call) { List<Type> result = new ArrayList<>(); if (call.getOpcode() != Opcodes.INVOKESTATIC) result.add(Type.getObjectType(call.owner)); result.addAll(List.of(Type.getArgumentTypes(call.desc))); return result; }
    private static List<AbstractInsnNode> real(MethodNode method) { return Arrays.stream(method.instructions.toArray()).filter(i -> i.getOpcode() >= 0).toList(); }
}
