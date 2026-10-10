/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import net.fabricmc.api.EnvType;
import net.forbric.kernel.GateLogContract;
import net.forbric.kernel.TestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The client part-tracking claim is a data-flow judgement, not a census of what {@code onTrackingStart} mentions.
 * Every 26.2 body reads {@code ClientLevel.dragonParts} in vanilla's EnderDragon case, so "reaches dragonParts" held on
 * any body that also read NeoForge's {@code getParts()} at all — including one that only counted the parts or sent them
 * to another list. What has to hold is that NeoForge's parts arrive in dragonParts, and that MinecraftForge's array is
 * tested for null on every path before it is used.
 *
 * <p>Every fixture here carries the vanilla dragon case, and is written differently from the platform bodies: another
 * name for the outer level field, other locals, NeoForge's parts handed over element by element, through a stream, a
 * bound method reference, a lambda of the class, a copying constructor, {@code Collections.addAll}, or the level's
 * {@code dragonParts()} accessor; MinecraftForge's array replaced by {@code requireNonNullElse} or tested some
 * instructions after it is read.
 */
// The repair reads its switch when it transforms; another test turns it off.
@ResourceLock("system-properties")
class ClientPartTrackingFlowTest {
	private static final Path STAGED = TestFixtures.stagedRoot();
	private static final Path MERGED = STAGED.resolve("merged-base/patched-mc-merged-26.2.jar");
	private static final Path GATE = Path.of("run/gate-m9-client.sh");
	private static final String CALLBACKS = ClientPartTrackingInjector.CALLBACKS;
	private static final String CALLBACKS_INTERNAL = ClientPartTrackingInjector.CALLBACKS_INTERNAL;
	private static final String LEVEL = ClientPartTrackingInjector.LEVEL;
	private static final String ENTITY = ClientPartTrackingInjector.ENTITY;
	private static final String DRAGON = ClientPartTrackingInjector.DRAGON;
	private static final String START = ClientPartTrackingInjector.TRACKING_START_DESC;
	private static final String FORGE_GET_PARTS = ClientPartTrackingInjector.FORGE_GET_PARTS;
	private static final String NEO_GET_PARTS = ClientPartTrackingInjector.NEO_GET_PARTS;
	private static final String FORGE_PART = DragonPartsInjector.FORGE_PART;
	private static final String NEO_PART = DragonPartsInjector.NEO_PART;
	private static final String PARTS_MAP = "it/unimi/dsi/fastutil/ints/Int2ObjectMap";
	private static final String LIST = "java/util/List";
	private static final String OUTER = "owningLevel";
	private static final TransformContext CLIENT = new TransformContext(EnvType.CLIENT, false, "intermediary");
	private static final Handle METAFACTORY = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
			"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
					+ "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);

	@TempDir Path root;

	// --- the reviewer's probe and other look-alikes: NeoForge's parts read, the dragon case present, nothing tracked ------

