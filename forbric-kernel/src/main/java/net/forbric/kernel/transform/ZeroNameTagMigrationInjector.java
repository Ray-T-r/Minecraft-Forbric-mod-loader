/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.forbric.kernel.util.ByteScan;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/**
 * Migrates the removed name-tag attribute only where data flow proves the read's sole effect is suppressing tags.
 *
 * <p>NeoForge 26.2.0.30 removed {@code NeoForgeMod.NAMETAG_DISTANCE} in favour of vanilla's
 * {@code Attributes.NAME_TAG_DISTANCE} (NeoForge PR #3333), so a mod built against an earlier 26.2 build dies with
 * {@code NoSuchFieldError} on its first read. The two attributes agree on one value only: zero hides the tag under
 * both, while nonzero (crouching) distances differ. A read therefore moves to the vanilla holder only when every
 * path its value takes ends in {@code setBaseValue} with a constant zero.
 *
 * <p>The proof follows values, not instruction order. The holder may sit in a local and reach any lookup from a holder
 * to its instance, {@code (Holder)AttributeInstance}: the map's {@code getInstance}, an entity's
 * {@code getAttribute}, a subclass calling the inherited one. The instance may be kept in a local, duplicated, cast,
 * and null-checked the way javac ({@code IFNULL}), Kotlin ({@code ?.}, {@code !!}) or {@code Objects.requireNonNull}
 * write it. Any other use of either value (reading the value, adding a modifier, storing it in a field, returning
 * it, passing it to anything else) leaves the class untouched, as does a zero that is not a constant.
 *
 * <p>Within 26.2 this is the only attribute holder a platform removed: NeoForge 26.2.0.8-beta's NeoForgeMod declares
 * SWIM_SPEED, NAMETAG_DISTANCE and CREATIVE_FLIGHT, 26.2.0.88's keeps SWIM_SPEED and CREATIVE_FLIGHT (and adds
 * GLIDING_FLIGHT), and MinecraftForge's ForgeMod still declares its own NAMETAG_DISTANCE.
 */
public final class ZeroNameTagMigrationInjector implements ClassTransformer {
    public static final String PROPERTY = "forbric.zeroNameTagMigration";
    public static final String NATIVE = "net/neoforged/neoforge/common/NeoForgeMod";
    public static final String ATTRIBUTES = "net/minecraft/world/entity/ai/attributes/Attributes";
    public static final String HOLDER = "Lnet/minecraft/core/Holder;";
    private static final String LEGACY = "NAMETAG_DISTANCE", REPLACEMENT = "NAME_TAG_DISTANCE";
    private static final String INSTANCE = "net/minecraft/world/entity/ai/attributes/AttributeInstance";
    /** A lookup from an attribute holder to that attribute's instance, whoever declares it. */
    private static final String LOOKUP = "(" + HOLDER + ")L" + INSTANCE + ";";
    private static final byte[] LEGACY_FIELD = ByteScan.needle(LEGACY);
    private final Function<String, ClassNode> declarations;

