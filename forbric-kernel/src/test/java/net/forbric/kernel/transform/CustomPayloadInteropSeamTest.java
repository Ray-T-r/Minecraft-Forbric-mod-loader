package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.forbric.kernel.TestFixtures;

/**
 * The merged base's PASS 4b splice, made by the kernel: dormant on the merged base, whose codec class already asks
 * {@code PayloadInterop}; landed on the genuine NeoForge base, whose {@code CustomPacketPayload$1} never went through the
 * merge tool. Both read against the staged jars, so the class each base's {@code codec} overload instantiates is the
 * one the jar has, not one this test names.
 */
class CustomPayloadInteropSeamTest {
	private static final Path MERGED = TestFixtures.stagedBase().normalize();
	private static final Path GENUINE = TestFixtures.genuineBase().normalize();
	private static final String PAYLOAD = CustomPayloadInteropSeam.PAYLOAD;

	@AfterEach void reset() { System.clearProperty(CustomPayloadInteropSeam.PROPERTY); }

	@Test void onTheMergedBaseTheCodecClassAlreadyAsksTheHelperSoNothingChanges() throws Exception {
		Function<String, byte[]> merged = VanillaMemberTwinInjectorTest.reader(MERGED);
		assertEquals(PAYLOAD + "$1$forbricneo", CustomPayloadInteropSeam.instantiated(merged.apply(PAYLOAD + ".class")),
				"premise: the merged codec overload instantiates the renamed NeoForge twin");
		byte[] twin = merged.apply(PAYLOAD + "$1$forbricneo.class");
		assertTrue(calls(method(node(twin), "findCodec"), CustomPayloadInteropSeam.LEGACY_INTEROP),
				"premise: the merge tool spliced the call under the old loader's owner");

		CustomPayloadInteropSeam seam = new CustomPayloadInteropSeam(merged);
		assertSame(twin, seam.transform((PAYLOAD + "$1$forbricneo").replace('/', '.'), twin, null),
				"the splice is already there under the old owner: stand down");
		byte[] adopted = adopt(twin);
		assertSame(adopted, seam.transform((PAYLOAD + "$1$forbricneo").replace('/', '.'), adopted, null),
				"and under the kernel's owner, once the merged-base compat transformer adopted it");
		byte[] vanillaShaped = merged.apply(PAYLOAD + "$1.class");
		assertSame(vanillaShaped, seam.transform((PAYLOAD + "$1").replace('/', '.'), vanillaShaped, null),
				"the merged base's vanilla-shaped $1 is not the class NeoForge's overload builds: untouched");
		for (String sibling : List.of("$Type", "$TypeAndCodec", "$FallbackProvider")) {
			byte[] bytes = merged.apply(PAYLOAD + sibling + ".class");
			assertSame(bytes, seam.transform((PAYLOAD + sibling).replace('/', '.'), bytes, null), sibling);
		}
	}

	@Test void onTheGenuineBaseNeoForgesCodecClassGetsTheMergedBasesLookup() throws Exception {
		Function<String, byte[]> genuine = VanillaMemberTwinInjectorTest.reader(GENUINE);
		assertEquals(PAYLOAD + "$1", CustomPayloadInteropSeam.instantiated(genuine.apply(PAYLOAD + ".class")),
				"premise: the genuine codec overload instantiates $1, the class fabric-networking's mixin names");
		byte[] raw = genuine.apply(PAYLOAD + "$1.class");
		MethodNode before = method(node(raw), "findCodec");
		assertFalse(calls(before, CustomPayloadInteropSeam.INTEROP), "premise: NeoForge's lookup is id-only");
		assertTrue(calls(before, "net/neoforged/neoforge/network/registration/NetworkRegistry"), "premise: NeoForge's own lookup");

		byte[] out = new CustomPayloadInteropSeam(genuine).transform((PAYLOAD + "$1").replace('/', '.'), raw, null);
		assertNotSame(raw, out);
		ClassNode node = node(out);
		MethodNode find = method(node, "findCodec");
		List<AbstractInsnNode> real = new ArrayList<>();
		for (AbstractInsnNode insn : find.instructions) if (insn.getOpcode() >= 0) real.add(insn);
		List<String> context = new ArrayList<>();
		for (AbstractInsnNode insn : real) if (insn instanceof FieldInsnNode field) context.add(field.name);
		assertEquals(List.of("val$idToType", "val$protocol", "val$packetFlow", "val$fallback"), context,
				"PASS 4b's context, in PASS 4b's order");
		MethodInsnNode call = (MethodInsnNode) real.get(real.size() - 3);
		assertEquals(CustomPayloadInteropSeam.INTEROP, call.owner);
		assertEquals("findCodec", call.name);
		assertEquals(CustomPayloadInteropSeam.FIND, call.desc);
		assertEquals(Opcodes.ARETURN, real.getLast().getOpcode());
		new Analyzer<>(new BasicVerifier()).analyze(node.name, find);
		// Exactly one seam, which is what PostMixinCodecReturnArbitration requires to reconcile around it.
		assertEquals(1, real.stream().filter(i -> i instanceof MethodInsnNode m && m.owner.equals(CustomPayloadInteropSeam.INTEROP)).count());
		for (MethodNode other : node.methods) {
			if (other == find) continue;
			MethodNode original = method(node(raw), other.name, other.desc);
			assertEquals(original.instructions.size(), other.instructions.size(), other.name + " is not the seam's to touch");
		}

		byte[] again = new CustomPayloadInteropSeam(genuine).transform((PAYLOAD + "$1").replace('/', '.'), out, null);
		assertSame(out, again, "a second pass finds the seam and stands down");
	}

	@Test void withoutTheInterfaceItGuessesNothing() throws Exception {
		byte[] raw = VanillaMemberTwinInjectorTest.reader(GENUINE).apply(PAYLOAD + "$1.class");
		assertSame(raw, new CustomPayloadInteropSeam(path -> null).transform((PAYLOAD + "$1").replace('/', '.'), raw, null));
	}

	@Test void theSwitchLeavesNeoForgesLookupAlone() throws Exception {
		Function<String, byte[]> genuine = VanillaMemberTwinInjectorTest.reader(GENUINE);
		byte[] raw = genuine.apply(PAYLOAD + "$1.class");
		System.setProperty(CustomPayloadInteropSeam.PROPERTY, "off");
		assertSame(raw, new CustomPayloadInteropSeam(genuine).transform((PAYLOAD + "$1").replace('/', '.'), raw, null));
		assertTrue(new CustomPayloadInteropSeam(genuine).anchors().anchors().isEmpty());
	}

	/** The merged-base compat transformer's adoption of the old owner, so the second stand-down is the shape it leaves. */
	private static byte[] adopt(byte[] bytes) {
		ClassNode node = node(bytes);
		for (MethodNode m : node.methods) for (AbstractInsnNode insn : m.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(CustomPayloadInteropSeam.LEGACY_INTEROP)) {
				call.owner = CustomPayloadInteropSeam.INTEROP;
			}
		}
		org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static MethodNode method(ClassNode node, String name) {
		return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElseThrow();
	}

	private static boolean calls(MethodNode method, String owner) {
		for (AbstractInsnNode insn : method.instructions) if (insn instanceof MethodInsnNode call && call.owner.equals(owner)) return true;
		return false;
	}
}
