/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/**
 * Preserve vanilla's false result for an eye query on a tag that has no tracker, rather than NeoForge's throw.
 *
 * <p>Both {@code isEyeInFluid(TagKey)} bodies go through NeoForge's {@code getFluidTypeByTag}, which throws for a tag
 * with no fluid type; vanilla answers false for a tag nothing tracks, and Fabric mods ask that while rendering. Each body
 * gets a guard in front: a tag the kernel knows a type for ({@code KernelFluidTypes.hasTagType}), or one the entity's
 * interaction tracks, goes on to NeoForge's answer; any other answers false.
 *
 * <p>"Tracks" is asked of {@code EntityFluidInteraction} itself, through {@code forbric$tracksTag(TagKey)}, which this
 * adds to that class. On the merged base the interaction is MinecraftForge's, which keeps a tracker per tag and answers
 * {@code getTracker(TagKey)}, so the method returns {@code getTracker(tag) != null} — exactly the query the
 * {@code Entity} guard used to make inline. NeoForge's interaction keeps trackers per fluid type and has no
 * {@code getTracker}: there no tag is tracked and the method returns false, so only the kernel's types go on. The
 * {@code Entity} guard used to call {@code getTracker} directly, which on the genuine NeoForge base is a reference to
 * nothing — a {@code NoSuchMethodError} on the first untracked query. {@code Entity} is edited without seeing
 * {@code EntityFluidInteraction}, so the method goes into that class whenever it is transformed, whatever else stands
 * down there; the {@code Entity} edit can then never point at what the game lacks.
 */
public final class UntrackedFluidEyeQueryInjector implements ClassTransformer {
    public static final String PROPERTY = "forbric.untrackedFluidEyes";
    static final String ENTITY = "net.minecraft.world.entity.Entity";
    static final String INTERACTION = "net.minecraft.world.entity.EntityFluidInteraction";
    static final String OWNER = INTERACTION.replace('.', '/');
    static final String TAG = "Lnet/minecraft/tags/TagKey;";
    static final String DESC = "(" + TAG + ")Z";
    /** Whether the interaction keeps a tracker for a tag; added to {@code EntityFluidInteraction}, asked by {@code Entity}. */
    static final String TRACKS_TAG = "forbric$tracksTag";
    static final String GET_TRACKER_DESC = "(" + TAG + ")L" + OWNER + "$Tracker;";
    @Override public String name() { return "forbric-untracked-fluid-eyes"; }
    private static boolean enabled() { return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on")); }
    @Override public AnchorSet anchors() {
        if (!enabled()) return AnchorSet.scanned("untracked fluid eye queries left native by request");
        return AnchorSet.of(new AnchorSet.Anchor(ENTITY, AnchorSet.Severity.REQUIRED, "a Fabric mod's untracked fluid tag query throws while rendering"),
                new AnchorSet.Anchor(INTERACTION, AnchorSet.Severity.REQUIRED, "vanilla's untracked fluid tag query must answer false"));
    }
    @Override public byte[] transform(String name, byte[] bytes, TransformContext context) {
        if (!enabled() || bytes == null || (!ENTITY.equals(name) && !INTERACTION.equals(name))) return bytes;
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
        boolean interaction = INTERACTION.equals(name);
        boolean changed = interaction && addTracksTag(node);
        if (guardEyeQuery(node, interaction)) changed = true;
        if (!changed) return bytes;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray();
    }

    /**
     * {@code public boolean forbric$tracksTag(TagKey tag)}: {@code Objects.nonNull(getTracker(tag))} where the class
     * declares MinecraftForge's per-tag {@code getTracker}, else {@code false}. Added when absent.
     */
    static boolean addTracksTag(ClassNode node) {
        for (MethodNode m : node.methods) if (m.name.equals(TRACKS_TAG) && m.desc.equals(DESC)) return false;
        boolean perTag = node.methods.stream().anyMatch(m -> m.name.equals("getTracker") && m.desc.equals(GET_TRACKER_DESC)
                && (m.access & Opcodes.ACC_STATIC) == 0);
        MethodNode tracks = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, TRACKS_TAG, DESC, null, null);
        if (perTag) {
            tracks.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            tracks.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            tracks.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, OWNER, "getTracker", GET_TRACKER_DESC, false));
            tracks.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Objects", "nonNull", "(Ljava/lang/Object;)Z", false));
        } else {
            tracks.instructions.add(new InsnNode(Opcodes.ICONST_0));
        }
        tracks.instructions.add(new InsnNode(Opcodes.IRETURN));
        node.methods.add(tracks);
        return true;
    }

    /** The guard in front of {@code isEyeInFluid(TagKey)}, where it still goes through NeoForge's tag lookup. */
    private static boolean guardEyeQuery(ClassNode node, boolean interaction) {
        MethodNode method = node.methods.stream().filter(m -> m.name.equals("isEyeInFluid") && m.desc.equals(DESC)).findFirst().orElse(null);
        if (method == null) return false;
        boolean nativeTagLookup = false;
        for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call
                && call.owner.equals(OWNER) && call.name.equals("getFluidTypeByTag")) nativeTagLookup = true;
        if (!nativeTagLookup) return false;
        for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call && call.name.equals("hasTagType")) return false;
        LabelNode known = new LabelNode(); InsnList guard = new InsnList();
        guard.add(new VarInsnNode(Opcodes.ALOAD, 1));
        guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "net/forbric/kernel/runtime/KernelFluidTypes", "hasTagType", DESC, false));
        guard.add(new JumpInsnNode(Opcodes.IFNE, known));
        guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
        if (!interaction) {
            if (node.fields.stream().noneMatch(f -> f.name.equals("fluidInteraction") && f.desc.equals("L" + OWNER + ";"))) return false;
            guard.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "fluidInteraction", "L" + OWNER + ";"));
            guard.add(new VarInsnNode(Opcodes.ALOAD, 1));
            guard.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, OWNER, TRACKS_TAG, DESC, false));
            guard.add(new JumpInsnNode(Opcodes.IFNE, known));
        } else {
            if (node.fields.stream().noneMatch(f -> f.name.equals("trackerByFluid") && f.desc.equals("Ljava/util/Map;"))) return false;
            guard.add(new FieldInsnNode(Opcodes.GETFIELD, OWNER, "trackerByFluid", "Ljava/util/Map;"));
            guard.add(new VarInsnNode(Opcodes.ALOAD, 1));
            guard.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "containsKey", "(Ljava/lang/Object;)Z", true));
            guard.add(new JumpInsnNode(Opcodes.IFNE, known));
        }
        guard.add(new InsnNode(Opcodes.ICONST_0)); guard.add(new InsnNode(Opcodes.IRETURN));
        guard.add(known); guard.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        method.instructions.insert(guard);
        return true;
    }
}
