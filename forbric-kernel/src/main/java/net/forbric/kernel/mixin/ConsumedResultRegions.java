/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.*;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/** The complete immediate result-consumer region, including its branches and non-result operand provenance. */
final class ConsumedResultRegions {
    private ConsumedResultRegions() { }

    static boolean same(String owner, MethodNode original, MethodInsnNode oldCall, MethodNode current, MethodInsnNode newCall) {
        if (!original.desc.equals(current.desc)) return false;
        List<String> before = signature(owner, original, oldCall), after = signature(owner, current, newCall);
        return before != null && before.equals(after);
    }

    /** The old producer's receiver is exactly the new operation's receiver; its result feeds the old consumer. */
    static boolean closedReceiver(String owner, MethodNode original, MethodInsnNode projection,
                                  MethodInsnNode consumer, MethodNode current, MethodInsnNode live) {
        if (!original.desc.equals(current.desc) || projection.getOpcode() == Opcodes.INVOKESTATIC
                || consumer.getOpcode() == Opcodes.INVOKESTATIC || live.getOpcode() == Opcodes.INVOKESTATIC) return false;
        int start = original.instructions.indexOf(projection), end = original.instructions.indexOf(consumer);
        if (start < 0 || end <= start) return false;
        for (int index = start + 1; index < end; index++) {
            AbstractInsnNode instruction = original.instructions.get(index);
            if (instruction.getOpcode() >= 0 && !(instruction instanceof VarInsnNode variable
                    && (variable.getOpcode() == Opcodes.ALOAD || variable.getOpcode() == Opcodes.ASTORE))) return false;
        }
        // An outside branch must not enter after the producer: then the consumer could use another path's value.
        for (AbstractInsnNode instruction : original.instructions) {
            if (instruction instanceof JumpInsnNode jump && enters(original, jump.label, start, end)) return false;
            if (instruction instanceof TableSwitchInsnNode table && (enters(original, table.dflt, start, end)
                    || table.labels.stream().anyMatch(label -> enters(original, label, start, end)))) return false;
            if (instruction instanceof LookupSwitchInsnNode lookup && (enters(original, lookup.dflt, start, end)
                    || lookup.labels.stream().anyMatch(label -> enters(original, label, start, end)))) return false;
        }
        if (original.tryCatchBlocks.stream().anyMatch(block -> enters(original, block.handler, start, end))) return false;
        Dependencies before = new Dependencies(original, null), after = new Dependencies(current, null);
        try {
            Frame<SourceValue>[] oldFrames = new Analyzer<>(before).analyze(owner, original);
            Frame<SourceValue>[] newFrames = new Analyzer<>(after).analyze(owner, current);
            Frame<SourceValue> producerFrame = oldFrames[start], consumerFrame = oldFrames[end];
            Frame<SourceValue> liveFrame = newFrames[current.instructions.indexOf(live)];
            if (producerFrame == null || consumerFrame == null || liveFrame == null) return false;
            SourceValue receiver = producerFrame.getStack(producerFrame.getStackSize() - Type.getArgumentTypes(projection.desc).length - 1);
            SourceValue used = consumerFrame.getStack(consumerFrame.getStackSize() - Type.getArgumentTypes(consumer.desc).length - 1);
            SourceValue nativeReceiver = liveFrame.getStack(liveFrame.getStackSize() - Type.getArgumentTypes(live.desc).length - 1);
            Set<AbstractInsnNode> projected = new HashSet<>(receiver.insns); projected.add(projection);
            Producers orderedBefore = new Producers(owner, original, null), orderedAfter = new Producers(owner, current, null);
            String oldKey = orderedBefore.key(orderedBefore.stack(projection, Type.getArgumentTypes(projection.desc).length));
            String newKey = orderedAfter.key(orderedAfter.stack(live, Type.getArgumentTypes(live.desc).length));
            return used.insns.equals(projected) && oldKey != null && oldKey.equals(newKey);
        } catch (AnalyzerException | RuntimeException malformed) { return false; }
    }

