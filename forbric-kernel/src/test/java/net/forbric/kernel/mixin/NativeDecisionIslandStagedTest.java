/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * The island on the staged merged base, read against vanilla's {@code LivingEntity.baseTick} (the Fabric native reference):
 * NeoForge's {@code baseTick} hands the air supply to {@code CommonHooks.onLivingBreathe}, and vanilla's two breathing
 * checks come back in front of that call — the eye-in-water check, the bubble-column and can-breathe checks, then the
 * water-breathing effect check, nothing that changes state — with only the static effect check relayed to the hook, which
 * asks it again. Nothing in the derivation names a class, method or mod: the test names the calls a guest wants, as a
 * guest's {@code @At} does.
 */
class NativeDecisionIslandStagedTest {
	private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
	private static final String EYE = "L" + LIVING + ";isEyeInFluid(Lnet/minecraft/tags/TagKey;)Z";
	private static final String EFFECT = "Lnet/minecraft/world/effect/MobEffectUtil;hasWaterBreathing(L" + LIVING + ";)Z";

	private static ClassNode staged(String jar, String entry) {
		Path path = TestFixtures.stagedRoot().resolve(jar);
		ClassNode node = new ClassNode();
		new ClassReader(TestFixtures.requireEntry(Fixture.STAGED, path, entry)).accept(node, 0);
		return node;
	}

	private static Function<String, ClassNode> classes() {
		return owner -> {
			try {
				return owner.startsWith("net/neoforged/") ? staged("neoforge-runtime/neoforge-runtime.jar", owner + ".class")
						: staged("merged-base/patched-mc-merged-26.2.jar", owner + ".class");
			} catch (AssertionError | RuntimeException absent) {
				return null;
			}
		};
	}

	@Test void vanillasBreathingChecksComeBackInFrontOfTheCarriersHookWithTheEffectCheckRelayed() throws Exception {
		ClassNode merged = staged("merged-base/patched-mc-merged-26.2.jar", LIVING + ".class");
		ClassNode vanilla = staged("merged-base/patched-mc-merged-26.2.jar", "META-INF/forbric/native-reference/FABRIC/" + LIVING + ".class.bin");
		MethodNode host = NativeCallInlining.method(merged, "baseTick()V"), was = NativeCallInlining.method(vanilla, "baseTick()V");
		assertNotNull(host);
		assertNotNull(was);
		NativeDecisionIsland.Outcome outcome = NativeDecisionIsland.derive(merged, host, vanilla, was, Set.of(EYE, EFFECT), classes());
		assertNotNull(outcome.island(), String.valueOf(outcome.declined()));
		NativeDecisionIsland.Island island = outcome.island();
		assertTrue(island.members().containsAll(List.of(EYE, EFFECT, "L" + LIVING + ";canBreatheUnderwater()Z")), island.members().toString());
		assertEquals(List.of(EFFECT), island.relays().values().stream().map(NativeDecisionIsland.Relay::key).toList(),
				"only the static effect check is asked again by the carrier's hook");
		assertFalse(island.carried().contains(EYE), "the hook never asks the eye-in-water check: " + island.carried());
		for (String member : island.members())
			assertFalse(member.contains("setAirSupply") || member.contains("hurtServer") || member.contains("decreaseAirSupply"),
					"nothing that changes state is copied: " + member);
		// The stretch the carrier replaced is the one that calls its hook.
		List<AbstractInsnNode> real = NativeDecisionIsland.real(host);
		boolean hook = false;
		for (int i = island.start(); i < island.resume(); i++)
			hook |= real.get(i) instanceof MethodInsnNode call && call.name.equals("onLivingBreathe");
		assertTrue(hook, "the carrier's hook lies in the merged stretch");

		NativeDecisionIsland.apply(host, was, island);
		host.maxStack += 8;   // the class writer computes it when the class is defined
		new Analyzer<>(new BasicVerifier()).analyze(merged.name, host);
		long eyes = 0, effects = 0;
		for (AbstractInsnNode insn : host.instructions) {
			if (!(insn instanceof MethodInsnNode call)) continue;
			if (NativeDecisionIsland.member(call).equals(EYE)) eyes++;
			if (NativeDecisionIsland.member(call).equals(EFFECT)) effects++;
		}
		assertEquals(1, eyes, "one eye-in-water check, as vanilla's baseTick makes");
		assertEquals(1, effects, "one water-breathing check, as vanilla's baseTick makes");
	}

