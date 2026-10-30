/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * {@link BlockItemLinkInjector} on the real carriers: MinecraftForge's item callbacks hand their live registry's
 * block→item map to the kernel when it is created and when it is cleared, and the map the kernel hands back is the one
 * the merged {@code Block.asItem()} reads — as on MinecraftForge, where {@code Item.BY_BLOCK} is that registry's map.
 *
 * <p>{@code BlockItemLinkInjectorExecutionTest} runs the edit; this pins it to the staged artifacts.
 */
@ResourceLock("system-properties")
class BlockItemLinkInjectorTest {
	private static final Path STAGED = TestFixtures.stagedRoot();
	private static final Path MERGED = STAGED.resolve(TestFixtures.BASE_JAR);
	private static final Path FORGE_CARRIER = STAGED.resolve(TestFixtures.FORGE_INTEROP_JAR);
	private static final Path FORGE_GAME = STAGED.resolve("forge-patched/patched-mc-forge-26.2.jar");
	private static final String CALLBACKS = "net/minecraftforge/registries/GameData$ItemCallbacks";
	private static final String ITEM = "net/minecraft/world/item/Item";
	private static final String NEO_GAME_DATA = "net/neoforged/neoforge/registries/GameData";
	private static final String REGISTRY_MANAGER = "net/minecraftforge/registries/RegistryManager";
	private static final String MAP_DESC = "Ljava/util/Map;";

	@AfterEach void reset() { System.clearProperty(BlockItemLinkInjector.PROPERTY); }

	/**
	 * The premise, on MinecraftForge's own game: {@code Item.BY_BLOCK} is taken from {@code ItemCallbacks.getBlockItemMap()},
	 * which is the ACTIVE item registry's slave map under the key the add-callback links into. The merged {@code Item}
	 * takes it from NeoForge instead, and the merged {@code Block.asItem()} reads it through {@code Item.byBlock}.
	 */
	@Test void onMinecraftForgeItemByBlockIsTheLiveRegistrysMapAndTheMergedItemTakesNeoForges() throws Exception {
		ClassNode forgeItem = node(NativeCoremodParityTest.read(FORGE_GAME, ITEM));
		assertEquals(CALLBACKS + ".getBlockItemMap", byBlockSource(forgeItem), "MinecraftForge's Item.BY_BLOCK");
		ClassNode callbacks = node(NativeCoremodParityTest.read(FORGE_CARRIER, CALLBACKS));
		MethodNode getBlockItemMap = method(callbacks, "getBlockItemMap", "()" + MAP_DESC);
		assertTrue(reads(getBlockItemMap, REGISTRY_MANAGER, "ACTIVE"), "is the ACTIVE registry's map");
		assertTrue(reads(getBlockItemMap, CALLBACKS, "BLOCK_TO_ITEM"), "under the add-callback's key");
		assertEquals(Set.of("BLOCK_TO_ITEM"), BlockItemLinkInjector.blockToItemKeys(callbacks), "the key the add-callback links into");

		ClassNode mergedItem = node(NativeCoremodParityTest.read(MERGED, ITEM));
		assertEquals(NEO_GAME_DATA + ".getBlockItemMap", byBlockSource(mergedItem), "the merged Item.BY_BLOCK is NeoForge's map");
		ClassNode block = node(NativeCoremodParityTest.read(MERGED, "net/minecraft/world/level/block/Block"));
		assertTrue(calls(method(block, "asItem", "()L" + ITEM + ";"), ITEM, "byBlock"), "merged Block.asItem() asks Item.byBlock");
		assertTrue(reads(method(mergedItem, "byBlock", "(Lnet/minecraft/world/level/block/Block;)L" + ITEM + ";"), ITEM, "BY_BLOCK"),
				"which reads Item.BY_BLOCK");
	}

	/** The hook hands the ACTIVE registry exactly the map the merged {@code Item.BY_BLOCK} is taken from. */
	@Test void theHookHandsTheLiveRegistryTheMapTheMergedItemReads() throws Exception {
		ClassNode mergedItem = node(NativeCoremodParityTest.read(MERGED, ITEM));
		assertEquals(NEO_GAME_DATA + ".getBlockItemMap", byBlockSource(mergedItem));
		Path hook = Path.of(System.getProperty("forbric.testRuntimeClasses", "build/classes/java/runtime"),
				BlockItemLinkInjector.HOOK_OWNER + ".class");
		TestFixtures.require(Fixture.GAME_SIDE, Files.isRegularFile(hook), "compiled game side: " + hook);
		ClassNode node = node(Files.readAllBytes(hook));
		for (String[] call : List.of(new String[] {BlockItemLinkInjector.MAP_HOOK, BlockItemLinkInjector.MAP_HOOK_DESC},
				new String[] {BlockItemLinkInjector.CLEAR_HOOK, BlockItemLinkInjector.CLEAR_HOOK_DESC})) {
			MethodNode hooked = method(node, call[0], call[1]);
			assertNotNull(hooked, call[0] + " exists with the descriptor the callbacks call it with");
			assertTrue(reads(hooked, REGISTRY_MANAGER, "ACTIVE"), call[0] + " tells the live registry from the other stages");
			assertTrue(calls(hooked, NEO_GAME_DATA, "getBlockItemMap"), call[0] + " knows the map the merged Item.BY_BLOCK is");
		}
	}

