/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.*;

/**
 * The released callbacks every callback adapter was built from, renamed to unrelated classes and handlers AND with every
 * selector written another way Mixin binds identically — owner-qualified, dotted, as an array, a bare name where the
 * class declares one method of that name, a descriptor where the mod wrote a bare name. Each adapter must restore
 * exactly as many callbacks as for the original spelling. A look-alike written for another method — an owner other than
 * the target, a name nothing declares, the overload a bare name really binds — must be left byte-for-byte alone.
 */
class MixinCallbackSelectorSpellingTest {
	@FunctionalInterface interface Source { ClassNode read() throws Exception; }
	@FunctionalInterface interface Adapter { int apply(ClassNode mixin, Function<String, ClassNode> targets); }
	record Contract(String id, Source source, Adapter adapter, int count) { }

	private static final java.util.function.BiFunction<Ecosystem, String, ClassNode> NATIVE = (family, name) -> CreateInjectionAdaptersTest.nativeTarget(name);

	private static Contract create(String entry, Adapter adapter, int count) {
		return new Contract(entry, () -> CreateGuestMixinFixture.mixin("com/zurrtum/create/" + entry), adapter, count);
	}
	private static Contract jar(String id, String path, String owner, Adapter adapter, int count) {
		return new Contract(id, () -> CarpetMixinAdapterTest.from(Fixture.THIRD_PARTY, Path.of(path), owner), adapter, count);
	}

	static List<Contract> contracts() {
		List<Contract> contracts = new ArrayList<>();
		Map<String, Integer> carriers = Map.of("client/mixin/ClientPacketListenerMixin", 1, "mixin/LevelChunkMixin", 1,
				"client/mixin/EntityFluidInteractionMixin", 2, "client/mixin/ModelManagerMixin", 1, "client/mixin/LoadBlockModelMixin", 1,
				"mixin/PersistentEntitySectionManagerCallbackMixin", 1, "client/mixin/GuiRendererMixin", 0, "mixin/ItemStackMixin", 2);
		carriers.forEach((entry, count) -> contracts.add(create(entry, (mixin, targets) -> MixinCarrierCallbackAdapters.adapt(mixin, targets, NATIVE), count)));
		for (String entry : List.of("LivingEntityMixin", "ItemEntityMixin", "ExperienceOrbMixin", "AbstractBoatMixin", "LeashableMixin", "ExplosionDamageCalculatorMixin"))
			contracts.add(create("mixin/" + entry, MixinBlockQueryAdapters::adapt, entry.equals("LivingEntityMixin") ? 2 : 1));
		for (var entry : Map.of("mixin/SignalGetterMixin", 1, "mixin/EntityMixin", 1, "client/mixin/MultiPlayerGameModeMixin", 2, "mixin/ServerPlayerGameModeMixin", 1).entrySet())
			contracts.add(create(entry.getKey(), (mixin, targets) -> MixinBlockInteractionAdapters.adapt(mixin, targets, NATIVE), entry.getValue()));
		contracts.add(create("mixin/LivingEntityMixin", MixinBreathingCallbackAdapter::adapt, 2));
		contracts.add(create("mixin/LivingEntityMixin", MixinEntitySoundCallbackAdapter::adapt, 1));
		contracts.add(create("mixin/EntityMixin", MixinEntitySoundCallbackAdapter::adapt, 1));
		contracts.add(create("client/mixin/HudMixin", MixinHudContextAdapter::adapt, 1));
		contracts.add(create("mixin/LiquidBlockMixin", MixinFluidInteractionAdapter::adapt, 2));
		contracts.add(create("client/mixin/KeyboardHandlerMixin", (mixin, targets) -> MixinKeyActionAdapter.adapt(mixin, targets, NATIVE), 2));
		contracts.add(create("mixin/StructureTemplateMixin", (mixin, targets) -> MixinStructurePlacementAdapter.adapt(mixin, targets, NATIVE), 3));
		for (String entry : CarpetMixinAdapterTest.NAMES)
			contracts.add(new Contract(entry, () -> CarpetMixinAdapterTest.mixin(entry),
					(mixin, targets) -> MixinPlayerWorldCallbackAdapter.adapt(mixin, targets) + MixinFluidReactionAdapter.adapt(mixin, targets),
					entry.startsWith("Level_") || entry.endsWith("BlackstoneMixin") ? 2 : 1));
		contracts.add(jar("sprite loader", "build/compat-inputs/player-loading/mods/continuity-3.0.1+26.2.jar",
				"me/pepperbell/continuity/client/mixin/SpriteSourceListMixin", MixinSpriteLoaderCallbackAdapter::adapt, 1));
		contracts.add(jar("GUI item", "build/sweep80-mac/v020-rounds/r3/mods/itemglintrelight-fabric-26.2-0.3.0+26.2.jar",
				"celia/adwadg/itemglintrelight/mixin/client/GuiGraphicsItemOutlineMixin", MixinGuiItemCaptureAdapter::adapt, 1));
		contracts.add(jar("camera roll", "build/compat-inputs/c2me-barrel-20261001/do_a_barrel_roll-fabric-3.8.4+26.2.jar",
				"nl/enjarai/doabarrelroll/mixin/client/roll/CameraMixin", (mixin, targets) -> MixinCameraRollAdapter.adapt(mixin, targets, NATIVE), 4));
		contracts.add(jar("chunk status", "build/compat-inputs/startup-20260930/c2me-notickvd.jar",
				"com/ishland/c2me/notickvd/mixin/MixinWorld", MixinCallbackSelectorSpellingTest::retarget, 1));
		return contracts;
	}