    private static boolean enters(MethodNode method, LabelNode label, int start, int end) {
        AbstractInsnNode target = label;
        while (target != null && target.getOpcode() < 0) target = target.getNext();
        int index = target == null ? -1 : method.instructions.indexOf(target);
        return index > start && index <= end;
    }

    private static List<String> signature(String owner, MethodNode method, MethodInsnNode result) {
        Dependencies interpreter = new Dependencies(method, result);
        Producers ordered;
        Frame<SourceValue>[] frames;
        try { frames = new Analyzer<>(interpreter).analyze(owner, method); ordered = new Producers(owner, method, result); }
        catch (AnalyzerException | RuntimeException unsupported) { return null; }
        int start = method.instructions.indexOf(result) + 1, end = -1;
        for (int index = start; index < method.instructions.size(); index++) {
            AbstractInsnNode instruction = method.instructions.get(index);
            if (!(instruction instanceof MethodInsnNode call)) continue;
            Frame<SourceValue> frame = frames[index]; if (frame == null) continue;
            int arguments = Type.getArgumentTypes(call.desc).length;
            int receiver = call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1;
            int first = frame.getStackSize() - arguments - receiver;
            if (first < 0) return null;
            // Calls on the result itself are its conversion, not the eventual destination of that value.
            if (receiver != 0 && frame.getStack(first).insns.contains(result)) continue;
            boolean receives = false;
            for (int input = first + receiver; input < frame.getStackSize(); input++)
                receives |= frame.getStack(input).insns.contains(result);
            if (receives) { end = index; break; }
        }
        if (end < 0) return null;
        Map<AbstractInsnNode, Integer> positions = new IdentityHashMap<>(); int ordinal = 0;
        for (int index = start; index <= end; index++) {
            AbstractInsnNode instruction = method.instructions.get(index);
            if (instruction.getOpcode() >= 0) positions.put(instruction, ordinal++);
        }
        List<String> signature = new ArrayList<>();
        for (int index = start; index <= end; index++) {
            AbstractInsnNode instruction = method.instructions.get(index); int opcode = instruction.getOpcode();
            if (opcode < 0) continue;
            Frame<SourceValue> frame = frames[index]; if (frame == null) return null;
            if (instruction instanceof VarInsnNode variable) {
                Frame<SourceValue> producerFrame = ordered.frame(instruction);
                SourceValue value = opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD
                        ? producerFrame.getLocal(variable.var) : producerFrame.getStack(producerFrame.getStackSize() - 1);
                String key = ordered.key(value); if (key == null) return null;
                signature.add("var:" + opcode + ":" + key);
            } else if (instruction instanceof JumpInsnNode jump) {
                AbstractInsnNode target = jump.label;
                while (target != null && target.getOpcode() < 0) target = target.getNext();
                Integer position = positions.get(target); if (position == null) return null;
                signature.add("jump:" + opcode + ":" + position);
                int inputs = opcode == Opcodes.GOTO ? 0 : opcode >= Opcodes.IF_ICMPEQ && opcode <= Opcodes.IF_ACMPNE ? 2
                        : opcode >= Opcodes.IFEQ && opcode <= Opcodes.IFLE || opcode == Opcodes.IFNULL || opcode == Opcodes.IFNONNULL ? 1 : -1;
                if (inputs < 0) return null;
                for (int input = inputs - 1; input >= 0; input--) {
                    String key = ordered.key(ordered.stack(jump, input)); if (key == null) return null;
                    signature.add("branch-input:" + key);
                }
            } else if (instruction instanceof TableSwitchInsnNode || instruction instanceof LookupSwitchInsnNode) return null;
            else signature.add(interpreter.atom(instruction));
        }
        // Exact semantic inputs to the destination: a matching member alone does not establish correspondence.
        MethodInsnNode sink = (MethodInsnNode) method.instructions.get(end);
        Frame<SourceValue> frame = ordered.frame(sink);
        int inputs = Type.getArgumentTypes(sink.desc).length + (sink.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1);
        for (int input = frame.getStackSize() - inputs; input < frame.getStackSize(); input++) {
            String key = ordered.key(frame.getStack(input)); if (key == null) return null;
            signature.add("sink-input:" + key);
        }
        return List.copyOf(signature);
    }

