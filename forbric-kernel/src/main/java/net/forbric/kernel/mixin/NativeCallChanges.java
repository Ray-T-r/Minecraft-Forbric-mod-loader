/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.*;
import java.util.function.Function;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/** Source/current call correspondence from actual operand providers and executable control flow. */
final class NativeCallChanges {
    private NativeCallChanges() { }
    record Expr(String kind, String symbol, List<Expr> inputs) {
        Expr(String kind, String symbol) { this(kind, symbol, List.of()); }
    }
    record Site(MethodInsnNode call, List<Expr> operands, String continuation, List<String> guards, int ordinal) { }
    record Context(List<String> guards,List<Expr> effects,int consumer) { }
    static List<Expr> argumentValuesAt(ClassNode owner,MethodNode method,MethodInsnNode point) {
        try {Origins origins=new Origins();var frames=new Analyzer<>(origins).analyze(owner.name,method);Evidence evidence=new Evidence(method,frames,origins.parameters);Frame<SourceValue> frame=frames[method.instructions.indexOf(point)];if(frame==null)return null;
            List<Expr> values=new ArrayList<>();int slot=(method.access&Opcodes.ACC_STATIC)==0?1:0;
            for(Type type:Type.getArgumentTypes(method.desc)){Expr value=evidence.value(frame.getLocal(slot));if(value==null)return null;values.add(value);slot+=type.getSize();}return values;
        }catch(AnalyzerException|RuntimeException invalid){return null;}
    }
    /**
     * What each local slot holds at {@code point}, as the expression that produced it (a parameter, a call on its inputs,
     * an allocation, a constant, …); slots whose value is not one such expression are absent. Null when the method does
     * not analyse or {@code point} is not in it.
     */
    static Map<Integer,Expr> localsAt(String owner,MethodNode method,AbstractInsnNode point) {
        try {Origins origins=new Origins();var frames=new Analyzer<>(origins).analyze(owner,method);Evidence evidence=new Evidence(method,frames,origins.parameters);
            int at=method.instructions.indexOf(point);Frame<SourceValue> frame=at<0?null:frames[at];if(frame==null)return null;
            Map<Integer,Expr> values=new LinkedHashMap<>();
            for(int slot=0;slot<frame.getLocals();slot++){SourceValue value=frame.getLocal(slot);if(value==null)continue;Expr expression=evidence.value(value);if(expression!=null)values.put(slot,expression);}
            return values;
        }catch(AnalyzerException|RuntimeException invalid){return null;}
    }
    /** The actual effects leading to this occurrence, with argument-producing carrier conversions kept out of the operation census. */
    static Context context(ClassNode owner,MethodNode method,MethodInsnNode point,Function<Expr,Expr> normalize,Set<Expr> providers) {
        try {
            Origins origins=new Origins();var frames=new Analyzer<>(origins).analyze(owner.name,method);Evidence evidence=new Evidence(method,frames,origins.parameters);
            List<Expr> effects=new ArrayList<>();
            for(AbstractInsnNode at:method.instructions){if(at==point)break;int opcode=at.getOpcode();Expr effect=null;
                if(!evidence.reaches(at,point))continue;
                if(at instanceof MethodInsnNode call){List<Expr> arguments=evidence.operands(call);if(arguments==null)return null;effect=new Expr("call",member(call),arguments);if(providers.contains(effect))continue;}
                else if(at instanceof FieldInsnNode field){if(opcode==Opcodes.GETFIELD||opcode==Opcodes.GETSTATIC)effect=evidence.producer(field);
                    else{List<Expr> values=evidence.inputs(field,opcode==Opcodes.PUTFIELD?2:1);if(values==null)return null;effect=new Expr("write",field.owner+"."+field.name+field.desc,values);}}
                else if(at instanceof InvokeDynamicInsnNode||opcode==Opcodes.IDIV||opcode==Opcodes.IREM||opcode==Opcodes.LDIV||opcode==Opcodes.LREM)effect=evidence.producer(at);
                else if(opcode==Opcodes.MONITORENTER||opcode==Opcodes.MONITOREXIT||opcode==Opcodes.ATHROW)return null;
                if(effect!=null && !providers.contains(effect))effects.add(normalize.apply(effect));
            }
            AbstractInsnNode consumer=point.getNext();while(consumer!=null&&(consumer.getOpcode()<0||consumer.getOpcode()==Opcodes.CHECKCAST))consumer=consumer.getNext();if(consumer==null)return null;
            return new Context(evidence.guards(point,normalize),List.copyOf(effects),consumer.getOpcode());
        }catch(AnalyzerException|RuntimeException unsupported){return null;}
    }
    static String member(MethodInsnNode call) { return "L" + call.owner + ";" + call.name + call.desc; }
    static MethodNode method(ClassNode owner, String signature) {
        return owner == null ? null : owner.methods.stream().filter(m -> (m.name + m.desc).equals(signature)).findFirst().orElse(null);
    }
    static List<Site> sites(ClassNode owner, MethodNode method) {
        if (owner == null || method == null) return List.of();
        try {
            Origins origins = new Origins(); Frame<SourceValue>[] frames = new Analyzer<>(origins).analyze(owner.name, method);
            Evidence evidence = new Evidence(method, frames, origins.parameters); Map<String, Integer> ordinals = new HashMap<>(); List<Site> result = new ArrayList<>();
            for (AbstractInsnNode i : method.instructions) if (i instanceof MethodInsnNode call) {
                int ordinal = ordinals.merge(member(call), 1, Integer::sum) - 1;
                List<Expr> operands = evidence.operands(call);
                if (operands != null) result.add(new Site(call, operands, evidence.continuation(call), evidence.guards(call), ordinal));
            }
            return result;
        } catch (AnalyzerException | RuntimeException invalid) { return List.of(); }
    }
    /** Exactly one candidate at the same guarded operation, preserving each source input or its provider. */
    static Site match(Site source, List<Site> current, java.util.function.Predicate<MethodInsnNode> eligible) {
        Site found = null;
        for (Site candidate : current) {
            if (!eligible.test(candidate.call) || source.continuation == null || !source.continuation.equals(candidate.continuation)
                    || source.guards == null || !source.guards.equals(candidate.guards)) continue;
            if (found != null) return null; found = candidate;
        }
        return found;
    }
    static int[] carried(List<Expr> source, List<Expr> current) {
        int[] result = new int[source.size()]; Arrays.fill(result, -1);
        for (int old = 0; old < source.size(); old++) for (int live = 0; live < current.size(); live++) if (source.get(old).equals(current.get(live))) {
            if (result[old] >= 0) return null; result[old] = live;
        }
        return result;
    }
    static List<String> projection(List<Expr> source, List<Expr> providers, List<Type> types) {
        List<String> projection = new ArrayList<>();
        for (Expr input : source) {
            String expression = null;
            for (int p = 0; p < providers.size(); p++) {
                Expr provider = providers.get(p); String candidate = input.equals(provider) ? "$" + p : getter(input, provider, p, types.get(p));
                if (candidate != null) { if (expression != null) return null; expression = candidate; }
            }
            if (expression == null) return null; projection.add(expression);
        }
        return projection;
    }
    private static String getter(Expr value, Expr provider, int index, Type type) {
        if (!value.kind.equals("call") || value.inputs.size() != 1 || !value.inputs.getFirst().equals(provider) || type.getSort() != Type.OBJECT) return null;
        MixinFit.Member getter = MixinFit.parseMember(value.symbol);
        return getter != null && getter.owner().equals(type.getInternalName()) && Type.getArgumentTypes(getter.desc()).length == 0
                ? "$" + index + "." + getter.name() + getter.desc() : null;
    }
    static boolean oneChangedCall(MethodNode source, MethodNode current, MethodInsnNode old, MethodInsnNode replacement) {
        if (!source.desc.equals(current.desc) || ((source.access ^ current.access) & Opcodes.ACC_STATIC) != 0) return false;
        MethodNode substituted = new MethodNode(source.access, source.name, source.desc, null, null); source.accept(substituted);
        List<AbstractInsnNode> original = real(source), cloned = real(substituted); int index = original.indexOf(old);
        if (index < 0) return false;
        substituted.instructions.set(cloned.get(index), new MethodInsnNode(replacement.getOpcode(), replacement.owner, replacement.name, replacement.desc, replacement.itf));
        return MixinInstructionFingerprint.hash(substituted).equals(MixinInstructionFingerprint.hash(current)) && locals(source).equals(locals(current));
    }
    private static List<String> locals(MethodNode method) {
        List<String> result = new ArrayList<>();
        if (method.localVariables != null) for (LocalVariableNode local : method.localVariables) result.add(local.index + ":" + local.desc + ":" + offset(method, local.start) + ":" + offset(method, local.end));
        return result;
    }
    private static int offset(MethodNode method, AbstractInsnNode at) { int n = 0; for (AbstractInsnNode i : method.instructions) { if (i == at) return n; if (i.getOpcode() >= 0) n++; } return -1; }
    private static List<AbstractInsnNode> real(MethodNode method) { return Arrays.stream(method.instructions.toArray()).filter(i -> i.getOpcode() >= 0).toList(); }
    private static final class Origins extends SourceInterpreter {
        private final Set<AbstractInsnNode> parameters = Collections.newSetFromMap(new IdentityHashMap<>());
        Origins() { super(Opcodes.ASM9); }
        @Override public SourceValue newParameterValue(boolean instance, int local, Type type) { VarInsnNode parameter = new VarInsnNode(type.getOpcode(Opcodes.ILOAD), local); parameters.add(parameter); return new SourceValue(type.getSize(), parameter); }
        @Override public SourceValue copyOperation(AbstractInsnNode instruction, SourceValue value) { return value; }
    }
    private static final class Evidence {
        final MethodNode method; final Frame<SourceValue>[] frames; final Set<AbstractInsnNode> parameters;
        final Map<AbstractInsnNode, Expr> cache = new IdentityHashMap<>(); final Set<AbstractInsnNode> visiting = Collections.newSetFromMap(new IdentityHashMap<>());
        Evidence(MethodNode method, Frame<SourceValue>[] frames, Set<AbstractInsnNode> parameters) { this.method = method; this.frames = frames; this.parameters = parameters; }
        Expr value(SourceValue value) {
            if (value == null) return null;
            if (value.insns.size() == 1) return producer(value.insns.iterator().next());
            // A counted array traversal is identified by its birth, increment and tested bound, not its local slot.
            List<IincInsnNode> increments = value.insns.stream().filter(IincInsnNode.class::isInstance).map(IincInsnNode.class::cast).toList();
            if (increments.size() != 1 || value.insns.size() != 2) return null;
            IincInsnNode step = increments.getFirst(); AbstractInsnNode initial = value.insns.stream().filter(i -> i != step).findFirst().orElse(null);
            Expr beginning = initial == null ? null : producer(initial); if (beginning == null) return null;
            for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof JumpInsnNode branch
                    && branch.getOpcode() >= Opcodes.IF_ICMPEQ && branch.getOpcode() <= Opcodes.IF_ICMPLE) {
                Frame<SourceValue> frame = frames[method.instructions.indexOf(branch)]; if (frame == null || frame.getStackSize() < 2) continue;
                SourceValue left = frame.getStack(frame.getStackSize() - 2), right = frame.getStack(frame.getStackSize() - 1);
                SourceValue bound = left.insns.equals(value.insns) ? right : right.insns.equals(value.insns) ? left : null;
                if (bound == null || bound.insns.contains(step)) continue;
                boolean backedge = false;
                for (AbstractInsnNode later = step.getNext(); later != null; later = later.getNext()) {
                    if (later instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.GOTO
                            && method.instructions.indexOf(jump.label) <= method.instructions.indexOf(branch)) { backedge = true; break; }
                    if (later.getOpcode() >= Opcodes.IRETURN && later.getOpcode() <= Opcodes.RETURN) break;
                }
                Expr limit = backedge ? value(bound) : null;
                if (limit != null) return new Expr("iteration", step.incr + ":" + branch.getOpcode() + ":" + left.insns.equals(value.insns), List.of(beginning, limit));
            }
            return null;
        }
        Expr producer(AbstractInsnNode i) {
            if (cache.containsKey(i)) return cache.get(i); if (!visiting.add(i)) return null; Expr result = null;
            if (parameters.contains(i)) result = new Expr("parameter", Integer.toString(((VarInsnNode) i).var));
            else if (i instanceof LdcInsnNode n) result = new Expr("literal", n.cst.getClass().getName() + ":" + n.cst);
            else if (i instanceof IntInsnNode n && (i.getOpcode() == Opcodes.BIPUSH || i.getOpcode() == Opcodes.SIPUSH)) result = new Expr("integer", Integer.toString(n.operand));
            else if (i.getOpcode() >= Opcodes.ACONST_NULL && i.getOpcode() <= Opcodes.DCONST_1) result = new Expr("constant", Integer.toString(i.getOpcode()));
            else if (i instanceof FieldInsnNode field) {
                String symbol = "L" + field.owner + ";" + field.name + ":" + field.desc;
                if (i.getOpcode() == Opcodes.GETSTATIC) result = new Expr("static-field", symbol);
                else if (i.getOpcode() == Opcodes.GETFIELD) { List<Expr> receiver = inputs(i, 1); if (receiver != null) result = new Expr("field", symbol, receiver); }
            } else if (i instanceof MethodInsnNode call) { List<Expr> args = operands(call); if (args != null) result = new Expr("call", member(call), args); }
            else if (i instanceof TypeInsnNode cast && i.getOpcode() == Opcodes.CHECKCAST) { List<Expr> source = inputs(i, 1); if (source != null) result = source.getFirst(); }
            else if (i instanceof TypeInsnNode created && i.getOpcode() == Opcodes.NEW) {
                MethodInsnNode constructor = null; List<Expr> arguments = null;
                for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKESPECIAL && call.name.equals("<init>") && call.owner.equals(created.desc)) {
                    Frame<SourceValue> frame = frames[method.instructions.indexOf(call)]; int count = Type.getArgumentTypes(call.desc).length;
                    if (frame == null || frame.getStackSize() <= count) continue;
                    SourceValue receiver = frame.getStack(frame.getStackSize() - count - 1);
                    if (receiver.insns.size() != 1 || !receiver.insns.contains(created)) continue;
                    if (constructor != null) { constructor = null; break; }
                    constructor = call; arguments = inputs(call, count);
                }
                if (constructor != null && arguments != null) {
                    int ordinal = 0; for (AbstractInsnNode previous : method.instructions) { if (previous == created) break; if (previous instanceof TypeInsnNode other && previous.getOpcode() == Opcodes.NEW && other.desc.equals(created.desc)) ordinal++; }
                    result = new Expr("allocation", created.desc + constructor.desc + ":" + ordinal, arguments);
                }
            } else if (i instanceof InvokeDynamicInsnNode dynamic) {
                List<Expr> arguments = inputs(i, Type.getArgumentTypes(dynamic.desc).length);
                if (arguments != null) result = new Expr("dynamic", dynamic.name + dynamic.desc + dynamic.bsm + Arrays.toString(dynamic.bsmArgs), arguments);
            }
            else if (i.getOpcode() == Opcodes.AALOAD) { List<Expr> source = inputs(i, 2); if (source != null) result = new Expr("array-read", "", source); }
            else if (i.getOpcode() == Opcodes.ARRAYLENGTH) { List<Expr> source = inputs(i, 1); if (source != null) result = new Expr("array-length", "", source); }
            else if (i.getOpcode() >= Opcodes.IADD && i.getOpcode() <= Opcodes.DREM || i.getOpcode() >= Opcodes.ISHL && i.getOpcode() <= Opcodes.LXOR) { List<Expr> source = inputs(i, 2); if (source != null) result = new Expr("operator", Integer.toString(i.getOpcode()), source); }
            else if (i.getOpcode() >= Opcodes.INEG && i.getOpcode() <= Opcodes.DNEG || i.getOpcode() >= Opcodes.I2L && i.getOpcode() <= Opcodes.I2S) { List<Expr> source = inputs(i, 1); if (source != null) result = new Expr("operator", Integer.toString(i.getOpcode()), source); }
            visiting.remove(i); cache.put(i, result); return result;
        }
        List<Expr> operands(MethodInsnNode call) { return inputs(call, Type.getArgumentTypes(call.desc).length + (call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1)); }
        List<Expr> inputs(AbstractInsnNode i, int count) {
            Frame<SourceValue> frame = frames[method.instructions.indexOf(i)]; if (frame == null || frame.getStackSize() < count) return null;
            List<Expr> result = new ArrayList<>();
            for (int p = frame.getStackSize() - count; p < frame.getStackSize(); p++) { Expr expression = value(frame.getStack(p)); if (expression == null) return null; result.add(expression); }
            return result;
        }
        List<String> guards(AbstractInsnNode call) {
            return guards(call,Function.identity());
        }
        List<String> guards(AbstractInsnNode call,Function<Expr,Expr> normalize) {
            List<String> result = new ArrayList<>();
            for (AbstractInsnNode i : method.instructions) if (i instanceof JumpInsnNode branch && branch.getOpcode() != Opcodes.GOTO && branch.getOpcode() != Opcodes.JSR) {
                // A condition after the current occurrence only governs a later loop iteration.
                if(method.instructions.indexOf(i)>=method.instructions.indexOf(call))continue;
                boolean taken = reaches(branch.label, call), other = reaches(branch.getNext(), call); if (taken == other) continue;
                List<Expr> operands = inputs(branch, branch.getOpcode() >= Opcodes.IF_ICMPEQ && branch.getOpcode() <= Opcodes.IF_ACMPNE ? 2 : 1);
                if (operands == null) return null;
                int opcode=branch.getOpcode(); boolean positive=taken;
                if(opcode==Opcodes.IFNE||opcode==Opcodes.IFGE||opcode==Opcodes.IFLE||opcode==Opcodes.IF_ICMPNE||opcode==Opcodes.IF_ICMPGE||opcode==Opcodes.IF_ICMPLE||opcode==Opcodes.IF_ACMPNE||opcode==Opcodes.IFNONNULL){opcode--;positive=!positive;}
                result.add(opcode + ":" + positive + ":" + operands.stream().map(normalize).toList());
            }
            return result;
        }
        String continuation(MethodInsnNode call) {
            AbstractInsnNode next = call.getNext(); while (next != null && next.getOpcode() < 0) next = next.getNext(); if (next == null) return null;
            // CHECKCAST is the source's narrowing of an Object-returning provider to the live return type.
            if (next.getOpcode() == Opcodes.CHECKCAST) { next = next.getNext(); while (next != null && next.getOpcode() < 0) next = next.getNext(); }
            if (next instanceof JumpInsnNode branch) {
                int opcode=branch.getOpcode();String taken=landmark(branch.label),other=landmark(branch.getNext());
                if(taken==null||other==null)return null;
                if(opcode==Opcodes.IFNE||opcode==Opcodes.IFGE||opcode==Opcodes.IFLE||opcode==Opcodes.IF_ICMPNE||opcode==Opcodes.IF_ICMPGE||opcode==Opcodes.IF_ICMPLE||opcode==Opcodes.IF_ACMPNE||opcode==Opcodes.IFNONNULL){opcode--;String swap=taken;taken=other;other=swap;}
                return "branch:"+opcode+":"+taken+":"+other;
            }
            if (next instanceof VarInsnNode store && store.getOpcode() >= Opcodes.ISTORE && store.getOpcode() <= Opcodes.ASTORE) return "store:" + store.var + ":" + store.getOpcode();
            if (next != null && next.getOpcode() >= Opcodes.IRETURN && next.getOpcode() <= Opcodes.RETURN) return "return:" + next.getOpcode();
            if (next != null && (next.getOpcode() == Opcodes.POP || next.getOpcode() == Opcodes.POP2)) { String target = landmark(next.getNext()); return target == null ? null : "discard:" + target; }
            return next == null ? null : "next:" + next.getOpcode();
        }
        String landmark(AbstractInsnNode start) {
            for (AbstractInsnNode i = start; i != null; i = i.getNext()) {
                if (i.getOpcode() < 0 || i.getOpcode() == Opcodes.NOP) continue;
                if (i instanceof MethodInsnNode call) return "call:" + member(call);
                if (i instanceof FieldInsnNode field && (i.getOpcode() == Opcodes.PUTFIELD || i.getOpcode() == Opcodes.PUTSTATIC)) return "write:" + field.owner + "." + field.name + field.desc;
                if (i instanceof LdcInsnNode constant) return "constant:" + constant.cst;
                if (i.getOpcode() >= Opcodes.IRETURN && i.getOpcode() <= Opcodes.RETURN) return "return:" + i.getOpcode();
                if (i instanceof JumpInsnNode) return null;
            }
            return null;
        }
        boolean reaches(AbstractInsnNode start, AbstractInsnNode target) {
            List<AbstractInsnNode> pending = new ArrayList<>(); pending.add(start); Set<AbstractInsnNode> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            while (!pending.isEmpty()) {
                AbstractInsnNode i = pending.removeLast(); if (i == null || !seen.add(i)) continue; if (i == target) return true;
                if (i.getOpcode() >= Opcodes.IRETURN && i.getOpcode() <= Opcodes.RETURN || i.getOpcode() == Opcodes.ATHROW) continue;
                if (i instanceof JumpInsnNode branch) { pending.add(branch.label); if (i.getOpcode() != Opcodes.GOTO) pending.add(i.getNext()); }
                else if (i instanceof TableSwitchInsnNode table) { pending.add(table.dflt); pending.addAll(table.labels); }
                else if (i instanceof LookupSwitchInsnNode table) { pending.add(table.dflt); pending.addAll(table.labels); }
                else pending.add(i.getNext());
            }
            return false;
        }
    }
}
