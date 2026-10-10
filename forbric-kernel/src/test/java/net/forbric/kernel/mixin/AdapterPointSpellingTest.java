/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.transform.ForbricMergedBaseCompatTransformer;
import net.forbric.kernel.transform.ItemUseOnInjector;

/**
 * The adapters that used to compare an {@code @At} target or a {@code method} selector with one spelling — block break,
 * entity anchors, replaced-call redirects, the shears relay, the relocated {@code useOn} call, the client-hook anchors, the
 * registry loader's companions and the registry bootstrap callback — given the released mixins they were built from, under
 * other class and handler names wherever the adapter does not key on a platform module's own mixin, with every point and
 * selector written another way Mixin reads as the same member: whitespace, a dotted owner, an owner-prefixed selector, a
 * selector array of two spellings, and — where the method the handler was written for, in the class the mod was compiled
 * against, decides it — no owner or no descriptor. Each must adapt exactly as many handlers as for the released spelling,
 * to the same result. And a point that names another member — another owner, or a shorter target that selects another
 * owner's or another overload's instructions in that method too, or one read without that method at hand — or a selector
 * binding another method, is left exactly as written.
 */
@ResourceLock("system-properties")
class AdapterPointSpellingTest {
	interface Source { ClassNode read() throws Exception; }
	interface Adapter { int apply(ClassNode mixin, Function<String, ClassNode> targets, BiFunction<Ecosystem, String, ClassNode> natives); }
	interface Check { void accept(ClassNode adapted) throws Exception; }

	/**
	 * One released mixin and its adapter. {@code rename}: run it under unrelated class and handler names (false only where the
	 * adapter keys on the platform module's own mixin); {@code points}: the members the adapter's points name, as released.
	 */
	record Case(String id, Source source, boolean rename, Ecosystem family, String owner, Adapter adapter, int count,
			List<String> points, Check check) { }

	private static final String GAME_MODE = "net/minecraft/server/level/ServerPlayerGameMode";
	private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
	private static final String PLAYER = "net/minecraft/server/level/ServerPlayer";
	private static final String STACK = "net/minecraft/world/item/ItemStack";
	private static final String CLIENT = "net/minecraft/client/Minecraft";
	private static final String LOADER = "net/minecraft/resources/RegistryDataLoader";
	private static final String BOOTSTRAP = "net/minecraft/server/Bootstrap";
	private static final String STATE = "Lnet/minecraft/world/level/block/state/BlockState;";
	private static final String ENTITY = "Lnet/minecraft/world/level/block/entity/BlockEntity;";
	private static final String CIR = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";
	private static final String FACTORY = "L" + LOADER + "$LoaderFactory;";
	private static final String ARGS = "Ljava/util/List;Ljava/util/List;Ljava/util/concurrent/Executor;";
	private static final String FUTURE = "Ljava/util/concurrent/CompletableFuture;";
	private static final Path POPULAR = Path.of("run/client-popular/mods");
	private static final Path VIA = Path.of("../build/random100-20260929/downloads/dmLiENU0/ViaFabricPlus-5.0.2.jar");
	private static final Path BCLIB = Path.of("build/compat-inputs/sweep90/mods/bclib-26.201.2.jar");
	private static final Path FANCYMENU = Path.of("build/sweep80-mac/v020-rounds/r1/mods/fancymenu_neoforge_3.9.12_MC_26.2.jar");
	private static final Path REGISTRY_SYNC = Path.of("build/compat-inputs/player-loading/api/fabric-registry-sync-v0-7.1.1+c7bd5b8e9e.jar");

	@AfterEach void reset() {
		MixinStubRebind.forget();
	}