    private static final class Dependencies extends SourceInterpreter {
        private final MethodInsnNode result;
        private final Map<Integer, String> parameters = new HashMap<>();
        private final Map<AbstractInsnNode, String> atoms = new IdentityHashMap<>();
        Dependencies(MethodNode method, MethodInsnNode result) {
            super(Opcodes.ASM9); this.result = result;
            int slot = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0, argument = 0;
            if (slot != 0) parameters.put(0, "this");
            for (Type type : Type.getArgumentTypes(method.desc)) {
                parameters.put(slot, "arg:" + argument++ + ":" + type.getDescriptor()); slot += type.getSize();
            }
        }
        @Override public SourceValue newParameterValue(boolean instance, int local, Type type) {
            InsnNode token = new InsnNode(Opcodes.NOP); atoms.put(token, parameters.get(local));
            return new SourceValue(type.getSize(), token);
        }
        @Override public SourceValue copyOperation(AbstractInsnNode instruction, SourceValue value) { return value; }
        @Override public SourceValue unaryOperation(AbstractInsnNode instruction, SourceValue value) {
            SourceValue made = super.unaryOperation(instruction, value); return combine(made, List.of(value));
        }
        @Override public SourceValue binaryOperation(AbstractInsnNode instruction, SourceValue a, SourceValue b) {
            return combine(super.binaryOperation(instruction, a, b), List.of(a, b));
        }
        @Override public SourceValue naryOperation(AbstractInsnNode instruction, List<? extends SourceValue> values) {
            SourceValue made = super.naryOperation(instruction, values);
            return instruction == result ? new SourceValue(made.size, result) : combine(made, values);
        }
        private SourceValue combine(SourceValue made, List<? extends SourceValue> values) {
            if (made == null) return null;
            Set<AbstractInsnNode> sources = new HashSet<>(made.insns);
            for (SourceValue value : values) sources.addAll(value.insns); return new SourceValue(made.size, sources);
        }
        String atom(AbstractInsnNode instruction) {
            if (instruction == result) return "returned-value";
            if (atoms.containsKey(instruction)) return atoms.get(instruction);
            int opcode = instruction.getOpcode();
            if (instruction instanceof MethodInsnNode call) return "call:" + opcode + ":" + call.owner + ":" + call.name + call.desc;
            if (instruction instanceof FieldInsnNode field) return "field:" + opcode + ":" + field.owner + ":" + field.name + field.desc;
            if (instruction instanceof TypeInsnNode type) return "type:" + opcode + ":" + type.desc;
            if (instruction instanceof LdcInsnNode constant) return "constant:" + constant.cst;
            if (instruction instanceof IntInsnNode integer) return "integer:" + opcode + ":" + integer.operand;
            if (instruction instanceof IincInsnNode increment) return "increment:" + increment.incr;
            return "opcode:" + opcode;
        }
    }

    /** Direct definitions retain operand order. Sets are used only for a control-proved merge, never as an expression. */
    private static final class Producers {
        private static final class Parameter extends InsnNode {
            final String key; Parameter(String key) { super(Opcodes.NOP); this.key = key; }
        }
        private static final class Definitions extends SourceInterpreter {
            private final Map<Integer,String> parameters = new HashMap<>();
            Definitions(MethodNode method) {
                super(Opcodes.ASM9); int slot = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0, argument = 0;
                if (slot != 0) parameters.put(0, "this");
                for (Type type : Type.getArgumentTypes(method.desc)) { parameters.put(slot, "arg:" + argument++ + ":" + type.getDescriptor()); slot += type.getSize(); }
            }
            @Override public SourceValue newParameterValue(boolean instance, int local, Type type) { return new SourceValue(type.getSize(), new Parameter(parameters.get(local))); }
            @Override public SourceValue copyOperation(AbstractInsnNode instruction, SourceValue value) {
                // Loads retain their branch placement, and stores retain their defining value. A phi of two
                // parameter loads otherwise forgets which parameter came from which side of the condition.
                int opcode = instruction.getOpcode();
                return opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD || opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE
                        ? new SourceValue(value.getSize(), instruction) : value;
            }
        }
        private record Edge(int from, AbstractInsnNode condition, String outcome) { }
        final MethodNode method; final MethodInsnNode result; final Frame<SourceValue>[] frames;
        final List<AbstractInsnNode> code = new ArrayList<>();
        final Map<AbstractInsnNode,Integer> positions = new IdentityHashMap<>();
        final Map<Integer,Integer> containing = new HashMap<>();
        final Map<Integer,List<Edge>> predecessors = new HashMap<>();