	private static int retarget(ClassNode mixin, Function<String, ClassNode> targets) {
		Function<String, byte[]> resource = name -> {
			if (!name.endsWith(".class")) return null;
			ClassNode target = targets.apply(name.substring(0, name.length() - 6));
			return target == null ? null : CarpetMixinAdapterTest.bytes(target);
		};
		return MixinRetarget.apply(mixin, MixinRetarget.plan(mixin, resource));
	}

	private static final Map<String, Optional<ClassNode>> MERGED = new HashMap<>(), NATIVES = new HashMap<>();

	/** The merged class (a fresh copy each time: adapters must not see one another's reads). */
	static ClassNode target(String name) {
		return MERGED.computeIfAbsent(name, n -> Optional.ofNullable(read(n))).map(MixinCallbackSelectorSpellingTest::copy).orElse(null);
	}
	private static ClassNode nativeTarget(String name) {
		return NATIVES.computeIfAbsent(name, n -> Optional.ofNullable(CreateInjectionAdaptersTest.nativeTarget(n))).orElse(null);
	}
	private static ClassNode copy(ClassNode node) { ClassNode copy = new ClassNode(); node.accept(copy); return copy; }

	private static ClassNode read(String name) {
		ClassNode target = CarpetMixinAdapterTest.target(name);
		if (name.equals("net/minecraft/client/gui/render/GuiRenderer")) {
			byte[] bytes = new net.forbric.kernel.transform.ForbricMergedBaseCompatTransformer().transform(name.replace('/', '.'), CarpetMixinAdapterTest.bytes(target), null);
			return MixinFit.parse(bytes);
		}
		return target;
	}

	/** Another package, another class name and other handler names, with self references and private delegates remapped. */
	static ClassNode unrelatedNames(ClassNode original) {
		Map<String, String> renames = new HashMap<>();
		renames.put(original.name, "org/example/elsewhere/RespelledCallbacks");
		int index = 0;
		for (MethodNode method : original.methods) if (!method.name.startsWith("<") && (MixinFit.injectorOf(method) != null
				|| (method.access & Opcodes.ACC_PRIVATE) != 0 && !shadow(method)))
			renames.put(original.name + "." + method.name + method.desc, "respelled" + (index++));
		ClassNode renamed = new ClassNode();
		original.accept(new ClassRemapper(renamed, new SimpleRemapper(Opcodes.ASM9, renames)));
		return renamed;
	}

	private static boolean shadow(MethodNode method) {
		return Stream.of(method.visibleAnnotations, method.invisibleAnnotations).filter(Objects::nonNull).flatMap(Collection::stream)
				.anyMatch(a -> a.desc.endsWith("/Shadow;"));
	}

	// ---- spellings ---------------------------------------------------------------------------------------------------

	/** A selector this test rewrites: a plain member (no owner, pattern or dynamic form). */
	private static boolean plain(String selector) {
		String s = selector.trim();
		if (s.isEmpty() || s.endsWith("/") || s.startsWith("@") || s.contains("->") || s.indexOf('.') >= 0) return false;
		int semi = s.indexOf(';'), paren = s.indexOf('(');
		return !(s.startsWith("L") && semi >= 0 && (paren < 0 || semi < paren));
	}

	/** Every injector's selectors in {@code mixin}, each plain one rewritten by {@code respell(target, selector)}. */
	private static void respell(ClassNode mixin, java.util.function.BiFunction<String, String, List<String>> respell) {
		String target = MixinFit.mixinTargets(mixin).getFirst();
		for (MethodNode method : mixin.methods) {
			AnnotationNode injector = MixinFit.injectorOf(method);
			if (injector == null || injector.values == null) continue;
			for (int i = 0; i + 1 < injector.values.size(); i += 2) {
				if (!"method".equals(injector.values.get(i))) continue;
				List<String> out = new ArrayList<>();
				for (String selector : MixinFit.stringList(injector.values.get(i + 1))) out.addAll(plain(selector) ? respell.apply(target, selector) : List.of(selector));
				injector.values.set(i + 1, out);
			}
		}
	}

