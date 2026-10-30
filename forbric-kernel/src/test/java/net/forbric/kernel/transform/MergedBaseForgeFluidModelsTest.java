package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * The two seams genuine Forge has inside {@code FluidRenderer.tesselate}: the model ask after the lookup, and the
 * tint ask where the model has no tint source. The first merge lost both; the edit-splicing merge keeps the model ask
 * and loses the tint ask. A lost seam is re-inserted, a kept one is routed through the kernel's helper instead of
 * asked a second time; the stack proof is ASM's verifier over the rewritten method.
 */
class MergedBaseForgeFluidModelsTest {
	private static final Path MERGED_BASE = TestFixtures.stagedBase();
	private static final String RENDERER = "net/minecraft/client/renderer/block/FluidRenderer";
	private static final String KERNEL = "net/forbric/kernel/runtime/KernelForgeFluids";
	private static final String TESSELATE = "(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;"
			+ "Lnet/minecraft/client/renderer/block/FluidRenderer$Output;Lnet/minecraft/world/level/block/state/BlockState;"
			+ "Lnet/minecraft/world/level/material/FluidState;)V";

	/**
	 * The shape the repair is derived from: one model lookup, a -1 tint fallback arm, and no MinecraftForge member
	 * except Forge's own model ask ({@code IClientFluidTypeExtensions.of} + {@code getModel}, stored back into the
	 * lookup's slot), which the edit-splicing merge keeps and the first merge lost. Anything more Forge in this class —
	 * the tint ask above all — means the repair may be redundant: re-derive it.
	 */
	@Test
	void thePremiseOneLookupAtMostForgesOwnModelAskAndAMinusOneTintArm() throws Exception {
		byte[] bytes = bytesOf(RENDERER);
		ClassNode node = parse(bytes);
		MethodNode tesselate = tesselate(node);
		assertEquals(1, lookups(tesselate).size(), "the repair assumes one FluidStateModelSet.get in tesselate");
		int slot = ((VarInsnNode) nextReal(lookups(tesselate).getFirst())).var;
		assertTrue(nativeAsks(tesselate, slot).size() <= 1, "at most one Forge model ask, stored back into the lookup's slot");
		java.util.Set<String> forge = new java.util.TreeSet<>();
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call && call.owner.startsWith("net/minecraftforge/")) forge.add(call.owner + "." + call.name);
				if (insn instanceof org.objectweb.asm.tree.FieldInsnNode field && (field.owner + field.desc).contains("net/minecraftforge/")) forge.add(field.owner + "." + field.name);
				if (insn instanceof org.objectweb.asm.tree.TypeInsnNode type && type.desc.contains("net/minecraftforge/")) forge.add(type.desc);
			}
		}
		forge.removeAll(List.of(EXTENSIONS + ".of", EXTENSIONS + ".getModel"));
		assertEquals(java.util.Set.of(), forge, "the merged FluidRenderer names a Forge member beyond its own model ask — re-derive the repair, it may be redundant");
		assertFalse(new String(bytes, StandardCharsets.ISO_8859_1).contains("getTintColor"), "the merged FluidRenderer asks Forge for the tint itself");
		InsnNode arm = tintArm(tesselate);
		assertNotNull(arm, "the IFNULL after fluidTintSource() must target ICONST_M1; ISTORE (vanilla's -1 tint)");
	}

	/**
	 * The fluid's extensions are asked for its model exactly once, through the kernel: as the funnel right after the
	 * lookup's store when the merged body lost Forge's ask, or as that very ask routed through the helper (same
	 * operands, same store) when the merged body keeps it — never both. Checked on the class as staged and on the
	 * same class with Forge's ask taken out, so both shapes are exercised whichever one the base has.
	 */
	@Test
	void theModelFunnelSitsRightAfterTheLookupsStoreAndReloadsTheSameSlot() throws Exception {
		byte[] staged = bytesOf(RENDERER);
		boolean stagedAsks = !nativeAsks(tesselate(parse(staged)), slotOf(tesselate(parse(staged)))).isEmpty();
		for (byte[] bytes : stagedAsks ? List.of(staged, withoutNativeAsk(staged)) : List.of(staged)) {
			MethodNode before = tesselate(parse(bytes));
			int slot = slotOf(before);
			boolean asks = !nativeAsks(before, slot).isEmpty();
			MethodNode after = tesselate(parse(transform(bytes)));
			assertEquals(List.of(), nativeAsks(after, slot), "no raw Forge model ask may survive beside the kernel's");
			List<MethodInsnNode> funnels = calls(after, "model"), routed = calls(after, "modelFromExtensions");
			assertEquals(1, funnels.size() + routed.size(), "exactly one model ask");
			if (asks) {
				assertEquals(1, routed.size(), "the merged body's own ask is the one that is kept");
				MethodInsnNode ask = routed.getFirst();
				assertEquals(Opcodes.INVOKESTATIC, ask.getOpcode());
				AbstractInsnNode model = previousReal(ask), pos = previousReal(model), level = previousReal(pos), state = previousReal(level);
				assertEquals(List.of(5, 1, 2, slot), List.of(((VarInsnNode) state).var, ((VarInsnNode) level).var,
						((VarInsnNode) pos).var, ((VarInsnNode) model).var), "the native ask's own operands: fluidState, level, pos, model");
				assertTrue(nextReal(ask) instanceof VarInsnNode back && back.getOpcode() == Opcodes.ASTORE && back.var == slot,
						"the answer still goes back into the model's own slot");
				continue;
			}
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
			new Analyzer<>(new BasicVerifier()).analyze(RENDERER, after);
		}
	}

	private static final String EXTENSIONS = "net/minecraftforge/client/extensions/common/IClientFluidTypeExtensions";

	private static int slotOf(MethodNode tesselate) {
		return ((VarInsnNode) nextReal(lookups(tesselate).getFirst())).var;
	}

	/** Forge's own {@code getModel} asks whose answer is stored back into {@code slot}. */
	private static List<MethodInsnNode> nativeAsks(MethodNode method, int slot) {
		List<MethodInsnNode> out = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && EXTENSIONS.equals(call.owner) && "getModel".equals(call.name)
					&& nextReal(call) instanceof VarInsnNode store && store.getOpcode() == Opcodes.ASTORE && store.var == slot) out.add(call);
		}
		return out;
	}

	/** The class with Forge's own model ask taken out — everything between the lookup's store and the ask's store. */
	private static byte[] withoutNativeAsk(byte[] bytes) {
		ClassNode node = parse(bytes);
		MethodNode tesselate = tesselate(node);
		int slot = slotOf(tesselate);
		AbstractInsnNode from = nextReal(lookups(tesselate).getFirst()), to = nextReal(nativeAsks(tesselate, slot).getFirst());
		AbstractInsnNode stop = to.getNext();
		for (AbstractInsnNode at = from.getNext(); at != null && at != stop;) {
			AbstractInsnNode next = at.getNext();
			if (at.getOpcode() >= 0) tesselate.instructions.remove(at);
			at = next;
		}
		assertEquals(List.of(), nativeAsks(tesselate, slot));
		org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
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
		TestFixtures.requireFile(Fixture.STAGED, MERGED_BASE, "staged merged base absent");
		try (ZipFile zip = new ZipFile(MERGED_BASE.toFile())) {
			ZipEntry entry = zip.getEntry(internal + ".class");
			assertNotNull(entry, internal);
			try (InputStream in = zip.getInputStream(entry)) {
				return in.readAllBytes();
			}
		}
	}
}
