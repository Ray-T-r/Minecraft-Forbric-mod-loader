/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * {@link ForgeModelDataFlowTransformer} over the staged merged base's own classes: the premise each repair answers is
 * read from the bytes first, then the repair's output is checked and verified.
 */
@ResourceLock("system-properties")
class MergedBaseForgeModelDataFlowTest {
	private static final Path MERGED_BASE = TestFixtures.stagedBase();
	private static final String RENDERER = "net/minecraft/client/renderer/block/ModelBlockRenderer";
	private static final String TESSELATE_ARGS = "Lnet/minecraft/client/renderer/block/BlockQuadOutput;FFF"
			+ "Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;"
			+ "Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;J";
	private static final String VANILLA = "(" + TESSELATE_ARGS + ")V";
	private static final String OVERLOAD = "(" + TESSELATE_ARGS + "Lnet/minecraftforge/client/model/data/ModelData;)V";

	@AfterEach void restore() {
		System.clearProperty(ForgeModelDataFlowTransformer.PROPERTY);
	}

	@Test void theMergedModelAnswersNeoForgesContextualQuestionsThroughMinecraftForgesContract() throws Exception {
		byte[] in = merged(ForgeModelDataFlowTransformer.BLOCK_STATE_MODEL);
		ClassNode before = parse(in);
		assertTrue(before.interfaces.containsAll(List.of(ForgeModelDataFlowTransformer.NEO_EXTENSION, ForgeModelDataFlowTransformer.FORGE_EXTENSION)),
				"premise: the merged interface extends both families' model extensions");
		assertNull(ForgeModelDataFlowTransformer.method(before, "collectParts", ForgeModelDataFlowTransformer.COLLECT),
				"premise: it does not answer NeoForge's contextual question itself, so NeoForge's default drops the context");

		ClassNode after = parse(transform(ForgeModelDataFlowTransformer.BLOCK_STATE_MODEL, in));
		MethodNode collect = ForgeModelDataFlowTransformer.method(after, "collectParts", ForgeModelDataFlowTransformer.COLLECT);
		assertNotNull(collect);
		assertEquals(Opcodes.ACC_PUBLIC, collect.access, "an interface default, overridable by any model that answers it itself");
		assertTrue(calls(collect, ForgeModelDataFlowTransformer.HELPER, "collectParts"));
		MethodNode particle = ForgeModelDataFlowTransformer.method(after, "particleMaterial", ForgeModelDataFlowTransformer.PARTICLE);
		assertNotNull(particle);
		assertTrue(calls(particle, ForgeModelDataFlowTransformer.HELPER, "at"));
		assertTrue(calls(particle, ForgeModelDataFlowTransformer.BLOCK_STATE_MODEL, "particleMaterial"));
		verify(after);
	}

	@Test void theConflictResolversParticleAnswerIsReplacedWhicheverRunsFirst() throws Exception {
		byte[] in = merged(ForgeModelDataFlowTransformer.BLOCK_STATE_MODEL);
		// The compat transformer's resolver first: its particleMaterial is the context-free answer, which this replaces.
		byte[] resolved = new ForbricMergedBaseCompatTransformer().transform(
				ForgeModelDataFlowTransformer.BLOCK_STATE_MODEL.replace('/', '.'), in, null);
		ClassNode node = parse(transform(ForgeModelDataFlowTransformer.BLOCK_STATE_MODEL, resolved));
		assertTrue(calls(ForgeModelDataFlowTransformer.method(node, "particleMaterial", ForgeModelDataFlowTransformer.PARTICLE),
				ForgeModelDataFlowTransformer.HELPER, "at"));
		// This first: the resolver finds the method there and leaves it.
		byte[] flowed = transform(ForgeModelDataFlowTransformer.BLOCK_STATE_MODEL, in);
		ClassNode then = parse(new ForbricMergedBaseCompatTransformer().transform(
				ForgeModelDataFlowTransformer.BLOCK_STATE_MODEL.replace('/', '.'), flowed, null));
		assertTrue(calls(ForgeModelDataFlowTransformer.method(then, "particleMaterial", ForgeModelDataFlowTransformer.PARTICLE),
				ForgeModelDataFlowTransformer.HELPER, "at"));
		verify(then);
	}

	@Test void theRendererEntersTheOverloadsEdgeTwinFirst() throws Exception {
		byte[] in = merged(RENDERER);
		ClassNode before = parse(in);
		MethodNode vanilla = ForgeModelDataFlowTransformer.method(before, "tesselateBlock", VANILLA);
		assertNotNull(ForgeModelDataFlowTransformer.method(before, "tesselateBlock", OVERLOAD), "premise: the carrier's overload is there");
		assertFalse(calls(vanilla, RENDERER, "tesselateBlock"), "premise: the vanilla signature carries a body, it does not forward");
		assertEquals(1, ForgeModelDataFlowTransformer.pairs(before).size(), "one model-data overload in the class");

		ClassNode after = parse(transform(RENDERER, in));
		MethodNode twin = ForgeModelDataFlowTransformer.method(after, "tesselateBlock" + ForgeModelDataFlowTransformer.EDGE_SUFFIX, OVERLOAD);
		assertNotNull(twin, "the twin has the overload's exact signature");
		assertEquals(4, real(twin).size(), "and only marks that it was reached");
		MethodNode entered = ForgeModelDataFlowTransformer.method(after, "tesselateBlock", VANILLA);
		List<AbstractInsnNode> head = real(entered).subList(0, 27);
		assertTrue(head.stream().anyMatch(i -> i instanceof MethodInsnNode c && c.name.equals(twin.name)), "the vanilla signature enters it first");
		assertTrue(head.stream().anyMatch(i -> i instanceof MethodInsnNode c && c.owner.equals(ForgeModelDataFlowTransformer.HELPER) && c.name.equals("of")));
		assertEquals(before.methods.stream().filter(m -> m.name.equals("tesselateBlock")).count(),
				after.methods.stream().filter(m -> m.name.equals("tesselateBlock")).count(), "both bodies are kept");
		verify(after);
		byte[] once = transform(RENDERER, in);
		assertSame(once, transform(RENDERER, once), "a second pass finds the twin and leaves the class alone");
	}

