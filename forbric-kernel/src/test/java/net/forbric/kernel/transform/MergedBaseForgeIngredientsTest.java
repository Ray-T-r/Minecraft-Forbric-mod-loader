package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * {@code Ingredient.CODEC} stored straight from NeoForge's factory means a {@code forge:*} ingredient type is a
 * recipe parsing error. One inserted instruction before the single {@code PUTSTATIC CODEC} routes the NeoForge
 * codec through the carrier's own Forge dispatch; every other store in {@code <clinit>} is left exactly alone.
 */
class MergedBaseForgeIngredientsTest {
	private static final Path MERGED_BASE = Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run",
			"merged-base", "patched-mc-merged-26.2.jar").normalize();
	private static final String INGREDIENT = "net/minecraft/world/item/crafting/Ingredient";
	private static final String NEO_FACTORY = "net/neoforged/neoforge/common/crafting/IngredientCodecs";
	private static final String KERNEL = "net/forbric/kernel/runtime/KernelForgeIngredients";
	private static final String CODEC_TO_CODEC = "(Lcom/mojang/serialization/Codec;)Lcom/mojang/serialization/Codec;";

	@Test
	void thePremiseCodecIsStoredStraightFromNeoForgesFactoryAndNoForgeCodecIsNamed() throws Exception {
		byte[] bytes = bytesOf(INGREDIENT);
		MethodNode clinit = clinit(parse(bytes));
		List<FieldInsnNode> stores = stores(clinit, "CODEC");
		assertEquals(1, stores.size(), "one PUTSTATIC CODEC expected");
		AbstractInsnNode previous = realPrevious(stores.getFirst());
		assertTrue(previous instanceof MethodInsnNode call && NEO_FACTORY.equals(call.owner) && "codec".equals(call.name),
				"CODEC must come straight from IngredientCodecs.codec; a different shape must be re-derived, not re-blessed");
		assertFalse(new String(bytes, StandardCharsets.ISO_8859_1).contains("net/minecraftforge/common/ForgeHooks"),
				"the base now composes a Forge ingredient codec itself — this repair would double-wrap it");
	}

	@Test
	void theKernelCallIsTheInstructionImmediatelyBeforeTheSingleCodecStore() throws Exception {
		MethodNode clinit = clinit(parse(transform(bytesOf(INGREDIENT))));
		List<FieldInsnNode> stores = stores(clinit, "CODEC");
		assertEquals(1, stores.size());
		AbstractInsnNode previous = realPrevious(stores.getFirst());
		assertTrue(previous instanceof MethodInsnNode, "expected the kernel call right before PUTSTATIC CODEC");
		MethodInsnNode call = (MethodInsnNode) previous;
		assertEquals(KERNEL, call.owner);
		assertEquals("alsoAskMinecraftForge", call.name);
		assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
		assertEquals(CODEC_TO_CODEC, call.desc, "raw Codec in and out, or the stack moves");
		assertTrue(realPrevious(call) instanceof MethodInsnNode factory && NEO_FACTORY.equals(factory.owner),
				"NeoForge's factory result is what gets wrapped");
	}

	@Test
	void everyOtherStoreInTheStaticInitializerIsUntouched() throws Exception {
		MethodNode before = clinit(parse(bytesOf(INGREDIENT)));
		MethodNode after = clinit(parse(transform(bytesOf(INGREDIENT))));
		assertEquals(describeStores(before), describeStores(after),
				"the set and order of static stores must be unchanged — only CODEC's value is wrapped");
		for (String other : List.of("CONTENTS_STREAM_CODEC", "OPTIONAL_CONTENTS_STREAM_CODEC", "NON_AIR_HOLDER_SET_CODEC")) {
			for (FieldInsnNode store : stores(after, other)) {
				AbstractInsnNode previous = realPrevious(store);
				assertFalse(previous instanceof MethodInsnNode call && KERNEL.equals(call.owner),
						other + " must not be wrapped — it is not a codec the Forge dispatch composes");
			}
		}
		assertEquals(1, count(after), "exactly one kernel call in <clinit>");
	}

	@Test
	void aSecondPassChangesNothingFurther() throws Exception {
		byte[] once = transform(bytesOf(INGREDIENT));
		assertSame(once, new ForbricMergedBaseCompatTransformer().transform(INGREDIENT.replace('/', '.'), once, null));
	}

	private static int count(MethodNode method) {
		int n = 0;
		for (AbstractInsnNode insn : method.instructions) if (insn instanceof MethodInsnNode call && KERNEL.equals(call.owner)) n++;
		return n;
	}

	private static List<String> describeStores(MethodNode method) {
		List<String> out = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC) out.add(field.name + field.desc);
		}
		return out;
	}

	private static List<FieldInsnNode> stores(MethodNode method, String name) {
		List<FieldInsnNode> out = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC && name.equals(field.name)) out.add(field);
		}
		return out;
	}

	private static AbstractInsnNode realPrevious(AbstractInsnNode insn) {
		AbstractInsnNode previous = insn.getPrevious();
		while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
		return previous;
	}

	private static MethodNode clinit(ClassNode node) {
		for (MethodNode method : node.methods) if ("<clinit>".equals(method.name)) return method;
		throw new AssertionError("no <clinit>");
	}

	private static byte[] transform(byte[] before) {
		return new ForbricMergedBaseCompatTransformer().transform(INGREDIENT.replace('/', '.'), before, null);
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] bytesOf(String internal) throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		try (ZipFile zip = new ZipFile(MERGED_BASE.toFile())) {
			ZipEntry entry = zip.getEntry(internal + ".class");
			assertNotNull(entry, internal);
			try (InputStream in = zip.getInputStream(entry)) {
				return in.readAllBytes();
			}
		}
	}
}
