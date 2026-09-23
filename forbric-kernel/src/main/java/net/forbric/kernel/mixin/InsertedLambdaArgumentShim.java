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
        if (mixin == null || targets == null || "off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"))) return 0;
        List<String> owners = MixinOverloadPin.targetsOf(mixin);
        if (owners.size() != 1) return 0;
        ClassNode target = targets.apply(owners.getFirst());
        if (target == null) return 0;
        List<MethodNode> added = new ArrayList<>();
        for (MethodNode handler : List.copyOf(mixin.methods)) {
            AnnotationNode inject = MixinFit.injectorOf(handler);
            if (inject == null || !INJECT.equals(inject.desc) || (handler.access & Opcodes.ACC_PRIVATE) == 0
                    || !Type.getReturnType(handler.desc).equals(Type.VOID_TYPE) || hasExtraContract(handler)) continue;
            List<String> selectors = MixinFit.stringList(MixinFit.value(inject, "method"));
            if (selectors.size() != 1 || MixinFit.value(inject, "slice") != null) continue;
            Object locals = MixinFit.value(inject, "locals");
            if (locals != null && (!(locals instanceof String[] e) || !e[1].equals("NO_CAPTURE"))) continue;
            String selector = selectors.getFirst(); int split = selector.indexOf('(');
            if (!selector.startsWith("lambda$") || split < 0) continue;
            String name = selector.substring(0, split), oldDesc = selector.substring(split);
            if (!DuplicateLambdaPruneInjector.droppedDescriptors(target.name, name).contains(oldDesc)) continue;
            List<MethodNode> live = target.methods.stream().filter(m -> m.name.equals(name)).toList();
            if (live.size() != 1) continue;
            MethodNode method = live.getFirst();
            if (oldDesc.equals(method.desc) || !Type.getReturnType(oldDesc).equals(Type.getReturnType(method.desc))
                    || ((method.access ^ handler.access) & Opcodes.ACC_STATIC) != 0 || !referenced(target, method)
                    || !anchorExists(inject, method)) continue;
            Type[] oldArgs = Type.getArgumentTypes(oldDesc), newArgs = Type.getArgumentTypes(method.desc);
            Type callback = Type.getType(Type.getReturnType(oldDesc).equals(Type.VOID_TYPE) ? CALLBACK : RETURNABLE);
            Type[] expected = Arrays.copyOf(oldArgs, oldArgs.length + 1); expected[oldArgs.length] = callback;
            if (!Arrays.equals(expected, Type.getArgumentTypes(handler.desc))) continue;
            int[] mapping = uniqueEmbedding(oldArgs, newArgs);
            if (mapping == null) continue;
            String shimName = "forbric$expanded$" + handler.name;
            if (mixin.methods.stream().anyMatch(m -> m.name.equals(shimName))) continue;
            Type[] shimArgs = Arrays.copyOf(newArgs, newArgs.length + 1); shimArgs[newArgs.length] = callback;
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
            shim.instructions.add(new MethodInsnNode(isStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKESPECIAL,
                    mixin.name, handler.name, handler.desc, false));
            shim.instructions.add(new InsnNode(Opcodes.RETURN));
            shim.maxStack = stack + 1; shim.maxLocals = slot + 1;
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

    private static boolean anchorExists(AnnotationNode inject, MethodNode target) {
        List<AnnotationNode> ats = MixinFit.atNodes(inject); if (ats.size() != 1) return false;
        AnnotationNode at = ats.getFirst();
        if (!"INVOKE".equals(MixinFit.value(at, "value")) || MixinFit.value(at, "args") != null
                || MixinFit.value(at, "slice") != null) return false;
        Object shift = MixinFit.value(at, "shift");
        if (shift != null && (!(shift instanceof String[] e) || !Set.of("NONE", "BEFORE", "AFTER").contains(e[1]))) return false;
        Object member = MixinFit.value(at, "target"); if (!(member instanceof String s)) return false;
        int hits = 0;
        for (var i : target.instructions) if (i instanceof MethodInsnNode c && s.equals("L" + c.owner + ";" + c.name + c.desc)) hits++;
        Object ordinal = MixinFit.value(at, "ordinal");
        return ordinal == null ? hits > 0 : ordinal instanceof Integer n && (n == -1 ? hits > 0 : n >= 0 && n < hits);
    }
}
