/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/**
 * {@link RegisterEventsSeam} on NeoForge 26.2.0.93's own {@code GameData} from the staged carrier: the one dispatch call
 * goes to the kernel, the Fabric step sits where the loop ends, the six tail steps are routed and the roll-back path is
 * NeoForge's, and the edited method still verifies. A corelib-shaped woven method (its
 * handler right after the dispatch call) keeps its handler right after the kernel's dispatch.
 */
class RegisterEventsSeamStagedTest {
	@Test
	void theGenuineMethodIsRoutedAndStillVerifies() throws Exception {
		ClassNode node = genuine();
		RegisterEventsSeam.Edit edit = RegisterEventsSeam.apply(node);
		assertNotNull(edit, "NeoForge .93's postRegisterEvents must be the shape the seam knows");
		assertEquals(6, edit.steps(), "modifyAttributes, fireSpawnPlacementEvent, postEvent, sortTabs, "
				+ "registerModdedCategories, ItemTooltipHandler.init");
		List<String> calls = calls(method(node));
		assertEquals(0, count(calls, "net/neoforged/fml/ModLoader.postEventWrapContainerInModOrder"), calls.toString());
		assertEquals(0, count(calls, "net/neoforged/fml/ModLoader.postEvent"), calls.toString());
		assertEquals(1, count(calls, RegisterEventsSeam.HOOKS + "." + RegisterEventsSeam.ON_EVENT), calls.toString());
		assertEquals(1, count(calls, RegisterEventsSeam.HOOKS + "." + RegisterEventsSeam.ON_DISPATCHED), calls.toString());
		assertEquals(5, count(calls, RegisterEventsSeam.HOOKS + "." + RegisterEventsSeam.ON_STEP), calls.toString());
		assertEquals(1, count(calls, "net/neoforged/neoforge/registries/RegistryManager.revertToVanilla"),
				"the roll-back stays NeoForge's: " + calls);
		// The Fabric step comes after the dispatch and the roll-back test, before the first tail step.
		int dispatched = calls.indexOf(RegisterEventsSeam.HOOKS + "." + RegisterEventsSeam.ON_DISPATCHED);
		assertTrue(dispatched > calls.indexOf("net/neoforged/neoforge/registries/RegistryManager.revertToVanilla")
				&& dispatched < calls.indexOf(RegisterEventsSeam.HOOKS + "." + RegisterEventsSeam.ON_STEP), calls.toString());
		List<String> steps = new ArrayList<>();
		for (AbstractInsnNode insn : method(node).instructions) {
			if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String name && insn.getNext() instanceof MethodInsnNode call
					&& call.name.equals(RegisterEventsSeam.ON_STEP)) {
				steps.add(((org.objectweb.asm.Type) ((LdcInsnNode) ldc.getPrevious()).cst).getInternalName() + "." + name);
			}
		}
		assertEquals(List.of("net/neoforged/neoforge/common/CommonHooks.modifyAttributes",
				"net/minecraft/world/entity/SpawnPlacements.fireSpawnPlacementEvent",
				"net/neoforged/neoforge/common/CreativeModeTabRegistry.sortTabs",
				"net/minecraft/world/level/gamerules/GameRuleCategory.registerModdedCategories",
				"net/neoforged/neoforge/common/tooltip/ItemTooltipHandler.init"), steps);

		// Written as the seam writes it, read back, and the edited method run through ASM's verifier: every edit keeps the
		// operand stack and the method's frames consistent (the types the base's own libraries define are not needed for that).
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		ClassNode written = new ClassNode();
		new ClassReader(writer.toByteArray()).accept(written, 0);
		new Analyzer<>(new BasicVerifier()).analyze(written.name, method(written));

	}

	@Test
	void aWovenHandlerAfterTheDispatchStaysRightAfterIt() throws Exception {
		ClassNode node = genuine();
		MethodNode method = method(node);
		MethodInsnNode dispatch = null;
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.name.equals(RegisterEventsSeam.DISPATCH)) dispatch = call;
		}
		// What corelib's @Inject(at = INVOKE postEventWrapContainerInModOrder, shift = AFTER) weaves: a null callback info
		// and a call of the merged handler.
		method.instructions.insert(dispatch, new MethodInsnNode(Opcodes.INVOKESTATIC, node.name,
				"handler$zzd000$corelib$postRegisterEvents", "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", false));
		method.instructions.insert(dispatch, new InsnNode(Opcodes.ACONST_NULL));
		assertNotNull(RegisterEventsSeam.apply(node));
		AbstractInsnNode after = null;
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.name.equals(RegisterEventsSeam.ON_EVENT)) after = insn.getNext().getNext();
		}
		assertTrue(after instanceof MethodInsnNode call && call.name.startsWith("handler$"),
				"the guest's handler must still run right after each registry's dispatch");
	}

	@Test
	void anUnknownShapeIsLeftAlone() throws Exception {
		ClassNode node = genuine();
		MethodNode method = method(node);
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.name.equals("getSuppressed")) call.name = "getStackTrace";
		}
		assertNull(RegisterEventsSeam.apply(node), "no tail test, no edit");
		assertEquals(1, count(calls(method), "net/neoforged/fml/ModLoader.postEventWrapContainerInModOrder"));
	}

	private static ClassNode genuine() throws Exception {
		Path carrier = TestFixtures.neoCarrier();
		TestFixtures.requireFiles(Fixture.STAGED, "NeoForge's carrier", carrier, TestFixtures.stagedBase());
		ClassNode node = new ClassNode();
		try (ZipFile zip = new ZipFile(carrier.toFile())) {
			new ClassReader(zip.getInputStream(zip.getEntry(RegisterEventsSeam.GAME_DATA + ".class"))).accept(node, 0);
		}
		return node;
	}

	private static MethodNode method(ClassNode node) {
		return node.methods.stream().filter(m -> m.name.equals(RegisterEventsSeam.METHOD)).findFirst().orElseThrow();
	}

	private static List<String> calls(MethodNode method) {
		List<String> out = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call) out.add(call.owner + "." + call.name);
		}
		return out;
	}

	private static int count(List<String> calls, String call) {
		return (int) calls.stream().filter(call::equals).count();
	}
}
