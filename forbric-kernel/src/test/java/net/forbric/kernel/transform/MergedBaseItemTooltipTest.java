/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * Covers the tooltip repair, whose whole symptom was a load-report row.
 *
 * <p>The merged {@code ItemStack.getTooltipLines} used to carry exactly ONE event call, MinecraftForge's
 * {@code ForgeEventFactory.onItemTooltip}, so NeoForge's {@code ItemTooltipEvent} was never constructed and every
 * NeoForge mod that adds a line to an item's tooltip added it to nothing. A merge that keeps both families' edits gives
 * the method NeoForge's {@code EventHooks.onItemTooltip} back, and there the repair has to stand down, or every NeoForge
 * tooltip line is drawn twice.
 *
 * <p>Asserted against the REAL merged base rather than a fixture, because the thing being claimed is a fact about
 * that jar; the shape the repair exists for is then derived from it by taking NeoForge's call away, so the repair's
 * own work is exercised whichever shape the staged base has.
 */
class MergedBaseItemTooltipTest {
	private static final Path MERGED_BASE = TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar");
	private static final String ITEM_STACK = "net/minecraft/world/item/ItemStack";
	private static final String BRIDGE = "net/forbric/kernel/runtime/KernelItemTooltips";
	private static final String FORGE_FACTORY = "net/minecraftforge/event/ForgeEventFactory";

	private static final String NEO_TOOLTIP = "Lnet/neoforged/neoforge/event/entity/player/ItemTooltipEvent;";

	@Test
	void theRepairLeavesExactlyOneNeoForgeTooltipEventOnTheStagedBase() throws Exception {
		byte[] bytes = readMergedBase();

		MethodNode before = tooltipLines(parse(bytes));
		assertNotNull(before, "the merged ItemStack must still have getTooltipLines");
		assertEquals(1, calls(before, FORGE_FACTORY, "onItemTooltip"), "MinecraftForge's call, once");
		int nativeNeo = nativeNeoForgeCalls(before);
		assertTrue(nativeNeo <= 1, "the merge posts NeoForge's event at most once");

		MethodNode after = tooltipLines(parse(transform(bytes)));
		assertEquals(1, nativeNeoForgeCalls(after) + calls(after, BRIDGE, "postNeoForge"),
				"one NeoForge ItemTooltipEvent per tooltip: the native call, or the repair where the merge kept none");
		assertEquals(nativeNeo == 0 ? 1 : 0, calls(after, BRIDGE, "postNeoForge"),
				"the repair posts exactly where the merged body does not");
	}

	@Test
	void theRepairPostsNeoForgesEventWithTheRealContextAndDisplay() throws Exception {
		// The shape the repair is for, derived from the staged body by taking NeoForge's own call out of the family:
		// the same instruction to an owner outside NeoForge's namespace is, to the repair, no NeoForge delivery at all.
		byte[] bytes = withoutNeoForgesCall(readMergedBase());

		MethodNode tooltip = tooltipLines(parse(transform(bytes)));
		assertNotNull(tooltip);
		MethodInsnNode posted = null;
		for (AbstractInsnNode insn : tooltip.instructions.toArray()) {
			if (insn instanceof MethodInsnNode call && BRIDGE.equals(call.owner)) posted = call;
		}
		assertNotNull(posted, "the repair must add the NeoForge post");
		assertEquals("postNeoForge", posted.name);

		// Six ALOADs, and the last two are the context (parameter 1) and the display local — not placeholders.
		List<VarInsnNode> loads = new ArrayList<>();
		for (AbstractInsnNode cursor = posted.getPrevious();
				cursor != null && cursor.getOpcode() == Opcodes.ALOAD; cursor = cursor.getPrevious()) {
			loads.add(0, (VarInsnNode) cursor);
		}
		assertEquals(6, loads.size(), "stack, player, list, flag, context, display");
		assertEquals(0, loads.get(0).var, "the stack is this");
		assertEquals(1, loads.get(4).var, "the Item.TooltipContext is the method's own first parameter");
		assertTrue(loads.get(5).var > 3, "the TooltipDisplay is a local the method computed, not a parameter");
	}