	private static String name(String selector) { int paren = selector.indexOf('('); return paren < 0 ? selector : selector.substring(0, paren); }
	private static List<MethodNode> named(ClassNode c, String name) { return c == null ? List.of() : c.methods.stream().filter(m -> m.name.equals(name)).toList(); }

	/**
	 * A bare name for a spelled member, or the member's descriptor for a bare name, wherever that binds the same method: in
	 * the merged class and in the class the mod was compiled against, the first method declared under that name (the one
	 * Mixin binds a bare name to) is the same member.
	 */
	private static List<String> swapForm(String owner, String selector) {
		String name = name(selector);
		List<MethodNode> merged = named(target(owner), name), natives = named(nativeTarget(owner), name);
		if (merged.isEmpty() || natives.isEmpty() || !(merged.getFirst().desc).equals(natives.getFirst().desc)) return List.of(selector);
		String described = name + merged.getFirst().desc;
		if (selector.equals(name)) return List.of(described);
		return described.equals(selector) ? List.of(name) : List.of(selector);
	}

	static final Map<String, java.util.function.BiFunction<String, String, List<String>>> EQUIVALENT = new LinkedHashMap<>();
	static {
		EQUIVALENT.put("owner-qualified", (owner, s) -> List.of("L" + owner + ";" + s));
		EQUIVALENT.put("dotted owner", (owner, s) -> List.of(owner.replace('/', '.') + "." + s));
		EQUIVALENT.put("array of two spellings", (owner, s) -> List.of(s, "L" + owner + ";" + s));
		EQUIVALENT.put("bare name or descriptor", MixinCallbackSelectorSpellingTest::swapForm);
	}

	/** Every selector these name an overload declared after another of its name: no bare name is equivalent (see below). */
	static final Set<String> UNCHANGED_BY_A_BARE_NAME = Set.of("Level_fillUpdatesMixin", "chunk status", "GUI item");

	@TestFactory Stream<DynamicTest> renamedCallbacksWrittenAnotherWayAreRestoredTheSame() {
		return contracts().stream().flatMap(contract -> EQUIVALENT.entrySet().stream()
				// MixinRetarget's plan rewrites one selector string; an array keeps its spelling and is left as compiled.
				.filter(form -> !(contract.id().equals("chunk status") && form.getKey().startsWith("array")))
				.map(form -> DynamicTest.dynamicTest(contract.id() + " / " + form.getKey(), () -> {
					ClassNode mixin = unrelatedNames(contract.source().read());
					Map<String, List<String>> before = selectors(mixin);
					respell(mixin, form.getValue());
					if (!(form.getKey().startsWith("bare") && UNCHANGED_BY_A_BARE_NAME.contains(contract.id())))
						assertNotEquals(before, selectors(mixin), "the fixture must actually be respelled");
					assertEquals(contract.count(), contract.adapter().apply(mixin, MixinCallbackSelectorSpellingTest::target));
					CarpetMixinAdapterTest.verify(mixin);
					assertEquals(0, contract.adapter().apply(mixin, MixinCallbackSelectorSpellingTest::target), "idempotence");
				})));
	}

	static final Map<String, java.util.function.BiFunction<String, String, List<String>>> ELSEWHERE = new LinkedHashMap<>();
	static {
		ELSEWHERE.put("another owner", (owner, s) -> List.of("Lnet/minecraft/world/level/block/Block;" + s));
		ELSEWHERE.put("a name nothing declares", (owner, s) -> List.of(name(s) + "Elsewhere" + s.substring(name(s).length())));
	}

	@TestFactory Stream<DynamicTest> lookAlikesWrittenForAnotherMethodAreLeftAlone() {
		return contracts().stream().flatMap(contract -> ELSEWHERE.entrySet().stream()
				.map(form -> DynamicTest.dynamicTest(contract.id() + " / " + form.getKey(), () -> {
					ClassNode mixin = unrelatedNames(contract.source().read());
					respell(mixin, form.getValue());
					byte[] before = CarpetMixinAdapterTest.bytes(mixin);
					assertEquals(0, contract.adapter().apply(mixin, MixinCallbackSelectorSpellingTest::target));
					assertArrayEquals(before, CarpetMixinAdapterTest.bytes(mixin));
				})));
	}

