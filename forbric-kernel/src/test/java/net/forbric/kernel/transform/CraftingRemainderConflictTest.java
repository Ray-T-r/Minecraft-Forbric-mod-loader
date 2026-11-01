package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * fabric-item-api-v1 on NeoForge's base, on the real classes: a NeoForge item's remainder override must still answer every
 * crafting-table result, brew and furnace fuel.
 *
 * <p>The mod's mixin puts {@code FabricItemStack} on {@code ItemStack}, whose NeoForge hierarchy already defaults
 * {@code getCraftingRemainder()} ({@code ItemInstanceExtension}): two unrelated defaults, which the repair settles with
 * Fabric's. Fabric's asks {@code Item.getCraftingRemainder(ItemStack)}, which the mod's class tweaker gives {@code Item} as
 * {@code FabricItem}'s default, and that ends in the final {@code Item.getCraftingRemainder()}. NeoForge's own route asks
 * {@code Item.getCraftingRemainder(ItemInstance)}, the overload a NeoForge item overrides through {@code IItemExtension}.
 * Settled Fabric's way and nothing more, that override is never reached (gate-m45 remainder.neoOverride and
 * craft.neoRemainder); {@code CHAINED} gives {@code Item} a body for the stack-typed call that asks NeoForge's overload, so
 * a Fabric item's override still wins by dispatch and a NeoForge item's is reached too. On the merged base the same row
 * settled a real conflict on {@code Item} (MinecraftForge's {@code IForgeItem} defaulted the stack-typed overload as
 * well); NeoForge's base has only Fabric's default there.
 */
class CraftingRemainderConflictTest {
	private static final List<Path> JARS = List.of(TestFixtures.stagedBase(), TestFixtures.neoCarrier());
	private static final Path FABRIC_API = TestFixtures.fabricApi();
	private static final String ITEM = "net/minecraft/world/item/Item";
	private static final String ITEM_STACK = "net/minecraft/world/item/ItemStack";
	private static final String FABRIC_ITEM = "net/fabricmc/fabric/api/item/v1/FabricItem";
	private static final String FABRIC_ITEM_STACK = "net/fabricmc/fabric/api/item/v1/FabricItemStack";
	private static final String REMAINDER = "getCraftingRemainder";
	private static final String TEMPLATE = "Lnet/minecraft/world/item/ItemStackTemplate;";
	private static final String BY_STACK = "(Lnet/minecraft/world/item/ItemStack;)" + TEMPLATE;
	private static final String BY_INSTANCE = "(Lnet/minecraft/world/item/ItemInstance;)" + TEMPLATE;
	private static final String NO_ARGUMENT = "()" + TEMPLATE;

	@Test void theTweakedItemGetsARemainderThatAsksNeoForgesOverload() throws Exception {
		Map<String, byte[]> fabric = fabricItemApi();
		InterfaceDefaultConflictRepair repair = repair(fabric);
		byte[] jarItem = game(ITEM);
		byte[] preMixin = withInterface(jarItem, FABRIC_ITEM, "premise: the jar's Item has no FabricItem");

		byte[] fixed = repair.transform("net.minecraft.world.item.Item", preMixin, preMixin);
		ClassNode node = node(fixed);
		MethodNode body = declared(node, BY_STACK);
		assertNotNull(body, "Item must answer the stack-typed remainder through NeoForge's overload, not FabricItem's default");
		MethodInsnNode call = soleCall(body);
		assertEquals(Opcodes.INVOKEVIRTUAL, call.getOpcode());
		assertEquals(ITEM, call.owner);
		assertEquals(REMAINDER + BY_INSTANCE, call.name + call.desc,
				"NeoForge's overload: every default answers self.getCraftingRemainder(), and a NeoForge override is reached too");
		assertSame(jarItem, repair.transform("net.minecraft.world.item.Item", jarItem, jarItem),
				"the jar's own Item has no Fabric default to chain");
		// Written back with the frames it came with: every other method of Item still carries its stack map.
		for (MethodNode method : node.methods) {
			boolean branches = Arrays.stream(method.instructions.toArray()).anyMatch(i -> i instanceof org.objectweb.asm.tree.JumpInsnNode);
			if (branches) assertTrue(Arrays.stream(method.instructions.toArray()).anyMatch(i -> i instanceof org.objectweb.asm.tree.FrameNode),
					method.name + method.desc + " lost its stack map frames");
		}
	}

	@Test void theWovenItemStackSettlesOnFabricsDefault() throws Exception {
		Map<String, byte[]> fabric = fabricItemApi();
		byte[] jarStack = game(ITEM_STACK);
		byte[] woven = withInterface(jarStack, FABRIC_ITEM_STACK, "premise: the jar's ItemStack has no FabricItemStack");
		assertNull(declared(node(jarStack), NO_ARGUMENT), "premise: NeoForge's ItemStack inherits its remainder");

		byte[] fixed = repair(fabric).transform("net.minecraft.world.item.ItemStack", jarStack, woven);
		MethodInsnNode call = soleCall(declared(node(fixed), NO_ARGUMENT));
		assertEquals(Opcodes.INVOKESPECIAL, call.getOpcode());
		assertEquals(FABRIC_ITEM_STACK, call.owner,
				"Fabric's default asks Item.getCraftingRemainder(ItemStack), where a Fabric item's override and Item's chained "
						+ "body both answer; NeoForge's would skip a Fabric item's override");
	}