        Producers(String owner, MethodNode method, MethodInsnNode result) throws AnalyzerException {
            this.method = method; this.result = result;
            frames = new Analyzer<>(new Definitions(method)).analyze(owner, method);
            for (AbstractInsnNode instruction : method.instructions) if (instruction.getOpcode() >= 0) { positions.put(instruction, code.size()); code.add(instruction); }
            buildGraph();
        }
        Frame<SourceValue> frame(AbstractInsnNode instruction) { return frames[method.instructions.indexOf(instruction)]; }
        SourceValue stack(AbstractInsnNode instruction, int fromTop) {
            Frame<SourceValue> at = frame(instruction); return at == null || at.getStackSize() <= fromTop ? null : at.getStack(at.getStackSize() - fromTop - 1);
        }
        String key(SourceValue value) { return key(value, new HashSet<>(), 0, -1); }
        private String key(SourceValue value, Set<AbstractInsnNode> active, int depth, int induction) {
            if (value == null || value.insns.isEmpty() || depth > 64) return null;
            if (value.insns.size() > 1) {
                String loop = loop(value, active, depth, induction); if (loop != null) return loop;
                if (value.insns.size() > 4) return null;
                List<String> alternatives = new ArrayList<>(); Set<String> conditions = new HashSet<>();
                for (AbstractInsnNode definition : value.insns) {
                    String condition = guard(definition, active, depth + 1, induction);
                    String expression = key(new SourceValue(value.getSize(), definition), active, depth + 1, induction);
                    if (condition == null || expression == null || !conditions.add(condition)) return null;
                    alternatives.add(condition + "=>" + expression);
                }
                alternatives.sort(String::compareTo); return "phi{" + String.join(";", alternatives) + "}";
            }
            AbstractInsnNode instruction = value.insns.iterator().next();
            if (instruction instanceof Parameter parameter) return parameter.key;
            if (instruction == result) return "returned-value";
            if (!active.add(instruction)) return null;
            try {
                int opcode = instruction.getOpcode(); Frame<SourceValue> at = frame(instruction); if (at == null) return null;
                if (instruction instanceof VarInsnNode variable) {
                    if (opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD) return variable.var == induction ? "induction-value:I"
                            : key(at.getLocal(variable.var), active, depth + 1, induction);
                    if (opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE) return key(stack(instruction, 0), active, depth + 1, induction);
                    return null;
                }
                String symbol; int inputs;
                if (instruction instanceof MethodInsnNode call) {
                    if (call.name.equals("<init>")) return null;
                    symbol = "call:" + opcode + ":" + call.owner + ":" + call.name + call.desc + ":" + call.itf;
                    inputs = Type.getArgumentTypes(call.desc).length + (opcode == Opcodes.INVOKESTATIC ? 0 : 1);
                    // Distinct effectful reads of the same member are not interchangeable SSA origins.
                    long occurrences = code.stream().filter(i -> i instanceof MethodInsnNode other && other.getOpcode() == opcode
                            && other.owner.equals(call.owner) && other.name.equals(call.name) && other.desc.equals(call.desc)).count();
                    if (occurrences != 1) return null;
                } else if (instruction instanceof FieldInsnNode field) {
                    if (opcode != Opcodes.GETSTATIC && opcode != Opcodes.GETFIELD) return null;
                    symbol = "field:" + opcode + ":" + field.owner + ":" + field.name + field.desc; inputs = opcode == Opcodes.GETSTATIC ? 0 : 1;
                } else if (instruction instanceof TypeInsnNode type && (opcode == Opcodes.CHECKCAST || opcode == Opcodes.INSTANCEOF)) {
                    symbol = "type:" + opcode + ":" + type.desc; inputs = 1;
                } else if (instruction instanceof LdcInsnNode constant) return "constant:" + constant.cst;
                else if (instruction instanceof IntInsnNode constant && (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH)) return "integer:" + constant.operand;
                else if (opcode == Opcodes.ACONST_NULL || opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.DCONST_1) return "constant-op:" + opcode;
                else {
                    symbol = "opcode:" + opcode;
                    if (opcode >= Opcodes.IALOAD && opcode <= Opcodes.SALOAD || opcode >= Opcodes.IADD && opcode <= Opcodes.DREM
                            || opcode >= Opcodes.ISHL && opcode <= Opcodes.LXOR || opcode >= Opcodes.LCMP && opcode <= Opcodes.DCMPG) inputs = 2;
                    else if (opcode >= Opcodes.INEG && opcode <= Opcodes.DNEG || opcode >= Opcodes.I2L && opcode <= Opcodes.I2S || opcode == Opcodes.ARRAYLENGTH) inputs = 1;
                    else return null;
                }
                List<String> operands = new ArrayList<>();
                for (int i = inputs - 1; i >= 0; i--) { String operand = key(stack(instruction, i), active, depth + 1, induction); if (operand == null) return null; operands.add(operand); }
                return symbol + "(" + String.join(",", operands) + ")";
            } finally { active.remove(instruction); }
        }

