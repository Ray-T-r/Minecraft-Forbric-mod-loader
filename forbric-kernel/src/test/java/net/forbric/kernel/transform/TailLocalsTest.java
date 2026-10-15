/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * {@link TailLocals} on a body javac compiles both ways: {@link Vanilla} with its guard clause, {@link Folded} with the
 * guard turned into the {@code if} block a decompiler writes back (EquipmentLayerRenderer.renderLayers' shape: a list,
 * an early return when it is empty, then an {@code int}, a {@code boolean} and another {@code int} the body declares, and
 * a loop). After the split, the tail declares vanilla's primitive locals again, in the table and in the frame, so the
 * {@code int} a TAIL handler asks for by ordinal is vanilla's; the class is defined and run, so the JVM's verifier passes
 * on the frame, and each call does what vanilla's does.
 */
@ResourceLock("system-properties")
class TailLocalsTest {
	@SuppressWarnings("unused")
	public static final class Vanilla {
		public static void layers(int light, int order, boolean empty, List<String> log) {
			List<String> layers = empty ? List.of() : List.of("a", "b");
			if (layers.isEmpty()) return;
			int dye = light * 2;
			boolean foil = order > 3;
			String name = "n" + order;
			int next = order + 1;
			for (String layer : layers) {
				int color = dye + next;
				log.add(layer + color + foil + name);
			}
			log.add("done " + next);
		}
	}

	@SuppressWarnings("unused")
	public static final class Folded {
		public static void layers(int light, int order, boolean empty, List<String> log) {
			List<String> layers = empty ? List.of() : List.of("a", "b");
			if (!layers.isEmpty()) {
				int dye = light * 2;
				boolean foil = order > 3;
				String name = "n" + order;
				int next = order + 1;
				int carrierOnly = 0;
				for (String layer : layers) {
					int color = dye + next + carrierOnly;
					log.add(layer + color + foil + name);
				}
				log.add("done " + next);
			}
		}
	}

	@AfterEach void clear() {
		System.clearProperty(TailLocals.PROPERTY);
	}

	@Test void theSplitTailHoldsVanillasPrimitiveLocalsAgain() throws Exception {
		ClassNode folded = split(VanillaEarlyReturnsTest.read(Vanilla.class));
		MethodNode method = VanillaEarlyReturnsTest.method(folded, "layers");
		assertEquals(List.of("light", "order", "dye", "next"), intsInScopeAtTail(method),
				"the fourth int a TAIL handler asks for is vanilla's next");
		assertEquals(intsInScopeAtTail(VanillaEarlyReturnsTest.method(VanillaEarlyReturnsTest.read(Vanilla.class), "layers")),
				intsInScopeAtTail(method), "as vanilla's tail has them");
		List<String> named = namesInScopeAtTail(method);
		assertTrue(named.contains("foil"), "the boolean too: " + named);
		assertFalse(named.contains("name"), "a reference stays out of the frame: no class for the verifier to load");
		assertFalse(named.contains("carrierOnly"), "vanilla's tail has no such local");
		FrameNode frame = tailFrame(method);
		assertEquals(Opcodes.F_FULL, frame.type);
		assertEquals(List.of(Opcodes.INTEGER, Opcodes.INTEGER, Opcodes.INTEGER, "java/util/List", "java/util/List",
				Opcodes.INTEGER, Opcodes.INTEGER, Opcodes.TOP, Opcodes.INTEGER), frame.local);

		// Defined and run: the verifier passes on the frame, and every call does what vanilla's does.
		Class<?> repaired = define(folded, Folded.class.getName());
		for (boolean empty : new boolean[] {true, false}) {
			List<String> want = new ArrayList<>(), got = new ArrayList<>();
			Vanilla.layers(3, 5, empty, want);
			repaired.getMethod("layers", int.class, int.class, boolean.class, List.class).invoke(null, 3, 5, empty, got);
			assertEquals(want, got, "empty=" + empty);
		}
	}

	@Test void withoutVanillasClassOrSwitchedOffTheTailStaysAsSplit() {
		MethodNode noVanilla = VanillaEarlyReturnsTest.method(splitWith(null), "layers");
		assertEquals(List.of("light", "order"), intsInScopeAtTail(noVanilla));
		System.setProperty(TailLocals.PROPERTY, "off");
		MethodNode off = VanillaEarlyReturnsTest.method(split(VanillaEarlyReturnsTest.read(Vanilla.class)), "layers");
		assertEquals(List.of("light", "order"), intsInScopeAtTail(off), "the control");
	}

