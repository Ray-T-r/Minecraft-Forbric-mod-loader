/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;
import java.util.*;
import java.util.function.Function;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import net.forbric.kernel.util.Caught;

/** Exact source/current prefix and operand-wire proof for a final call extracted into a static helper. */
final class NativeCallbackSeam {
    record Plan(String host, String method, String member, String helper, String helperMethod,
                int[] hostParameters, String sourcePrefix, String currentPrefix, String helperHash) { }
    private record Feed(List<AbstractInsnNode> instructions, List<String> wires) { }
    private NativeCallbackSeam() { }
    /**
     * {@code target} is the {@code @At(INVOKE)} target as the mod wrote it. It selects calls as Mixin matches one
     * ({@link MixinCallbackShape#selected}: whitespace, a dotted owner, and an owner or descriptor left out all read as
     * Mixin reads them), in {@code source}, the method the handler was written for: there it must select exactly one
     * call, and that call's member is the one followed into the helper and named by the plan. The merged method must
     * keep no call the target would still select.
     */
    static Plan derive(ClassNode original, ClassNode current, MethodNode source, String target,
            Function<String, ClassNode> classes) {
        return derive(original, List.of(), current, source, target, classes);
    }

    /**
     * {@link #derive(ClassNode, ClassNode, MethodNode, String, Function)}, where the merged method may reach the helper
     * by the way one of {@code peers} reaches its own copy of the call instead: {@code peers} are the same class as other
     * ecosystems compiled it natively. A merge that keeps another ecosystem's own edit in front of the helper (its hook,
     * re-deriving the very operands the call is handed) leaves a prefix no longer the source's, but exactly that
     * ecosystem's — where its own body makes the same call, final, from the same operand wires. Locals other than the
     * parameters and those wires are compared as the variables they are (their def-use webs, by first appearance), as
     * a merge gives a kept edit's own local a fresh slot where that ecosystem's compiler reused one.
     */
    static Plan derive(ClassNode original, List<ClassNode> peers, ClassNode current, MethodNode source, String target,
            Function<String, ClassNode> classes) {
        if (original == null || current == null || source == null
                || !Type.getReturnType(source.desc).equals(Type.VOID_TYPE)) return null;
        MethodNode live = NativeCallChanges.method(current, source.name + source.desc);
        if (live == null || ((live.access ^ source.access) & Opcodes.ACC_STATIC) != 0) return null;
        if (!source.tryCatchBlocks.isEmpty() || !live.tryCatchBlocks.isEmpty()) return null;
        List<MethodInsnNode> old = selectedCalls(target, source);
        if (old.size() != 1 || !selectedCalls(target, live).isEmpty()) return null;
        String member = NativeCallChanges.member(old.getFirst());
        Feed sourceFeed = feed(old.getFirst()); if (sourceFeed == null || !finalCall(source, old.getFirst())) return null;
        Set<Integer> fixed = fixedSlots(source, sourceFeed);
        String sourcePrefix = prefix(source, sourceFeed.instructions.getFirst(), fixed); if (sourcePrefix == null) return null;
        // Each native way to the call: the source's own, then each peer whose body makes the same call, once and final,
        // from the same wires.
        List<String> natives = new ArrayList<>(List.of(sourcePrefix));
        for (ClassNode peer : peers == null ? List.<ClassNode>of() : peers) {
            MethodNode peerMethod = peer == null ? null : NativeCallChanges.method(peer, source.name + source.desc);
            if (peerMethod == null || ((peerMethod.access ^ source.access) & Opcodes.ACC_STATIC) != 0 || !peerMethod.tryCatchBlocks.isEmpty()) continue;
            List<MethodInsnNode> own = selectedCalls(target, peerMethod);
            if (own.size() != 1 || !NativeCallChanges.member(own.getFirst()).equals(member) || !finalCall(peerMethod, own.getFirst())) continue;
            Feed peerFeed = feed(own.getFirst());
            if (peerFeed == null || !peerFeed.wires.equals(sourceFeed.wires)) continue;
            String peerPrefix = prefix(peerMethod, peerFeed.instructions.getFirst(), fixed);
            if (peerPrefix != null && !natives.contains(peerPrefix)) natives.add(peerPrefix);
        }
        Plan found = null;
        for (MethodInsnNode edge : calls(live)) {
            if (edge.getOpcode() != Opcodes.INVOKESTATIC || !Type.getReturnType(edge.desc).equals(Type.VOID_TYPE) || !finalCall(live, edge)) continue;
            Feed currentFeed = feed(edge); if (currentFeed == null) continue;
            String currentPrefix = prefix(live, currentFeed.instructions.getFirst(), fixed); if (!natives.contains(currentPrefix)) continue;
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
            // The native prefix the current one is: the source's, or the peer's the merge kept.
            Plan candidate = new Plan(current.name, source.name + source.desc, member, helper.name, body.name + body.desc,
                projection, currentPrefix, currentPrefix, MixinInstructionFingerprint.hash(body));
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
    /** The receiver and parameter slots, and every local the call's operand wires read: compared by number. */
    private static Set<Integer> fixedSlots(MethodNode method, Feed feed) {
        Set<Integer> fixed = new HashSet<>();
        int slots = (Type.getArgumentsAndReturnSizes(method.desc) >> 2) - ((method.access & Opcodes.ACC_STATIC) != 0 ? 1 : 0);
        for (int slot = 0; slot < slots; slot++) fixed.add(slot);
        for (String wire : feed.wires) if (wire.startsWith("local:")) fixed.add(Integer.parseInt(wire.substring(6)));
        return fixed;
    }
    private static String prefix(MethodNode source, AbstractInsnNode until, Set<Integer> fixed) {
        MethodNode copy = new MethodNode(source.access, source.name, source.desc, null, null); source.accept(copy);
        List<AbstractInsnNode> code = real(source), cloned = real(copy); int index = code.indexOf(until); if (index < 0) return null;
        int[] variables = variables(source, code, index, fixed);
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
        // Every other local as the variable it is, numbered by first appearance: a body's own locals consistently renamed
        // (or one slot reused for two variables where the other body gives each its own) is the same code. A body that
        // cannot be analysed keeps its slot numbers, and so matches only a prefix written exactly alike.
        for (int i = 0; variables != null && i < index; i++) {
            if (variables[i] < 0) continue;
            if (cloned.get(i) instanceof VarInsnNode local) local.var = variables[i];
            else if (cloned.get(i) instanceof IincInsnNode increment) increment.var = variables[i];
        }
        return MixinInstructionFingerprint.hash(copy);
    }
    /**
     * Per executable instruction before {@code end}: for a load, store or increment of a local outside {@code fixed}, the
     * number of the variable it touches — the def-use web of the stores reaching its loads, by first appearance — else -1.
     * Null when the method cannot be analysed, or a value stored at or past {@code end} reaches a load before it.
     */
    private static int[] variables(MethodNode method, List<AbstractInsnNode> code, int end, Set<Integer> fixed) {
        boolean own = false;
        for (int i = 0; i < end && !own; i++)
            own = code.get(i) instanceof VarInsnNode local && local.getOpcode() != Opcodes.RET && !fixed.contains(local.var)
                    || code.get(i) instanceof IincInsnNode increment && !fixed.contains(increment.var);
        if (!own) { int[] none = new int[end]; Arrays.fill(none, -1); return none; }
        org.objectweb.asm.tree.analysis.Frame<org.objectweb.asm.tree.analysis.SourceValue>[] frames;
        try { frames = new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.SourceInterpreter()).analyze("java/lang/Object", method); }
        catch (org.objectweb.asm.tree.analysis.AnalyzerException | RuntimeException unanalysable) { Caught.expected(unanalysable); return null; }
        int[] parent = new int[end];
        for (int i = 0; i < end; i++) parent[i] = i;
        Map<AbstractInsnNode, Integer> at = new IdentityHashMap<>();
        for (int i = 0; i < end; i++) at.put(code.get(i), i);
        for (int i = 0; i < end; i++) {
            AbstractInsnNode instruction = code.get(i);
            int var = instruction instanceof VarInsnNode local && local.getOpcode() != Opcodes.RET ? local.var
                    : instruction instanceof IincInsnNode increment ? increment.var : -1;
            boolean reads = instruction instanceof IincInsnNode || instruction.getOpcode() >= Opcodes.ILOAD && instruction.getOpcode() <= Opcodes.ALOAD;
            if (var < 0 || fixed.contains(var) || !reads) continue;
            var frame = frames[method.instructions.indexOf(instruction)];
            if (frame == null) continue;
            for (AbstractInsnNode store : frame.getLocal(var).insns) {
                Integer from = at.get(store);
                // A value stored at or past the end reaches a load before it only round a loop back from the call.
                if (from == null) return null;
                parent[root(parent, from)] = root(parent, i);
            }
        }
        int[] variables = new int[end];
        Map<Integer, Integer> numbers = new HashMap<>();
        for (int i = 0; i < end; i++) {
            AbstractInsnNode instruction = code.get(i);
            int var = instruction instanceof VarInsnNode local && local.getOpcode() != Opcodes.RET ? local.var
                    : instruction instanceof IincInsnNode increment ? increment.var : -1;
            variables[i] = var < 0 || fixed.contains(var) ? -1 : numbers.computeIfAbsent(root(parent, i), r -> 1024 + 2 * numbers.size());
        }
        return variables;
    }
    private static int root(int[] parent, int i) {
        while (parent[i] != i) { parent[i] = parent[parent[i]]; i = parent[i]; }
        return i;
    }
    private static boolean finalCall(MethodNode method, MethodInsnNode call) {
        AbstractInsnNode next = next(call); if (next != null && (next.getOpcode() == Opcodes.POP || next.getOpcode() == Opcodes.POP2)) next = next(next);
        return next != null && next.getOpcode() == Opcodes.RETURN;
    }
    private static List<MethodInsnNode> calls(MethodNode method) { return real(method).stream().filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast).toList(); }
    /** The calls of {@code method} an {@code @At(INVOKE)} {@code target} selects, as Mixin matches it. */
    private static List<MethodInsnNode> selectedCalls(String target, MethodNode method) {
        return MixinCallbackShape.selected(target, method).stream().filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast).toList();
    }
    private static List<AbstractInsnNode> real(MethodNode method) { return Arrays.stream(method.instructions.toArray()).filter(i -> i.getOpcode() >= 0).toList(); }
    private static AbstractInsnNode previous(AbstractInsnNode at) { at=at.getPrevious();while(at!=null&&at.getOpcode()<0)at=at.getPrevious();return at; }
    private static AbstractInsnNode next(AbstractInsnNode at) { at=at.getNext();while(at!=null&&at.getOpcode()<0)at=at.getNext();return at; }
}
