package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;
import net.forbric.api.Ecosystem;

/** Fabric mods' injectors on the merged base's carrier stubs, as shipped, against the real merged classes. */
@ResourceLock("system-properties")
@ResourceLock("ModCatalog")
class MixinStubRebindTest {
	private static final Path MERGED = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", "../forbric-loader"), "run/merged-base/patched-mc-merged-26.2.jar");
	private static final Path POPULAR = Path.of("run/client-popular/mods");
	private static final Path MERGED_PACK = Path.of("run/client-merged-pack/mods");

	@AfterEach void reset() {
		CompatibilityFindings.reset();
		System.clearProperty(MixinStubRebind.STUB_FINDING_PROPERTY);
		System.clearProperty(MixinStubRebind.PROPERTY);
		System.clearProperty(MixinStubRebind.MODIFY_VARIABLE_PROPERTY);
		System.clearProperty(MixinStubRebind.CAPTURES_PROPERTY);
		System.clearProperty(MixinStubRebind.SUGAR_BOUNDARY_PROPERTY);
		System.clearProperty(MixinStubRebind.FORGE_FAMILY_PROPERTY);
		System.clearProperty(MixinStubRebind.TYPED_LOCAL_PROPERTY);
		System.clearProperty(MixinStubRebind.SHARED_PROPERTY);
		MixinStubRebind.forget();
	}