	/** Mixin binds a bare name to the FIRST method of that name; where that is another overload, it is another callback. */
	@Test void aBareNameBindingAnotherOverloadIsNotTheNativeCallback() throws Exception {
		ClassNode fill = unrelatedNames(CarpetMixinAdapterTest.mixin("Level_fillUpdatesMixin"));
		respell(fill, (owner, s) -> List.of(name(s)));  // setBlock(BlockPos, BlockState, int) is declared first
		byte[] before = CarpetMixinAdapterTest.bytes(fill);
		assertEquals(0, MixinPlayerWorldCallbackAdapter.adapt(fill, MixinCallbackSelectorSpellingTest::target));
		assertArrayEquals(before, CarpetMixinAdapterTest.bytes(fill));
		ClassNode item = unrelatedNames(CarpetMixinAdapterTest.from(Fixture.THIRD_PARTY,
				Path.of("build/sweep80-mac/v020-rounds/r3/mods/itemglintrelight-fabric-26.2-0.3.0+26.2.jar"),
				"celia/adwadg/itemglintrelight/mixin/client/GuiGraphicsItemOutlineMixin"));
		respell(item, (owner, s) -> List.of(name(s)));  // item(ItemStack, int, int) is declared first
		before = CarpetMixinAdapterTest.bytes(item);
		assertEquals(0, MixinGuiItemCaptureAdapter.adapt(item, MixinCallbackSelectorSpellingTest::target));
		assertArrayEquals(before, CarpetMixinAdapterTest.bytes(item));
	}

	/**
	 * A native method the merged class no longer declares (StructureTemplate's original placeEntities, the left-click
	 * lambda NeoForge widened) or keeps beside a carrier overload (the bounce restitution): a bare name names it in the
	 * class the mod was compiled against, and the adapted callbacks bind exactly what the descriptor spelling's do.
	 * Without that class only a selector pinning the descriptor names such a method, so a bare name is not guessed.
	 */
	@Test void aBareNameForAMethodTheMergedGameDroppedIsReadOffTheNativeClass() throws Exception {
		Map<String, Integer> without = Map.of("mixin/StructureTemplateMixin", 0, "client/mixin/MultiPlayerGameModeMixin", 1, "mixin/EntityMixin", 0);
		for (String entry : without.keySet()) {
			ClassNode spelled = unrelatedNames(CreateGuestMixinFixture.mixin("com/zurrtum/create/" + entry)),
					bare = unrelatedNames(CreateGuestMixinFixture.mixin("com/zurrtum/create/" + entry)),
					guessed = unrelatedNames(CreateGuestMixinFixture.mixin("com/zurrtum/create/" + entry));
			// A bare name for a member the native class declares alone under that name, where the merged class either dropped
			// that member or still declares it first under that name: elsewhere a bare name is another callback (tested above).
			for (ClassNode mixin : List.of(bare, guessed)) respell(mixin, (owner, s) -> {
				List<MethodNode> declared = named(nativeTarget(owner), name(s)), merged = named(target(owner), name(s));
				boolean dropped = merged.stream().noneMatch(m -> (m.name + m.desc).equals(s));
				boolean first = !merged.isEmpty() && (merged.getFirst().name + merged.getFirst().desc).equals(s);
				return declared.size() == 1 && (name(s) + declared.getFirst().desc).equals(s) && (dropped || first) ? List.of(name(s)) : List.of(s);
			});
			assertNotEquals(selectors(spelled), selectors(bare), entry + ": the fixture must actually be respelled");
			String owner = MixinFit.mixinTargets(spelled).getFirst();
			assertTrue(adaptCreate(entry, spelled, NATIVE) > 0, entry);
			assertTrue(adaptCreate(entry, bare, NATIVE) > 0, entry);
			assertEquals(bindings(spelled, target(owner)), bindings(bare, target(owner)), entry + ": the same callbacks bind the same methods and points");
			CarpetMixinAdapterTest.verify(bare);
			assertEquals(without.get(entry), adaptCreate(entry, guessed, (family, name) -> null), entry + " without the native class");
		}
	}

	private static int adaptCreate(String entry, ClassNode mixin, java.util.function.BiFunction<Ecosystem, String, ClassNode> references) {
		return entry.contains("Structure") ? MixinStructurePlacementAdapter.adapt(mixin, MixinCallbackSelectorSpellingTest::target, references)
				: MixinBlockInteractionAdapters.adapt(mixin, MixinCallbackSelectorSpellingTest::target, references);
	}