	/** The dragon case reads dragonParts; NeoForge's parts are only counted. The old census called this tracked. */
	@Test void theDragonCaseDoesNotStandInForNeoForgesPartsThatAreOnlyCounted() {
		byte[] probe = callbacks((mv, exit) -> {
			multipart(mv, exit);
			mv.visitVarInsn(Opcodes.ALOAD, 1);
			mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ENTITY, "getParts", NEO_GET_PARTS, false);
			mv.visitInsn(Opcodes.ARRAYLENGTH);
			mv.visitVarInsn(Opcodes.ISTORE, 2);
		});
		assertMiss(probe);
	}

	@Test void neoForgesPartsAddedToAnotherListOfTheLevelAreNotTracked() {
		assertMiss(callbacks((mv, exit) -> {
			multipart(mv, exit);
			level(mv);
			mv.visitFieldInsn(Opcodes.GETFIELD, LEVEL, "players", "Ljava/util/List;");
			neoParts(mv);
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Arrays", "asList", "([Ljava/lang/Object;)Ljava/util/List;", false);
			mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, LIST, "addAll", "(Ljava/util/Collection;)Z", true);
			mv.visitInsn(Opcodes.POP);
		}));
	}

	@Test void neoForgesPartsCopiedIntoAListThatNeverReachesDragonPartsAreNotTracked() {
		assertMiss(callbacks((mv, exit) -> {
			multipart(mv, exit);
			copyOfNeoForgesParts(mv, 4);
			mv.visitVarInsn(Opcodes.ALOAD, 4);
			mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, LIST, "size", "()I", true);
			mv.visitInsn(Opcodes.POP);
		}));
	}

	@Test void removingNeoForgesPartsFromDragonPartsIsNotTrackingThem() {
		assertMiss(callbacks((mv, exit) -> {
			multipart(mv, exit);
			dragonParts(mv);
			neoParts(mv);
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Arrays", "asList", "([Ljava/lang/Object;)Ljava/util/List;", false);
			mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, LIST, "removeAll", "(Ljava/util/Collection;)Z", true);
			mv.visitInsn(Opcodes.POP);
		}));
	}

	@Test void aBoundMethodReferenceThatRemovesFromDragonPartsIsNotTrackingThem() {
		assertMiss(callbacks((mv, exit) -> {
			multipart(mv, exit);
			neoParts(mv);
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Arrays", "stream", "([Ljava/lang/Object;)Ljava/util/stream/Stream;", false);
			dragonParts(mv);
			boundListMethod(mv, "remove", "(Ljava/lang/Object;)Z");
			mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/stream/Stream", "forEach", "(Ljava/util/function/Consumer;)V", true);
		}));
	}

	@Test void aLambdaThatAddsNeoForgesPartsToAnotherListIsNotTrackingThem() {
		assertMiss(callbacks((mv, exit) -> {
			multipart(mv, exit);
			forEachThroughLambda(mv);
		}, cw -> lambda(cw, "players")));
	}

	// --- MinecraftForge's array: tested on every path, judged by flow ----------------------------------------------------

	/** Tested on one path only: when the entity is not multipart the loop still walks an array nobody tested. */
	@Test void aMinecraftForgeArrayTestedOnOnlyOnePathIsAMiss() {
		assertMiss(callbacks((mv, exit) -> {
			addAllNeoForgesParts(mv);
			Label loop = new Label();
			mv.visitVarInsn(Opcodes.ALOAD, 1);
			mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ENTITY, "getParts", FORGE_GET_PARTS, false);
			mv.visitVarInsn(Opcodes.ASTORE, 5);
			mv.visitVarInsn(Opcodes.ALOAD, 1);
			mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ENTITY, "isMultipartEntity", "()Z", false);
			mv.visitJumpInsn(Opcodes.IFEQ, loop);
			mv.visitVarInsn(Opcodes.ALOAD, 5);
			mv.visitJumpInsn(Opcodes.IFNONNULL, loop);
			mv.visitInsn(Opcodes.RETURN);
			mv.visitLabel(loop);
			forgeLoop(mv, 5, 6);
		}));
	}

	/** {@code if (parts != null) return; parts.length}: the array is used on the side of the test that knows it is null. */
	@Test void theSideOfTheTestThatKnowsTheArrayIsNullIsNotRefined() {
		assertMiss(callbacks((mv, exit) -> {
			addAllNeoForgesParts(mv);
			Label tested = new Label();
			mv.visitVarInsn(Opcodes.ALOAD, 1);
			mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ENTITY, "getParts", FORGE_GET_PARTS, false);
			mv.visitVarInsn(Opcodes.ASTORE, 5);
			mv.visitVarInsn(Opcodes.ALOAD, 5);
			mv.visitJumpInsn(Opcodes.IFNONNULL, tested);
			mv.visitVarInsn(Opcodes.ALOAD, 5);
			mv.visitInsn(Opcodes.ARRAYLENGTH);
			mv.visitInsn(Opcodes.POP);
			mv.visitLabel(tested);
		}));
	}

	/** MinecraftForge's read replaced by {@code requireNonNullElse(parts, new PartEntity[0])}, the form the companion repair writes. */
	@Test void aMinecraftForgeReadReplacedByRequireNonNullElseIsTested() {
		assertHit(callbacks((mv, exit) -> {
			multipart(mv, exit);
			neoForgesPartsElementByElement(mv);
			mv.visitVarInsn(Opcodes.ALOAD, 1);
			mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ENTITY, "getParts", FORGE_GET_PARTS, false);
			mv.visitInsn(Opcodes.ICONST_0);
			mv.visitTypeInsn(Opcodes.ANEWARRAY, FORGE_PART);
			orElse(mv, 7);
			forgeLoop(mv, 7, 8);
		}));
	}

	/** The same with the empty array taken from a constant, which no instruction template of the read expected. */
	@Test void aMinecraftForgeReadReplacedByRequireNonNullElseWithAConstantIsTested() {
		assertHit(callbacks((mv, exit) -> {
			multipart(mv, exit);
			neoForgesPartsElementByElement(mv);
			mv.visitVarInsn(Opcodes.ALOAD, 1);
			mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ENTITY, "getParts", FORGE_GET_PARTS, false);
			mv.visitFieldInsn(Opcodes.GETSTATIC, CALLBACKS_INTERNAL, "NO_PARTS", "[L" + FORGE_PART + ";");
			orElse(mv, 7);
			forgeLoop(mv, 7, 8);
		}));
	}

	/** Read first, tested only after NeoForge's parts are registered: on every path the test comes before the loop. */
	@Test void aMinecraftForgeArrayTestedLaterOnEveryPathIsTested() {
		assertHit(callbacks((mv, exit) -> {
			multipart(mv, exit);
			Label done = new Label();
			mv.visitVarInsn(Opcodes.ALOAD, 1);
			mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ENTITY, "getParts", FORGE_GET_PARTS, false);
			mv.visitTypeInsn(Opcodes.CHECKCAST, "[L" + FORGE_PART + ";");
			mv.visitVarInsn(Opcodes.ASTORE, 9);
			addAllNeoForgesParts(mv);
			mv.visitVarInsn(Opcodes.ALOAD, 9);
			mv.visitJumpInsn(Opcodes.IFNULL, done);
			forgeLoop(mv, 9, 10);
			mv.visitLabel(done);
		}));
	}

	// --- NeoForge's parts reaching dragonParts, written other ways -------------------------------------------------------

	@Test void neoForgesPartsThroughAStreamAreTracked() {
		assertHit(callbacks((mv, exit) -> {
			multipart(mv, exit);
			dragonParts(mv);
			neoParts(mv);
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Arrays", "stream", "([Ljava/lang/Object;)Ljava/util/stream/Stream;", false);
			mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/stream/Stream", "toList", "()Ljava/util/List;", true);
			mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, LIST, "addAll", "(Ljava/util/Collection;)Z", true);
			mv.visitInsn(Opcodes.POP);
		}));
	}

	@Test void neoForgesPartsAddedByABoundMethodReferenceAreTracked() {
		assertHit(callbacks((mv, exit) -> {
			multipart(mv, exit);
			neoParts(mv);
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Arrays", "stream", "([Ljava/lang/Object;)Ljava/util/stream/Stream;", false);
			dragonParts(mv);
			boundListMethod(mv, "add", "(Ljava/lang/Object;)Z");
			mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/stream/Stream", "forEach", "(Ljava/util/function/Consumer;)V", true);
		}));
	}

	@Test void neoForgesPartsAddedByALambdaOfTheClassAreTracked() {
		assertHit(callbacks((mv, exit) -> {
			multipart(mv, exit);
			forEachThroughLambda(mv);
		}, cw -> lambda(cw, "dragonParts")));
	}

	/** {@code level.dragonParts().addAll(new ArrayList<>(List.of(parts)))}, the copy kept in a local first. */
	@Test void neoForgesPartsCopiedThroughTheLevelsAccessorAreTracked() {
		assertHit(callbacks((mv, exit) -> {
			multipart(mv, exit);
			copyOfNeoForgesParts(mv, 4);
			level(mv);
			mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, LEVEL, "dragonParts", "()Ljava/util/List;", false);
			mv.visitVarInsn(Opcodes.ALOAD, 4);
			mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, LIST, "addAll", "(Ljava/util/Collection;)Z", true);
			mv.visitInsn(Opcodes.POP);
		}));
	}

	/** Kept the way kotlinc keeps a value ({@code DUP; ASTORE; POP}) and handed over with {@code Collections.addAll}. */
	@Test void neoForgesPartsKeptKotlinStyleAndHandedToCollectionsAddAllAreTracked() {
		assertHit(callbacks((mv, exit) -> {
			multipart(mv, exit);
			neoParts(mv);
			mv.visitInsn(Opcodes.DUP);
			mv.visitVarInsn(Opcodes.ASTORE, 11);
			mv.visitInsn(Opcodes.POP);
			dragonParts(mv);
			mv.visitVarInsn(Opcodes.ALOAD, 11);
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Collections", "addAll", "(Ljava/util/Collection;[Ljava/lang/Object;)Z", false);
			mv.visitInsn(Opcodes.POP);
		}));
	}

	// --- the staged platform body, edited into the probe -----------------------------------------------------------------

	/** The staged NeoForge body with NeoForge's parts sent to {@code players}: its dragon case still reads dragonParts. */
	@Test void theStagedBodyWithNeoForgesPartsSentToAnotherListIsAMissTheGateSees() throws Exception {
		byte[] staged = NativeCoremodParityTest.read(MERGED, CALLBACKS_INTERNAL);
		assertTrue(ClientPartTrackingInjector.tracksNeoForgeParts(staged), "premise: the staged body tracks NeoForge's parts");
		byte[] elsewhere = editStagedRegistration(staged, (method, list, read) -> list.name = "players");
		assertMissTheGateSees(elsewhere);
	}

	/** The reviewer's probe on the real body: NeoForge's parts only counted, the dragon case left as it is. */
	@Test void theStagedBodyWithNeoForgesPartsOnlyCountedIsAMissTheGateSees() throws Exception {
		byte[] staged = NativeCoremodParityTest.read(MERGED, CALLBACKS_INTERNAL);
		byte[] counted = editStagedRegistration(staged, (method, list, read) -> {
			// this.this$0.dragonParts.addAll(Arrays.asList(entity.getParts())); -> entity.getParts().length; (popped as before)
			AbstractInsnNode outer = previous(list), self = previous(outer);
			assertEquals(Opcodes.ALOAD, self.getOpcode(), "premise: dragonParts is read through this.this$0");
			AbstractInsnNode asList = next(read), addAll = next(asList);
			assertEquals(Opcodes.POP, next(addAll).getOpcode(), "premise: addAll's answer is dropped");
			for (AbstractInsnNode gone : List.of(self, outer, list, asList, addAll)) method.instructions.remove(gone);
			method.instructions.insert(read, new InsnNode(Opcodes.ARRAYLENGTH));
		});
		assertMissTheGateSees(counted);
	}

	/** The companion MinecraftForge repair wraps NeoForge's read in requireNonNullElse; the parts still arrive. */
	@Test void theStagedBodyAfterTheCompanionRepairStillTracksNeoForgesParts() throws Exception {
		ClassNode node = node(NativeCoremodParityTest.read(MERGED, CALLBACKS_INTERNAL));
		assertEquals(1, ForgePartTrackingInjector.clientCallbacks(node, true), "premise: the companion repair edits the staged body");
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		assertHit(writer.toByteArray());
	}

	// ---------------------------------------------------------------------------------------------------------------

	private static void assertHit(byte[] callbacks) {
		assertTrue(ClientPartTrackingInjector.tracksNeoForgeParts(callbacks));
		assertEquals(Set.of(ClientPartTrackingInjector.CLAIM), hits(callbacks));
	}

	private static void assertMiss(byte[] callbacks) {
		assertFalse(ClientPartTrackingInjector.tracksNeoForgeParts(callbacks));
		assertEquals(Set.of(), hits(callbacks));
	}

	private void assertMissTheGateSees(byte[] callbacks) throws Exception {
		assertFalse(ClientPartTrackingInjector.tracksNeoForgeParts(callbacks));
		TransformChain chain = new TransformChain();
		chain.register(TransformPhase.COREMOD, new ClientPartTrackingInjector());
		String[] out = new String[1];
		assertSame(callbacks, GateLogContract.capture(() -> chain.applyBeforeMixin(CALLBACKS, callbacks, CLIENT), out),
				"the body reads NeoForge's parts, so the repair has nothing to add; the claim is what judges it");
		AnchorLedger.Report report = chain.ledger().report();
		assertEquals(List.of(ClientPartTrackingInjector.CLAIM), report.misses().stream().map(AnchorLedger.Miss::transformer).toList());
		assertEquals(1, GateLogContract.count(root, GateLogContract.pattern(GATE, "no repair was handed its target and declined"), out[0]), out[0]);
	}

	private static Set<String> hits(byte[] callbacks) {
		Set<String> hits = new HashSet<>();
		new ClientPartTrackingInjector().transform(CALLBACKS, callbacks, null, hits::add);
		return hits;
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
		return node;
	}

	private interface Edit {
		void apply(MethodNode method, FieldInsnNode list, MethodInsnNode read);
	}

	/**
	 * Edits the staged onTrackingStart's NeoForge registration ({@code list} is its {@code GETFIELD dragonParts}, {@code
	 * read} its NeoForge getParts()), leaving the dragon case's own read of dragonParts in place. Written without frames,
	 * since it is only read back.
	 */
	private static byte[] editStagedRegistration(byte[] staged, Edit edit) {
		ClassNode node = new ClassNode();
		new ClassReader(staged).accept(node, ClassReader.SKIP_FRAMES);
		MethodNode start = node.methods.stream().filter(m -> m.name.equals("onTrackingStart") && m.desc.equals(START)).findFirst().orElseThrow();
		MethodInsnNode read = null;
		List<FieldInsnNode> lists = new java.util.ArrayList<>();
		for (AbstractInsnNode insn : start.instructions) {
			if (insn instanceof MethodInsnNode call && call.name.equals("getParts") && call.desc.equals(NEO_GET_PARTS)) read = call;
			if (insn instanceof FieldInsnNode field && field.name.equals("dragonParts") && field.owner.equals(LEVEL)) lists.add(field);
		}
		assertNotNull(read, "premise: the staged body reads NeoForge's parts");
		assertEquals(2, lists.size(), "premise: the dragon case and NeoForge's registration each read dragonParts");
		FieldInsnNode registration = lists.get(1);
		assertSame(read, next(next(registration)), "premise: NeoForge's registration is dragonParts.addAll(Arrays.asList(entity.getParts()))");
		edit.apply(start, registration, read);
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static AbstractInsnNode next(AbstractInsnNode insn) {
		AbstractInsnNode at = insn.getNext();
		while (at != null && at.getOpcode() < 0) at = at.getNext();
		return at;
	}

	private static AbstractInsnNode previous(AbstractInsnNode insn) {
		AbstractInsnNode at = insn.getPrevious();
		while (at != null && at.getOpcode() < 0) at = at.getPrevious();
		return at;
	}

	/**
	 * ClientLevel$EntityCallbacks with its outer level in a field of another name, a constant {@code NO_PARTS}, and an
	 * {@code onTrackingStart(Entity)} that opens with vanilla's dragon case — which reads dragonParts — and then runs
	 * {@code body}, which returns by falling off its end.
	 */
	private static byte[] callbacks(BiConsumer<MethodVisitor, Label> body) {
		return callbacks(body, cw -> { });
	}

	/** {@code body} is given the label of the method's final return, where {@code if (!entity.isMultipartEntity())} goes. */
	private static byte[] callbacks(BiConsumer<MethodVisitor, Label> body, Consumer<ClassWriter> more) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_SUPER, CALLBACKS_INTERNAL, null, "java/lang/Object", null);
		cw.visitField(Opcodes.ACC_FINAL, OUTER, "L" + LEVEL + ";", null, null).visitEnd();
		cw.visitField(Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "NO_PARTS", "[L" + FORGE_PART + ";", null, null).visitEnd();
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "onTrackingStart", START, null, null);
		mv.visitCode();
		// if (entity instanceof EnderDragon dragon) { level.dragonParts.addAll(Arrays.asList(dragon.getSubEntities())); return; }
		Label notDragon = new Label();
		mv.visitVarInsn(Opcodes.ALOAD, 1);
		mv.visitTypeInsn(Opcodes.INSTANCEOF, DRAGON);
		mv.visitJumpInsn(Opcodes.IFEQ, notDragon);
		dragonParts(mv);
		mv.visitVarInsn(Opcodes.ALOAD, 1);
		mv.visitTypeInsn(Opcodes.CHECKCAST, DRAGON);
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, DRAGON, "getSubEntities", "()[Lnet/minecraft/world/entity/boss/enderdragon/EnderDragonPart;", false);
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Arrays", "asList", "([Ljava/lang/Object;)Ljava/util/List;", false);
		mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, LIST, "addAll", "(Ljava/util/Collection;)Z", true);
		mv.visitInsn(Opcodes.POP);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitLabel(notDragon);
		Label exit = new Label();
		body.accept(mv, exit);
		mv.visitLabel(exit);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		more.accept(cw);
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static void multipart(MethodVisitor mv, Label exit) {
		mv.visitVarInsn(Opcodes.ALOAD, 1);
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ENTITY, "isMultipartEntity", "()Z", false);
		mv.visitJumpInsn(Opcodes.IFEQ, exit);
	}

	private static void level(MethodVisitor mv) {
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitFieldInsn(Opcodes.GETFIELD, CALLBACKS_INTERNAL, OUTER, "L" + LEVEL + ";");
	}

	private static void dragonParts(MethodVisitor mv) {
		level(mv);
		mv.visitFieldInsn(Opcodes.GETFIELD, LEVEL, "dragonParts", "Ljava/util/List;");
	}

	private static void neoParts(MethodVisitor mv) {
		mv.visitVarInsn(Opcodes.ALOAD, 1);
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ENTITY, "getParts", NEO_GET_PARTS, false);
	}

	/** {@code level.dragonParts.addAll(Arrays.asList(entity.getParts()))}, NeoForge's own registration. */
	private static void addAllNeoForgesParts(MethodVisitor mv) {
		dragonParts(mv);
		neoParts(mv);
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Arrays", "asList", "([Ljava/lang/Object;)Ljava/util/List;", false);
		mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, LIST, "addAll", "(Ljava/util/Collection;)Z", true);
		mv.visitInsn(Opcodes.POP);
	}

	/** {@code List copy = new ArrayList<>(List.of(entity.getParts()));} into {@code local}. */
	private static void copyOfNeoForgesParts(MethodVisitor mv, int local) {
		mv.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
		mv.visitInsn(Opcodes.DUP);
		neoParts(mv);
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, LIST, "of", "([Ljava/lang/Object;)Ljava/util/List;", true);
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "(Ljava/util/Collection;)V", false);
		mv.visitVarInsn(Opcodes.ASTORE, local);
	}

	/** {@code parts = entity.getParts(); if (parts != null) for (int i = 0; i < parts.length; i++) level.dragonParts.add(parts[i]);} */
	private static void neoForgesPartsElementByElement(MethodVisitor mv) {
		Label head = new Label(), exit = new Label();
		neoParts(mv);
		mv.visitVarInsn(Opcodes.ASTORE, 2);
		mv.visitVarInsn(Opcodes.ALOAD, 2);
		mv.visitJumpInsn(Opcodes.IFNULL, exit);
		mv.visitInsn(Opcodes.ICONST_0);
		mv.visitVarInsn(Opcodes.ISTORE, 3);
		mv.visitLabel(head);
		mv.visitVarInsn(Opcodes.ILOAD, 3);
		mv.visitVarInsn(Opcodes.ALOAD, 2);
		mv.visitInsn(Opcodes.ARRAYLENGTH);
		mv.visitJumpInsn(Opcodes.IF_ICMPGE, exit);
		dragonParts(mv);
		mv.visitVarInsn(Opcodes.ALOAD, 2);
		mv.visitVarInsn(Opcodes.ILOAD, 3);
		mv.visitInsn(Opcodes.AALOAD);
		mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, LIST, "add", "(Ljava/lang/Object;)Z", true);
		mv.visitInsn(Opcodes.POP);
		mv.visitIincInsn(3, 1);
		mv.visitJumpInsn(Opcodes.GOTO, head);
		mv.visitLabel(exit);
	}

	/** {@code (PartEntity[]) Objects.requireNonNullElse(read, fallback)} into {@code local}; both are on the stack. */
	private static void orElse(MethodVisitor mv, int local) {
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Objects", "requireNonNullElse", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
		mv.visitTypeInsn(Opcodes.CHECKCAST, "[L" + FORGE_PART + ";");
		mv.visitVarInsn(Opcodes.ASTORE, local);
	}

	/** A {@code java.util.function.Consumer} bound to the list on the stack: {@code list::name}. */
	private static void boundListMethod(MethodVisitor mv, String name, String desc) {
		mv.visitInsn(Opcodes.DUP);
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Objects", "requireNonNull", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
		mv.visitInsn(Opcodes.POP);
		mv.visitInvokeDynamicInsn("accept", "(Ljava/util/List;)Ljava/util/function/Consumer;", METAFACTORY,
				Type.getType("(Ljava/lang/Object;)V"), new Handle(Opcodes.H_INVOKEINTERFACE, LIST, name, desc, true),
				Type.getType("(L" + NEO_PART + ";)V"));
	}

	/** {@code List.of(entity.getParts()).forEach(part -> this.track(part))}, the lambda an instance method of the class. */
	private static void forEachThroughLambda(MethodVisitor mv) {
		neoParts(mv);
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, LIST, "of", "([Ljava/lang/Object;)Ljava/util/List;", true);
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitInvokeDynamicInsn("accept", "(L" + CALLBACKS_INTERNAL + ";)Ljava/util/function/Consumer;", METAFACTORY,
				Type.getType("(Ljava/lang/Object;)V"),
				new Handle(Opcodes.H_INVOKESPECIAL, CALLBACKS_INTERNAL, "lambda$track$0", "(L" + NEO_PART + ";)V", false),
				Type.getType("(L" + NEO_PART + ";)V"));
		mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, LIST, "forEach", "(Ljava/util/function/Consumer;)V", true);
	}

	/** {@code private void lambda$track$0(PartEntity part) { level.<list>.add(part); }} */
	private static void lambda(ClassWriter cw, String list) {
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC, "lambda$track$0", "(L" + NEO_PART + ";)V", null, null);
		mv.visitCode();
		level(mv);
		mv.visitFieldInsn(Opcodes.GETFIELD, LEVEL, list, "Ljava/util/List;");
		mv.visitVarInsn(Opcodes.ALOAD, 1);
		mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, LIST, "add", "(Ljava/lang/Object;)Z", true);
		mv.visitInsn(Opcodes.POP);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}

	/** {@code for (int i = 0; i < parts.length; i++) level.partEntities.put(parts[i].getId(), parts[i]);} over {@code parts}. */
	private static void forgeLoop(MethodVisitor mv, int parts, int index) {
		Label head = new Label(), exit = new Label();
		mv.visitInsn(Opcodes.ICONST_0);
		mv.visitVarInsn(Opcodes.ISTORE, index);
		mv.visitLabel(head);
		mv.visitVarInsn(Opcodes.ILOAD, index);
		mv.visitVarInsn(Opcodes.ALOAD, parts);
		mv.visitInsn(Opcodes.ARRAYLENGTH);
		mv.visitJumpInsn(Opcodes.IF_ICMPGE, exit);
		level(mv);
		mv.visitFieldInsn(Opcodes.GETFIELD, LEVEL, "partEntities", "L" + PARTS_MAP + ";");
		mv.visitVarInsn(Opcodes.ALOAD, parts);
		mv.visitVarInsn(Opcodes.ILOAD, index);
		mv.visitInsn(Opcodes.AALOAD);
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FORGE_PART, "getId", "()I", false);
		mv.visitVarInsn(Opcodes.ALOAD, parts);
		mv.visitVarInsn(Opcodes.ILOAD, index);
		mv.visitInsn(Opcodes.AALOAD);
		mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, PARTS_MAP, "put", "(ILjava/lang/Object;)Ljava/lang/Object;", true);
		mv.visitInsn(Opcodes.POP);
		mv.visitIincInsn(index, 1);
		mv.visitJumpInsn(Opcodes.GOTO, head);
		mv.visitLabel(exit);
	}
}