	static List<Case> cases() {
		List<Case> cases = new ArrayList<>();
		// FabricBlockBreakMixinAdapter: any mod's break callback and harvest modifier; fabric-api's AFTER row.
		cases.add(new Case("break locals", () -> jar(Fixture.THIRD_PARTY, POPULAR.resolve("architectury-fabric-21.1.10.jar"),
				"dev/architectury/mixin/fabric/MixinServerPlayerGameMode"), true, Ecosystem.FABRIC, GAME_MODE, FabricBlockBreakMixinAdapter::adapt, 1,
				List.of(STATE + "getBlock()Lnet/minecraft/world/level/block/Block;"),
				mixin -> assertTrue(mixin.methods.stream().anyMatch(m -> MixinFit.injectorOf(m) != null && m.desc.equals("(Lnet/minecraft/core/BlockPos;"
						+ CIR + STATE + "Lnet/neoforged/neoforge/event/level/block/BreakBlockEvent;" + ENTITY + ")V")), "the merged frame is captured")));
		cases.add(new Case("harvest ordinal", AdapterPointSpellingTest::apoli, true, Ecosystem.FABRIC, GAME_MODE, FabricBlockBreakMixinAdapter::adapt, 1,
				List.of("Lnet/minecraft/world/item/ItemStack;mineBlock(Lnet/minecraft/world/level/Level;" + STATE
						+ "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/player/Player;)V"),
				mixin -> assertTrue(mixin.methods.stream().anyMatch(m -> MixinFit.injectorOf(m) != null && m.desc.equals("(Z)Z")
						&& Integer.valueOf(0).equals(MixinFit.value(MixinFit.injectorOf(m), "ordinal"))), "ordinal 0")));
		cases.add(new Case("fabric-api AFTER break", () -> StagedFabricMixinFixture.mixin("fabric-events-interaction-v0", FabricBlockBreakMixinAdapter.FABRIC),
				false, Ecosystem.FABRIC, GAME_MODE, FabricBlockBreakMixinAdapter::adapt, 1, List.of(FabricBlockBreakMixinAdapter.BLOCK_DESTROY),
				mixin -> assertTrue(atTargets(mixin).contains("L" + GAME_MODE + ";removeBlock" + FabricBlockBreakMixinAdapter.REMOVE_BLOCK_DESC))));
		// FabricEntityMixinAnchors: any mod's per-effect clear veto; fabric-api's entity event rows.
		cases.add(new Case("clear veto", AdapterPointSpellingTest::clearVeto, false, Ecosystem.FABRIC, LIVING, FabricEntityMixinAnchors::adapt, 1,
				List.of("Ljava/util/Map;clear()V"), mixin -> assertTrue(mixin.methods.stream().anyMatch(m -> m.name.startsWith("forbric$clearVeto$")
						&& MixinFit.injectorOf(m) != null), "re-hosted on NeoForge's per-effect question")));
		String root = "net/fabricmc/fabric/mixin/entity/event/";
		cases.add(new Case("fabric-api effects", () -> StagedFabricMixinFixture.mixin("fabric-entity-events-v1", root + "effect/LivingEntityMixin"), false,
				Ecosystem.FABRIC, LIVING, FabricEntityMixinAnchors::adapt, 3,
				List.of("L" + LIVING + ";canBeAffected(Lnet/minecraft/world/effect/MobEffectInstance;)Z",
						"Lcom/google/common/collect/Maps;newHashMap(Ljava/util/Map;)Ljava/util/HashMap;", "Ljava/util/Map;clear()V"),
				mixin -> assertTrue(atTargets(mixin).containsAll(List.of("java/util/HashMap",
						"Lnet/neoforged/neoforge/event/EventHooks;onEffectRemoved(L" + LIVING + ";Lnet/minecraft/world/effect/MobEffectInstance;)Z")))));
		cases.add(new Case("fabric-api elytra", () -> StagedFabricMixinFixture.mixin("fabric-entity-events-v1", root + "elytra/LivingEntityMixin"), false,
				Ecosystem.FABRIC, LIVING, FabricEntityMixinAnchors::adapt, 2,
				List.of("Lnet/minecraft/world/entity/EquipmentSlot;VALUES:Ljava/util/List;",
						"Lnet/minecraft/util/Util;getRandom(Ljava/util/List;Lnet/minecraft/util/RandomSource;)Ljava/lang/Object;"),
				mixin -> assertTrue(atTargets(mixin).containsAll(List.of("Lnet/neoforged/neoforge/common/NeoForgeMod;GLIDING_FLIGHT:Lnet/minecraft/core/Holder;",
						"Ljava/util/List;isEmpty()Z")))));
		cases.add(new Case("fabric-api beds", () -> StagedFabricMixinFixture.mixin("fabric-entity-events-v1", root + "LivingEntityMixin"), false,
				Ecosystem.FABRIC, LIVING, FabricEntityMixinAnchors::adapt, 2,
				List.of("Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;" + STATE + "I)Z",
						"Lnet/minecraft/world/level/block/BedBlock;getBedOrientation(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/Direction;"),
				mixin -> assertTrue(mixin.methods.stream().map(m -> m.name).toList().containsAll(List.of("forbric$setBedOccupied", "forbric$modifySleepingDirection")))));
		cases.add(new Case("fabric-api sleep", () -> StagedFabricMixinFixture.mixin("fabric-entity-events-v1", root + "ServerPlayerMixin"), false,
				Ecosystem.FABRIC, PLAYER, FabricEntityMixinAnchors::adapt, 1, List.of("Ljava/util/List;isEmpty()Z"),
				mixin -> assertTrue(selectors(mixin).stream().anyMatch(s -> s.startsWith("lambda$startSleepInBed$")))));
		// ReplacedCallRedirects: ViaFabricPlus' three, forwarding the carrier's call.
		String via = "com/viaversion/viafabricplus/injection/mixin/features/";
		cases.add(via("hotbar keys", via + "v1_4_2/MixinAbstractContainerScreen", "net/minecraft/client/gui/screens/inventory/AbstractContainerScreen",
				"Lnet/minecraft/client/KeyMapping;matches(Lnet/minecraft/client/input/KeyEvent;)Z",
				"Lnet/minecraft/client/KeyMapping;isActiveAndMatches(Lcom/mojang/blaze3d/platform/InputConstants$Key;)Z"));
		cases.add(via("item use", via + "v1_14_3/MixinLivingEntity", LIVING, "L" + STACK + ";isSameItem(L" + STACK + ";L" + STACK + ";)Z",
				"Lnet/neoforged/neoforge/common/CommonHooks;canContinueUsing(L" + STACK + ";L" + STACK + ";)Z"));
		cases.add(via("shovel path", via + "v1_8/item/MixinShovelItem", "net/minecraft/world/item/ShovelItem", "Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;",
				STATE + "getToolModifiedState(Lnet/minecraft/world/item/context/UseOnContext;Lnet/neoforged/neoforge/common/ItemAbility;Z)" + STATE));
		// MixinShearsRelay: BCLib's six wraps of is(Items.SHEARS).
		for (MixinShearsRelay.Row row : MixinShearsRelay.ROWS) {
			String name = row.target().substring(row.target().lastIndexOf('/') + 1);
			cases.add(new Case("shears " + name, () -> jar(Fixture.THIRD_PARTY, BCLIB, "org/betterx/bclib/mixin/common/shears/" + name + "Mixin"), true,
					Ecosystem.FABRIC, row.target(), MixinShearsRelay::adapt, 1, List.of(MixinShearsRelay.VANILLA_TARGET),
					mixin -> assertTrue(atTargets(mixin).contains(MixinShearsRelay.carrierTarget(row)), "the carrier's question is answered")));
		}
		// MixinRelocatedCall: fabric-api's ItemEvents.USE_ON wrap, under another mod's names.
		cases.add(new Case("relocated useOn", () -> StagedFabricMixinFixture.mixin("fabric-events-interaction-v0", "net/fabricmc/fabric/mixin/event/interaction/ItemStackMixin"),
				true, Ecosystem.FABRIC, STACK, MixinRelocatedCall::adapt, 1,
				List.of("Lnet/minecraft/world/item/Item;useOn(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;"),
				mixin -> assertTrue(selectors(mixin).contains(ItemUseOnInjector.RELAY + ItemUseOnInjector.RELAY_DESC))));
		// KernelClientHookMixinAnchors: FancyMenu's NeoForge client initializer hook.
		cases.add(new Case("client hooks", () -> jar(Fixture.THIRD_PARTY, FANCYMENU, "de/keksuccino/fancymenu/mixin/mixins/neoforge/client/MixinMinecraft"), true,
				Ecosystem.NEOFORGE, CLIENT, KernelClientHookMixinAnchors::adapt, 1,
				List.of("Lnet/neoforged/neoforge/client/ClientHooks;initClientHooks(L" + CLIENT + ";Lnet/minecraft/server/packs/resources/ReloadableResourceManager;)V"),
				mixin -> assertTrue(atTargets(mixin).stream().anyMatch(t -> t.startsWith("Lnet/forbric/kernel/runtime/KernelForgeClientInit;")))));
		// FabricRegistryLoaderMixinAdapter: fabric-registry-sync's wrap and its companion on the dead loader.
		cases.add(new Case("registry loader", () -> jar(Fixture.THIRD_PARTY, REGISTRY_SYNC, "net/fabricmc/fabric/mixin/registry/sync/RegistryDataLoaderMixin"), true,
				Ecosystem.FABRIC, LOADER, FabricRegistryLoaderMixinAdapter::adapt, 2,
				List.of("L" + LOADER + ";load(" + FACTORY + ARGS + ")" + FUTURE, "Ljava/util/concurrent/CompletableFuture;supplyAsync(Ljava/util/function/Supplier;Ljava/util/concurrent/Executor;)" + FUTURE),
				mixin -> {
					assertTrue(atTargets(mixin).contains("L" + LOADER + ";load(" + FACTORY + ARGS + "Z)" + FUTURE), "the wrap follows the widened loader");
					assertTrue(selectors(mixin).contains("load(" + FACTORY + ARGS + "Z)" + FUTURE), "the companion moves into it");
				}));
		// FabricRegistryInitializationMixinAdapter: fabric-registry-sync's bootstrap tracker callback.
		cases.add(new Case("registry bootstrap", () -> jar(Fixture.THIRD_PARTY, REGISTRY_SYNC, "net/fabricmc/fabric/mixin/registry/sync/BootstrapMixin"), true,
				Ecosystem.FABRIC, BOOTSTRAP, FabricRegistryInitializationMixinAdapter::adapt, 1,
				List.of("L" + BOOTSTRAP + ";wrapStreams()V", "Lnet/minecraft/core/registries/BuiltInRegistries;bootStrap()V"),
				mixin -> {
					assertTrue(mixin.methods.stream().anyMatch(m -> MixinFit.injectorOf(m) != null && MixinFit.atNodes(MixinFit.injectorOf(m)).stream()
							.anyMatch(at -> "TAIL".equals(MixinFit.value(at, "value")))), "the tracker callback runs at the end of bootstrap");
					assertTrue(mixin.methods.stream().noneMatch(m -> MixinFit.injectorOf(m) != null && m.desc.equals("()V")), "the deferred freeze is gone");
				}));
		return cases;
	}

