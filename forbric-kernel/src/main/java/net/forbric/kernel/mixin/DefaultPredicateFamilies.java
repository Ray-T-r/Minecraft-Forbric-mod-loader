/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import net.forbric.kernel.boot.DefinedMethodContracts;

/** Structural contracts for a predicate whose complete body is one flags operation, AND mask, nonzero. */
final class DefaultPredicateFamilies {
    record Predicate(DefaultMethodOverloadBridge.Member flags, List<Integer> flagParameters, int maskParameter,
                     List<DefinedMethodContracts.MethodContract> helpers) { }
    private DefaultPredicateFamilies() { }

    static Predicate predicate(DefaultMethodOverloadBridge.Declaration declaration, Function<String, ClassNode> resolver) {
        MethodNode method = declaration.method();
        if ((declaration.owner().access & Opcodes.ACC_INTERFACE) == 0
                || (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_PRIVATE
                    | Opcodes.ACC_NATIVE | Opcodes.ACC_SYNCHRONIZED)) != 0
                || !Type.getReturnType(method.desc).equals(Type.BOOLEAN_TYPE)
                || !method.tryCatchBlocks.isEmpty()) return null;
        List<AbstractInsnNode> code = DefaultMethodOverloadBridge.real(method);
        int and = -1;
        for (int index = 0; index < code.size(); index++) if (code.get(index).getOpcode() == Opcodes.IAND) {
            if (and >= 0) return null; and = index;
        }
        if (and < 3 || and + 6 != code.size()
                || !(code.get(and - 1) instanceof VarInsnNode mask) || mask.getOpcode() != Opcodes.ILOAD
                || !(code.get(and - 2) instanceof MethodInsnNode flags)
                || flags.getOpcode() != Opcodes.INVOKEINTERFACE && flags.getOpcode() != Opcodes.INVOKEVIRTUAL
                || !Type.getReturnType(flags.desc).equals(Type.INT_TYPE)
                || !(code.getFirst() instanceof VarInsnNode self) || self.getOpcode() != Opcodes.ALOAD || self.var != 0)
            return null;
        if (!(code.get(and + 1) instanceof JumpInsnNode branch) || !(code.get(and + 3) instanceof JumpInsnNode done)
                || done.getOpcode() != Opcodes.GOTO || code.getLast().getOpcode() != Opcodes.IRETURN) return null;
        int firstConstant = code.get(and + 2).getOpcode(), secondConstant = code.get(and + 4).getOpcode();
        boolean nonzero = branch.getOpcode() == Opcodes.IFEQ && firstConstant == Opcodes.ICONST_1 && secondConstant == Opcodes.ICONST_0
                || branch.getOpcode() == Opcodes.IFNE && firstConstant == Opcodes.ICONST_0 && secondConstant == Opcodes.ICONST_1;
        if (!nonzero || next(branch.label) != code.get(and + 4) || next(done.label) != code.getLast()) return null;
        int argumentStart = 1;
        List<DefinedMethodContracts.MethodContract> helpers = new ArrayList<>();
        if (code.get(1) instanceof TypeInsnNode cast) {
            if (cast.getOpcode() != Opcodes.CHECKCAST || !cast.desc.equals(flags.owner)) return null;
            argumentStart++;
        } else if (code.get(1) instanceof MethodInsnNode helper && helper != flags) {
            ClassNode helperOwner = helper.owner.equals(declaration.owner().name) ? declaration.owner() : resolver.apply(helper.owner);
            MethodNode projection = identity(helperOwner, helper, flags.owner);
            if (projection == null) return null;
            helpers.add(new DefinedMethodContracts.MethodContract(helperOwner.name, projection.name, projection.desc,
                    DefinedMethodContracts.fingerprint(projection)));
            argumentStart++;
        } else if (!flags.owner.equals(declaration.owner().name)) return null;
        Type[] methodArguments = Type.getArgumentTypes(method.desc), flagArguments = Type.getArgumentTypes(flags.desc);
        int[] slots = DefaultMethodOverloadBridge.slots(methodArguments, false);
        if (and - 2 != argumentStart + flagArguments.length) return null;
        List<Integer> mapping = new ArrayList<>();
        for (int index = 0; index < flagArguments.length; index++) {
            AbstractInsnNode instruction = code.get(argumentStart + index);
            if (!(instruction instanceof VarInsnNode load) || load.getOpcode() != flagArguments[index].getOpcode(Opcodes.ILOAD)) return null;
            int parameter = parameter(slots, methodArguments, load.var, flagArguments[index]);
            if (parameter < 0) return null; mapping.add(parameter);
        }
        int maskParameter = parameter(slots, methodArguments, mask.var, Type.INT_TYPE);
        if (maskParameter < 0 || mapping.contains(maskParameter)) return null;
        return new Predicate(DefaultMethodOverloadBridge.Member.of(flags), List.copyOf(mapping), maskParameter, List.copyOf(helpers));
    }

    private static MethodNode identity(ClassNode owner, MethodInsnNode call, String returnedOwner) {
        if (owner == null || !call.desc.equals("()L" + returnedOwner + ";")) return null;
        List<MethodNode> candidates = owner.methods.stream().filter(method -> method.name.equals(call.name) && method.desc.equals(call.desc)).toList();
        if (candidates.size() != 1) return null;
        MethodNode method = candidates.getFirst();
        if ((method.access & Opcodes.ACC_PRIVATE) == 0
                || (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_SYNCHRONIZED)) != 0 || !method.tryCatchBlocks.isEmpty()) return null;
        List<AbstractInsnNode> body = DefaultMethodOverloadBridge.real(method);
        boolean pure = body.size() == 3 && body.getFirst() instanceof VarInsnNode self && self.var == 0 && self.getOpcode() == Opcodes.ALOAD
                && body.get(1) instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST && cast.desc.equals(returnedOwner)
                && body.getLast().getOpcode() == Opcodes.ARETURN;
        return pure ? method : null;
    }

    private static int parameter(int[] slots, Type[] types, int slot, Type expected) {
        for (int index = 0; index < slots.length; index++) if (slots[index] == slot && types[index].equals(expected)) return index;
        return -1;
    }

    private static AbstractInsnNode next(AbstractInsnNode instruction) {
        AbstractInsnNode next = instruction.getNext();
        while (next != null && next.getOpcode() < 0) next = next.getNext();
        return next;
    }
}
