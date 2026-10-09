/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.*;
import java.util.function.Function;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

import net.forbric.kernel.transform.VanillaEarlyReturns;

/**
 * A TAIL handler that captures locals the merged tail no longer declares is moved to the end of the one path that still
 * holds them.
 *
 * <p>Vanilla's guard clause returned early, so a local its body declared stayed in scope to the last return: a Fabric
 * mod's {@code @Inject(at = @At("TAIL"), locals = CAPTURE_*)} captures it there. The carriers' recompiled bodies fold the
 * guard into an {@code if} block, whose scope ends at the join the tail sits on; the join's frame drops the local, because
 * the guard's path arrives without it. {@code VanillaEarlyReturns} sends the guard's path back to a return of its own, but
 * keeps the tail's frame as it was: the tail is still declared without the local. So Mixin finds nothing to capture
 * (CAPTURE_FAILSOFT skips the handler, CAPTURE_FAILHARD fails the mixin), or — where javac wrote only a "same" frame
 * there — types the slot from the earlier store and loads a slot the guard's path never wrote, and the class fails
 * verification. Item Glint Relight's capture of {@code GuiGraphicsExtractor.item}'s render state was the first seen;
 * the same fold is on thousands of merged methods.
 *
 * <p>Decided on the target alone, for any class and method: the handler is an {@code @Inject} at {@code TAIL} with a
 * locals capture, whose leading parameters are exactly the target method's arguments and its callback and whose other
 * parameters are all captured locals (no sugar); the tail's frame does not declare them (so Mixin cannot capture them
 * there); and exactly one edge into the tail carries them — by the data flow, every captured slot holds a value there,
 * and the method's table names those slots, first after the arguments, with the captured types, at the instruction that
 * leaves for the tail. That instruction must be a call (or its result's {@code pop}, or a {@code goto} right after one):
 * the point becomes {@code INVOKE} that call, {@code AFTER} (with the ordinal that singles it out), still inside the
 * locals' scope and still after everything the body did. A handler is moved only where it is the mixin's one TAIL capture
 * on that method. {@code -Dforbric.guiItemCaptureAnchor=off} leaves every TAIL as compiled.
 */
public final class MixinGuiItemCaptureAdapter {
    public static final String PROPERTY = "forbric.guiItemCaptureAnchor";
    private static final String CALLBACK = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
    private static final String RETURNABLE = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";
    private MixinGuiItemCaptureAdapter() { }