	private static Case via(String id, String entry, String owner, String vanilla, String merged) {
		return new Case("redirect " + id, () -> jar(Fixture.THIRD_PARTY, VIA, entry), true, Ecosystem.FABRIC, owner, ReplacedCallRedirects::adapt, 1,
				List.of(vanilla), mixin -> assertTrue(atTargets(mixin).contains(merged), "forwards " + merged));
	}

	// ---- positives ------------------------------------------------------------------------------------------------

	@TestFactory Stream<DynamicTest> pointsWrittenAnotherWayAreAdaptedTheSame() {
		return cases().stream().flatMap(c -> PointRespelling.allForms().stream().map(form -> DynamicTest.dynamicTest(c.id() + " / " + form.id(), () -> {
			ClassNode mixin = read(c), natives = NATIVES.apply(c.family(), c.owner());
			int respelled = PointRespelling.points(mixin, key(c, mixin)::contains, form, m -> PointRespelling.bound(m, natives));
			assertTrue(respelled > 0 && respelledWherever(mixin, c, form, natives), "premise: every point the adapter reads is respelled where the form applies");
			assertAdapted(c, mixin);
		})));
	}

	/**
	 * Whether every point still spelled as released is one {@code form} cannot write another way: it drops the owner or the
	 * descriptor, and the method the handler was written for also accesses another member a shorter target would select.
	 */
	private static boolean respelledWherever(ClassNode mixin, Case c, PointRespelling.Form form, ClassNode natives) {
		for (MethodNode handler : mixin.methods) {
			AnnotationNode injector = MixinFit.injectorOf(handler);
			if (injector == null) continue;
			for (AnnotationNode at : MixinFit.atNodes(injector)) {
				String target = MixinFit.asString(MixinFit.value(at, "target"));
				if (target == null || !c.points().contains(target)) continue;
				if (!form.dropsOwner() && !form.dropsDesc()) return false;
				MixinFit.Member member = MixinFit.parseMember(target);
				List<MethodNode> bodies = PointRespelling.bound(handler, natives);
				if (!bodies.isEmpty() && bodies.stream().allMatch(b -> PointRespelling.onlyThatMember(b, member, form.dropsOwner(), form.dropsDesc()))) return false;
			}
		}
		return true;
	}