	/**
	 * The kernel's vanilla refill in the carrier's hook asks whether the eye is in a fluid that drowns the body of the body's fluid
	 * interaction — each eye fluid type's own {@code canDrownIn} — so the island still holds vanilla's eye-in-water check (a mod's
	 * injector there keeps running at the native call) and still relays the effect check. The control makes the refill ask
	 * vanilla's {@code isEyeInFluid(WATER)} on the body as well, as vanilla's stretch does: the island then finds the carrier
	 * making that call with operands of its own, takes it as moved, and gives it up — which is why the refill must not ask it
	 * that way. Both read the compiled refill class through the same class view the island plans with.
	 */
	@Test void theVanillaRefillInTheHookLeavesTheEyeCheckToTheIsland() throws Exception {
		ClassNode merged = staged("merged-base/patched-mc-merged-26.2.jar", LIVING + ".class");
		ClassNode vanilla = staged("merged-base/patched-mc-merged-26.2.jar", "META-INF/forbric/native-reference/FABRIC/" + LIVING + ".class.bin");
		MethodNode host = NativeCallInlining.method(merged, "baseTick()V"), was = NativeCallInlining.method(vanilla, "baseTick()V");
		String hook = "net/neoforged/neoforge/common/CommonHooks", refill = net.forbric.kernel.transform.VanillaAirRefillInjector.RUNTIME;
		ClassNode decorated = new ClassNode();
		new ClassReader(new net.forbric.kernel.transform.VanillaAirRefillInjector().transform(hook.replace('/', '.'),
				TestFixtures.requireEntry(Fixture.STAGED, TestFixtures.stagedRoot().resolve("neoforge-runtime/neoforge-runtime.jar"), hook + ".class"), null)).accept(decorated, 0);
		Path compiled = Path.of(System.getProperty("forbric.test.runtimeClasses", "build/classes/java/runtime")).resolve(refill + ".class");
		TestFixtures.require(Fixture.GAME_SIDE, java.nio.file.Files.isRegularFile(compiled), "compiled KernelAirRefill required");
		ClassNode runtime = new ClassNode();
		new ClassReader(java.nio.file.Files.readAllBytes(compiled)).accept(runtime, 0);
		Function<String, ClassNode> base = classes();
		Function<String, ClassNode> view = owner -> owner.equals(hook) ? decorated : owner.equals(refill) ? runtime : base.apply(owner);
		NativeDecisionIsland.Outcome outcome = NativeDecisionIsland.derive(merged, host, vanilla, was, Set.of(EYE, EFFECT), view);
		assertNotNull(outcome.island(), String.valueOf(outcome.declined()));
		assertFalse(outcome.island().carried().contains(EYE), "the hook's refill never asks the eye-in-water check: " + outcome.island().carried());
		assertEquals(List.of(EFFECT), outcome.island().relays().values().stream().map(NativeDecisionIsland.Relay::key).toList());

		MethodNode protectedRefill = NativeCallInlining.method(runtime, net.forbric.kernel.transform.VanillaAirRefillInjector.PROTECTED
				+ net.forbric.kernel.transform.VanillaAirRefillInjector.REFILL_DESC);
		assertNotNull(protectedRefill);
		List<String> asked = new java.util.ArrayList<>();
		for (AbstractInsnNode insn : protectedRefill.instructions) if (insn instanceof MethodInsnNode call) asked.add(call.owner + "." + call.name);
		assertTrue(asked.contains("net/minecraft/world/entity/EntityFluidInteraction.isEyeInFluidMatching"),
				"the refill asks the eye's fluid types through the body's fluid interaction: " + asked);
		assertFalse(asked.stream().anyMatch(call -> call.endsWith(".isEyeInFluid")), "and never vanilla's tag question: " + asked);

		// Control: the same refill asking isEyeInFluid(WATER) on the body too, as vanilla's stretch does (and the proof that the
		// island reads the refill class at all).
		ClassNode asking = new ClassNode();
		runtime.accept(asking);
		MethodNode refillWithTag = NativeCallInlining.method(asking, protectedRefill.name + protectedRefill.desc);
		org.objectweb.asm.tree.InsnList vanillaQuestion = new org.objectweb.asm.tree.InsnList();
		vanillaQuestion.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
		vanillaQuestion.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETSTATIC, "net/minecraft/tags/FluidTags", "WATER", "Lnet/minecraft/tags/TagKey;"));
		vanillaQuestion.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LIVING, "isEyeInFluid", "(Lnet/minecraft/tags/TagKey;)Z", false));
		vanillaQuestion.add(new org.objectweb.asm.tree.InsnNode(Opcodes.POP));
		refillWithTag.instructions.insert(vanillaQuestion);
		Function<String, ClassNode> controlView = owner -> owner.equals(refill) ? asking : view.apply(owner);
		NativeDecisionIsland.Outcome control = NativeDecisionIsland.derive(merged, host, vanilla, was, Set.of(EYE, EFFECT), controlView);
		assertNotNull(control.island(), String.valueOf(control.declined()));
		assertTrue(control.island().carried().contains(EYE), "asked through isEyeInFluid, the island takes the eye check as carried");
	}

	/**
	 * Where the carrier moved the call rather than replaced it — the merged {@code SimpleModelWrapper.bake(baker, id, state)}
	 * forwards to the overload taking the resolved model, which asks {@code findNonBlockSprites} on quads of its own — the
	 * island would ask it a second time on quads the carrier never sees: the call is carried, not relayable, and planning
	 * leaves the injector to the adapters that bind it at the carrier's call.
	 */
	@Test void aCallTheCarrierMovedIntoItsOwnStaticCalleeIsCarriedAndNotRelayed() throws Exception {
		String owner = "net/minecraft/client/resources/model/SimpleModelWrapper";
		String sprites = "L" + owner + ";findNonBlockSprites(Lnet/minecraft/client/resources/model/geometry/QuadCollection;)Lcom/google/common/collect/Multimap;";
		ClassNode merged = staged("merged-base/patched-mc-merged-26.2.jar", owner + ".class");
		ClassNode vanilla = staged("merged-base/patched-mc-merged-26.2.jar", "META-INF/forbric/native-reference/FABRIC/" + owner + ".class.bin");
		String bake = "bake(Lnet/minecraft/client/resources/model/ModelBaker;Lnet/minecraft/resources/Identifier;"
				+ "Lnet/minecraft/client/renderer/block/dispatch/ModelState;)Lnet/minecraft/client/renderer/block/dispatch/BlockStateModelPart;";
		MethodNode host = NativeCallInlining.method(merged, bake), was = NativeCallInlining.method(vanilla, bake);
		assertNotNull(host);
		assertNotNull(was);
		NativeDecisionIsland.Outcome outcome = NativeDecisionIsland.derive(merged, host, vanilla, was, Set.of(sprites), classes());
		assertNotNull(outcome.island(), String.valueOf(outcome.declined()));
		assertTrue(outcome.island().carried().contains(sprites), outcome.island().carried().toString());
		assertNull(outcome.island().relayable().get(sprites), "its operand is quads the island computes itself");
	}

	@Test void theRelayedEffectCheckAnswersAnOpenFrameAndOtherwiseRunsAsAlways() throws Exception {
		ClassNode util = staged("merged-base/patched-mc-merged-26.2.jar", "net/minecraft/world/effect/MobEffectUtil.class");
		MethodNode effect = NativeCallInlining.method(util, "hasWaterBreathing(L" + LIVING + ";)Z");
		assertNotNull(effect);
		assertTrue((effect.access & Opcodes.ACC_STATIC) != 0);
		int before = effect.instructions.size();
		NativeDecisionIsland.answering(effect, new NativeDecisionIsland.Relay("net/minecraft/world/effect/MobEffectUtil", "hasWaterBreathing",
				"(L" + LIVING + ";)Z"));
		assertTrue(effect.instructions.size() > before);
		effect.maxStack += 8;   // the class writer computes it when the class is defined
		new Analyzer<>(new BasicVerifier()).analyze(util.name, effect);
	}
}
