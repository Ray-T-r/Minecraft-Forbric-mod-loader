/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/** Pairs retained calls by operand origins and their next operation, never by a game-specific ordinal table. */
final class CallOccurrenceAlignment {
    private CallOccurrenceAlignment() { }
    record Occurrence(MethodInsnNode call, String operands, String continuation) { }
    record Match(MethodInsnNode call, int ordinal) { }
    /** Opaque primitive results can be paired when every executable operation up to their production is identical. */
    static Map<Integer,Integer> prefixLocals(MethodNode handler,String owner,MethodNode nativeMethod,AbstractInsnNode nativePoint,
            MethodNode current,AbstractInsnNode currentPoint) {
        try {
            if(!nativeMethod.desc.equals(current.desc)||((nativeMethod.access^current.access)&Opcodes.ACC_STATIC)!=0)return null;
            Origins a=new Origins(),b=new Origins();
            var before=new Analyzer<>(a).analyze(owner,nativeMethod);var after=new Analyzer<>(b).analyze(owner,current);
            var nativeTypes=new Analyzer<>(new BasicInterpreter()).analyze(owner,nativeMethod);
            var liveTypes=new Analyzer<>(new BasicInterpreter()).analyze(owner,current);
            int oldAt=nativeMethod.instructions.indexOf(nativePoint),at=current.instructions.indexOf(currentPoint);
            if(oldAt<0||at<0||before[oldAt]==null||after[at]==null)return null;
            Map<Integer,Integer> result=new java.util.LinkedHashMap<>();Type[] parameters=Type.getArgumentTypes(handler.desc);
            for(int p=0;p<parameters.length;p++) {
                AnnotationNode local=MixinStubRebind.sugar(handler,p,MixinRetarget.LOCAL_SUGAR);if(local==null)continue;
                Type type=parameters[p];if(type.getSort()==Type.OBJECT||type.getSort()==Type.ARRAY)return null;
                List<Integer> candidates=localSlots(nativeTypes[oldAt],type);
                Object explicit=MixinFit.value(local,"index"),ordinal=MixinFit.value(local,"ordinal");
                List<String> names=MixinFit.stringList(MixinFit.value(local,"name"));
                if(Boolean.TRUE.equals(MixinFit.value(local,"argsOnly")))return null;
                if(explicit instanceof Number index&&index.intValue()>=0)candidates=candidates.stream().filter(slot->slot==index.intValue()).toList();
                if(!names.isEmpty())candidates=candidates.stream().filter(slot->nativeMethod.localVariables!=null&&nativeMethod.localVariables.stream()
                        .anyMatch(v->v.index==slot&&names.contains(v.name)&&nativeMethod.instructions.indexOf(v.start)<=oldAt&&oldAt<nativeMethod.instructions.indexOf(v.end))).toList();
                if(ordinal instanceof Number index&&index.intValue()>=0)candidates=index.intValue()<candidates.size()?List.of(candidates.get(index.intValue())):List.of();
                if(candidates.size()!=1)return null;
                SourceValue source=before[oldAt].getLocal(candidates.getFirst());if(source.insns.size()!=1)return null;
                List<String> wanted=straightPrefix(nativeMethod,source.insns.iterator().next());if(wanted==null)return null;
                int match=-1;
                for(int slot:localSlots(liveTypes[at],type)) {
                    SourceValue live=after[at].getLocal(slot);if(live.insns.size()!=1)continue;
                    List<String> prefix=straightPrefix(current,live.insns.iterator().next());
                    if(!wanted.equals(prefix))continue;
                    if(match>=0)return null;match=slot;
                }
                if(match<0)return null;result.put(p,match);
            }
            return result;
        }catch(AnalyzerException|RuntimeException invalid){return null;}
    }
    private static List<Integer> localSlots(Frame<BasicValue> frame,Type type) {
        List<Integer> slots=new ArrayList<>();if(frame==null)return slots;
        for(int slot=0;slot<frame.getLocals();slot++)if(frame.getLocal(slot)!=null&&type.equals(frame.getLocal(slot).getType()))slots.add(slot);
        return slots;
    }
    private static List<String> straightPrefix(MethodNode method,AbstractInsnNode producer) {
        if(method.instructions.indexOf(producer)<0)return null;
        List<String> tokens=new ArrayList<>();
        for(var instruction:method.instructions) {
            int opcode=instruction.getOpcode();if(opcode<0)continue;
            if(instruction instanceof JumpInsnNode||instruction instanceof LookupSwitchInsnNode||instruction instanceof TableSwitchInsnNode)return null;
            String token="opcode:"+opcode;
            if(instruction instanceof VarInsnNode variable)token+=":"+variable.var;
            else if(instruction instanceof FieldInsnNode field)token+=":"+field.owner+"."+field.name+field.desc;
            else if(instruction instanceof MethodInsnNode call)token+=":"+member(call)+":"+call.itf;
            else if(instruction instanceof TypeInsnNode type)token+=":"+type.desc;
            else if(instruction instanceof LdcInsnNode literal)token+=":"+literal.cst;
            else if(instruction instanceof IntInsnNode integer)token+=":"+integer.operand;
            else if(instruction instanceof InvokeDynamicInsnNode||instruction instanceof IincInsnNode)return null;
            tokens.add(token);if(instruction==producer)return tokens;
        }
        return null;
    }


