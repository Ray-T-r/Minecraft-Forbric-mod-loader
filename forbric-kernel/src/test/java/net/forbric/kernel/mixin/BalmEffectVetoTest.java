package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

/** balm's clear-all MOB_EFFECT_REMOVE veto wraps NeoForge's per-effect question, as fabric-api's ALLOW_EARLY_REMOVE does. */
@ResourceLock("system-properties")
class BalmEffectVetoTest {
	private static final Path BALM = Path.of("build/compat-inputs/sweep90/mods/balm-fabric-26.2-26.2.0.9.jar");

	@AfterEach void reset() {
		System.clearProperty(FabricEntityMixinAnchors.BALM_VETO_PROPERTY);
		System.clearProperty(FabricEntityMixinAnchors.PROPERTY);
	}

	private static ClassNode balm() throws Exception {
		Assumptions.assumeTrue(Files.isRegularFile(BALM), "sweep pack absent");
		try (ZipFile zip = new ZipFile(BALM.toFile())) {
			return MixinFit.parse(zip.getInputStream(zip.getEntry(FabricEntityMixinAnchors.BALM_MIXIN + ".class")).readAllBytes());
		}
	}

	@Test void theDeadClearWrapBecomesAWrapOfNeoForgesPerEffectQuestion() throws Exception {
		ClassNode mixin = balm(), living = StagedFabricMixinFixture.living(false);
		assertEquals(1, FabricEntityMixinAnchors.adapt(mixin, n -> living));
		assertNull(MixinFit.injectorOf(StagedFabricMixinFixture.method(mixin, "clearAllEffects")), "the dead clear() wrap is gone");
		MethodNode handler = StagedFabricMixinFixture.method(mixin, "forbric$balmAllowRemove");
		assertEquals("(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/effect/MobEffectInstance;"
				+ "Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)Z", handler.desc);
		AnnotationNode wrap = MixinFit.injectorOf(handler);
		assertEquals("Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;", wrap.desc);
		assertEquals(List.of("removeAllEffects()Z"), MixinFit.stringList(MixinFit.value(wrap, "method")));
		assertEquals("Lnet/neoforged/neoforge/event/EventHooks;onEffectRemoved(Lnet/minecraft/world/entity/LivingEntity;"
				+ "Lnet/minecraft/world/effect/MobEffectInstance;)Z", MixinFit.value(StagedFabricMixinFixture.at(mixin, "forbric$balmAllowRemove"), "target"));
		// NeoForge first (the Operation), then balm's own question with the effect's holder, exactly as its lambda asks it.
		List<String> calls = new ArrayList<>();
		for (AbstractInsnNode insn : handler.instructions) if (insn instanceof MethodInsnNode c) calls.add(c.name);
		assertEquals(List.of("call", "booleanValue", "invoker", "getEffect", "allowRemove"), calls);
		new Analyzer<>(new BasicVerifier()).analyze(mixin.name, handler);
		assertEquals(0, FabricEntityMixinAnchors.adapt(mixin, n -> living), "second adaptation is a no-op");
	}

	@Test void aBalmThatAsksAnythingElseIsNotReimplemented() throws Exception {
		ClassNode living = StagedFabricMixinFixture.living(false);
		ClassNode handler = balm();
		StagedFabricMixinFixture.method(handler, "clearAllEffects").instructions.insert(new InsnNode(Opcodes.NOP));
		assertEquals(0, FabricEntityMixinAnchors.adapt(handler, n -> living), "a changed handler body");
		assertNotNull(MixinFit.injectorOf(StagedFabricMixinFixture.method(handler, "clearAllEffects")));
		ClassNode question = balm();
		MethodNode lambda = question.methods.stream().filter(m -> m.name.equals("lambda$clearAllEffects$0")).findFirst().orElseThrow();
		lambda.instructions.insert(new InsnNode(Opcodes.NOP));
		assertEquals(0, FabricEntityMixinAnchors.adapt(question, n -> living), "a changed veto question");
	}

	@Test void onlyWhenNeoForgeAsksOncePerEffect() throws Exception {
		ClassNode mixin = balm(), living = StagedFabricMixinFixture.living(false);
		MethodNode remove = StagedFabricMixinFixture.method(living, "removeAllEffects");
		for (AbstractInsnNode insn : remove.instructions) {
			if (insn instanceof MethodInsnNode c && c.name.equals("onEffectRemoved")) {
				remove.instructions.insert(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, c.owner, c.name, c.desc, false));
				break;
			}
		}
		assertEquals(0, FabricEntityMixinAnchors.adapt(mixin, n -> living), "two native questions: not guessed");
	}

	@Test void itsOwnSwitchAndTheAdaptersSwitchBothLeaveItAlone() throws Exception {
		ClassNode living = StagedFabricMixinFixture.living(false);
		System.setProperty(FabricEntityMixinAnchors.BALM_VETO_PROPERTY, "off");
		ClassNode own = balm();
		assertEquals(0, FabricEntityMixinAnchors.adapt(own, n -> living));
		assertNotNull(MixinFit.injectorOf(StagedFabricMixinFixture.method(own, "clearAllEffects")));
		System.clearProperty(FabricEntityMixinAnchors.BALM_VETO_PROPERTY);
		System.setProperty(FabricEntityMixinAnchors.PROPERTY, "off");
		assertEquals(0, FabricEntityMixinAnchors.adapt(balm(), n -> living));
	}
}
