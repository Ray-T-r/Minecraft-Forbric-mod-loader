/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * fabric-item-api's furnace mixin on NeoForge's base: its {@code @Redirect} of vanilla's {@code Item.getCraftingRemainder()}
 * has no anchor in NeoForge's {@code consumeFuel}, which asks the unshrunk fuel stack for its stack-aware remainder itself,
 * so the report's SUSPECTED rows for it were false (regress-gate's {@code fuel.remainder.*} measure the furnace as native).
 * SupersededMixins' {@code native-furnace-remainder} entry resolves them, on the real mixin and the real staged class:
 *
 * <ul>
 *   <li>the claim fits the shipped mixin, and the same mixin under unrelated names; not one whose redirect answers
 *       something else or that has a third injector;</li>
 *   <li>the witness holds on the staged base's {@code AbstractFurnaceBlockEntity}, and not on vanilla's (which asks the
 *       item after shrinking) or on the staged class with the remainder read moved after the shrink;</li>
 *   <li>the rows the kernel records for the mixin (the whole-mixin row and the redirect's) are resolved when the staged
 *       class is defined, and stay SUSPECTED when the premise is broken.</li>
 * </ul>
 */
@ResourceLock("system-properties")
class SupersededFurnaceRemainderStagedTest {
	private static final String MODULE = "fabric-item-api-v1";
	private static final String MIXIN = "net/fabricmc/fabric/mixin/item/AbstractFurnaceBlockEntityMixin";
	private static final String CONFIG = "fabric-item-api-v1.mixins.json";
	private static final String FURNACE = SupersededMixins.FURNACE.replace('/', '.');

	@BeforeEach @AfterEach void reset() {
		MixinCompatibility.reset();
		CompatibilityFindings.reset();
		SupersededMixins.reset();
		System.clearProperty(SupersededMixins.PROPERTY);
		MixinConfigOwners.publish(List.of(new MixinConfigOwners.Owned(CONFIG, MODULE, Ecosystem.FABRIC)));
	}

	@Test void theClaimFitsTheShippedMixinAndNothingLikeIt() throws Exception {
		ClassNode mixin = StagedFabricMixinFixture.mixin(MODULE, MIXIN);
		assertTrue(SupersededMixins.copiesTheFuelForTheRemainder(mixin), "fabric-item-api's furnace mixin is the remainder fix");
		ClassNode renamed = new ClassNode();
		mixin.accept(new org.objectweb.asm.commons.ClassRemapper(renamed,
				new org.objectweb.asm.commons.SimpleRemapper(MIXIN, "org/qzx/heat/QzxEmbers")));
		assertTrue(SupersededMixins.copiesTheFuelForTheRemainder(renamed), "read from the mixin, not its name");

		ClassNode other = StagedFabricMixinFixture.mixin(MODULE, MIXIN);
		for (MethodNode m : other.methods) for (AbstractInsnNode i : m.instructions)
			if (i instanceof MethodInsnNode call && call.name.equals("getCraftingRemainder") && call.owner.equals("net/minecraft/world/item/ItemStack"))
				call.name = "getCraftingRemainderElsewhere";
		assertFalse(SupersededMixins.copiesTheFuelForTheRemainder(other), "a redirect answering something else is not the fix");

		ClassNode third = StagedFabricMixinFixture.mixin(MODULE, MIXIN);
		MethodNode copy = third.methods.stream().filter(m -> m.name.equals("copyStack")).findFirst().orElseThrow();
		MethodNode extra = new MethodNode(copy.access, "copyStackAgain", copy.desc, copy.signature, null);
		copy.accept(extra);
		extra.name = "copyStackAgain";
		third.methods.add(extra);
		assertFalse(SupersededMixins.copiesTheFuelForTheRemainder(third), "a mixin doing more than the fix is not superseded");

		SupersededMixins.noteMixin(mixin, path -> null);
		assertNotNull(SupersededMixins.replacementFor(MIXIN.replace('/', '.')));
		System.setProperty(SupersededMixins.PROPERTY, "off");
		assertNull(SupersededMixins.replacementFor(MIXIN.replace('/', '.')), "-Dforbric.supersededMixins=off claims nothing");
	}

	@Test void theWitnessIsNeoForgesConsumeFuelAndNotVanillasOrABrokenOne() throws Exception {
		assertTrue(SupersededMixins.consumeFuelAsksTheStack(node(staged())), "NeoForge's consumeFuel asks the unshrunk stack");
		assertFalse(SupersededMixins.consumeFuelAsksTheStack(node(vanilla())), "vanilla asks the item, after shrinking");
		assertFalse(SupersededMixins.consumeFuelAsksTheStack(node(shrunkFirst(staged()))), "the remainder read after the shrink");
	}

	@Test void theRowsAreResolvedWhenTheStagedClassIsDefinedAndStaySuspectedWhenItIsBroken() throws Exception {
		for (boolean premise : new boolean[] {true, false}) {
			reset();
			SupersededMixins.noteMixin(StagedFabricMixinFixture.mixin(MODULE, MIXIN), path -> null);
			String binary = MIXIN.replace('/', '.');
			String mixinRow = MixinCompatibility.id(CONFIG, binary);
			String injectorRow = "mixin-injector:" + CONFIG + ":" + binary
					+ "#getCraftingRemainder(Lnet/minecraft/world/item/Item;Lcom/llamalad7/mixinextras/sugar/ref/LocalRef;)"
					+ "Lnet/minecraft/world/item/ItemStackTemplate;@" + FURNACE;
			// As the kernel records them on NeoForge's base: the partial fit at config time, the redirect's miss at definition.
			MixinCompatibility.recordAs(mixinRow, CONFIG, binary, "2/3 anchors resolve, missing: @At(INVOKE) "
					+ "net.minecraft.world.item.Item.getCraftingRemainder in AbstractFurnaceBlockEntity.consumeFuel",
					CompatibilityFinding.Confidence.SUSPECTED, true, List.of());
			CompatibilityFindings.record(new CompatibilityFinding(injectorRow, MODULE, "Mixin injection getCraftingRemainder",
					"mixin-application:" + CONFIG, CompatibilityFinding.Confidence.SUSPECTED, true,
					"A required injector's attachment in the actual defined class could not be established", List.of()));
			SupersededMixins.awaitProof(CONFIG, binary);
			SupersededMixins.observeDefinition(FURNACE, premise ? staged() : shrunkFirst(staged()));
			for (String id : List.of(mixinRow, injectorRow)) {
				CompatibilityFinding row = CompatibilityFindings.all().stream().filter(f -> f.id().equals(id)).findFirst().orElseThrow();
				assertEquals(premise ? CompatibilityFinding.Confidence.RESOLVED : CompatibilityFinding.Confidence.SUSPECTED,
						row.confidence(), (premise ? "the native consumeFuel resolves " : "a broken one leaves ") + id);
			}
		}
	}

	/** The staged base's AbstractFurnaceBlockEntity, as the jar holds it. */
	private static byte[] staged() throws Exception {
		return entry(TestFixtures.stagedBase(), Fixture.STAGED);
	}

	private static byte[] vanilla() throws Exception {
		return entry(TestFixtures.vanillaJar(), Fixture.MC_LIBRARIES);
	}

	private static byte[] entry(java.nio.file.Path jar, Fixture fixture) throws Exception {
		TestFixtures.requireFile(fixture, jar, "the furnace class's jar");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			return zip.getInputStream(zip.getEntry(SupersededMixins.FURNACE + ".class")).readAllBytes();
		}
	}

	/** {@code bytes} with consumeFuel's {@code fuel.shrink(1)} moved to the top of the method: the premise broken. */
	private static byte[] shrunkFirst(byte[] bytes) {
		ClassNode node = node(bytes);
		MethodNode consume = node.methods.stream().filter(m -> m.name.equals("consumeFuel")).findFirst().orElseThrow();
		MethodInsnNode shrink = null;
		for (AbstractInsnNode i : consume.instructions)
			if (i instanceof MethodInsnNode call && call.name.equals("shrink")) shrink = call;
		assertNotNull(shrink, "premise: NeoForge's consumeFuel shrinks the fuel");
		AbstractInsnNode count = shrink.getPrevious(), stack = count.getPrevious();
		assertTrue(stack instanceof VarInsnNode load && load.var == 1 && count.getOpcode() == Opcodes.ICONST_1, "premise: fuel.shrink(1)");
		consume.instructions.remove(stack);
		consume.instructions.remove(count);
		consume.instructions.remove(shrink);
		InsnList first = new InsnList();
		first.add(stack);
		first.add(count);
		first.add(shrink);
		consume.instructions.insert(first);
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}
}
