/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.transform;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Predicate;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.mixin.MixinFit;
import net.forbric.kernel.util.ByteScan;
import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Caught;

/**
 * Lets NeoForge's registry sync remap the registries that MinecraftForge wraps.
 *
 * <p>On the merged base, {@code BuiltInRegistries.BLOCK}, {@code ITEM}, {@code ENTITY_TYPE} and 24 more builtin
 * registries (plus MinecraftForge's own three) are {@code net.minecraftforge.registries.NamespacedWrapper}s — the
 * {@code MappedRegistry} subclass that makes a Forge mod's {@code ForgeRegistries.BLOCKS} and vanilla's registry ONE
 * store. It does that by delegating every lookup to its {@code ForgeRegistry}; the {@code MappedRegistry} fields it
 * inherits ({@code byId}, {@code toId}, {@code byKey}) stay empty for life.
 *
 * <p>NeoForge's {@code RegistryManager.applySnapshot} — the client half of its configuration-phase registry sync —
 * remaps a registry to the server's ids through exactly those fields: {@code unfreeze(false)}, {@code clear(false)},
 * {@code registerIdMapping(key, id)} per entry, {@code freeze()}. Two of the four are NeoForge additions the wrapper
 * never heard of, so it inherits the merged {@code MappedRegistry}'s versions, and {@code registerIdMapping} does
 * {@code byKey.get(key).value()} on the empty map: {@code NullPointerException: "holder" is null}, and the client
 * is dropped with "Failed to sync registries from the server". The check right before it, {@code containsKey(key)},
 * passed — the wrapper overrides THAT one to ask the delegate. gate-m12 is the first thing that ever ran this path;
 * singleplayer syncs nothing.
 *
 * <p>Forge has its own remap path for the same job — {@code GameData.injectSnapshot}, what a Forge client runs on
 * login: {@code ForgeRegistry.sync} re-adds every entry at its new id and {@code NamespacedWrapper.onAdded} re-indexes
 * the wrapper's holders, reusing the same {@code Holder.Reference} objects so nothing that captured one goes stale.
 * So this injector gives the wrapper NeoForge's contract and routes it there:
 * <ul>
 *   <li>{@code clear(boolean)} and {@code registerIdMapping(ResourceKey, int)} are ADDED to {@code NamespacedWrapper}
 *       (inherited by {@code NamespacedDefaultedWrapper}), forwarding to {@code KernelForgeWrapperSync}, which only
 *       STAGES the server's ids per registry;</li>
 *   <li>{@code RegistryManager.applySnapshot(Map, boolean)} gets a prologue that resets the staging and, before each
 *       return, a call that FLUSHES it — one {@code injectSnapshot} for every staged registry, then a rebuild of the
 *       block-state id map, which on the merged base is NeoForge's — unless the snapshot had missing entries, in which
 *       case NeoForge is about to disconnect and the Forge registries are left untouched.</li>
 * </ul>
 * The wrapper's own {@code freeze()} returns early while its {@code frozen} flag is set, and NeoForge's
 * {@code unfreeze(false)} clears the INHERITED flag, not that one — so NeoForge's per-registry freeze is a no-op
 * here, and the flush after the loop is the only write.
 *
 * <p>Frame-safe by construction: the added methods are straight-line, and the {@code applySnapshot} splices add no
 * branch target — a static call at the head, and {@code DUP; INVOKESTATIC} in front of an {@code ARETURN} whose
 * operand stays on the stack. {@code COMPUTE_MAXS} for the extra stack slot.
 */
public final class RegistrySyncParityInjector implements ClassTransformer {
	private static final String WRAPPER = "net.minecraftforge.registries.NamespacedWrapper";
	/**
	 * The anonymous {@code Registry.PendingTags} MinecraftForge's wrapper hands back from {@code prepareTagReload}.
	 * On the merged base that interface extends NeoForge's {@code PendingTagsExtension}, which Forge's class was
	 * never compiled against — so the first thing that asks a wrapped registry for its pending tag contents dies
	 * with an {@code AbstractMethodError}, and it does it during a world load.
	 */
	private static final String WRAPPER_PENDING_TAGS = "net.minecraftforge.registries.NamespacedWrapper$3";
	private static final String PENDING_BINDINGS = "val$newBindings";
	private static final String IMMUTABLE_MAP = "Lcom/google/common/collect/ImmutableMap;";
	private static final String NEO_REGISTRY_MANAGER = ForeignType.REGISTRY_MANAGER.binary(Ecosystem.NEOFORGE);

