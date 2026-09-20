package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
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
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** {@code WeightedVariants.first}: one Forge reader in the base, zero writers — the merge dropped Forge's write. */
class MergedBaseWeightedVariantsFirstTest {
	private static final Path MERGED_BASE = Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run",
			"merged-base", "patched-mc-merged-26.2.jar").normalize();
	private static final String OWNER = "net/minecraft/client/renderer/block/dispatch/WeightedVariants";

	@Test
	void thePremiseTheFieldHasOneForgeReaderAndNoWriter() throws Exception {
		ClassNode node = parse(bytesOf(OWNER));
		assertTrue(node.fields.stream().anyMatch(f -> "first".equals(f.name)), "the field is gone — re-derive");
		assertEquals(List.of(), writers(node), "a rebuilt base writes it: the repair is redundant now");
		List<String> readers = readers(node);
		assertEquals(List.of("particleMaterial(Lnet/minecraftforge/client/model/data/ModelData;)Lnet/minecraft/client/resources/model/sprite/Material$Baked;"),
				readers, "Forge's particleMaterial(ModelData) is the only reader");
	}

	@Test
	void theWriteIsInTheConstructorRightAfterTheFirstModelIsComputed() throws Exception {
		ClassNode after = parse(transform(bytesOf(OWNER)));
		assertEquals(List.of("<init>"), writers(after));
		MethodNode init = after.methods.stream().filter(m -> "<init>".equals(m.name)).findFirst().orElseThrow();
		FieldInsnNode write = null;
		int storeIndex = -1, writeIndex = -1, index = 0;
		for (AbstractInsnNode insn : init.instructions) {
			if (insn instanceof VarInsnNode var && var.getOpcode() == Opcodes.ASTORE && var.var == 2 && storeIndex < 0) storeIndex = index;
			if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD && "first".equals(field.name)) {
				write = field;
				writeIndex = index;
			}
			index++;
		}
		assertNotNull(write);
		assertTrue(storeIndex >= 0 && writeIndex > storeIndex, "the write must follow the ASTORE 2 that defines the local");
		AbstractInsnNode value = previousReal(write), self = previousReal(value);
		assertTrue(value instanceof VarInsnNode v && v.getOpcode() == Opcodes.ALOAD && v.var == 2);
		assertTrue(self instanceof VarInsnNode t && t.getOpcode() == Opcodes.ALOAD && t.var == 0);
	}

	@Test
	void aSecondPassChangesNothingFurther() throws Exception {
		byte[] once = transform(bytesOf(OWNER));
		assertSame(once, new ForbricMergedBaseCompatTransformer().transform(OWNER.replace('/', '.'), once, null));
	}

	@Test
	void theSiblingVariantClassIsUntouched() throws Exception {
		byte[] other = bytesOf("net/minecraft/client/renderer/block/dispatch/SingleVariant");
		assertSame(other, new ForbricMergedBaseCompatTransformer().transform(
				"net.minecraft.client.renderer.block.dispatch.SingleVariant", other, null));
	}

	private static List<String> writers(ClassNode node) {
		List<String> out = new ArrayList<>();
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD && OWNER.equals(f.owner) && "first".equals(f.name)) out.add(method.name);
			}
		}
		return out;
	}

	private static List<String> readers(ClassNode node) {
		List<String> out = new ArrayList<>();
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETFIELD && OWNER.equals(f.owner) && "first".equals(f.name)) out.add(method.name + method.desc);
			}
		}
		return out;
	}

	private static AbstractInsnNode previousReal(AbstractInsnNode insn) {
		AbstractInsnNode previous = insn.getPrevious();
		while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
		return previous;
	}

	private static byte[] transform(byte[] before) {
		return new ForbricMergedBaseCompatTransformer().transform(OWNER.replace('/', '.'), before, null);
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
