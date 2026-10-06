/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.boot.DefinedMethodContracts;

/**
 * A pure interface default which ignores context and delegates to a shorter member may forward that fallback
 * to the unique richer native overload using unchanged parameters. Concrete/default overrides of the source
 * signature still win by normal virtual dispatch. No owner, method or guest identity is configured here.
 */
public final class DefaultMethodOverloadBridge {
    private DefaultMethodOverloadBridge() { }

    public record Member(int opcode, String owner, String name, String descriptor, boolean itf) {
        static Member of(MethodInsnNode call) {
            return new Member(call.getOpcode(), call.owner, call.name, call.desc, call.itf);
        }
        MethodInsnNode instruction() { return new MethodInsnNode(opcode, owner, name, descriptor, itf); }
        String atTarget() { return "L" + owner + ";" + name + descriptor; }
    }

    public record Declaration(ClassNode owner, MethodNode method) { }
    public record Projection(Member original, Member richer, List<Integer> parameters) { }

    public static int adapt(ClassNode type, Function<String, ClassNode> resolver) {
        if (type == null || resolver == null || (type.access & Opcodes.ACC_INTERFACE) == 0) return 0;
        int changed = 0;
        for (MethodNode method : type.methods) {
            Projection plan = projection(type, method, resolver);
            if (plan == null) continue;
            rewrite(method, plan); changed++;
        }
        return changed;
    }