	@TestFactory Stream<DynamicTest> selectorsWrittenAnotherWayAreAdaptedTheSame() {
		return cases().stream().flatMap(c -> PointRespelling.SELECTORS.entrySet().stream().map(form -> DynamicTest.dynamicTest(c.id() + " / " + form.getKey(), () -> {
			ClassNode mixin = read(c);
			assertTrue(PointRespelling.selectors(mixin, key(c, mixin)::contains, form.getValue()) > 0, "premise: the selectors are respelled");
			assertAdapted(c, mixin);
		})));
	}

	/**
	 * A bare selector given its descriptor, or a described one reduced to its bare name, wherever that binds the same method
	 * — the first declared under that name is the member, in the merged class and in the one the mod was compiled against.
	 * Mixins where no selector has such a twin are not listed.
	 */
	@TestFactory Stream<DynamicTest> selectorsWithAndWithoutTheirDescriptorAreAdaptedTheSame() {
		return cases().stream().filter(c -> {
			try { return swapDescriptors(c, read(c)) > 0; } catch (Exception unreadable) { return true; }
		}).map(c -> DynamicTest.dynamicTest(c.id(), () -> {
			ClassNode mixin = read(c);
			assertTrue(swapDescriptors(c, mixin) > 0, "premise");
			assertAdapted(c, mixin);
		}));
	}

