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

package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import net.forbric.api.Ecosystem;

/**
 * Rule R3: the selector's method lost its body to a carrier RENAME, the renamed one has the SAME descriptor, and the
 * shipped census says so. The classes carry the real names because only a row of {@code carrier-renames.txt}
 * authorizes a move; their bodies are reduced to the calls that matter.
 *
 * <p>The live case is {@code PersistentEntitySectionManager.addEntity}: NeoForge posts its event there and calls
 * vanilla's body, renamed {@code addEntityWithoutEvent}, with the same arguments. Carpet's and architectury's
 * entity-add hooks anchor inside that body.
 *
 * <p>Identical descriptor is what makes the rewrite safe — every handler parameter, {@code CallbackInfo} and
 * {@code @Local} stays as valid as it was — but it does not make the target the right one: text_styles'
 * {@code Style.withColor(I)} has the same shape as {@code withShadowColor(I)} and nothing to do with it. Both moved
 * before the census. NeoForge's renamed tooltip body {@code addDetailsToTooltipComponents} is the body, but private and
 * never called: an injector moves there only to bind, and says it never runs.
 */
class MixinRetargetRenamedBodyTest {
	private static final String MANAGER = "net/minecraft/world/level/entity/PersistentEntitySectionManager";
	private static final String ADD = "(Lnet/minecraft/world/level/entity/EntityAccess;Z)Z";
	private static final String TICKING = "Lnet/minecraft/world/level/entity/Visibility;isTicking()Z";
	private static final String STYLE = "net/minecraft/network/chat/Style";
	private static final String WITH = "(I)Lnet/minecraft/network/chat/Style;";
	private static final String CHECK_EMPTY = "L" + STYLE + ";checkEmptyAfterChange(L" + STYLE + ";Ljava/lang/Object;Ljava/lang/Object;)L" + STYLE + ";";
	private static final String ITEM_STACK = "net/minecraft/world/item/ItemStack";
	private static final String TOOLTIP = CarrierRenameCensusTest.TOOLTIP;
	private static final String ADD_TO_TOOLTIP = "L" + ITEM_STACK + ";addToTooltip(Lnet/minecraft/core/component/DataComponentType;"
			+ "Lnet/minecraft/world/item/Item$TooltipContext;Lnet/minecraft/world/item/component/TooltipDisplay;"
			+ "Ljava/util/function/Consumer;Lnet/minecraft/world/item/TooltipFlag;)V";
	private static final String PLAYER = "net/minecraft/server/level/ServerPlayer";
	private static final String SLEEP = "(Lnet/minecraft/core/BlockPos;)Lcom/mojang/datafixers/util/Either;";
	private static final String RESPAWN = "L" + PLAYER + ";setRespawnPosition(L" + PLAYER + "$RespawnConfig;Z)V";
	private static final String CONFIG = "net/minecraft/server/network/ServerConfigurationPacketListenerImpl";
	private static final String TASK = "Lnet/minecraft/server/network/config/SynchronizeRegistriesTask;<init>(Ljava/util/List;"
			+ "Lnet/minecraft/core/LayeredRegistryAccess;)V";
	private static final String MODIFY_ARG = "Lorg/spongepowered/asm/mixin/injection/ModifyArg;";
	private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
	private static final String CI = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
	private static final String CIR = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";
	private static final String LOCAL = "Lcom/llamalad7/mixinextras/sugar/Local;";
	private static final String SHARE = "Lcom/llamalad7/mixinextras/sugar/Share;";
	/** fabric-item-api's and apoli's advanced-tooltip anchor: the item id line, in vanilla's tail and NeoForge's addDetailsToTooltipTail. */
	private static final String GET_KEY = "Lnet/minecraft/core/DefaultedRegistry;getKey(Ljava/lang/Object;)Lnet/minecraft/resources/Identifier;";
	private static final String TOOLTIP_ARGS = TOOLTIP.substring(1, TOOLTIP.indexOf(')'));
	static final String MIXIN = "test/RenamedBodyMixin";

	/** This suite pins the legacy rule independently; execution-path proofs have their own positive/negative tests. */
	@org.junit.jupiter.api.BeforeEach
	void legacyRuleScope() { System.setProperty(MixinExecutionPathRetarget.PROPERTY, "off"); }

	@AfterEach
	void reset() {
		System.clearProperty(MixinExecutionPathRetarget.PROPERTY);
		System.clearProperty(MixinRetarget.PROPERTY);
		System.clearProperty(MixinRetarget.RENAME_CENSUS_PROPERTY);
		System.clearProperty(MixinRetarget.UNCALLED_PROPERTY);
		System.clearProperty(MixinFit.LIVENESS_PROPERTY);
		MixinRetarget.reset();
		MixinStubRebind.forget();
	}

	@Test
	void aSelectorWhoseBodyWasRenamedIsReboundToTheRenamedOne() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		Function<String, byte[]> resolver = resolver(MANAGER, manager(true));
		byte[] mixin = mixin(MANAGER, "addEntity", TICKING);
		assertEquals(MixinFit.Verdict.PARTIAL, MixinFit.evaluate(mixin, resolver).verdict(), "premise");

