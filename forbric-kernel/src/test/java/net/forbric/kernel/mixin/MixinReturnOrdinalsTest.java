/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/**
 * A guest's {@code @At("RETURN", ordinal = n)} names the n-th return of the body it was compiled against; the current body
 * may number its returns differently. Apoli's {@code action_on_block_break} names vanilla's last return of
 * {@code ServerPlayerGameMode.destroyBlock} (ordinal 4). The merged base kept MinecraftForge's {@code onBlockStartBreak}
 * veto there as one more early return, so its ordinal 4 was the creative-mode exit, where the two booleans the handler
 * reads by {@code @Local} are not in scope: the injector failed and the power never acted. NeoForge's own body has
 * vanilla's five exits, so nothing moves on it; the mechanism keeps its real-mod positive on a copy of NeoForge's body
 * with an exit added ahead of vanilla's. Which return is which is read off both bodies, by what decides each exit, never
 * by the guest or the method.
 */
class MixinReturnOrdinalsTest {
	private static final Path MODS = Path.of(System.getProperty("forbric.popularMods", "run/client-popular/mods"));
	private static final String TARGET = "net/minecraft/server/level/ServerPlayerGameMode";

	/** On NeoForge's body apoli's ordinal already names the last return: the exits are vanilla's, and the mixin is left as compiled. */
	@Test void apolisLastReturnOfDestroyBlockIsNeoForgesLastReturn() throws Exception {
		ClassNode current = game(Fixture.STAGED, TestFixtures.stagedBase());
		ClassNode vanilla = game(Fixture.MC_LIBRARIES, TestFixtures.vanillaJar());
		MethodNode live = destroyBlock(current), original = destroyBlock(vanilla);
		List<AbstractInsnNode> returns = returns(live);
		assertEquals(5, returns(original).size(), "vanilla's destroyBlock: four early returns and the last");
		assertEquals(5, returns.size(), "NeoForge's destroyBlock has vanilla's exits; MinecraftForge's veto exit went with the merged base");
		assertEquals(2, booleansInScope(live, returns.get(4)), "NeoForge's return 4 is where the handler's booleans live");

		ClassNode mixin = apoli();
		byte[] before = bytes(mixin);
		assertEquals(0, MixinReturnOrdinals.adapt(mixin, name -> name.equals(TARGET) ? current : null, (family, name) -> name.equals(TARGET) ? vanilla : null));
		assertArrayEquals(before, bytes(mixin), "the mixin is left as compiled");
	}

	/**
	 * The kept mechanism's positive on the real mod: NeoForge's body with one exit added ahead of vanilla's, decided by a
	 * call vanilla never makes (the shape MinecraftForge's {@code onBlockStartBreak} veto gave the merged body). Apoli's
	 * ordinal 4 now reads an early exit; it moves to the last return, where both booleans are, and is counted once.
	 */
	@Test void anExitAddedAheadOfVanillasMovesApolisOrdinalToTheLastReturn() throws Exception {
		ClassNode current = withAnExitAheadOfVanillas(game(Fixture.STAGED, TestFixtures.stagedBase()));
		ClassNode vanilla = game(Fixture.MC_LIBRARIES, TestFixtures.vanillaJar());
		MethodNode live = destroyBlock(current);
		List<AbstractInsnNode> returns = returns(live);
		assertEquals(6, returns.size(), "premise: one exit more than vanilla's five");
		assertNotEquals(2, booleansInScope(live, returns.get(4)), "premise: the native ordinal read on this body is not where the booleans live");
		assertEquals(2, booleansInScope(live, returns.getLast()), "the last return has both");

		ClassNode mixin = apoli();
		assertEquals(1, MixinReturnOrdinals.adapt(mixin, name -> name.equals(TARGET) ? current : null, (family, name) -> name.equals(TARGET) ? vanilla : null));
		AnnotationNode at = MixinFit.atNodes(MixinFit.injectorOf(handler(mixin, "actionOnBlockBreak"))).getFirst();
		assertEquals(returns.size() - 1, MixinFit.value(at, "ordinal"));
		assertTrue(CurrentBodyOrdinals.counted(handler(mixin, "actionOnBlockBreak")));
		assertEquals(0, MixinReturnOrdinals.adapt(mixin, name -> name.equals(TARGET) ? current : null, (family, name) -> name.equals(TARGET) ? vanilla : null),
				"a counted point is never counted again");
	}

