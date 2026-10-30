/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;

/**
 * {@link CarrierMethodReferences} on the unmodified fabric-api enchanting-table redirect against the staged merged base and the
 * native references it carries. Natively {@code getAvailableEnchantmentResults} filters with a lambda
 * {@code holder.value().isPrimaryItem(stack) || isBook}, and fabric-item-api redirects {@code isPrimaryItem} there; NeoForge
 * filters with {@code stack::isPrimaryItemFor}, whose inherited implementation answers true for a book first. The redirect moves
 * onto that call, made in a method of the class taking the lambda's parameters, and runs the native lambda with fabric-api's
 * handler, as written, at the call. The mechanism is given no name: renamed, it still moves; with the book test changed in the
 * carrier's default, nothing is folded and nothing moves.
 */
@ResourceLock("system-properties")
class CarrierMethodReferencesStagedTest {
	private static final String HELPER = "net/minecraft/world/item/enchantment/EnchantmentHelper";
	private static final String MIXIN = "net/fabricmc/fabric/mixin/item/EnchantmentHelperMixin";
	private static final String STACK_EXTENSION = "net/neoforged/neoforge/common/extensions/IItemStackExtension";
	private static final String ITEM_EXTENSION = "net/neoforged/neoforge/common/extensions/IItemExtension";

	@AfterEach void reset() {
		System.clearProperty(CarrierMethodReferences.PROPERTY);
	}

	private static Path merged() {
		Path jar = TestFixtures.mergedBase();
		TestFixtures.requireFile(TestFixtures.Fixture.STAGED, jar, "actual game required");
		return jar;
	}

	/** Every class of the merged base and both carriers, with local variable tables, as the mixin service hands them over. */
	private static Function<String, ClassNode> game() {
		List<Path> jars = List.of(merged(), TestFixtures.stagedRoot().resolve(TestFixtures.NEO_CARRIER_JAR),
				TestFixtures.stagedRoot().resolve(TestFixtures.FORGE_INTEROP_JAR));
		Map<String, ClassNode> read = new HashMap<>();
		return owner -> read.computeIfAbsent(owner, name -> {
			for (Path jar : jars) {
				if (!Files.isRegularFile(jar)) continue;
				try (ZipFile zip = new ZipFile(jar.toFile())) {
					var entry = zip.getEntry(name + ".class");
					if (entry == null) continue;
					ClassNode node = new ClassNode();
					new ClassReader(zip.getInputStream(entry).readAllBytes()).accept(node, 0);
					return node;
				} catch (Exception unreadable) {
					return null;
				}
			}
			return null;
		});
	}