	/** A vanilla whose tail does not have the local in scope gives nothing back. */
	@Test void aLocalVanillasTailDoesNotHoldIsNotGivenBack() {
		ClassNode vanilla = VanillaEarlyReturnsTest.read(Vanilla.class);
		MethodNode layers = VanillaEarlyReturnsTest.method(vanilla, "layers");
		for (LocalVariableNode local : layers.localVariables) if (local.name.equals("next")) local.name = "renamed";
		assertEquals(List.of("light", "order", "dye"), intsInScopeAtTail(VanillaEarlyReturnsTest.method(split(vanilla), "layers")));
	}

	/**
	 * A path into the tail whose DECLARED frame dropped the local, though the data flow holds a value there:
	 * ThrownEnderpearl.tick's goto into its tail sits at a branch target declared with only {@code this} and the level.
	 * The verifier reads the declared state, so the local stays out (given back, the class failed verification at boot);
	 * with the join declaring it, it is given back, and the class verifies either way.
	 */
	@Test void aPathWhoseDeclaredFrameDroppedTheLocalKeepsItOut() throws Exception {
		for (boolean declared : new boolean[] {false, true}) {
			ClassNode node = joined(declared);
			ClassNode vanilla = joined(true);
			assertEquals(declared ? 1 : 0, TailLocals.restore(node.name, node.methods.getFirst(), vanilla), "join declares x: " + declared);
			Class<?> defined = define(node, node.name.replace('/', '.'));
			defined.getMethod("m", int.class).invoke(null, 7);
			defined.getMethod("m", int.class).invoke(null, 2);
		}
	}

