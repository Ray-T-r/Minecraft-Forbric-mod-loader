/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * NeoForge's client extensions of a MinecraftForge mod's fluid type come from that type's MinecraftForge extensions.
 *
 * <p>A MinecraftForge mod's fluid has NeoForge's view of its own type ({@code KernelForgeFluidTypeViews}). NeoForge's
 * {@code IClientFluidTypeExtensions.of(FluidType)} looks extensions up in the map NeoForge's registration event fills,
 * which the view is in no entry of, so NeoForge's client code — the fog colour above all, which the merged client asks
 * NeoForge alone — got NeoForge's defaults for every MinecraftForge fluid. First thing in that lookup, the kernel is now
 * asked ({@code KernelForgeFluidClientViews.neoExtensions}); for a view it answers the view's extensions, for any other
 * type null, and NeoForge's lookup runs as before. On the reviewed shape only: a public static
 * {@code of(FluidType)} of NeoForge's interface returning its extensions type. Not made twice.
 * {@code -Dforbric.forgeFluidOwnType=off} (no views at all) leaves the lookup as merged.
 */
public final class ForgeFluidViewExtensionsInjector implements ClassTransformer {
	/** The views' own switch: without them there is nothing to answer. */
	public static final String PROPERTY = "forbric.forgeFluidOwnType";
	static final String EXTENSIONS = ForeignType.CLIENT_FLUID_TYPE_EXTENSIONS.binary(Ecosystem.NEOFORGE);
	static final String EXTENSIONS_INTERNAL = ForeignType.CLIENT_FLUID_TYPE_EXTENSIONS.internal(Ecosystem.NEOFORGE);
	static final String TYPE = ForeignType.FLUID_TYPE.internal(Ecosystem.NEOFORGE);
	static final String RUNTIME = "net/forbric/kernel/runtime/KernelForgeFluidClientViews";

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override public String name() { return "forbric-forge-fluid-view-extensions"; }

	@Override public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("no NeoForge views of MinecraftForge fluid types with -D" + PROPERTY + "=off");
		return AnchorSet.of(new AnchorSet.Anchor(EXTENSIONS, AnchorSet.Severity.REQUIRED,
				"a MinecraftForge fluid's fog keeps the sky's colour on the client"));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || bytes == null || bytes.length == 0 || !EXTENSIONS.equals(className)) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		if (repair(node) <= 0) return bytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		ForbricLog.info("[Forbric/Fluid] NeoForge's client extensions of a MinecraftForge fluid's type come from its MinecraftForge "
				+ "extensions — its fog colour on the client is the mod's");
		return writer.toByteArray();
	}

	static int repair(ClassNode node) {
		String desc = "(L" + TYPE + ";)L" + EXTENSIONS_INTERNAL + ";";
		MethodNode of = null;
		for (MethodNode m : node.methods) if (m.name.equals("of") && m.desc.equals(desc)) of = m;
		if (of == null || (of.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PUBLIC)) != (Opcodes.ACC_STATIC | Opcodes.ACC_PUBLIC)) {
			ForbricLog.warn("[Forbric/Fluid] left NeoForge's IClientFluidTypeExtensions.of(FluidType) as merged: it is not a public static "
					+ "lookup — a MinecraftForge fluid's fog keeps the sky's colour");
			return -1;
		}
		for (AbstractInsnNode insn : of.instructions) if (insn instanceof MethodInsnNode call && call.owner.equals(RUNTIME)) return 0;
		LabelNode neoForge = new LabelNode();
		InsnList ask = new InsnList();
		ask.add(new VarInsnNode(Opcodes.ALOAD, 0));
		ask.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "neoExtensions", "(Ljava/lang/Object;)Ljava/lang/Object;", false));
		ask.add(new InsnNode(Opcodes.DUP));
		ask.add(new JumpInsnNode(Opcodes.IFNULL, neoForge));
		ask.add(new TypeInsnNode(Opcodes.CHECKCAST, EXTENSIONS_INTERNAL));
		ask.add(new InsnNode(Opcodes.ARETURN));
		ask.add(neoForge);
		ask.add(new FrameNode(Opcodes.F_SAME1, 0, null, 1, new Object[] {"java/lang/Object"}));
		ask.add(new InsnNode(Opcodes.POP));
		of.instructions.insert(ask);
		return 1;
	}
}
