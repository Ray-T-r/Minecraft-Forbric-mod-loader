/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * A Fabric mod's compostables go in a composter again: the merged composter asks vanilla's {@code COMPOSTABLES} map when
 * NeoForge's data map has no answer, through calls shaped the way vanilla made them.
 *
 * <p>Vanilla's {@code ComposterBlock} reads {@code COMPOSTABLES.containsKey(item)} in {@code useItemOn},
 * {@code insertItem} and {@code InputContainer.canPlaceItemThroughFace}, and {@code COMPOSTABLES.getFloat(item)} in
 * {@code addItem}. NeoForge replaced all four with {@code getValue(stack)}, its {@code neoforge:compostables} data map,
 * and in the merged game nothing reads the vanilla map any more. So fabric-content-registries'
 * {@code CompostableRegistry.add} put items where nobody looked, and BCLib's four {@code @WrapOperation}s on those
 * {@code containsKey}/{@code getFloat} calls (every item with a compostable trait) had nothing to attach to: by hand, by
 * hopper or by dropper, none of them composted.
 *
 * <p>At each of the four {@code getValue} calls — which stays, so NeoForge's answer and anything wrapping it are
 * untouched — the method now also makes the vanilla call, on {@code KernelCompostables.fallback()}, and takes its answer
 * only when the data map did not list the item. The call sits in the method it sat in on vanilla, with vanilla's
 * {@code stack.getItem()} argument, so a guest injector anchored on it binds in its own method as written (BCLib's
 * {@code useItemOn} handler is an instance method and its {@code InputContainer} one targets another class: neither
 * could have been moved into {@code getValue}). The call is made on every query, as vanilla made it, because a wrap
 * that extends the answer (BCLib's traits are never in the map) has to run on a miss. Straight-line code: no branch
 * and no frame is added. Its {@code getItem()} comes before {@code useItemOn}'s {@code awardStat} one, as vanilla's
 * did, so an injector counting {@code getItem} calls in NeoForge's body now counts one more there.
 *
 * <p>The fallback is not the whole vanilla map: {@code bootStrap}'s own {@code add} calls are recorded as they are
 * made, and an entry still holding vanilla's chance is hidden, so a datapack that removes a vanilla item from the data
 * map still keeps it out. A snapshot at the end of {@code bootStrap} would have called anything put in before it
 * vanilla; recording {@code add} from {@code bootStrap} alone does not count BCLib's {@code @Invoker} on the same
 * {@code add}. fabric-transfer's {@code ComposterWrapper} reads {@code COMPOSTABLES.getFloat} directly, so a Fabric pipe
 * and the block would disagree; its read goes to {@code KernelCompostables.effective()}, the block's own answer.
 * What a NeoForge mod still puts in the vanilla map (GlitchCore's {@code registerCompostable} does) is read on a miss
 * too, where NeoForge alone ignores it. Not covered: a farmer villager, whose merged {@code WorkAtComposter} asks the
 * data map's {@code canVillagerCompost}, not fabric-api's villager-compostable list.
 *
 * <p>All or nothing per class, on the reviewed shape: exactly one {@code getValue} in each of the four methods, no
 * vanilla map call already there, and {@code add} calls in {@code bootStrap}. {@code -Dforbric.composterBridge=off}
 * leaves all three classes as merged.
 */
public final class CompostablesFallbackInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.composterBridge";
	static final String COMPOSTER = "net.minecraft.world.level.block.ComposterBlock";
	static final String INPUT = "net.minecraft.world.level.block.ComposterBlock$InputContainer";
	static final String TOP_STORAGE = "net.fabricmc.fabric.impl.transfer.item.ComposterWrapper$TopStorage";
	static final String COMPOSTER_INTERNAL = "net/minecraft/world/level/block/ComposterBlock";
	static final String RUNTIME = "net/forbric/kernel/runtime/KernelCompostables";
	static final String MAP = "it/unimi/dsi/fastutil/objects/Object2FloatMap";
	static final String MAP_DESC = "L" + MAP + ";";
	static final String ITEM_STACK = "net/minecraft/world/item/ItemStack";
	static final String GET_VALUE_DESC = "(L" + ITEM_STACK + ";)F";
	static final String ADD_DESC = "(FLnet/minecraft/world/level/ItemLike;)V";

	/** The three merged block methods and the vanilla call each made: true for {@code containsKey}, false for {@code getFloat}. */
	static final Map<String, Boolean> COMPOSTER_SITES = Map.of("useItemOn", true, "insertItem", true, "addItem", false);

	/** One {@code getValue} call and the method whose instruction list holds it. */
	record Site(MethodNode method, MethodInsnNode getValue) {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override public String name() { return "forbric-composter-bridge"; }

	@Override public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("vanilla's compostables map left unread by the merged composter with -D" + PROPERTY + "=off");
		return AnchorSet.of(
				new AnchorSet.Anchor(COMPOSTER, AnchorSet.Severity.REQUIRED,
						"a Fabric mod's compostables (CompostableRegistry, BCLib traits) cannot go in a composter by hand"),
				new AnchorSet.Anchor(INPUT, AnchorSet.Severity.REQUIRED,
						"a hopper or dropper cannot put a Fabric mod's compostables in a composter"),
				new AnchorSet.Anchor(TOP_STORAGE, AnchorSet.Severity.REQUIRED,
						"a Fabric pipe and the composter disagree about what is compostable"));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || bytes == null || bytes.length == 0) return bytes;
		if (!COMPOSTER.equals(className) && !INPUT.equals(className) && !TOP_STORAGE.equals(className)) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		int edited = COMPOSTER.equals(className) ? repairComposter(node) : INPUT.equals(className) ? repairInput(node) : repairTopStorage(node);
		if (edited <= 0) return bytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		ForbricLog.info("[Forbric/Composter] %s asks vanilla's compostables map (entries added or changed after bootstrap) "
				+ "where NeoForge's compostables data map has no answer — %d site(s)", className, edited);
		return writer.toByteArray();
	}

	/** {@code bootStrap}'s {@code add} calls are recorded, and the three block methods ask the fallback. */
	static int repairComposter(ClassNode composter) {
		if (calls(composter, RUNTIME)) return 0;   // already done
		MethodNode bootStrap = method(composter, "bootStrap", "()V");
		if (bootStrap == null) return declined(COMPOSTER, "bootStrap() is missing");
		List<MethodInsnNode> adds = new ArrayList<>();
		for (AbstractInsnNode insn : bootStrap.instructions) {
			if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC && call.owner.equals(COMPOSTER_INTERNAL)
					&& call.name.equals("add") && call.desc.equals(ADD_DESC)) adds.add(call);
		}
		if (adds.isEmpty()) return declined(COMPOSTER, "bootStrap() makes no add(float, ItemLike) call to record");
		List<Site> sites = new ArrayList<>();
		List<Boolean> kinds = new ArrayList<>();
		for (Map.Entry<String, Boolean> wanted : COMPOSTER_SITES.entrySet()) {
			Site site = onlySite(composter, wanted.getKey());
			if (site == null) return declined(COMPOSTER, wanted.getKey() + " does not make exactly one ComposterBlock.getValue call "
					+ "and no vanilla-map call");
			sites.add(site);
			kinds.add(wanted.getValue());
		}
		// bootStrap: `dup2; invokestatic vanilla(FLItemLike;)V` before each add — the add still runs with its own arguments.
		for (MethodInsnNode add : adds) {
			InsnList record = new InsnList();
			record.add(new InsnNode(Opcodes.DUP2));
			record.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "vanilla", ADD_DESC, false));
			bootStrap.instructions.insertBefore(add, record);
		}
		for (int i = 0; i < sites.size(); i++) relay(sites.get(i), kinds.get(i));
		return sites.size();
	}

	static int repairInput(ClassNode input) {
		if (calls(input, RUNTIME)) return 0;
		Site site = onlySite(input, "canPlaceItemThroughFace");
		if (site == null) return declined(INPUT, "canPlaceItemThroughFace does not make exactly one ComposterBlock.getValue call "
				+ "and no vanilla-map call");
		relay(site, true);
		return 1;
	}

	/** fabric-transfer's one {@code COMPOSTABLES} read, in {@code TopStorage.insert}, reads the block's own answer instead. */
	static int repairTopStorage(ClassNode storage) {
		if (calls(storage, RUNTIME)) return 0;
		MethodNode insert = null;
		FieldInsnNode read = null;
		int reads = 0;
		for (MethodNode method : storage.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC && field.owner.equals(COMPOSTER_INTERNAL)
						&& field.name.equals("COMPOSTABLES")) {
					reads++;
					if (method.name.equals("insert") && field.desc.equals(MAP_DESC)) {
						insert = method;
						read = field;
					}
				}
			}
		}
		if (reads != 1 || read == null) {
			return declined(TOP_STORAGE, "ComposterWrapper$TopStorage does not read COMPOSTABLES exactly once, in insert");
		}
		insert.instructions.set(read, new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "effective", "()" + MAP_DESC, false));
		return 1;
	}

	/**
	 * Around {@code getValue(stack)} — {@code [stack]} before the call — keeps NeoForge's float and adds the vanilla call
	 * on the fallback: {@code [s] dup [s s] getValue [s f] swap [f s] fallback() [f s m] swap [f m s] getItem [f m item]
	 * containsKey|getFloat [f x] orContains|orChance [f']}.
	 */
	static void relay(Site site, boolean containsKey) {
		InsnList after = new InsnList();
		after.add(new InsnNode(Opcodes.SWAP));
		after.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "fallback", "()" + MAP_DESC, false));
		after.add(new InsnNode(Opcodes.SWAP));
		after.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ITEM_STACK, "getItem", "()Lnet/minecraft/world/item/Item;", false));
		if (containsKey) {
			after.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP, "containsKey", "(Ljava/lang/Object;)Z", true));
			after.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "orContains", "(FZ)F", false));
		} else {
			after.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP, "getFloat", "(Ljava/lang/Object;)F", true));
			after.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "orChance", "(FF)F", false));
		}
		site.method().instructions.insertBefore(site.getValue(), new InsnNode(Opcodes.DUP));
		site.method().instructions.insert(site.getValue(), after);
	}

	/** The one {@code getValue} call in the one method named {@code name}, when that method makes no vanilla-map call. */
	static Site onlySite(ClassNode owner, String name) {
		MethodNode found = null;
		for (MethodNode method : owner.methods) {
			if (!method.name.equals(name)) continue;
			if (found != null) return null;
			found = method;
		}
		if (found == null) return null;
		MethodInsnNode site = null;
		for (AbstractInsnNode insn : found.instructions) {
			if (!(insn instanceof MethodInsnNode call)) continue;
			if (call.owner.equals(MAP) && (call.name.equals("containsKey") || call.name.equals("getFloat"))) return null;
			if (call.getOpcode() == Opcodes.INVOKESTATIC && call.owner.equals(COMPOSTER_INTERNAL) && call.name.equals("getValue")
					&& call.desc.equals(GET_VALUE_DESC)) {
				if (site != null) return null;
				site = call;
			}
		}
		return site == null ? null : new Site(found, site);
	}

	private static boolean calls(ClassNode node, String owner) {
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) if (insn instanceof MethodInsnNode call && call.owner.equals(owner)) return true;
		}
		return false;
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		for (MethodNode method : node.methods) if (method.name.equals(name) && method.desc.equals(desc)) return method;
		return null;
	}

	private static int declined(String className, String reason) {
		ForbricLog.warn("[Forbric/Composter] left %s as merged: %s — a Fabric mod's compostables cannot go in a composter", className, reason);
		return -1;
	}
}