	/** {@code static void m(int a)}: x = 1; if (a > 5) Math.abs(a); [join: frame]; goto tail; [tail: frame] return. */
	private static ClassNode joined(boolean joinDeclaresX) {
		ClassNode node = new ClassNode();
		node.version = Opcodes.V21;
		node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER;
		node.name = "fixture/taillocals/Joined" + joinDeclaresX;
		node.superName = "java/lang/Object";
		MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "m", "(I)V", null, null);
		org.objectweb.asm.tree.LabelNode start = new org.objectweb.asm.tree.LabelNode(), xs = new org.objectweb.asm.tree.LabelNode(),
				join = new org.objectweb.asm.tree.LabelNode(), tail = new org.objectweb.asm.tree.LabelNode(), end = new org.objectweb.asm.tree.LabelNode();
		var code = m.instructions;
		code.add(start);
		code.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ICONST_1));
		code.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ISTORE, 1));
		code.add(xs);
		code.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ILOAD, 0));
		code.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ICONST_5));
		code.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.IF_ICMPLE, join));
		code.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ILOAD, 0));
		code.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "abs", "(I)I", false));
		code.add(new org.objectweb.asm.tree.InsnNode(Opcodes.POP));
		code.add(join);
		code.add(new FrameNode(Opcodes.F_FULL, joinDeclaresX ? 2 : 1, joinDeclaresX ? new Object[] {Opcodes.INTEGER, Opcodes.INTEGER}
				: new Object[] {Opcodes.INTEGER}, 0, new Object[0]));
		code.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.GOTO, tail));
		code.add(tail);
		code.add(new FrameNode(Opcodes.F_FULL, 1, new Object[] {Opcodes.INTEGER}, 0, new Object[0]));
		code.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
		code.add(end);
		m.localVariables = new ArrayList<>(List.of(new LocalVariableNode("a", "I", null, start, end, 0),
				new LocalVariableNode("x", "I", null, xs, joinDeclaresX ? end : tail, 1)));
		m.maxLocals = 2;
		m.maxStack = 2;
		node.methods.add(m);
		return node;
	}

	/**
	 * The merged EquipmentLayerRenderer.renderLayers, split by the shipped row, against Fabric's native reference: the fifth
	 * {@code int} in scope at the tail — what Essential's emissive elytra layer asks for with {@code @Local(ordinal = 4)} —
	 * is vanilla's {@code nextOrder} again (before: only the three {@code int} arguments).
	 */
	@Test void theMergedRenderLayersTailHoldsVanillasNextOrderAgain() {
		String owner = "net/minecraft/client/renderer/entity/layers/EquipmentLayerRenderer";
		java.nio.file.Path merged = net.forbric.kernel.TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar");
		ClassNode node = node(net.forbric.kernel.TestFixtures.requireEntry(net.forbric.kernel.TestFixtures.Fixture.STAGED, merged, owner + ".class"));
		ClassNode vanilla = node(net.forbric.kernel.TestFixtures.requireEntry(net.forbric.kernel.TestFixtures.Fixture.STAGED, merged,
				"META-INF/forbric/native-reference/FABRIC/" + owner + ".class.bin"));
		String desc = "(Lnet/minecraft/client/resources/model/EquipmentClientInfo$LayerType;Lnet/minecraft/resources/ResourceKey;"
				+ "Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/world/item/ItemStack;Lcom/mojang/blaze3d/vertex/PoseStack;"
				+ "Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;II)V";
		MethodNode before = method(node, "renderLayers", desc);
		assertEquals(List.of("lightCoords", "outlineColor", "order"), intsInScopeAtTail(before), "the folded tail");
		assertTrue(VanillaEarlyReturns.restore(node, VanillaEarlyReturns.table().get(owner), name -> vanilla) >= 1);
		List<String> ints = intsInScopeAtTail(method(node, "renderLayers", desc));
		assertEquals("nextOrder", ints.get(4), "ints at the tail: " + ints);
		assertEquals(intsInScopeAtTail(method(vanilla, "renderLayers", desc)), ints, "as at vanilla's tail");
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new org.objectweb.asm.ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		for (MethodNode m : node.methods) if (m.name.equals(name) && m.desc.equals(desc)) return m;
		throw new AssertionError("no " + name + desc);
	}

	// ---------------------------------------------------------------------------------------------------------------

	private static ClassNode split(ClassNode vanilla) {
		return splitWith(vanilla);
	}

	private static ClassNode splitWith(ClassNode vanilla) {
		ClassNode folded = VanillaEarlyReturnsTest.read(Folded.class);
		MethodNode vanillaMethod = VanillaEarlyReturnsTest.method(VanillaEarlyReturnsTest.read(Vanilla.class), "layers");
		Map<String, Map<String, List<String>>> rows = Map.of("layers" + vanillaMethod.desc, VanillaEarlyReturns.labels(vanillaMethod));
		assertEquals(1, VanillaEarlyReturns.restore(folded, rows, vanilla == null ? null : name -> vanilla), "the guard is split");
		return folded;
	}

	private static List<String> intsInScopeAtTail(MethodNode method) {
		List<String> out = new ArrayList<>();
		for (LocalVariableNode local : inScopeAtTail(method)) if (local.desc.equals("I")) out.add(local.name);
		return out;
	}

	private static List<String> namesInScopeAtTail(MethodNode method) {
		return inScopeAtTail(method).stream().map(l -> l.name).toList();
	}

	/** The table's locals in scope at the last return, by slot — what Mixin names a TAIL handler's locals from. */
	private static List<LocalVariableNode> inScopeAtTail(MethodNode method) {
		AbstractInsnNode tail = VanillaEarlyReturns.lastReturn(method);
		int at = method.instructions.indexOf(tail);
		java.util.TreeMap<Integer, LocalVariableNode> scope = new java.util.TreeMap<>();
		for (LocalVariableNode local : method.localVariables) {
			if (method.instructions.indexOf(local.start) <= at && at < method.instructions.indexOf(local.end)) scope.put(local.index, local);
		}
		return new ArrayList<>(scope.values());
	}

	private static FrameNode tailFrame(MethodNode method) {
		for (AbstractInsnNode insn = VanillaEarlyReturns.lastReturn(method).getPrevious(); insn != null && insn.getOpcode() < 0; insn = insn.getPrevious()) {
			if (insn instanceof FrameNode frame) return frame;
		}
		throw new AssertionError("no tail frame");
	}

	private static Class<?> define(ClassNode node, String binaryName) {
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		byte[] bytes = writer.toByteArray();
		return new ClassLoader(TailLocalsTest.class.getClassLoader()) {
			Class<?> defined = defineClass(binaryName, bytes, 0, bytes.length);
		}.defined;
	}
}
