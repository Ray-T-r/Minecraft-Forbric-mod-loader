/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.List;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * The Ender Dragon's parts are NeoForge {@code PartEntity}s again, which is what the rest of the merged game expects.
 *
 * <p>The byte merge put {@code EnderDragonPart} under MinecraftForge's {@code PartEntity} (MergedBaseBuilder's
 * {@code PREFER_FORGE_STRUCTURAL}, to keep a Forge-typed {@code EnderDragon.getParts()} verifiable). Everything that
 * consumes parts kept NeoForge's body: the server's entity callbacks index {@code getParts()} typed to NeoForge's
 * {@code PartEntity}, {@code Level.getEntities} casts every part to it, and attacks, camera and chunk tracking test for
 * it. {@code EnderDragon} overrides only the Forge-typed {@code getParts()}, so NeoForge's answered its interface
 * default, null: adding a dragon to a world threw a NullPointerException in {@code ServerLevel}'s tracking callback,
 * and so did removing it, which left the server unable to stop. On a client the parts reached NeoForge's list as
 * Forge-typed objects, and the first entity lookup that met one threw a ClassCastException.
 *
 * <p>Three edits, each on the reviewed shape only:
 * <ul>
 *   <li>{@code EnderDragonPart} extends NeoForge's {@code PartEntity} (the two classes have the same constructor and
 *       members), so a part is what every NeoForge-typed consumer casts it to.</li>
 *   <li>{@code EnderDragon} gets NeoForge's {@code getParts()}, returning its parts as NeoForge's own does; the
 *       Forge-typed one returns an empty array — no Forge-typed part exists any more, and an empty array is what
 *       every Forge-typed caller in the game (the client's tracking callback, a dead {@code Level} lambda) already
 *       handles.</li>
 *   <li>{@code EntityHitboxDebugRenderer.showHitboxes}, MinecraftForge's body, reads the parts through NeoForge's
 *       {@code getParts()} so the debug hitboxes still show them.</li>
 * </ul>
 * {@code -Dforbric.dragonParts=off} leaves the three classes as merged.
 */
public final class DragonPartsInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.dragonParts";
	static final String PART = "net.minecraft.world.entity.boss.enderdragon.EnderDragonPart";
	static final String DRAGON = "net.minecraft.world.entity.boss.enderdragon.EnderDragon";
	static final String HITBOXES = "net.minecraft.client.renderer.debug.EntityHitboxDebugRenderer";
	static final String FORGE_PART = ForeignType.PART_ENTITY.internal(Ecosystem.FORGE);
	static final String NEO_PART = ForeignType.PART_ENTITY.internal(Ecosystem.NEOFORGE);
	static final String PART_INTERNAL = "net/minecraft/world/entity/boss/enderdragon/EnderDragonPart";
	static final String DRAGON_INTERNAL = "net/minecraft/world/entity/boss/enderdragon/EnderDragon";
	static final String ENTITY = "net/minecraft/world/entity/Entity";
	static final String FORGE_GET_PARTS = "()[L" + FORGE_PART + ";";
	static final String NEO_GET_PARTS = "()[L" + NEO_PART + ";";

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/**
	 * The superclass the game really runs {@code internalName} with when this repair rebased it, else null — for code
	 * that reads the hierarchy out of the jar (MergedBaseFrameRecomputer) and must see what is loaded.
	 */
	public static String rebasedSuperclass(String internalName) {
		return enabled() && PART_INTERNAL.equals(internalName) ? NEO_PART : null;
	}

	/** The part's superclass the rebase takes off the merged hierarchy: MinecraftForge's, or NeoForge's when it is off. */
	public static String lostPartEntity() {
		return enabled() ? FORGE_PART : NEO_PART;
	}

	@Override public String name() { return "forbric-dragon-parts"; }

	@Override public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("the Ender Dragon's parts explicitly left Forge-typed with -D" + PROPERTY + "=off");
		return AnchorSet.of(
				new AnchorSet.Anchor(PART, AnchorSet.Severity.REQUIRED, "adding an Ender Dragon to a world throws in the entity callbacks"),
				new AnchorSet.Anchor(DRAGON, AnchorSet.Severity.REQUIRED, "adding an Ender Dragon to a world throws in the entity callbacks"),
				new AnchorSet.Anchor(HITBOXES, AnchorSet.Severity.REQUIRED, "the debug hitboxes leave out the Ender Dragon's parts"));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || bytes == null || bytes.length == 0) return bytes;
		if (!PART.equals(className) && !DRAGON.equals(className) && !HITBOXES.equals(className)) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		int changed = PART.equals(className) ? rebasePart(node) : DRAGON.equals(className) ? neoForgeParts(node) : hitboxes(node);
		if (changed <= 0) return bytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		ForbricLog.info(PART.equals(className) ? "[Forbric/Entity] EnderDragonPart is a NeoForge PartEntity, as every part consumer in the merged game casts it"
				: DRAGON.equals(className) ? "[Forbric/Entity] EnderDragon answers NeoForge's getParts() with its parts — it answered null, and adding a dragon threw"
				: "[Forbric/Entity] the debug hitboxes read the Ender Dragon's parts through NeoForge's getParts()");
		return writer.toByteArray();
	}

	/** EnderDragonPart: superclass, super constructor call and generic signature move to NeoForge's PartEntity. */
	static int rebasePart(ClassNode part) {
		if (NEO_PART.equals(part.superName)) return 0;
		if (!FORGE_PART.equals(part.superName)) return declined("EnderDragonPart extends " + part.superName + ", not MinecraftForge's PartEntity");
		int constructors = 0;
		for (MethodNode method : part.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call && call.owner.equals(FORGE_PART)) {
					if (!call.name.equals("<init>") || !call.desc.equals("(L" + ENTITY + ";)V")) {
						return declined("EnderDragonPart calls MinecraftForge's PartEntity." + call.name + call.desc);
					}
					constructors++;
				}
			}
		}
		if (constructors != 1) return declined("EnderDragonPart does not make exactly one super constructor call");
		part.superName = NEO_PART;
		if (part.signature != null) part.signature = part.signature.replace("L" + FORGE_PART + "<", "L" + NEO_PART + "<");
		for (MethodNode method : part.methods) retype(method);
		return 1;
	}

	/** EnderDragon: NeoForge's getParts() returns the parts; MinecraftForge's returns an empty array. */
	static int neoForgeParts(ClassNode dragon) {
		MethodNode neo = method(dragon, "getParts", NEO_GET_PARTS);
		MethodNode forge = method(dragon, "getParts", FORGE_GET_PARTS);
		if (neo != null) return 0;
		if (forge == null) return declined("EnderDragon has no MinecraftForge getParts() to take its parts from");
		List<AbstractInsnNode> real = real(forge);
		if (real.size() != 3 || !(real.get(0) instanceof VarInsnNode self && self.var == 0)
				|| !(real.get(1) instanceof FieldInsnNode field && field.name.equals("subEntities")
						&& field.desc.equals("[L" + PART_INTERNAL + ";") && field.owner.equals(DRAGON_INTERNAL))
				|| real.get(2).getOpcode() != Opcodes.ARETURN) {
			return declined("EnderDragon's getParts() is not `return this.subEntities`");
		}
		MethodNode parts = new MethodNode(Opcodes.ASM9, forge.access, "getParts", NEO_GET_PARTS,
				"()[L" + NEO_PART + "<*>;", null);
		parts.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		parts.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, DRAGON_INTERNAL, "subEntities", "[L" + PART_INTERNAL + ";"));
		parts.instructions.add(new InsnNode(Opcodes.ARETURN));
		dragon.methods.add(parts);

		forge.instructions.clear();
		forge.tryCatchBlocks.clear();
		if (forge.localVariables != null) forge.localVariables.clear();
		forge.instructions.add(new InsnNode(Opcodes.ICONST_0));
		forge.instructions.add(new TypeInsnNode(Opcodes.ANEWARRAY, FORGE_PART));
		forge.instructions.add(new InsnNode(Opcodes.ARETURN));
		return 1;
	}

	/** showHitboxes: MinecraftForge's loop over getParts(), read through NeoForge's — the one type substituted throughout. */
	static int hitboxes(ClassNode renderer) {
		int changed = 0;
		for (MethodNode method : renderer.methods) {
			boolean reads = false;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call && call.owner.equals(ENTITY) && call.name.equals("getParts")
						&& call.desc.equals(FORGE_GET_PARTS)) reads = true;
			}
			if (!reads) continue;
			retype(method);
			changed++;
		}
		return changed;
	}

	/** Substitutes NeoForge's PartEntity for MinecraftForge's in every instruction, frame and local of {@code method}. */
	static void retype(MethodNode method) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call) {
				if (call.owner.equals(FORGE_PART)) call.owner = NEO_PART;
				call.desc = swap(call.desc);
			} else if (insn instanceof TypeInsnNode type) {
				type.desc = swap(type.desc);
			} else if (insn instanceof FieldInsnNode field) {
				field.desc = swap(field.desc);
			} else if (insn instanceof FrameNode frame) {
				swapAll(frame.local);
				swapAll(frame.stack);
			}
		}
		if (method.localVariables != null) {
			for (LocalVariableNode local : method.localVariables) {
				local.desc = swap(local.desc);
				if (local.signature != null) local.signature = swap(local.signature);
			}
		}
	}

	private static void swapAll(List<Object> types) {
		if (types == null) return;
		for (int i = 0; i < types.size(); i++) if (types.get(i) instanceof String type) types.set(i, swap(type));
	}

	private static String swap(String text) {
		return text == null ? null : text.equals(FORGE_PART) ? NEO_PART : text.replace("L" + FORGE_PART + ";", "L" + NEO_PART + ";")
				.replace("L" + FORGE_PART + "<", "L" + NEO_PART + "<");
	}

	private static List<AbstractInsnNode> real(MethodNode method) {
		List<AbstractInsnNode> out = new java.util.ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) if (insn.getOpcode() >= 0) out.add(insn);
		return out;
	}

	private static int declined(String reason) {
		ForbricLog.warn("[Forbric/Entity] left the Ender Dragon's parts as merged: %s — adding a dragon to a world may throw", reason);
		return -1;
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		for (MethodNode method : node.methods) if (method.name.equals(name) && method.desc.equals(desc)) return method;
		return null;
	}
}