	@Test void onTheNativeBodyItselfNothingMoves() throws Exception {
		ClassNode vanilla = game(Fixture.MC_LIBRARIES, TestFixtures.vanillaJar());
		ClassNode mixin = apoli();
		byte[] before = bytes(mixin);
		assertEquals(0, MixinReturnOrdinals.adapt(mixin, name -> name.equals(TARGET) ? vanilla : null, (family, name) -> name.equals(TARGET) ? vanilla : null));
		assertArrayEquals(before, bytes(mixin));
	}

	/** On the body where the ordinal would move, the switch leaves it as compiled. */
	@Test void theSwitchLeavesTheOrdinalAsCompiled() throws Exception {
		ClassNode current = withAnExitAheadOfVanillas(game(Fixture.STAGED, TestFixtures.stagedBase()));
		ClassNode vanilla = game(Fixture.MC_LIBRARIES, TestFixtures.vanillaJar());
		String old = System.setProperty(MixinReturnOrdinals.PROPERTY, "off");
		try {
			ClassNode mixin = apoli();
			byte[] before = bytes(mixin);
			assertEquals(0, MixinReturnOrdinals.adapt(mixin, name -> current, (family, name) -> vanilla));
			assertArrayEquals(before, bytes(mixin));
		} finally {
			if (old == null) System.clearProperty(MixinReturnOrdinals.PROPERTY); else System.setProperty(MixinReturnOrdinals.PROPERTY, old);
		}
	}

	// ---- synthetic bodies: the rule, not the sample

