/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.List;
import java.util.Map;
import java.util.Set;

import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;
import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * What NeoForge's own coremods do to the game, done by the kernel, after Mixin, where NeoForge does it.
 *
 * <p>NeoForge ships its coremods in a separate jar ({@code neoforge-coremods}, a {@code ClassProcessorProvider}) that
 * the kernel never loads, so on the merged base — whose bodies are mostly NeoForge's and are written for those
 * rewrites — none of them ever ran:
 * <ul>
 *   <li>{@code FlowerPotBlock.potted} → {@code getPotted()}. NeoForge's constructors store null in the field and
 *       keep the plant in a supplier, so every pot threw on pick-block, planting and taking a plant out.</li>
 *   <li>{@code Biome.climateSettings}/{@code specialEffects} → {@code getModifiedClimateSettings()}/
 *       {@code getModifiedSpecialEffects()}, and {@code Structure.settings} → {@code getModifiedStructureSettings()}.
 *       Without them NeoForge's (and the bridged MinecraftForge) biome and structure modifiers changed nothing that
 *       reads climate, colours, structure biomes, spawns, step or terrain adaptation.</li>
 *   <li>{@code mob.finalizeSpawn(...)} in the 26 classes of NeoForge's {@code finalize_spawn_targets.json} →
 *       its event hook. Neither family's finalization event was ever posted outside a spawner. Here the calls go
 *       to KernelFinalizeSpawn, which posts both families' events and finalizes once; TrialSpawner's call, which
 *       the merged base already routes to NeoForge's spawner hook, goes to its two-family twin.</li>
 * </ul>
 *
 * <p>The rules are NeoForge's own: a field read is rewritten in every method of the class except one whose
 * descriptor is the getter's ({@code "()" + field}), so the getter itself and any same-shaped accessor stay raw;
 * the finalize redirect takes every {@code INVOKEVIRTUAL} of that name and descriptor, whatever the receiver's class.
 * Two changes, both narrower: a read is matched by owner, name and descriptor (NeoForge matches the name alone), and
 * constructors are not rewritten (none reads these fields, and a getter called before the constructor finished
 * would not have its state yet). The field need not be private — the ACCESS phase may have widened it for a Fabric
 * mod, where NeoForge's own check would throw.
 *
 * <p>Why after Mixin: NeoForge's processors run after Mixin, so a Fabric or NeoForge mixin aimed at the vanilla
 * field read or the vanilla {@code finalizeSpawn} call still finds it, and code a mixin adds to these classes is
 * rewritten too. Every edit replaces one instruction with one of the same stack effect, so frames are kept as Mixin
 * wrote them. A class whose shape is not the expected one is left as it is and reported.
 *
 * <p>Switches: {@code -Dforbric.coremodParity=off} (all), {@code forbric.flowerPotRepair}, {@code forbric.biomeModifiedView},
 * {@code forbric.structureModifiedView}, {@code forbric.finalizeSpawnRedirect}.
 */
public final class NativeCoremodParity {
	public static final String PROPERTY = "forbric.coremodParity";
	public static final String FLOWER_POT = "forbric.flowerPotRepair";
	public static final String BIOME = "forbric.biomeModifiedView";
	public static final String STRUCTURE = "forbric.structureModifiedView";
	public static final String FINALIZE = "forbric.finalizeSpawnRedirect";

	static final String RUNTIME = "net/forbric/kernel/runtime/KernelFinalizeSpawn";
	static final String FINALIZE_NAME = "finalizeSpawn";
	static final String FINALIZE_DESC = "(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/world/DifficultyInstance;"
			+ "Lnet/minecraft/world/entity/EntitySpawnReason;Lnet/minecraft/world/entity/SpawnGroupData;)Lnet/minecraft/world/entity/SpawnGroupData;";
	static final String TRIAL_SPAWNER = "net/minecraft/world/level/block/entity/trialspawner/TrialSpawner";
	static final String NEO_HOOKS = "net/neoforged/neoforge/event/EventHooks";
	static final String NEO_SPAWNER_HOOK = "finalizeMobSpawnSpawner";
	static final String NEO_SPAWNER_DESC = "(Lnet/minecraft/world/entity/Mob;Lnet/minecraft/world/level/ServerLevelAccessor;"
			+ "Lnet/minecraft/world/DifficultyInstance;Lnet/minecraft/world/entity/EntitySpawnReason;"
			+ "Lnet/minecraft/world/entity/SpawnGroupData;Lnet/neoforged/neoforge/common/extensions/IOwnedSpawner;Z)"
			+ "Lnet/neoforged/neoforge/event/entity/living/FinalizeSpawnEvent;";

	/** One field-to-getter rewrite: {@code GETFIELD owner.field:desc} becomes {@code owner.getter()desc}. */
	record FieldGetter(String field, String desc, String getter, String property) {
	}