	private static int swapDescriptors(Case c, ClassNode mixin) {
		ClassNode merged = merged(c.owner()), natives = NATIVES.apply(c.family(), c.owner());
		int changed = 0;
		for (MethodNode handler : key(c, mixin)) {
			AnnotationNode injector = MixinFit.injectorOf(handler);
			List<String> out = new ArrayList<>();
			for (String selector : MixinFit.stringList(MixinFit.value(injector, "method"))) {
				int paren = selector.indexOf('(');
				String name = paren < 0 ? selector : selector.substring(0, paren);
				MethodNode first = firstNamed(merged, name), original = firstNamed(natives, name);
				boolean plain = !name.isEmpty() && name.chars().noneMatch(ch -> ch == ';' || ch == '.' || ch == '*' || ch == '/' || Character.isWhitespace(ch));
				if (!plain || first == null || original == null || !first.desc.equals(original.desc)) { out.add(selector); continue; }
				if (paren < 0) { out.add(name + first.desc); changed++; }
				// Reduced to a bare name only where no other method carries it: a bare name shared by overloads binds the
				// first declared, an order the merge decides, and an adapter may rightly refuse that (the registry loader does).
				else if (selector.substring(paren).equals(first.desc) && named(merged, name) == 1 && named(natives, name) == 1) { out.add(name); changed++; }
				else out.add(selector);
			}
			MixinPlayerWorldCallbackAdapter.set(injector, "method", out);
		}
		return changed;
	}

	private static long named(ClassNode node, String name) {
		return node.methods.stream().filter(m -> m.name.equals(name)).count();
	}

	private static MethodNode firstNamed(ClassNode node, String name) {
		if (node == null) return null;
		for (MethodNode m : node.methods) if (m.name.equals(name)) return m;
		return null;
	}