	/** The chain the two settled bodies rely on, read from the real defaults. */
	@Test void everyRouteEndsInNeoForgesOverloadOrTheItemsOwnRemainder() throws Exception {
		Map<String, byte[]> fabric = fabricItemApi();
		assertEquals(List.of(ITEM + "." + REMAINDER + BY_STACK), remainderCalls(fabric.get(FABRIC_ITEM_STACK), NO_ARGUMENT),
				"FabricItemStack's default asks the Item, stack-typed");
		assertEquals(List.of(ITEM + "." + REMAINDER + NO_ARGUMENT), remainderCalls(fabric.get(FABRIC_ITEM), BY_STACK),
				"FabricItem's default answers the item's own remainder");
		assertEquals(List.of(ITEM + "." + REMAINDER + NO_ARGUMENT),
				remainderCalls(game("net/neoforged/neoforge/common/extensions/IItemExtension"), BY_INSTANCE),
				"IItemExtension's default answers the item's own remainder");
		assertEquals(List.of(ITEM + "." + REMAINDER + BY_INSTANCE),
				remainderCalls(game("net/neoforged/neoforge/common/extensions/ItemInstanceExtension"), NO_ARGUMENT),
				"NeoForge's own ItemStack route asks the overload its items override");
		MethodNode itemsOwn = declared(node(game(ITEM)), NO_ARGUMENT);
		assertNotNull(itemsOwn);
		assertNotEquals(0, itemsOwn.access & Opcodes.ACC_FINAL, "premise: no item overrides the no-argument remainder");
	}

	private static InterfaceDefaultConflictRepair repair(Map<String, byte[]> fabric) {
		return new InterfaceDefaultConflictRepair(name -> fabric.containsKey(name) ? fabric.get(name) : game(name));
	}

	private static byte[] withInterface(byte[] bytes, String iface, String premise) {
		ClassNode node = node(bytes);
		assertFalse(node.interfaces.contains(iface), premise);
		node.interfaces.add(iface);                     // what the class tweaker / the mixin adds
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static List<String> remainderCalls(byte[] bytes, String desc) {
		MethodNode method = declared(node(bytes), desc);
		assertNotNull(method, desc);
		return Arrays.stream(method.instructions.toArray()).filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast)
				.filter(c -> c.name.equals(REMAINDER)).map(c -> c.owner + "." + c.name + c.desc).toList();
	}

	private static MethodNode declared(ClassNode node, String desc) {
		return node.methods.stream().filter(m -> m.name.equals(REMAINDER) && m.desc.equals(desc)).findFirst().orElse(null);
	}

	private static MethodInsnNode soleCall(MethodNode method) {
		assertNotNull(method, "the repair added no body");
		List<AbstractInsnNode> calls = Arrays.stream(method.instructions.toArray()).filter(MethodInsnNode.class::isInstance).toList();
		assertEquals(1, calls.size(), method.name + method.desc);
		return (MethodInsnNode) calls.getFirst();
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] game(String internalName) {
		for (Path jar : JARS) {
			if (!Files.isRegularFile(jar)) continue;
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				ZipEntry entry = zip.getEntry(internalName + ".class");
				if (entry != null) return zip.getInputStream(entry).readAllBytes();
			} catch (java.io.IOException unreadable) {
				throw new java.io.UncheckedIOException(unreadable);
			}
		}
		return null;
	}

	/** FabricItem and FabricItemStack from the actual fabric-api's nested fabric-item-api-v1. */
	private static Map<String, byte[]> fabricItemApi() throws Exception {
		TestFixtures.require(Fixture.THIRD_PARTY, Files.isRegularFile(FABRIC_API), "actual Fabric API required: " + FABRIC_API);
		TestFixtures.requireFile(Fixture.STAGED, JARS.getFirst(), "actual game required: " + JARS.getFirst());
		Map<String, byte[]> out = new HashMap<>();
		try (ZipFile zip = new ZipFile(FABRIC_API.toFile())) {
			ZipEntry nested = zip.stream().filter(e -> e.getName().startsWith("META-INF/jars/fabric-item-api-v1-")).findFirst().orElseThrow();
			try (ZipInputStream in = new ZipInputStream(zip.getInputStream(nested))) {
				for (ZipEntry e; (e = in.getNextEntry()) != null;) {
					String name = e.getName();
					if (name.equals(FABRIC_ITEM + ".class") || name.equals(FABRIC_ITEM_STACK + ".class")) {
						out.put(name.substring(0, name.length() - ".class".length()), in.readAllBytes());
					}
				}
			}
		}
		assertEquals(2, out.size(), "FabricItem and FabricItemStack in fabric-item-api-v1: " + out.keySet());
		return out;
	}
}
