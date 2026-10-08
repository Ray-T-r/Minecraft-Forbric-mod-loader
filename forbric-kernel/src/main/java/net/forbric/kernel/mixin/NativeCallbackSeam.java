/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;
import java.util.*;
import java.util.function.Function;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Exact source/current prefix and operand-wire proof for a final call extracted into a static helper. */
final class NativeCallbackSeam {
    record Plan(String host, String method, String member, String helper, String helperMethod,
                int[] hostParameters, String sourcePrefix, String currentPrefix, String helperHash) { }
    private record Feed(List<AbstractInsnNode> instructions, List<String> wires) { }
    private NativeCallbackSeam() { }
    static Plan derive(ClassNode original, ClassNode current, MethodNode source, String member,
            Function<String, ClassNode> classes) {
        if (original == null || current == null || source == null
                || !Type.getReturnType(source.desc).equals(Type.VOID_TYPE)) return null;
        MethodNode live = NativeCallChanges.method(current, source.name + source.desc);
        if (live == null || ((live.access ^ source.access) & Opcodes.ACC_STATIC) != 0) return null;
        if (!source.tryCatchBlocks.isEmpty() || !live.tryCatchBlocks.isEmpty()) return null;
        List<MethodInsnNode> old = calls(source).stream().filter(c -> NativeCallChanges.member(c).equals(member)).toList();
        if (old.size() != 1 || calls(live).stream().anyMatch(c -> NativeCallChanges.member(c).equals(member))) return null;
        Feed sourceFeed = feed(old.getFirst()); if (sourceFeed == null || !finalCall(source, old.getFirst())) return null;
        String sourcePrefix = prefix(source, sourceFeed.instructions.getFirst()); if (sourcePrefix == null) return null;
        Plan found = null;
        for (MethodInsnNode edge : calls(live)) {
            if (edge.getOpcode() != Opcodes.INVOKESTATIC || !Type.getReturnType(edge.desc).equals(Type.VOID_TYPE) || !finalCall(live, edge)) continue;
            Feed currentFeed = feed(edge); if (currentFeed == null) continue;
            String currentPrefix = prefix(live, currentFeed.instructions.getFirst()); if (!sourcePrefix.equals(currentPrefix)) continue;
            ClassNode helper = classes.apply(edge.owner); MethodNode body = NativeCallChanges.method(helper, edge.name + edge.desc);
            if (body == null || (body.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PUBLIC)) != (Opcodes.ACC_STATIC | Opcodes.ACC_PUBLIC)
                    || (body.access & (Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_SYNCHRONIZED)) != 0) continue;
            List<MethodInsnNode> inside = calls(body).stream().filter(c -> NativeCallChanges.member(c).equals(member)).toList();
            if (inside.size() != 1 || !body.tryCatchBlocks.isEmpty()) continue; Feed helperFeed = feed(inside.getFirst()); if (helperFeed == null
                    || !unconditionalFeed(body, helperFeed)) continue;
            Map<Integer, String> parameters = new HashMap<>(); Type[] args = Type.getArgumentTypes(edge.desc); int slot = 0;
            for (int p = 0; p < args.length; p++) { parameters.put(slot, currentFeed.wires.get(p)); slot += args[p].getSize(); }
            List<String> projected = new ArrayList<>();
            for (String wire : helperFeed.wires) {
                if (wire.startsWith("local:")) projected.add(parameters.getOrDefault(Integer.parseInt(wire.substring(6)), "unmapped")); else projected.add(wire);
            }
            if (!projected.equals(sourceFeed.wires)) continue;
            Type[] hostArgs = Type.getArgumentTypes(source.desc); int[] projection = new int[hostArgs.length]; int hostSlot = (source.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0; boolean complete = true;
            for (int p = 0; p < hostArgs.length; p++) {
                projection[p] = -1;
                for (int q = 0; q < args.length; q++) if (args[q].equals(hostArgs[p]) && currentFeed.wires.get(q).equals("local:" + hostSlot)) {
                    if (projection[p] >= 0) { complete = false; break; } projection[p] = q;
                }
                if (projection[p] < 0) complete = false; hostSlot += hostArgs[p].getSize();
            }
            if (!complete) continue;
            Plan candidate = new Plan(current.name, source.name + source.desc, member, helper.name, body.name + body.desc,
                projection, sourcePrefix, currentPrefix, MixinInstructionFingerprint.hash(body));
            if (found != null) return null; found = candidate;
        }
        return found;
    }
    private static Feed feed(MethodInsnNode call) {
        int count = Type.getArgumentTypes(call.desc).length + (call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1);
        List<AbstractInsnNode> instructions = new ArrayList<>(); List<String> wires = new ArrayList<>(); AbstractInsnNode at = call;
        for (int p = 0; p < count; p++) {
            at = previous(at); if (at == null) return null;
            if (at instanceof VarInsnNode load && at.getOpcode() >= Opcodes.ILOAD && at.getOpcode() <= Opcodes.ALOAD) wires.addFirst("local:" + load.var);
            else if (at.getOpcode() >= Opcodes.ACONST_NULL && at.getOpcode() <= Opcodes.DCONST_1) wires.addFirst("constant:" + at.getOpcode());
            else if (at instanceof LdcInsnNode constant) wires.addFirst("literal:" + constant.cst.getClass().getName() + ":" + constant.cst);
            else return null;
            instructions.addFirst(at);
        }
        return new Feed(instructions, wires);
    }
    private static boolean unconditionalFeed(MethodNode body, Feed feed) {
        Set<Integer> inputs=new HashSet<>();for(String wire:feed.wires)if(wire.startsWith("local:"))inputs.add(Integer.parseInt(wire.substring(6)));
        for(AbstractInsnNode at:body.instructions){if(at==feed.instructions.getFirst())return true;
            if(at instanceof JumpInsnNode||at instanceof TableSwitchInsnNode||at instanceof LookupSwitchInsnNode
                ||at.getOpcode()>=Opcodes.IRETURN&&at.getOpcode()<=Opcodes.RETURN||at.getOpcode()==Opcodes.ATHROW)return false;
            if(at instanceof VarInsnNode store&&at.getOpcode()>=Opcodes.ISTORE&&at.getOpcode()<=Opcodes.ASTORE&&inputs.contains(store.var)
                ||at instanceof IincInsnNode increment&&inputs.contains(increment.var))return false;
        }return false;
    }
    private static String prefix(MethodNode source, AbstractInsnNode until) {
        MethodNode copy = new MethodNode(source.access, source.name, source.desc, null, null); source.accept(copy);
        List<AbstractInsnNode> code = real(source), cloned = real(copy); int index = code.indexOf(until); if (index < 0) return null;
        AbstractInsnNode cut = cloned.get(index); Set<LabelNode> removed = Collections.newSetFromMap(new IdentityHashMap<>());
        for (AbstractInsnNode i = cut; i != null; i = i.getNext()) if (i instanceof LabelNode label) removed.add(label);
        // A source-prefix branch into the argument feed is retained as the shared end of this prefix.
        LabelNode end = new LabelNode();
        for (AbstractInsnNode i = copy.instructions.getFirst(); i != cut; i = i.getNext()) {
            // A branch bypassing the native call must never be normalized to the call's entry.
            if (i instanceof JumpInsnNode branch && removed.contains(branch.label)) return null;
            if (i instanceof TableSwitchInsnNode table && (removed.contains(table.dflt) || table.labels.stream().anyMatch(removed::contains))) return null;
            if (i instanceof LookupSwitchInsnNode table && (removed.contains(table.dflt) || table.labels.stream().anyMatch(removed::contains))) return null;
        }
        for (AbstractInsnNode i = cut; i != null;) { AbstractInsnNode next = i.getNext(); copy.instructions.remove(i); i = next; }
        copy.instructions.add(end); copy.instructions.add(new InsnNode(Opcodes.RETURN));
        copy.tryCatchBlocks.clear(); copy.localVariables = null;
        return MixinInstructionFingerprint.hash(copy);
    }
    private static boolean finalCall(MethodNode method, MethodInsnNode call) {
        AbstractInsnNode next = next(call); if (next != null && (next.getOpcode() == Opcodes.POP || next.getOpcode() == Opcodes.POP2)) next = next(next);
        return next != null && next.getOpcode() == Opcodes.RETURN;
    }
    private static List<MethodInsnNode> calls(MethodNode method) { return real(method).stream().filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast).toList(); }
    private static List<AbstractInsnNode> real(MethodNode method) { return Arrays.stream(method.instructions.toArray()).filter(i -> i.getOpcode() >= 0).toList(); }
    private static AbstractInsnNode previous(AbstractInsnNode at) { at=at.getPrevious();while(at!=null&&at.getOpcode()<0)at=at.getPrevious();return at; }
    private static AbstractInsnNode next(AbstractInsnNode at) { at=at.getNext();while(at!=null&&at.getOpcode()<0)at=at.getNext();return at; }
}