    /** A plan is derived only from a pure source default and unique member/argument correspondence. */
    public static Projection projection(ClassNode type, MethodNode method, Function<String, ClassNode> resolver) {
        if ((type.access & Opcodes.ACC_INTERFACE) == 0
                || (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_ABSTRACT
                        | Opcodes.ACC_NATIVE | Opcodes.ACC_SYNCHRONIZED)) != 0) return null;
        PureDelegate source = pure(type, method);
        if (source == null) return null;
        Type[] available = Type.getArgumentTypes(method.desc);
        Type[] old = Type.getArgumentTypes(source.member().descriptor());
        if (available.length <= old.length) return null;
        ClassNode target = source.member().owner().equals(type.name) ? type : resolver.apply(source.member().owner());
        if (target == null) return null;
        List<Projection> candidates = new ArrayList<>();
        for (Declaration declaration : family(target, method.name, resolver)) {
            MethodNode candidate = declaration.method();
            if (candidate.desc.equals(method.desc) || candidate.desc.equals(source.member().descriptor())
                    || (candidate.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) != 0
                    || !Type.getReturnType(candidate.desc).equals(Type.getReturnType(method.desc))) continue;
            Type[] wanted = Type.getArgumentTypes(candidate.desc);
            if (wanted.length <= old.length || wanted.length > available.length) continue;
            List<Integer> mapping = subset(available, wanted);
            if (mapping == null) continue;
            // Existing fallback operands cannot be exchanged for a same-typed context parameter.
            boolean kept = true;
            for (int index = 0; index < old.length; index++) {
                int occurrence = unique(wanted, old[index]);
                if (occurrence < 0 || !mapping.get(occurrence).equals(source.parameters().get(index))) { kept = false; break; }
            }
            if (!kept) continue;
            candidates.add(new Projection(source.member(), new Member(source.member().opcode(), source.member().owner(),
                    source.member().name(), candidate.desc, source.member().itf()), mapping));
        }
        return candidates.size() == 1 ? candidates.getFirst() : null;
    }

    /** The precise final body expected after this forwarding plan, without editing the supplied node. */
    public static DefinedMethodContracts.MethodContract projectedContract(ClassNode owner, MethodNode method, Projection plan) {
        MethodNode copy = new MethodNode(method.access, method.name, method.desc, null, null);
        method.accept(copy); rewrite(copy, plan);
        return new DefinedMethodContracts.MethodContract(owner.name, method.name, method.desc,
                DefinedMethodContracts.fingerprint(copy));
    }

    public static DefinedMethodContracts.MethodContract contract(Declaration declaration) {
        return new DefinedMethodContracts.MethodContract(declaration.owner().name, declaration.method().name,
                declaration.method().desc, DefinedMethodContracts.fingerprint(declaration.method()));
    }

    static List<Declaration> family(ClassNode root, String name, Function<String, ClassNode> resolver) {
        Map<String, List<Declaration>> declarations = new LinkedHashMap<>();
        collect(root, name, resolver, new HashSet<>(), declarations);
        List<Declaration> out = new ArrayList<>();
        for (var entry : declarations.entrySet()) {
            List<Declaration> own = entry.getValue().stream().filter(d -> d.owner().name.equals(root.name)).toList();
            if (own.size() == 1) out.add(own.getFirst());
            else if (own.isEmpty()) out.addAll(entry.getValue());
            // Multiple inherited declarations remain multiple candidates, never a traversal-order winner.
        }
        return out;
    }

    static Declaration declaration(ClassNode root, String name, String descriptor, Function<String, ClassNode> resolver) {
        List<Declaration> matches = family(root, name, resolver).stream().filter(d -> d.method().desc.equals(descriptor)).toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private static void collect(ClassNode type, String name, Function<String, ClassNode> resolver,
                                Set<String> seen, Map<String, List<Declaration>> out) {
        if (type == null || !seen.add(type.name)) return;
        for (MethodNode method : type.methods) if (method.name.equals(name))
            out.computeIfAbsent(method.desc, key -> new ArrayList<>()).add(new Declaration(type, method));
        if (type.superName != null) collect(resolver.apply(type.superName), name, resolver, seen, out);
        for (String parent : type.interfaces) collect(resolver.apply(parent), name, resolver, seen, out);
    }

    static List<Integer> subset(Type[] available, Type[] wanted) {
        List<Integer> out = new ArrayList<>();
        // An exact prefix supplies positional evidence even when a type occurs again in unused trailing context.
        boolean prefix = wanted.length <= available.length;
        for (int index = 0; prefix && index < wanted.length; index++) prefix = wanted[index].equals(available[index]);
        if (prefix) { for (int index = 0; index < wanted.length; index++) out.add(index); return List.copyOf(out); }
        Set<Integer> used = new HashSet<>();
        for (Type wantedType : wanted) {
            int index = unique(available, wantedType);
            if (index < 0 || !used.add(index)) return null;
            out.add(index);
        }
        return List.copyOf(out);
    }

    private static int unique(Type[] types, Type wanted) {
        int found = -1;
        for (int index = 0; index < types.length; index++) if (types[index].equals(wanted)) {
            if (found >= 0) return -1; found = index;
        }
        return found;
    }

    private record PureDelegate(Member member, List<Integer> parameters) { }

    private static PureDelegate pure(ClassNode owner, MethodNode method) {
        if (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty()) return null;
        List<AbstractInsnNode> code = real(method);
        if (code.size() < 3 || !(code.getFirst() instanceof VarInsnNode receiver)
                || receiver.getOpcode() != Opcodes.ALOAD || receiver.var != 0
                || !(code.get(code.size() - 2) instanceof MethodInsnNode call)
                || call.getOpcode() != Opcodes.INVOKEVIRTUAL && call.getOpcode() != Opcodes.INVOKEINTERFACE
                || !call.name.equals(method.name) || !Type.getReturnType(call.desc).equals(Type.getReturnType(method.desc))
                || code.getLast().getOpcode() != Type.getReturnType(method.desc).getOpcode(Opcodes.IRETURN)) return null;
        int firstArgument = 1;
        if (code.get(1) instanceof TypeInsnNode cast) {
            if (cast.getOpcode() != Opcodes.CHECKCAST || !cast.desc.equals(call.owner)) return null;
            firstArgument++;
        } else if (!call.owner.equals(owner.name)) return null;
        Type[] arguments = Type.getArgumentTypes(method.desc), called = Type.getArgumentTypes(call.desc);
        int[] slots = slots(arguments, false);
        if (code.size() != firstArgument + called.length + 2) return null;
        List<Integer> mapping = new ArrayList<>();
        for (int index = 0; index < called.length; index++) {
            if (!(code.get(firstArgument + index) instanceof VarInsnNode load)
                    || load.getOpcode() != called[index].getOpcode(Opcodes.ILOAD)) return null;
            int parameter = -1;
            for (int arg = 0; arg < arguments.length; arg++)
                if (slots[arg] == load.var && arguments[arg].equals(called[index])) parameter = arg;
            if (parameter < 0) return null; mapping.add(parameter);
        }
        return new PureDelegate(Member.of(call), List.copyOf(mapping));
    }

    static List<AbstractInsnNode> real(MethodNode method) {
        List<AbstractInsnNode> code = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions)
            if (instruction.getOpcode() >= 0 && instruction.getOpcode() != Opcodes.NOP) code.add(instruction);
        return code;
    }

    static int[] slots(Type[] arguments, boolean isStatic) {
        int[] out = new int[arguments.length]; int slot = isStatic ? 0 : 1;
        for (int index = 0; index < arguments.length; index++) { out[index] = slot; slot += arguments[index].getSize(); }
        return out;
    }

    private static void rewrite(MethodNode method, Projection plan) {
        Type[] arguments = Type.getArgumentTypes(method.desc); int[] slots = slots(arguments, false);
        LabelNode start = new LabelNode(), end = new LabelNode();
        method.instructions.clear(); method.instructions.add(start);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, plan.richer().owner()));
        int stack = 1;
        for (int argument : plan.parameters()) {
            method.instructions.add(new VarInsnNode(arguments[argument].getOpcode(Opcodes.ILOAD), slots[argument]));
            stack += arguments[argument].getSize();
        }
        method.instructions.add(plan.richer().instruction());
        method.instructions.add(new InsnNode(Type.getReturnType(method.desc).getOpcode(Opcodes.IRETURN)));
        method.instructions.add(end);
        if (method.localVariables != null) for (var local : method.localVariables) { local.start = start; local.end = end; }
        if (method.visibleLocalVariableAnnotations != null) for (var annotation : method.visibleLocalVariableAnnotations) {
            java.util.Collections.fill(annotation.start, start); java.util.Collections.fill(annotation.end, end);
        }
        if (method.invisibleLocalVariableAnnotations != null) for (var annotation : method.invisibleLocalVariableAnnotations) {
            java.util.Collections.fill(annotation.start, start); java.util.Collections.fill(annotation.end, end);
        }
        method.maxLocals = 1;
        for (Type argument : arguments) method.maxLocals += argument.getSize();
        method.maxStack = Math.max(stack, Type.getReturnType(method.desc).getSize());
    }
}
