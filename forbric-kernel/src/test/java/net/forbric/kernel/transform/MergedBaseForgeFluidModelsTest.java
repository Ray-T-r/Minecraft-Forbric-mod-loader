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
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * The two seams genuine Forge has inside {@code FluidRenderer.tesselate} and the merge lost: the model ask after
 * the lookup, and the tint ask where the model has no tint source. Both are re-inserted; the stack proof is
 * ASM's verifier over the rewritten method.
 */
class MergedBaseForgeFluidModelsTest {
	private static final Path MERGED_BASE = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", System.getProperty("user.dir") + "/../forbric-loader"), "run",
			"merged-base", "patched-mc-merged-26.2.jar").normalize();
	private static final String RENDERER = "net/minecraft/client/renderer/block/FluidRenderer";
	private static final String KERNEL = "net/forbric/kernel/runtime/KernelForgeFluids";
	private static final String TESSELATE = "(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;"
			+ "Lnet/minecraft/client/renderer/block/FluidRenderer$Output;Lnet/minecraft/world/level/block/state/BlockState;"
			+ "Lnet/minecraft/world/level/material/FluidState;)V";

	@Test
	void thePremiseOneLookupNoForgeAskAndAMinusOneTintArm() throws Exception {
		byte[] bytes = bytesOf(RENDERER);
		MethodNode tesselate = tesselate(parse(bytes));
		assertEquals(1, lookups(tesselate).size(), "the repair assumes one FluidStateModelSet.get in tesselate");
		assertFalse(new String(bytes, StandardCharsets.ISO_8859_1).contains("net/minecraftforge/"),
				"the merged FluidRenderer now names a Forge type — re-derive the repair, it may be redundant");
		InsnNode arm = tintArm(tesselate);
		assertNotNull(arm, "the IFNULL after fluidTintSource() must target ICONST_M1; ISTORE (vanilla's -1 tint)");
	}

	@Test
	void theModelFunnelSitsRightAfterTheLookupsStoreAndReloadsTheSameSlot() throws Exception {
		MethodNode before = tesselate(parse(bytesOf(RENDERER)));
		int slot = ((VarInsnNode) nextReal(lookups(before).getFirst())).var;
		MethodNode after = tesselate(parse(transform(bytesOf(RENDERER))));
		List<MethodInsnNode> funnels = calls(after, "model");
		assertEquals(1, funnels.size());
		MethodInsnNode funnel = funnels.getFirst();
		assertEquals(Opcodes.INVOKESTATIC, funnel.getOpcode());
		AbstractInsnNode pos = previousReal(funnel), level = previousReal(pos), state = previousReal(level), model = previousReal(state);
		assertEquals(List.of(slot, 5, 1, 2), List.of(((VarInsnNode) model).var, ((VarInsnNode) state).var,
				((VarInsnNode) level).var, ((VarInsnNode) pos).var), "ALOAD n / ALOAD 5 (fluidState) / ALOAD 1 (level) / ALOAD 2 (pos)");
		AbstractInsnNode store = nextReal(funnel);
		assertTrue(store instanceof VarInsnNode back && back.getOpcode() == Opcodes.ASTORE && back.var == slot,
				"the funnel's answer must go back into the model's own slot");
		AbstractInsnNode original = previousReal(model);
		assertTrue(original instanceof VarInsnNode first && first.getOpcode() == Opcodes.ASTORE && first.var == slot,
				"the funnel follows the lookup's own store");
	}

	@Test
	void theTintArmAsksForgeInsteadOfPushingMinusOne() throws Exception {
		MethodNode after = tesselate(parse(transform(bytesOf(RENDERER))));
		List<MethodInsnNode> tints = calls(after, "tintColor");
		assertEquals(1, tints.size());
		assertEquals("(Lnet/minecraft/world/level/material/FluidState;)I", tints.getFirst().desc);
		assertTrue(previousReal(tints.getFirst()) instanceof VarInsnNode load && load.var == 5, "ALOAD 5 feeds it");
		assertTrue(nextReal(tints.getFirst()) instanceof VarInsnNode store && store.getOpcode() == Opcodes.ISTORE,
				"the int lands where -1 used to");
		assertTrue(tintArm(after) == null, "no ICONST_M1 arm may survive after fluidTintSource()");
	}

	@Test
	void theRewrittenMethodPassesAsmsVerifier() throws Exception {
		ClassNode after = parse(transform(bytesOf(RENDERER)));
		new Analyzer<>(new BasicVerifier()).analyze(after.name, tesselate(after));
	}

	@Test
	void aSecondPassChangesNothingFurther() throws Exception {
		byte[] once = transform(bytesOf(RENDERER));
		assertSame(once, new ForbricMergedBaseCompatTransformer().transform(RENDERER.replace('/', '.'), once, null));
	}

	private static InsnNode tintArm(MethodNode method) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && "fluidTintSource".equals(call.name)
					&& nextReal(call) instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFNULL) {
				AbstractInsnNode target = jump.label;
				while (target != null && target.getOpcode() < 0) target = target.getNext();
				if (target instanceof InsnNode constant && constant.getOpcode() == Opcodes.ICONST_M1) return constant;
			}
		}
		return null;
	}

	private static List<MethodInsnNode> lookups(MethodNode method) {
		List<MethodInsnNode> out = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && "net/minecraft/client/renderer/block/FluidStateModelSet".equals(call.owner)
					&& "get".equals(call.name)) out.add(call);
		}
		return out;
	}

	private static List<MethodInsnNode> calls(MethodNode method, String name) {
		List<MethodInsnNode> out = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && KERNEL.equals(call.owner) && name.equals(call.name)) out.add(call);
		}
		return out;
	}

	private static AbstractInsnNode nextReal(AbstractInsnNode insn) {
		AbstractInsnNode next = insn.getNext();
		while (next != null && next.getOpcode() < 0) next = next.getNext();
		return next;
	}

	private static AbstractInsnNode previousReal(AbstractInsnNode insn) {
		AbstractInsnNode previous = insn.getPrevious();
		while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
		return previous;
	}

	private static MethodNode tesselate(ClassNode node) {
		for (MethodNode method : node.methods) if ("tesselate".equals(method.name) && TESSELATE.equals(method.desc)) return method;
		throw new AssertionError("no tesselate with the expected descriptor");
	}

	private static byte[] transform(byte[] before) {
		return new ForbricMergedBaseCompatTransformer().transform(RENDERER.replace('/', '.'), before, null);
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