	@Test void architecturysBreakSpeedMovesToTheBodyAndStillReceivesTheState() throws Exception {
		ClassNode mixin = fromJar(POPULAR.resolve("architectury-fabric-21.1.10.jar"), "dev/architectury/mixin/fabric/MixinPlayer");
		ClassNode player = merged("net/minecraft/world/entity/player/Player");
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		assertEquals(1, MixinStubRebind.adapt(mixin, name -> player));
		MethodNode outer = mixin.methods.stream().filter(m -> m.name.equals("breakSpeed")).findFirst().orElseThrow();
		assertEquals(List.of("getDestroySpeed(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)F"),
				MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(outer), "method")));
		assertTrue(outer.desc.startsWith("(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;"
				+ "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;"), outer.desc);
		assertNull(MixinFit.injectorOf(mixin.methods.stream().filter(m -> m.name.equals("breakSpeed" + MixinHandlerShim.INNER_SUFFIX)).findFirst().orElseThrow()));
		List<Integer> loads = Arrays.stream(outer.instructions.toArray()).filter(VarInsnNode.class::isInstance).map(i -> ((VarInsnNode) i).var).toList();
		assertEquals(List.of(0, 1, 3), loads, "this, the state (the stub passes its only argument first), the callback");
		new Analyzer<>(new BasicVerifier()).analyze(mixin.name, outer);
		assertEquals(0, MixinStubRebind.adapt(mixin, name -> player), "a second pass changes nothing");
	}

	@Test void fabricApisElytraCheckMovesWhereItsFieldReadIs() throws Exception {
		ClassNode mixin = StagedFabricMixinFixture.mixin("fabric-entity-events-v1", "net/fabricmc/fabric/mixin/entity/event/elytra/LivingEntityMixin");
		ClassNode living = merged("net/minecraft/world/entity/LivingEntity");
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		assertEquals(1, MixinStubRebind.adapt(mixin, name -> living));
		MethodNode handler = mixin.methods.stream().filter(m -> m.name.equals("injectElytraCheck")).findFirst().orElseThrow();
		assertEquals(List.of("canGlide(Z)Z"), MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(handler), "method")));
	}

	@Test void aPairVanillaAlreadyHasStaysWhereTheModPutIt() throws Exception {
		Path xaero;
		try (var files = Files.list(MERGED_PACK)) {
			xaero = files.filter(p -> p.getFileName().toString().contains("xaerominimap-fabric")).findFirst().orElse(null);
		}
		Assumptions.assumeTrue(xaero != null, "Xaero's Minimap (Fabric) fixture absent");
		ClassNode mixin = fromJar(xaero, "xaero/common/mixin/MixinFabricMinecraftClient");
		ClassNode minecraft = merged("net/minecraft/client/Minecraft");
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		assertEquals(0, MixinStubRebind.adapt(mixin, name -> minecraft),
				"vanilla's own disconnect(Screen, boolean) forwards to its three-argument overload: the mod chose it");
	}

	@Test void aNeoForgeModsMixinIsLeftAsNeoForgeWouldHaveIt() throws Exception {
		ClassNode mixin = fromJar(POPULAR.resolve("architectury-fabric-21.1.10.jar"), "dev/architectury/mixin/fabric/MixinPlayer");
		ClassNode player = merged("net/minecraft/world/entity/player/Player");
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.NEOFORGE);
		assertEquals(0, MixinStubRebind.adapt(mixin, name -> player), "compiled against the stub-first shape: native behaviour");
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FORGE);
		assertEquals(0, MixinStubRebind.adapt(mixin, name -> player), "MinecraftForge keeps the same stub: native behaviour too");
		MixinStubRebind.forget();
		assertEquals(0, MixinStubRebind.adapt(mixin, name -> player), "no known owner: no move");
	}

	// --- a Forge-family mod on the other carrier's stub ---

	private static final Path SWEEP = Path.of("build/compat-inputs/sweep90/mods");
	private static final String MODEL_MANAGER = "net/minecraft/client/resources/model/ModelManager";
	private static final String LOAD_MODELS_BODY = "loadModels(Lnet/minecraft/client/renderer/texture/SpriteLoader$Preparations;"
			+ "Lnet/minecraft/client/renderer/texture/SpriteLoader$Preparations;Lnet/minecraft/client/resources/model/ModelBakery;"
			+ "Lnet/minecraft/client/renderer/block/LoadedBlockModels;Lit/unimi/dsi/fastutil/objects/Object2IntMap;"
			+ "Lnet/minecraft/client/model/geom/EntityModelSet;Ljava/util/concurrent/Executor;"
			+ "Lnet/neoforged/neoforge/client/entity/animation/json/AnimationLoader$PendingAnimations;)Ljava/util/concurrent/CompletableFuture;";

	/**
	 * fusion is a MinecraftForge mod. MinecraftForge's ModelManager has one loadModels, the body; NeoForge added an
	 * overload and left the seven-argument one as a stub nothing calls. fusion's name-only HEAD capture of the block
	 * atlas bound that stub, its static stayed null, and every model bake threw on it (35,845 "Unable to bake model").
	 */
	@Test void fusionsSpriteCaptureMovesToTheBodyMinecraftForgeRan() throws Exception {
		ClassNode mixin = fromJar(SWEEP.resolve("fusion-1.3.15a-forge-mc26.2.jar"), "com/supermartijn642/fusion/mixin/ModelManagerMixin");
		ClassNode manager = merged(MODEL_MANAGER);
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FORGE);
		MixinStubRebind.adapt(mixin, name -> manager);
		MethodNode outer = mixin.methods.stream().filter(m -> m.name.equals("captureBlockItemSprites")).findFirst().orElseThrow();
		assertEquals(List.of(LOAD_MODELS_BODY), MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(outer), "method")));
		assertNull(MixinFit.injectorOf(mixin.methods.stream().filter(m -> m.name.equals("captureBlockItemSprites" + MixinHandlerShim.INNER_SUFFIX))
				.findFirst().orElseThrow()));
		List<Integer> loads = Arrays.stream(outer.instructions.toArray()).filter(VarInsnNode.class::isInstance).map(i -> ((VarInsnNode) i).var).toList();
		assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 8), loads, "the stub's seven arguments, then the callback past the pending animations");
		new Analyzer<>(new BasicVerifier()).analyze(mixin.name, outer);

		for (Ecosystem stays : List.of(Ecosystem.NEOFORGE, Ecosystem.FORGE)) {
			ClassNode again = fromJar(SWEEP.resolve("fusion-1.3.15a-forge-mc26.2.jar"), "com/supermartijn642/fusion/mixin/ModelManagerMixin");
			MixinStubRebind.noteEcosystem(again.name, stays);
			if (stays == Ecosystem.FORGE) System.setProperty(MixinStubRebind.FORGE_FAMILY_PROPERTY, "off");
			MixinStubRebind.adapt(again, name -> manager);
			assertEquals(List.of("loadModels"), selectors(again, "captureBlockItemSprites"),
					stays == Ecosystem.FORGE ? "the switch: Fabric mods only" : "a NeoForge mod was compiled against that very stub");
			System.clearProperty(MixinStubRebind.FORGE_FAMILY_PROPERTY);
		}
	}

	private static final String DISCOVER_STUB = "discoverModelDependencies(Ljava/util/Map;Lnet/minecraft/client/resources/model/BlockStateModelLoader$LoadedModels;"
			+ "Lnet/minecraft/client/resources/model/ClientItemInfoLoader$LoadedClientInfos;)Lnet/minecraft/client/resources/model/ModelManager$ResolvedModels;";
	private static final String DISCOVER_BODY = "discoverModelDependencies(Ljava/util/Map;Lnet/minecraft/client/resources/model/BlockStateModelLoader$LoadedModels;"
			+ "Lnet/minecraft/client/resources/model/ClientItemInfoLoader$LoadedClientInfos;"
			+ "Lnet/neoforged/neoforge/client/model/standalone/StandaloneModelLoader$LoadedModels;)Lnet/minecraft/client/resources/model/ModelManager$ResolvedModels;";

	/**
	 * fusion's overlay models are added as discovery roots through the ModelDiscovery the method builds, taken by a
	 * @Local with nothing but its type. The ResolvedModels construction it anchors on is only in NeoForge's overload,
	 * where `result` is the one ModelDiscovery live there.
	 */
	@Test void fusionsOverlayHookMovesWithTheOnlyModelDiscoveryInTheBody() throws Exception {
		ClassNode manager = merged(MODEL_MANAGER);
		ClassNode mixin = fromJar(SWEEP.resolve("fusion-1.3.15a-forge-mc26.2.jar"), "com/supermartijn642/fusion/mixin/ModelManagerMixin");
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FORGE);
		assertEquals(2, MixinStubRebind.adapt(mixin, name -> manager), "the sprite capture and the overlay hook");
		assertEquals(List.of(DISCOVER_BODY), selectors(mixin, "registerBlockModelOverlays"));

		System.setProperty(MixinStubRebind.TYPED_LOCAL_PROPERTY, "off");
		ClassNode off = fromJar(SWEEP.resolve("fusion-1.3.15a-forge-mc26.2.jar"), "com/supermartijn642/fusion/mixin/ModelManagerMixin");
		MixinStubRebind.adapt(off, name -> manager);
		assertEquals(List.of(DISCOVER_STUB), selectors(off, "registerBlockModelOverlays"), "the switch");
	}

	/**
	 * supermartijn642corelib (Fabric) hooks the same method before ModelDiscovery.missingModel, capturing the stub's
	 * three arguments and the ModelDiscovery by type: wrapped, the @Local stays on the outer's last parameter.
	 */
	@Test void coreLibsModelHookMovesWrappedWithItsByTypeLocal() throws Exception {
		ClassNode manager = merged(MODEL_MANAGER);
		ClassNode mixin = fromJar(SWEEP.resolve("supermartijn642corelib-1.1.24b-fabric-mc26.2.jar"), "com/supermartijn642/core/mixin/ModelManagerMixin");
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		assertEquals(1, MixinStubRebind.adapt(mixin, name -> manager));
		MethodNode outer = mixin.methods.stream().filter(m -> m.name.equals("discoverModelDependencies")).findFirst().orElseThrow();
		assertEquals(List.of(DISCOVER_BODY), MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(outer), "method")));
		Type[] params = Type.getArgumentTypes(outer.desc);
		assertEquals("Lnet/minecraft/client/resources/model/ModelDiscovery;", params[params.length - 1].getDescriptor());
		assertTrue(MixinStubRebind.annotated(outer, params.length - 1), "the @Local moved with its parameter");
		new Analyzer<>(new BasicVerifier()).analyze(mixin.name, outer);
	}

	/** A second ModelDiscovery live at the anchor, or the one there unnamed by the table: MixinExtras' pick is not the proof's. */
	@Test void aByTypeLocalIsLeftWhereTheBodyDoesNotDecideIt() throws Exception {
		String discovery = "Lnet/minecraft/client/resources/model/ModelDiscovery;";
		for (String why : List.of("a second ModelDiscovery slot", "the one slot unnamed", "no local variable table")) {
			ClassNode manager = merged(MODEL_MANAGER);
			MethodNode body = manager.methods.stream().filter(m -> DISCOVER_BODY.equals(m.name + m.desc)).findFirst().orElseThrow();
			switch (why) {
				case "a second ModelDiscovery slot" -> {
					int slot = body.maxLocals;
					body.maxLocals++;
					org.objectweb.asm.tree.LabelNode start = new org.objectweb.asm.tree.LabelNode(), end = new org.objectweb.asm.tree.LabelNode();
					body.instructions.insert(start);
					body.instructions.insert(new VarInsnNode(Opcodes.ASTORE, slot));
					body.instructions.insert(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.CHECKCAST, "net/minecraft/client/resources/model/ModelDiscovery"));
					body.instructions.insert(new org.objectweb.asm.tree.InsnNode(Opcodes.ACONST_NULL));
					body.instructions.add(end);
					body.localVariables.add(new org.objectweb.asm.tree.LocalVariableNode("other", discovery, null, start, end, slot));
				}
				case "the one slot unnamed" -> body.localVariables.removeIf(l -> l.desc.equals(discovery));
				default -> body.localVariables = null;
			}
			ClassNode mixin = fromJar(SWEEP.resolve("fusion-1.3.15a-forge-mc26.2.jar"), "com/supermartijn642/fusion/mixin/ModelManagerMixin");
			MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FORGE);
			MixinStubRebind.adapt(mixin, name -> manager);
			assertEquals(List.of(DISCOVER_STUB), selectors(mixin, "registerBlockModelOverlays"), why);
		}
	}

	/**
	 * What MixinExtras' implicit @Local counts, as Mixin's own Locals.getLocalsAt reports it on these javac shapes: each
	 * slot typed by its table entry and carried down the method from above. {@code List x = new ArrayList()} beside
	 * {@code List y} is two Lists to it, and so is an x a block, a loop or a catch above left behind; on two it fails
	 * the injection, and the mixin with it. The data flow at the anchor saw only y in each. An argument counts, never
	 * read or not; {@code this} never does: an instance method's own class is no candidate there, and one copy of it is
	 * the only one.
	 */
	@Test void aByTypeLocalCountsWhatMixinExtrasCounts() throws Exception {
		ClassNode shapes = new ClassNode();
		try (java.io.InputStream in = TypedLocals.class.getResourceAsStream("MixinStubRebindTest$TypedLocals.class")) {
			new ClassReader(in).accept(shapes, 0);
		}
		List<AnnotationNode> yield = List.of(at("INVOKE", "target", "Ljava/lang/Thread;yield()V"));
		java.util.Map<String, Boolean> decided = new java.util.TreeMap<>();
		for (MethodNode shape : shapes.methods) {
			if (shape.name.startsWith("<")) continue;
			Type wanted = Type.getType((shape.access & Opcodes.ACC_STATIC) != 0 ? List.class : TypedLocals.class);
			decided.put(shape.name, MixinStubRebind.theOnlyLocalOfItsType(shapes, shape, wanted, yield));
		}
		assertEquals(java.util.Map.of("twoLists", false, "anArrayListAndAList", true, "aBlockAbove", false, "aLoopAbove", false,
				"aCatchAbove", false, "aSlotReused", true, "onlyItself", false, "itselfAndACopy", true, "anArgument", true,
				"anArgumentAndALocal", false), decided);
	}

	/** javac's shapes around a {@code Thread.yield()} anchor, for a by-type {@code @Local List} (or TypedLocals, in its own methods). */
	@SuppressWarnings({"rawtypes", "unused"})
	private static final class TypedLocals {
		static void twoLists() { List x = new java.util.ArrayList(); List y = List.of(); Thread.yield(); x.size(); y.size(); }
		static void anArrayListAndAList() { java.util.ArrayList x = new java.util.ArrayList(); List y = List.of(); Thread.yield(); x.size(); y.size(); }
		static void aBlockAbove() { List y = List.of(); { List x = new java.util.ArrayList(); x.size(); } Thread.yield(); y.size(); }
		static void aLoopAbove() { List y = List.of(); for (int i = 0; i < 3; i++) { List x = new java.util.ArrayList(); x.size(); } Thread.yield(); y.size(); }
		static void aCatchAbove() { List y = List.of(); try { y.size(); } catch (RuntimeException e) { List x = new java.util.ArrayList(); x.size(); } Thread.yield(); y.size(); }
		static void aSlotReused() { { List x = new java.util.ArrayList(); x.size(); } List y = List.of(); Thread.yield(); y.size(); }
		static void anArgument(List a) { Thread.yield(); }
		static void anArgumentAndALocal(List a) { List y = List.of(); Thread.yield(); y.size(); }
		void onlyItself() { Thread.yield(); }
		void itselfAndACopy() { TypedLocals y = this; Thread.yield(); y.hashCode(); }
	}

	/**
	 * Mixin's walk can lose a live slot at a frame: right after Player.doSweepAttack's entity loop it holds no
	 * ServerLevel, though {@code serverLevel} is live and the table names it, so MixinExtras finds none at that
	 * getYRot and fails the injection. In the loop, and past the next read of it, Mixin holds it again.
	 */
	@Test void aByTypeLocalMixinLosesAtALoopsExitIsLeft() throws Exception {
		ClassNode player = merged("net/minecraft/world/entity/player/Player");
		MethodNode sweep = player.methods.stream().filter(m -> m.name.equals("doSweepAttack") && m.desc.equals("(Lnet/minecraft/world/entity/"
				+ "Entity;FLnet/minecraft/world/damagesource/DamageSource;FLnet/minecraft/world/phys/AABB;)V")).findFirst().orElseThrow();
		Type level = Type.getType("Lnet/minecraft/server/level/ServerLevel;");
		assertFalse(MixinStubRebind.theOnlyLocalOfItsType(player, sweep, level, List.of(at("INVOKE", "target",
				"Lnet/minecraft/world/entity/player/Player;getYRot()F"))), "one of its getYRot calls follows the loop's exit");
		assertTrue(MixinStubRebind.theOnlyLocalOfItsType(player, sweep, level, List.of(at("INVOKE", "target",
				"Lnet/minecraft/world/entity/LivingEntity;hurtServer(Lnet/minecraft/server/level/ServerLevel;"
						+ "Lnet/minecraft/world/damagesource/DamageSource;F)Z"))), "in the loop");
		assertTrue(MixinStubRebind.theOnlyLocalOfItsType(player, sweep, level, List.of(at("INVOKE", "target",
				"Lnet/minecraft/server/level/ServerLevel;sendParticles(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I"))),
				"read again after it");
	}

	/** MixinFit asks the same rule: the ResolvedModels anchor reads found for the MinecraftForge mod, missing otherwise. */
	@Test void fusionsVerdictFindsTheAnchorWhereTheRebindPutsIt() throws Exception {
		Path jar = SWEEP.resolve("fusion-1.3.15a-forge-mc26.2.jar");
		Assumptions.assumeTrue(Files.isRegularFile(jar) && Files.isRegularFile(MERGED), "fusion and the merged base required");
		byte[] mixin, manager;
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			mixin = zip.getInputStream(zip.getEntry("com/supermartijn642/fusion/mixin/ModelManagerMixin.class")).readAllBytes();
		}
		try (ZipFile zip = new ZipFile(MERGED.toFile())) {
			manager = zip.getInputStream(zip.getEntry(MODEL_MANAGER + ".class")).readAllBytes();
		}
		java.util.function.Function<String, byte[]> resolver = name -> name.equals(MODEL_MANAGER + ".class") ? manager : null;
		java.util.function.Predicate<MixinFit.Result> resolvedModelsMissing =
				fit -> fit.unresolved().stream().anyMatch(u -> u.contains("<init>") && u.contains("discoverModelDependencies"));
		MixinStubRebind.noteEcosystem("com/supermartijn642/fusion/mixin/ModelManagerMixin", Ecosystem.NEOFORGE);
		assertTrue(resolvedModelsMissing.test(MixinFit.evaluate(mixin, resolver)), "premise: bound to the stub");
		MixinStubRebind.noteEcosystem("com/supermartijn642/fusion/mixin/ModelManagerMixin", Ecosystem.FORGE);
		MixinFit.Result fit = MixinFit.evaluate(mixin, resolver);
		assertFalse(resolvedModelsMissing.test(fit), fit.toString());
	}

	// --- @Share groups and argsOnly locals: owo's lang hooks ---

	private static final String LANGUAGE = "net/minecraft/locale/Language";
	private static final String LOAD_FROM_JSON_BODY = "loadFromJson(Ljava/io/InputStream;Ljava/util/function/BiConsumer;Ljava/util/function/BiConsumer;)V";
	private static final String LOAD_FROM_JSON_STUB = "loadFromJson(Ljava/io/InputStream;Ljava/util/function/BiConsumer;)V";
	private static final List<String> OWO_HOOKS = List.of("deNestNestedKeys", "handleRichTranslationsAndErrors", "doSkip");

	private static ClassNode owoLanguageMixin() throws Exception {
		return fromJar(SWEEP.resolve("owo-lib-0.13.1+26.2.jar"), "io/wispforest/owo/mixin/text/LanguageMixin");
	}

	/**
	 * owo's three lang hooks pass flags through @Share and read the InputStream as an argsOnly @Local. On the stub none
	 * attached, NeoForge's body parsed owo's nested '.{}' keys as components and dropped owo's whole lang file.
	 */
	@Test void owosLangHooksMoveTogetherToTheBodyClientLanguageCalls() throws Exception {
		ClassNode language = merged(LANGUAGE);
		ClassNode mixin = owoLanguageMixin();
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		for (String hook : OWO_HOOKS) {
			MethodNode handler = mixin.methods.stream().filter(m -> m.name.equals(hook)).findFirst().orElseThrow();
			assertEquals(LOAD_FROM_JSON_BODY, describe(MixinStubRebind.destination(mixin, handler, language)), hook);
		}
		assertEquals(3, MixinStubRebind.adapt(mixin, name -> language));
		for (String hook : OWO_HOOKS) assertEquals(List.of(LOAD_FROM_JSON_BODY), selectors(mixin, hook), hook);
		assertEquals(0, MixinStubRebind.adapt(mixin, name -> language), "a second pass changes nothing");

		System.setProperty(MixinStubRebind.SHARED_PROPERTY, "off");
		ClassNode off = owoLanguageMixin();
		assertEquals(0, MixinStubRebind.adapt(off, name -> language), "the switch");
	}

	/** One sharer that cannot move holds its whole group — and, through the keys they share, every group linked to it. */
	@Test void aShareGroupMovesWholeOrNotAtAll() throws Exception {
		ClassNode language = merged(LANGUAGE);
		// doSkip allows one BiConsumer.accept; NeoForge's body has three. It stays, so does the handler it shares the
		// skip flag with, and so does the one sharing the other two flags with that.
		ClassNode bounded = owoLanguageMixin();
		MixinStubRebind.noteEcosystem(bounded.name, Ecosystem.FABRIC);
		MixinFit.injectorOf(bounded.methods.stream().filter(m -> m.name.equals("doSkip")).findFirst().orElseThrow()).values.addAll(List.of("allow", 1));
		assertEquals(0, MixinStubRebind.adapt(bounded, name -> language));
		for (String hook : OWO_HOOKS) assertEquals(List.of(LOAD_FROM_JSON_STUB), selectors(bounded, hook), hook);

		// An explicit namespace can be shared with injectors elsewhere: that handler stays. It is its own key, so the other
		// two, which now share only with each other, still move.
		ClassNode named = owoLanguageMixin();
		MixinStubRebind.noteEcosystem(named.name, Ecosystem.FABRIC);
		MethodNode deNest = named.methods.stream().filter(m -> m.name.equals("deNestNestedKeys")).findFirst().orElseThrow();
		for (List<AnnotationNode> parameter : deNest.invisibleParameterAnnotations == null ? List.<List<AnnotationNode>>of() : Arrays.asList(deNest.invisibleParameterAnnotations)) {
			if (parameter != null) for (AnnotationNode a : parameter) if (a.desc.endsWith("/Share;")) a.values.addAll(List.of("namespace", "elsewhere"));
		}
		for (List<AnnotationNode> parameter : deNest.visibleParameterAnnotations == null ? List.<List<AnnotationNode>>of() : Arrays.asList(deNest.visibleParameterAnnotations)) {
			if (parameter != null) for (AnnotationNode a : parameter) if (a.desc.endsWith("/Share;")) a.values.addAll(List.of("namespace", "elsewhere"));
		}
		assertEquals(2, MixinStubRebind.adapt(named, name -> language));
		assertEquals(List.of(LOAD_FROM_JSON_STUB), selectors(named, "deNestNestedKeys"));
		assertEquals(List.of(LOAD_FROM_JSON_BODY), selectors(named, "doSkip"));
	}

	/**
	 * fabric-renderer-api's chunk-meshing takeover: an @Inject sets the FRAPI renderer up through @Share, a @Redirect hands
	 * it every block. On NeoForge's compile overload the redirected call is NeoForge's own per-block renderer, so the
	 * group stays on the stub — switching chunk meshing is not this rule's to decide.
	 */
	@Test void aShareGroupWithARedirectStays() throws Exception {
		ClassNode mixin = StagedFabricMixinFixture.mixin("fabric-renderer-api-v1", "net/fabricmc/fabric/mixin/client/renderer/block/render/SectionCompilerMixin");
		ClassNode compiler = merged("net/minecraft/client/renderer/chunk/SectionCompiler");
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		assertEquals(0, MixinStubRebind.adapt(mixin, name -> compiler));
		assertEquals(List.of("compile"), selectors(mixin, "beforeLoopCompile"));
		assertEquals(List.of("compile"), selectors(mixin, "tesselateBlockProxy"));
	}

	/** MixinFit asks the same group rule: owo's lang mixin reads FIT where the three move, PARTIAL where they cannot. */
	@Test void owosVerdictFollowsTheGroup() throws Exception {
		Path jar = SWEEP.resolve("owo-lib-0.13.1+26.2.jar");
		Assumptions.assumeTrue(Files.isRegularFile(jar) && Files.isRegularFile(MERGED), "owo and the merged base required");
		byte[] mixin, language;
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			mixin = zip.getInputStream(zip.getEntry("io/wispforest/owo/mixin/text/LanguageMixin.class")).readAllBytes();
		}
		try (ZipFile zip = new ZipFile(MERGED.toFile())) {
			language = zip.getInputStream(zip.getEntry(LANGUAGE + ".class")).readAllBytes();
		}
		java.util.function.Function<String, byte[]> resolver = name -> name.equals(LANGUAGE + ".class") ? language : null;
		assertEquals(MixinFit.Verdict.PARTIAL, MixinFit.evaluate(mixin, resolver).verdict(), "premise: bound to the stub");
		MixinStubRebind.noteEcosystem("io/wispforest/owo/mixin/text/LanguageMixin", Ecosystem.FABRIC);
		MixinFit.Result fit = MixinFit.evaluate(mixin, resolver);
		assertEquals(MixinFit.Verdict.FIT, fit.verdict(), fit.toString());
		System.setProperty(MixinStubRebind.SHARED_PROPERTY, "off");
		assertEquals(MixinFit.Verdict.PARTIAL, MixinFit.evaluate(mixin, resolver).verdict(), "the switch");
	}

	/** An argsOnly @Local reads the argument its type (and ordinal) picks: the stub must pass that one through to the one it picks there. */
	@Test void anArgsOnlyLocalMovesOnlyWhereTheStubPassesItsArgumentThrough() {
		MethodNode stub = new MethodNode(Opcodes.ACC_STATIC, "f", "(IILjava/io/InputStream;)V", null, null);
		MethodNode body = new MethodNode(Opcodes.ACC_STATIC, "f", "(IILjava/io/InputStream;Z)V", null, null);
		Type in = Type.getType("Ljava/io/InputStream;");
		MixinStubRebind.Delegation straight = new MixinStubRebind.Delegation(body, new int[] { 0, 1, 2 });
		MixinStubRebind.Delegation swapped = new MixinStubRebind.Delegation(body, new int[] { 1, 0, 2 });
		assertTrue(MixinStubRebind.argumentSurvives(in, null, stub, straight));
		assertTrue(MixinStubRebind.argumentSurvives(Type.INT_TYPE, 1, stub, straight));
		assertFalse(MixinStubRebind.argumentSurvives(Type.INT_TYPE, 0, stub, swapped), "the stub hands its first int over as the second");
		assertFalse(MixinStubRebind.argumentSurvives(Type.INT_TYPE, null, stub, straight), "two ints and no ordinal: Mixin's own pick fails");
		assertFalse(MixinStubRebind.argumentSurvives(in, null, stub, new MixinStubRebind.Delegation(body, new int[] { 0, 1, -1 })),
				"the stream is not passed through");
	}

	private static String describe(MethodNode method) {
		return method == null ? null : method.name + method.desc;
	}

	/** The mirror: MinecraftForge forwards PackDetector's two-argument detectPackResources; NeoForge kept it as the body. */
	@Test void aNeoForgeModMovesOffAStubOnlyMinecraftForgeHas() throws Exception {
		String owner = "net/minecraft/server/packs/repository/PackDetector";
		ClassNode detector = merged(owner);
		for (Ecosystem ecosystem : List.of(Ecosystem.NEOFORGE, Ecosystem.FORGE)) {
			ClassNode mixin = synthetic("com/example/PackDetectorMixin", owner, "onDetect", "(" + CALLBACK_INFO_RETURNABLE + ")V", false,
					injector(INJECT, "detectPackResources", List.of(at("HEAD"))));
			MixinStubRebind.noteEcosystem(mixin.name, ecosystem);
			assertEquals(ecosystem == Ecosystem.NEOFORGE ? 1 : 0, MixinStubRebind.adapt(mixin, name -> detector), ecosystem.name());
			assertEquals(List.of(ecosystem == Ecosystem.NEOFORGE ? "detectPackResources(Ljava/nio/file/Path;Ljava/util/List;Z)Ljava/lang/Object;"
					: "detectPackResources"), selectors(mixin, "onDetect"), ecosystem.name());
		}
	}

	/** Each native shape moves the selector forms that ran on code there, and only for its own family. */
	@Test void aRowMovesTheSelectorFormsThatRanOnCodeOnTheModsOwnPlatform() {
		String stub = "(I)V", overload = "(IZ)V";
		assertEquals(MixinStubRebind.Shape.BODY, MixinStubRebind.Shape.of(platform(m("f", stub, false), m("g", "()V", false)), "f", stub, overload));
		assertEquals(MixinStubRebind.Shape.DESCRIPTOR_BODY, MixinStubRebind.Shape.of(platform(m("f", "()V", false), m("f", stub, false)), "f", stub, overload));
		assertEquals(MixinStubRebind.Shape.OVERLOAD_BODY, MixinStubRebind.Shape.of(platform(m("f", overload, false)), "f", stub, overload));
		assertEquals(MixinStubRebind.Shape.STUB, MixinStubRebind.Shape.of(platform(m("f", stub, true), m("f", overload, false)), "f", stub, overload));
		assertEquals(MixinStubRebind.Shape.ABSENT, MixinStubRebind.Shape.of(platform(m("f", "()V", false), m("f", overload, false)), "f", stub, overload),
				"another overload binds the name first, and nothing has the descriptor");
		assertEquals(MixinStubRebind.Shape.ABSENT, MixinStubRebind.Shape.of(null, "f", stub, overload));

		MixinStubRebind.Row neoAdded = new MixinStubRebind.Row(MixinStubRebind.Shape.BODY, MixinStubRebind.Shape.STUB);
		assertTrue(neoAdded.moves(Ecosystem.FABRIC, true));
		assertTrue(neoAdded.moves(Ecosystem.FORGE, true));
		assertTrue(neoAdded.moves(Ecosystem.FORGE, false));
		assertFalse(neoAdded.moves(Ecosystem.NEOFORGE, true));
		MixinStubRebind.Row late = new MixinStubRebind.Row(MixinStubRebind.Shape.DESCRIPTOR_BODY, MixinStubRebind.Shape.OVERLOAD_BODY);
		assertFalse(late.moves(Ecosystem.FORGE, true), "natively the name bound the other overload");
		assertTrue(late.moves(Ecosystem.FORGE, false));
		assertTrue(late.moves(Ecosystem.NEOFORGE, true));
		assertFalse(late.moves(Ecosystem.NEOFORGE, false), "natively that descriptor bound nothing");
		MixinStubRebind.Row both = new MixinStubRebind.Row(MixinStubRebind.Shape.STUB, MixinStubRebind.Shape.ABSENT);
		assertTrue(both.moves(Ecosystem.FABRIC, false));
		assertFalse(both.moves(Ecosystem.FORGE, true));
		assertFalse(both.moves(Ecosystem.NEOFORGE, true));
		System.setProperty(MixinStubRebind.FORGE_FAMILY_PROPERTY, "off");
		assertFalse(neoAdded.moves(Ecosystem.FORGE, true), "the switch");
		assertTrue(neoAdded.moves(Ecosystem.FABRIC, true));
	}

	private static ClassNode platform(MethodNode... methods) {
		ClassNode node = new ClassNode();
		node.name = "p/C";
		node.methods = new java.util.ArrayList<>(List.of(methods));
		return node;
	}

	/** A static method: a body ({@code return}), or a stub forwarding its int to {@code f(IZ)V}. */
	private static MethodNode m(String name, String desc, boolean forwards) {
		MethodNode method = new MethodNode(Opcodes.ACC_STATIC, name, desc, null, null);
		if (forwards) {
			method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
			method.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ICONST_0));
			method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "p/C", name, "(IZ)V", false));
		}
		method.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
		return method;
	}

	@Test void theSwitchMovesNothing() throws Exception {
		System.setProperty(MixinStubRebind.PROPERTY, "off");
		ClassNode mixin = fromJar(POPULAR.resolve("architectury-fabric-21.1.10.jar"), "dev/architectury/mixin/fabric/MixinPlayer");
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		ClassNode player = merged("net/minecraft/world/entity/player/Player");
		assertEquals(0, MixinStubRebind.adapt(mixin, name -> player));
	}

	/**
	 * malilib keeps its mods' number formats (%d, %02d, %.2f) out of vanilla's rewrite with a @ModifyArgs on
	 * Language.loadFromJson(InputStream, BiConsumer) — on the merged base a stub passing a no-op lambda to NeoForge's
	 * three-argument body, which is the one ClientLanguage calls. The handler's @Local entry is in the body's table.
	 */
	@Test void malilibsFormatRestoreMovesToTheBodyClientLanguageCalls() throws Exception {
		Path jar = MERGED_PACK.resolve("malilib-fabric-26.2-0.29.3.jar");
		Assumptions.assumeTrue(Files.isRegularFile(jar), "malilib absent from the merged pack");
		ClassNode mixin = fromJar(jar, "fi/dy/masa/malilib/mixin/client/MixinLanguage");
		ClassNode language = merged("net/minecraft/locale/Language");
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		MethodNode handler = mixin.methods.stream().filter(m -> m.name.equals("malilib_onLoadCustomText")).findFirst().orElseThrow();
		String desc = handler.desc;
		assertEquals(1, MixinStubRebind.adapt(mixin, name -> language));
		handler = mixin.methods.stream().filter(m -> m.name.equals("malilib_onLoadCustomText")).findFirst().orElseThrow();
		assertEquals(List.of("loadFromJson(Ljava/io/InputStream;Ljava/util/function/BiConsumer;Ljava/util/function/BiConsumer;)V"),
				MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(handler), "method")));
		assertEquals(desc, handler.desc, "a @ModifyArgs handler keeps its own signature");
		assertTrue(mixin.methods.stream().noneMatch(m -> m.name.endsWith(MixinHandlerShim.INNER_SUFFIX)));
		assertEquals(0, MixinStubRebind.adapt(mixin, name -> language), "a second pass changes nothing");
		MixinStubRebind.forget();
		ClassNode neo = fromJar(jar, "fi/dy/masa/malilib/mixin/client/MixinLanguage");
		MixinStubRebind.noteEcosystem(neo.name, Ecosystem.NEOFORGE);
		assertEquals(0, MixinStubRebind.adapt(neo, name -> language), "not a Fabric mod's mixin");
	}

	/** MixinFit asks the rebind: malilib's hook reads FIT for a Fabric mod (where it moves), PARTIAL otherwise. */
	@Test void theVerdictFollowsTheRebind() throws Exception {
		Path jar = MERGED_PACK.resolve("malilib-fabric-26.2-0.29.3.jar");
		Assumptions.assumeTrue(Files.isRegularFile(jar), "malilib absent from the merged pack");
		byte[] mixin;
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			mixin = zip.getInputStream(zip.getEntry("fi/dy/masa/malilib/mixin/client/MixinLanguage.class")).readAllBytes();
		}
		byte[] language;
		try (ZipFile zip = new ZipFile(MERGED.toFile())) {
			language = zip.getInputStream(zip.getEntry("net/minecraft/locale/Language.class")).readAllBytes();
		}
		java.util.function.Function<String, byte[]> resolver = name -> name.equals("net/minecraft/locale/Language.class") ? language : null;
		assertEquals(MixinFit.Verdict.PARTIAL, MixinFit.evaluate(mixin, resolver).verdict(), "premise: bound to the stub");
		MixinStubRebind.noteEcosystem("fi/dy/masa/malilib/mixin/client/MixinLanguage", Ecosystem.FABRIC);
		MixinFit.Result fit = MixinFit.evaluate(mixin, resolver);
		assertEquals(MixinFit.Verdict.FIT, fit.verdict(), fit.toString());
		System.setProperty(MixinStubRebind.PROPERTY, "off");
		assertEquals(MixinFit.Verdict.PARTIAL, MixinFit.evaluate(mixin, resolver).verdict(), "the rebind's switch");
	}

	/** A non-capturing lambda is a constant; a capturing one, or a string concatenation, is work the stub does. */
	@Test void aLambdaStubIsAStubButACapturingOneIsNot() throws Exception {
		ClassNode language = merged("net/minecraft/locale/Language");
		MethodNode stub = language.methods.stream().filter(m -> m.name.equals("loadFromJson")
				&& m.desc.equals("(Ljava/io/InputStream;Ljava/util/function/BiConsumer;)V")).findFirst().orElseThrow();
		assertNotNull(MixinStubRebind.delegation(language, stub));
		org.objectweb.asm.tree.InvokeDynamicInsnNode indy = Arrays.stream(stub.instructions.toArray())
				.filter(org.objectweb.asm.tree.InvokeDynamicInsnNode.class::isInstance).map(org.objectweb.asm.tree.InvokeDynamicInsnNode.class::cast)
				.findFirst().orElseThrow();
		String nonCapturing = indy.desc;
		indy.desc = "(Ljava/lang/Object;)Ljava/util/function/BiConsumer;";
		stub.instructions.insertBefore(indy, new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.ACONST_NULL));
		assertNull(MixinStubRebind.delegation(language, stub), "a capturing lambda");
		indy.desc = nonCapturing;
		stub.instructions.remove(indy.getPrevious());
		indy.bsm = new org.objectweb.asm.Handle(org.objectweb.asm.Opcodes.H_INVOKESTATIC, "java/lang/invoke/StringConcatFactory",
				"makeConcatWithConstants", indy.bsm.getDesc(), false);
		assertNull(MixinStubRebind.delegation(language, stub), "a string concatenation");
	}

	/** An interface default forwarding to an abstract overload has nowhere to inject: no stub. */
	@Test void aDefaultForwardingToAnAbstractOverloadIsNoStub() throws Exception {
		ClassNode loader = merged("net/minecraft/client/renderer/texture/atlas/SpriteResourceLoader");
		MethodNode forwarding = loader.methods.stream().filter(m -> m.name.equals("loadSprite")
				&& Type.getArgumentTypes(m.desc).length == 2 && m.instructions.size() > 0).findFirst().orElse(null);
		Assumptions.assumeTrue(forwarding != null, "SpriteResourceLoader.loadSprite reshaped");
		assertNull(MixinStubRebind.delegation(loader, forwarding));
	}

	// --- trailing captures of the target's arguments on the @At-driven kinds ---

	private static final String FUEL = "net/minecraft/world/level/block/entity/FuelValues";
	private static final String FUEL_VALUES = "L" + FUEL + ";";
	private static final String PROVIDER = "Lnet/minecraft/core/HolderLookup$Provider;";
	private static final String FLAGS = "Lnet/minecraft/world/flag/FeatureFlagSet;";
	private static final String BUILDER = "L" + FUEL + "$Builder;";
	private static final String STUB_BURN = "vanillaBurnTimes(" + PROVIDER + FLAGS + "I)" + FUEL_VALUES;
	private static final String BODY_BURN = "vanillaBurnTimes(" + BUILDER + "I)" + FUEL_VALUES;
	private static final String STATE = "Lnet/minecraft/world/level/block/state/BlockState;";
	private static final String POS = "Lnet/minecraft/core/BlockPos;";
	private static final String MODIFY_RETURN = "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;";
	private static final String WRAP_OPERATION = "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";
	private static final String OPERATION = "Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;";
	private static final String MODIFY_VARIABLE = "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;";
	private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
	private static final String COERCE = "Lorg/spongepowered/asm/mixin/injection/Coerce;";
	private static final String NOT_NULL = "Lorg/jetbrains/annotations/NotNull;";
	private static final String CALLBACK_INFO_RETURNABLE = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";

	/**
	 * torrential's fuel modifier captures all three of the stub's arguments after the value it modifies. Two of them
	 * go into a Builder and never reach the body, so on the body MixinExtras rejects the handler — it stays on the
	 * stub, where it binds, and both adapters and the verdict say so.
	 */
	@Test void aReturnModifierCapturingTheStubsArgumentsStaysOnTheStub() throws Exception {
		ClassNode fuel = merged(FUEL);
		ClassNode mixin = synthetic("sircow/torrential/mixin/FuelValuesMixin", FUEL, "torrential$modifyFuelValues",
				"(" + FUEL_VALUES + PROVIDER + FLAGS + "I)" + FUEL_VALUES, true, injector(MODIFY_RETURN, STUB_BURN, List.of(at("RETURN"))));
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC, "torrential.mixins.json");
		MethodNode handler = mixin.methods.getFirst();
		assertNull(MixinStubRebind.destination(mixin, handler, fuel));
		assertTrue(CompatibilityFindings.all().isEmpty(), "asking where it would go reports nothing");
		assertEquals(0, MixinStubRebind.adapt(mixin, name -> fuel));
		assertEquals(List.of(STUB_BURN), selectors(mixin, "torrential$modifyFuelValues"));
		// It binds on the stub and runs only where the stub is called: the dedicated server builds fuel without it.
		List<CompatibilityFinding> stays = CompatibilityFindings.all();
		assertEquals(1, stays.size(), stays.toString());
		assertEquals(CompatibilityFinding.Confidence.SUSPECTED, stays.getFirst().confidence());
		assertFalse(stays.getFirst().required());
		assertTrue(stays.getFirst().id().startsWith("mixin-stub-bound:torrential.mixins.json:"), stays.getFirst().id());
		assertTrue(stays.getFirst().detail().contains("torrential$modifyFuelValues stays on "
				+ "net.minecraft.world.level.block.entity.FuelValues." + STUB_BURN), stays.getFirst().detail());
		CompatibilityFindings.reset();
		System.setProperty(MixinStubRebind.STUB_FINDING_PROPERTY, "off");
		assertEquals(0, MixinStubRebind.adapt(mixin, name -> fuel));
		assertTrue(CompatibilityFindings.all().isEmpty(), "its switch drops the finding and moves nothing");
		System.clearProperty(MixinStubRebind.STUB_FINDING_PROPERTY);

		MethodNode stub = fuel.methods.stream().filter(m -> STUB_BURN.equals(m.name + m.desc)).findFirst().orElseThrow();
		MethodNode body = fuel.methods.stream().filter(m -> BODY_BURN.equals(m.name + m.desc)).findFirst().orElseThrow();
		assertFalse(MixinRetarget.handlerFits(handler, MixinFit.injectorOf(handler), fuel, stub, body), "R1 agrees");

		System.setProperty(MixinStubRebind.CAPTURES_PROPERTY, "off");
		assertTrue(MixinRetarget.handlerFits(handler, MixinFit.injectorOf(handler), fuel, stub, body), "the A/B switch: R1 as before");
		assertEquals(1, MixinStubRebind.adapt(mixin, name -> fuel), "the A/B switch: the move that made MixinExtras reject it");
		assertEquals(List.of(BODY_BURN), selectors(mixin, "torrential$modifyFuelValues"));
		System.clearProperty(MixinStubRebind.CAPTURES_PROPERTY);

		ClassNode plain = synthetic("sircow/torrential/mixin/FuelValuesMixin", FUEL, "modify",
				"(" + FUEL_VALUES + ")" + FUEL_VALUES, true, injector(MODIFY_RETURN, STUB_BURN, List.of(at("RETURN"))));
		assertEquals(1, MixinStubRebind.adapt(plain, name -> fuel), "no capture: the move it always made");
		assertEquals(List.of(BODY_BURN), selectors(plain, "modify"));
		assertTrue(CompatibilityFindings.all().isEmpty(), "a handler that moves is nothing to report");
	}

	/** puzzleslib's break-speed modifier captures the state, which the stub passes through first: it still moves. */
	@Test void aCaptureTheStubPassesThroughInPlaceStillMoves() throws Exception {
		ClassNode player = merged("net/minecraft/world/entity/player/Player");
		ClassNode mixin = synthetic("fuzs/puzzleslib/fabric/mixin/PlayerFabricMixin", "net/minecraft/world/entity/player/Player",
				"getDestroySpeed", "(F" + STATE + ")F", false, injector(MODIFY_RETURN, "getDestroySpeed", List.of(at("TAIL"))));
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		assertEquals(1, MixinStubRebind.adapt(mixin, name -> player));
		assertEquals(List.of("getDestroySpeed(" + STATE + POS + ")F"), selectors(mixin, "getDestroySpeed"));

		ClassNode wrongPrefix = synthetic("fuzs/puzzleslib/fabric/mixin/PlayerFabricMixin", "net/minecraft/world/entity/player/Player",
				"getDestroySpeed", "(F" + POS + ")F", false, injector(MODIFY_RETURN, "getDestroySpeed", List.of(at("TAIL"))));
		assertEquals(0, MixinStubRebind.adapt(wrongPrefix, name -> player), "not a prefix of the stub's arguments");
	}

	/** A wrap whose trailing capture is an argument the stub consumes stays; the same wrap without it moves. */
	@Test void aWrapCapturingAnArgumentTheStubConsumesStays() throws Exception {
		ClassNode fuel = merged(FUEL);
		String add = "L" + FUEL + "$Builder;add(Lnet/minecraft/world/level/ItemLike;I)" + BUILDER;
		String wrap = "(" + BUILDER + "Lnet/minecraft/world/level/ItemLike;I" + OPERATION;
		ClassNode capturing = synthetic("com/example/FuelWrap", FUEL, "wrapAdd", wrap + PROVIDER + ")" + BUILDER, true,
				injector(WRAP_OPERATION, STUB_BURN, List.of(at("INVOKE", "target", add))));
		MixinStubRebind.noteEcosystem(capturing.name, Ecosystem.FABRIC);
		assertEquals(0, MixinStubRebind.adapt(capturing, name -> fuel));
		ClassNode plain = synthetic("com/example/FuelWrap", FUEL, "wrapAdd", wrap + ")" + BUILDER, true,
				injector(WRAP_OPERATION, STUB_BURN, List.of(at("INVOKE", "target", add))));
		assertEquals(1, MixinStubRebind.adapt(plain, name -> fuel));
		assertEquals(List.of(BODY_BURN), selectors(plain, "wrapAdd"));
	}

	/**
	 * Only MixinExtras sugar ends a handler's call part. A {@code @Coerce} receiver is the call's own, and the invisible
	 * {@code @NotNull} Kotlin puts on every handler parameter is nothing at all; read as the boundary, or as a reason to
	 * refuse, either left the injector on the stub, where its anchor is missing. R1 reads the same rule.
	 */
	@Test void aParameterAnnotationThatIsNotSugarDoesNotKeepTheInjectorOnTheStub() throws Exception {
		ClassNode fuel = merged(FUEL);
		MethodNode stub = fuel.methods.stream().filter(m -> STUB_BURN.equals(m.name + m.desc)).findFirst().orElseThrow();
		MethodNode body = fuel.methods.stream().filter(m -> BODY_BURN.equals(m.name + m.desc)).findFirst().orElseThrow();
		String add = "L" + FUEL + "$Builder;add(Lnet/minecraft/world/level/ItemLike;I)" + BUILDER;
		String wrap = "(" + BUILDER + "Lnet/minecraft/world/level/ItemLike;I" + OPERATION + ")" + BUILDER;
		for (String annotation : List.of(COERCE, NOT_NULL)) {
			ClassNode mixin = synthetic("com/example/FuelWrap", FUEL, "wrapAdd", wrap, true,
					injector(WRAP_OPERATION, STUB_BURN, List.of(at("INVOKE", "target", add))));
			MethodNode handler = annotate(mixin, annotation);
			MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
			assertTrue(MixinRetarget.handlerFits(handler, MixinFit.injectorOf(handler), fuel, stub, body), annotation + ": R1 agrees");
			assertEquals(1, MixinStubRebind.adapt(mixin, name -> fuel), annotation);
			assertEquals(List.of(BODY_BURN), selectors(mixin, "wrapAdd"), annotation);
		}

		ClassNode player = merged("net/minecraft/world/entity/player/Player");
		ClassNode inject = synthetic("com/example/BreakSpeed", "net/minecraft/world/entity/player/Player", "onSpeed",
				"(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V", false,
				injector(INJECT, "getDestroySpeed", List.of(at("RETURN"))));
		annotate(inject, NOT_NULL);
		MixinStubRebind.noteEcosystem(inject.name, Ecosystem.FABRIC);
		assertEquals(1, MixinStubRebind.adapt(inject, name -> player), "an @Inject whose callback Kotlin marked @NotNull");
		assertEquals(List.of("getDestroySpeed(" + STATE + POS + ")F"), selectors(inject, "onSpeed"));

		System.setProperty(MixinStubRebind.SUGAR_BOUNDARY_PROPERTY, "off");
		for (String annotation : List.of(COERCE, NOT_NULL)) {
			ClassNode mixin = synthetic("com/example/FuelWrap", FUEL, "wrapAdd", wrap, true,
					injector(WRAP_OPERATION, STUB_BURN, List.of(at("INVOKE", "target", add))));
			MethodNode handler = annotate(mixin, annotation);
			assertFalse(MixinRetarget.handlerFits(handler, MixinFit.injectorOf(handler), fuel, stub, body), annotation + ": R1 agrees");
			assertEquals(0, MixinStubRebind.adapt(mixin, name -> fuel), annotation + ": switched off, it stays on the stub again");
		}
		ClassNode offInject = synthetic("com/example/BreakSpeed", "net/minecraft/world/entity/player/Player", "onSpeed",
				"(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V", false,
				injector(INJECT, "getDestroySpeed", List.of(at("RETURN"))));
		annotate(offInject, NOT_NULL);
		assertEquals(0, MixinStubRebind.adapt(offInject, name -> player), "switched off, the @Inject stays too");
	}

	/** Marks the only handler's receiver {@code @Coerce} (visible), or every parameter {@code @NotNull} (invisible). */
	@SuppressWarnings("unchecked")
	private static MethodNode annotate(ClassNode mixin, String annotation) {
		MethodNode handler = mixin.methods.getFirst();
		int count = Type.getArgumentTypes(handler.desc).length;
		List<AnnotationNode>[] parameters = new List[count];
		for (int i = 0; i < count; i++) {
			if (i == 0 || annotation.equals(NOT_NULL)) parameters[i] = new java.util.ArrayList<>(List.of(new AnnotationNode(annotation)));
		}
		if (annotation.equals(COERCE)) handler.visibleParameterAnnotations = parameters;
		else handler.invisibleParameterAnnotations = parameters;
		return handler;
	}

	/** The injector's own part, per kind: the value, or the receiver and arguments of the access, or declined. */
	@Test void theContractsOwnSizeIsReadFromTheAccessOrDeclined() {
		MethodNode body = new MethodNode(Opcodes.ACC_STATIC, "m", "()V", null, null);
		body.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETFIELD, "p/O", "f", "I"));
		body.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETSTATIC, "p/O", "s", "I"));
		body.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.PUTFIELD, "p/O", "w", "I"));
		body.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.PUTSTATIC, "p/O", "t", "I"));
		body.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETFIELD, "p/O", "both", "I"));
		body.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.PUTFIELD, "p/O", "both", "I"));
		body.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "p/O", "st", "(IJ)V", false));
		body.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "p/O", "vi", "(I)V", false));
		String redirect = "Lorg/spongepowered/asm/mixin/injection/Redirect;";
		Type[] many = Type.getArgumentTypes("(IIIIIII)V");
		assertEquals(1, MixinStubRebind.intrinsicArity(injector(redirect, "m", at("FIELD", "target", "Lp/O;f:I")), many, 7, body));
		assertEquals(0, MixinStubRebind.intrinsicArity(injector(redirect, "m", at("FIELD", "target", "Lp/O;s:I")), many, 7, body));
		assertEquals(2, MixinStubRebind.intrinsicArity(injector(redirect, "m", at("FIELD", "target", "Lp/O;w:I")), many, 7, body));
		assertEquals(1, MixinStubRebind.intrinsicArity(injector(redirect, "m", at("FIELD", "target", "Lp/O;t:I")), many, 7, body));
		assertEquals(-1, MixinStubRebind.intrinsicArity(injector(redirect, "m", at("FIELD", "target", "Lp/O;both:I")), many, 7, body),
				"a read and a write: no one shape");
		assertEquals(2, MixinStubRebind.intrinsicArity(injector(redirect, "m", at("INVOKE", "target", "Lp/O;st(IJ)V")), many, 7, body));
		assertEquals(2, MixinStubRebind.intrinsicArity(injector(redirect, "m", at("INVOKE", "target", "Lp/O;vi(I)V")), many, 7, body));
		assertEquals(-1, MixinStubRebind.intrinsicArity(injector(redirect, "m", at("INVOKE", "target", "Lp/O;gone()V")), many, 7, body));
		assertEquals(3, MixinStubRebind.intrinsicArity(injector(redirect, "m", at("NEW", "target", "(IJI)Lp/T;")), many, 7, body));
		assertEquals(-1, MixinStubRebind.intrinsicArity(injector(redirect, "m", at("NEW", "target", "Lp/T;")), many, 7, body));
		assertEquals(1, MixinStubRebind.intrinsicArity(injector(MODIFY_RETURN, "m", at("RETURN")), many, 7, body));
		assertEquals(-1, MixinStubRebind.intrinsicArity(injector(WRAP_OPERATION, "m", at("INVOKE", "target", "Lp/O;vi(I)V")), many, 7, body),
				"a wrap whose third parameter is not the Operation");
		Type[] wrapped = Type.getArgumentTypes("(Lp/O;I" + OPERATION + "J)V");
		assertEquals(3, MixinStubRebind.intrinsicArity(injector(WRAP_OPERATION, "m", at("INVOKE", "target", "Lp/O;vi(I)V")), wrapped, 4, body));
	}

	// --- @ModifyVariable by name ---

	/** torrential's Conduit Power bonus: `speed` is only in the body, stored first right after the tool's speed. */
	@Test void aModifyVariableByNameMovesToTheBodyThatHasTheLocal() throws Exception {
		ClassNode player = merged("net/minecraft/world/entity/player/Player");
		ClassNode mixin = conduit("(F" + STATE + ")F", "speed", 0);
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		assertEquals("getDestroySpeed", MixinStubRebind.destination(mixin, mixin.methods.getFirst(), player).name);
		assertEquals(1, MixinStubRebind.adapt(mixin, name -> player));
		assertEquals(List.of("getDestroySpeed(" + STATE + POS + ")F"), selectors(mixin, "torrential$applyConduitModifier"));
		assertEquals(0, MixinStubRebind.adapt(mixin, name -> player), "a second pass changes nothing");

		ClassNode noTable = new ClassNode();
		try (ZipFile zip = new ZipFile(MERGED.toFile())) {
			new ClassReader(zip.getInputStream(zip.getEntry("net/minecraft/world/entity/player/Player.class")).readAllBytes())
					.accept(noTable, ClassReader.SKIP_DEBUG);
		}
		ClassNode again = conduit("(F" + STATE + ")F", "speed", 0);
		assertNull(MixinStubRebind.destination(again, again.methods.getFirst(), noTable),
				"no local variable table, no proof (MixinFit re-reads with it)");
	}

	@Test void aModifyVariableIsLeftWhereNothingProvesTheLocal() throws Exception {
		String[] why = { "by ordinal, not by name", "a name the body does not have", "the name in another type",
				"the name in two slots", "a capture that is not the stub's first argument", "an ordinal past the body's stores",
				"the switch", "a NeoForge mod's mixin" };
		for (int mode = 0; mode < why.length; mode++) {
			ClassNode player = merged("net/minecraft/world/entity/player/Player");
			ClassNode mixin = switch (mode) {
				case 1 -> conduit("(F" + STATE + ")F", "velocity", 0);
				case 2 -> conduit("(I" + STATE + ")I", "speed", 0);
				case 4 -> conduit("(F" + POS + ")F", "speed", 0);
				case 5 -> conduit("(F" + STATE + ")F", "speed", 40);
				default -> conduit("(F" + STATE + ")F", "speed", 0);
			};
			AnnotationNode injector = MixinFit.injectorOf(mixin.methods.getFirst());
			if (mode == 0) {
				injector.values.set(injector.values.indexOf("name"), "ordinal");
				injector.values.set(injector.values.indexOf("ordinal") + 1, 0);
			}
			if (mode == 3) {
				MethodNode body = player.methods.stream().filter(m -> m.name.equals("getDestroySpeed") && m.desc.equals("(" + STATE + POS + ")F"))
						.findFirst().orElseThrow();
				org.objectweb.asm.tree.LocalVariableNode speed = body.localVariables.stream().filter(l -> l.name.equals("speed")).findFirst().orElseThrow();
				body.localVariables.add(new org.objectweb.asm.tree.LocalVariableNode("speed", "F", null, speed.start, speed.end, 9));
			}
			if (mode == 6) System.setProperty(MixinStubRebind.MODIFY_VARIABLE_PROPERTY, "off");
			MixinStubRebind.noteEcosystem(mixin.name, mode == 7 ? Ecosystem.NEOFORGE : Ecosystem.FABRIC);
			assertEquals(0, MixinStubRebind.adapt(mixin, name -> player), why[mode]);
			assertEquals(List.of("getDestroySpeed"), selectors(mixin, "torrential$applyConduitModifier"), why[mode]);
			System.clearProperty(MixinStubRebind.MODIFY_VARIABLE_PROPERTY);
		}
	}

	private static ClassNode conduit(String desc, String local, int ordinal) {
		AnnotationNode injector = injector(MODIFY_VARIABLE, "getDestroySpeed", at("STORE", "ordinal", ordinal));
		injector.values.addAll(List.of("name", new java.util.ArrayList<>(List.of(local))));
		return synthetic("sircow/torrential/mixin/FabricPlayerMixin", "net/minecraft/world/entity/player/Player",
				"torrential$applyConduitModifier", desc, false, injector);
	}

	private static ClassNode synthetic(String name, String target, String handlerName, String desc, boolean isStatic, AnnotationNode injector) {
		ClassNode mixin = new ClassNode();
		mixin.version = Opcodes.V21;
		mixin.name = name;
		mixin.superName = "java/lang/Object";
		AnnotationNode type = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		type.values = new java.util.ArrayList<>(List.of("value", new java.util.ArrayList<>(List.of(Type.getObjectType(target)))));
		mixin.invisibleAnnotations = new java.util.ArrayList<>(List.of(type));
		MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE | (isStatic ? Opcodes.ACC_STATIC : 0), handlerName, desc, null, null);
		handler.visibleAnnotations = new java.util.ArrayList<>(List.of(injector));
		mixin.methods = new java.util.ArrayList<>(List.of(handler));
		return mixin;
	}

	private static AnnotationNode injector(String desc, String selector, Object at) {
		AnnotationNode injector = new AnnotationNode(desc);
		injector.values = new java.util.ArrayList<>(List.of("method", new java.util.ArrayList<>(List.of(selector)), "at", at));
		return injector;
	}

	private static AnnotationNode at(String value, Object... more) {
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new java.util.ArrayList<>(List.of("value", value));
		at.values.addAll(List.of(more));
		return at;
	}

	private static List<String> selectors(ClassNode mixin, String handler) {
		return MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(mixin.methods.stream()
				.filter(m -> m.name.equals(handler)).findFirst().orElseThrow()), "method"));
	}

	private static ClassNode merged(String name) throws Exception {
		Assumptions.assumeTrue(Files.isRegularFile(MERGED), "actual game required");
		try (ZipFile zip = new ZipFile(MERGED.toFile())) {
			ClassNode node = new ClassNode();
			new ClassReader(zip.getInputStream(zip.getEntry(name + ".class")).readAllBytes()).accept(node, 0);
			return node;
		}
	}

	private static ClassNode fromJar(Path jar, String name) throws Exception {
		Assumptions.assumeTrue(Files.isRegularFile(jar), jar + " absent");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry(name + ".class");
			assertNotNull(entry, name);
			return MixinFit.parse(zip.getInputStream(entry).readAllBytes());
		}
	}
}
