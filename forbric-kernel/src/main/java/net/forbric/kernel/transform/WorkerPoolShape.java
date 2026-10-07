/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** JDK-level worker roles, derived from bytecode rather than vendor class or field names. */
final class WorkerPoolShape {
    static final String ATOMIC = "Ljava/util/concurrent/atomic/AtomicBoolean;", QUEUE = "Ljava/util/Deque;";
    record Roles(FieldNode running, FieldNode queue) { }
    static AbstractInsnNode previous(AbstractInsnNode instruction) {
        do { instruction = instruction.getPrevious(); } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }
    static AbstractInsnNode next(AbstractInsnNode instruction) {
        do { instruction = instruction.getNext(); } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }
    static boolean load(AbstractInsnNode instruction, int slot) {
        return instruction instanceof VarInsnNode variable && variable.getOpcode() == Opcodes.ALOAD && variable.var == slot;
    }
    static boolean ownField(AbstractInsnNode instruction, String owner, FieldNode declaration) {
        return instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                && field.owner.equals(owner) && field.name.equals(declaration.name) && field.desc.equals(declaration.desc)
                && load(previous(field), 0);
    }
    static List<AbstractInsnNode> code(MethodNode method) {
        List<AbstractInsnNode> result = new ArrayList<>();
        for (var instruction : method.instructions) if (instruction.getOpcode() >= 0) result.add(instruction);
        return result;
    }
    static Roles roles(ClassNode pool) {
        if (pool == null) return null;
        List<FieldNode> running = pool.fields.stream().filter(f -> (f.access & Opcodes.ACC_STATIC) == 0 && f.desc.equals(ATOMIC)).toList();
        List<FieldNode> queues = pool.fields.stream().filter(f -> (f.access & Opcodes.ACC_STATIC) == 0 && f.desc.equals(QUEUE)).toList();
        if (running.size() != 1 || queues.size() != 1) return null;
        boolean stops = false, submits = false;
        for (MethodNode method : pool.methods) {
            if ((method.access & Opcodes.ACC_STATIC) != 0) continue;
            for (var instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call)) continue;
                var value = previous(call);
                var receiver = value == null ? null : previous(value);
                if (call.getOpcode() == Opcodes.INVOKEVIRTUAL && call.owner.equals("java/util/concurrent/atomic/AtomicBoolean")
                        && (call.name.equals("set") && call.desc.equals("(Z)V") || call.name.equals("getAndSet") && call.desc.equals("(Z)Z"))
                        && value != null && value.getOpcode() == Opcodes.ICONST_0 && ownField(receiver, pool.name, running.getFirst())) stops = true;
                if (method.desc.equals("(Ljava/lang/Runnable;)V") && call.getOpcode() == Opcodes.INVOKEINTERFACE
                        && call.owner.equals("java/util/Deque") && call.name.equals("add") && call.desc.equals("(Ljava/lang/Object;)Z")
                        && load(value, 1) && ownField(receiver, pool.name, queues.getFirst())) submits = true;
            }
        }
        return stops && submits ? new Roles(running.getFirst(), queues.getFirst()) : null;
    }
    static MethodNode method(ClassNode owner, String name, String descriptor) {
        return owner == null ? null : owner.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(descriptor)).findFirst().orElse(null);
    }
    private WorkerPoolShape() { }
}