	/**
	 * gate-m9 asks whether a NeoForge mod can add a tooltip line by the line either outcome prints: the repair's when it
	 * posts, the merged-base transformer's when the base already does. Both must match what the code prints: the
	 * transformer's line is captured from the transformer running, and the repair's is read from the compiled
	 * KernelItemTooltips itself (its POSTED constant, which is what it logs), so neither half can rot unseen.
	 */
	@Test
	void theGateRecognisesBothWaysTheEventGetsPosted(@org.junit.jupiter.api.io.TempDir Path scratch) throws Exception {
		String pattern = net.forbric.kernel.GateLogContract.pattern(Path.of("run/gate-m9-client.sh"),
				"a NeoForge mod can add a line to an item's tooltip");
		String[] log = new String[1];
		byte[] staged = readMergedBase();
		net.forbric.kernel.GateLogContract.capture(() -> transform(staged), log);
		boolean nativeNeo = nativeNeoForgeCalls(tooltipLines(parse(staged))) > 0;
		assertEquals(nativeNeo ? 1 : 0, net.forbric.kernel.GateLogContract.count(scratch, pattern, log[0]),
				"the stand-down line, exactly when the staged base posts NeoForge's event itself: " + log[0]);
		String repairLine = "[Render thread/INFO]: " + postedLine() + "\n";
		assertEquals(1, net.forbric.kernel.GateLogContract.count(scratch, pattern, repairLine),
				"the repair's own first-post line (KernelItemTooltips): " + repairLine);
	}

	/** The line KernelItemTooltips logs on its first post, read from its compiled class. */
	private static String postedLine() throws Exception {
		Path runtime = Path.of("build/classes/java/runtime/net/forbric/kernel/runtime/KernelItemTooltips.class");
		net.forbric.kernel.TestFixtures.require(net.forbric.kernel.TestFixtures.Fixture.GAME_SIDE, java.nio.file.Files.isRegularFile(runtime),
				"requires the runtime source set");
		ClassNode node = parse(java.nio.file.Files.readAllBytes(runtime));
		for (org.objectweb.asm.tree.FieldNode field : node.fields) if (field.name.equals("POSTED") && field.value instanceof String text) return text;
		throw new AssertionError("KernelItemTooltips no longer declares the line it logs as POSTED");
	}

	/** NeoForge hook calls in {@code method} that hand back NeoForge's ItemTooltipEvent. */
	private static int nativeNeoForgeCalls(MethodNode method) {
		int found = 0;
		for (AbstractInsnNode insn : method.instructions.toArray()) {
			if (insn instanceof MethodInsnNode call && call.owner.startsWith("net/neoforged/")
					&& Type.getReturnType(call.desc).getDescriptor().equals(NEO_TOOLTIP)) found++;
		}
		return found;
	}

	private static byte[] withoutNeoForgesCall(byte[] bytes) {
		ClassNode node = parse(bytes);
		MethodNode tooltip = tooltipLines(node);
		for (AbstractInsnNode insn : tooltip.instructions.toArray()) {
			if (insn instanceof MethodInsnNode call && call.owner.startsWith("net/neoforged/")
					&& Type.getReturnType(call.desc).getDescriptor().equals(NEO_TOOLTIP)) call.owner = "test/elsewhere/Hooks";
		}
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** Twice would post the event twice, and every NeoForge mod's tooltip line would appear twice. */
	@Test
	void aSecondPassLeavesTheRepairedClassAlone() throws Exception {
		byte[] bytes = readMergedBase();

		byte[] once = transform(bytes);
		assertSame(once, transform(once), "a body that already posts must not be rewritten");
	}

	private static MethodNode tooltipLines(ClassNode node) {
		for (MethodNode method : node.methods) {
			if ("getTooltipLines".equals(method.name)) return method;
		}
		return null;
	}

	private static int calls(MethodNode method, String owner, String name) {
		int found = 0;
		for (AbstractInsnNode insn : method.instructions.toArray()) {
			if (insn instanceof MethodInsnNode call && owner.equals(call.owner) && name.equals(call.name)) found++;
		}
		return found;
	}

	private static byte[] transform(byte[] bytes) {
		return new ForbricMergedBaseCompatTransformer().transform(ITEM_STACK.replace('/', '.'), bytes, null);
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] readMergedBase() throws Exception {
		return TestFixtures.requireEntry(Fixture.STAGED, MERGED_BASE, ITEM_STACK + ".class");
	}
}