	/**
	 * Without the merged class (a caller that has none), the bootstrap callback's selector is read as Mixin parses it:
	 * written with its descriptor, its owner or both, it is the same selector; one naming another overload, another owner
	 * or more than one method is not.
	 */
	@TestFactory Stream<DynamicTest> theBootstrapCallbackWithoutTheClassReadsItsSelectorAsParsed() {
		Map<List<String>, Integer> selectors = new java.util.LinkedHashMap<>();
		selectors.put(List.of("bootStrap()V"), 1);
		selectors.put(List.of("Lnet/minecraft/server/Bootstrap;bootStrap"), 1);
		selectors.put(List.of(" net.minecraft.server.Bootstrap . bootStrap ()V"), 1);
		selectors.put(List.of("bootStrap", "Lnet/minecraft/server/Bootstrap;bootStrap()V"), 1);
		selectors.put(List.of("bootStrap(Z)V"), 0);
		selectors.put(List.of("Lnet/minecraft/server/Main;bootStrap()V"), 0);
		selectors.put(List.of("bootStrap*"), 0);
		selectors.put(List.of("bootStrap", "wrapStreams"), 0);
		Case c = cases().stream().filter(k -> k.id().equals("registry bootstrap")).findFirst().orElseThrow();
		return selectors.entrySet().stream().map(e -> DynamicTest.dynamicTest(String.join(" + ", e.getKey()), () -> {
			ClassNode mixin = read(c);
			for (MethodNode handler : key(c, mixin)) if (atTargets(handler).contains("L" + BOOTSTRAP + ";wrapStreams()V"))
				MixinPlayerWorldCallbackAdapter.set(MixinFit.injectorOf(handler), "method", new ArrayList<>(e.getKey()));
			byte[] before = CarpetMixinAdapterTest.bytes(mixin);
			assertEquals(e.getValue(), FabricRegistryInitializationMixinAdapter.adapt(mixin));
			if (e.getValue() == 0) assertArrayEquals(before, CarpetMixinAdapterTest.bytes(mixin));
		}));
	}

	private static List<String> atTargets(MethodNode handler) {
		List<String> out = new ArrayList<>();
		AnnotationNode injector = MixinFit.injectorOf(handler);
		if (injector != null) for (AnnotationNode at : MixinFit.atNodes(injector)) out.add(MixinFit.asString(MixinFit.value(at, "target")));
		return out;
	}

	/** Points and selectors both written another way at once, the shorter targets read in the method each was written for. */
	@TestFactory Stream<DynamicTest> everythingWrittenAnotherWayAtOnceIsAdaptedTheSame() {
		return cases().stream().map(c -> DynamicTest.dynamicTest(c.id(), () -> {
			ClassNode mixin = read(c), natives = NATIVES.apply(c.family(), c.owner());
			Set<MethodNode> key = key(c, mixin);
			for (PointRespelling.Form form : List.of(PointRespelling.READ_IN_THE_WRITTEN_METHOD.getFirst(), PointRespelling.READ_IN_THE_WRITTEN_METHOD.getLast(),
					PointRespelling.SAME_MEMBER.getLast()))
				PointRespelling.points(mixin, key::contains, form, m -> PointRespelling.bound(m, natives));
			assertTrue(noneLeft(mixin, c), "premise: every point the adapter reads is respelled");
			assertTrue(PointRespelling.selectors(mixin, key::contains, PointRespelling.SELECTORS.get("dotted-owner selector")) > 0, "premise");
			assertAdapted(c, mixin);
		}));
	}

	private static void assertAdapted(Case c, ClassNode mixin) throws Exception {
		assertEquals(c.count(), c.adapter().apply(mixin, AdapterPointSpellingTest::merged, (family, owner) -> NATIVES.apply(c.family(), owner)), c.id());
		c.check().accept(mixin);
		CarpetMixinAdapterTest.verify(mixin);
		assertEquals(0, c.adapter().apply(mixin, AdapterPointSpellingTest::merged, (family, owner) -> NATIVES.apply(c.family(), owner)), "idempotence");
	}

	// ---- negatives ------------------------------------------------------------------------------------------------

