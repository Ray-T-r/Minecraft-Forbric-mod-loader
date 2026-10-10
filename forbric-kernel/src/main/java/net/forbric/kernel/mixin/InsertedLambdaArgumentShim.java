/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.*;
import java.util.function.Function;
import net.forbric.kernel.transform.DuplicateLambdaPruneInjector;
import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Restores explicit lambda selectors only when the pruner proved their old body is dead.
 * The old parameter sequence must have exactly one order-preserving embedding in the live one.
 * Unlike a type-only map this preserves repeated resource handles, while refusing ambiguous insertions.
 */
public final class InsertedLambdaArgumentShim {
    static final String PROPERTY = "forbric.insertedLambdaArguments";
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String CALLBACK = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
    private static final String RETURNABLE = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";
    private InsertedLambdaArgumentShim() {}

    public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
        return adapt(mixin, targets, mixin == null ? null : natives(mixin));
    }

    /**
     * {@link #adapt(ClassNode, Function)}, reading a selector that does not spell the dropped lambda's descriptor in
     * {@code natives}' class (the class the mod was compiled against, by internal name; null, or answering null: none).
     */
    public static int adapt(ClassNode mixin, Function<String, ClassNode> targets, Function<String, ClassNode> natives) {
        if (mixin == null || targets == null || "off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"))) return 0;
        List<String> owners = MixinOverloadPin.targetsOf(mixin);
        if (owners.size() != 1) return 0;
        ClassNode target = targets.apply(owners.getFirst());
        if (target == null) return 0;
        List<MethodNode> added = new ArrayList<>();
        for (MethodNode handler : List.copyOf(mixin.methods)) {
            Plan plan = plan(handler, target, natives);
            if (plan == null) continue;
            AnnotationNode inject = plan.inject(); MethodNode method = plan.live(); String name = method.name;
            Type[] oldArgs = plan.oldArgs(), newArgs = plan.newArgs(), captured = plan.captured();
            Type callback = plan.callback(); int[] mapping = plan.mapping();
            String shimName = "forbric$expanded$" + handler.name;
            if (mixin.methods.stream().anyMatch(m -> m.name.equals(shimName))) continue;
            Type[] shimArgs = Arrays.copyOf(newArgs, newArgs.length + 1 + captured.length); shimArgs[newArgs.length] = callback;
            System.arraycopy(captured, 0, shimArgs, newArgs.length + 1, captured.length);
            boolean isStatic = (handler.access & Opcodes.ACC_STATIC) != 0;
            MethodNode shim = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC | (isStatic ? Opcodes.ACC_STATIC : 0),
                    shimName, Type.getMethodDescriptor(Type.VOID_TYPE, shimArgs), null, null);
            int slot = isStatic ? 0 : 1, stack = isStatic ? 0 : 1;
            int[] slots = new int[newArgs.length];
            for (int i = 0; i < newArgs.length; i++) { slots[i] = slot; slot += newArgs[i].getSize(); }
            if (!isStatic) shim.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            for (int i = 0; i < mapping.length; i++) {
                shim.instructions.add(new VarInsnNode(oldArgs[i].getOpcode(Opcodes.ILOAD), slots[mapping[i]]));
                stack += oldArgs[i].getSize();
            }
            shim.instructions.add(new VarInsnNode(Opcodes.ALOAD, slot));
            int next = slot + 1; stack += 1;
            for (Type local : captured) {
                shim.instructions.add(new VarInsnNode(local.getOpcode(Opcodes.ILOAD), next));
                next += local.getSize(); stack += local.getSize();
            }
            shim.instructions.add(MixinHandlerShim.callOwn(mixin, isStatic, handler.name, handler.desc));
            shim.instructions.add(new InsnNode(Opcodes.RETURN));
            shim.maxStack = stack; shim.maxLocals = next;
            // Keep the original helper's name so internal calls (including recursion) stay intact.
            if (handler.visibleAnnotations != null) handler.visibleAnnotations.remove(inject);
            if (handler.invisibleAnnotations != null) handler.invisibleAnnotations.remove(inject);
            if (handler.visibleAnnotations == null) handler.visibleAnnotations = new ArrayList<>();
            handler.visibleAnnotations.add(new AnnotationNode("Lorg/spongepowered/asm/mixin/Unique;"));
            for (int i = 0; i < inject.values.size(); i += 2)
                if (inject.values.get(i).equals("method")) inject.values.set(i + 1, new ArrayList<>(List.of(name + method.desc)));
            shim.visibleAnnotations = new ArrayList<>(List.of(inject)); added.add(shim);
            ForbricLog.info("[Forbric/Mixin] %s.%s forwards the uniquely aligned original arguments to the retained %s%s",
                    mixin.name, handler.name, name, method.desc);
        }
        mixin.methods.addAll(added); return added.size();
    }

    /** Where one handler goes and how its arguments line up; {@code null} when the shim declines it. */
    record Plan(AnnotationNode inject, MethodNode live, Type[] oldArgs, Type[] newArgs, Type callback, Type[] captured,
            int[] mapping) {
    }

    /**
     * The live lambda a handler's pruned selector moves to — the same decision {@link #adapt} acts on, so
     * {@link MixinFit} can judge the injector where it will land instead of calling it unfit and removing it before
     * the shim ever runs. {@code target} must carry code, and its local variable table when the handler captures.
     */
    public static MethodNode destination(MethodNode handler, ClassNode target) {
        return destination(null, handler, target);
    }

    /**
     * {@link #destination(MethodNode, ClassNode)} for a handler of {@code mixin}, whose selector {@link #adapt} reads in
     * the class the mod was compiled against where it does not spell the lambda's descriptor.
     */
    public static MethodNode destination(ClassNode mixin, MethodNode handler, ClassNode target) {
        if (handler == null || target == null || "off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"))) return null;
        Plan plan = plan(handler, target, mixin == null ? null : natives(mixin));
        return plan == null ? null : plan.live();
    }

    /** The classes {@code mixin}'s mod was compiled against: its own platform's native game, where one is recorded. */
    private static Function<String, ClassNode> natives(ClassNode mixin) {
        return owner -> MixinBindings.nativeClass(mixin.name, owner);
    }

    static Plan plan(MethodNode handler, ClassNode target) {
        return plan(handler, target, null);
    }

    static Plan plan(MethodNode handler, ClassNode target, Function<String, ClassNode> natives) {
        AnnotationNode inject = MixinFit.injectorOf(handler);
        if (inject == null || !INJECT.equals(inject.desc) || (handler.access & Opcodes.ACC_PRIVATE) == 0
                || !Type.getReturnType(handler.desc).equals(Type.VOID_TYPE) || hasExtraContract(handler)) return null;
        if (MixinFit.value(inject, "slice") != null) return null;
        Object locals = MixinFit.value(inject, "locals");
        boolean capturing = locals != null && (!(locals instanceof String[] e) || !e[1].equals("NO_CAPTURE"));
        if (capturing && !(locals instanceof String[] mode && mode[1].startsWith("CAPTURE_"))) return null;
        String dropped = dropped(handler, target, natives);
        if (dropped == null) return null;
        int split = dropped.indexOf('(');
        String name = dropped.substring(0, split), oldDesc = dropped.substring(split);
        List<MethodNode> live = target.methods.stream().filter(m -> m.name.equals(name)).toList();
        if (live.size() != 1) return null;
        MethodNode method = live.getFirst();
        if (oldDesc.equals(method.desc) || !Type.getReturnType(oldDesc).equals(Type.getReturnType(method.desc))
                || ((method.access ^ handler.access) & Opcodes.ACC_STATIC) != 0 || !referenced(target, method)
                || !anchorExists(inject, method)) return null;
        Type[] oldArgs = Type.getArgumentTypes(oldDesc), newArgs = Type.getArgumentTypes(method.desc);
        Type callback = Type.getType(Type.getReturnType(oldDesc).equals(Type.VOID_TYPE) ? CALLBACK : RETURNABLE);
        Type[] handlerArgs = Type.getArgumentTypes(handler.desc);
        if (handlerArgs.length < oldArgs.length + 1) return null;
        Type[] expected = Arrays.copyOf(oldArgs, oldArgs.length + 1); expected[oldArgs.length] = callback;
        if (!Arrays.equals(expected, Arrays.copyOf(handlerArgs, expected.length))) return null;
        // A handler that captures locals takes them after the callback. They are forwarded untouched, and only when
        // the live lambda provably holds exactly those types in the slots after ITS arguments at the anchor: the
        // inserted argument shifts every local, and Mixin captures from the first slot past the arguments of the
        // method it actually injects into.
        Type[] captured = Arrays.copyOfRange(handlerArgs, expected.length, handlerArgs.length);
        if (capturing != (captured.length > 0)) return null;
        if (capturing && !localsAtAnchor(inject, method, captured)) return null;
        int[] mapping = uniqueEmbedding(oldArgs, newArgs);
        if (mapping == null) return null;
        return new Plan(inject, method, oldArgs, newArgs, callback, captured, mapping);
    }

    /**
     * The lambda ({@code name + desc}) the handler's selectors name as Mixin reads them, where it is one the pruner
     * dropped from {@code target}: spelled with its descriptor — whitespace, an owner prefix (the target's) or a dotted
     * owner, one spelling or several of the same member — or bound by a bare name in the class the mod was compiled
     * against. Null for anything else: another owner, a pattern, a member the pruner did not drop.
     */
    private static String dropped(MethodNode handler, ClassNode target, Function<String, ClassNode> natives) {
        String member = MixinTargetSelectors.nativeMember(handler, null, target.name);
        if (member == null && natives != null) {
            // A bare name binds the live lambda in the merged class: only where the pruner dropped a lambda of the name
            // it reaches there is the class the mod was compiled against worth reading.
            List<MixinTargetSelectors.Reach> reach = MixinTargetSelectors.reach(handler, target);
            boolean pruned = reach != null && !reach.isEmpty() && reach.stream().allMatch(r -> r.matched().stream()
                    .anyMatch(m -> !DuplicateLambdaPruneInjector.droppedDescriptors(target.name, m.name).isEmpty()));
            if (pruned) member = MixinTargetSelectors.nativeMember(handler, natives.apply(target.name), target.name);
        }
        if (member == null || !member.startsWith("lambda$")) return null;
        int split = member.indexOf('(');
        return split > 0 && DuplicateLambdaPruneInjector.droppedDescriptors(target.name, member.substring(0, split))
                .contains(member.substring(split)) ? member : null;
    }

    static int[] uniqueEmbedding(Type[] oldArgs, Type[] newArgs) {
        if (newArgs.length <= oldArgs.length) return null;
        int[] first = new int[oldArgs.length], last = new int[oldArgs.length];
        int cursor = 0;
        for (int i = 0; i < oldArgs.length; i++) {
            while (cursor < newArgs.length && !oldArgs[i].equals(newArgs[cursor])) cursor++;
            if (cursor == newArgs.length) return null; first[i] = cursor++;
        }
        cursor = newArgs.length - 1;
        for (int i = oldArgs.length - 1; i >= 0; i--) {
            while (cursor >= 0 && !oldArgs[i].equals(newArgs[cursor])) cursor--;
            if (cursor < 0) return null; last[i] = cursor--;
        }
        return Arrays.equals(first, last) ? first : null;
    }

    private static boolean hasExtraContract(MethodNode handler) {
        for (var list : Arrays.asList(handler.visibleAnnotations, handler.invisibleAnnotations))
            if (list != null) for (var a : list) if (!a.desc.equals(INJECT)) return true;
        for (var lists : Arrays.asList(handler.visibleParameterAnnotations, handler.invisibleParameterAnnotations))
            if (lists != null) for (var list : lists) if (list != null && !list.isEmpty()) return true;
        return false;
    }

    private static boolean referenced(ClassNode owner, MethodNode target) {
        for (MethodNode method : owner.methods) if (method != target) for (var i : method.instructions) {
            if (i instanceof MethodInsnNode c && c.owner.equals(owner.name) && c.name.equals(target.name) && c.desc.equals(target.desc)) return true;
            if (i instanceof InvokeDynamicInsnNode d) for (Object a : d.bsmArgs)
                if (a instanceof Handle h && h.getOwner().equals(owner.name) && h.getName().equals(target.name) && h.getDesc().equals(target.desc)) return true;
        }
        return false;
    }

    /**
     * Whether the live lambda's local variable table puts exactly {@code captured}, in order, in the slots right after
     * its arguments at every instruction the anchor names. Without a table there is no proof, and the handler is
     * left alone (Mixin itself reads the table to decide what it captures).
     */
    static boolean localsAtAnchor(AnnotationNode inject, MethodNode target, Type[] captured) {
        if (target.localVariables == null || target.localVariables.isEmpty()) return false;
        Object member = MixinFit.value(MixinFit.atNodes(inject).getFirst(), "target");
        return member instanceof String s && localsAtCall(s, target, captured);
    }

    /**
     * {@link #localsAtAnchor} at every call {@code s} selects in {@code target}, as Mixin matches an {@code @At(INVOKE)}
     * target ({@link MixinCallbackShape#selected}: its name, and its owner and descriptor wherever it gives them, however
     * it spells them), whatever the injector says — MixinRetarget asks it about the call an anchor is about to be moved to.
     */
    static boolean localsAtCall(String s, MethodNode target, Type[] captured) {
        if (target.localVariables == null || target.localVariables.isEmpty()) return false;
        int first = (target.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        for (Type arg : Type.getArgumentTypes(target.desc)) first += arg.getSize();
        boolean any = false;
        for (AbstractInsnNode insn : calls(s, target)) {
            any = true;
            int index = target.instructions.indexOf(insn), slot = first;
            for (Type local : captured) {
                LocalVariableNode live = null;
                for (LocalVariableNode variable : target.localVariables) {
                    if (variable.index == slot && target.instructions.indexOf(variable.start) <= index
                            && index < target.instructions.indexOf(variable.end)) live = variable;
                }
                if (live == null || !live.desc.equals(local.getDescriptor())) return false;
                slot += local.getSize();
            }
        }
        return any;
    }

    private static boolean anchorExists(AnnotationNode inject, MethodNode target) {
        List<AnnotationNode> ats = MixinFit.atNodes(inject); if (ats.size() != 1) return false;
        AnnotationNode at = ats.getFirst();
        if (!"INVOKE".equals(MixinFit.value(at, "value")) || MixinFit.value(at, "args") != null
                || MixinFit.value(at, "slice") != null) return false;
        Object shift = MixinFit.value(at, "shift");
        if (shift != null && (!(shift instanceof String[] e) || !Set.of("NONE", "BEFORE", "AFTER").contains(e[1]))) return false;
        Object member = MixinFit.value(at, "target"); if (!(member instanceof String s)) return false;
        int hits = calls(s, target).size();
        Object ordinal = MixinFit.value(at, "ordinal");
        return ordinal == null ? hits > 0 : ordinal instanceof Integer n && (n == -1 ? hits > 0 : n >= 0 && n < hits);
    }

    /** The calls of {@code method} an {@code @At(INVOKE)} target {@code s} selects, before any ordinal picks among them. */
    private static List<MethodInsnNode> calls(String s, MethodNode method) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode selected : MixinCallbackShape.selected(s, method))
            if (selected instanceof MethodInsnNode call) calls.add(call);
        return calls;
    }
}