	@Test void onCreateAndOnClearGoThroughTheKernelForTheBlockToItemKey() throws Exception {
		byte[] callbacks = NativeCoremodParityTest.read(FORGE_CARRIER, CALLBACKS);
		byte[] out = new BlockItemLinkInjector().transform(BlockItemLinkInjector.TARGET, callbacks, null);
		assertNotSame(callbacks, out);
		ClassNode node = node(out);

		List<AbstractInsnNode> create = real(method(node, "onCreate", BlockItemLinkInjector.CALLBACK_DESC));
		int setSlaveMap = indexOf(create, "setSlaveMap");
		assertTrue(setSlaveMap > 0, "onCreate still installs the map");
		assertStageThenHook(create, setSlaveMap - 2, BlockItemLinkInjector.MAP_HOOK, BlockItemLinkInjector.MAP_HOOK_DESC,
				"the map MinecraftForge built goes through the kernel with the stage, right before it is installed");
		assertTrue(create.get(1) instanceof FieldInsnNode key && key.name.equals("BLOCK_TO_ITEM"), "under the block_to_item key");

		List<AbstractInsnNode> clear = real(method(node, "onClear", BlockItemLinkInjector.CALLBACK_DESC));
		assertEquals(-1, indexOf(clear, "clear", "java/util/Map"), "MinecraftForge's own Map.clear() is gone");
		int routed = indexOf(clear, BlockItemLinkInjector.CLEAR_HOOK, BlockItemLinkInjector.HOOK_OWNER);
		assertStageThenHook(clear, routed - 1, BlockItemLinkInjector.CLEAR_HOOK, BlockItemLinkInjector.CLEAR_HOOK_DESC,
				"the clear goes through the kernel with the stage");

		for (String name : List.of("onCreate", "onClear")) {
			new Analyzer<>(new BasicVerifier()).analyze(CALLBACKS, method(node, name, BlockItemLinkInjector.CALLBACK_DESC));
		}
		assertSame(out, new BlockItemLinkInjector().transform(BlockItemLinkInjector.TARGET, out, null), "a second pass edits nothing");
	}

	@Test void theSwitchLeavesTheCallbacksAlone() throws Exception {
		System.setProperty(BlockItemLinkInjector.PROPERTY, "off");
		byte[] callbacks = NativeCoremodParityTest.read(FORGE_CARRIER, CALLBACKS);
		assertSame(callbacks, new BlockItemLinkInjector().transform(BlockItemLinkInjector.TARGET, callbacks, null));
		assertFalse(BlockItemLinkInjector.enabled());
	}

	/** The owner and name of the static call whose result {@code <clinit>} stores in {@code Item.BY_BLOCK}. */
	private static String byBlockSource(ClassNode item) {
		List<AbstractInsnNode> code = real(method(item, "<clinit>", "()V"));
		for (int i = 1; i < code.size(); i++) {
			if (code.get(i) instanceof FieldInsnNode put && put.getOpcode() == Opcodes.PUTSTATIC && put.name.equals("BY_BLOCK")
					&& code.get(i - 1) instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC) {
				return call.owner + "." + call.name;
			}
		}
		return null;
	}

	private static void assertStageThenHook(List<AbstractInsnNode> code, int at, String hook, String desc, String what) {
		assertTrue(at >= 0 && code.get(at) instanceof VarInsnNode stage && stage.getOpcode() == Opcodes.ALOAD && stage.var == 2,
				what + ": the stage");
		assertTrue(code.get(at + 1) instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
				&& call.owner.equals(BlockItemLinkInjector.HOOK_OWNER) && call.name.equals(hook) && call.desc.equals(desc), what + ": the hook");
	}

	private static int indexOf(List<AbstractInsnNode> code, String name) {
		return indexOf(code, name, null);
	}

	private static int indexOf(List<AbstractInsnNode> code, String name, String owner) {
		for (int i = 0; i < code.size(); i++) {
			if (code.get(i) instanceof MethodInsnNode call && call.name.equals(name) && (owner == null || call.owner.equals(owner))) return i;
		}
		return -1;
	}

	private static boolean calls(MethodNode method, String owner, String name) {
		for (AbstractInsnNode insn : method.instructions) if (insn instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) return true;
		return false;
	}

	private static boolean reads(MethodNode method, String owner, String field) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof FieldInsnNode get && get.getOpcode() == Opcodes.GETSTATIC && get.owner.equals(owner)
					&& get.name.equals(field)) return true;
		}
		return false;
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElse(null);
	}

	private static List<AbstractInsnNode> real(MethodNode method) {
		List<AbstractInsnNode> out = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) if (insn.getOpcode() >= 0) out.add(insn);
		return out;
	}
}