	@TestFactory Stream<DynamicTest> pointsNamingAnotherMemberAreLeftAsWritten() {
		return cases().stream().flatMap(c -> Stream.of(
				DynamicTest.dynamicTest(c.id() + " / another owner", () -> {
					ClassNode mixin = read(c);
					PointRespelling.Form elsewhere = new PointRespelling.Form("another owner",
							m -> "Lorg/example/elsewhere/Unrelated;" + m.name() + (m.desc().startsWith("(") ? "" : ":") + m.desc(), false, false);
					assertTrue(PointRespelling.points(mixin, key(c, mixin)::contains, elsewhere, null) > 0 && noneLeft(mixin, c), "premise");
					assertLeftAlone(c, mixin, (family, owner) -> NATIVES.apply(c.family(), owner));
				}),
				DynamicTest.dynamicTest(c.id() + " / no owner, another owner's member there too", () -> crowded(c, PointRespelling.READ_IN_THE_WRITTEN_METHOD.getFirst(), true)),
				DynamicTest.dynamicTest(c.id() + " / no descriptor, another overload there too", () -> crowded(c, PointRespelling.READ_IN_THE_WRITTEN_METHOD.get(1), false)),
				DynamicTest.dynamicTest(c.id() + " / no owner, without the class it was compiled against", () -> {
					ClassNode mixin = read(c), natives = NATIVES.apply(c.family(), c.owner());
					PointRespelling.Form form = PointRespelling.READ_IN_THE_WRITTEN_METHOD.getFirst();
					assertTrue(PointRespelling.points(mixin, key(c, mixin)::contains, form, m -> PointRespelling.bound(m, natives)) > 0
							&& respelledWherever(mixin, c, form, natives), "premise");
					assertLeftAlone(c, mixin, (family, owner) -> null);
				}),
				DynamicTest.dynamicTest(c.id() + " / a selector binding another method", () -> {
					ClassNode mixin = read(c), merged = merged(c.owner());
					int changed = 0;
					for (MethodNode handler : key(c, mixin)) {
						MethodNode bound = MixinTargetSelectors.one(handler, merged), other = null;
						for (MethodNode m : merged.methods)
							if (!m.name.startsWith("<") && (bound == null || !m.name.equals(bound.name))
									&& ((m.access ^ (bound == null ? m.access : bound.access)) & Opcodes.ACC_STATIC) == 0) { other = m; break; }
						assertNotNull(other);
						MixinPlayerWorldCallbackAdapter.set(MixinFit.injectorOf(handler), "method", new ArrayList<>(List.of(other.name + other.desc)));
						changed++;
					}
					assertTrue(changed > 0, "premise");
					assertLeftAlone(c, mixin, (family, owner) -> NATIVES.apply(c.family(), owner));
				})));
	}

	/** The shorter target, against a written method that also accesses another owner's member of that name (or another overload). */
	private static void crowded(Case c, PointRespelling.Form form, boolean otherOwner) throws Exception {
		ClassNode mixin = read(c), natives = NATIVES.apply(c.family(), c.owner()), crowd = PointRespelling.copy(natives);
		Set<MethodNode> key = key(c, mixin);
		assertTrue(PointRespelling.crowd(mixin, key::contains, crowd, otherOwner) > 0, "premise: the written methods are crowded");
		assertTrue(PointRespelling.points(mixin, key::contains, form, m -> PointRespelling.bound(m, natives)) > 0
				&& respelledWherever(mixin, c, form, natives), "premise: every point the adapter reads is respelled where the form applies");
		assertLeftAlone(c, mixin, (family, owner) -> owner.equals(c.owner()) ? crowd : NATIVES.apply(c.family(), owner));
	}

	private static void assertLeftAlone(Case c, ClassNode mixin, BiFunction<Ecosystem, String, ClassNode> natives) {
		byte[] before = CarpetMixinAdapterTest.bytes(mixin);
		assertEquals(0, c.adapter().apply(mixin, AdapterPointSpellingTest::merged, natives), c.id());
		assertArrayEquals(before, CarpetMixinAdapterTest.bytes(mixin), c.id() + ": the mixin was rewritten");
	}

	// ---- reading ----------------------------------------------------------------------------------------------------

	/** The handlers the adapter reads: those with a point naming one of the case's members as released (read before any rewrite). */
	private static Set<MethodNode> key(Case c, ClassNode mixin) {
		Set<MethodNode> key = Collections.newSetFromMap(new IdentityHashMap<>());
		for (MethodNode handler : mixin.methods) {
			AnnotationNode injector = MixinFit.injectorOf(handler);
			if (injector != null) for (AnnotationNode at : MixinFit.atNodes(injector)) {
				String target = MixinFit.asString(MixinFit.value(at, "target"));
				if (target != null && c.points().contains(target)) key.add(handler);
			}
		}
		return key;
	}

