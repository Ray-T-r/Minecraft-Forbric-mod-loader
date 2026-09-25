package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/** The Ender Dragon's parts, NeoForge-typed again on the merged base; on the real classes. */
@ResourceLock("system-properties")
class DragonPartsInjectorTest {
	private static final Path STAGED = Path.of(System.getProperty("forbric.stagedRoot", "../forbric-loader/run"));
	private static final Path MERGED = STAGED.resolve("merged-base/patched-mc-merged-26.2.jar");
	private static final Path NEO = STAGED.resolve("neoforge-patched/patched-mc-neoforge-26.2.jar");
	private static final String PART = "net/minecraft/world/entity/boss/enderdragon/EnderDragonPart";
	private static final String DRAGON = "net/minecraft/world/entity/boss/enderdragon/EnderDragon";
	private static final String HITBOXES = "net/minecraft/client/renderer/debug/EntityHitboxDebugRenderer";

	@AfterEach void reset() { System.clearProperty(DragonPartsInjector.PROPERTY); }

	@Test void thePartIsANeoForgePartEntity() throws Exception {
		byte[] original = NativeCoremodParityTest.read(MERGED, PART);
		assertEquals(DragonPartsInjector.FORGE_PART, node(original).superName, "premise: the merge put it under MinecraftForge's");
		byte[] out = new DragonPartsInjector().transform(DragonPartsInjector.PART, original, null);
		ClassNode part = node(out);
		assertEquals(DragonPartsInjector.NEO_PART, part.superName);
		assertTrue(part.signature.startsWith("L" + DragonPartsInjector.NEO_PART + "<"), part.signature);
		MethodNode ctor = part.methods.stream().filter(m -> m.name.equals("<init>")).findFirst().orElseThrow();
		assertTrue(Arrays.stream(ctor.instructions.toArray()).anyMatch(i -> i instanceof MethodInsnNode c && c.owner.equals(DragonPartsInjector.NEO_PART)
				&& c.name.equals("<init>")), "its super constructor call is NeoForge's");
		assertFalse(new String(out, java.nio.charset.StandardCharsets.ISO_8859_1).contains(DragonPartsInjector.FORGE_PART), "no Forge-typed reference is left");
		assertSame(out, new DragonPartsInjector().transform(DragonPartsInjector.PART, out, null));
	}

	@Test void theDragonAnswersNeoForgesGetPartsAndMinecraftForgesWithNothing() throws Exception {
		byte[] out = new DragonPartsInjector().transform(DragonPartsInjector.DRAGON, NativeCoremodParityTest.read(MERGED, DRAGON), null);
		ClassNode dragon = node(out);
		MethodNode neo = method(dragon, "getParts", DragonPartsInjector.NEO_GET_PARTS);
		assertTrue(Arrays.stream(neo.instructions.toArray()).anyMatch(i -> i instanceof FieldInsnNode f && f.name.equals("subEntities")));
		MethodNode forge = method(dragon, "getParts", DragonPartsInjector.FORGE_GET_PARTS);
		List<AbstractInsnNode> real = Arrays.stream(forge.instructions.toArray()).filter(i -> i.getOpcode() >= 0).toList();
		assertEquals(List.of(Opcodes.ICONST_0, Opcodes.ANEWARRAY, Opcodes.ARETURN), real.stream().map(AbstractInsnNode::getOpcode).toList(),
				"no Forge-typed part exists any more: an empty array, which every Forge-typed caller in the game handles");
		new Analyzer<>(new BasicVerifier()).analyze(DRAGON, neo);
		new Analyzer<>(new BasicVerifier()).analyze(DRAGON, forge);
		assertSame(out, new DragonPartsInjector().transform(DragonPartsInjector.DRAGON, out, null));
	}

	@Test void theDebugHitboxesReadNeoForgesParts() throws Exception {
		byte[] out = new DragonPartsInjector().transform(DragonPartsInjector.HITBOXES, NativeCoremodParityTest.read(MERGED, HITBOXES), null);
		assertFalse(new String(out, java.nio.charset.StandardCharsets.ISO_8859_1).contains(DragonPartsInjector.FORGE_PART));
		MethodNode show = node(out).methods.stream().filter(m -> m.name.equals("showHitboxes")).findFirst().orElseThrow();
		assertTrue(Arrays.stream(show.instructions.toArray()).anyMatch(i -> i instanceof MethodInsnNode c && c.name.equals("getParts")
				&& c.desc.equals(DragonPartsInjector.NEO_GET_PARTS)));
		new Analyzer<>(new BasicVerifier()).analyze(HITBOXES, show);
	}

	@Test void neoForgesOwnClassesAreLeftAlone() throws Exception {
		for (String name : List.of(PART, DRAGON)) {
			byte[] own = NativeCoremodParityTest.read(NEO, name);
			assertSame(own, new DragonPartsInjector().transform(name.replace('/', '.'), own, null), name);
		}
	}

	@Test void theSwitchLeavesAllThreeAlone() throws Exception {
		System.setProperty(DragonPartsInjector.PROPERTY, "off");
		for (String name : List.of(PART, DRAGON, HITBOXES)) {
			byte[] merged = NativeCoremodParityTest.read(MERGED, name);
			assertSame(merged, new DragonPartsInjector().transform(name.replace('/', '.'), merged, null), name);
		}
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElseThrow(() -> new AssertionError(name + desc));
	}
}