	@Test void theLevelKeepsMinecraftForgesManagerWhereTheMergeDroppedIt() throws Exception {
		byte[] in = merged(ForgeModelDataFlowTransformer.CLIENT_LEVEL);
		assertSame(in, transform(ForgeModelDataFlowTransformer.CLIENT_LEVEL, in),
				"this base keeps MinecraftForge's own manager and accessor: nothing to add");

		// A merge that lost them — MinecraftForge's accessor, field and construction taken out of this class — gets them back.
		ClassNode lost = parse(in);
		lost.methods.removeIf(m -> m.name.equals("getModelDataManager") && m.desc.equals(ForgeModelDataFlowTransformer.MANAGER_GETTER));
		lost.fields.removeIf(f -> f.desc.equals("L" + ForgeModelDataFlowTransformer.MANAGER + ";"));
		for (MethodNode ctor : lost.methods) {
			if (!ctor.name.equals("<init>")) continue;
			for (AbstractInsnNode insn : ctor.instructions.toArray()) {
				if (insn instanceof FieldInsnNode put && put.getOpcode() == Opcodes.PUTFIELD
						&& put.desc.equals("L" + ForgeModelDataFlowTransformer.MANAGER + ";")) {
					// aload_0; new; dup; aload_0; invokespecial; putfield
					AbstractInsnNode at = put;
					for (int i = 0; i < 5; i++) at = at.getPrevious();
					for (int i = 0; i < 6; i++) { AbstractInsnNode next = at.getNext(); ctor.instructions.remove(at); at = next; }
				}
			}
		}
		ClassNode restored = parse(transform(ForgeModelDataFlowTransformer.CLIENT_LEVEL, write(lost)));
		assertNotNull(ForgeModelDataFlowTransformer.method(restored, "getModelDataManager", ForgeModelDataFlowTransformer.MANAGER_GETTER));
		MethodNode ctor = restored.methods.stream().filter(m -> m.name.equals("<init>")).findFirst().orElseThrow();
		assertTrue(calls(ctor, ForgeModelDataFlowTransformer.MANAGER, "<init>"), "built in the constructor, as MinecraftForge builds it");
		verify(restored);
	}

	/** Each repair is its own claim: applied, or satisfied by a class that already carries it (this base's level). */
	@Test void everyClaimIsReportedWhereItsTargetIsHandedOver() throws Exception {
		ForgeModelDataFlowTransformer flow = new ForgeModelDataFlowTransformer();
		assertTrue(flow.anchors().anchors().isEmpty() && flow.anchors().scanNote() != null, "the repairs are claimed one by one, not as one class-level anchor");
		java.util.Set<String> declared = new java.util.TreeSet<>();
		for (ClassTransformer.Claim claim : flow.claims()) declared.add(claim.id());
		assertEquals(3, declared.size(), declared.toString());
		java.util.Set<String> hit = new java.util.TreeSet<>();
		for (String owner : List.of(ForgeModelDataFlowTransformer.BLOCK_STATE_MODEL, RENDERER, ForgeModelDataFlowTransformer.CLIENT_LEVEL)) {
			flow.transform(owner.replace('/', '.'), merged(owner), null, hit::add);
		}
		assertEquals(declared, hit, "the level keeps MinecraftForge's own manager on this base: satisfied, not missed");
	}

	@Test void offLeavesEveryClassAsMerged() throws Exception {
		System.setProperty(ForgeModelDataFlowTransformer.PROPERTY, "off");
		for (String owner : List.of(ForgeModelDataFlowTransformer.BLOCK_STATE_MODEL, RENDERER, ForgeModelDataFlowTransformer.CLIENT_LEVEL)) {
			byte[] in = merged(owner);
			assertSame(in, transform(owner, in), owner);
		}
	}

	private static byte[] transform(String owner, byte[] bytes) {
		return new ForgeModelDataFlowTransformer().transform(owner.replace('/', '.'), bytes, null);
	}

	private static byte[] merged(String owner) throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		try (ZipFile zip = new ZipFile(MERGED_BASE.toFile())) {
			ZipEntry entry = zip.getEntry(owner + ".class");
			assertNotNull(entry, owner + " missing from the staged merged base");
			try (InputStream in = zip.getInputStream(entry)) {
				return in.readAllBytes();
			}
		}
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] write(ClassNode node) {
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static boolean calls(MethodNode method, String owner, String name) {
		if (method == null) return false;
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) return true;
		}
		return false;
	}

	private static List<AbstractInsnNode> real(MethodNode method) {
		return java.util.Arrays.stream(method.instructions.toArray()).filter(i -> i.getOpcode() >= 0).toList();
	}

	/** Every method of the class still type-checks after a write and re-read (the transformer computes no frames). */
	private static void verify(ClassNode node) throws Exception {
		ClassNode reread = parse(write(node));
		for (MethodNode m : reread.methods) {
			if ((m.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
			new Analyzer<>(new BasicVerifier()).analyze(reread.name, m);
		}
	}
}
