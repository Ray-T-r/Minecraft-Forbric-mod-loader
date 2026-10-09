/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.fabricmc.api.EnvType;
import net.forbric.kernel.GateLogContract;
import net.forbric.kernel.TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * The client part-tracking repair is judged by its end state, not by whether it edited. Which family's
 * {@code onTrackingStart} the merge keeps moves with the merge tools: the staged base keeps NeoForge's body, which
 * already registers a NeoForge mod's parts in {@code dragonParts} and never reads MinecraftForge's array, and the ledger
 * used to score "handed the class, made no edit" as a missing repair there (gate-m9's two anchor checks went red).
 *
 * <p>The claim holds on every body that tracks NeoForge's parts and null-tests MinecraftForge's: NeoForge's own,
 * this repair's edit of MinecraftForge's, and a composed body written another way. It does not hold on a body that
 * still dereferences MinecraftForge's array unchecked, nor on one that never registers NeoForge's parts; those stay
 * misses, and the ledger prints the line the gate looks for.
 */
@ResourceLock("system-properties")
class ClientPartTrackingClaimTest {
	private static final Path STAGED = TestFixtures.stagedRoot();
	private static final Path MERGED = STAGED.resolve("merged-base/patched-mc-merged-26.2.jar");
	private static final Path FORGE = STAGED.resolve("forge-patched/patched-mc-forge-26.2.jar");
	private static final Path NEO = STAGED.resolve("neoforge-patched/patched-mc-neoforge-26.2.jar");
	private static final Path GATE = Path.of("run/gate-m9-client.sh");
	private static final String CALLBACKS = ClientPartTrackingInjector.CALLBACKS;
	private static final String CALLBACKS_INTERNAL = ClientPartTrackingInjector.CALLBACKS_INTERNAL;
	private static final String LEVEL = ClientPartTrackingInjector.LEVEL;
	private static final String ENTITY = ClientPartTrackingInjector.ENTITY;
	private static final String START = ClientPartTrackingInjector.TRACKING_START_DESC;
	private static final String FORGE_GET_PARTS = ClientPartTrackingInjector.FORGE_GET_PARTS;
	private static final String NEO_GET_PARTS = ClientPartTrackingInjector.NEO_GET_PARTS;
	private static final String FORGE_PART = DragonPartsInjector.FORGE_PART;
	private static final String NEO_PART = DragonPartsInjector.NEO_PART;
	private static final String PARTS_MAP = "it/unimi/dsi/fastutil/ints/Int2ObjectMap";
	private static final TransformContext CLIENT = new TransformContext(EnvType.CLIENT, false, "intermediary");

	@TempDir Path root;

	@AfterEach void reset() { System.clearProperty(ClientPartTrackingInjector.PROPERTY); }

	@Test void theStagedNeoForgeBodyLandsTheClaimWithoutAnEditAndTheLedgerIsClean() throws Exception {
		byte[] merged = NativeCoremodParityTest.read(MERGED, CALLBACKS_INTERNAL);
		assertTrue(ClientPartTrackingInjector.tracksNeoForgeParts(merged), "premise: the staged merge keeps a body that tracks NeoForge's parts");
		TransformChain chain = chain();
		String[] out = new String[1];
		byte[] result = GateLogContract.capture(() -> chain.applyBeforeMixin(CALLBACKS, merged, CLIENT), out);
		assertSame(merged, result, "there is nothing to edit");
		AnchorLedger.Report report = chain.ledger().report();
		assertTrue(report.clean(), () -> "the end state holds, so nothing is missing: " + report.misses());
		assertEquals(1, report.hit(), "the claim is the one anchor, and it landed");
		assertEquals(0, GateLogContract.count(root, gatePattern("no repair was handed its target and declined"), out[0]), out[0]);
	}

	@Test void neoForgesOwnBodyIsTheEndStateToo() throws Exception {
		byte[] own = NativeCoremodParityTest.read(NEO, CALLBACKS_INTERNAL);
		assertEquals(Set.of(ClientPartTrackingInjector.CLAIM), hits(own));
	}

	@Test void minecraftForgesOwnBodyIsNotTheEndStateUntilTheRepairEditsIt() throws Exception {
		byte[] forge = NativeCoremodParityTest.read(FORGE, CALLBACKS_INTERNAL);
		assertFalse(ClientPartTrackingInjector.tracksNeoForgeParts(forge), "MinecraftForge's body dereferences its own array, null for a NeoForge mod's entity");
		Set<String> hits = new HashSet<>();
		byte[] repaired = new ClientPartTrackingInjector().transform(CALLBACKS, forge, null, hits::add);
		assertNotSame(forge, repaired, "the repair edits MinecraftForge's body");
		assertEquals(Set.of(ClientPartTrackingInjector.CLAIM), hits);
		assertTrue(ClientPartTrackingInjector.tracksNeoForgeParts(repaired));
	}

	/**
	 * A body that composes both families another way: MinecraftForge's array tested as it is stored
	 * ({@code if ((parts = e.getParts()) != null)}), NeoForge's handed to dragonParts through requireNonNullElse.
	 */
	@Test void aComposedBodyWrittenAnotherWayLandsTheClaimWithoutAnEdit() throws Exception {
		byte[] composed = callbacks(Shape.COMPOSED_TESTED_AS_STORED);
		Set<String> hits = new HashSet<>();
		assertSame(composed, new ClientPartTrackingInjector().transform(CALLBACKS, composed, null, hits::add));
		assertEquals(Set.of(ClientPartTrackingInjector.CLAIM), hits);
	}

	/** It reads NeoForge's parts into dragonParts, but still walks MinecraftForge's array unchecked: a NeoForge mod's entity throws. */
	@Test void aLookAlikeThatStillWalksMinecraftForgesArrayUncheckedIsAMissTheGateSees() throws Exception {
		byte[] lookAlike = callbacks(Shape.BOTH_READS_FORGE_UNCHECKED);
		TransformChain chain = chain();
		String[] out = new String[1];
		assertSame(lookAlike, GateLogContract.capture(() -> chain.applyBeforeMixin(CALLBACKS, lookAlike, CLIENT), out));
		AnchorLedger.Report report = chain.ledger().report();
		assertEquals(List.of(ClientPartTrackingInjector.CLAIM), report.misses().stream().map(AnchorLedger.Miss::transformer).toList());
		assertEquals(1, GateLogContract.count(root, gatePattern("no repair was handed its target and declined"), out[0]), out[0]);
	}

	@Test void aBodyThatNeverRegistersNeoForgesPartsIsAMiss() throws Exception {
		assertEquals(Set.of(), hits(callbacks(Shape.FORGE_TESTED_NEO_NEVER_READ)));
		assertEquals(Set.of(), hits(callbacks(Shape.NEO_READ_NOT_REGISTERED)));
	}

	@Test void switchedOffTheRepairClaimsNothing() {
		System.setProperty(ClientPartTrackingInjector.PROPERTY, "off");
		assertEquals(List.of(), new ClientPartTrackingInjector().claims());
		assertTrue(new ClientPartTrackingInjector().anchors().anchors().isEmpty());
	}

	// ---------------------------------------------------------------------------------------------------------------

	private static Set<String> hits(byte[] callbacks) {
		Set<String> hits = new HashSet<>();
		new ClientPartTrackingInjector().transform(CALLBACKS, callbacks, null, hits::add);
		return hits;
	}

	private static TransformChain chain() {
		TransformChain chain = new TransformChain();
		chain.register(TransformPhase.COREMOD, new ClientPartTrackingInjector());
		return chain;
	}

	private static String gatePattern(String what) throws Exception { return GateLogContract.pattern(GATE, what); }

	private enum Shape { COMPOSED_TESTED_AS_STORED, BOTH_READS_FORGE_UNCHECKED, FORGE_TESTED_NEO_NEVER_READ, NEO_READ_NOT_REGISTERED }

	/**
	 * ClientLevel$EntityCallbacks with only {@code this$0} and {@code onTrackingStart(Entity)}, written for a shape. The
	 * class is only read back, never loaded, so it carries no frames.
	 */
	private static byte[] callbacks(Shape shape) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_SUPER, CALLBACKS_INTERNAL, null, "java/lang/Object", null);
		cw.visitField(Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC, "this$0", "L" + LEVEL + ";", null, null).visitEnd();
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "onTrackingStart", START, null, null);
		mv.visitCode();
		Label done = new Label();
		mv.visitVarInsn(Opcodes.ALOAD, 1);
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ENTITY, "isMultipartEntity", "()Z", false);
		mv.visitJumpInsn(Opcodes.IFEQ, done);
		boolean forge = shape != Shape.NEO_READ_NOT_REGISTERED;
		if (forge) {
			mv.visitVarInsn(Opcodes.ALOAD, 1);
			mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ENTITY, "getParts", FORGE_GET_PARTS, false);
			if (shape == Shape.BOTH_READS_FORGE_UNCHECKED) {
				mv.visitVarInsn(Opcodes.ASTORE, 2);
			} else {
				// if ((parts = entity.getParts()) != null)
				mv.visitInsn(Opcodes.DUP);
				mv.visitVarInsn(Opcodes.ASTORE, 2);
				mv.visitJumpInsn(Opcodes.IFNULL, done);
			}
			forgeLoop(mv);
		}
		if (shape == Shape.COMPOSED_TESTED_AS_STORED || shape == Shape.BOTH_READS_FORGE_UNCHECKED) {
			// this$0.dragonParts.addAll(Arrays.asList(requireNonNullElse(entity.getParts(), new PartEntity[0])))
			mv.visitVarInsn(Opcodes.ALOAD, 0);
			mv.visitFieldInsn(Opcodes.GETFIELD, CALLBACKS_INTERNAL, "this$0", "L" + LEVEL + ";");
			mv.visitFieldInsn(Opcodes.GETFIELD, LEVEL, "dragonParts", "Ljava/util/List;");
			mv.visitVarInsn(Opcodes.ALOAD, 1);
			mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ENTITY, "getParts", NEO_GET_PARTS, false);
			mv.visitInsn(Opcodes.ICONST_0);
			mv.visitTypeInsn(Opcodes.ANEWARRAY, NEO_PART);
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Objects", "requireNonNullElse", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
			mv.visitTypeInsn(Opcodes.CHECKCAST, "[Ljava/lang/Object;");
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Arrays", "asList", "([Ljava/lang/Object;)Ljava/util/List;", false);
			mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "addAll", "(Ljava/util/Collection;)Z", true);
			mv.visitInsn(Opcodes.POP);
		} else if (shape == Shape.NEO_READ_NOT_REGISTERED) {
			// NeoForge's parts are read, but only counted: nothing reaches dragonParts.
			mv.visitVarInsn(Opcodes.ALOAD, 1);
			mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ENTITY, "getParts", NEO_GET_PARTS, false);
			mv.visitInsn(Opcodes.ARRAYLENGTH);
			mv.visitInsn(Opcodes.POP);
		}
		mv.visitLabel(done);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** {@code for (int i = 0; i < parts.length; i++) this$0.partEntities.put(parts[i].getId(), parts[i]);} over local 2. */
	private static void forgeLoop(MethodVisitor mv) {
		Label head = new Label(), exit = new Label();
		mv.visitInsn(Opcodes.ICONST_0);
		mv.visitVarInsn(Opcodes.ISTORE, 3);
		mv.visitLabel(head);
		mv.visitVarInsn(Opcodes.ILOAD, 3);
		mv.visitVarInsn(Opcodes.ALOAD, 2);
		mv.visitInsn(Opcodes.ARRAYLENGTH);
		mv.visitJumpInsn(Opcodes.IF_ICMPGE, exit);
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitFieldInsn(Opcodes.GETFIELD, CALLBACKS_INTERNAL, "this$0", "L" + LEVEL + ";");
		mv.visitFieldInsn(Opcodes.GETFIELD, LEVEL, "partEntities", "L" + PARTS_MAP + ";");
		mv.visitVarInsn(Opcodes.ALOAD, 2);
		mv.visitVarInsn(Opcodes.ILOAD, 3);
		mv.visitInsn(Opcodes.AALOAD);
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FORGE_PART, "getId", "()I", false);
		mv.visitVarInsn(Opcodes.ALOAD, 2);
		mv.visitVarInsn(Opcodes.ILOAD, 3);
		mv.visitInsn(Opcodes.AALOAD);
		mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, PARTS_MAP, "put", "(ILjava/lang/Object;)Ljava/lang/Object;", true);
		mv.visitInsn(Opcodes.POP);
		mv.visitIincInsn(3, 1);
		mv.visitJumpInsn(Opcodes.GOTO, head);
		mv.visitLabel(exit);
	}
}