    /** Added event arguments remain on the live call; only their birth operands identify the source program point. */
    static Match prefixCall(ClassNode sourceOwner, MethodNode source, ClassNode liveOwner, MethodNode live,
            String sourceMember, int sourceOrdinal, java.util.function.Function<String, ClassNode> declarations) {
        try {
            Origins beforeOrigins = new Origins(), afterOrigins = new Origins();
            Evidence before = new Evidence(source, new Analyzer<>(beforeOrigins).analyze(sourceOwner.name, source), beforeOrigins.parameters, declarations);
            Evidence after = new Evidence(live, new Analyzer<>(afterOrigins).analyze(liveOwner.name, live), afterOrigins.parameters, declarations);
            List<MethodInsnNode> sourceCalls = new ArrayList<>();
            for (var instruction : source.instructions) if (instruction instanceof MethodInsnNode call && member(call).equals(sourceMember)) sourceCalls.add(call);
            if (sourceOrdinal < 0 || sourceOrdinal >= sourceCalls.size()) return null;
            MethodInsnNode old = sourceCalls.get(sourceOrdinal); Type[] args = Type.getArgumentTypes(old.desc);
            String expected = before.callPrefix(old, args.length);
            if (expected == null) return null;
            Match found = null;
            Map<String, Integer> ordinals = new java.util.HashMap<>();
            for (var instruction : live.instructions) if (instruction instanceof MethodInsnNode call) {
                int ordinal = ordinals.merge(member(call), 1, Integer::sum) - 1;
                Type[] parameters = Type.getArgumentTypes(call.desc);
                if (call.getOpcode() != old.getOpcode() || !call.owner.equals(old.owner) || !call.name.equals(old.name)
                        || !Type.getReturnType(call.desc).equals(Type.getReturnType(old.desc)) || parameters.length < args.length) continue;
                boolean prefix = true;
                for (int p = 0; p < args.length; p++) prefix &= args[p].equals(parameters[p]);
                if (!prefix || !expected.equals(after.callPrefix(call, args.length))
                        || !before.guards(old).equals(after.guards(call))) continue;
                if (found != null) return null;
                found = new Match(call, ordinal);
            }
            return found;
        } catch (AnalyzerException | RuntimeException invalid) { return null; }
    }

    /** An ambiguous, unreachable, escaping or unsupported origin is deliberately not evidence. */
    static int retainedOrdinal(ClassNode originalOwner, MethodNode original, ClassNode currentOwner,
            MethodNode current, String member, int ordinal) {
        if (originalOwner == null || currentOwner == null || original == null || current == null
                || !original.desc.equals(current.desc) || ((original.access ^ current.access) & Opcodes.ACC_STATIC) != 0) return -1;
        List<Occurrence> before = occurrences(originalOwner.name, original, member);
        List<Occurrence> after = occurrences(currentOwner.name, current, member);
        if (before == null || after == null || ordinal < 0 || ordinal >= before.size() || after.size() >= before.size()) return -1;
        Occurrence wanted = before.get(ordinal);
        if (wanted.operands() == null || wanted.continuation() == null) return -1;
        // Every surviving occurrence must have one unique native counterpart, in native execution order.
        int previous = -1, result = -1;
        for (int index = 0; index < after.size(); index++) {
            Occurrence live = after.get(index);
            if (live.operands() == null || live.continuation() == null) return -1;
            int match = -1;
            for (int source = 0; source < before.size(); source++) {
                Occurrence old = before.get(source);
                if (live.operands().equals(old.operands()) && live.continuation().equals(old.continuation())) {
                    if (match >= 0) return -1;
                    match = source;
                }
            }
            if (match <= previous) return -1;
            previous = match;
            if (match == ordinal) result = index;
        }
        return result;
    }

