/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.HashSet;
import java.util.Set;

import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

/**
 * MinecraftForge's live item registry keeps its block→item map in {@code Item.BY_BLOCK}, as on MinecraftForge — so a
 * block item is linked to its block as the registry adds it, and {@code Block.asItem()} is right from {@code Bootstrap}
 * on, before any mod code runs.
 *
 * <p>{@code Block.asItem()} answers from {@code Item.BY_BLOCK}. On MinecraftForge that map IS the {@code block_to_item}
 * slave map of the item registry in {@code RegistryManager.ACTIVE}: {@code Item.<clinit>} takes it from
 * {@code GameData$ItemCallbacks.getBlockItemMap()}, and the item add-callback, {@code ItemCallbacks.onAdd}, links each
 * {@code BlockItem} into it through the item's own {@code registerBlocks} (unlinking the item it replaces through
 * {@code removeFromBlockToItemMap}) — so MinecraftForge's {@code Items.registerItem} has no {@code registerBlocks} call of
 * its own. The merged {@code Items} is MinecraftForge's, but the merged {@code Item} is NeoForge's, which takes
 * {@code BY_BLOCK} from NeoForge's {@code GameData.getBlockItemMap()}; the add-callback still ran on every add, into a
 * map nothing read. Nothing filled {@code BY_BLOCK} until the kernel's sweep when its registration window closes —
 * after the Fabric main entrypoints — and a block asked before that keeps AIR, because {@code asItem()} caches its first
 * answer. Naturalist's hippo builds {@code Ingredient.of(Blocks.MELON.asItem())} in a static initializer its main
 * entrypoint reaches; "Ingredient can't contain air" stopped the server.
 *
 * <p>{@code ItemCallbacks.onCreate} now hands the map it is about to install to {@code KernelBlockItemLinks.blockToItemMap},
 * which gives the ACTIVE registry NeoForge's map — the one {@code Item.BY_BLOCK} is — and every other stage the map
 * MinecraftForge built for it. Every add to the live registry then links where {@code asItem()} looks: the builtin items
 * inside {@code Bootstrap}, a Fabric mod's {@code Registry.register} and NeoForge's {@code RegisterEvent} through the
 * wrapper, a MinecraftForge mod's {@code RegisterEvent} straight into the {@code ForgeRegistry}, and the client's
 * held-back MinecraftForge mods; an override unlinks the item it replaces. The link is made inside the add, so a
 * {@code RegistryEntryAddedCallback} listener already sees it.
 *
 * <p>{@code ItemCallbacks.onClear} empties that map whenever MinecraftForge re-syncs the live registry (world close, a
 * save's or a server's ids) and re-adds every entry. Shared, that would also drop what other code put into
 * {@code BY_BLOCK} by hand, which Fabric never clears; so its {@code Map.clear()} goes through
 * {@code KernelBlockItemLinks.clear}, which keeps the shared map and empties any other. The re-adds relink every block
 * item either way.
 *
 * <p>The key the two methods edit for is found, not named: the slave key the class's own add-callback reads right before
 * {@code BlockItem.registerBlocks}. {@code -Dforbric.blockItemLinks=off} leaves the class as merged.
 */
