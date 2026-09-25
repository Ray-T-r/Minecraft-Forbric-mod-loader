package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * fabric-item-api-v1's class tweaker injects FabricItem into the merged Item, which already has MinecraftForge's
 * IForgeItem: both default getCraftingRemainder(ItemStack), and every craft, brew and fuel threw. On the real classes.
 */
class CraftingRemainderConflictTest {
	private static final Path STAGED = Path.of(System.getProperty("forbric.stagedRoot", "../forbric-loader/run"));
	private static final List<Path> JARS = List.of(STAGED.resolve("merged-base/patched-mc-merged-26.2.jar"),
			STAGED.resolve("merged-base/forge-runtime-interop.jar"), STAGED.resolve("neoforge-runtime/neoforge-runtime.jar"));
	private static final Path FABRIC_API = Path.of("run/client-merged-pack/mods/fabric-api-0.155.2+26.2.jar");
	private static final String ITEM = "net/minecraft/world/item/Item";
	private static final String FABRIC_ITEM = "net/fabricmc/fabric/api/item/v1/FabricItem";
	private static final String REMAINDER = "getCraftingRemainder";

	@Test void theTweakedItemGetsARemainderThatAsksNeoForgesOverload() throws Exception {
		byte[] fabricItem = fabricItem();
		InterfaceDefaultConflictRepair repair = new InterfaceDefaultConflictRepair(name -> name.equals(FABRIC_ITEM) ? fabricItem : game(name));
		byte[] jarItem = game(ITEM);
		ClassNode tweaked = new ClassNode();
		new ClassReader(jarItem).accept(tweaked, 0);
		assertFalse(tweaked.interfaces.contains(FABRIC_ITEM), "premise: the merged jar's Item has no FabricItem");
		tweaked.interfaces.add(FABRIC_ITEM);                     // what the class tweaker does in the pre-mixin chain
		ClassWriter writer = new ClassWriter(0);
		tweaked.accept(writer);
		byte[] preMixin = writer.toByteArray();

		byte[] fixed = repair.transform("net.minecraft.world.item.Item", preMixin, preMixin);
		ClassNode node = new ClassNode();
		new ClassReader(fixed).accept(node, 0);
		MethodNode override = node.methods.stream().filter(m -> m.name.equals(REMAINDER)
				&& m.desc.equals("(Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/ItemStackTemplate;")).findFirst().orElse(null);
		assertNotNull(override, "Item must settle the conflict it was given");
		List<AbstractInsnNode> calls = Arrays.stream(override.instructions.toArray()).filter(MethodInsnNode.class::isInstance).toList();
		assertEquals(1, calls.size());
		MethodInsnNode call = (MethodInsnNode) calls.getFirst();
		assertEquals(ITEM, call.owner);
		assertEquals("(Lnet/minecraft/world/item/ItemInstance;)Lnet/minecraft/world/item/ItemStackTemplate;", call.desc,
				"NeoForge's overload: every default answers self.getCraftingRemainder(), and a NeoForge override is reached too");
		assertSame(jarItem, repair.transform("net.minecraft.world.item.Item", jarItem, jarItem), "the jar's own Item has no conflict");
		// Written back with the frames it came with: every other method of Item still carries its stack map.
		for (MethodNode method : node.methods) {
			boolean branches = Arrays.stream(method.instructions.toArray()).anyMatch(i -> i instanceof org.objectweb.asm.tree.JumpInsnNode);
			if (branches) assertTrue(Arrays.stream(method.instructions.toArray()).anyMatch(i -> i instanceof org.objectweb.asm.tree.FrameNode),
					method.name + method.desc + " lost its stack map frames");
		}
	}

	@Test void allThreeDefaultsAnswerTheSameNoArgumentCall() throws Exception {
		for (byte[] iface : List.of(fabricItem(), game("net/minecraftforge/common/extensions/IForgeItem"),
				game("net/neoforged/neoforge/common/extensions/IItemExtension"))) {
			ClassNode node = new ClassNode();
			new ClassReader(iface).accept(node, 0);
			MethodNode remainder = node.methods.stream().filter(m -> m.name.equals(REMAINDER) && m.desc.startsWith("(Lnet/minecraft/world/item/Item"))
					.findFirst().orElseThrow();
			List<MethodInsnNode> calls = Arrays.stream(remainder.instructions.toArray()).filter(MethodInsnNode.class::isInstance)
					.map(MethodInsnNode.class::cast).filter(c -> c.name.equals(REMAINDER)).toList();
			assertEquals(1, calls.size(), node.name);
			assertEquals(ITEM, calls.getFirst().owner, node.name);
			assertEquals("()Lnet/minecraft/world/item/ItemStackTemplate;", calls.getFirst().desc, node.name);
		}
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

	private static byte[] fabricItem() throws Exception {
		Assumptions.assumeTrue(Files.isRegularFile(FABRIC_API) && Files.isRegularFile(JARS.getFirst()), "actual game and Fabric API required");
		try (ZipFile zip = new ZipFile(FABRIC_API.toFile())) {
			ZipEntry nested = zip.stream().filter(e -> e.getName().startsWith("META-INF/jars/fabric-item-api-v1-")).findFirst().orElseThrow();
			try (ZipInputStream in = new ZipInputStream(zip.getInputStream(nested))) {
				for (ZipEntry e; (e = in.getNextEntry()) != null;) if (e.getName().equals(FABRIC_ITEM + ".class")) return in.readAllBytes();
			}
		}
		throw new AssertionError("FabricItem not found");
	}
}