	private static final String HOOK_OWNER = "net/forbric/kernel/boot/KernelForgeWrapperSync";
	private static final String RESOURCE_KEY = "Lnet/minecraft/resources/ResourceKey;";

	private static final String REGISTER_ID_MAPPING = "registerIdMapping";
	private static final String REGISTER_ID_MAPPING_DESC = "(" + RESOURCE_KEY + "I)V";
	private static final String CLEAR = "clear";
	private static final String CLEAR_DESC = "(Z)V";

	private static final String APPLY_SNAPSHOT = "applySnapshot";
	private static final String APPLY_SNAPSHOT_DESC = "(Ljava/util/Map;Z)Ljava/util/Set;";
	private static final String REVERT_TO_FROZEN = "revertToFrozen";
	private static final String REVERT_OWNER = "net/forbric/kernel/boot/KernelRegistryRevert";

	/** The server's ids, by entry name, as a remap contract takes them. */
	private static final String IDS = "Lit/unimi/dsi/fastutil/objects/Object2IntMap;";
	private static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";

	/**
	 * A remap a mod's mixin adds to a registry class: a public {@code void} method the mixin implements for one of its
	 * interfaces ({@code owner}), that takes the server's ids as an {@code Object2IntMap} (argument {@code idsArgument})
	 * and works on the registry's own entry storage through the mixin's {@code @Shadow} fields. fabric-api's registry
	 * sync is one; whatever its interface and method are called, the wrapper that inherits it from {@code target} has
	 * empty copies of those fields and needs its own. {@code undo}, when the same interface declares one, is the
	 * argument-free {@code void} method the mixin implements over the same storage: what puts the registry back after a
	 * remap (null when there is none, or several). Internal names.
	 */
	public record RemapContract(String target, String owner, String name, String desc, int idsArgument, String undo) {
	}

	private static final Map<RemapContract, byte[][]> NEEDLES = new ConcurrentHashMap<>();
	/** Per mixin config, the remap contracts its mixins add, as the kernel's mixin discovery read them. */
	private static final Map<String, List<RemapContract>> DISCOVERED = new ConcurrentHashMap<>();

	/**
	 * Whether the loader that defines the wrapper has a class, by internal name. Asked when the wrapper is
	 * transformed, for the types each remap contract names.
	 */
	private final Predicate<String> gameHasClass;
	/** The remap contracts mixins add to a class, by its internal name. */
	private final Function<String, List<RemapContract>> remapContracts;

	/**
	 * The injector KernelBoot registers: {@code game} is the loader that defines the wrapper, and so the one that
	 * resolves the types its methods name. It is asked for each type's {@code .class} resource when the wrapper is
	 * transformed, and a remap contract is added only when every type it names is there. The decision lives here, not
	 * in KernelBoot, so that {@code RegistryWrapperAccessInjectorTest} runs the very predicate the game does. The
	 * contracts are the ones the mixin configs were seen to add ({@link #noteMixin}). See {@link #giveWrapperBothContracts}.
	 */
	public static RegistrySyncParityInjector forGameLoader(ClassLoader game) {
		if (game == null) throw new NullPointerException("game");
		return new RegistrySyncParityInjector(type -> game.getResource(type + ".class") != null, RegistrySyncParityInjector::discovered);
	}

	/**
	 * For tests that stand in for the game loader; the game's injector is {@link #forGameLoader}.
	 *
	 * @param gameHasClass   whether the game loader has the class with this internal name
	 * @param remapContracts the remap contracts mixins add to a class, by its internal name; every known one for {@code null}
	 */
	RegistrySyncParityInjector(Predicate<String> gameHasClass, Function<String, List<RemapContract>> remapContracts) {
		if (gameHasClass == null) throw new NullPointerException("gameHasClass");
		if (remapContracts == null) throw new NullPointerException("remapContracts");
		this.gameHasClass = gameHasClass;
		this.remapContracts = remapContracts;
	}