	static final Map<String, List<FieldGetter>> GETTERS = Map.of(
			"net/minecraft/world/level/block/FlowerPotBlock", List.of(
					new FieldGetter("potted", "Lnet/minecraft/world/level/block/Block;", "getPotted", FLOWER_POT)),
			"net/minecraft/world/level/biome/Biome", List.of(
					new FieldGetter("climateSettings", "Lnet/minecraft/world/level/biome/Biome$ClimateSettings;",
							"getModifiedClimateSettings", BIOME),
					new FieldGetter("specialEffects", "Lnet/minecraft/world/level/biome/BiomeSpecialEffects;",
							"getModifiedSpecialEffects", BIOME)),
			"net/minecraft/world/level/levelgen/structure/Structure", List.of(
					new FieldGetter("settings", "Lnet/minecraft/world/level/levelgen/structure/Structure$StructureSettings;",
							"getModifiedStructureSettings", STRUCTURE)));

	/** NeoForge 26.2.0.88's {@code finalize_spawn_targets.json}; MinecraftForge's list is the same plus TrialSpawner. */
	static final Set<String> FINALIZE_TARGETS = Set.of(
			"net/minecraft/gametest/framework/GameTestEntityBuilder",
			"net/minecraft/server/commands/RaidCommand",
			"net/minecraft/server/commands/SummonCommand",
			"net/minecraft/world/entity/EntityType",
			"net/minecraft/world/entity/ai/village/VillageSiege",
			"net/minecraft/world/entity/animal/equine/SkeletonTrapGoal",
			"net/minecraft/world/entity/animal/equine/ZombieHorse",
			"net/minecraft/world/entity/animal/frog/Tadpole",
			"net/minecraft/world/entity/monster/Strider",
			"net/minecraft/world/entity/monster/illager/Evoker$EvokerSummonSpellGoal",
			"net/minecraft/world/entity/monster/spider/Spider",
			"net/minecraft/world/entity/monster/zombie/Drowned",
			"net/minecraft/world/entity/monster/zombie/Husk",
			"net/minecraft/world/entity/monster/zombie/Zombie",
			"net/minecraft/world/entity/monster/zombie/ZombieVillager",
			"net/minecraft/world/entity/npc/CatSpawner",
			"net/minecraft/world/entity/npc/villager/Villager",
			"net/minecraft/world/entity/raid/Raid",
			"net/minecraft/world/level/NaturalSpawner",
			"net/minecraft/world/level/levelgen/PatrolSpawner",
			"net/minecraft/world/level/levelgen/PhantomSpawner",
			"net/minecraft/world/level/levelgen/structure/structures/OceanMonumentPieces$OceanMonumentPiece",
			"net/minecraft/world/level/levelgen/structure/structures/OceanRuinPieces$OceanRuinPiece",
			"net/minecraft/world/level/levelgen/structure/structures/SwampHutPiece",
			"net/minecraft/world/level/levelgen/structure/structures/WoodlandMansionPieces$WoodlandMansionPiece",
			"net/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate");

	private NativeCoremodParity() {
	}

	static boolean on(String property) {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"))
				&& !"off".equalsIgnoreCase(System.getProperty(property, "on"));
	}

	/** Whether {@code apply} would look at this class at all (a cheap test, for the loader's hot path). */
	static boolean concerns(String internalName) {
		return GETTERS.containsKey(internalName) || FINALIZE_TARGETS.contains(internalName) || TRIAL_SPAWNER.equals(internalName);
	}

	/**
	 * Rewrites {@code bytes} (a class Mixin has just woven, named in either binary or internal form) if it is one of
	 * NeoForge's coremod targets; returns the same array when nothing changes. Never throws.
	 */
	public static byte[] apply(String name, byte[] bytes) {
		if (bytes == null || name == null) return bytes;
		String internalName = name.replace('.', '/');
		if (!concerns(internalName)) return bytes;
		try {
			ClassNode node = new ClassNode();
			new ClassReader(bytes).accept(node, 0);
			int reads = 0, finalizes = 0, trial = 0;
			for (FieldGetter rule : GETTERS.getOrDefault(internalName, List.of())) {
				if (on(rule.property())) reads += rewriteReads(node, rule);
			}
			if (FINALIZE_TARGETS.contains(internalName) && on(FINALIZE)) {
				finalizes = redirectFinalize(node);
				if (finalizes == 0) {
					report(internalName, "finalize-spawn-redirect", "NeoForge rewrites mob.finalizeSpawn in this class, "
							+ "but it has no such call on this base; its spawns post neither family's finalization event");
				}
			}
			if (TRIAL_SPAWNER.equals(internalName) && on(FINALIZE)) trial = routeTrialSpawner(node);
			if (reads <= 0 && finalizes == 0 && trial == 0) return bytes;
			ClassWriter writer = new ClassWriter(0);
			node.accept(writer);
			ForbricLog.info("[Forbric/Coremod] %s: %s", internalName.substring(internalName.lastIndexOf('/') + 1),
					describe(reads, finalizes, trial));
			return writer.toByteArray();
		} catch (Throwable failure) {
			ForbricLog.warn("[Forbric/Coremod] left %s as Mixin wove it: %s", internalName, failure);
			return bytes;
		}
	}