		MixinRetarget.Plan plan = MixinRetarget.plan(MixinFit.parse(mixin), resolver);
		assertEquals(1, plan.rewrites().size(), plan.describe());
		assertEquals("addEntityWithoutEvent" + ADD, plan.rewrites().get(0).to());
		assertEquals(MixinFit.Verdict.FIT, MixinFit.evaluate(MixinRetarget.rewritten(mixin, plan), resolver).verdict(),
				"the rewritten mixin must actually bind — a plan that does not improve the verdict is kept by nobody");
	}

	/** Nothing moved: the anchor is still in the method the mod named, so there is nothing to rebind. */
	@Test
	void aSelectorThatStillHasItsAnchorIsLeftAlone() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		byte[] target = new Target(MANAGER).method("addEntity", ADD, TICKING).calling("addEntityWithoutEvent")
				.method("addEntityWithoutEvent", ADD, TICKING).bytes();
		assertTrue(MixinRetarget.plan(MixinFit.parse(mixin(MANAGER, "addEntity", TICKING)), resolver(MANAGER, target)).isEmpty());
	}

	/**
	 * text_styles' StyleMixin: withShadowColor(I) is vanilla's own method of the same shape, carries the anchor and is
	 * called in the class — and withColor(I) never calls it. No row, no move; with the census off it moves, as it did.
	 */
	@Test
	void anUnrelatedMethodOfTheSameShapeIsRefused() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.NEOFORGE);
		byte[] target = new Target(STYLE).method("withColor", WITH).method("withShadowColor", WITH, CHECK_EMPTY)
				.method("applyTo", WITH).calling("withShadowColor").bytes();
		byte[] mixin = mixin(STYLE, "withColor" + WITH, CHECK_EMPTY);
		assertTrue(MixinRetarget.plan(MixinFit.parse(mixin), resolver(STYLE, target)).isEmpty(),
				"two methods of one shape are not a rename");
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		assertTrue(MixinRetarget.plan(MixinFit.parse(mixin), resolver(STYLE, target)).isEmpty());

		System.setProperty(MixinRetarget.RENAME_CENSUS_PROPERTY, "off");
		MixinRetarget.Plan bytesAlone = MixinRetarget.plan(MixinFit.parse(mixin), resolver(STYLE, target));
		assertEquals(1, bytesAlone.rewrites().size(), "RED control: the bytes alone move it — " + bytesAlone.describe());
		assertEquals("withShadowColor" + WITH, bytesAlone.rewrites().get(0).to());
	}

	/**
	 * malilib's tooltip hook ({@code @Inject} after an addToTooltip call, taking the method's arguments):
	 * addDetailsToTooltipComponents is NeoForge's renamed tooltip body, private, and nothing calls it — the census marks
	 * the pair UNCALLED. The injector moves there to bind where the body it was written against is, and the move says it
	 * never runs; MixinFit then reads it so — soft, PARTIAL, with nothing missing outright, so the adapter keeps the plan
	 * and the final-class check reports a never-running injector instead of a required one that bound nowhere. With
	 * liveness off it reads FIT, which is how the move read before the census, when it was made on the bytes alone.
	 */
	@Test
	void aPrivateRenamedBodyNothingCallsTakesTheInjectorAndSaysItNeverRuns() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		Function<String, byte[]> resolver = resolver(ITEM_STACK, uncalledTooltip(true, false));
		byte[] mixin = inject(ITEM_STACK, "addDetailsToTooltip", ADD_TO_TOOLTIP, "(" + TOOLTIP_ARGS + CI + ")V", false);
		MixinFit.Result before = MixinFit.evaluate(mixin, resolver);
		assertEquals(1, before.hardUnresolved(), "premise: the anchor left the dispatcher — " + before.reason());

		MixinRetarget.Plan plan = MixinRetarget.plan(MixinFit.parse(mixin), resolver);
		assertEquals(List.of("addDetailsToTooltipComponents" + TOOLTIP), targets(plan), plan.describe());
		assertTrue(plan.rewrites().get(0).why().contains("nothing in the merged game calls")
				&& plan.rewrites().get(0).why().contains("never runs"), plan.describe());
		MixinFit.Result after = MixinFit.evaluate(MixinRetarget.rewritten(mixin, plan), resolver);
		assertEquals(MixinFit.Verdict.PARTIAL, after.verdict(), after.reason());
		assertEquals(0, after.hardUnresolved(), after.reason());
		assertTrue(after.reason().contains("addDetailsToTooltipComponents never runs"), after.reason());

		System.setProperty(MixinFit.LIVENESS_PROPERTY, "off");
		assertEquals(MixinFit.Verdict.FIT, MixinFit.evaluate(MixinRetarget.rewritten(mixin, plan), resolver).verdict(),
				"RED control: unasked whether it runs, the bound injector reads as fitting");
	}

	/**
	 * The UNCALLED row moves an injector only while the live class agrees: the renamed method private and called by
	 * none of its methods. A public one may be called from another class or virtually (NeoForge's
	 * {@code KeyBindsScreen.keyReleased} is, and the census leaves it out for that), a called one is not dead, and only a
	 * mod of a listed ecosystem moves — NeoForge's own mods were compiled against the dispatcher. The switch keeps it
	 * where it is; on the bytes alone (census off) it moves as before, saying nothing about running.
	 */
	@Test
	void anUncalledBodyTakesTheInjectorOnlyWhileTheLiveClassAgrees() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		byte[] mixin = inject(ITEM_STACK, "addDetailsToTooltip", ADD_TO_TOOLTIP, "(" + TOOLTIP_ARGS + CI + ")V", false);
		assertEquals(1, MixinRetarget.plan(MixinFit.parse(mixin), resolver(ITEM_STACK, uncalledTooltip(true, false))).rewrites().size(),
				"control");
		assertTrue(MixinRetarget.plan(MixinFit.parse(mixin), resolver(ITEM_STACK, uncalledTooltip(false, false))).isEmpty(), "public");
		assertTrue(MixinRetarget.plan(MixinFit.parse(mixin), resolver(ITEM_STACK, uncalledTooltip(true, true))).isEmpty(), "called");
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.NEOFORGE);
		assertTrue(MixinRetarget.plan(MixinFit.parse(mixin), resolver(ITEM_STACK, uncalledTooltip(true, false))).isEmpty(), "NeoForge mod");
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FORGE);
		assertEquals(1, MixinRetarget.plan(MixinFit.parse(mixin), resolver(ITEM_STACK, uncalledTooltip(true, false))).rewrites().size(),
				"MinecraftForge kept vanilla's body in addDetailsToTooltip");

		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		System.setProperty(MixinRetarget.UNCALLED_PROPERTY, "off");
		assertTrue(MixinRetarget.plan(MixinFit.parse(mixin), resolver(ITEM_STACK, uncalledTooltip(true, false))).isEmpty(), "switched off");
		System.clearProperty(MixinRetarget.UNCALLED_PROPERTY);
		System.setProperty(MixinRetarget.RENAME_CENSUS_PROPERTY, "off");
		MixinRetarget.Plan bytesAlone = MixinRetarget.plan(MixinFit.parse(mixin), resolver(ITEM_STACK, uncalledTooltip(false, true)));
		assertEquals(1, bytesAlone.rewrites().size(), "RED control: the bytes alone move it whatever calls it");
		assertTrue(!bytesAlone.rewrites().get(0).why().contains("never runs"), bytesAlone.describe());
	}

	/**
	 * What a handler means where nothing runs does not matter, but it must bind as it would in the body: one that
	 * captures a local of the body ({@code @Local} without argsOnly) could fail to, and stays. An argument it takes as an
	 * {@code @Local(argsOnly = true)}, a cancel, and an anchor at an ordinal the renamed body makes as often as vanilla's
	 * method do not stop it.
	 */
	@Test
	void anUncalledBodyTakesNoHandlerThatCouldFailToBindThere() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		Function<String, byte[]> resolver = resolver(ITEM_STACK, uncalledTooltip(true, false));
		String local = "(" + CI + "Ljava/util/function/Consumer;)V";
		assertTrue(MixinRetarget.plan(MixinFit.parse(inject(ITEM_STACK, "addDetailsToTooltip", ADD_TO_TOOLTIP, local, false, 1, LOCAL + "!")),
				resolver).isEmpty(), "a captured local of the body");
		assertEquals(1, MixinRetarget.plan(MixinFit.parse(inject(ITEM_STACK, "addDetailsToTooltip", ADD_TO_TOOLTIP, local, false, 1, LOCAL)),
				resolver).rewrites().size(), "an argument");
		assertEquals(1, MixinRetarget.plan(MixinFit.parse(inject(ITEM_STACK, "addDetailsToTooltip", ADD_TO_TOOLTIP, "(" + CI + ")V", true)),
				resolver).rewrites().size(), "a cancel");
		assertTrue(MixinRetarget.plan(MixinFit.parse(mixin(ITEM_STACK, "addDetailsToTooltip", ADD_TO_TOOLTIP, 23)), resolver).isEmpty(),
				"an ordinal where the fixture's body makes the call once and vanilla's 25 times");
	}

	/** A row is re-checked on the live bytes: when the class no longer calls the renamed body, there is nothing to move to. */
	@Test
	void aRowWhoseRenamedBodyIsNoLongerCalledIsRefused() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		assertTrue(MixinRetarget.plan(MixinFit.parse(mixin(MANAGER, "addEntity", TICKING)), resolver(MANAGER, manager(false))).isEmpty());
	}

	/**
	 * The row's ecosystems: MinecraftForge and NeoForge both declare addEntityWithoutEvent themselves, so their mods were
	 * compiled against the rename and miss natively the same way; a mixin whose owner is unknown moves nowhere.
	 */
	@Test
	void onlyAModOfAnEcosystemTheRowListsMoves() {
		Function<String, byte[]> resolver = resolver(MANAGER, manager(true));
		byte[] mixin = mixin(MANAGER, "addEntity", TICKING);
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FORGE);
		assertTrue(MixinRetarget.plan(MixinFit.parse(mixin), resolver).isEmpty());
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.NEOFORGE);
		assertTrue(MixinRetarget.plan(MixinFit.parse(mixin), resolver).isEmpty());
		MixinStubRebind.forget();
		assertTrue(MixinRetarget.plan(MixinFit.parse(mixin), resolver).isEmpty(), "no known owner, no move");
	}

	/**
	 * fabric-resource-loader's known-packs filter: NeoForge's startConfiguration does not call runConfiguration, it asks
	 * the client what it speaks; handlePong runs vanilla's body. The class calls it, so the body runs, and it moves.
	 */
	@Test
	void aRenamedBodyTheCarrierRunsLaterStillMoves() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		byte[] target = new Target(CONFIG).method("startConfiguration", "()V").method("runConfiguration", "()V", TASK)
				.method("handlePong", "()V").calling("runConfiguration").bytes();
		MixinRetarget.Plan plan = MixinRetarget.plan(MixinFit.parse(mixin(CONFIG, "startConfiguration", TASK)), resolver(CONFIG, target));
		assertEquals(1, plan.rewrites().size(), plan.describe());
		assertEquals("runConfiguration()V", plan.rewrites().get(0).to());
	}

	/**
	 * Two same-shaped methods both carrying the anchor: refuse. NeoForge really does cut addDetailsToTooltip in pieces —
	 * {@code addDetailsToTooltipComponents} and {@code addDetailsToTooltipTail} — and picking one would be a guess that
	 * runs an injection in the wrong half (R4 takes only a pure dispatcher along a SPLIT row).
	 */
	@Test
	void twoCandidatesThatBothFitAreRefused() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		byte[] target = new Target(ITEM_STACK).method("addDetailsToTooltip", TOOLTIP).calling("addDetailsToTooltipTail")
				.method("addDetailsToTooltipComponents", TOOLTIP, ADD_TO_TOOLTIP)
				.method("addDetailsToTooltipTail", TOOLTIP, ADD_TO_TOOLTIP).bytes();
		assertTrue(MixinRetarget.plan(MixinFit.parse(mixin(ITEM_STACK, "addDetailsToTooltip", ADD_TO_TOOLTIP)),
				resolver(ITEM_STACK, target)).isEmpty(), "a rewrite to the wrong half is an injection running where the mod did not ask, silently");
	}

	/**
	 * A candidate of a DIFFERENT shape is refused however well its body matches: the whole safety of this rule is that
	 * the handler's parameters, CallbackInfo and @Local captures stay valid, which only an identical descriptor
	 * guarantees. Judged on the bytes alone, so the row is not what refuses it.
	 */
	@Test
	void aCandidateWithAnotherDescriptorIsRefused() {
		System.setProperty(MixinRetarget.RENAME_CENSUS_PROPERTY, "off");
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		byte[] target = new Target(MANAGER).method("addEntity", ADD).calling("addEntityWithoutEvent")
				.method("addEntityWithoutEvent", "(Lnet/minecraft/world/level/entity/EntityAccess;ZI)Z", TICKING).bytes();
		assertTrue(MixinRetarget.plan(MixinFit.parse(mixin(MANAGER, "addEntity", TICKING)), resolver(MANAGER, target)).isEmpty());
	}

	/** A static body cannot take an instance handler: refused whatever it calls, on the bytes alone too. */
	@Test
	void aCandidateOfTheOtherStaticnessIsRefused() {
		System.setProperty(MixinRetarget.RENAME_CENSUS_PROPERTY, "off");
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		byte[] target = new Target(MANAGER).method("addEntity", ADD).calling("addEntityWithoutEvent")
				.staticMethod("addEntityWithoutEvent", ADD, TICKING).bytes();
		assertTrue(MixinRetarget.plan(MixinFit.parse(mixin(MANAGER, "addEntity", TICKING)), resolver(MANAGER, target)).isEmpty());
	}

	/**
	 * The mod names the method by bare name, and the target OVERRIDES a superclass method of the same name and
	 * descriptor (ServerPlayer.startSleepInBed over Player's). That is still one method to Mixin, and NeoForge's lambda
	 * that now carries the body — the method hands it on as a method handle — is rebound to, as apoli-legacy needs.
	 */
	@Test
	void anOverriddenSuperclassMethodIsNotASecondCandidate() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		String base = "net/minecraft/world/entity/player/Player";
		byte[] player = new Target(base).method("startSleepInBed", SLEEP).bytes();
		byte[] server = new Target(PLAYER, base).method("startSleepInBed", SLEEP).handing("lambda$startSleepInBed$0")
				.method("lambda$startSleepInBed$0", SLEEP, RESPAWN).bytes();
		Function<String, byte[]> resolver = Map.of(PLAYER + ".class", server, base + ".class", player)::get;
		MixinRetarget.Plan plan = MixinRetarget.plan(MixinFit.parse(inject(PLAYER, "startSleepInBed", RESPAWN, "(" + CIR + ")V", false)), resolver);
		assertEquals(1, plan.rewrites().size(), plan.describe());
		assertEquals("lambda$startSleepInBed$0" + SLEEP, plan.rewrites().get(0).to());
	}

	/**
	 * A row authorizes the calls that moved with the body, not every call the renamed method makes. An anchor the
	 * reference's method never made — here a call only the carrier's renamed body makes — missed on the mod's own game
	 * too; moving it would make it bind where it never did. With the census off it moves, as it did.
	 */
	@Test
	void anAnchorTheReferenceMethodNeverMadeDoesNotMove() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		String carriers = "Lnet/neoforged/neoforge/event/EventHooks;onEntityAdded(Lnet/minecraft/world/level/entity/EntityAccess;)V";
		byte[] target = new Target(MANAGER).method("addEntity", ADD).calling("addEntityWithoutEvent")
				.method("addEntityWithoutEvent", ADD, TICKING, carriers).bytes();
		Function<String, byte[]> resolver = resolver(MANAGER, target);
		assertTrue(MixinRetarget.plan(MixinFit.parse(mixin(MANAGER, "addEntity", carriers)), resolver).isEmpty());
		assertEquals(1, MixinRetarget.plan(MixinFit.parse(mixin(MANAGER, "addEntity", TICKING)), resolver).rewrites().size(),
				"control: vanilla's own call in the same body moves");
		System.setProperty(MixinRetarget.RENAME_CENSUS_PROPERTY, "off");
		assertEquals(1, MixinRetarget.plan(MixinFit.parse(mixin(MANAGER, "addEntity", carriers)), resolver).rewrites().size(), "RED control");
	}

	/** An anchor written without its descriptor that would also bind to a call the carrier added does not move. */
	@Test
	void aLooseAnchorThatAlsoNamesACarriersCallDoesNotMove() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		byte[] target = new Target(MANAGER).method("addEntity", ADD).calling("addEntityWithoutEvent")
				.method("addEntityWithoutEvent", ADD, TICKING, "Lnet/minecraft/world/level/entity/Visibility;isTicking(I)Z").bytes();
		Function<String, byte[]> resolver = resolver(MANAGER, target);
		assertTrue(MixinRetarget.plan(MixinFit.parse(mixin(MANAGER, "addEntity", "Lnet/minecraft/world/level/entity/Visibility;isTicking")),
				resolver).isEmpty());
		assertEquals(1, MixinRetarget.plan(MixinFit.parse(mixin(MANAGER, "addEntity", TICKING)), resolver).rewrites().size(),
				"control: written out, the anchor names only vanilla's call");
	}

	/**
	 * NeoForge's addDetailsToTooltipTail is a PIECE of vanilla's tooltip: its advanced tail, which addDetailsToTooltip
	 * calls with its own arguments. A handler that needs nothing but the call — the ModifyArg here, polymer's id — moves.
	 */
	@Test
	void aPieceTakesAHandlerThatNeedsOnlyTheCall() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		MixinRetarget.Plan plan = MixinRetarget.plan(MixinFit.parse(mixin(ITEM_STACK, "addDetailsToTooltip", GET_KEY)), resolver(ITEM_STACK, tooltip(true)));
		assertEquals(1, plan.rewrites().size(), plan.describe());
		assertEquals("addDetailsToTooltipTail" + TOOLTIP, plan.rewrites().get(0).to());
		assertTrue(plan.rewrites().get(0).why().contains("pieces"), plan.describe());
	}

	/**
	 * Cancelling in a piece returns from the piece, where vanilla returned from the method: into the tooltip tail, which
	 * addDetailsToTooltip goes on after, a cancellable @Inject stays. The same injector that cannot cancel moves, and on a
	 * whole RENAME the cancellable one moves too.
	 */
	@Test
	void aPieceRefusesAHandlerThatCanCancelTheMethod() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		Function<String, byte[]> resolver = resolver(ITEM_STACK, tooltip(true));
		assertTrue(MixinRetarget.plan(MixinFit.parse(inject(ITEM_STACK, "addDetailsToTooltip", GET_KEY, "(" + CI + ")V", true)), resolver).isEmpty());
		assertEquals(1, MixinRetarget.plan(MixinFit.parse(inject(ITEM_STACK, "addDetailsToTooltip", GET_KEY, "(" + CI + ")V", false)),
				resolver).rewrites().size(), "control: the same injector that cannot cancel");
		assertEquals(1, MixinRetarget.plan(MixinFit.parse(inject(MANAGER, "addEntity", TICKING, "(" + CIR + ")V", true)),
				resolver(MANAGER, manager(true))).rewrites().size(), "control: a whole body keeps every return the handler knew");
		System.setProperty(MixinRetarget.RENAME_CENSUS_PROPERTY, "off");
		assertEquals(1, MixinRetarget.plan(MixinFit.parse(inject(ITEM_STACK, "addDetailsToTooltip", GET_KEY, "(" + CI + ")V", true)),
				resolver).rewrites().size(), "RED control: on the bytes alone it moved");
	}

	/**
	 * fabric-item-api's postTooltipsAdvanced threads a @Share index with the injectors left in addDetailsToTooltip: in
	 * the piece it would read a fresh 0. A sugar parameter other than an argument keeps the handler where it is.
	 */
	@Test
	void aPieceRefusesAHandlerThatSharesWithTheMethod() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		Function<String, byte[]> resolver = resolver(ITEM_STACK, tooltip(true));
		String shared = "(" + CI + "Lcom/llamalad7/mixinextras/sugar/ref/LocalIntRef;)V";
		assertTrue(MixinRetarget.plan(MixinFit.parse(inject(ITEM_STACK, "addDetailsToTooltip", GET_KEY, shared, false, 1, SHARE)), resolver).isEmpty());
		System.setProperty(MixinRetarget.RENAME_CENSUS_PROPERTY, "off");
		assertEquals(1, MixinRetarget.plan(MixinFit.parse(inject(ITEM_STACK, "addDetailsToTooltip", GET_KEY, shared, false, 1, SHARE)),
				resolver).rewrites().size(), "RED control");
	}

	/**
	 * A handler that takes the method's arguments — as an @Inject's leading parameters, or as apoli's
	 * {@code @Local(argsOnly = true)} consumer — gets the piece's in the piece. It moves when the method hands the piece
	 * its own, and not when the method hands it others (NeoForge's InventoryScreen passes angles where it was given the
	 * mouse position).
	 */
	@Test
	void aPieceTakesTheMethodsArgumentsOnlyWhenTheMethodHandsThemOn() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		String plain = "(" + TOOLTIP_ARGS + CI + ")V";
		String argument = "(" + CI + "Ljava/util/function/Consumer;)V";
		for (byte[] mixin : List.of(inject(ITEM_STACK, "addDetailsToTooltip", GET_KEY, plain, false),
				inject(ITEM_STACK, "addDetailsToTooltip", GET_KEY, argument, false, 1, LOCAL))) {
			assertEquals(1, MixinRetarget.plan(MixinFit.parse(mixin), resolver(ITEM_STACK, tooltip(true))).rewrites().size(),
					"handed on in place");
			assertTrue(MixinRetarget.plan(MixinFit.parse(mixin), resolver(ITEM_STACK, tooltip(false))).isEmpty(), "handed other arguments");
		}
		// A @Local that is not an argument means something else in a piece wherever its arguments come from.
		assertTrue(MixinRetarget.plan(MixinFit.parse(inject(ITEM_STACK, "addDetailsToTooltip", GET_KEY, argument, false, 1, LOCAL + "!")),
				resolver(ITEM_STACK, tooltip(true))).isEmpty());
	}

	/** The startSleepInBed lambda captures the position in place, as NeoForge's does: an argument-taking handler moves. */
	@Test
	void aLambdaThatCapturesTheArgumentsTakesThem() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		String takesPos = "(Lnet/minecraft/core/BlockPos;" + CIR + ")V";
		byte[] mixin = inject(PLAYER, "startSleepInBed", RESPAWN, takesPos, false);
		byte[] captured = new Target(PLAYER).method("startSleepInBed", SLEEP).capturing("lambda$startSleepInBed$0")
				.method("lambda$startSleepInBed$0", SLEEP, RESPAWN).bytes();
		MixinRetarget.Plan plan = MixinRetarget.plan(MixinFit.parse(mixin), resolver(PLAYER, captured));
		assertEquals(1, plan.rewrites().size(), plan.describe());
		assertEquals("lambda$startSleepInBed$0" + SLEEP, plan.rewrites().get(0).to());
		byte[] applied = new Target(PLAYER).method("startSleepInBed", SLEEP).handing("lambda$startSleepInBed$0")
				.method("lambda$startSleepInBed$0", SLEEP, RESPAWN).bytes();
		assertTrue(MixinRetarget.plan(MixinFit.parse(mixin), resolver(PLAYER, applied)).isEmpty(),
				"control: a lambda handed the position by its caller, not captured here, is not the method's arguments");
	}

	/**
	 * NeoForge's startSleepInBed hands its lambda's answer to its hook and returns it whenever it is a left: a handler in
	 * the lambda that cancels only with a left leaves the method with it, the way vanilla's own checks in the lambda say
	 * no. apoli's preventAvianSleep hands its callback to a lambda over its powers that sets Either.left(null); others set
	 * the left themselves, through a local, or in a private helper. With the census off they move too, on the bytes alone.
	 */
	@Test
	void aPieceWhoseMethodReturnsItsLeftsTakesAHandlerThatCancelsWithALeft() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		Function<String, byte[]> resolver = resolver(PLAYER, sleeping(true));
		for (Cancel cancel : List.of(Cancel.LEFT, Cancel.LEFT_THROUGH_LOCAL, Cancel.LEFT_NULL_IN_LAMBDA, Cancel.LEFT_IN_HELPER)) {
			MixinRetarget.Plan plan = MixinRetarget.plan(MixinFit.parse(cancellingInject(cancel)), resolver);
			assertEquals(List.of("lambda$startSleepInBed$0" + SLEEP), targets(plan), cancel + ": " + plan.describe());
		}
		MixinRetarget.Plan wrap = MixinRetarget.plan(MixinFit.parse(cancellingWrap(Cancel.LEFT)), resolver);
		assertEquals(List.of("lambda$startSleepInBed$0" + SLEEP), targets(wrap), "fabric-entity-events' @Cancellable wrap: " + wrap.describe());
	}

	/**
	 * Every way out that is not a left keeps the handler where it is: cancel() (a null result), a right, a value that is
	 * a left only on one branch, and a callback handed to anything but the mixin's own methods or kept in a field. With
	 * the census off each moves, as on origin/main before the census.
	 */
	@Test
	void aHandlerThatCanCancelWithAnythingButALeftStaysOutOfThePiece() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		Function<String, byte[]> resolver = resolver(PLAYER, sleeping(true));
		List<Cancel> others = List.of(Cancel.CANCEL, Cancel.RIGHT, Cancel.LEFT_OR_RIGHT, Cancel.ESCAPES, Cancel.KEPT);
		assertEquals(List.of(), others.stream().filter(c -> !MixinRetarget.plan(MixinFit.parse(cancellingInject(c)), resolver).isEmpty()).toList(),
				"moved into the piece");
		assertTrue(MixinRetarget.plan(MixinFit.parse(cancellingWrap(Cancel.CANCEL)), resolver).isEmpty(), "@Cancellable cancel()");
		System.setProperty(MixinRetarget.RENAME_CENSUS_PROPERTY, "off");
		assertEquals(others, others.stream().filter(c -> MixinRetarget.plan(MixinFit.parse(cancellingInject(c)), resolver).rewrites().size() == 1)
				.toList(), "RED control: on the bytes alone every one moves");
	}

	/**
	 * The left is returned only because the method returns it: re-read on the live bytes, a method that goes on after
	 * the lambda whatever it answered takes no handler that can cancel, and with the left exit switched off neither does
	 * NeoForge's. A handler that cannot cancel moves either way.
	 */
	@Test
	void aCancellingHandlerMovesOnlyWhereTheLiveMethodReturnsTheLeft() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		byte[] cancelling = cancellingInject(Cancel.LEFT);
		assertTrue(MixinRetarget.plan(MixinFit.parse(cancelling), resolver(PLAYER, sleeping(false))).isEmpty(), "the method goes on");
		assertEquals(1, MixinRetarget.plan(MixinFit.parse(inject(PLAYER, "startSleepInBed", RESPAWN, "(Lnet/minecraft/core/BlockPos;" + CIR + ")V",
				false)), resolver(PLAYER, sleeping(false))).rewrites().size(), "control: a handler that cannot cancel");
		System.setProperty(MixinRetarget.LEFT_EXIT_PROPERTY, "off");
		try {
			assertTrue(MixinRetarget.plan(MixinFit.parse(cancelling), resolver(PLAYER, sleeping(true))).isEmpty(), "leftExit=off");
		} finally {
			System.clearProperty(MixinRetarget.LEFT_EXIT_PROPERTY);
		}
		assertEquals(1, MixinRetarget.plan(MixinFit.parse(cancelling), resolver(PLAYER, sleeping(true))).rewrites().size(), "control");
	}

	/**
	 * A row says how often the reference's method makes its call. Where the carrier's renamed body makes it more often, an
	 * anchor that binds every match would bind the carrier's copy too, and an ordinal may name it: refused. Written with
	 * an ordinal, the anchor moves only where the counts agree and it can match nothing else.
	 */
	@Test
	void anAnchorMovesOnlyAsOftenAsTheReferenceMadeIt() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		byte[] twice = new Target(MANAGER).method("addEntity", ADD).calling("addEntityWithoutEvent")
				.method("addEntityWithoutEvent", ADD, TICKING, TICKING).bytes();
		assertTrue(MixinRetarget.plan(MixinFit.parse(mixin(MANAGER, "addEntity", TICKING)), resolver(MANAGER, twice)).isEmpty(), "made twice");
		assertTrue(MixinRetarget.plan(MixinFit.parse(mixin(MANAGER, "addEntity", TICKING, 0)), resolver(MANAGER, twice)).isEmpty(), "ordinal 0");
		Function<String, byte[]> once = resolver(MANAGER, manager(true));
		assertEquals(1, MixinRetarget.plan(MixinFit.parse(mixin(MANAGER, "addEntity", TICKING, 0)), once).rewrites().size(), "control: once, ordinal 0");
		assertTrue(MixinRetarget.plan(MixinFit.parse(mixin(MANAGER, "addEntity", "Lnet/minecraft/world/level/entity/Visibility;isTicking", 0)),
				once).isEmpty(), "a loose anchor's ordinal counts matches this cannot see");
		System.setProperty(MixinRetarget.RENAME_CENSUS_PROPERTY, "off");
		assertEquals(1, MixinRetarget.plan(MixinFit.parse(mixin(MANAGER, "addEntity", TICKING)), resolver(MANAGER, twice)).rewrites().size(),
				"RED control: on the bytes alone it moves");
	}

	/** A method that stores into a parameter before the call hands the piece another value under the same load. */
	@Test
	void aPieceIsNotHandedArgumentsTheMethodChangedFirst() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		byte[] changed = new Target(ITEM_STACK).method("addDetailsToTooltip", TOOLTIP).storing(5).calling("addDetailsToTooltipTail")
				.method("addDetailsToTooltipTail", TOOLTIP, GET_KEY).bytes();
		byte[] takes = inject(ITEM_STACK, "addDetailsToTooltip", GET_KEY, "(" + TOOLTIP_ARGS + CI + ")V", false);
		assertTrue(MixinRetarget.plan(MixinFit.parse(takes), resolver(ITEM_STACK, changed)).isEmpty(), "the consumer was replaced first");
		assertEquals(1, MixinRetarget.plan(MixinFit.parse(takes), resolver(ITEM_STACK, tooltip(true))).rewrites().size(), "control: handed in place");
		assertEquals(1, MixinRetarget.plan(MixinFit.parse(inject(ITEM_STACK, "addDetailsToTooltip", GET_KEY, "(" + CI + ")V", false)),
				resolver(ITEM_STACK, changed)).rewrites().size(), "control: a handler that takes no argument");
	}

	/**
	 * MixinExtras keeps one value per @Share key per target method: on a whole RENAME a handler that shares one moves only
	 * with every handler of its mixin sharing it there. A HEAD filler left in addEntity keeps the TICKING reader with it;
	 * two that both move go together; an explicit namespace may be shared with mixins this cannot see.
	 */
	@Test
	void aRenameMovesASharingHandlerOnlyWithItsGroup() {
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		Function<String, byte[]> resolver = resolver(MANAGER, manager(true));
		assertTrue(MixinRetarget.plan(MixinFit.parse(sharing(new String[] {"filler", "HEAD", null}, new String[] {"reader", TICKING, null})),
				resolver).isEmpty(), "the HEAD filler stays in addEntity");
		assertEquals(List.of("addEntityWithoutEvent" + ADD, "addEntityWithoutEvent" + ADD), targets(MixinRetarget.plan(MixinFit.parse(
				sharing(new String[] {"filler", TICKING, null}, new String[] {"reader", TICKING, null})), resolver)), "control: both move");
		assertEquals(1, MixinRetarget.plan(MixinFit.parse(sharing(new String[] {"filler", "HEAD", null}, new String[] {"reader", TICKING, null},
				true)), resolver).rewrites().size(), "control: alone in its key");
		assertTrue(MixinRetarget.plan(MixinFit.parse(sharing(new String[] {"reader", TICKING, "elsewhere"})), resolver).isEmpty(), "namespaced");
		System.setProperty(MixinRetarget.RENAME_CENSUS_PROPERTY, "off");
		assertEquals(1, MixinRetarget.plan(MixinFit.parse(sharing(new String[] {"filler", "HEAD", null}, new String[] {"reader", TICKING, null})),
				resolver).rewrites().size(), "RED control: on the bytes alone the reader moves without its filler");
	}

	@Test
	void theSwitchOffPlansNothing() {
		System.setProperty(MixinRetarget.PROPERTY, "off");
		MixinStubRebind.noteEcosystem(MIXIN, Ecosystem.FABRIC);
		assertTrue(MixinRetarget.plan(MixinFit.parse(mixin(MANAGER, "addEntity", TICKING)), resolver(MANAGER, manager(true))).isEmpty());
	}

	// --- fixtures ---

	private static List<String> targets(MixinRetarget.Plan plan) {
		return plan.rewrites().stream().map(MixinRetarget.Rewrite::to).toList();
	}

	private static final String EITHER = "com/mojang/datafixers/util/Either";
	private static final String EITHER_LEFT = "(Ljava/lang/Object;)L" + EITHER + ";";
	private static final String PROBLEM = "net/minecraft/world/entity/player/Player$BedSleepingProblem";
	private static final String GET_VALUE = "Lnet/minecraft/world/level/block/state/BlockState;getValue("
			+ "Lnet/minecraft/world/level/block/state/properties/Property;)Ljava/lang/Comparable;";
	private static final String WRAP = "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";
	private static final String CANCELLABLE = "Lcom/llamalad7/mixinextras/sugar/Cancellable;";
	private static final Handle METAFACTORY = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
			"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
					+ "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
					+ "Ljava/lang/invoke/CallSite;", false);

	/**
	 * NeoForge's startSleepInBed: the lambda's answer through EventHooks.canPlayerStartSleeping, returned when it is a
	 * left ({@code returnsLefts}), else dropped while the method goes on. The lambda makes the respawn and facing calls.
	 */
	private static byte[] sleeping(boolean returnsLefts) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, PLAYER, null, "java/lang/Object", null);
		MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC, "startSleepInBed", SLEEP, null, null);
		m.visitCode();
		m.visitVarInsn(Opcodes.ALOAD, 0);
		m.visitVarInsn(Opcodes.ALOAD, 1);
		m.visitInvokeDynamicInsn("get", "(L" + PLAYER + ";Lnet/minecraft/core/BlockPos;)Ljava/util/function/Supplier;", METAFACTORY,
				Type.getType("()Ljava/lang/Object;"), new Handle(Opcodes.H_INVOKEVIRTUAL, PLAYER, "lambda$startSleepInBed$0", SLEEP, false),
				Type.getType("()L" + EITHER + ";"));
		m.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Supplier", "get", "()Ljava/lang/Object;", true);
		m.visitTypeInsn(Opcodes.CHECKCAST, EITHER);
		m.visitVarInsn(Opcodes.ASTORE, 2);
		m.visitVarInsn(Opcodes.ALOAD, 0);
		m.visitVarInsn(Opcodes.ALOAD, 1);
		m.visitVarInsn(Opcodes.ALOAD, 2);
		m.visitMethodInsn(Opcodes.INVOKESTATIC, "net/neoforged/neoforge/event/EventHooks", "canPlayerStartSleeping",
				"(L" + PLAYER + ";Lnet/minecraft/core/BlockPos;L" + EITHER + ";)L" + EITHER + ";", false);
		m.visitVarInsn(Opcodes.ASTORE, 2);
		if (returnsLefts) {
			org.objectweb.asm.Label goOn = new org.objectweb.asm.Label();
			m.visitVarInsn(Opcodes.ALOAD, 2);
			m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, EITHER, "left", "()Ljava/util/Optional;", false);
			m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Optional", "isPresent", "()Z", false);
			m.visitJumpInsn(Opcodes.IFEQ, goOn);
			m.visitVarInsn(Opcodes.ALOAD, 2);
			m.visitInsn(Opcodes.ARETURN);
			m.visitLabel(goOn);
		}
		m.visitInsn(Opcodes.ACONST_NULL);
		m.visitInsn(Opcodes.ARETURN);
		m.visitMaxs(0, 0);
		m.visitEnd();
		MethodVisitor lambda = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC, "lambda$startSleepInBed$0", SLEEP, null, null);
		lambda.visitCode();
		for (String anchor : List.of(RESPAWN, GET_VALUE)) {
			MixinFit.Member call = MixinFit.parseMember(anchor);
			for (int i = 0; i < Type.getArgumentTypes(call.desc()).length; i++) lambda.visitInsn(Opcodes.ACONST_NULL);
			lambda.visitMethodInsn(Opcodes.INVOKESTATIC, call.owner(), call.name(), call.desc(), false);
			if (Type.getReturnType(call.desc()).getSize() > 0) lambda.visitInsn(Opcodes.POP);
		}
		lambda.visitInsn(Opcodes.ACONST_NULL);
		lambda.visitInsn(Opcodes.ARETURN);
		lambda.visitMaxs(0, 0);
		lambda.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** How a handler cancels, written into its body. */
	enum Cancel {
		/** {@code cir.setReturnValue(Either.left(OTHER_PROBLEM))}. */
		LEFT,
		/** The left built into a local first. */
		LEFT_THROUGH_LOCAL,
		/** apoli: the callback captured by a lambda of the mixin's own, which sets {@code Either.left(null)}. */
		LEFT_NULL_IN_LAMBDA,
		/** The callback handed to a private helper of the mixin's own, which sets the left. */
		LEFT_IN_HELPER,
		/** {@code cir.cancel()}: the method returns null. */
		CANCEL,
		/** {@code cir.setReturnValue(Either.right(null))}. */
		RIGHT,
		/** A left on one branch, a right on the other. */
		LEFT_OR_RIGHT,
		/** The callback handed to a method of another class. */
		ESCAPES,
		/** The callback kept in a field. */
		KEPT
	}

	/** Emits {@code cancel} on the callback in {@code slot}; the stack is left empty. */
	private static void cancel(MethodVisitor m, Cancel cancel, int slot) {
		String setter = "setReturnValue";
		switch (cancel) {
			case LEFT, LEFT_IN_HELPER, LEFT_NULL_IN_LAMBDA -> {
				m.visitVarInsn(Opcodes.ALOAD, slot);
				if (cancel == Cancel.LEFT_NULL_IN_LAMBDA) m.visitInsn(Opcodes.ACONST_NULL);
				else m.visitFieldInsn(Opcodes.GETSTATIC, PROBLEM, "OTHER_PROBLEM", "L" + PROBLEM + ";");
				m.visitMethodInsn(Opcodes.INVOKESTATIC, EITHER, "left", EITHER_LEFT, false);
				m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CIR.substring(1, CIR.length() - 1), setter, "(Ljava/lang/Object;)V", false);
			}
			case LEFT_THROUGH_LOCAL -> {
				m.visitFieldInsn(Opcodes.GETSTATIC, PROBLEM, "OTHER_PROBLEM", "L" + PROBLEM + ";");
				m.visitMethodInsn(Opcodes.INVOKESTATIC, EITHER, "left", EITHER_LEFT, false);
				m.visitVarInsn(Opcodes.ASTORE, slot + 1);
				m.visitVarInsn(Opcodes.ALOAD, slot);
				m.visitVarInsn(Opcodes.ALOAD, slot + 1);
				m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CIR.substring(1, CIR.length() - 1), setter, "(Ljava/lang/Object;)V", false);
			}
			case CANCEL -> {
				m.visitVarInsn(Opcodes.ALOAD, slot);
				m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CIR.substring(1, CIR.length() - 1), "cancel", "()V", false);
			}
			case RIGHT -> {
				m.visitVarInsn(Opcodes.ALOAD, slot);
				m.visitInsn(Opcodes.ACONST_NULL);
				m.visitMethodInsn(Opcodes.INVOKESTATIC, EITHER, "right", EITHER_LEFT, false);
				m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CIR.substring(1, CIR.length() - 1), setter, "(Ljava/lang/Object;)V", false);
			}
			case LEFT_OR_RIGHT -> {
				org.objectweb.asm.Label right = new org.objectweb.asm.Label(), set = new org.objectweb.asm.Label();
				m.visitVarInsn(Opcodes.ALOAD, slot);
				m.visitVarInsn(Opcodes.ALOAD, 1);
				m.visitJumpInsn(Opcodes.IFNULL, right);
				m.visitInsn(Opcodes.ACONST_NULL);
				m.visitMethodInsn(Opcodes.INVOKESTATIC, EITHER, "left", EITHER_LEFT, false);
				m.visitJumpInsn(Opcodes.GOTO, set);
				m.visitLabel(right);
				m.visitInsn(Opcodes.ACONST_NULL);
				m.visitMethodInsn(Opcodes.INVOKESTATIC, EITHER, "right", EITHER_LEFT, false);
				m.visitLabel(set);
				m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CIR.substring(1, CIR.length() - 1), setter, "(Ljava/lang/Object;)V", false);
			}
			case ESCAPES -> {
				m.visitVarInsn(Opcodes.ALOAD, slot);
				m.visitMethodInsn(Opcodes.INVOKESTATIC, "some/Elsewhere", "take", "(" + CIR + ")V", false);
			}
			case KEPT -> {
				m.visitVarInsn(Opcodes.ALOAD, 0);
				m.visitVarInsn(Opcodes.ALOAD, slot);
				m.visitFieldInsn(Opcodes.PUTFIELD, MIXIN, "kept", CIR);
			}
		}
	}

	/** A cancellable {@code @Inject} at startSleepInBed's respawn call, {@code (BlockPos, CallbackInfoReturnable)V}, that cancels so. */
	static byte[] cancellingInject(Cancel cancel) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		mixinHeader(cw, PLAYER);
		cw.visitField(Opcodes.ACC_PRIVATE, "kept", CIR, null, null).visitEnd();
		String desc = "(Lnet/minecraft/core/BlockPos;" + CIR + ")V";
		MethodVisitor h = cw.visitMethod(Opcodes.ACC_PRIVATE, "handler", desc, null, null);
		AnnotationVisitor inj = h.visitAnnotation(INJECT, true);
		selectorAndAt(inj, "startSleepInBed", RESPAWN, -1);
		inj.visit("cancellable", true);
		inj.visitEnd();
		h.visitCode();
		if (cancel == Cancel.LEFT_NULL_IN_LAMBDA) {
			h.visitVarInsn(Opcodes.ALOAD, 0);
			h.visitVarInsn(Opcodes.ALOAD, 1);
			h.visitVarInsn(Opcodes.ALOAD, 2);
			String lambdaDesc = "(Lnet/minecraft/core/BlockPos;" + CIR + "Ljava/lang/Object;)V";
			h.visitInvokeDynamicInsn("accept", "(L" + MIXIN + ";Lnet/minecraft/core/BlockPos;" + CIR + ")Ljava/util/function/Consumer;",
					METAFACTORY, Type.getType("(Ljava/lang/Object;)V"), new Handle(Opcodes.H_INVOKEVIRTUAL, MIXIN, "lambda$handler$0", lambdaDesc, false),
					Type.getType("(Ljava/lang/Object;)V"));
			h.visitInsn(Opcodes.POP);
			MethodVisitor lambda = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC, "lambda$handler$0", lambdaDesc, null, null);
			lambda.visitCode();
			cancel(lambda, cancel, 2);
			lambda.visitInsn(Opcodes.RETURN);
			lambda.visitMaxs(0, 0);
			lambda.visitEnd();
		} else if (cancel == Cancel.LEFT_IN_HELPER) {
			h.visitVarInsn(Opcodes.ALOAD, 0);
			h.visitVarInsn(Opcodes.ALOAD, 2);
			h.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MIXIN, "helper", "(" + CIR + ")V", false);
			MethodVisitor helper = cw.visitMethod(Opcodes.ACC_PRIVATE, "helper", "(" + CIR + ")V", null, null);
			helper.visitCode();
			cancel(helper, Cancel.LEFT, 1);
			helper.visitInsn(Opcodes.RETURN);
			helper.visitMaxs(0, 0);
			helper.visitEnd();
		} else {
			cancel(h, cancel, 2);
		}
		h.visitInsn(Opcodes.RETURN);
		h.visitMaxs(0, 0);
		h.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/**
	 * fabric-entity-events' redirectSleepDirection: a {@code @WrapOperation} of the lambda's facing read that takes the
	 * position and a {@code @Cancellable} callback, cancels so and returns null.
	 */
	static byte[] cancellingWrap(Cancel cancel) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		mixinHeader(cw, PLAYER);
		String desc = "(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/properties/Property;"
				+ "Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;Lnet/minecraft/core/BlockPos;" + CIR + ")Ljava/lang/Comparable;";
		MethodVisitor h = cw.visitMethod(Opcodes.ACC_PRIVATE, "handler", desc, null, null);
		AnnotationVisitor inj = h.visitAnnotation(WRAP, true);
		selectorAndAt(inj, "startSleepInBed", GET_VALUE, -1);
		inj.visitEnd();
		h.visitParameterAnnotation(4, CANCELLABLE, false).visitEnd();
		h.visitCode();
		cancel(h, cancel, 5);
		h.visitInsn(Opcodes.ACONST_NULL);
		h.visitInsn(Opcodes.ARETURN);
		h.visitMaxs(0, 0);
		h.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/**
	 * {@code @Inject}s into addEntity, each {@code (EntityAccess, boolean, CallbackInfoReturnable, @Share LocalIntRef)V}:
	 * {@code {name, "HEAD" or an INVOKE anchor, namespace or null}}; with {@code ownKeys} each shares a key of its own.
	 */
	private static byte[] sharing(String[]... handlers) {
		return sharing(handlers, false);
	}

	private static byte[] sharing(String[] first, String[] second, boolean ownKeys) {
		return sharing(new String[][] {first, second}, ownKeys);
	}

	private static byte[] sharing(String[][] handlers, boolean ownKeys) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		mixinHeader(cw, MANAGER);
		for (String[] spec : handlers) {
			MethodVisitor h = cw.visitMethod(Opcodes.ACC_PRIVATE, spec[0], "(Lnet/minecraft/world/level/entity/EntityAccess;Z" + CIR
					+ "Lcom/llamalad7/mixinextras/sugar/ref/LocalIntRef;)V", null, null);
			AnnotationVisitor inj = h.visitAnnotation(INJECT, true);
			AnnotationVisitor method = inj.visitArray("method");
			method.visit(null, "addEntity");
			method.visitEnd();
			AnnotationVisitor at = inj.visitAnnotation("at", "Lorg/spongepowered/asm/mixin/injection/At;");
			if (spec[1].equals("HEAD")) {
				at.visit("value", "HEAD");
			} else {
				at.visit("value", "INVOKE");
				at.visit("target", spec[1]);
			}
			at.visitEnd();
			inj.visitEnd();
			AnnotationVisitor share = h.visitParameterAnnotation(3, SHARE, false);
			share.visit("value", ownKeys ? spec[0] : "index");
			if (spec[2] != null) share.visit("namespace", spec[2]);
			share.visitEnd();
			h.visitCode();
			h.visitInsn(Opcodes.RETURN);
			h.visitMaxs(0, 0);
			h.visitEnd();
		}
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static void mixinHeader(ClassWriter cw, String target) {
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, MIXIN, null, "java/lang/Object", null);
		AnnotationVisitor at = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
		AnnotationVisitor targets = at.visitArray("value");
		targets.visit(null, Type.getObjectType(target));
		targets.visitEnd();
		at.visitEnd();
	}

	private static void selectorAndAt(AnnotationVisitor injector, String selector, String anchor, int ordinal) {
		AnnotationVisitor method = injector.visitArray("method");
		method.visit(null, selector);
		method.visitEnd();
		AnnotationVisitor at = injector.visitAnnotation("at", "Lorg/spongepowered/asm/mixin/injection/At;");
		at.visit("value", "INVOKE");
		at.visit("target", anchor);
		if (ordinal >= 0) at.visit("ordinal", ordinal);
		at.visitEnd();
	}

	/** NeoForge's shape: addEntity posts nothing here, and calls the renamed body when {@code dispatches}. */
	private static byte[] manager(boolean dispatches) {
		Target target = new Target(MANAGER).method("addEntity", ADD);
		if (dispatches) target.calling("addEntityWithoutEvent");
		return target.method("addEntityWithoutEvent", ADD, TICKING).bytes();
	}

	/**
	 * NeoForge's tooltip shape: addDetailsToTooltip calls the tail piece, with its own arguments when {@code inPlace},
	 * else with another consumer; the tail makes the id line.
	 */
	private static byte[] tooltip(boolean inPlace) {
		Target target = new Target(ITEM_STACK).method("addDetailsToTooltip", TOOLTIP);
		if (inPlace) target.calling("addDetailsToTooltipTail");
		else target.callingWithOthers("addDetailsToTooltipTail");
		return target.method("addDetailsToTooltipTail", TOOLTIP, GET_KEY).bytes();
	}

	/**
	 * NeoForge's tooltip shape around its renamed body: addDetailsToTooltip makes none of vanilla's component calls, and
	 * addDetailsToTooltipComponents makes one, {@code private} or public, and called by addDetailsToTooltip when
	 * {@code called}.
	 */
	private static byte[] uncalledTooltip(boolean isPrivate, boolean called) {
		Target target = new Target(ITEM_STACK).method("addDetailsToTooltip", TOOLTIP);
		if (called) target.calling("addDetailsToTooltipComponents");
		return (isPrivate ? target.method("addDetailsToTooltipComponents", TOOLTIP, ADD_TO_TOOLTIP)
				: target.publicMethod("addDetailsToTooltipComponents", TOOLTIP, ADD_TO_TOOLTIP)).bytes();
	}

	/** A class of private methods, each making its anchor calls and then, if asked, calling or handing on a sibling. */
	private static final class Target {
		private final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		private final String name;
		private final Map<String, String> descs = new HashMap<>();
		private MethodVisitor open;
		private String openDesc;
		private boolean openStatic;

		Target(String name) {
			this(name, "java/lang/Object");
		}

		Target(String name, String superName) {
			this.name = name;
			cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, superName, null);
		}

		Target method(String method, String desc, String... anchors) {
			return open(method, desc, false, Opcodes.ACC_PRIVATE, anchors);
		}

		/** As {@link #method}, but public. */
		Target publicMethod(String method, String desc, String... anchors) {
			return open(method, desc, false, Opcodes.ACC_PUBLIC, anchors);
		}

		Target staticMethod(String method, String desc, String... anchors) {
			return open(method, desc, true, Opcodes.ACC_PRIVATE, anchors);
		}

		private Target open(String method, String desc, boolean isStatic, int access, String... anchors) {
			close();
			descs.put(method, desc);
			open = cw.visitMethod(access | (isStatic ? Opcodes.ACC_STATIC : 0), method, desc, null, null);
			openDesc = desc;
			openStatic = isStatic;
			open.visitCode();
			for (String anchor : anchors) call(MixinFit.parseMember(anchor));
			return this;
		}

		/** The anchor, made as a call with default arguments and its result dropped: only its presence is read. */
		private void call(MixinFit.Member call) {
			boolean init = call.name().equals("<init>");
			if (init) {
				open.visitTypeInsn(Opcodes.NEW, call.owner());
				open.visitInsn(Opcodes.DUP);
			}
			for (Type arg : Type.getArgumentTypes(call.desc())) {
				switch (arg.getSort()) {
					case Type.OBJECT, Type.ARRAY -> open.visitInsn(Opcodes.ACONST_NULL);
					case Type.LONG -> open.visitInsn(Opcodes.LCONST_0);
					case Type.FLOAT -> open.visitInsn(Opcodes.FCONST_0);
					case Type.DOUBLE -> open.visitInsn(Opcodes.DCONST_0);
					default -> open.visitInsn(Opcodes.ICONST_0);
				}
			}
			open.visitMethodInsn(init ? Opcodes.INVOKESPECIAL : Opcodes.INVOKESTATIC, call.owner(), call.name(), call.desc(), false);
			int size = init ? 1 : Type.getReturnType(call.desc()).getSize();
			if (size > 0) open.visitInsn(size == 2 ? Opcodes.POP2 : Opcodes.POP);
		}

		/** The open method stores null into its local {@code slot} (a parameter's, to change it before handing it on). */
		Target storing(int slot) {
			open.visitInsn(Opcodes.ACONST_NULL);
			open.visitVarInsn(Opcodes.ASTORE, slot);
			return this;
		}

		/** The open method calls {@code sibling} (declared before or after) with its own arguments. */
		Target calling(String sibling) {
			String desc = descs.getOrDefault(sibling, openDesc);
			open.visitVarInsn(Opcodes.ALOAD, 0);
			int slot = openStatic ? 0 : 1;
			for (Type arg : Type.getArgumentTypes(desc)) {
				open.visitVarInsn(arg.getOpcode(Opcodes.ILOAD), slot);
				slot += arg.getSize();
			}
			open.visitMethodInsn(Opcodes.INVOKEVIRTUAL, name, sibling, desc, false);
			Type returns = Type.getReturnType(desc);
			if (returns.getSize() > 0) open.visitInsn(returns.getSize() == 2 ? Opcodes.POP2 : Opcodes.POP);
			return this;
		}

		/** The open method calls {@code sibling} with {@code this} and a default value for every argument. */
		Target callingWithOthers(String sibling) {
			String desc = descs.getOrDefault(sibling, openDesc);
			open.visitVarInsn(Opcodes.ALOAD, 0);
			for (Type arg : Type.getArgumentTypes(desc)) open.visitInsn(arg.getSort() >= Type.ARRAY ? Opcodes.ACONST_NULL : Opcodes.ICONST_0);
			open.visitMethodInsn(Opcodes.INVOKEVIRTUAL, name, sibling, desc, false);
			Type returns = Type.getReturnType(desc);
			if (returns.getSize() > 0) open.visitInsn(returns.getSize() == 2 ? Opcodes.POP2 : Opcodes.POP);
			return this;
		}

		/** The open method creates a supplier whose body is {@code sibling}, capturing {@code this} and its arguments, as NeoForge's startSleepInBed does. */
		Target capturing(String sibling) {
			Handle metafactory = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
					"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
							+ "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
							+ "Ljava/lang/invoke/CallSite;", false);
			open.visitVarInsn(Opcodes.ALOAD, 0);
			int slot = 1;
			StringBuilder captured = new StringBuilder("(L" + name + ";");
			for (Type arg : Type.getArgumentTypes(openDesc)) {
				open.visitVarInsn(arg.getOpcode(Opcodes.ILOAD), slot);
				slot += arg.getSize();
				captured.append(arg.getDescriptor());
			}
			Type returns = Type.getReturnType(openDesc);
			open.visitInvokeDynamicInsn("get", captured + ")Ljava/util/function/Supplier;", metafactory,
					Type.getType("()Ljava/lang/Object;"), new Handle(Opcodes.H_INVOKEVIRTUAL, name, sibling, openDesc, false),
					Type.getMethodType(returns));
			open.visitInsn(Opcodes.POP);
			return this;
		}

		/** The open method creates a lambda whose body is {@code sibling}, as NeoForge's startSleepInBed does. */
		Target handing(String sibling) {
			Handle metafactory = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
					"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
							+ "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
							+ "Ljava/lang/invoke/CallSite;", false);
			open.visitVarInsn(Opcodes.ALOAD, 0);
			open.visitInvokeDynamicInsn("apply", "(L" + name + ";)Ljava/util/function/Function;", metafactory,
					Type.getType("(Ljava/lang/Object;)Ljava/lang/Object;"),
					new Handle(Opcodes.H_INVOKEVIRTUAL, name, sibling, openDesc, false), Type.getType(openDesc));
			open.visitInsn(Opcodes.POP);
			return this;
		}

		private void close() {
			if (open == null) return;
			Type returns = Type.getReturnType(openDesc);
			if (returns.getSort() == Type.VOID) {
				open.visitInsn(Opcodes.RETURN);
			} else if (returns.getSort() == Type.OBJECT || returns.getSort() == Type.ARRAY) {
				open.visitInsn(Opcodes.ACONST_NULL);
				open.visitInsn(Opcodes.ARETURN);
			} else {
				open.visitInsn(Opcodes.ICONST_0);
				open.visitInsn(Opcodes.IRETURN);
			}
			open.visitMaxs(0, 0);
			open.visitEnd();
			open = null;
		}

		byte[] bytes() {
			close();
			cw.visitEnd();
			return cw.toByteArray();
		}
	}

	/** One {@code @ModifyArg} at INVOKE {@code anchor}, selecting {@code selector} as the real mixin does. */
	private static byte[] mixin(String target, String selector, String anchor) {
		return mixin(target, selector, anchor, -1);
	}

	/** As above, the anchor at {@code ordinal} (-1: none written). */
	private static byte[] mixin(String target, String selector, String anchor, int ordinal) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, MIXIN, null, "java/lang/Object", null);
		AnnotationVisitor at = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
		AnnotationVisitor targets = at.visitArray("value");
		targets.visit(null, Type.getObjectType(target));
		targets.visitEnd();
		at.visitEnd();

		MethodVisitor h = cw.visitMethod(Opcodes.ACC_PRIVATE, "handler", "()V", null, null);
		AnnotationVisitor inj = h.visitAnnotation(MODIFY_ARG, true);
		AnnotationVisitor method = inj.visitArray("method");
		method.visit(null, selector);
		method.visitEnd();
		AnnotationVisitor atNode = inj.visitAnnotation("at", "Lorg/spongepowered/asm/mixin/injection/At;");
		atNode.visit("value", "INVOKE");
		atNode.visit("target", anchor);
		if (ordinal >= 0) atNode.visit("ordinal", ordinal);
		atNode.visitEnd();
		inj.visitEnd();
		h.visitCode();
		h.visitInsn(Opcodes.RETURN);
		h.visitMaxs(0, 8);
		h.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/**
	 * One {@code @Inject} at INVOKE {@code anchor} on a handler of {@code handlerDesc}; {@code sugarAt}/{@code sugar}
	 * annotate one parameter ({@code @Local} with {@code argsOnly = true}; a trailing {@code !} drops argsOnly).
	 */
	private static byte[] inject(String target, String selector, String anchor, String handlerDesc, boolean cancellable,
			Object... sugar) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, MIXIN, null, "java/lang/Object", null);
		AnnotationVisitor at = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
		AnnotationVisitor targets = at.visitArray("value");
		targets.visit(null, Type.getObjectType(target));
		targets.visitEnd();
		at.visitEnd();

		MethodVisitor h = cw.visitMethod(Opcodes.ACC_PRIVATE, "handler", handlerDesc, null, null);
		AnnotationVisitor inj = h.visitAnnotation(INJECT, true);
		AnnotationVisitor method = inj.visitArray("method");
		method.visit(null, selector);
		method.visitEnd();
		AnnotationVisitor atNode = inj.visitAnnotation("at", "Lorg/spongepowered/asm/mixin/injection/At;");
		atNode.visit("value", "INVOKE");
		atNode.visit("target", anchor);
		atNode.visitEnd();
		if (cancellable) inj.visit("cancellable", true);
		inj.visitEnd();
		if (sugar.length == 2) {
			String desc = (String) sugar[1];
			boolean argsOnly = !desc.endsWith("!");
			AnnotationVisitor p = h.visitParameterAnnotation((Integer) sugar[0], argsOnly ? desc : desc.substring(0, desc.length() - 1), false);
			if (LOCAL.equals(desc) && argsOnly) p.visit("argsOnly", true);
			if (SHARE.equals(desc)) p.visit("value", "index");
			p.visitEnd();
		}
		h.visitCode();
		h.visitInsn(Opcodes.RETURN);
		h.visitMaxs(0, 8);
		h.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static Function<String, byte[]> resolver(String name, byte[] bytes) {
		return Map.of(name + ".class", bytes)::get;
	}
}