public final class BlockItemLinkInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.blockItemLinks";
	static final String TARGET = "net.minecraftforge.registries.GameData$ItemCallbacks";
	static final String CALLBACK_DESC = "(Lnet/minecraftforge/registries/IForgeRegistryInternal;Lnet/minecraftforge/registries/RegistryManager;)V";
	static final String SLAVE_KEY_DESC = "Lnet/minecraftforge/registries/IForgeRegistry$SlaveKey;";
	static final String BLOCK_ITEM = "net/minecraft/world/item/BlockItem";
	static final String REGISTER_BLOCKS_DESC = "(Ljava/util/Map;Lnet/minecraft/world/item/Item;)V";
	static final String HOOK_OWNER = "net/forbric/kernel/runtime/KernelBlockItemLinks";
	static final String MAP_HOOK = "blockToItemMap";
	static final String MAP_HOOK_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
	static final String CLEAR_HOOK = "clear";
	static final String CLEAR_HOOK_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)V";
	/** {@code onCreate(owner, stage)} / {@code onClear(owner, stage)}: the stage is the second argument. */
	private static final int STAGE_SLOT = 2;

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override public String name() { return "forbric-block-item-links"; }

	@Override public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("MinecraftForge's item callbacks explicitly left as merged with -D" + PROPERTY + "=off");
		return AnchorSet.of(new AnchorSet.Anchor(TARGET, AnchorSet.Severity.REQUIRED,
				"Block.asItem() answers AIR for every block until the registration window closes, and a block asked earlier "
						+ "keeps AIR — a mod building an Ingredient from a block in its initializer fails to load"));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || bytes == null || bytes.length == 0 || !TARGET.equals(className)) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		Set<String> keys = blockToItemKeys(node);
		if (keys.isEmpty()) return declined("its add-callback reads no slave key before BlockItem.registerBlocks", bytes);
		MethodNode onCreate = callback(node, "onCreate");
		MethodNode onClear = callback(node, "onClear");
		if (onCreate == null) return declined("it declares no onCreate" + CALLBACK_DESC, bytes);
		if (calls(onCreate, MAP_HOOK)) return bytes;
		int created;
		try {
			created = shareCreatedMap(node.name, onCreate, keys);
		} catch (AnalyzerException e) {
			return declined("onCreate does not analyze: " + e.getMessage(), bytes);
		}
		if (created == 0) return declined("onCreate installs no map under the key " + keys, bytes);
		int cleared = onClear == null ? 0 : keepSharedMapOnClear(onClear, keys);
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		ForbricLog.info("[Forbric/Registries] MinecraftForge's item registry keeps its block->item map in Item.BY_BLOCK, as "
				+ "on MinecraftForge: block items link as they are added, not when the registration window closes "
				+ "(%d map(s) shared, %d clear(s) routed past the shared map; -D%s=off to leave it as merged)", created, cleared, PROPERTY);
		if (cleared == 0) {
			ForbricLog.warn("[Forbric/Registries] MinecraftForge's item onClear empties no map under the key %s that the kernel "
					+ "could route — a re-sync of the item registry will empty Item.BY_BLOCK and relink only block items", keys);
		}
		return writer.toByteArray();
	}

	/**
	 * The slave keys under which the class's add-callback hands a map to {@code BlockItem.registerBlocks}: the nearest
	 * {@code getstatic} of a slave key before each such call, in the same method.
	 */
	static Set<String> blockToItemKeys(ClassNode node) {
		Set<String> keys = new HashSet<>();
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (!(insn instanceof MethodInsnNode call && call.owner.equals(BLOCK_ITEM) && call.name.equals("registerBlocks")
						&& call.desc.equals(REGISTER_BLOCKS_DESC))) continue;
				for (AbstractInsnNode back = insn.getPrevious(); back != null; back = back.getPrevious()) {
					if (back instanceof MethodInsnNode other && other.owner.equals(BLOCK_ITEM)) break;
					if (back instanceof FieldInsnNode key && key.getOpcode() == Opcodes.GETSTATIC && key.owner.equals(node.name)
							&& key.desc.equals(SLAVE_KEY_DESC)) {
						keys.add(key.name);
						break;
					}
				}
			}
		}
		return keys;
	}

	/** Every {@code setSlaveMap(key, map)} under one of {@code keys}: the map goes through the hook first, with the stage. */
	private static int shareCreatedMap(String owner, MethodNode onCreate, Set<String> keys) throws AnalyzerException {
		Frame<SourceValue>[] frames = new Analyzer<>(new SourceInterpreter()).analyze(owner, onCreate);
		AbstractInsnNode[] code = onCreate.instructions.toArray();
		int sites = 0;
		for (int i = 0; i < code.length; i++) {
			if (!(code[i] instanceof MethodInsnNode call && call.name.equals("setSlaveMap")
					&& call.desc.equals("(" + SLAVE_KEY_DESC + "Ljava/lang/Object;)V")) || frames[i] == null) continue;
			Frame<SourceValue> frame = frames[i];
			SourceValue key = frame.getStack(frame.getStackSize() - 2);
			if (!keyedBy(key, owner, keys)) continue;
			InsnList share = new InsnList();
			share.add(new VarInsnNode(Opcodes.ALOAD, STAGE_SLOT));
			share.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER, MAP_HOOK, MAP_HOOK_DESC, false));
			onCreate.instructions.insertBefore(call, share);
			sites++;
		}
		return sites;
	}

	/** Every {@code Map.clear()} on {@code getSlaveMap(key)} under one of {@code keys} becomes the hook's clear. */
	private static int keepSharedMapOnClear(MethodNode onClear, Set<String> keys) {
		int sites = 0;
		for (AbstractInsnNode insn : onClear.instructions.toArray()) {
			if (!(insn instanceof MethodInsnNode clear && clear.getOpcode() == Opcodes.INVOKEINTERFACE && clear.owner.equals("java/util/Map")
					&& clear.name.equals("clear") && clear.desc.equals("()V"))) continue;
			AbstractInsnNode back = real(insn.getPrevious());
			if (back instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST) back = real(back.getPrevious());
			if (!(back instanceof MethodInsnNode get && get.name.equals("getSlaveMap"))) continue;
			if (!(real(back.getPrevious()) instanceof FieldInsnNode key && key.getOpcode() == Opcodes.GETSTATIC && keys.contains(key.name)
					&& key.desc.equals(SLAVE_KEY_DESC))) continue;
			InsnList routed = new InsnList();
			routed.add(new VarInsnNode(Opcodes.ALOAD, STAGE_SLOT));
			routed.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER, CLEAR_HOOK, CLEAR_HOOK_DESC, false));
			onClear.instructions.insertBefore(insn, routed);
			onClear.instructions.remove(insn);
			sites++;
		}
		return sites;
	}

	private static boolean keyedBy(SourceValue value, String owner, Set<String> keys) {
		if (value.insns.size() != 1) return false;
		return value.insns.iterator().next() instanceof FieldInsnNode key && key.getOpcode() == Opcodes.GETSTATIC
				&& key.owner.equals(owner) && keys.contains(key.name) && key.desc.equals(SLAVE_KEY_DESC);
	}

	private static MethodNode callback(ClassNode node, String name) {
		for (MethodNode method : node.methods) {
			if (method.name.equals(name) && method.desc.equals(CALLBACK_DESC) && (method.access & Opcodes.ACC_STATIC) == 0) return method;
		}
		return null;
	}

	private static boolean calls(MethodNode method, String hook) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(HOOK_OWNER) && call.name.equals(hook)) return true;
		}
		return false;
	}

	private static AbstractInsnNode real(AbstractInsnNode insn) {
		while (insn != null && insn.getOpcode() < 0) insn = insn.getPrevious();
		return insn;
	}

	private static byte[] declined(String reason, byte[] bytes) {
		ForbricLog.warn("[Forbric/Registries] left MinecraftForge's item callbacks as merged: %s — Block.asItem() answers "
				+ "AIR until the registration window closes", reason);
		return bytes;
	}
}