	/** {@code int m(int)}: early exits decided by {@code a()}, then {@code b()}, then the last return. */
	private static MethodNode body(boolean extraExitFirst, boolean twiceB) {
		MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC, "m", "(I)I", null, null);
		InsnList c = m.instructions;
		if (extraExitFirst) exit(c, "veto", 9);
		exit(c, "a", 1);
		exit(c, "b", 2);
		if (twiceB) exit(c, "b", 3);
		c.add(new InsnNode(Opcodes.ICONST_0));
		c.add(new InsnNode(Opcodes.IRETURN));
		m.maxLocals = 2;
		m.maxStack = 2;
		return m;
	}

	private static void exit(InsnList c, String decider, int value) {
		LabelNode skip = new LabelNode();
		c.add(new VarInsnNode(Opcodes.ALOAD, 0));
		c.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "t/T", decider, "()Z", false));
		c.add(new JumpInsnNode(Opcodes.IFEQ, skip));
		c.add(new IntInsnNode(Opcodes.BIPUSH, value));
		c.add(new InsnNode(Opcodes.IRETURN));
		c.add(skip);
		c.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
	}

	@Test void anEarlyExitIsTheOneTheSameTestDecidesAndAnAddedExitIsNoNativeOne() {
		int[] map = ReturnAlignment.map("t/T", body(false, false), "t/T", body(true, false));
		assertArrayEquals(new int[] {1, 2, 3}, map, "a → 1, b → 2, last → last; the veto exit (0) is nobody's");
		assertArrayEquals(new int[] {0, 1, 2}, ReturnAlignment.map("t/T", body(false, false), "t/T", body(false, false)));
	}

	@Test void twoTestsOfOnePredicateAreTwoExitsButOnlyWhileBothBodiesTestItAsOften() {
		assertArrayEquals(new int[] {1, 2, 3, 4}, ReturnAlignment.map("t/T", body(false, true), "t/T", body(true, true)),
				"the first and the second test of b are told apart");
		// The current body tests b once more than the native body: no occurrence of b is counted against the other.
		int[] map = ReturnAlignment.map("t/T", body(false, false), "t/T", body(false, true));
		assertEquals(0, map[0], "a is still a");
		assertEquals(-1, map[1], "b is tested once natively and twice in the current body");
		assertEquals(3, map[2], "the last is the last");
	}

	@Test void aGuestPointOnAnUnprovedExitStaysAsCompiled() throws Exception {
		ClassNode nativeOwner = owner(body(false, false)), currentOwner = owner(body(true, true));
		ClassNode mixin = guest(1);
		assertEquals(0, MixinReturnOrdinals.adapt(mixin, name -> currentOwner, (family, name) -> nativeOwner));
		assertEquals(1, MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(mixin.methods.getFirst())).getFirst(), "ordinal"));
		ClassNode moved = guest(0);
		assertEquals(1, MixinReturnOrdinals.adapt(moved, name -> currentOwner, (family, name) -> nativeOwner));
		assertEquals(1, MixinFit.value(MixinFit.atNodes(MixinFit.injectorOf(moved.methods.getFirst())).getFirst(), "ordinal"));
	}

	/**
	 * {@code float m(float)}: natively {@code if (amount == 0) return 0; return amount;}; on the current body the same
	 * test reaches a return block through a goto (the shape a split of folded early returns leaves before the tail),
	 * after a carrier reassigned the parameter and added a veto exit in front.
	 */
	@Test void anExitReachedThroughAGotoIsKnownByTheTestBeforeIt() {
		MethodNode before = new MethodNode(Opcodes.ACC_PUBLIC, "m", "(F)F", null, null);
		LabelNode go = new LabelNode();
		before.instructions.add(new VarInsnNode(Opcodes.FLOAD, 1));
		before.instructions.add(new InsnNode(Opcodes.FCONST_0));
		before.instructions.add(new InsnNode(Opcodes.FCMPL));
		before.instructions.add(new JumpInsnNode(Opcodes.IFNE, go));
		before.instructions.add(new InsnNode(Opcodes.FCONST_0));
		before.instructions.add(new InsnNode(Opcodes.FRETURN));
		before.instructions.add(go);
		before.instructions.add(new VarInsnNode(Opcodes.FLOAD, 1));
		before.instructions.add(new InsnNode(Opcodes.FRETURN));
		before.maxLocals = 2;
		before.maxStack = 2;

		MethodNode after = new MethodNode(Opcodes.ACC_PUBLIC, "m", "(F)F", null, null);
		LabelNode pass = new LabelNode(), body = new LabelNode(), block = new LabelNode(), tail = new LabelNode();
		InsnList c = after.instructions;
		c.add(new VarInsnNode(Opcodes.ALOAD, 0));
		c.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "t/T", "veto", "()Z", false));
		c.add(new JumpInsnNode(Opcodes.IFEQ, pass));
		c.add(new InsnNode(Opcodes.FCONST_1));
		c.add(new InsnNode(Opcodes.FRETURN));
		c.add(pass);
		c.add(new VarInsnNode(Opcodes.FLOAD, 1));
		c.add(new InsnNode(Opcodes.FCONST_1));
		c.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "max", "(FF)F", false));
		c.add(new VarInsnNode(Opcodes.FSTORE, 1));
		c.add(new VarInsnNode(Opcodes.FLOAD, 1));
		c.add(new InsnNode(Opcodes.FCONST_0));
		c.add(new InsnNode(Opcodes.FCMPL));
		c.add(new JumpInsnNode(Opcodes.IFNE, body));
		c.add(new InsnNode(Opcodes.FCONST_0));
		c.add(new JumpInsnNode(Opcodes.GOTO, block));
		c.add(body);
		c.add(new VarInsnNode(Opcodes.FLOAD, 1));
		c.add(new JumpInsnNode(Opcodes.GOTO, tail));
		c.add(block);
		c.add(new InsnNode(Opcodes.FRETURN));
		c.add(tail);
		c.add(new InsnNode(Opcodes.FRETURN));
		after.maxLocals = 2;
		after.maxStack = 2;
		assertArrayEquals(new int[] {1, 2}, ReturnAlignment.map("t/T", before, "t/T", after),
				"amount == 0 is the return block the goto reaches; the last is the last; the veto (0) is nobody's");
	}

	private static ClassNode owner(MethodNode method) {
		ClassNode owner = new ClassNode();
		owner.version = Opcodes.V21;
		owner.access = Opcodes.ACC_PUBLIC;
		owner.name = "t/T";
		owner.superName = "java/lang/Object";
		owner.methods.add(method);
		return owner;
	}

	/** A Fabric-namespaced guest whose one handler is an {@code @Inject} at {@code RETURN} ordinal {@code ordinal} of {@code m}. */
	private static ClassNode guest(int ordinal) {
		ClassNode mixin = new ClassNode();
		mixin.version = Opcodes.V21;
		mixin.access = Opcodes.ACC_PUBLIC;
		mixin.name = "net/fabricmc/example/mixin/GuestMixin";
		mixin.superName = "java/lang/Object";
		AnnotationNode target = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		target.values = new ArrayList<>(List.of("value", List.of(org.objectweb.asm.Type.getObjectType("t/T"))));
		mixin.invisibleAnnotations = new ArrayList<>(List.of(target));
		MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE, "onExit", "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V", null, null);
		handler.instructions.add(new InsnNode(Opcodes.RETURN));
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", "RETURN", "ordinal", ordinal));
		AnnotationNode inject = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
		inject.values = new ArrayList<>(List.of("method", List.of("m(I)I"), "at", List.of(at)));
		handler.visibleAnnotations = new ArrayList<>(List.of(inject));
		mixin.methods.add(handler);
		MixinStubRebind.noteEcosystem(mixin.name, net.forbric.api.Ecosystem.FABRIC);
		return mixin;
	}

	// ---- helpers

	/**
	 * {@code node} with one exit put ahead of {@code destroyBlock}'s own: {@code if (BreakVeto.vetoed(this, pos)) return false;},
	 * decided by a call vanilla's body never makes.
	 */
	private static ClassNode withAnExitAheadOfVanillas(ClassNode node) {
		LabelNode pass = new LabelNode();
		InsnList veto = new InsnList();
		veto.add(new VarInsnNode(Opcodes.ALOAD, 0));
		veto.add(new VarInsnNode(Opcodes.ALOAD, 1));
		veto.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "forbric/test/BreakVeto", "vetoed", "(Ljava/lang/Object;Lnet/minecraft/core/BlockPos;)Z", false));
		veto.add(new JumpInsnNode(Opcodes.IFEQ, pass));
		veto.add(new InsnNode(Opcodes.ICONST_0));
		veto.add(new InsnNode(Opcodes.IRETURN));
		veto.add(pass);
		veto.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
		destroyBlock(node).instructions.insert(veto);
		return node;
	}

	private static int booleansInScope(MethodNode method, AbstractInsnNode at) {
		int index = method.instructions.indexOf(at), found = 0;
		for (LocalVariableNode local : method.localVariables) {
			if (local.desc.equals("Z") && method.instructions.indexOf(local.start) <= index && index < method.instructions.indexOf(local.end)) found++;
		}
		return found;
	}

	private static List<AbstractInsnNode> returns(MethodNode method) {
		List<AbstractInsnNode> out = new ArrayList<>();
		for (AbstractInsnNode at : method.instructions) if (at.getOpcode() == Opcodes.IRETURN) out.add(at);
		return out;
	}

	private static MethodNode destroyBlock(ClassNode node) {
		return node.methods.stream().filter(m -> m.name.equals("destroyBlock") && m.desc.equals("(Lnet/minecraft/core/BlockPos;)Z")).findFirst().orElseThrow();
	}

	private static MethodNode handler(ClassNode mixin, String name) {
		return mixin.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow(() -> new AssertionError(name));
	}

	private static byte[] bytes(ClassNode node) {
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static ClassNode game(Fixture kind, Path jar) throws Exception {
		TestFixtures.require(kind, Files.isRegularFile(jar), "actual game required");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ClassNode node = new ClassNode();
			new ClassReader(zip.getInputStream(zip.getEntry(TARGET + ".class")).readAllBytes()).accept(node, 0);
			return node;
		}
	}

	private static ClassNode apoli() throws Exception {
		Path jar = MODS.resolve("Origins-Legacy-1.12.18+26.2.jar");
		TestFixtures.require(Fixture.THIRD_PARTY, Files.isRegularFile(jar), "popular-pack Origins fixture absent");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry nested = zip.getEntry("META-INF/jars/Apoli-Legacy-2.12.12+26.2.jar");
			try (ZipInputStream in = new ZipInputStream(zip.getInputStream(nested))) {
				for (ZipEntry entry; (entry = in.getNextEntry()) != null;) {
					if (entry.getName().equals("io/github/apace100/apoli/mixin/ServerPlayerInteractionManagerMixin.class")) {
						ClassNode mixin = MixinFit.parse(in.readAllBytes());
						MixinStubRebind.noteEcosystem(mixin.name, net.forbric.api.Ecosystem.FABRIC);
						return mixin;
					}
				}
			}
		}
		throw new AssertionError("apoli's mixin is not in the nested jar");
	}
}