	private static Map<String, List<String>> selectors(ClassNode mixin) {
		Map<String, List<String>> out = new TreeMap<>();
		for (MethodNode m : mixin.methods) if (MixinFit.injectorOf(m) != null) out.put(m.name + m.desc, MixinTargetSelectors.selectors(m));
		return out;
	}

	/** For each injector handler: the one merged method its selectors bind (or "-") and its points' targets. */
	private static Map<String, String> bindings(ClassNode mixin, ClassNode target) {
		Map<String, String> out = new TreeMap<>();
		for (MethodNode m : mixin.methods) {
			AnnotationNode injector = MixinFit.injectorOf(m);
			if (injector == null) continue;
			MethodNode bound = MixinTargetSelectors.one(m, target);
			List<String> points = MixinFit.atNodes(injector).stream().map(at -> String.valueOf(MixinFit.value(at, "target"))).toList();
			out.put(m.name + m.desc, (bound == null ? "-" : bound.name + bound.desc) + " @ " + points);
		}
		return out;
	}

	/**
	 * A value appended unannotated after a wrap's {@code Operation} is the target's argument at its position — Mixin hands
	 * it over only where the target has one. {@code baseTick()} has none, so Create's unannotated {@code ServerLevel} is no
	 * argument and no {@code @Local}: Mixin refuses it, and the adapter leaves it so.
	 */
	@Test void anUnannotatedExtraIsNotTheLocalAnAdapterSupplies() throws Exception {
		var contract = create("mixin/LivingEntityMixin", MixinBreathingCallbackAdapter::adapt, 2);
		ClassNode mixin = withoutWrapAnnotations(unrelatedNames(contract.source().read()));
		byte[] before = CarpetMixinAdapterTest.bytes(mixin);
		assertEquals(0, contract.adapter().apply(mixin, MixinCallbackSelectorSpellingTest::target), contract.id());
		assertArrayEquals(before, CarpetMixinAdapterTest.bytes(mixin));
	}

	/**
	 * Create's HUD and step-sound wraps ask for their target's arguments with {@code @Local(argsOnly = true)}; the same
	 * handlers written without the annotation take them as the arguments MixinExtras appends after the {@code Operation}
	 * (WrapOperationInjector's captureTargetArgs) — the same values. Each adapter serves that form, and serves it exactly
	 * as the annotated one: the adapted classes are the same bytes.
	 */
	@Test void anUnannotatedTargetArgumentIsTheArgsOnlyLocalItStandsFor() throws Exception {
		for (var contract : List.of(create("client/mixin/HudMixin", MixinHudContextAdapter::adapt, 1), create("mixin/EntityMixin", MixinEntitySoundCallbackAdapter::adapt, 1))) {
			ClassNode annotated = unrelatedNames(contract.source().read()), bare = unrelatedNames(contract.source().read());
			assertEquals(contract.count(), contract.adapter().apply(annotated, MixinCallbackSelectorSpellingTest::target), contract.id());
			// The wrap the adapter moved asked for its target's leading arguments with @Local(argsOnly = true): the same
			// wrap without those annotations takes them as appended arguments.
			Set<String> moved = new HashSet<>();
			for (MethodNode method : annotated.methods) if (method.name.endsWith("$forbricOriginal")) moved.add(method.name.substring(0, method.name.length() - "$forbricOriginal".length()));
			assertEquals(1, moved.size(), contract.id());
			for (MethodNode method : bare.methods) if (moved.contains(method.name)) {
				assertTrue(Arrays.stream(method.invisibleParameterAnnotations).filter(Objects::nonNull).flatMap(List::stream)
						.allMatch(a -> a.desc.equals(MixinHandlerShape.LOCAL) && List.of("argsOnly", true).equals(a.values)), "premise: only argsOnly locals");
				method.invisibleParameterAnnotations = null; method.visibleParameterAnnotations = null;
			}
			assertEquals(contract.count(), contract.adapter().apply(bare, MixinCallbackSelectorSpellingTest::target), contract.id());
			CarpetMixinAdapterTest.verify(bare);
			assertArrayEquals(CarpetMixinAdapterTest.bytes(annotated), CarpetMixinAdapterTest.bytes(bare), contract.id() + ": adapted as the @Local form is");
			assertEquals(0, contract.adapter().apply(bare, MixinCallbackSelectorSpellingTest::target), "idempotence");
		}
	}

	private static ClassNode withoutWrapAnnotations(ClassNode mixin) {
		for (MethodNode method : mixin.methods) if (MixinFit.injectorOf(method) != null && method.desc.contains(MixinHandlerShape.OPERATION)) {
			method.invisibleParameterAnnotations = null; method.visibleParameterAnnotations = null;
		}
		return mixin;
	}
}
