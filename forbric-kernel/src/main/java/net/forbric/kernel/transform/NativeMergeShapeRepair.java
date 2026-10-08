/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.NativeGameReferences;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/** Repairs merge artifacts only after current hierarchy and indexed native definitions prove their origin. */
public final class NativeMergeShapeRepair {
    private final Function<String, ClassNode> classes;
    private final BiFunction<Ecosystem, String, ClassNode> originals;
    public NativeMergeShapeRepair(Function<String, ClassNode> classes,
            BiFunction<Ecosystem, String, ClassNode> originals) {
        this.classes = classes; this.originals = originals;
    }
    public static NativeMergeShapeRepair production(ClassNode owner, Function<String, byte[]> resources) {
        Map<String, ClassNode> cache = new HashMap<>(); cache.put(owner.name, owner);
        return new NativeMergeShapeRepair(name -> {
            if (cache.containsKey(name)) return cache.get(name);
            // Raw selected resources avoid recursively entering this repair through getPreMixinClassBytes.
            ClassNode node = null;
            if (resources != null) try {
                byte[] bytes = resources.apply(name + ".class");
                if (bytes != null) { ClassNode parsed = new ClassNode(); new ClassReader(bytes).accept(parsed, 0); if (name.equals(parsed.name)) node = parsed; }
            } catch (RuntimeException unavailable) { }
            cache.put(name, node); return node;
        }, NativeGameReferences::reference);
    }
    public boolean dropSuperclassStubs(ClassNode owner) { return dropDelegates(owner, true); }
    public boolean dropDefaultStubs(ClassNode owner) { return dropDelegates(owner, false); }
    private boolean dropDelegates(ClassNode owner, boolean superclass) {
        List<MethodNode> remove = new ArrayList<>();
        for (MethodNode method : owner.methods) {
            MethodInsnNode call = delegate(method);
            if (call == null || !owner.interfaces.contains(call.owner) || !introduced(owner, method)) continue;
            Map<String, ClassNode> closure = new LinkedHashMap<>();
            if (!hierarchy(owner, closure, new HashSet<>())) continue;
            ClassNode called = closure.get(call.owner);
            if (called == null || (called.access & Opcodes.ACC_INTERFACE) == 0 || uniqueDefault(called, method) == null) continue;
            int inherited = superclassImplementation(owner, method);
            if (superclass ? inherited == 1 : inherited == 0 && sameDefault(owner, called, method)) remove.add(method);
        }
        owner.methods.removeAll(remove); return !remove.isEmpty();
    }
    /** Both original classes must be known, and neither may have declared this method intentionally. */
    private boolean introduced(ClassNode owner, MethodNode method) {
        for (Ecosystem family : List.of(Ecosystem.FORGE, Ecosystem.NEOFORGE)) {
            ClassNode nativeOwner = originals.apply(family, owner.name);
            if (nativeOwner == null || method(nativeOwner, method.name, method.desc) != null) return false;
        }
        return true;
    }
    /** 1: public concrete non-delegate; 0: absent in a complete chain; -1: blocked or unproved. */
    private int superclassImplementation(ClassNode owner, MethodNode target) {
        Set<String> seen = new HashSet<>();
        for (String name = owner.superName; name != null;) {
            if (!seen.add(name)) return -1;
            ClassNode parent = classes.apply(name); if (parent == null) return -1;
            MethodNode inherited = method(parent, target.name, target.desc);
            if (inherited != null) {
                if ((inherited.access & Opcodes.ACC_PRIVATE) != 0) { name = parent.superName; continue; }
                if ((inherited.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != Opcodes.ACC_PUBLIC
                        || inherited.instructions.size() == 0 || delegate(inherited) != null) return -1;
                return 1;
            }
            name = parent.superName;
        }
        return 0;
    }
    private boolean sameDefault(ClassNode owner, ClassNode called, MethodNode target) {
        String inherited = uniqueDefault(owner, target), selected = uniqueDefault(called, target);
        return inherited != null && inherited.equals(selected);
    }
    /** Complete interface closure, including abstract declarations that suppress an ancestor default. */
    private String uniqueDefault(ClassNode root, MethodNode target) {
        Map<String, ClassNode> closure = new LinkedHashMap<>();
        if (!hierarchy(root, closure, new HashSet<>())) return null;
        Map<String, MethodNode> declarations = new LinkedHashMap<>();
        for (ClassNode type : closure.values()) if ((type.access & Opcodes.ACC_INTERFACE) != 0) {
            MethodNode method = method(type, target.name, target.desc);
            if (method != null && (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) == 0) declarations.put(type.name, method);
        }
        List<String> maximal = new ArrayList<>();
        for (String candidate : declarations.keySet()) {
            boolean superseded = false;
            for (String other : declarations.keySet()) if (!other.equals(candidate)
                    && extendsInterface(closure.get(other), candidate, closure, new HashSet<>())) { superseded = true; break; }
            if (!superseded) maximal.add(candidate);
        }
        if (maximal.size() != 1) return null;
        MethodNode winner = declarations.get(maximal.getFirst());
        return (winner.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == Opcodes.ACC_PUBLIC
                && winner.instructions.size() > 0 ? maximal.getFirst() : null;
    }
    private static boolean extendsInterface(ClassNode child, String parent, Map<String, ClassNode> closure, Set<String> seen) {
        if (!seen.add(child.name)) return false;
        for (String next : child.interfaces) if (next.equals(parent) || extendsInterface(closure.get(next), parent, closure, seen)) return true;
        return false;
    }
    private boolean hierarchy(ClassNode owner, Map<String, ClassNode> result, Set<String> visiting) {
        if (result.containsKey(owner.name)) return true;
        if (!visiting.add(owner.name)) return false;
        if (owner.superName != null) {
            ClassNode parent = classes.apply(owner.superName);
            if (parent == null || !hierarchy(parent, result, visiting)) return false;
        }
        for (String name : owner.interfaces) {
            ClassNode parent = classes.apply(name);
            if (parent == null || !hierarchy(parent, result, visiting)) return false;
        }
        visiting.remove(owner.name); result.put(owner.name, owner); return true;
    }
    private static MethodInsnNode delegate(MethodNode method) {
        if ((method.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_SYNCHRONIZED)) != Opcodes.ACC_PUBLIC
                || !method.tryCatchBlocks.isEmpty()) return null;
        List<AbstractInsnNode> code = code(method); Type[] args = Type.getArgumentTypes(method.desc);
        if (code.size() != args.length + 3 || !(code.getFirst() instanceof VarInsnNode self) || self.getOpcode() != Opcodes.ALOAD || self.var != 0) return null;
        int slot = 1;
        for (int i = 0; i < args.length; i++) {
            if (!(code.get(i + 1) instanceof VarInsnNode load) || load.var != slot || load.getOpcode() != args[i].getOpcode(Opcodes.ILOAD)) return null;
            slot += args[i].getSize();
        }
        return code.get(args.length + 1) instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL && call.itf
                && call.name.equals(method.name) && call.desc.equals(method.desc)
                && code.getLast().getOpcode() == Type.getReturnType(method.desc).getOpcode(Opcodes.IRETURN) ? call : null;
    }

    private record SwitchMap(String enumeration, Map<Integer, List<String>> cases) { }
    public boolean inlineLostSwitchMaps(ClassNode owner) {
        int changed = 0;
        for (MethodNode method : owner.methods) for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof FieldInsnNode get) || get.getOpcode() != Opcodes.GETSTATIC || !get.desc.equals("[I")) continue;
            AbstractInsnNode load = next(get), ordinal = next(load), array = next(ordinal), branch = next(array);
            if (!(load instanceof VarInsnNode local) || local.getOpcode() != Opcodes.ALOAD
                    || !(ordinal instanceof MethodInsnNode call) || !ordinal(call, call.owner)
                    || array == null || array.getOpcode() != Opcodes.IALOAD
                    || !(branch instanceof LookupSwitchInsnNode || branch instanceof TableSwitchInsnNode)
                    || boundary(get, branch) || !emptyStack(owner, method, get)) continue;
            ClassNode holder = classes.apply(get.owner); Map<String, ClassNode> holderClosure = new LinkedHashMap<>();
            if (holder == null || !hierarchy(holder, holderClosure, new HashSet<>())) continue;
            if (holderClosure.values().stream().anyMatch(type -> type.fields.stream().anyMatch(field -> field.name.equals(get.name) && field.desc.equals(get.desc)))) continue;
            SwitchMap mapping = nativeMapping(owner, method, get, call.owner); if (mapping == null) continue;
            List<Integer> keys = new ArrayList<>(); List<LabelNode> labels = new ArrayList<>(); LabelNode fallback;
            if (branch instanceof LookupSwitchInsnNode lookup) { keys.addAll(lookup.keys); labels.addAll(lookup.labels); fallback = lookup.dflt; }
            else { TableSwitchInsnNode table = (TableSwitchInsnNode) branch; for (int i = table.min; i <= table.max; i++) keys.add(i); labels.addAll(table.labels); fallback = table.dflt; }
            if (keys.stream().anyMatch(key -> !mapping.cases.containsKey(key))) continue;
            InsnList comparison = new InsnList(); String enumeration = mapping.enumeration;
            // The original holder initializes the enum even for null input, then ordinal throws NPE.
            String first = mapping.cases.values().iterator().next().getFirst();
            comparison.add(new FieldInsnNode(Opcodes.GETSTATIC, enumeration, first, "L" + enumeration + ";")); comparison.add(new InsnNode(Opcodes.POP));
            comparison.add(new VarInsnNode(Opcodes.ALOAD, local.var));
            comparison.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, enumeration, "ordinal", "()I", false)); comparison.add(new InsnNode(Opcodes.POP));
            for (int i = 0; i < keys.size(); i++) for (String constant : mapping.cases.get(keys.get(i))) {
                comparison.add(new VarInsnNode(Opcodes.ALOAD, local.var));
                comparison.add(new FieldInsnNode(Opcodes.GETSTATIC, enumeration, constant, "L" + enumeration + ";"));
                comparison.add(new JumpInsnNode(Opcodes.IF_ACMPEQ, labels.get(i)));
            }
            comparison.add(new JumpInsnNode(Opcodes.GOTO, fallback)); method.instructions.insertBefore(get, comparison);
            for (AbstractInsnNode victim : List.of(get, load, ordinal, array, branch)) method.instructions.remove(victim);
            method.maxStack = Math.max(method.maxStack, 2); changed++;
        }
        return changed > 0;
    }
    private SwitchMap nativeMapping(ClassNode user, MethodNode consumer, FieldInsnNode get, String enumeration) {
        SwitchMap accepted = null;
        for (Ecosystem family : List.of(Ecosystem.FORGE, Ecosystem.NEOFORGE)) {
            ClassNode source = originals.apply(family, user.name); if (source == null) return null;
            MethodNode method = method(source, consumer.name, consumer.desc);
            if (method == null || code(method).stream().noneMatch(i -> i instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                    && field.owner.equals(get.owner) && field.name.equals(get.name) && field.desc.equals(get.desc))) continue;
            SwitchMap mapping = readMap(originals.apply(family, get.owner), get.name, family, enumeration);
            if (mapping == null || accepted != null && !accepted.equals(mapping)) return null;
            accepted = mapping;
        }
        return accepted;
    }
    /** The entire native initializer must be compiler map allocation/assignment plus NoSuchFieldError guards. */
    private SwitchMap readMap(ClassNode holder, String fieldName, Ecosystem family, String expectedEnum) {
        if (holder == null) return null;
        MethodNode init = method(holder, "<clinit>", "()V"); if (init == null) return null;
        Map<String, String> allocations = new HashMap<>(); Map<String, Map<String, Integer>> assignments = new HashMap<>();
        Set<AbstractInsnNode> used = Collections.newSetFromMap(new IdentityHashMap<>());
        List<AbstractInsnNode> code = code(init);
        for (int i = 0; i < code.size(); i++) {
            AbstractInsnNode at = code.get(i);
            if (at instanceof MethodInsnNode factory && factory.getOpcode() == Opcodes.INVOKESTATIC && factory.name.equals("values")
                    && factory.desc.equals("()[L" + factory.owner + ";") && i + 3 < code.size()
                    && code.get(i + 1).getOpcode() == Opcodes.ARRAYLENGTH && code.get(i + 2) instanceof IntInsnNode array
                    && array.getOpcode() == Opcodes.NEWARRAY && array.operand == Opcodes.T_INT
                    && code.get(i + 3) instanceof FieldInsnNode store && store.getOpcode() == Opcodes.PUTSTATIC
                    && store.owner.equals(holder.name) && store.desc.equals("[I") && mapField(holder, store.name)
                    && enumValues(originals.apply(family, factory.owner), factory) && allocations.putIfAbsent(store.name, factory.owner) == null) {
                used.addAll(code.subList(i, i + 4)); i += 3; continue;
            }
            if (at instanceof FieldInsnNode map && map.getOpcode() == Opcodes.GETSTATIC && map.owner.equals(holder.name) && map.desc.equals("[I")
                    && i + 4 < code.size() && code.get(i + 1) instanceof FieldInsnNode constant && constant.getOpcode() == Opcodes.GETSTATIC
                    && constant.owner.equals(allocations.get(map.name)) && code.get(i + 2) instanceof MethodInsnNode ordinal
                    && ordinal(ordinal, constant.owner) && integer(code.get(i + 3)) != null && integer(code.get(i + 3)) > 0
                    && code.get(i + 4).getOpcode() == Opcodes.IASTORE
                    && enumConstant(originals.apply(family, constant.owner), constant) && enumConstant(classes.apply(constant.owner), constant)) {
                Map<String, Integer> values = assignments.computeIfAbsent(map.name, key -> new LinkedHashMap<>());
                if (values.putIfAbsent(constant.name, integer(code.get(i + 3))) != null) return null;
                used.addAll(code.subList(i, i + 5)); i += 4;
            }
        }
        Set<AbstractInsnNode> handlers = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<AbstractInsnNode> jumps = Collections.newSetFromMap(new IdentityHashMap<>());
        for (TryCatchBlockNode guard : init.tryCatchBlocks) {
            if (!"java/lang/NoSuchFieldError".equals(guard.type)) return null;
            List<AbstractInsnNode> protectedCode = new ArrayList<>();
            for (AbstractInsnNode i = guard.start; i != null && i != guard.end; i = i.getNext()) if (i.getOpcode() >= 0) protectedCode.add(i);
            if (protectedCode.size() != 5 || !used.containsAll(protectedCode) || protectedCode.getLast().getOpcode() != Opcodes.IASTORE) return null;
            AbstractInsnNode handler = realAt(guard.handler);
            if (!(handler instanceof VarInsnNode variable && variable.getOpcode() == Opcodes.ASTORE) && (handler == null || handler.getOpcode() != Opcodes.POP)) return null;
            handlers.add(handler);
            AbstractInsnNode jump = realAt(guard.end), target = next(handler);
            if (jump instanceof JumpInsnNode branch && branch.getOpcode() == Opcodes.GOTO && realAt(branch.label) == target) jumps.add(jump);
        }
        for (AbstractInsnNode instruction : code) if (!used.contains(instruction) && !handlers.contains(instruction) && !jumps.contains(instruction)
                && !(instruction == code.getLast() && instruction.getOpcode() == Opcodes.RETURN)) return null;
        if (!expectedEnum.equals(allocations.get(fieldName))) return null;
        Map<String, Integer> values = assignments.get(fieldName); if (values == null || values.isEmpty()) return null;
        Map<Integer, List<String>> cases = new TreeMap<>();
        for (var value : values.entrySet()) cases.computeIfAbsent(value.getValue(), key -> new ArrayList<>()).add(value.getKey());
        cases.replaceAll((key, names) -> names.stream().sorted().toList());
        return new SwitchMap(expectedEnum, Map.copyOf(cases));
    }
    private static boolean enumType(ClassNode owner) { return owner != null && (owner.access & Opcodes.ACC_ENUM) != 0 && "java/lang/Enum".equals(owner.superName); }
    /** values() must itself be the compiler's pure array clone, rather than a patched side-effecting factory. */
    private static boolean enumValues(ClassNode owner, MethodInsnNode factory) {
        if (!enumType(owner)) return false;
        MethodNode values = method(owner, factory.name, factory.desc);
        if (values == null || (values.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PUBLIC)) != (Opcodes.ACC_STATIC | Opcodes.ACC_PUBLIC)
                || !values.tryCatchBlocks.isEmpty()) return false;
        List<AbstractInsnNode> body = code(values); String array = "[L" + owner.name + ";";
        return body.size() == 4 && body.getFirst() instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                && field.owner.equals(owner.name) && field.desc.equals(array)
                && owner.fields.stream().anyMatch(f -> f.name.equals(field.name) && f.desc.equals(array)
                    && (f.access & (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC)) == (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC))
                && body.get(1) instanceof MethodInsnNode clone && clone.getOpcode() == Opcodes.INVOKEVIRTUAL
                && clone.owner.equals(array) && clone.name.equals("clone") && clone.desc.equals("()Ljava/lang/Object;")
                && body.get(2) instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST && cast.desc.equals(array)
                && body.getLast().getOpcode() == Opcodes.ARETURN;
    }
    private static boolean enumConstant(ClassNode owner, FieldInsnNode field) {
        return enumType(owner) && field.desc.equals("L" + owner.name + ";") && owner.fields.stream().anyMatch(f -> f.name.equals(field.name) && f.desc.equals(field.desc)
                && (f.access & (Opcodes.ACC_ENUM | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)) == (Opcodes.ACC_ENUM | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL));
    }
    private static boolean mapField(ClassNode holder, String name) { return holder.fields.stream().anyMatch(f -> f.name.equals(name) && f.desc.equals("[I")
            && (f.access & (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC)) == (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC)); }
    private static boolean ordinal(MethodInsnNode call, String enumeration) { return call.getOpcode() == Opcodes.INVOKEVIRTUAL && !call.itf && call.owner.equals(enumeration) && call.name.equals("ordinal") && call.desc.equals("()I"); }
    private static Integer integer(AbstractInsnNode i) {
        if (i.getOpcode() >= Opcodes.ICONST_M1 && i.getOpcode() <= Opcodes.ICONST_5) return i.getOpcode() - Opcodes.ICONST_0;
        if (i instanceof IntInsnNode n && (n.getOpcode() == Opcodes.BIPUSH || n.getOpcode() == Opcodes.SIPUSH)) return n.operand;
        return i instanceof LdcInsnNode n && n.cst instanceof Integer k ? k : null;
    }
    private static boolean boundary(AbstractInsnNode start, AbstractInsnNode end) { for (AbstractInsnNode i = start.getNext(); i != end; i = i.getNext()) if (i == null || i instanceof LabelNode || i instanceof FrameNode) return true; return false; }
    private static boolean emptyStack(ClassNode owner, MethodNode method, AbstractInsnNode at) {
        try { Frame<BasicValue>[] frames = new Analyzer<>(new BasicInterpreter()).analyze(owner.name, method); Frame<BasicValue> frame = frames[method.instructions.indexOf(at)]; return frame != null && frame.getStackSize() == 0; }
        catch (AnalyzerException | RuntimeException invalid) { return false; }
    }
    private static MethodNode method(ClassNode owner, String name, String desc) { return owner.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElse(null); }
    private static List<AbstractInsnNode> code(MethodNode method) { List<AbstractInsnNode> result = new ArrayList<>(); for (AbstractInsnNode i : method.instructions) if (i.getOpcode() >= 0) result.add(i); return result; }
    private static AbstractInsnNode realAt(AbstractInsnNode i) { while (i != null && i.getOpcode() < 0) i = i.getNext(); return i; }
    private static AbstractInsnNode next(AbstractInsnNode i) { return i == null ? null : realAt(i.getNext()); }
}