    /**
     * Describes each side effect of a straight-line run of {@code method} — a call, a field write, a local store — by
     * its operation and the origins of its operands, never by local slot numbers: two bodies that keep a temporary in
     * different locals describe the same run identically. Operand producers (loads, constants, arithmetic, reads) are
     * described through the effects that consume them. The method is analysed once; a run with any opaque operation or
     * operand origin, or any run of an unanalysable method, is described as null.
     */
    static java.util.function.Function<List<AbstractInsnNode>, List<String>> effects(String owner, MethodNode method) {
        Evidence evidence;
        try {
            Origins origins = new Origins();
            evidence = new Evidence(method, new Analyzer<>(origins).analyze(owner, method), origins.parameters);
        } catch (AnalyzerException | RuntimeException invalid) { return run -> null; }
        return run -> effects(evidence, run);
    }

    private static List<String> effects(Evidence evidence, List<AbstractInsnNode> run) {
        try {
            List<String> effects = new ArrayList<>();
            for (AbstractInsnNode instruction : run) {
                int opcode = instruction.getOpcode();
                if (opcode < 0 || producesOnly(opcode)) continue;
                String effect = null;
                if (instruction instanceof MethodInsnNode call) effect = evidence.call(call);
                else if (instruction instanceof FieldInsnNode field && (opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC)) {
                    String inputs = evidence.inputs(field, opcode == Opcodes.PUTFIELD ? 2 : 1);
                    if (inputs != null) effect = "put:" + field.owner + "." + field.name + field.desc + inputs;
                } else if (opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE) {
                    String inputs = evidence.inputs(instruction, 1);
                    if (inputs != null) effect = "store:" + opcode + inputs;
                } else if (instruction instanceof IincInsnNode increment) effect = "iinc:" + increment.incr;
                if (effect == null) return null;
                effects.add(effect);
            }
            return effects;
        } catch (RuntimeException invalid) { return null; }
    }

    /** Operations whose only effect is a value on the stack (or a stack shuffle): their consumers describe them. */
    private static boolean producesOnly(int opcode) {
        return opcode == Opcodes.NOP || opcode >= Opcodes.ACONST_NULL && opcode <= Opcodes.SALOAD
                || opcode >= Opcodes.POP && opcode <= Opcodes.SWAP || opcode >= Opcodes.IADD && opcode <= Opcodes.LXOR
                || opcode >= Opcodes.I2L && opcode <= Opcodes.DCMPG || opcode == Opcodes.GETSTATIC || opcode == Opcodes.GETFIELD
                || opcode == Opcodes.NEW || opcode == Opcodes.ARRAYLENGTH || opcode == Opcodes.CHECKCAST
                || opcode == Opcodes.INSTANCEOF;
    }