	/** No point of the mixin is still spelled as released for one of the case's members. */
	private static boolean noneLeft(ClassNode mixin, Case c) {
		return atTargets(mixin).stream().noneMatch(c.points()::contains);
	}

	private static List<String> atTargets(ClassNode mixin) {
		List<String> out = new ArrayList<>();
		for (MethodNode m : mixin.methods) {
			AnnotationNode injector = MixinFit.injectorOf(m);
			if (injector != null) for (AnnotationNode at : MixinFit.atNodes(injector)) {
				String target = MixinFit.asString(MixinFit.value(at, "target"));
				if (target != null) out.add(target);
			}
		}
		return out;
	}

	private static List<String> selectors(ClassNode mixin) {
		List<String> out = new ArrayList<>();
		for (MethodNode m : mixin.methods) {
			AnnotationNode injector = MixinFit.injectorOf(m);
			if (injector != null) out.addAll(MixinFit.stringList(MixinFit.value(injector, "method")));
		}
		return out;
	}

	private static ClassNode read(Case c) throws Exception {
		ClassNode mixin = c.source().read();
		if (c.rename()) mixin = MixinCallbackSelectorSpellingTest.unrelatedNames(mixin);
		MixinStubRebind.noteEcosystem(mixin.name, c.family());
		return mixin;
	}

	private static final BiFunction<Ecosystem, String, ClassNode> STAGED = NativeCallTestEvidence.staged();
	/** The class the mod was compiled against: staged native references, read once. */
	private static final BiFunction<Ecosystem, String, ClassNode> NATIVES = STAGED::apply;

	private static final Map<String, byte[]> MERGED = new HashMap<>();

	/** The merged class with code and local variable tables, a fresh node each time, with the kernel's own pre-mixin relays. */
	static ClassNode merged(String name) {
		byte[] bytes = MERGED.computeIfAbsent(name, n -> {
			Path jar = TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar");
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				ZipEntry entry = zip.getEntry(n + ".class");
				if (entry == null) return null;
				byte[] read = zip.getInputStream(entry).readAllBytes();
				if (n.equals(STACK)) read = new ItemUseOnInjector().transform(n.replace('/', '.'), read, null);
				if (n.equals(CLIENT)) read = new ForbricMergedBaseCompatTransformer().transform(n.replace('/', '.'), read, null);
				return read;
			} catch (Exception unreadable) {
				return null;
			}
		});
		if (bytes == null) return null;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static ClassNode jar(Fixture kind, Path jar, String entry) throws Exception {
		TestFixtures.require(kind, Files.isRegularFile(jar), jar + " absent");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			return MixinFit.parse(zip.getInputStream(zip.getEntry(entry + ".class")).readAllBytes());
		}
	}

	private static ClassNode apoli() throws Exception {
		Path jar = POPULAR.resolve("Origins-Legacy-1.12.18+26.2.jar");
		TestFixtures.require(Fixture.THIRD_PARTY, Files.isRegularFile(jar), "popular-pack Origins fixture absent");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			try (ZipInputStream in = new ZipInputStream(zip.getInputStream(zip.getEntry("META-INF/jars/Apoli-Legacy-2.12.12+26.2.jar")))) {
				for (ZipEntry entry; (entry = in.getNextEntry()) != null;)
					if (entry.getName().equals("io/github/apace100/apoli/mixin/ServerPlayerInteractionManagerMixin.class")) return MixinFit.parse(in.readAllBytes());
			}
		}
		throw new AssertionError("apoli's mixin is not in the nested jar");
	}

	/** A per-effect clear veto that is not balm's: a loop over a snapshot ({@link PerEffectClearVetoTest#snapshotLoop}). */
	private static ClassNode clearVeto() {
		ClassNode mixin = PerEffectClearVetoTest.mixin("org/example/effects/mixin/RespelledVetoMixin");
		mixin.methods.add(PerEffectClearVetoTest.snapshotLoop("keepWarded", false, false));
		return mixin;
	}
}
