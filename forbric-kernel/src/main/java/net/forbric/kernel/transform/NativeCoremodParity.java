/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.List;
import java.util.Map;
import java.util.Set;

import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;
import net.forbric.kernel.util.Caught;
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
 * <p>MinecraftForge's own field-to-getter transformer ({@code FieldToMethodTransformer} in {@code forge-transformers}, a
 * modlauncher service the kernel never loads either) has the same pot and biome rules and two more that NeoForge has
 * no need of, done here too: {@code LiquidBlock.fluid} → {@code getFluid()} and {@code BucketItem.content} →
 * {@code getFluid()}. MinecraftForge's {@code (Supplier, …)} constructors, which the merged base keeps, store null in
 * the field and keep the fluid in a supplier, because the fluid is registered after the block or item that names it;
 * every MinecraftForge liquid block threw on being placed (its {@code onPlace} reads the field) and every
 * MinecraftForge bucket emptied nothing. Its rule differs from NeoForge's in which methods keep the raw read: only
 * the getter itself (its same-descriptor check compares a String with the MethodNode and never holds), so vanilla's
 * {@code BucketItem.getContent()} and a mixin's {@code @Accessor} for the field are routed too. Two additions, both
 * for this base: a read is routed only while the getter answers for an instance every constructor builds
 * ({@link #answersEveryConstructor}), so vanilla's water and lava never lose their fluid to a supplier left unset;
 * and on the merged base both fields are public, as NeoForge declares them, so NeoForge's own fluid code and
 * NeoForge mods read them from outside the class, which no native loader ever pairs with a MinecraftForge-built
 * instance — such a read ({@code GETFIELD} naming the class itself) goes through the getter as well.
 *
 * <p>Why after Mixin: NeoForge's processors run after Mixin, so a Fabric or NeoForge mixin aimed at the vanilla
 * field read or the vanilla {@code finalizeSpawn} call still finds it, and code a mixin adds to these classes is
 * rewritten too. Every edit replaces one instruction with one of the same stack effect, so frames are kept as Mixin
 * wrote them. A class whose shape is not the expected one is left as it is and reported.
 *
 * <p>Switches: {@code -Dforbric.coremodParity=off} (all), {@code forbric.flowerPotRepair}, {@code forbric.biomeModifiedView},
 * {@code forbric.structureModifiedView}, {@code forbric.finalizeSpawnRedirect}, {@code forbric.forgeFieldGetters}.
 */
public final class NativeCoremodParity {
	public static final String PROPERTY = "forbric.coremodParity";
	public static final String FLOWER_POT = "forbric.flowerPotRepair";
	public static final String BIOME = "forbric.biomeModifiedView";
	public static final String STRUCTURE = "forbric.structureModifiedView";
	public static final String FINALIZE = "forbric.finalizeSpawnRedirect";
	public static final String FORGE_GETTERS = "forbric.forgeFieldGetters";

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

	/** Whose coremod a rule is: they differ in which methods keep the raw read, and in who else may read the field. */
	enum Family {
		NEOFORGE("NeoForge"), MINECRAFTFORGE("MinecraftForge");

		final String label;

		Family(String label) {
			this.label = label;
		}
	}

	/** One field-to-getter rewrite: {@code GETFIELD owner.field:desc} becomes {@code owner.getter()desc}. */
	record FieldGetter(String field, String desc, String getter, String property, Family family) {
	}

	static final Map<String, List<FieldGetter>> GETTERS = Map.of(
			"net/minecraft/world/level/block/FlowerPotBlock", List.of(
					new FieldGetter("potted", "Lnet/minecraft/world/level/block/Block;", "getPotted", FLOWER_POT, Family.NEOFORGE)),
			"net/minecraft/world/level/biome/Biome", List.of(
					new FieldGetter("climateSettings", "Lnet/minecraft/world/level/biome/Biome$ClimateSettings;",
							"getModifiedClimateSettings", BIOME, Family.NEOFORGE),
					new FieldGetter("specialEffects", "Lnet/minecraft/world/level/biome/BiomeSpecialEffects;",
							"getModifiedSpecialEffects", BIOME, Family.NEOFORGE)),
			"net/minecraft/world/level/levelgen/structure/Structure", List.of(
					new FieldGetter("settings", "Lnet/minecraft/world/level/levelgen/structure/Structure$StructureSettings;",
							"getModifiedStructureSettings", STRUCTURE, Family.NEOFORGE)),
			"net/minecraft/world/level/block/LiquidBlock", List.of(
					new FieldGetter("fluid", "Lnet/minecraft/world/level/material/FlowingFluid;", "getFluid", FORGE_GETTERS,
							Family.MINECRAFTFORGE)),
			"net/minecraft/world/item/BucketItem", List.of(
					new FieldGetter("content", "Lnet/minecraft/world/level/material/Fluid;", "getFluid", FORGE_GETTERS,
							Family.MINECRAFTFORGE)));

	/** The owners of MinecraftForge's rules, and each field's name, as constant-pool entries: what an outside reader names. */
	private static final List<String> OUTSIDE_OWNERS = GETTERS.entrySet().stream()
			.filter(e -> e.getValue().stream().anyMatch(r -> r.family() == Family.MINECRAFTFORGE)).map(Map.Entry::getKey).sorted().toList();
	private static final byte[][] OUTSIDE_NAMES = OUTSIDE_OWNERS.stream()
			.flatMap(owner -> java.util.stream.Stream.concat(java.util.stream.Stream.of(owner),
					GETTERS.get(owner).stream().filter(r -> r.family() == Family.MINECRAFTFORGE).map(FieldGetter::field)))
			.map(net.forbric.kernel.util.ByteScan::poolEntry).toArray(byte[][]::new);

	/** The game's raw class files by resource path, to read an outside read's owner as the base has it. */
	private static volatile java.util.function.Function<String, byte[]> gameClasses;
	/** Per owner: whether its getter answers for every instance on this base, so an outside read may go through it. */
	private static final Map<String, Boolean> OUTSIDE_ROUTABLE = new java.util.concurrent.ConcurrentHashMap<>();

	/** Where outside reads find their owner's base bytes; without it they stay raw. Clears what was read before. */
	public static void readGameClassesFrom(java.util.function.Function<String, byte[]> lookup) {
		gameClasses = lookup;
		OUTSIDE_ROUTABLE.clear();
	}

	/** NeoForge 26.2.0.88's {@code finalize_spawn_targets.json} (the coremods jar of 26.2.0.93 is entry-for-entry the same); MinecraftForge's list is the same plus TrialSpawner. */
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
		boolean outside = on(FORGE_GETTERS) && namesAnOutsideField(bytes);
		if (!concerns(internalName) && !outside) return bytes;
		try {
			ClassNode node = new ClassNode();
			new ClassReader(bytes).accept(node, 0);
			int reads = 0, forgeReads = 0, outsideReads = 0, finalizes = 0, trial = 0;
			for (FieldGetter rule : GETTERS.getOrDefault(internalName, List.of())) {
				if (!on(rule.property())) continue;
				int rewritten = rewriteReads(node, rule);
				if (rule.family() == Family.MINECRAFTFORGE) forgeReads += Math.max(rewritten, 0);
				else reads += rewritten;
			}
			if (outside) outsideReads = routeOutsideReads(node);
			if (FINALIZE_TARGETS.contains(internalName) && on(FINALIZE)) {
				finalizes = redirectFinalize(node);
				if (finalizes == 0) {
					report(internalName, "finalize-spawn-redirect", "NeoForge", "NeoForge rewrites mob.finalizeSpawn in this class, "
							+ "but it has no such call on this base; its spawns post neither family's finalization event");
				}
			}
			if (TRIAL_SPAWNER.equals(internalName) && on(FINALIZE)) trial = routeTrialSpawner(node);
			if (reads <= 0 && forgeReads == 0 && outsideReads == 0 && finalizes == 0 && trial == 0) return bytes;
			ClassWriter writer = new ClassWriter(0);
			node.accept(writer);
			ForbricLog.info("[Forbric/Coremod] %s: %s", internalName.substring(internalName.lastIndexOf('/') + 1),
					describe(reads, forgeReads, outsideReads, finalizes, trial));
			return writer.toByteArray();
		} catch (Throwable failure) {
			Caught.warn(failure, "[Forbric/Coremod] left %s as Mixin wove it", internalName);
			return bytes;
		}
	}

	private static String describe(int reads, int forgeReads, int outsideReads, int finalizes, int trial) {
		StringBuilder out = new StringBuilder();
		if (reads > 0) out.append(reads).append(" field read(s) now go through NeoForge's getter");
		if (forgeReads > 0) {
			if (out.length() > 0) out.append("; ");
			out.append(forgeReads).append(" field read(s) now go through MinecraftForge's getter");
		}
		if (outsideReads > 0) {
			if (out.length() > 0) out.append("; ");
			out.append(outsideReads).append(" read(s) of another class's MinecraftForge-routed field now go through its getter");
		}
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
		String family = rule.family().label;
		if (field == null || (field.access & Opcodes.ACC_STATIC) != 0 || getters != 1 || (getter.access & Opcodes.ACC_STATIC) != 0
				|| returnsField(getter, node.name, rule)) {
			report(node.name, "field-getter:" + rule.field(), family, family + " routes reads of " + rule.field() + " through "
					+ rule.getter() + "(), but this class does not have that field and getter in " + family + "'s shape; reads stay raw");
			return -1;
		}
		if (rule.family() == Family.MINECRAFTFORGE && !answersEveryConstructor(node, getter, rule)) {
			report(node.name, "field-getter:" + rule.field(), family, family + " routes reads of " + rule.field() + " through "
					+ rule.getter() + "(), but on this base the getter does not answer for an instance every constructor builds; "
					+ "reads stay raw");
			return -1;
		}
		int rewritten = 0;
		for (MethodNode method : node.methods) {
			if (method.name.equals("<init>") || method.instructions == null) continue;
			// NeoForge keeps every method shaped like the getter raw; MinecraftForge only the getter itself.
			if (rule.family() == Family.NEOFORGE ? method.desc.equals(getterDesc) : method == getter) continue;
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

	/**
	 * Whether the getter answers for an instance built by any constructor, so a read routed through it is never worse
	 * than the read: it reads the field itself (a repaired getter that falls back to the supplier), or it reads one other
	 * field of the class, which every constructor not handing over to another fills with something other than the null
	 * constant. MinecraftForge's getters read only their supplier, and on a base whose vanilla-shaped constructor leaves
	 * the supplier unset they would take water's fluid away from it.
	 */
	static boolean answersEveryConstructor(ClassNode node, MethodNode getter, FieldGetter rule) {
		java.util.Set<String> sources = new java.util.HashSet<>();
		for (AbstractInsnNode insn : getter.instructions) {
			if (insn.getOpcode() != Opcodes.GETFIELD || !(insn instanceof FieldInsnNode read) || !read.owner.equals(node.name)) continue;
			if (read.name.equals(rule.field()) && read.desc.equals(rule.desc())) return true;
			sources.add(read.name + ":" + read.desc);
		}
		if (sources.size() != 1) return false;
		String source = sources.iterator().next();
		int at = source.indexOf(':');
		return everyConstructorFills(node, source.substring(0, at), source.substring(at + 1));
	}

	/**
	 * Whether every constructor that does not hand over to another one stores something other than the null constant
	 * into {@code name:desc}: a constructor that leaves it unset, or sets it to null, builds an instance a getter
	 * reading only that field cannot answer for.
	 */
	static boolean everyConstructorFills(ClassNode node, String name, String desc) {
		int constructors = 0;
		for (MethodNode method : node.methods) {
			if (!method.name.equals("<init>")) continue;
			boolean delegates = false, fills = false;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL && call.owner.equals(node.name)
						&& call.name.equals("<init>")) delegates = true;
				if (insn instanceof FieldInsnNode put && put.getOpcode() == Opcodes.PUTFIELD && put.owner.equals(node.name)
						&& put.name.equals(name) && put.desc.equals(desc) && previousOpcode(put) != Opcodes.ACONST_NULL) fills = true;
			}
			if (delegates) continue;
			if (!fills) return false;
			constructors++;
		}
		return constructors > 0;
	}

	private static int previousOpcode(AbstractInsnNode from) {
		for (AbstractInsnNode insn = from.getPrevious(); insn != null; insn = insn.getPrevious()) {
			if (insn.getOpcode() >= 0) return insn.getOpcode();
		}
		return -1;
	}

	/** The cheap question asked of every class: does its constant pool name one of MinecraftForge's rule owners and its field. */
	static boolean namesAnOutsideField(byte[] bytes) {
		boolean[] found = net.forbric.kernel.util.ByteScan.constantPoolNames(bytes, OUTSIDE_NAMES);
		int at = 0;
		for (String owner : OUTSIDE_OWNERS) {
			boolean named = found[at++], field = false;
			for (FieldGetter rule : GETTERS.get(owner)) {
				if (rule.family() == Family.MINECRAFTFORGE) field |= found[at++];
			}
			if (named && field) return true;
		}
		return false;
	}

	/**
	 * Reads, from another class, of a field MinecraftForge routes through its getter, routed the same way: the read
	 * names the owner itself (as NeoForge's code and NeoForge mods do, the field being public there) and the owner, as
	 * the base has it, has the getter answering for every constructor. The owner's own reads are its rule's.
	 */
	static int routeOutsideReads(ClassNode node) {
		int routed = 0;
		for (MethodNode method : node.methods) {
			if (method.instructions == null) continue;
			for (AbstractInsnNode insn : method.instructions.toArray()) {
				if (insn.getOpcode() != Opcodes.GETFIELD || !(insn instanceof FieldInsnNode read) || read.owner.equals(node.name)) continue;
				for (FieldGetter rule : GETTERS.getOrDefault(read.owner, List.of())) {
					if (rule.family() != Family.MINECRAFTFORGE || !read.name.equals(rule.field()) || !read.desc.equals(rule.desc())
							|| !outsideRoutable(read.owner, rule)) continue;
					method.instructions.set(read, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, read.owner, rule.getter(), "()" + rule.desc(), false));
					routed++;
				}
			}
		}
		return routed;
	}

	/**
	 * Whether the owner's base class has the rule's field and a getter that answers for every constructor; read once.
	 * A base that does not is reported by the owner's own rule when the owner loads, so it is not reported here.
	 */
	private static boolean outsideRoutable(String owner, FieldGetter rule) {
		return OUTSIDE_ROUTABLE.computeIfAbsent(owner + "." + rule.field(), key -> {
			java.util.function.Function<String, byte[]> lookup = gameClasses;
			byte[] bytes = lookup == null ? null : lookup.apply(owner + ".class");
			if (bytes == null) {
				ForbricLog.warn("[Forbric/Coremod] %s: the game's class could not be read; reads of %s from other classes stay raw",
						owner, rule.field());
				return false;
			}
			ClassNode base = new ClassNode();
			new ClassReader(bytes).accept(base, ClassReader.SKIP_FRAMES);
			MethodNode getter = null;
			int getters = 0;
			for (MethodNode method : base.methods) {
				if (method.name.equals(rule.getter()) && method.desc.equals("()" + rule.desc()) && (method.access & Opcodes.ACC_STATIC) == 0) {
					getter = method;
					getters++;
				}
			}
			boolean field = base.fields.stream().anyMatch(f -> f.name.equals(rule.field()) && f.desc.equals(rule.desc())
					&& (f.access & Opcodes.ACC_STATIC) == 0);
			return field && getters == 1 && !returnsField(getter, owner, rule) && answersEveryConstructor(base, getter, rule);
		});
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

	/**
	 * The trial spawner's NeoForge finalization hook goes to the kernel, which posts MinecraftForge's event too — except in
	 * a method that already calls MinecraftForge's own spawner hook (a merge that keeps both sides' edits can write both
	 * families' calls into one body), where it would post MinecraftForge's a second time.
	 */
	static int routeTrialSpawner(ClassNode node) {
		int routed = 0, native_ = 0;
		for (MethodNode method : node.methods) {
			if (method.instructions == null) continue;
			if (SpawnerFinalizeInjector.carriesForgeFinalize(node.name, method)) {
				native_++;
				continue;
			}
			for (AbstractInsnNode insn : method.instructions) {
				if (insn.getOpcode() == Opcodes.INVOKESTATIC && insn instanceof MethodInsnNode call && call.owner.equals(NEO_HOOKS)
						&& call.name.equals(NEO_SPAWNER_HOOK) && call.desc.equals(NEO_SPAWNER_DESC)) {
					call.owner = RUNTIME;
					call.name = "finalizeTrialSpawner";
					routed++;
				}
			}
		}
		if (routed == 0 && native_ == 0) {
			report(node.name, "finalize-spawn-trial", "NeoForge", "the trial spawner does not call NeoForge's spawner finalization hook "
					+ "on this base; MinecraftForge's finalization event is not posted for its mobs");
		}
		return routed;
	}

	private static void report(String internalName, String id, String family, String detail) {
		ForbricLog.warn("[Forbric/Coremod] %s: %s", internalName, detail);
		try {
			CompatibilityFindings.record(new CompatibilityFinding("coremod-parity:" + id, "forbric", family + " coremods",
					"NativeCoremodParity", CompatibilityFinding.Confidence.CONFIRMED, false, detail,
					List.of(internalName.replace('/', '.'), detail)));
		} catch (Throwable unavailable) {
			// The findings ledger is optional here; the WARN above already said it.
			Caught.warn(unavailable, "[Forbric/Coremod] could not record the %s finding for %s in the compatibility"
					+ " report", id, internalName);
		}
	}
}