	private static String describe(int reads, int finalizes, int trial) {
		StringBuilder out = new StringBuilder();
		if (reads > 0) out.append(reads).append(" field read(s) now go through NeoForge's getter");
		if (finalizes > 0) {
			if (out.length() > 0) out.append("; ");
			out.append(finalizes).append(" finalizeSpawn call(s) now post both families' events and finalize once");
		}
		if (trial > 0) {
			if (out.length() > 0) out.append("; ");
			out.append("the trial spawner's finalization now also posts MinecraftForge's event");
		}
		return out.toString();
	}

	/** The number of reads rewritten, or -1 when the class is not the shape the rule is for (reported). */
	static int rewriteReads(ClassNode node, FieldGetter rule) {
		FieldNode field = null;
		for (FieldNode candidate : node.fields) {
			if (candidate.name.equals(rule.field()) && candidate.desc.equals(rule.desc())) field = candidate;
		}
		String getterDesc = "()" + rule.desc();
		MethodNode getter = null;
		int getters = 0;
		for (MethodNode method : node.methods) {
			if (method.name.equals(rule.getter()) && method.desc.equals(getterDesc)) {
				getter = method;
				getters++;
			}
		}
		if (field == null || (field.access & Opcodes.ACC_STATIC) != 0 || getters != 1 || (getter.access & Opcodes.ACC_STATIC) != 0
				|| returnsField(getter, node.name, rule)) {
			report(node.name, "field-getter:" + rule.field(), "NeoForge routes reads of " + rule.field() + " through "
					+ rule.getter() + "(), but this class does not have that field and getter in NeoForge's shape; reads stay raw");
			return -1;
		}
		int rewritten = 0;
		for (MethodNode method : node.methods) {
			if (method.desc.equals(getterDesc) || method.name.equals("<init>") || method.instructions == null) continue;
			for (AbstractInsnNode insn : method.instructions.toArray()) {
				if (insn.getOpcode() == Opcodes.GETFIELD && insn instanceof FieldInsnNode read && read.owner.equals(node.name)
						&& read.name.equals(rule.field()) && read.desc.equals(rule.desc())) {
					method.instructions.set(read, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, rule.getter(), getterDesc, false));
					rewritten++;
				}
			}
		}
		return rewritten;
	}

	/**
	 * Vanilla's getter, {@code return this.field}: the field is the value, and there is nothing to route reads to.
	 * (A getter that also reads the field, as BiomeLateWriteInjector's does, is still NeoForge's; it is never
	 * rewritten itself, being the one method with its descriptor excluded.)
	 */
	private static boolean returnsField(MethodNode getter, String owner, FieldGetter rule) {
		List<AbstractInsnNode> real = new java.util.ArrayList<>();
		for (AbstractInsnNode insn : getter.instructions) if (insn.getOpcode() >= 0) real.add(insn);
		return real.size() == 3 && real.get(0).getOpcode() == Opcodes.ALOAD && real.get(1) instanceof FieldInsnNode read
				&& read.getOpcode() == Opcodes.GETFIELD && read.owner.equals(owner) && read.name.equals(rule.field())
				&& real.get(2).getOpcode() == Opcodes.ARETURN;
	}

	static int redirectFinalize(ClassNode node) {
		int redirected = 0;
		for (MethodNode method : node.methods) {
			if (method.instructions == null) continue;
			for (AbstractInsnNode insn : method.instructions.toArray()) {
				if (insn.getOpcode() == Opcodes.INVOKEVIRTUAL && insn instanceof MethodInsnNode call
						&& call.name.equals(FINALIZE_NAME) && call.desc.equals(FINALIZE_DESC)) {
					method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "finalizeMobSpawn",
							"(Lnet/minecraft/world/entity/Mob;" + FINALIZE_DESC.substring(1), false));
					redirected++;
				}
			}
		}
		return redirected;
	}

	static int routeTrialSpawner(ClassNode node) {
		int routed = 0;
		for (MethodNode method : node.methods) {
			if (method.instructions == null) continue;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn.getOpcode() == Opcodes.INVOKESTATIC && insn instanceof MethodInsnNode call && call.owner.equals(NEO_HOOKS)
						&& call.name.equals(NEO_SPAWNER_HOOK) && call.desc.equals(NEO_SPAWNER_DESC)) {
					call.owner = RUNTIME;
					call.name = "finalizeTrialSpawner";
					routed++;
				}
			}
		}
		if (routed == 0) {
			report(node.name, "finalize-spawn-trial", "the trial spawner does not call NeoForge's spawner finalization hook "
					+ "on this base; MinecraftForge's finalization event is not posted for its mobs");
		}
		return routed;
	}

	private static void report(String internalName, String id, String detail) {
		ForbricLog.warn("[Forbric/Coremod] %s: %s", internalName, detail);
		try {
			CompatibilityFindings.record(new CompatibilityFinding("coremod-parity:" + id, "forbric", "NeoForge coremods",
					"NativeCoremodParity", CompatibilityFinding.Confidence.CONFIRMED, false, detail,
					List.of(internalName.replace('/', '.'), detail)));
		} catch (Throwable unavailable) {
			// The findings ledger is optional here; the WARN above already said it.
		}
	}
}