	/** Records the remap contracts {@code mixin}, of {@code config}, adds; {@code resources} reads its interfaces. */
	public static void noteMixin(String config, ClassNode mixin, Function<String, byte[]> resources) {
		List<RemapContract> found = remapContracts(mixin, resources);
		if (!found.isEmpty()) DISCOVERED.computeIfAbsent(config, ignored -> new CopyOnWriteArrayList<>()).addAll(found);
	}

	/** Forgets what {@code config}'s mixins were seen to add, before it is read again. */
	public static void forgetMixins(String config) {
		DISCOVERED.remove(config);
	}

	/** Every remap contract the kernel's mixin discovery noted, for whichever registry class. */
	public static List<RemapContract> discoveredContracts() {
		return discovered(null);
	}

	/** The discovered remap contracts mixins add to {@code target}; every one of them for {@code null}. */
	static List<RemapContract> discovered(String target) {
		List<RemapContract> out = new ArrayList<>();
		for (List<RemapContract> contracts : DISCOVERED.values()) {
			for (RemapContract contract : contracts) if (target == null || contract.target().equals(target)) out.add(contract);
		}
		return out;
	}

	/**
	 * The remap contracts {@code mixin} adds to each of its targets (see {@link RemapContract}). Read off the mixin class
	 * and the interfaces it implements ({@code resources}: class files by resource path); whose mixin it is plays no part.
	 */
	public static List<RemapContract> remapContracts(ClassNode mixin, Function<String, byte[]> resources) {
		if (mixin == null || mixin.interfaces == null || mixin.interfaces.isEmpty() || resources == null) return List.of();
		List<String> targets = MixinFit.mixinTargets(mixin);
		if (targets.isEmpty()) return List.of();
		java.util.Set<String> shadows = new java.util.HashSet<>();
		for (FieldNode field : mixin.fields) {
			if ((field.access & Opcodes.ACC_STATIC) == 0 && (annotated(field.visibleAnnotations) || annotated(field.invisibleAnnotations))) shadows.add(field.name);
		}
		if (shadows.isEmpty()) return List.of();
		List<RemapContract> out = new ArrayList<>();
		for (MethodNode method : mixin.methods) {
			if ((method.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != Opcodes.ACC_PUBLIC
					|| method.name.startsWith("<") || !method.desc.endsWith(")V")) continue;
			Type[] arguments = Type.getArgumentTypes(method.desc);
			int ids = -1;
			for (int i = 0; i < arguments.length; i++) {
				if (!arguments[i].getDescriptor().equals(IDS)) continue;
				if (ids >= 0) { ids = -2; break; }
				ids = i;
			}
			if (ids < 0) continue;
			if (!touchesShadows(mixin, method, shadows)) continue;
			String owner = declaring(mixin.interfaces, method.name, method.desc, resources, new java.util.HashSet<>());
			if (owner == null) continue;
			String undo = null;
			for (MethodNode other : mixin.methods) {
				if ((other.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != Opcodes.ACC_PUBLIC
						|| !other.desc.equals("()V") || !touchesShadows(mixin, other, shadows)
						|| !owner.equals(declaring(mixin.interfaces, other.name, other.desc, resources, new java.util.HashSet<>()))) continue;
				undo = undo == null ? other.name : "";
			}
			if ("".equals(undo)) undo = null;
			for (String target : targets) out.add(new RemapContract(target, owner, method.name, method.desc, ids, undo));
		}
		return out;
	}

	/** Whether {@code method} reads or writes one of {@code mixin}'s {@code @Shadow} instance fields. */
	private static boolean touchesShadows(ClassNode mixin, MethodNode method, java.util.Set<String> shadows) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof FieldInsnNode field && field.owner.equals(mixin.name) && shadows.contains(field.name)) return true;
		}
		return false;
	}

	private static boolean annotated(List<org.objectweb.asm.tree.AnnotationNode> annotations) {
		if (annotations == null) return false;
		for (var annotation : annotations) if (SHADOW.equals(annotation.desc)) return true;
		return false;
	}

	/** The interface among {@code interfaces} (or their superinterfaces) that declares {@code name desc}, or null. */
	private static String declaring(List<String> interfaces, String name, String desc, Function<String, byte[]> resources, java.util.Set<String> seen) {
		for (String contract : interfaces) {
			if (!seen.add(contract)) continue;
			byte[] bytes;
			try { bytes = resources.apply(contract + ".class"); } catch (RuntimeException unreadable) { Caught.warn(unreadable, "[Forbric/RegistrySync] could not read %s; it is not checked as a remap contract", contract); bytes = null; }
			if (bytes == null) continue;
			ClassNode node = new ClassNode();
			new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			if ((node.access & Opcodes.ACC_INTERFACE) == 0) continue;
			for (MethodNode method : node.methods) {
				if (method.name.equals(name) && method.desc.equals(desc) && (method.access & Opcodes.ACC_STATIC) == 0) return node.name;
			}
			String inherited = declaring(node.interfaces, name, desc, resources, seen);
			if (inherited != null) return inherited;
		}
		return null;
	}

	@Override
	public String name() {
		return "forbric-registry-sync-parity";
	}

	@Override
	public AnchorSet anchors() {
		// The middle one is pinned by ANONYMOUS ORDINAL, and anonymous numbering shifts whenever the enclosing
		// source does -- which on a byte-merged base it does. That is precisely the anchor most worth watching.
		return AnchorSet.of(
				new AnchorSet.Anchor(WRAPPER, AnchorSet.Severity.REQUIRED,
						"the Forge registry wrapper would satisfy only one ecosystem's contract, and the other's "
								+ "mods would find their registries unusable"),
				// HEDGE, and the live ledger is what corrected this: the staged carrier ALREADY declares
				// contents(), so this repair correctly declines every boot. "Handed the class and made no edit"
				// is therefore the healthy answer here, not a finding. The anonymous-class ordinal that addresses
				// this target is guarded by RegistrySyncParityInjectorTest, which is where that belongs.
				new AnchorSet.Anchor(WRAPPER_PENDING_TAGS, AnchorSet.Severity.HEDGE,
						"nothing today: this carrier already implements contents(). Kept for one that does not, "
								+ "where its absence would kill a world load inside NeoForge's condition context"),
				new AnchorSet.Anchor(NEO_REGISTRY_MANAGER, AnchorSet.Severity.REQUIRED,
						"the kernel's own pre-connection registry snapshot would never be applied, so ids would "
								+ "not revert after a multiplayer disconnect"));
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0) return classBytes;
		if (WRAPPER.equals(className)) return giveWrapperBothContracts(className, classBytes);
		if (WRAPPER_PENDING_TAGS.equals(className)) return giveWrapperPendingTagsItsContents(className, classBytes);
		if (NEO_REGISTRY_MANAGER.equals(className)) return flushAroundApplySnapshot(className, classBytes);
		return flushAroundRemapCalls(className, classBytes);
	}

	/** The reference types {@code contract}'s descriptor names that {@link #gameHasClass} does not find, by internal name. */
	private List<String> missingTypes(RemapContract contract) {
		List<String> missing = new ArrayList<>();
		for (Type argument : Type.getArgumentTypes(contract.desc())) {
			if (argument.getSort() == Type.OBJECT && !gameHasClass.test(argument.getInternalName())) {
				missing.add(argument.getInternalName());
			}
		}
		return missing;
	}

	/**
	 * Adds {@code contents()} to MinecraftForge's pending-tags class: the map of tag key to the holders that tag is
	 * about to hold.
	 *
	 * <p>The class already keeps exactly that map — its own {@code getPending} looks a key up in it and its
	 * {@code size} returns its size — so the method NeoForge's extension asks for is a plain read of the field
	 * Forge already fills. Returning anything else, an empty map above all, would tell NeoForge's condition context
	 * that a datapack's tags are empty when they are not.
	 */
	private static byte[] giveWrapperPendingTagsItsContents(String className, byte[] classBytes) {
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);

		for (MethodNode existing : node.methods) {
			if ("contents".equals(existing.name)) return classBytes; // a carrier that already has it
		}
		boolean hasBindings = false;
		for (org.objectweb.asm.tree.FieldNode field : node.fields) {
			if (PENDING_BINDINGS.equals(field.name) && IMMUTABLE_MAP.equals(field.desc)) hasBindings = true;
		}
		if (!hasBindings) {
			ForbricLog.warn("[Forbric/RegistrySync] %s no longer keeps its pending tags in %s — a world load will die "
					+ "in NeoForge's condition context; re-derive this", className, PENDING_BINDINGS);
			return classBytes;
		}

		// public Map contents() { return this.val$newBindings; }
		MethodNode contents = new MethodNode(Opcodes.ACC_PUBLIC, "contents", "()Ljava/util/Map;", null, null);
		contents.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		contents.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETFIELD, node.name,
				PENDING_BINDINGS, IMMUTABLE_MAP));
		contents.instructions.add(new InsnNode(Opcodes.ARETURN));
		contents.maxStack = 1;
		contents.maxLocals = 1;
		node.methods.add(contents);

		ForbricLog.info("[Forbric/RegistrySync] gave %s NeoForge's pending-tags contract (contents) — the merged "
				+ "Registry.PendingTags extends NeoForge's interface, and MinecraftForge's implementation of it "
				+ "predates that", className);

		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	/**
	 * Adds NeoForge's {@code clear(Z)} / {@code registerIdMapping(ResourceKey, I)} and every remap contract a mixin adds
	 * to the wrapper's superclass ({@link RemapContract}) to the wrapper — unless it grew its own.
	 *
	 * <p>A remap contract is the same disease with a quieter symptom: the mixin method rewrites the {@code MappedRegistry}
	 * fields it shadows, and on the wrapper those are the same empty fields — so against a PURE Fabric server (where only
	 * fabric-api's sync, one such contract, runs) the wrapped registries kept their local ids and nothing said so. With
	 * mod sets that differ between client and server, every block, item, entity type and sound in those registries then
	 * decodes to the wrong one. The override stages the server's ids exactly as the NeoForge one does, and the method that
	 * calls the contract flushes them when it returns ({@link #flushAroundRemapCalls}).
	 *
	 * <p><b>Only when its types are on the game loader.</b> The method is public and names the mod's own types.
	 * {@code Class.getMethod} searches from the runtime class upwards, resolves the parameter types of every public
	 * method each class it reaches declares, and stops at the first class that declares a match; {@code getMethods}
	 * reaches every class. Were a type absent, every lookup that reaches {@code NamespacedWrapper} would throw
	 * {@code NoClassDefFoundError}: {@code getMethods()}, any {@code getMethod} on a plain wrapper, and on a
	 * {@code NamespacedDefaultedWrapper} (the class of {@code BuiltInRegistries.BLOCK}) any name it does not declare
	 * itself. {@code clear} and {@code registerIdMapping} are protected, which {@code getMethod} does not look at, and
	 * name only game types.
	 */
	private byte[] giveWrapperBothContracts(String className, byte[] classBytes) {
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);

		List<RemapContract> contracts = new ArrayList<>();
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (RemapContract contract : remapContracts.apply(node.superName)) if (seen.add(contract.name() + contract.desc())) contracts.add(contract);
		for (MethodNode m : node.methods) {
			boolean remap = contracts.stream().anyMatch(contract -> m.name.equals(contract.name()) && m.desc.equals(contract.desc()));
			if ((m.name.equals(REGISTER_ID_MAPPING) && m.desc.equals(REGISTER_ID_MAPPING_DESC))
					|| (m.name.equals(CLEAR) && m.desc.equals(CLEAR_DESC)) || remap) {
				// A wrapper that overrides these itself has closed the gap natively; adding a second copy would
				// be a duplicate-method ClassFormatError, so leave it be and say so.
				ForbricLog.warn("[Forbric/RegistrySync] %s already declares %s%s — leaving Forge's own version in "
						+ "charge of the registry sync", className, m.name, m.desc);
				return classBytes;
			}
		}

		// protected void registerIdMapping(ResourceKey<T> key, int id) { KernelForgeWrapperSync.stageIdMapping(this, key, id); }
		MethodNode stage = new MethodNode(Opcodes.ACC_PROTECTED, REGISTER_ID_MAPPING, REGISTER_ID_MAPPING_DESC, null, null);
		stage.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		stage.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
		stage.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
		stage.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER, "stageIdMapping",
				"(Ljava/lang/Object;Ljava/lang/Object;I)V", false));
		stage.instructions.add(new InsnNode(Opcodes.RETURN));
		stage.maxStack = 3;
		stage.maxLocals = 3;
		node.methods.add(stage);

		// protected void clear(boolean full) { KernelForgeWrapperSync.clear(this, full); }
		MethodNode clear = new MethodNode(Opcodes.ACC_PROTECTED, CLEAR, CLEAR_DESC, null, null);
		clear.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		clear.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
		clear.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER, "clear",
				"(Ljava/lang/Object;Z)V", false));
		clear.instructions.add(new InsnNode(Opcodes.RETURN));
		clear.maxStack = 2;
		clear.maxLocals = 2;
		node.methods.add(clear);

		List<String> added = new ArrayList<>(), left = new ArrayList<>();
		for (RemapContract contract : contracts) {
			List<String> missing = missingTypes(contract);
			if (!missing.isEmpty()) {
				left.add(contract.owner().replace('/', '.') + "." + contract.name() + " (the game has no " + String.join(", ", missing) + ")");
				continue;
			}
			node.methods.add(stageRemap(contract));
			added.add(contract.owner().replace('/', '.') + "." + contract.name());
		}
		ForbricLog.info("[Forbric/RegistrySync] gave %s NeoForge's id-remap contract (clear, registerIdMapping)%s — the server's "
				+ "ids are staged and applied through Forge's own injectSnapshot%s", className,
				added.isEmpty() ? "" : " and the remap contract(s) a mixin adds to its superclass: " + String.join(", ", added),
				left.isEmpty() ? "" : "; left out, because a public method naming an absent type would make getMethods() and every "
						+ "getMethod lookup that reaches this class fail to link: " + String.join(", ", left));
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** {@code public void <contract>(..ids.., ..) { KernelForgeWrapperSync.stageFabricRemap(this, ids, <other argument or null>); }} */
	private static MethodNode stageRemap(RemapContract contract) {
		MethodNode remap = new MethodNode(Opcodes.ACC_PUBLIC, contract.name(), contract.desc(), null, null);
		Type[] arguments = Type.getArgumentTypes(contract.desc());
		int slot = 1, idsSlot = -1, otherSlot = -1, others = 0;
		for (int i = 0; i < arguments.length; i++) {
			if (i == contract.idsArgument()) idsSlot = slot;
			else if (arguments[i].getSort() >= Type.ARRAY) { otherSlot = slot; others++; }
			slot += arguments[i].getSize();
		}
		remap.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		remap.instructions.add(new VarInsnNode(Opcodes.ALOAD, idsSlot));
		if (others == 1) remap.instructions.add(new VarInsnNode(Opcodes.ALOAD, otherSlot));
		else remap.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
		remap.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER, "stageFabricRemap",
				"(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V", false));
		remap.instructions.add(new InsnNode(Opcodes.RETURN));
		remap.maxStack = 3;
		remap.maxLocals = slot;
		return remap;
	}

	/** Resets the staging at the head of {@code applySnapshot(Map, boolean)} and flushes it before every return. */
	private static byte[] flushAroundApplySnapshot(String className, byte[] classBytes) {
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);

		MethodNode target = null;
		for (MethodNode m : node.methods) {
			if (m.name.equals(APPLY_SNAPSHOT) && m.desc.equals(APPLY_SNAPSHOT_DESC)
					&& (m.access & Opcodes.ACC_STATIC) != 0) {
				target = m;
				break;
			}
		}
		if (target == null) {
			ForbricLog.warn("[Forbric/RegistrySync] %s has no static %s%s — NeoForge's registry sync will stage ids "
					+ "on the Forge-wrapped registries and never apply them; re-derive RegistrySyncParityInjector",
					className, APPLY_SNAPSHOT, APPLY_SNAPSHOT_DESC);
			return classBytes;
		}

		InsnList head = new InsnList();
		head.add(new LdcInsnNode(org.objectweb.asm.Type.getObjectType(node.name))); // a game class, for its loader
		head.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER, "beginSnapshotApplication", "(Ljava/lang/Class;)V", false));
		target.instructions.insert(head);

		int returns = 0;
		for (AbstractInsnNode insn = target.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() != Opcodes.ARETURN) continue;
			// The Set of missing keys is on the stack; hand a copy to the flush and leave the original to return.
			InsnList flush = new InsnList();
			flush.add(new InsnNode(Opcodes.DUP));
			flush.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER, "finishSnapshotApplication",
					"(Ljava/util/Set;)V", false));
			target.instructions.insertBefore(insn, flush);
			returns++;
		}
		target.maxStack = Math.max(target.maxStack, 2);

		// revertToFrozen(): NeoForge's disconnect-time revert, which re-applies a snapshot GameData.freezeData takes
		// — a freeze the kernel owns, so the snapshot never existed and the call used to be neutered. The kernel
		// captures its own at the first remap (the head hook above) and this body now puts it back.
		for (MethodNode m : node.methods) {
			if (!m.name.equals(REVERT_TO_FROZEN) || !m.desc.equals("()V") || (m.access & Opcodes.ACC_STATIC) == 0) continue;
			InsnList body = new InsnList();
			body.add(new LdcInsnNode(org.objectweb.asm.Type.getObjectType(node.name)));
			body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, REVERT_OWNER, "revertToPreConnection", "(Ljava/lang/Class;)V", false));
			body.add(new InsnNode(Opcodes.RETURN));
			m.instructions = body;
			m.tryCatchBlocks = null;
			m.localVariables = null;
			m.maxStack = 1;
			m.maxLocals = 0;
			ForbricLog.info("[Forbric/RegistrySync] %s.%s() now restores the kernel's own pre-connection snapshot", className,
					REVERT_TO_FROZEN);
		}

		ForbricLog.info("[Forbric/RegistrySync] flushing the Forge-wrapped registries' staged ids at %d return(s) of "
				+ "%s.%s", returns, className, APPLY_SNAPSHOT);
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	/**
	 * The twin of {@link #flushAroundApplySnapshot} for the remap contracts: a method that calls one on the synced
	 * registries — fabric-api's client sync, whatever it is called — is where the wrapped ones get theirs applied, when
	 * it returns. The staging is reset at its head; a bare call goes before each return, so an exception path leaves the
	 * staging to the next {@code beginSnapshotApplication}. Which methods those are is read from their calls, not their
	 * names; a class that names no contract is handed back untouched after a constant-pool scan.
	 */
	private byte[] flushAroundRemapCalls(String className, byte[] classBytes) {
		List<RemapContract> contracts = allContracts();
		if (contracts.isEmpty() || className.startsWith("net.forbric.")) return classBytes;
		boolean named = false;
		for (RemapContract contract : contracts) {
			byte[][] needles = NEEDLES.computeIfAbsent(contract, c -> new byte[][] { ByteScan.needle(c.desc()), ByteScan.needle(c.owner()) });
			named |= ByteScan.contains(classBytes, needles[0]) && ByteScan.contains(classBytes, needles[1]);
		}
		if (!named) return classBytes;
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		List<String> hooked = new ArrayList<>();
		for (MethodNode method : node.methods) {
			boolean calls = false;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call) for (RemapContract contract : contracts) {
					calls |= call.owner.equals(contract.owner()) && call.name.equals(contract.name()) && call.desc.equals(contract.desc());
				}
			}
			if (!calls || method.name.startsWith("<")) continue;
			InsnList head = new InsnList();
			head.add(new LdcInsnNode(Type.getObjectType(node.name)));
			head.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER, "beginSnapshotApplication", "(Ljava/lang/Class;)V", false));
			method.instructions.insert(head);
			int returns = 0;
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn.getOpcode() < Opcodes.IRETURN || insn.getOpcode() > Opcodes.RETURN) continue;
				method.instructions.insertBefore(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER, "finishFabricRemap", "()V", false));
				returns++;
			}
			hooked.add(method.name + " (" + returns + " return(s))");
		}
		if (hooked.isEmpty()) return classBytes;
		ForbricLog.info("[Forbric/RegistrySync] flushing the Forge-wrapped registries' staged ids at the returns of %s.%s, "
				+ "which call(s) a remap contract", className, String.join(", ", hooked));
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** Every remap contract this injector knows, for whichever registry class. */
	private List<RemapContract> allContracts() {
		List<RemapContract> all = remapContracts.apply(null);
		return all == null ? List.of() : all;
	}
}