    public ZeroNameTagMigrationInjector(Function<String, ClassNode> declarations) { this.declarations = declarations; }
    @Override public AnchorSet anchors() { return AnchorSet.scanned("proved zero-distance uses of a removed platform attribute"); }
    @Override public byte[] transform(String name, byte[] bytes, TransformContext context) {
        if (bytes == null || "off".equalsIgnoreCase(net.forbric.kernel.util.ForbricSwitches.get(PROPERTY, "on"))) return bytes;
        if (!ByteScan.contains(bytes, LEGACY_FIELD)) return bytes;
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
        List<FieldInsnNode> reads = new ArrayList<>();
        for (MethodNode method : node.methods) for (var instruction : method.instructions)
            if (legacy(instruction)) reads.add((FieldInsnNode) instruction);
        if (reads.isEmpty()) return bytes;
        ClassNode old = declarations.apply(NATIVE), replacement = declarations.apply(ATTRIBUTES);
        if (old == null || replacement == null || old.fields.stream().anyMatch(f -> f.name.equals(LEGACY))) return bytes;
        if (replacement.fields.stream().noneMatch(f -> f.name.equals(REPLACEMENT) && f.desc.equals(HOLDER)
                && (f.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)) == (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC))) return bytes;
        for (MethodNode method : node.methods) if (java.util.stream.StreamSupport.stream(method.instructions.spliterator(), false).anyMatch(ZeroNameTagMigrationInjector::legacy)
                && !zeroOnly(node.name, method)) return bytes;
        for (FieldInsnNode field : reads) { field.owner = ATTRIBUTES; field.name = REPLACEMENT; }
        ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray();
    }

    private static boolean legacy(AbstractInsnNode instruction) {
        return instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                && field.owner.equals(NATIVE) && field.name.equals(LEGACY) && field.desc.equals(HOLDER);
    }

    /**
     * Source analysis that also records, for every instruction it reaches, the producers of each operand that
     * instruction consumes. Copies, casts and {@code Objects.requireNonNull} keep their producer, so a value is
     * followed through locals, {@code DUP}s and null checks to the instruction that actually uses it.
     */
    private static final class Flow extends SourceInterpreter {
        final Map<AbstractInsnNode, List<Set<AbstractInsnNode>>> operands = new IdentityHashMap<>();
        Flow() { super(Opcodes.ASM9); }

        private void consume(AbstractInsnNode instruction, List<? extends SourceValue> values) {
            List<Set<AbstractInsnNode>> seen = operands.computeIfAbsent(instruction, ignored -> new ArrayList<>());
            for (int i = 0; i < values.size(); i++) {
                if (seen.size() == i) seen.add(Collections.newSetFromMap(new IdentityHashMap<>()));
                seen.get(i).addAll(values.get(i).insns);
            }
        }
        /**
         * A copy keeps its producers, so a value is followed through locals and DUPs. A value with none (a parameter,
         * {@code this}, a caught exception) gets its load as producer instead: merged with a constant 0 at a join, an
         * empty set would vanish and leave the 0 looking proved.
         */
        @Override public SourceValue copyOperation(AbstractInsnNode instruction, SourceValue value) {
            return value.insns.isEmpty() ? super.copyOperation(instruction, value) : value;
        }
        @Override public SourceValue unaryOperation(AbstractInsnNode instruction, SourceValue value) {
            consume(instruction, List.of(value));
            return instruction.getOpcode() == Opcodes.CHECKCAST ? value : super.unaryOperation(instruction, value);
        }
        @Override public SourceValue binaryOperation(AbstractInsnNode instruction, SourceValue first, SourceValue second) {
            consume(instruction, List.of(first, second));
            return super.binaryOperation(instruction, first, second);
        }
        @Override public SourceValue ternaryOperation(AbstractInsnNode instruction, SourceValue first, SourceValue second, SourceValue third) {
            consume(instruction, List.of(first, second, third));
            return super.ternaryOperation(instruction, first, second, third);
        }
        @Override public SourceValue naryOperation(AbstractInsnNode instruction, List<? extends SourceValue> values) {
            consume(instruction, values);
            return passesThrough(instruction) ? values.get(0) : super.naryOperation(instruction, values);
        }
        @Override public void returnOperation(AbstractInsnNode instruction, SourceValue value, SourceValue expected) {
            consume(instruction, List.of(value));
        }
    }

    /** {@code Objects.requireNonNull} hands back the reference it was given. */
    private static boolean passesThrough(AbstractInsnNode instruction) {
        return instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
                && call.owner.equals("java/util/Objects") && call.name.equals("requireNonNull")
                && call.desc.startsWith("(Ljava/lang/Object;") && call.desc.endsWith(")Ljava/lang/Object;");
    }

    /** Operand {@code index} of {@code instruction} only learns whether a reference is null (or casts it). */
    private static boolean nullCheck(AbstractInsnNode instruction, int index) {
        int opcode = instruction.getOpcode();
        if (opcode == Opcodes.IFNULL || opcode == Opcodes.IFNONNULL || opcode == Opcodes.CHECKCAST) return true;
        if (index != 0 || !(instruction instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC
                || !call.desc.startsWith("(Ljava/lang/Object;")) return false;
        // Kotlin's !! and its checked platform-type assignment; javac's explicit requireNonNull.
        return passesThrough(call) || call.owner.equals("kotlin/jvm/internal/Intrinsics") && call.desc.endsWith(")V")
                && (call.name.equals("checkNotNull") || call.name.equals("checkNotNullExpressionValue"));
    }

    /** Operand {@code index} of {@code instruction} is the holder argument of a holder-to-instance lookup. */
    private static boolean lookup(AbstractInsnNode instruction, int index, int arity) {
        return instruction instanceof MethodInsnNode call && call.desc.equals(LOOKUP) && index == arity - 1;
    }

    private static boolean setter(AbstractInsnNode instruction) {
        return instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                && call.owner.equals(INSTANCE) && call.name.equals("setBaseValue") && call.desc.equals("(D)V");
    }

    /** Every producer is the constant zero, written as a double or widened from an int, long or float zero. */
    private static boolean zero(Flow flow, Set<AbstractInsnNode> producers) {
        if (producers.isEmpty()) return false;
        for (AbstractInsnNode producer : producers) {
            boolean constant = switch (producer.getOpcode()) {
                case Opcodes.DCONST_0, Opcodes.FCONST_0, Opcodes.LCONST_0, Opcodes.ICONST_0 -> true;
                case Opcodes.BIPUSH, Opcodes.SIPUSH -> ((IntInsnNode) producer).operand == 0;
                case Opcodes.LDC -> ((LdcInsnNode) producer).cst instanceof Number number && number.doubleValue() == 0;
                case Opcodes.I2D, Opcodes.L2D, Opcodes.F2D -> {
                    List<Set<AbstractInsnNode>> widened = flow.operands.get(producer);
                    yield widened != null && zero(flow, widened.get(0));
                }
                default -> false;
            };
            if (!constant) return false;
        }
        return true;
    }

    private static Set<AbstractInsnNode> among(Set<AbstractInsnNode> producers, Set<AbstractInsnNode> wanted) {
        Set<AbstractInsnNode> found = Collections.newSetFromMap(new IdentityHashMap<>());
        for (AbstractInsnNode producer : producers) if (wanted.contains(producer)) found.add(producer);
        return found;
    }

    /**
     * Every reachable legacy read feeds only holder-to-instance lookups (through null checks), and every such lookup's
     * instance is only null-checked and handed to {@code setBaseValue} with a constant zero, at least once.
     */
    private static boolean zeroOnly(String owner, MethodNode method) {
        try {
            Flow flow = new Flow();
            Frame<SourceValue>[] frames = new Analyzer<>(flow).analyze(owner, method);
            Set<AbstractInsnNode> reads = Collections.newSetFromMap(new IdentityHashMap<>());
            for (int i = 0; i < method.instructions.size(); i++)
                if (frames[i] != null && legacy(method.instructions.get(i))) reads.add(method.instructions.get(i));

            Set<AbstractInsnNode> lookups = Collections.newSetFromMap(new IdentityHashMap<>());
            Set<AbstractInsnNode> looked = Collections.newSetFromMap(new IdentityHashMap<>());
            for (var use : flow.operands.entrySet()) {
                List<Set<AbstractInsnNode>> operands = use.getValue();
                for (int i = 0; i < operands.size(); i++) {
                    Set<AbstractInsnNode> holders = among(operands.get(i), reads);
                    if (holders.isEmpty() || nullCheck(use.getKey(), i)) continue;
                    if (!lookup(use.getKey(), i, operands.size())) return false;
                    lookups.add(use.getKey()); looked.addAll(holders);
                }
            }
            if (reads.isEmpty() || looked.size() != reads.size()) return false;

            Set<AbstractInsnNode> zeroed = Collections.newSetFromMap(new IdentityHashMap<>());
            for (var use : flow.operands.entrySet()) {
                List<Set<AbstractInsnNode>> operands = use.getValue();
                for (int i = 0; i < operands.size(); i++) {
                    Set<AbstractInsnNode> instances = among(operands.get(i), lookups);
                    if (instances.isEmpty() || nullCheck(use.getKey(), i)) continue;
                    if (!setter(use.getKey()) || i != 0 || !zero(flow, operands.get(1))) return false;
                    zeroed.addAll(instances);
                }
            }
            return zeroed.size() == lookups.size();
        } catch (AnalyzerException | RuntimeException invalid) { return false; }
    }
}