    public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
        if ("off".equalsIgnoreCase(System.getProperty(PROPERTY, "on")) || mixin == null || mixin.methods == null) return 0;
        List<String> owners = MixinFit.mixinTargets(mixin);
        if (owners.size() != 1) return 0;
        ClassNode target = targets.apply(owners.getFirst());
        if (target == null) return 0;
        Map<MethodNode, List<MethodNode>> captures = new IdentityHashMap<>();
        for (MethodNode handler : mixin.methods) {
            MethodNode bound = tailCapture(handler, target);
            if (bound != null) captures.computeIfAbsent(bound, b -> new ArrayList<>()).add(handler);
        }
        int changed = 0;
        for (var capture : captures.entrySet()) {
            if (capture.getValue().size() != 1) continue;
            MethodNode handler = capture.getValue().getFirst();
            List<Type> captured = MixinHandlerShape.of(handler).extras().stream().map(MixinHandlerShape.Extra::type).toList();
            Point point = bodyEnd(target, capture.getKey(), captured);
            if (point == null) continue;
            AnnotationNode at = MixinFit.atNodes(MixinFit.injectorOf(handler)).getFirst();
            at.values = new ArrayList<>(List.of("value", "INVOKE", "target", point.member(),
                    "shift", new String[] {"Lorg/spongepowered/asm/mixin/injection/At$Shift;", "AFTER"}));
            if (point.ordinal() >= 0) { at.values.add("ordinal"); at.values.add(point.ordinal()); }
            changed++;
        }
        return changed;
    }

    /** The one method of {@code target} a TAIL locals-capture handler binds, when its operands are that method's; else null. */
    private static MethodNode tailCapture(MethodNode handler, ClassNode target) {
        if (!MixinCallbackShape.kind(handler, "Inject") || !MixinCallbackShape.plainPoint(handler, "TAIL", null)) return null;
        AnnotationNode inject = MixinFit.injectorOf(handler);
        if (!(MixinFit.value(inject, "locals") instanceof String[] locals) || locals.length != 2 || !locals[1].startsWith("CAPTURE_")) return null;
        MethodNode bound = MixinTargetSelectors.one(handler, target);
        if (bound == null || bound.instructions == null || bound.instructions.size() == 0
                || ((bound.access ^ handler.access) & Opcodes.ACC_STATIC) != 0) return null;
        MixinHandlerShape shape = MixinHandlerShape.of(handler);
        List<Type> operands = new ArrayList<>(List.of(Type.getArgumentTypes(bound.desc)));
        operands.add(Type.getType(Type.getReturnType(bound.desc).equals(Type.VOID_TYPE) ? CALLBACK : RETURNABLE));
        if (shape == null || !shape.returns().equals(Type.VOID_TYPE) || !shape.operands().equals(operands) || shape.extras().isEmpty()
                || shape.extras().stream().anyMatch(extra -> extra.role() != MixinHandlerShape.Role.CAPTURED)) return null;
        return bound;
    }

    /** Where a TAIL capture moves: {@code INVOKE member}, {@code AFTER}, and the ordinal that singles the call out (-1: none). */
    record Point(String member, int ordinal) { }

    /**
     * The call that ends the one path into {@code method}'s tail on which the {@code captured} locals are held, when the
     * tail's own frame does not declare them; null when the tail can serve the capture or no single such path exists.
     */
    static Point bodyEnd(ClassNode owner, MethodNode method, List<Type> captured) {
        AbstractInsnNode tail = VanillaEarlyReturns.lastReturn(method);
        if (tail == null) return null;
        InsnList code = method.instructions;
        int at = code.indexOf(tail), runStart = at;
        FrameNode tailFrame = null;
        for (AbstractInsnNode insn = tail.getPrevious(); insn != null && insn.getOpcode() < 0; insn = insn.getPrevious()) {
            runStart = code.indexOf(insn);
            if (insn instanceof FrameNode frame && tailFrame == null) tailFrame = frame;
        }
        // Only a jump target carries a frame; a tail reached by falling through alone holds what the body left there.
        if (tailFrame == null) return null;
        int first = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        for (Type argument : Type.getArgumentTypes(method.desc)) first += argument.getSize();
        List<List<Object>> declared = VanillaEarlyReturns.stateAt(owner.name, method, tailFrame);
        if (declared == null) return null;
        if (holds(declared.get(0), first, captured)) return null;

        Set<Long> edges = new HashSet<>();
        Frame<BasicValue>[] frames;
        try {
            frames = new Analyzer<>(new BasicInterpreter()) {
                @Override protected void newControlFlowEdge(int from, int to) { edges.add(((long) from << 32) | to); }
            }.analyze(owner.name, method);
        } catch (AnalyzerException | RuntimeException unanalysable) {
            return null;
        }
        AbstractInsnNode leaving = null;
        for (long edge : edges) {
            int from = (int) (edge >>> 32), to = (int) edge;
            if (to < runStart || to > at || (from >= runStart && from <= at)) continue;
            if (frames[from] == null || code.get(from) instanceof VarInsnNode || code.get(from) instanceof IincInsnNode) return null;
            if (!carries(method, frames[from], from, first, captured)) continue;
            if (leaving != null) return null;    // two paths carry them: one point cannot stand for both
            leaving = code.get(from);
        }
        if (leaving == null) return null;
        AbstractInsnNode call = leaving.getOpcode() == Opcodes.GOTO ? previousReal(leaving) : leaving;
        if (call != null && (call.getOpcode() == Opcodes.POP || call.getOpcode() == Opcodes.POP2)) call = previousReal(call);
        if (!(call instanceof MethodInsnNode invoke) || invoke.name.equals("<init>")
                || frames[code.indexOf(call)] == null || !carries(method, frames[code.indexOf(call)], code.indexOf(call), first, captured)) return null;
        String member = "L" + invoke.owner + ";" + invoke.name + invoke.desc;
        int ordinal = -1, total = 0;
        for (AbstractInsnNode insn : code) {
            if (!(insn instanceof MethodInsnNode other) || !member.equals("L" + other.owner + ";" + other.name + other.desc)) continue;
            if (insn == call) ordinal = total;
            total++;
        }
        return new Point(member, total > 1 ? ordinal : -1);
    }

    /** Whether the frame's declared locals hold, first after the arguments, the captured types. */
    private static boolean holds(List<Object> locals, int first, List<Type> captured) {
        List<Object> slots = new ArrayList<>();
        for (Object entry : locals) {
            slots.add(entry);
            if (entry == Opcodes.LONG || entry == Opcodes.DOUBLE) slots.add(Opcodes.TOP);
        }
        int slot = first;
        for (Type type : captured) {
            while (slot < slots.size() && (slots.get(slot) == Opcodes.TOP || slots.get(slot) == null)) slot++;
            if (slot >= slots.size() || !sameFrameType(slots.get(slot), type)) return false;
            slot += type.getSize();
        }
        return true;
    }

    private static boolean sameFrameType(Object entry, Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> entry == Opcodes.INTEGER;
            case Type.FLOAT -> entry == Opcodes.FLOAT;
            case Type.LONG -> entry == Opcodes.LONG;
            case Type.DOUBLE -> entry == Opcodes.DOUBLE;
            case Type.ARRAY -> type.getDescriptor().equals(entry);
            default -> type.getInternalName().equals(entry);
        };
    }

    /**
     * Whether, at instruction {@code index}, the method's table names the locals after the arguments, in slot order, with
     * the captured types first, and the data flow holds a value in each of those slots.
     */
    private static boolean carries(MethodNode method, Frame<BasicValue> frame, int index, int first, List<Type> captured) {
        if (method.localVariables == null) return false;
        TreeMap<Integer, LocalVariableNode> scope = new TreeMap<>();
        for (LocalVariableNode local : method.localVariables) {
            if (local.index < first || method.instructions.indexOf(local.start) > index || index >= method.instructions.indexOf(local.end)) continue;
            if (scope.put(local.index, local) != null) return false;
        }
        Iterator<LocalVariableNode> inScope = scope.values().iterator();
        for (Type type : captured) {
            if (!inScope.hasNext()) return false;
            LocalVariableNode local = inScope.next();
            if (!local.desc.equals(type.getDescriptor()) || local.index >= frame.getLocals()) return false;
            BasicValue held = frame.getLocal(local.index);
            if (held == null || held == BasicValue.UNINITIALIZED_VALUE) return false;
        }
        return true;
    }

    private static AbstractInsnNode previousReal(AbstractInsnNode insn) {
        insn = insn.getPrevious();
        while (insn != null && insn.getOpcode() < 0) insn = insn.getPrevious();
        return insn;
    }
}