	private static BiFunction<Ecosystem, String, ClassNode> natives() {
		Path jar = merged();
		NativeGameReferences references = new NativeGameReferences(path -> {
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				var entry = zip.getEntry(path);
				return entry == null ? null : zip.getInputStream(entry).readAllBytes();
			} catch (Exception unreadable) {
				return null;
			}
		});
		return references::get;
	}

	private static ClassNode fabric() throws Exception {
		ClassNode mixin = StagedFabricMixinFixture.mixin("fabric-item-api-v1", MIXIN);
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		return mixin;
	}

	private static int adapt(ClassNode mixin, Function<String, ClassNode> code, List<CarrierMethodReferences.Plan> plans) {
		return CarrierMethodReferences.adapt(mixin, code, natives(), plan -> plans.add(plan));
	}

	@Test void theTablesRedirectMovesOntoTheMethodReferencesCallAndRunsTheNativeLambdaThere() throws Exception {
		TestFixtures.requireForgeCarrierWhereTheBaseNamesIt("the method reference's declaring hierarchy in the base");
		ClassNode mixin = fabric();
		MethodNode handler = StagedFabricMixinFixture.method(mixin, "useCustomEnchantingChecks");
		String desc = handler.desc, written = MixinInstructionFingerprint.hash(handler);
		List<CarrierMethodReferences.Plan> plans = new ArrayList<>();
		assertEquals(1, adapt(mixin, game(), plans));
		CarrierMethodReferences.Plan plan = plans.getFirst();
		assertEquals(HELPER, plan.host());
		assertEquals(List.of(4), plan.extra(), "the merged method's isBook local, the same expression the lambda captured");
		assertEquals("(Lnet/minecraft/world/item/ItemStack;ZLnet/minecraft/core/Holder;)Z", plan.thunkDesc(), "the lambda's own parameters");
		assertEquals(STACK_EXTENSION, plan.handle().getOwner());
		MethodNode wrapper = StagedFabricMixinFixture.method(mixin, "useCustomEnchantingChecks");
		AnnotationNode redirect = MixinFit.injectorOf(wrapper);
		assertEquals(List.of(plan.thunk() + plan.thunkDesc()), MixinFit.stringList(MixinFit.value(redirect, "method")));
		assertEquals("L" + STACK_EXTENSION + ";isPrimaryItemFor(Lnet/minecraft/core/Holder;)Z", MixinFit.value(MixinFit.atNodes(redirect).getFirst(), "target"));
		assertEquals("(L" + STACK_EXTENSION + ";Lnet/minecraft/core/Holder;Lnet/minecraft/world/item/ItemStack;ZLnet/minecraft/core/Holder;)Z", wrapper.desc,
				"the reference's call, then the lambda's parameters captured");
		MethodNode original = StagedFabricMixinFixture.method(mixin, "useCustomEnchantingChecks" + CarrierDecisions.SUFFIX);
		assertNull(MixinFit.injectorOf(original));
		assertEquals(desc, original.desc);
		assertEquals(written, MixinInstructionFingerprint.hash(original), "fabric-api's own handler, unchanged");
		assertEquals(1, calls(wrapper, mixin.name, original.name), "fabric-api's handler where the lambda asked isPrimaryItem");
		assertEquals(1, calls(wrapper, "net/minecraft/core/Holder", "value"), "the native lambda's own body");
		assertEquals(0, calls(wrapper, "net/minecraft/world/item/enchantment/Enchantment", "isPrimaryItem"), "the vanilla call is the handler's alone");
		new Analyzer<>(new BasicVerifier()).analyze(mixin.name, wrapper);
		assertEquals(0, adapt(mixin, game(), new ArrayList<>()), "a second pass changes nothing");
	}

	@Test void theMaterializedReferenceTakesTheLambdasParametersAndReadsTheFoldedValueFromItsLocal() throws Exception {
		TestFixtures.requireForgeCarrierWhereTheBaseNamesIt("the method reference's declaring hierarchy in the base");
		List<CarrierMethodReferences.Plan> plans = new ArrayList<>();
		assertEquals(1, adapt(fabric(), game(), plans));
		CarrierMethodReferences.Plan plan = plans.getFirst();
		ClassNode helper = game().apply(HELPER);
		assertTrue(CarrierMethodReferences.materialize(helper, plan));
		MethodNode host = helper.methods.stream().filter(m -> (m.name + m.desc).equals(plan.method())).findFirst().orElseThrow();
		InvokeDynamicInsnNode reference = null;
		for (AbstractInsnNode instruction : host.instructions)
			if (instruction instanceof InvokeDynamicInsnNode indy && indy.bsmArgs[1] instanceof Handle handle && handle.getName().equals(plan.thunk())) reference = indy;
		assertNotNull(reference, "the method reference names the materialized method");
		assertEquals(Opcodes.H_INVOKESTATIC, ((Handle) reference.bsmArgs[1]).getTag());
		assertEquals("(Lnet/minecraft/world/item/ItemStack;Z)Ljava/util/function/Predicate;", reference.desc);
		AbstractInsnNode before = reference.getPrevious();
		assertTrue(before instanceof VarInsnNode load && load.getOpcode() == Opcodes.ILOAD && load.var == 4, "handed isBook from its local");
		MethodNode thunk = StagedFabricMixinFixture.method(helper, plan.thunk());
		assertEquals(1, calls(thunk, STACK_EXTENSION, "isPrimaryItemFor"));
		for (MethodNode method : List.of(host, thunk)) new Analyzer<>(new BasicVerifier()).analyze(helper.name, method);
		assertTrue(CarrierMethodReferences.materialize(helper, plan), "materialized once");
		// Read again with the reference materialized, the same move is planned onto the same method, with nothing more to hand it.
		List<CarrierMethodReferences.Plan> again = new ArrayList<>();
		Function<String, ClassNode> game = game();
		assertEquals(1, adapt(fabric(), owner -> owner.equals(HELPER) ? helper : game.apply(owner), again));
		assertEquals(plan.thunk(), again.getFirst().thunk());
		assertEquals(List.of(), again.getFirst().extra());
	}

	@Test void theMoveKnowsNoName() throws Exception {
		TestFixtures.requireForgeCarrierWhereTheBaseNamesIt("the method reference's declaring hierarchy in the base");
		ClassNode mixin = fabric();
		mixin.name = "org/example/forge/TableHook";
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		MethodNode handler = StagedFabricMixinFixture.method(mixin, "useCustomEnchantingChecks");
		handler.name = "primary";
		handler.instructions.insert(new InsnNode(Opcodes.NOP));
		assertEquals(1, adapt(mixin, game(), new ArrayList<>()));
		assertNotNull(StagedFabricMixinFixture.method(mixin, "primary" + CarrierDecisions.SUFFIX));
	}

	/**
	 * An access widener that only makes the receiver's class extendable ({@code extendable class ItemStack}, as Apoli Legacy
	 * ships) takes ACC_FINAL off ItemStack. The captured {@code stack.is(Items.BOOK)} is still read through the implementation an
	 * ItemStack reaches, as the chain is: the move is made exactly as on the unwidened class. Read by finality, it was declined
	 * ("does not first test the captured boolean") for every pack that installed such a widener.
	 */
	@Test void aWidenerThatOnlyMakesTheStackExtendableStillMoves() throws Exception {
		TestFixtures.requireForgeCarrierWhereTheBaseNamesIt("the method reference's declaring hierarchy in the base");
		Function<String, ClassNode> game = game();
		ClassNode stack = game.apply("net/minecraft/world/item/ItemStack");
		assertTrue((stack.access & Opcodes.ACC_FINAL) != 0, "the merged ItemStack is final as built");
		stack.access &= ~Opcodes.ACC_FINAL;
		List<CarrierMethodReferences.Plan> plans = new ArrayList<>();
		assertEquals(1, adapt(fabric(), owner -> owner.equals(stack.name) ? stack : game.apply(owner), plans));
		assertEquals(List.of(4), plans.getFirst().extra(), "the same isBook local as on the unwidened class");
		assertEquals(STACK_EXTENSION, plans.getFirst().handle().getOwner());
	}

	/**
	 * What the moved redirect leaves to the carrier: nothing of isPrimaryItemFor. The wrapper runs the native lambda — the mod's
	 * handler, then the captured isBook — and the mod's own fallback asks Enchantment.isPrimaryItem, whose merged body (and the
	 * isSupportedItem it asks) is the native body instruction for instruction. Only without a mod's redirect does the carrier's
	 * isPrimaryItemFor decide, and its default differs from the native lambda for an enchanted book alone (supportsEnchantment
	 * answers true for one), or for an item whose class overrides it — the carrier's own contract.
	 */
	@Test void theMovedDecisionLeavesTheCarrierNothingButTheNativeIsPrimaryItem() throws Exception {
		Function<String, ClassNode> game = game();
		String enchantment = "net/minecraft/world/item/enchantment/Enchantment";
		ClassNode merged = game.apply(enchantment), original = natives().apply(Ecosystem.FABRIC, enchantment);
		for (String name : List.of("isPrimaryItem", "isSupportedItem")) {
			MethodNode was = original.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals("(Lnet/minecraft/world/item/ItemStack;)Z")).findFirst().orElseThrow();
			MethodNode is = merged.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(was.desc)).findFirst().orElseThrow();
			assertEquals(MixinInstructionFingerprint.hash(was), MixinInstructionFingerprint.hash(is), name + " is the native body");
		}
		MethodNode supports = game.apply(ITEM_EXTENSION).methods.stream().filter(m -> m.name.equals("supportsEnchantment")).findFirst().orElseThrow();
		boolean enchantedBook = false;
		for (AbstractInsnNode instruction : supports.instructions)
			if (instruction instanceof FieldInsnNode field && field.name.equals("ENCHANTED_BOOK")) enchantedBook = true;
		assertTrue(enchantedBook, "the carrier's default answers true for an enchanted book before it asks the enchantment");
	}

	/** The carrier's default that tests another value first folds nothing the lambda captured: nothing moves. */
	@Test void withoutTheFoldedDisjunctNothingMoves() throws Exception {
		Function<String, ClassNode> game = game();
		ClassNode extension = game.apply(ITEM_EXTENSION);
		MethodNode decision = extension.methods.stream().filter(m -> m.name.equals("isPrimaryItemFor")).findFirst().orElseThrow();
		for (AbstractInsnNode instruction : decision.instructions)
			if (instruction instanceof FieldInsnNode field && field.name.equals("BOOK")) field.name = "STICK";
		ClassNode mixin = fabric();
		byte[] before = StagedFabricMixinFixture.bytes(mixin);
		assertEquals(0, adapt(mixin, owner -> owner.equals(ITEM_EXTENSION) ? extension : game.apply(owner), new ArrayList<>()));
		assertArrayEquals(before, StagedFabricMixinFixture.bytes(mixin));
	}

	@Test void aGroupOrTheSwitchLeavesTheRedirectAsCompiled() throws Exception {
		ClassNode grouped = fabric();
		StagedFabricMixinFixture.method(grouped, "useCustomEnchantingChecks").visibleAnnotations.add(new AnnotationNode(CarrierDecisions.GROUP));
		assertEquals(0, adapt(grouped, game(), new ArrayList<>()));
		System.setProperty(CarrierMethodReferences.PROPERTY, "off");
		assertEquals(0, adapt(fabric(), game(), new ArrayList<>()));
	}

	/** Natively the lambda is in the class: with the native class as the merged one there is nothing to move. */
	@Test void whereTheMergedClassStillHasTheLambdaNothingMoves() throws Exception {
		ClassNode nativeHelper = natives().apply(Ecosystem.FABRIC, HELPER);
		Function<String, ClassNode> game = game();
		assertEquals(0, adapt(fabric(), owner -> owner.equals(HELPER) ? nativeHelper : game.apply(owner), new ArrayList<>()));
	}

	private static int calls(MethodNode method, String owner, String name) {
		int n = 0;
		for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) n++;
		return n;
	}
}