    private static List<Occurrence> occurrences(String owner, MethodNode method, String member) {
        try {
            Origins origins = new Origins();
            Frame<SourceValue>[] frames = new Analyzer<>(origins).analyze(owner, method);
            Evidence evidence = new Evidence(method, frames, origins.parameters);
            List<Occurrence> result = new ArrayList<>();
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call) || !member(call).equals(member)) continue;
                result.add(new Occurrence(call, evidence.call(call), evidence.continuation(call)));
            }
            return result;
        } catch (AnalyzerException | RuntimeException invalid) { return null; }
    }

    private static final class Origins extends SourceInterpreter {
        final Set<AbstractInsnNode> parameters = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        Origins() { super(Opcodes.ASM9); }
        @Override public SourceValue newParameterValue(boolean instance, int local, Type type) {
            VarInsnNode parameter = new VarInsnNode(type.getOpcode(Opcodes.ILOAD), local);
            parameters.add(parameter);
            return new SourceValue(type.getSize(), parameter);
        }
        @Override public SourceValue copyOperation(AbstractInsnNode instruction, SourceValue value) { return value; }
    }

    private static final class Evidence {
        final MethodNode method;
        final Frame<SourceValue>[] frames;
        final Set<AbstractInsnNode> parameters;
        final java.util.function.Function<String, ClassNode> declarations;
        final Map<AbstractInsnNode, String> cached = new IdentityHashMap<>();
        final Set<AbstractInsnNode> visiting = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        /** Origins whose guards are being described: a loop makes a guard's operand a phi of the origin itself. */
        final Set<AbstractInsnNode> guarding = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        final Map<AbstractInsnNode, List<String>> guardCache = new IdentityHashMap<>();
        /** Deeper than this, a description is not evidence anyone can compare; it is answered as unprovable. */
        static final int MAX_GUARD_DEPTH = 32;
        Evidence(MethodNode method, Frame<SourceValue>[] frames, Set<AbstractInsnNode> parameters) {
            this(method, frames, parameters, null);
        }
        Evidence(MethodNode method, Frame<SourceValue>[] frames, Set<AbstractInsnNode> parameters,
                java.util.function.Function<String, ClassNode> declarations) {
            this.method = method; this.frames = frames; this.parameters = parameters; this.declarations = declarations;
        }
        String value(SourceValue value) {
            if (value == null || value.insns.isEmpty()) return null;
            if (value.insns.size() == 1) return producer(value.insns.iterator().next());
            // A phi keeps each alternative's controlling conditions; swapping the two branches is not equality.
            java.util.Set<String> alternatives = new java.util.TreeSet<>();
            for (AbstractInsnNode origin : value.insns) {
                String expression = producer(origin); List<String> conditions = guards(origin);
                if (expression == null || conditions == null) return null;
                alternatives.add(expression + "when:" + conditions);
            }
            return "phi:" + alternatives;
        }
        String producer(AbstractInsnNode instruction) {
            if (cached.containsKey(instruction)) return cached.get(instruction);
            if (!visiting.add(instruction)) return null;
            String result = null;
            if (parameters.contains(instruction)) result = "parameter:" + ((VarInsnNode) instruction).var;
            else if (instruction instanceof LdcInsnNode literal) result = "literal:" + literal.cst.getClass().getName() + ":" + literal.cst;
            else if (instruction instanceof IntInsnNode integer && (integer.getOpcode() == Opcodes.BIPUSH || integer.getOpcode() == Opcodes.SIPUSH)) result = "integer:" + integer.operand;
            else if (instruction.getOpcode() >= Opcodes.ACONST_NULL && instruction.getOpcode() <= Opcodes.DCONST_1) result = "constant:" + instruction.getOpcode();
            else if (instruction instanceof FieldInsnNode field) {
                if (field.getOpcode() == Opcodes.GETSTATIC) result = "field:" + field.owner + "." + field.name + field.desc;
                else if (field.getOpcode() == Opcodes.GETFIELD) {
                    String receiver = inputs(instruction, 1);
                    if (receiver != null) result = "field:" + field.owner + "." + field.name + field.desc + receiver;
                }
            } else if (instruction instanceof MethodInsnNode call) {
                result = projection(call);
                if (result == null) result = call(call);
            }
            else if (instruction instanceof TypeInsnNode cast && (cast.getOpcode() == Opcodes.CHECKCAST || cast.getOpcode() == Opcodes.INSTANCEOF)) {
                String source = inputs(instruction, 1);
                if (source != null) result = "type:" + cast.getOpcode() + ":" + cast.desc + source;
            } else if (instruction.getOpcode() >= Opcodes.INEG && instruction.getOpcode() <= Opcodes.DNEG) {
                String source = inputs(instruction, 1);
                if (source != null) result = "unary:" + instruction.getOpcode() + source;
            } else if (instruction.getOpcode() >= Opcodes.IADD && instruction.getOpcode() <= Opcodes.DREM) {
                String source = inputs(instruction, 2);
                if (source != null) result = "binary:" + instruction.getOpcode() + source;
            }
            visiting.remove(instruction); cached.put(instruction, result); return result;
        }
        String inputs(AbstractInsnNode instruction, int count) {
            int index = method.instructions.indexOf(instruction);
            Frame<SourceValue> frame = index < 0 ? null : frames[index];
            if (frame == null || frame.getStackSize() < count) return null;
            List<String> values = new ArrayList<>();
            for (int n = frame.getStackSize() - count; n < frame.getStackSize(); n++) {
                String source = value(frame.getStack(n));
                if (source == null) return null;
                values.add(source);
            }
            return values.toString();
        }
        String call(MethodInsnNode call) {
            String inputs = inputs(call, Type.getArgumentTypes(call.desc).length + (call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1));
            return inputs == null ? null : call.getOpcode() + ":" + member(call) + inputs;
        }
        String callPrefix(MethodInsnNode call, int prefix) {
            Frame<SourceValue> frame = frames[method.instructions.indexOf(call)];
            int count = Type.getArgumentTypes(call.desc).length, receiver = call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1;
            if (frame == null || frame.getStackSize() < count + receiver) return null;
            List<String> values = new ArrayList<>();
            for (int p = frame.getStackSize() - count - receiver; p < frame.getStackSize() - count + prefix; p++) {
                String source = value(frame.getStack(p)); if (source == null) return null; values.add(source);
            }
            return values.toString();
        }
        String projection(MethodInsnNode getter) {
            if (declarations == null || getter.getOpcode() == Opcodes.INVOKESTATIC || Type.getArgumentTypes(getter.desc).length != 0) return null;
            ClassNode owner;try{owner=declarations.apply(getter.owner);}catch(RuntimeException unavailable){return null;}if(owner==null||!owner.name.equals(getter.owner))return null;
            List<MethodNode> methods = owner.methods.stream().filter(m -> m.name.equals(getter.name) && m.desc.equals(getter.desc)).toList();
            if (methods.size() != 1) return null;
            List<AbstractInsnNode> body = real(methods.getFirst());
            if (body.size() != 3 || !(body.get(0) instanceof VarInsnNode self) || self.var != 0 || self.getOpcode() != Opcodes.ALOAD
                    || !(body.get(1) instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETFIELD
                    || body.get(2).getOpcode() != Type.getReturnType(getter.desc).getOpcode(Opcodes.IRETURN)) return null;
            Frame<SourceValue> getterFrame = frames[method.instructions.indexOf(getter)];
            if (getterFrame == null || getterFrame.getStackSize() == 0) return null;
            SourceValue allocation = birth(getterFrame.getStack(getterFrame.getStackSize() - 1));
            if (allocation == null || allocation.insns.size() != 1 || !(allocation.insns.iterator().next() instanceof TypeInsnNode created)
                    || created.getOpcode() != Opcodes.NEW || !created.desc.equals(owner.name)) return null;
            MethodInsnNode constructor = null; Frame<SourceValue> constructorFrame = null;
            for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && call.name.equals("<init>") && call.owner.equals(owner.name)) {
                Frame<SourceValue> frame = frames[method.instructions.indexOf(call)]; int count = Type.getArgumentTypes(call.desc).length;
                if (frame != null && frame.getStackSize() > count && frame.getStack(frame.getStackSize() - count - 1).insns.equals(allocation.insns)) {
                    if (constructor != null) return null; constructor = call; constructorFrame = frame;
                }
            }
            if (constructor == null) return null;
            MethodInsnNode ctor = constructor;
            MethodNode declaration = owner.methods.stream().filter(m -> m.name.equals("<init>") && m.desc.equals(ctor.desc)).findFirst().orElse(null);
            if (declaration == null) return null;
            int slot = -1;
            for (var instruction : declaration.instructions) if (instruction instanceof FieldInsnNode write && write.getOpcode() == Opcodes.PUTFIELD
                    && write.owner.equals(field.owner) && write.name.equals(field.name) && write.desc.equals(field.desc)) {
                AbstractInsnNode previous = previous(write);
                if (slot >= 0 || !(previous instanceof VarInsnNode argument) || argument.getOpcode() != Type.getType(field.desc).getOpcode(Opcodes.ILOAD)
                        || !(previous(argument) instanceof VarInsnNode receiver) || receiver.var != 0 || receiver.getOpcode() != Opcodes.ALOAD) return null;
                slot = argument.var;
            }
            for (var instruction : declaration.instructions) if (instruction instanceof MethodInsnNode setter
                    && setter.getOpcode() == Opcodes.INVOKEVIRTUAL && setter.owner.equals(owner.name)
                    && setter.desc.equals("(" + field.desc + ")V")) {
                MethodNode implementation = owner.methods.stream().filter(m -> m.name.equals(setter.name) && m.desc.equals(setter.desc)).findFirst().orElse(null);
                if (implementation == null) continue;
                List<AbstractInsnNode> set = real(implementation);
                if (set.size() != 4 || !(set.get(0) instanceof VarInsnNode selfLoad) || selfLoad.getOpcode() != Opcodes.ALOAD || selfLoad.var != 0
                        || !(set.get(1) instanceof VarInsnNode input) || input.var != 1 || input.getOpcode() != Type.getType(field.desc).getOpcode(Opcodes.ILOAD)
                        || !(set.get(2) instanceof FieldInsnNode write) || write.getOpcode() != Opcodes.PUTFIELD
                        || !write.owner.equals(field.owner) || !write.name.equals(field.name) || !write.desc.equals(field.desc)
                        || set.get(3).getOpcode() != Opcodes.RETURN) continue;
                if (slot >= 0 || !(previous(setter) instanceof VarInsnNode argument) || argument.getOpcode() != input.getOpcode()
                        || !(previous(argument) instanceof VarInsnNode receiver) || receiver.var != 0 || receiver.getOpcode() != Opcodes.ALOAD) return null;
                slot = argument.var;
            }
            int local = 1; Type[] args = Type.getArgumentTypes(constructor.desc);
            for (int p = 0; p < args.length; p++) {
                if (local == slot) return value(constructorFrame.getStack(constructorFrame.getStackSize() - args.length + p));
                local += args[p].getSize();
            }
            return null;
        }
        SourceValue birth(SourceValue source) {
            if (source == null || source.insns.size() != 1) return null;
            AbstractInsnNode origin = source.insns.iterator().next();
            if (origin instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST) {
                Frame<SourceValue> frame = frames[method.instructions.indexOf(origin)];
                return frame == null || frame.getStackSize() == 0 ? null : birth(frame.getStack(frame.getStackSize() - 1));
            }
            // The platform event-bus API returns the submitted event after listeners have mutated it. The event
            // dispatch remains in place; this projection identifies only its original camera-angle operands.
            if (origin instanceof MethodInsnNode call && call.owner.equals("net/neoforged/bus/api/IEventBus") && call.name.equals("post")
                    && call.desc.equals("(Lnet/neoforged/bus/api/Event;)Lnet/neoforged/bus/api/Event;")) {
                Frame<SourceValue> frame = frames[method.instructions.indexOf(origin)];
                return frame == null || frame.getStackSize() == 0 ? null : birth(frame.getStack(frame.getStackSize() - 1));
            }
            return source;
        }
        /**
         * The conditions that decide whether {@code call} is reached. A loop-carried value's guard can depend on the value
         * itself (the condition compares a phi whose alternatives are guarded by that same condition): re-entering an
         * origin whose guards are already being described, or nesting deeper than {@link #MAX_GUARD_DEPTH}, answers null
         * — unprovable — instead of recursing until the stack overflows.
         */
        List<String> guards(AbstractInsnNode call) {
            if (guardCache.containsKey(call)) return guardCache.get(call);
            if (guarding.size() >= MAX_GUARD_DEPTH || !guarding.add(call)) return null;
            List<String> guards = new ArrayList<>();
            try {
                for (var instruction : method.instructions) if (instruction instanceof JumpInsnNode branch && branch.getOpcode() != Opcodes.GOTO
                        && branch.getOpcode() != Opcodes.JSR) {
                    boolean taken = reaches(branch.label, call), other = reaches(branch.getNext(), call);
                    if (taken == other) continue;
                    String operands = inputs(branch, branch.getOpcode() >= Opcodes.IF_ICMPEQ && branch.getOpcode() <= Opcodes.IF_ACMPNE ? 2 : 1);
                    if (operands == null) { guards = null; break; }
                    guards.add(branch.getOpcode() + ":" + taken + ":" + operands);
                }
            } finally { guarding.remove(call); }
            // Only a description reached outside any enclosing cycle is final; one cut short by an enclosing guard is not cached.
            if (guards != null || guarding.isEmpty()) guardCache.put(call, guards);
            return guards;
        }
        boolean reaches(AbstractInsnNode start, AbstractInsnNode target) {
            List<AbstractInsnNode> pending = new ArrayList<>(); pending.add(start);
            Set<AbstractInsnNode> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            while (!pending.isEmpty()) {
                AbstractInsnNode instruction = pending.removeLast(); if (instruction == null || !seen.add(instruction)) continue;
                if (instruction == target) return true;
                int opcode = instruction.getOpcode();
                if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN || opcode == Opcodes.ATHROW) continue;
                if (instruction instanceof JumpInsnNode branch) {
                    pending.add(branch.label); if (opcode != Opcodes.GOTO) pending.add(instruction.getNext());
                } else if (instruction instanceof LookupSwitchInsnNode table) { pending.add(table.dflt); pending.addAll(table.labels); }
                else if (instruction instanceof TableSwitchInsnNode table) { pending.add(table.dflt); pending.addAll(table.labels); }
                else pending.add(instruction.getNext());
            }
            return false;
        }
        String continuation(MethodInsnNode anchor) {
            List<String> steps = new ArrayList<>();
            for (AbstractInsnNode next = anchor.getNext(); next != null; next = next.getNext()) {
                if (next.getOpcode() < 0) continue;
                if (next instanceof MethodInsnNode call) {
                    String operation = call(call);
                    return operation == null ? null : steps + "/" + operation;
                }
                if (next instanceof JumpInsnNode branch) {
                    if (branch.getOpcode() == Opcodes.GOTO || branch.getOpcode() == Opcodes.JSR) return null;
                    String condition = inputs(branch, branch.getOpcode() >= Opcodes.IF_ICMPEQ && branch.getOpcode() <= Opcodes.IF_ACMPNE ? 2 : 1);
                    String destination = destination(branch.label);
                    if (condition == null || destination == null) return null;
                    steps.add("branch:" + branch.getOpcode() + condition + "to:" + destination);
                } else if (next instanceof VarInsnNode variable && variable.getOpcode() >= Opcodes.ILOAD && variable.getOpcode() <= Opcodes.ALOAD) {
                    Frame<SourceValue> frame = frames[method.instructions.indexOf(next)];
                    String source = frame == null ? null : value(frame.getLocal(variable.var));
                    if (source == null) return null;
                    steps.add("load:" + source);
                } else if (next instanceof InsnNode && next.getOpcode() == Opcodes.NOP) continue;
                else return null;
            }
            return null;
        }
        /** A kept branch must still skip to the same terminal/next operation, not merely use the same opcode. */
        String destination(LabelNode label) {
            List<String> operations = new ArrayList<>();
            for (AbstractInsnNode instruction = label.getNext(); instruction != null; instruction = instruction.getNext()) {
                if (instruction.getOpcode() < 0) continue;
                if (instruction instanceof MethodInsnNode call) {
                    String operation = call(call);
                    return operation == null ? null : operations + "/" + operation;
                }
                if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC)
                    operations.add("field:" + field.owner + "." + field.name + field.desc);
                else if (instruction instanceof VarInsnNode variable && variable.getOpcode() >= Opcodes.ILOAD && variable.getOpcode() <= Opcodes.ALOAD) {
                    Frame<SourceValue> frame = frames[method.instructions.indexOf(instruction)];
                    String source = frame == null ? null : value(frame.getLocal(variable.var));
                    if (source == null) return null;
                    operations.add("load:" + source);
                } else if (instruction.getOpcode() >= Opcodes.IRETURN && instruction.getOpcode() <= Opcodes.RETURN)
                    return operations + "/return:" + instruction.getOpcode();
                else if (instruction.getOpcode() == Opcodes.NOP) continue;
                else return null;
            }
            return null;
        }
    }
    static String member(MethodInsnNode call) { return "L" + call.owner + ";" + call.name + call.desc; }
    private static List<AbstractInsnNode> real(MethodNode method) { return java.util.Arrays.stream(method.instructions.toArray()).filter(i -> i.getOpcode() >= 0).toList(); }
    private static AbstractInsnNode previous(AbstractInsnNode instruction) { do { instruction = instruction.getPrevious(); } while (instruction != null && instruction.getOpcode() < 0); return instruction; }
}