        /** A two-definition int induction: exact initial value, one step, one back edge and ordered exit predicate. */
        private String loop(SourceValue value, Set<AbstractInsnNode> active, int depth, int enclosing) {
            if (value.insns.size() != 2 || enclosing >= 0) return null;
            VarInsnNode initial = null; IincInsnNode step = null;
            for (AbstractInsnNode instruction : value.insns) {
                if (instruction instanceof VarInsnNode store && store.getOpcode() == Opcodes.ISTORE) initial = store;
                else if (instruction instanceof IincInsnNode increment) step = increment; else return null;
            }
            if (initial == null || step == null || initial.var != step.var || step.incr == 0) return null;
            int slot = initial.var; AbstractInsnNode nextStep = next(step), header = next(initial);
            if (!(nextStep instanceof JumpInsnNode back) || back.getOpcode() != Opcodes.GOTO || next(back.label) != header
                    || !(header instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ILOAD || load.var != slot) return null;
            Integer start = positions.get(header), end = positions.get(back); if (start == null || end == null || start >= end) return null;
            for (AbstractInsnNode instruction : code) {
                if (instruction instanceof VarInsnNode store && store.var == slot && store.getOpcode() >= Opcodes.ISTORE && store.getOpcode() <= Opcodes.ASTORE && store != initial) return null;
                if (instruction instanceof IincInsnNode increment && increment.var == slot && increment != step) return null;
            }
            JumpInsnNode exit = null;
            for (int i = start; i < end; i++) if (code.get(i) instanceof JumpInsnNode jump) { exit = jump; break; }
            if (exit == null || exit.getOpcode() < Opcodes.IF_ICMPEQ || exit.getOpcode() > Opcodes.IF_ICMPLE) return null;
            Integer leave = positions.get(next(exit.label)); if (leave == null || leave <= end) return null;
            // The only edge back to the header is the proved step; no outside path enters the loop body.
            for (AbstractInsnNode instruction : code) for (LabelNode label : labels(instruction)) {
                Integer destination = positions.get(next(label)); if (destination == null) return null;
                int from = positions.get(instruction);
                if (destination == start && instruction != back || (from < start || from > end) && destination > start && destination <= end) return null;
            }
            String seed = key(stack(initial, 0), active, depth + 1, -1);
            String left = key(stack(exit, 1), active, depth + 1, slot), right = key(stack(exit, 0), active, depth + 1, slot);
            if (seed == null || left == null || right == null) return null;
            return "counted-loop{initial=" + seed + ";step=" + step.incr + ";exit=" + exit.getOpcode() + "(" + left + "," + right + ")}";
        }

        private String guard(AbstractInsnNode definition, Set<AbstractInsnNode> active, int depth, int induction) {
            Integer at = positions.get(definition); if (at == null) return null; Integer block = containing.get(at); Set<Integer> visited = new HashSet<>();
            while (block != null && visited.add(block)) {
                List<Edge> incoming = predecessors.getOrDefault(block, List.of()); if (incoming.size() != 1) return null;
                Edge edge = incoming.getFirst(); AbstractInsnNode condition = edge.condition();
                if (condition != null && condition.getOpcode() != Opcodes.GOTO) {
                    int opcode = condition.getOpcode(), inputs;
                    if (opcode >= Opcodes.IF_ICMPEQ && opcode <= Opcodes.IF_ACMPNE) inputs = 2;
                    else if (opcode >= Opcodes.IFEQ && opcode <= Opcodes.IFLE || opcode == Opcodes.IFNULL || opcode == Opcodes.IFNONNULL) inputs = 1;
                    else return null;
                    List<String> operands = new ArrayList<>();
                    for (int i = inputs - 1; i >= 0; i--) { String operand = key(stack(condition, i), active, depth + 1, induction); if (operand == null) return null; operands.add(operand); }
                    return "guard:" + opcode + ":" + edge.outcome() + "(" + String.join(",", operands) + ")";
                }
                block = edge.from();
            }
            return null;
        }
        private void buildGraph() {
            if (code.isEmpty()) return;
            Set<Integer> leaders = new TreeSet<>(); leaders.add(0);
            for (int i = 0; i < code.size(); i++) {
                AbstractInsnNode instruction = code.get(i);
                for (LabelNode label : labels(instruction)) { Integer destination = positions.get(next(label)); if (destination != null) leaders.add(destination); }
                if ((instruction instanceof JumpInsnNode || instruction instanceof TableSwitchInsnNode || instruction instanceof LookupSwitchInsnNode
                        || instruction.getOpcode() >= Opcodes.IRETURN && instruction.getOpcode() <= Opcodes.RETURN || instruction.getOpcode() == Opcodes.ATHROW) && i + 1 < code.size()) leaders.add(i + 1);
            }
            List<Integer> starts = new ArrayList<>(leaders);
            for (int block = 0; block < starts.size(); block++) for (int i = starts.get(block); i < (block + 1 < starts.size() ? starts.get(block + 1) : code.size()); i++) containing.put(i, block);
            for (int block = 0; block < starts.size(); block++) {
                AbstractInsnNode terminal = code.get((block + 1 < starts.size() ? starts.get(block + 1) : code.size()) - 1);
                if (terminal instanceof JumpInsnNode jump) {
                    Integer destination = positions.get(next(jump.label)); if (destination != null) add(containing.get(destination), new Edge(block, jump, "taken"));
                    if (jump.getOpcode() != Opcodes.GOTO && block + 1 < starts.size()) add(block + 1, new Edge(block, jump, "fallthrough"));
                } else if (!(terminal instanceof TableSwitchInsnNode) && !(terminal instanceof LookupSwitchInsnNode)
                        && !(terminal.getOpcode() >= Opcodes.IRETURN && terminal.getOpcode() <= Opcodes.RETURN) && terminal.getOpcode() != Opcodes.ATHROW && block + 1 < starts.size()) add(block + 1, new Edge(block, null, "next"));
            }
        }
        private void add(Integer block, Edge edge) { if (block != null) predecessors.computeIfAbsent(block, ignored -> new ArrayList<>()).add(edge); }
        private static List<LabelNode> labels(AbstractInsnNode instruction) {
            if (instruction instanceof JumpInsnNode jump) return List.of(jump.label);
            if (instruction instanceof TableSwitchInsnNode table) { List<LabelNode> labels = new ArrayList<>(table.labels); labels.add(table.dflt); return labels; }
            if (instruction instanceof LookupSwitchInsnNode lookup) { List<LabelNode> labels = new ArrayList<>(lookup.labels); labels.add(lookup.dflt); return labels; } return List.of();
        }
        private static AbstractInsnNode next(AbstractInsnNode instruction) {
            for (AbstractInsnNode next = instruction.getNext(); next != null; next = next.getNext()) if (next.getOpcode() >= 0) return next; return null;
        }
    }
}
