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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.ForbricLog;

/**
 * Repairs class-local bytecode invariants that can drift when two patched Minecraft bases are merged.
 */
public final class ForbricMergedBaseCompatTransformer implements ClassTransformer {
	/**
	 * Reads another class's bytes, for the one repair that has to look up the superclass chain. Null when the
	 * transformer was built without one, in which case that repair stands down rather than guessing.
	 */
	private final java.util.function.Function<String, byte[]> classBytes;

	/** Without a resolver: every repair except the shadowing-override one, which needs to read other classes. */
	public ForbricMergedBaseCompatTransformer() {
		this(null);
	}

	public ForbricMergedBaseCompatTransformer(java.util.function.Function<String, byte[]> classBytes) {
		this.classBytes = classBytes;
	}

	@Override
	public String name() {
		return "forbric-merged-base-compat";
	}

	@Override
	public AnchorSet anchors() {
		// Independent repairs behind one `changed` flag -- dungeon generation, key mappings, the particle map,
		// default attributes, the save on teardown. Each one can stop applying on its own, and a single
		// class-level answer cannot see that. This is the largest reservoir of the failure this mechanism
		// exists for, and it needs one claim per repair rather than one anchor per class.
		//
		// COUNTED, never written down. This sentence said "47" and the comment above it said "Forty" while
		// REPAIRS held 49: two self-descriptions that drifted because nothing compared them to anything, in
		// the one class whose entire job is that a silent change gets noticed. The list is the number.
		return AnchorSet.scanned(REPAIRS.size() + " independent repairs across the whole base, each needing its own claim");
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		return transform(className, classBytes, context, ClaimReporter.NONE);
	}

	/** The repairs {@link #transform} runs, in its order; a test pins the two lists against each other. */
	static final List<String> REPAIRS = List.of("repairLambdaBootstrapHandles", "addBlockStateModelConflictResolvers", "addBlockStateAppearanceResolver", "routeKeyMappingClickToPopulatedLookup", "giveKeyMappingItsVanillaMap", "giveTheVanillaParticleMapAViewOfTheLiveOne", "letDungeonsGenerateWithoutTheDataMap", "restoreDoublePrecisionToTheRandomSources", "convertRadiansWithVanillasFoldedConstant", "callVanillasWriteByteAgain", "saveTheHeightmapsVanillaSaves", "guardNeoForgesWorldModifierPass", "letForeignResourceConditionsThrough", "letFabricResourceConditionsDecide", "translateAGuestsPrivateSkipMarker", "nameTheReloadListenersNeoForgeRefusesToName", "dropInterfaceDefaultShadowingOverrides", "tolerateEmptyCreativeTabStacks", "bridgeOrphanedPipRenderers", "dropTheWindowTitlesLoaderBrand", "keepTheSaveOffTheTeardownsFailurePath", "askNeoForgeWhatAnItemsAttributesAre", "giveTheUnwrittenLoggerAValue", "dropStubsThatBypassARealSuperclassMethod", "inlineTheSwitchMapTheMergeLost", "vetoUnjudgeableOverlayConditions", "hideTheLegacyLootModifierIndexFromTheDirectoryScan", "letModdedFeatureFlagsRegister", "dropTheKeyModifierSuffixBeforeParsingAKeyName", "letTheAtlasLowerItsMipLevelLikeVanilla", "wrapTheStreamsVanillaWraps", "returnFromANestedBootstrapBeforeItsTail");

	private static final String NEO_EVENT_HOOKS_BINARY = "net.neoforged.neoforge.event.EventHooks";

	/**
	 * One claim per repair, in {@link #REPAIRS} order. A repair with one fixed target declares it REQUIRED with
	 * the cost of its silence; one that scans by shape declares {@link AnchorSet#scanned}. Client-only targets
	 * are simply never loaded on a dedicated server, which the ledger reports as absent, not missed.
	 */
	@Override
	public List<Claim> claims() {
		List<Claim> out = new ArrayList<>();
		out.add(scanned("repairLambdaBootstrapHandles", "any class whose invokedynamic still names the old loader's hook owners"));
		out.add(fixed("addBlockStateModelConflictResolvers", "net/minecraft/client/renderer/block/dispatch/BlockStateModel",
				"every block model's geometry key and conflict resolver are gone — the merged BlockStateModel lacks the methods both families call"));
		out.add(scanned("addBlockStateAppearanceResolver",
				"net.minecraft.world.level.block.Block and ...block.state.BlockState, which each inherit "
						+ "getAppearance as a default from BOTH NeoForge and fabric-api and declare neither, so the "
						+ "first mod to ask a neighbour what it looks like — any connected-texture mod — dies on "
						+ "IncompatibleClassChangeError mid-frame"));
		out.add(fixed("routeKeyMappingClickToPopulatedLookup", KEY_MAPPING,
				"key presses are looked up in the lookup registration never populated — MinecraftForge mods' keys never fire"));
		out.add(fixed("giveKeyMappingItsVanillaMap", KEY_MAPPING,
				"KeyMapping has no vanilla-typed MAP — a mod reading it as a Map dies on NoSuchFieldError (LiquidBounce, on a key press)"));
		out.add(fixed("giveTheVanillaParticleMapAViewOfTheLiveOne", PARTICLE_RESOURCES,
				"the vanilla-typed particle provider map stays empty — particles registered the vanilla way never render"));
		out.add(fixed("letDungeonsGenerateWithoutTheDataMap", MONSTER_ROOM_FEATURE,
				"monster rooms never generate — the NeoForge data map they ask has no vanilla fallback"));
		out.add(randomSourcePrecisionEnabled()
				? new Claim(claimId("restoreDoublePrecisionToTheRandomSources"), AnchorSet.of(
						new AnchorSet.Anchor(XOROSHIRO_RANDOM_SOURCE.replace('/', '.'), AnchorSet.Severity.REQUIRED,
								"every noise octave's origin is off — the merged nextDouble() rounds through float, so no "
										+ "world generates the way the same seed does in vanilla"),
						new AnchorSet.Anchor(BIT_RANDOM_SOURCE.replace('/', '.'), AnchorSet.Severity.REQUIRED,
								"WorldgenRandom's nextDouble() rounds through float and can return exactly 1.0 — out of "
										+ "the [0,1) range every caller assumes")))
				: scanned("restoreDoublePrecisionToTheRandomSources", "-D" + RANDOM_PRECISION_PROPERTY + "=off"));
		out.add(fixed("convertRadiansWithVanillasFoldedConstant", "net/minecraft/world/entity/Entity",
				"every angle the game computes from a vector is off in the eighth digit — the merged base divides by "
						+ "pi at run time where vanilla multiplies by a constant it folded in float"));
		out.add(vanillaWriteByteEnabled()
				? fixed("callVanillasWriteByteAgain", PLAYER_ABILITIES_PACKET,
						"a packet writes its byte through NeoForge's writeByte(byte), so a mixin on vanilla's writeByte(int) "
								+ "there binds nothing — ViaFabricPlus' old-protocol ability flags, a required injector")
				: scanned("callVanillasWriteByteAgain", "-D" + VANILLA_WRITE_BYTE_PROPERTY + "=off"));
		out.add(savedHeightmapsEnabled()
				? fixed("saveTheHeightmapsVanillaSaves", CHUNK_STATUS,
						"an unfinished chunk is saved with the two worldgen heightmaps vanilla never persists, and "
								+ "reloads with them stale — a feature placed on WORLD_SURFACE_WG then lands somewhere "
								+ "vanilla would not put it")
				: scanned("saveTheHeightmapsVanillaSaves", "-D" + SAVED_HEIGHTMAPS_PROPERTY + "=off"));
		out.add(fixed("guardNeoForgesWorldModifierPass", NEO_SERVER_LIFECYCLE_HOOKS,
				"NeoForge's biome/structure modifier pass is neutered — every neoforge:biome_modifier does nothing"));
		out.add(fixed("letForeignResourceConditionsThrough", ICONDITION,
				"another ecosystem's condition type fails NeoForge's evaluator and the whole registry load with it"));
		out.add(fixed("letFabricResourceConditionsDecide", CONDITIONAL_OPS,
				"fabric:load_conditions has no evaluator — a Fabric mod's conditional data files all load"));
		out.add(fixed("translateAGuestsPrivateSkipMarker", JSON_RELOAD_LISTENER,
				"fabric-api's skip marker reaches the merged reader's cast — the datapack load dies (\"can't proceed with server load\")"));
		out.add(fixed("nameTheReloadListenersNeoForgeRefusesToName", ADD_CLIENT_RELOAD_LISTENERS,
				"a Fabric mod's client reload listener kills the client — NeoForge refuses to name it"));
		out.add(scanned("dropInterfaceDefaultShadowingOverrides", "native-absent delegates with one proved current interface default"));
		out.add(fixed("tolerateEmptyCreativeTabStacks", NEO_EVENT_HOOKS_BINARY.replace('.', '/'),
				"one empty stack from any mod aborts the whole creative menu"));
		out.add(fixed("bridgeOrphanedPipRenderers", GUI_RENDERER,
				"a picture-in-picture renderer registered the vanilla way never draws"));
		out.add(fixed("dropTheWindowTitlesLoaderBrand", "net/minecraft/client/Minecraft",
				"the window title carries another loader's brand"));
		out.add(fixed("keepTheSaveOffTheTeardownsFailurePath", INTEGRATED_SERVER,
				"a throw in IntegratedServer.teardownPublishedState costs the world save"));
		out.add(fixed("askNeoForgeWhatAnItemsAttributesAre", ITEM_STACK,
				"an item's attributes are read off the raw component — elytra flight and every NeoForge attribute modifier stop working"));
		out.add(scanned("giveTheUnwrittenLoggerAValue", "any class with a static final Logger the merge left unassigned"));
		out.add(scanned("dropStubsThatBypassARealSuperclassMethod", "native-absent delegates with a proved public current superclass implementation"));
		out.add(scanned("inlineTheSwitchMapTheMergeLost", "absent compiler map fields whose actual native initialization proves each enum case"));
		out.add(fixed("vetoUnjudgeableOverlayConditions", OVERLAY_ENTRY,
				"a pack.mcmeta overlay gated by a condition no evaluator here can judge is mounted anyway"));
		out.add(fixed("hideTheLegacyLootModifierIndexFromTheDirectoryScan", LOOT_MODIFIER_MANAGER_NEO,
				"NeoForge's loot-modifier manager parse-fails MinecraftForge's legacy index file on every reload"));
		out.add(fixed("letModdedFeatureFlagsRegister", FEATURE_FLAGS,
				"NeoForge mods' declared feature flags are never registered — a mod asking for its own flag dies in its static "
						+ "initialiser and its datapack then fails the whole registry load"));
		// Both of these are switched off by their own property, and a claim that stays REQUIRED while its repair is
		// off reports the switch as a broken anchor. The lesson is J11's: a conditional repair declares a
		// conditional claim, or the census stops meaning what it says.
		out.add(keyModifierSuffixEnabled()
				? fixed("dropTheKeyModifierSuffixBeforeParsingAKeyName", INPUT_CONSTANTS,
						"one modded key bound with a modifier throws out of options.txt parsing — the player loses EVERY setting")
				: scanned("dropTheKeyModifierSuffixBeforeParsingAKeyName", "-D" + KEY_SUFFIX_PROPERTY + "=off"));
		// The repair itself is structural and runs wherever its proof holds; the anchor is only the ledger's
		// account of the one platform class that MUST be bounded. Without it a proof that rejects the real
		// SpriteLoader is exactly the old failure: a permanent black screen, and nothing in the log saying why.
		out.add(mipmapLoweringEnabled()
				? fixed("letTheAtlasLowerItsMipLevelLikeVanilla", SPRITE_LOADER,
						"an atlas holding a sprite smaller than the mip level allows fails to upload — the FIRST resource "
								+ "reload dies, every pack is dropped, and the client sits on a black screen with no further log")
				: scanned("letTheAtlasLowerItsMipLevelLikeVanilla", "-D" + MIPMAP_PROPERTY + "=off"));
		out.add(fixed("wrapTheStreamsVanillaWraps", BOOTSTRAP,
				"System.out and System.err are never routed into log4j, so every line a mod PRINTS rather than logs "
						+ "is absent from latest.log — including the debug output a mod is told to turn on when it "
						+ "misbehaves"));
		out.add(fixed("returnFromANestedBootstrapBeforeItsTail", BOOTSTRAP,
				"MinecraftForge's ForgeRegistries re-enters Bootstrap.bootStrap() from inside the first one, so every "
						+ "mixin at its TAIL runs twice, the first time half-way through bootstrap — a Fabric mod that "
						+ "initialises there once (cristellib) throws and the server does not start"));
		return List.copyOf(out);
	}

	private Claim fixed(String repair, String internalTarget, String cost) {
		return new Claim(claimId(repair), AnchorSet.of(new AnchorSet.Anchor(internalTarget.replace('/', '.'), AnchorSet.Severity.REQUIRED, cost)));
	}

	private Claim scanned(String repair, String why) {
		return new Claim(claimId(repair), AnchorSet.scanned(why));
	}


	/** Reports {@code id} as applied when {@code applied}; the repair's own answer is returned unchanged. */
	private boolean claim(ClaimReporter reporter, String id, boolean applied) {
		if (applied) reporter.hit(claimId(id));
		return applied;
	}
	private boolean claim(ClaimReporter reporter, String id, boolean changed, boolean satisfied) {
		if (changed || satisfied) reporter.hit(claimId(id));
		return changed;
	}

	private String claimId(String repair) {
		return name() + "#" + repair;
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context, ClaimReporter reporter) {
		if (classBytes == null || classBytes.length == 0) return classBytes;
		try {
			boolean namedOldLoader = stillNamesTheOldLoader(classBytes);
			ClassNode node = new ClassNode();
			new ClassReader(classBytes).accept(node, 0);
			boolean changed = false;
			changed |= claim(reporter, "repairLambdaBootstrapHandles", repairLambdaBootstrapHandles(node));
			changed |= claim(reporter, "addBlockStateModelConflictResolvers", addBlockStateModelConflictResolvers(node));
			changed |= claim(reporter, "addBlockStateAppearanceResolver", addBlockStateAppearanceResolver(node));
			changed |= claim(reporter, "routeKeyMappingClickToPopulatedLookup", routeKeyMappingClickToPopulatedLookup(node));
			changed |= claim(reporter, "giveKeyMappingItsVanillaMap", giveKeyMappingItsVanillaMap(node));
			changed |= claim(reporter, "giveTheVanillaParticleMapAViewOfTheLiveOne", giveTheVanillaParticleMapAViewOfTheLiveOne(node));
			changed |= claim(reporter, "letDungeonsGenerateWithoutTheDataMap", letDungeonsGenerateWithoutTheDataMap(node));
			changed |= claim(reporter, "restoreDoublePrecisionToTheRandomSources", restoreDoublePrecisionToTheRandomSources(node));
			changed |= claim(reporter, "convertRadiansWithVanillasFoldedConstant", convertRadiansWithVanillasFoldedConstant(node));
			changed |= claim(reporter, "callVanillasWriteByteAgain", callVanillasWriteByteAgain(node));
			changed |= claim(reporter, "saveTheHeightmapsVanillaSaves", saveTheHeightmapsVanillaSaves(node));
			changed |= claim(reporter, "guardNeoForgesWorldModifierPass", guardNeoForgesWorldModifierPass(node));
			changed |= claim(reporter, "letForeignResourceConditionsThrough", letForeignResourceConditionsThrough(node));
			changed |= claim(reporter, "letFabricResourceConditionsDecide", letFabricResourceConditionsDecide(node));
			changed |= claim(reporter, "translateAGuestsPrivateSkipMarker", translateAGuestsPrivateSkipMarker(node));
			changed |= claim(reporter, "nameTheReloadListenersNeoForgeRefusesToName", nameTheReloadListenersNeoForgeRefusesToName(node));
			changed |= claim(reporter, "dropInterfaceDefaultShadowingOverrides", dropInterfaceDefaultShadowingOverrides(node));
			changed |= claim(reporter, "tolerateEmptyCreativeTabStacks", tolerateEmptyCreativeTabStacks(node));
			changed |= claim(reporter, "bridgeOrphanedPipRenderers", bridgeOrphanedPipRenderers(node));
			changed |= claim(reporter, "dropTheWindowTitlesLoaderBrand", dropTheWindowTitlesLoaderBrand(node));
			changed |= claim(reporter, "keepTheSaveOffTheTeardownsFailurePath", keepTheSaveOffTheTeardownsFailurePath(node));
			changed |= claim(reporter, "askNeoForgeWhatAnItemsAttributesAre", askNeoForgeWhatAnItemsAttributesAre(node), attributesAlreadyComputed(node));
			changed |= claim(reporter, "giveTheUnwrittenLoggerAValue", giveTheUnwrittenLoggerAValue(node));
			changed |= claim(reporter, "dropStubsThatBypassARealSuperclassMethod", dropStubsThatBypassARealSuperclassMethod(node));
			changed |= claim(reporter, "inlineTheSwitchMapTheMergeLost", inlineTheSwitchMapTheMergeLost(node));
			changed |= claim(reporter, "vetoUnjudgeableOverlayConditions", vetoUnjudgeableOverlayConditions(node));
			changed |= claim(reporter, "hideTheLegacyLootModifierIndexFromTheDirectoryScan", hideTheLegacyLootModifierIndexFromTheDirectoryScan(node));
			changed |= claim(reporter, "letModdedFeatureFlagsRegister", letModdedFeatureFlagsRegister(node));
			changed |= claim(reporter, "dropTheKeyModifierSuffixBeforeParsingAKeyName",
					dropTheKeyModifierSuffixBeforeParsingAKeyName(node));
			changed |= claim(reporter, "letTheAtlasLowerItsMipLevelLikeVanilla",
					letTheAtlasLowerItsMipLevelLikeVanilla(node));
			changed |= claim(reporter, "wrapTheStreamsVanillaWraps", wrapTheStreamsVanillaWraps(node));
			changed |= claim(reporter, "returnFromANestedBootstrapBeforeItsTail", returnFromANestedBootstrapBeforeItsTail(node));
			changed |= namedOldLoader && adoptInteropHooksTheBaseStillNamesAfterTheOldLoader(node);

			byte[] result = classBytes;
			if (changed) {
				ClassWriter writer = new ClassWriter(0);
				node.accept(writer);
				result = writer.toByteArray();
			}
			if (namedOldLoader && stillNamesTheOldLoader(result)) {
				// Not fatal here, but it WILL be at link time, in a stack that points at the game rather than at
				// this transformer. Name it while the cause is still legible.
				ForbricLog.error("[Forbric/MergedBaseCompat] %s still names %s after adoption — a reference shape "
								+ "this pass does not rewrite. It will fail to link.",
						className, LEGACY_INTEROP_PACKAGE.replace('/', '.'));
			}
			return result;
		} catch (RuntimeException e) {
			Caught.warn(e, "[Forbric/MergedBaseCompat] could not inspect %s", className);
			return classBytes;
		}
	}

	/**
	 * Rewrites calls the merged base makes to the kernel's reflective interop hooks under their OLD owner names.
	 *
	 * <p>The merged base is built by the previous-generation loader's {@code MergedBaseBuilder}, which splices an
	 * {@code INVOKESTATIC net/forbric/loader/impl/compat/ForbricCustomPayloadInterop.findCodec} into the merged
	 * {@code CustomPacketPayload} codec provider. Those three helper classes now live in the kernel
	 * ({@code net.forbric.kernel.interop}) and the old loader jars are no longer on the boot classpath, so the
	 * baked-in owner names no longer resolve — the symptom is a {@code NoClassDefFoundError} inside the netty
	 * encoder the moment anything sends a custom payload, i.e. every world join.
	 *
	 * <p>This is a permanent adaptation, not a one-off migration step: the base-building pipeline belongs to the
	 * other repository and keeps emitting the names it knows. The kernel owns what its own base links against, so
	 * it retargets them here rather than requiring a 35 MB artifact to be rebuilt in lockstep.
	 *
	 * <p>The only shape the builder emits is a method owner. Anything else carrying the legacy prefix — a field
	 * owner, a {@code new}, a class constant — would survive this pass and fail at link time far away from here,
	 * so {@link #stillNamesTheOldLoader} re-reads the finished bytes and says so out loud.
	 */
	private static boolean adoptInteropHooksTheBaseStillNamesAfterTheOldLoader(ClassNode node) {
		boolean changed = false;
		for (MethodNode method : node.methods) {
			if (method.instructions == null) continue;
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof MethodInsnNode call)) continue;
				String adopted = LEGACY_INTEROP_OWNERS.get(call.owner);
				if (adopted == null) continue;
				call.owner = adopted;
				changed = true;
			}
		}
		if (changed) {
			ForbricLog.warn("[Forbric/MergedBaseCompat] adopted old-loader interop hooks named by %s",
					node.name.replace('/', '.'));
		}
		return changed;
	}

	/** True if {@code classBytes} still mentions the old loader's package anywhere — a link error waiting to happen. */
	static boolean stillNamesTheOldLoader(byte[] classBytes) {
		byte[] needle = LEGACY_INTEROP_PACKAGE.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		outer:
		for (int i = 0; i + needle.length <= classBytes.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (classBytes[i + j] != needle[j]) continue outer;
			}
			return true;
		}
		return false;
	}

	private static final String LEGACY_INTEROP_PACKAGE = "net/forbric/loader/impl/";

	private static final String KEY_MAPPING = "net/minecraft/client/KeyMapping";

	private static final String PARTICLE_RESOURCES = "net/minecraft/client/particle/ParticleResources";


	private static final String KERNEL_NEO_WORLDGEN = "net/forbric/kernel/runtime/KernelNeoWorldgen";
	private static final String MONSTER_ROOM_FEATURE = "net/minecraft/world/level/levelgen/feature/MonsterRoomFeature";
	private static final String MONSTER_ROOM_HOOKS = "net/neoforged/neoforge/common/MonsterRoomHooks";
	private static final String RANDOM_MONSTER_ROOM_MOB =
			"(Lnet/minecraft/util/RandomSource;)Lnet/minecraft/world/entity/EntityType;";
	private static final String NEO_SERVER_LIFECYCLE_HOOKS = "net/neoforged/neoforge/server/ServerLifecycleHooks";
	private static final String RUN_MODIFIERS = "(Lnet/minecraft/server/MinecraftServer;)V";

	private static final String ICONDITION = ForeignType.ICONDITION.internal(Ecosystem.NEOFORGE);
	private static final String CODEC_DESC = "Lcom/mojang/serialization/Codec;";
	private static final String KERNEL_NEO_CONDITIONS = "net/forbric/kernel/runtime/KernelNeoConditions";
	static final String CODEC_TO_CODEC = "(Lcom/mojang/serialization/Codec;)Lcom/mojang/serialization/Codec;";
	private static final String BOOTSTRAP = "net/minecraft/server/Bootstrap";
	private static final String JSON_RELOAD_LISTENER = "net/minecraft/server/packs/resources/SimpleJsonResourceReloadListener";
	private static final String DATA_RESULT = "Lcom/mojang/serialization/DataResult;";

	private static final String CONDITIONAL_OPS = "net/neoforged/neoforge/common/conditions/ConditionalOps";
	private static final String CONDITIONAL_FACTORY =
			"(Lcom/mojang/serialization/Codec;Ljava/lang/String;)Lcom/mojang/serialization/Codec;";
	private static final String KERNEL_FABRIC_CONDITIONS = "net/forbric/kernel/runtime/KernelFabricConditions";
	private static final String CODEC = "Lcom/mojang/serialization/Codec;";

	private static final String ADD_CLIENT_RELOAD_LISTENERS =
			"net/neoforged/neoforge/client/event/AddClientReloadListenersEvent";
	private static final String VANILLA_CLIENT_LISTENERS =
			"net/neoforged/neoforge/client/resources/VanillaClientListeners";
	private static final String NAME_FOR_CLASS =
			"(Ljava/lang/Class;)Lnet/minecraft/resources/Identifier;";
	private static final String KERNEL_RELOAD_NAMES = "net/forbric/kernel/runtime/KernelClientReloadNames";
	/** {@code org.slf4j.Logger}, the one unwritten static the merge leaves that has an obvious correct value. */
	private static final String LOGGER_DESC = "Lorg/slf4j/Logger;";
	private static final String NAME_KEYED = "Ljava/util/Map;";
	/** Vanilla's own descriptor for it, and the one fabric-api reads. */
	private static final String ID_KEYED = "Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;";
	private static final String KERNEL_PARTICLES = "net/forbric/kernel/runtime/KernelParticleProviders";
	private static final String KERNEL_KEY_MAPPING_MAP = "net/forbric/kernel/runtime/KernelKeyMappingMap";
	/** Old owner → the kernel class that now carries the method, for hooks the merged base still names. */
	private static final Map<String, String> LEGACY_INTEROP_OWNERS = Map.of(
			"net/forbric/loader/impl/compat/ForbricCustomPayloadInterop", "net/forbric/kernel/interop/PayloadInterop",
			"net/forbric/loader/impl/forge/runtime/ForbricClientShutdown", "net/forbric/kernel/interop/ClientShutdown");

	private static boolean repairLambdaBootstrapHandles(ClassNode node) {
		Map<String, MethodNode> methods = new HashMap<>();
		for (MethodNode method : node.methods) {
			methods.put(method.name + method.desc, method);
		}

		boolean changed = false;
		for (MethodNode caller : node.methods) {
			for (AbstractInsnNode insn = caller.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof InvokeDynamicInsnNode indy) || indy.bsmArgs == null) continue;
				for (int i = 0; i < indy.bsmArgs.length; i++) {
					if (!(indy.bsmArgs[i] instanceof Handle handle)) continue;
					Handle repaired = repairLambdaHandle(node, methods, caller, indy, handle);
					if (repaired == handle) continue;
					indy.bsmArgs[i] = repaired;
					changed = true;
				}
			}
		}
		return changed;
	}

	/**
	 * Gives merged {@code BlockState} its own {@code getAppearance}, because it inherits TWO.
	 *
	 * <p>The merged class declares {@code IBlockStateExtension} (NeoForge) and {@code IForgeBlockState}
	 * (MinecraftForge); fabric-api's mixin then adds {@code FabricBlockState}. NeoForge's and Fabric's both
	 * carry a {@code default getAppearance} with a byte-identical descriptor, neither overrides the other, and
	 * the class declares nothing — so the JVM refuses to choose and the FIRST caller dies:
	 * <pre>
	 * java.lang.IncompatibleClassChangeError: Conflicting default methods:
	 *   net/neoforged/neoforge/common/extensions/IBlockStateExtension.getAppearance
	 *   net/fabricmc/fabric/api/block/v1/FabricBlockState.getAppearance
	 *   at BlockState.getAppearance
	 *   at me.pepperbell.continuity.client.model.CtmBlockStateModel.emitQuads
	 * </pre>
	 * Measured on the reporting instance the moment connected textures were switched on — Continuity is a
	 * connected-texture mod, so asking a neighbour what it LOOKS like is the one thing it does, and nothing else
	 * in a 28-mod pack had ever called this method. On either loader alone only one default exists and the
	 * conflict cannot arise.
	 *
	 * <p>The body is written out rather than delegated to one side, because neither side is a choice: both
	 * defaults are {@code this.getBlock().getAppearance(this, level, pos, direction, queryState, queryPos)},
	 * differing only in how they obtain {@code this} (NeoForge through {@code self()}, Fabric through a
	 * {@code checkcast}). Writing it directly also means the resolver does not depend on which of the two
	 * interfaces is present at transform time — and fabric-api's is NOT, since a mixin adds it later.
	 */
	/**
	 * The same conflict one level down, on {@code Block} — and the level that actually crashed.
	 *
	 * <p>Giving {@code BlockState} its own {@code getAppearance} was correct and it works: it resolves and
	 * delegates to {@code getBlock().getAppearance(...)}. That delegate is where the SECOND copy of the same
	 * defect lives. {@code Block} declares {@code IBlockExtension} (NeoForge) and {@code IForgeBlock}
	 * (MinecraftForge); fabric-api's mixin adds {@code FabricBlock}; NeoForge's and Fabric's both default
	 * {@code getAppearance} with the same descriptor and {@code Block} declares neither, so every subclass that
	 * does not override it inherits two defaults:
	 * <pre>
	 * java.lang.IncompatibleClassChangeError: Conflicting default methods:
	 *   net/neoforged/neoforge/common/extensions/IBlockExtension.getAppearance
	 *   net/fabricmc/fabric/api/block/v1/FabricBlock.getAppearance
	 *   at MudBlock.getAppearance
	 *   at BlockState.getAppearance   &lt;- the first repair, working
	 * </pre>
	 * Measured on the reporting instance the day after the first half shipped. Fixing one frame of a crash and
	 * not asking whether the frame below it has the same shape is what made this two crashes instead of one.
	 *
	 * <p>The family is now closed rather than patched twice. Census of the two interface pairs on this carrier:
	 * {@code IBlockExtension} declares 64 defaults and {@code FabricBlock} 2; {@code IBlockStateExtension} 61
	 * and {@code FabricBlockState} 2; the ONLY name declared default by both sides, in either pair, is
	 * {@code getAppearance}. There is no third one waiting.
	 *
	 * <p>Both defaults here are literally {@code aload_1; areturn} — return the state you were asked about — so
	 * again there is no side to choose, and writing the body out keeps the resolver independent of which
	 * interface is present when the transformer runs.
	 */
	/**
	 * Restores {@code Bootstrap.bootStrap()}'s call to its own {@code wrapStreams()}, which routes
	 * {@code System.out}/{@code System.err} into log4j.
	 *
	 * <p>Vanilla calls it as the last thing bootstrap does. NeoForge's patch spends that exact slot on
	 * {@code GameData.vanillaSnapshot()} instead, and the byte merge kept NeoForge's half — so the merged
	 * {@code bootStrap()} runs the snapshot and never wraps the streams. Measured: stock 26.2 has
	 * {@code invokestatic wrapStreams:()V} at bci 81; in the merged base the ONLY class mentioning
	 * {@code wrapStreams} is {@code Bootstrap} itself, and inside it the only mention is the declaration.
	 * The method's body survived the merge intact — it still builds {@code LoggedPrintStream("STDOUT")} and
	 * calls {@code System.setOut} — so nothing needs writing, only calling.
	 *
	 * <p>What it costs while dead: every line a mod PRINTS instead of logging is gone. Not degraded, not
	 * misfiled — absent. MouseTweaks writes its entire diagnostic output through {@code System.out}, so a
	 * player told to turn on its debug mode produces a log with nothing in it, and the silence reads as
	 * "the mod said nothing" rather than "nobody was listening". Any mod printing a stack trace to stderr
	 * disappears the same way.
	 *
	 * <p>Both halves are kept. The snapshot is NeoForge's and it stays exactly where NeoForge put it; the
	 * wrap goes after it, at vanilla's position relative to {@code bootstrapDuration}. Restoring one
	 * ecosystem's line must not cost the other's — that is the merge failure this repair is undoing, and
	 * doing it in reverse would be no better.
	 */
	private static boolean wrapTheStreamsVanillaWraps(ClassNode node) {
		if (!BOOTSTRAP.equals(node.name) || node.methods == null) return false;
		if (!hasMethod(node, "wrapStreams", "()V")) return false;

		MethodNode bootStrap = null;
		for (MethodNode method : node.methods) {
			if ("bootStrap".equals(method.name) && "()V".equals(method.desc)) bootStrap = method;
		}
		if (bootStrap == null || bootStrap.instructions == null) return false;

		// Already calling it (a future base that keeps vanilla's line) — this repair is then a no-op, and must
		// report itself as one rather than inserting a second wrap that would nest the streams twice.
		for (AbstractInsnNode insn : bootStrap.instructions) {
			if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
					&& BOOTSTRAP.equals(call.owner) && "wrapStreams".equals(call.name)) {
				return false;
			}
		}

		// Vanilla's position: immediately before bootstrapDuration is written, which is the last thing the
		// method does. Anchoring on that field write rather than on the preceding call keeps the insertion
		// correct whichever ecosystem's calls precede it.
		AbstractInsnNode anchor = null;
		for (AbstractInsnNode insn : bootStrap.instructions) {
			if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
					&& BOOTSTRAP.equals(field.owner) && "bootstrapDuration".equals(field.name)) {
				anchor = insn;
				break;
			}
		}
		if (anchor == null) return false;

		bootStrap.instructions.insertBefore(anchor,
				new MethodInsnNode(Opcodes.INVOKESTATIC, BOOTSTRAP, "wrapStreams", "()V", false));
		bootStrap.maxStack = Math.max(bootStrap.maxStack, 2);

		ForbricLog.warn("[Forbric/MergedBaseCompat] Bootstrap now wraps System.out/System.err into log4j again "
				+ "— NeoForge's patch spends vanilla's wrapStreams() slot on GameData.vanillaSnapshot() and the "
				+ "merge kept only that half, so every line a mod PRINTED rather than logged was absent from the "
				+ "log entirely (a mod's own debug mode produced a log with nothing in it). Both calls now run");
		return true;
	}

	/**
	 * Gives {@code Bootstrap.bootStrap()}'s already-bootstrapped path its own {@code return}, ahead of the body, so
	 * the method's last {@code return} — the one a mixin's {@code @At("TAIL")} names — is reached only by the call
	 * that actually bootstrapped.
	 *
	 * <p>Vanilla's shape is {@code if (!isBootstrapped) { isBootstrapped = true; ... } return;}: one return, reached
	 * by every call. On vanilla and on Fabric that is one call per process ({@code Main.main} / the client's
	 * {@code Main}), so a mod injecting at TAIL runs once, after bootstrap. The merged game also carries
	 * MinecraftForge's {@code ForgeRegistries.<clinit>}, whose {@code init()} calls {@code Bootstrap.bootStrap()}
	 * to make sure bootstrap has happened — and it is first touched from INSIDE bootstrap, while {@code Items}
	 * constructs a bucket. {@code isBootstrapped} is already true there, the nested call skips the body and falls
	 * through to the same return, and every TAIL handler runs half-way through bootstrap and then again at its
	 * end. Measured with {@code -Xlog:class+init}: {@code ForgeRegistries} initialises between {@code BucketItem}
	 * and cristellib's {@code CristelLib}; cristellib's TAIL handler freezes its pack registry and config data the
	 * first time and throws "Cannot set Auto Config data twice" the second, and the server does not start.
	 *
	 * <p>Only TAIL changes. The early return is still a return, so an {@code @At("RETURN")} handler still sees
	 * every call; HEAD is untouched; the body and the call that runs it are exactly what they were.
	 */
	private static boolean returnFromANestedBootstrapBeforeItsTail(ClassNode node) {
		if (!BOOTSTRAP.equals(node.name) || node.methods == null) return false;
		MethodNode bootStrap = null;
		for (MethodNode method : node.methods) {
			if ("bootStrap".equals(method.name) && "()V".equals(method.desc)) bootStrap = method;
		}
		if (bootStrap == null || bootStrap.instructions == null) return false;

		// The guard: the first real instructions are GETSTATIC isBootstrapped; IFNE skip.
		AbstractInsnNode first = realAfter(bootStrap.instructions.getFirst(), true);
		if (!(first instanceof FieldInsnNode read) || read.getOpcode() != Opcodes.GETSTATIC
				|| !BOOTSTRAP.equals(read.owner) || !"isBootstrapped".equals(read.name) || !"Z".equals(read.desc)) {
			return false;
		}
		AbstractInsnNode next = realAfter(first.getNext(), true);
		// Already repaired (IFEQ body; RETURN) or a base that returns early itself: nothing to do.
		if (!(next instanceof JumpInsnNode guard) || guard.getOpcode() != Opcodes.IFNE) return false;

		// The guard must skip to the method's LAST return, or this is not the shape the repair is about.
		AbstractInsnNode skipped = realAfter(guard.label, true);
		AbstractInsnNode lastReturn = null;
		for (AbstractInsnNode insn = bootStrap.instructions.getLast(); insn != null; insn = insn.getPrevious()) {
			if (insn.getOpcode() == Opcodes.RETURN) { lastReturn = insn; break; }
		}
		if (skipped == null || skipped != lastReturn) return false;

		LabelNode body = new LabelNode();
		InsnList early = new InsnList();
		early.add(new InsnNode(Opcodes.RETURN));
		early.add(body);
		// Method entry's frame: a static no-argument method, nothing on the stack.
		early.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
		bootStrap.instructions.insert(guard, early);
		guard.setOpcode(Opcodes.IFEQ);
		guard.label = body;

		ForbricLog.info("[Forbric/MergedBaseCompat] Bootstrap.bootStrap() returns before its TAIL when bootstrap has "
				+ "already begun — MinecraftForge's ForgeRegistries calls it again from inside the first call, which ran "
				+ "every TAIL handler twice, the first time half-way through bootstrap");
		return true;
	}

	/** The first instruction at or after {@code from} that is not a label, line number or frame. */
	private static AbstractInsnNode realAfter(AbstractInsnNode from, boolean inclusive) {
		AbstractInsnNode insn = inclusive ? from : (from == null ? null : from.getNext());
		while (insn != null && insn.getOpcode() < 0) insn = insn.getNext();
		return insn;
	}

	private static boolean addBlockAppearanceResolver(ClassNode node) {
		String desc = "(Lnet/minecraft/world/level/block/state/BlockState;"
				+ "Lnet/minecraft/world/level/BlockAndLightGetter;Lnet/minecraft/core/BlockPos;"
				+ "Lnet/minecraft/core/Direction;Lnet/minecraft/world/level/block/state/BlockState;"
				+ "Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;";
		if (hasMethod(node, "getAppearance", desc)) return false;

		MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "getAppearance", desc, null, null);
		method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
		method.instructions.add(new InsnNode(Opcodes.ARETURN));
		method.maxStack = 1;
		method.maxLocals = 7;
		node.methods.add(method);

		ForbricLog.warn("[Forbric/MergedBaseCompat] gave Block its own getAppearance — NeoForge's and fabric-api's "
				+ "interfaces both default it identically and neither wins, so every block subclass that does not "
				+ "override it died on IncompatibleClassChangeError the moment a connected-texture mod asked what "
				+ "a neighbour looks like");
		return true;
	}

	private static boolean addBlockStateAppearanceResolver(ClassNode node) {
		if ("net/minecraft/world/level/block/Block".equals(node.name)) return addBlockAppearanceResolver(node);
		if (!"net/minecraft/world/level/block/state/BlockState".equals(node.name)) return false;

		String desc = "(Lnet/minecraft/world/level/BlockAndLightGetter;Lnet/minecraft/core/BlockPos;"
				+ "Lnet/minecraft/core/Direction;Lnet/minecraft/world/level/block/state/BlockState;"
				+ "Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;";
		if (hasMethod(node, "getAppearance", desc)) return false;

		MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "getAppearance", desc, null, null);
		method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
				"net/minecraft/world/level/block/state/BlockState", "getBlock",
				"()Lnet/minecraft/world/level/block/Block;", false));
		for (int slot = 0; slot <= 5; slot++) method.instructions.add(new VarInsnNode(Opcodes.ALOAD, slot));
		method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
				"net/minecraft/world/level/block/Block", "getAppearance",
				"(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockAndLightGetter;"
						+ "Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;"
						+ "Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)"
						+ "Lnet/minecraft/world/level/block/state/BlockState;", false));
		method.instructions.add(new InsnNode(Opcodes.ARETURN));
		// receiver + the six arguments of Block.getAppearance
		method.maxStack = 7;
		method.maxLocals = 6;
		node.methods.add(method);

		ForbricLog.warn("[Forbric/MergedBaseCompat] gave BlockState its own getAppearance — NeoForge's and "
				+ "fabric-api's interfaces both default it with the same descriptor and neither wins, so the "
				+ "first mod to ask a neighbour what it looks like (a connected-texture mod) died on "
				+ "IncompatibleClassChangeError. Both defaults are the same call, so this is that call");
		return true;
	}

	private static boolean addBlockStateModelConflictResolvers(ClassNode node) {
		if (!"net/minecraft/client/renderer/block/dispatch/BlockStateModel".equals(node.name)) return false;

		boolean changed = false;
		if (!hasMethod(node, "createGeometryKey",
				"(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;"
						+ "Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/util/RandomSource;)"
						+ "Ljava/lang/Object;")) {
			MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "createGeometryKey",
					"(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;"
							+ "Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/util/RandomSource;)"
							+ "Ljava/lang/Object;",
					null, null);
			method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
			method.instructions.add(new InsnNode(Opcodes.ARETURN));
			method.maxStack = 1;
			method.maxLocals = 5;
			node.methods.add(method);
			changed = true;
		}

		if (!hasMethod(node, "particleMaterial",
				"(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;"
						+ "Lnet/minecraft/world/level/block/state/BlockState;)"
						+ "Lnet/minecraft/client/resources/model/sprite/Material$Baked;")) {
			MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "particleMaterial",
					"(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;"
							+ "Lnet/minecraft/world/level/block/state/BlockState;)"
							+ "Lnet/minecraft/client/resources/model/sprite/Material$Baked;",
					null, null);
			method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
			method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
					"net/minecraft/client/renderer/block/dispatch/BlockStateModel",
					"particleMaterial",
					"()Lnet/minecraft/client/resources/model/sprite/Material$Baked;",
					true));
			method.instructions.add(new InsnNode(Opcodes.ARETURN));
			method.maxStack = 1;
			method.maxLocals = 4;
			node.methods.add(method);
			changed = true;
		}

		if (!hasMethod(node, "materialFlags",
				"(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;"
						+ "Lnet/minecraft/world/level/block/state/BlockState;)I")) {
			MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "materialFlags",
					"(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;"
							+ "Lnet/minecraft/world/level/block/state/BlockState;)I",
					null, null);
			method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
			method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
					"net/minecraft/client/renderer/block/dispatch/BlockStateModel",
					"materialFlags", "()I", true));
			method.instructions.add(new InsnNode(Opcodes.IRETURN));
			method.maxStack = 1;
			method.maxLocals = 4;
			node.methods.add(method);
			changed = true;
		}

		if (changed) {
			ForbricLog.warn("[Forbric/MergedBaseCompat] added BlockStateModel default-method conflict resolvers");
		}
		return changed;
	}

	/**
	 * Points {@code KeyMapping.click} at the key lookup that registration actually populates.
	 *
	 * <p>The byte-merge left {@code KeyMapping} with TWO static fields both named {@code MAP} — NeoForge's
	 * {@code KeyMappingLookup} and MinecraftForge's (same name, different descriptor: legal in bytecode, unwritable
	 * in Java source). Every WRITE goes to the NeoForge one ({@code registerMapping}, the constructors,
	 * {@code setKeyModifierAndCode}, {@code resetMapping}), and {@code <clinit>} only ever assigned that one. The
	 * merge also kept BOTH {@code forAllKeyMappings} overloads, and they READ different maps: the 3-arg one — used
	 * by {@code KeyMapping.set}, which drives {@code isDown} — reads NeoForge's, while the 2-arg one, whose single
	 * caller is {@code KeyMapping.click} (it drives {@code clickCount}), reads MinecraftForge's.
	 *
	 * <p>So the Forge lookup is permanently EMPTY and {@code click} matches nothing: {@code clickCount} never
	 * increments and {@code consumeClick()} is forever false. That kills every {@code consumeClick}-driven key for
	 * vanilla AND every mod — inventory (E), chat (T), command ({@code /}), drop (Q) — while {@code isDown} keys
	 * (WASD, sneak, attack) keep working, because {@code set} reads the populated map. ESC still opens the pause
	 * menu, because that is a direct key-code check in {@code KeyboardHandler}, not a {@code KeyMapping} — which is
	 * exactly the "ESC pauses but E does nothing" shape this presents as.
	 *
	 * <p>Both {@code getAll(InputConstants$Key)} overloads return {@code List<KeyMapping>}, so redirecting the field
	 * read and the call is descriptor-identical. (The Forge lookup's own initializer was added by a MinecraftForge repair
	 * that went with MinecraftForge.)
	 */
	private static boolean routeKeyMappingClickToPopulatedLookup(ClassNode node) {
		if (!"net/minecraft/client/KeyMapping".equals(node.name)) return false;

		String forgeLookup = "Lnet/minecraftforge/client/settings/KeyMappingLookup;";
		String neoLookup = "Lnet/neoforged/neoforge/client/settings/KeyMappingLookup;";
		// Only meaningful when the merge actually produced BOTH lookups; a single-ecosystem base is already coherent.
		if (!hasField(node, "MAP", forgeLookup) || !hasField(node, "MAP", neoLookup)) return false;

		boolean changed = false;
		for (MethodNode lookup : node.methods) {
			if ("<init>".equals(lookup.name) || "<clinit>".equals(lookup.name)) continue;
		for (AbstractInsnNode insn = lookup.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() == Opcodes.GETSTATIC && insn instanceof FieldInsnNode field
					&& "MAP".equals(field.name) && forgeLookup.equals(field.desc)) {
				field.desc = neoLookup;
				changed = true;
			} else if (insn instanceof MethodInsnNode call
					&& ForeignType.KEY_MAPPING_LOOKUP.internal(Ecosystem.FORGE).equals(call.owner)
					&& "getAll".equals(call.name)) {
				call.owner = ForeignType.KEY_MAPPING_LOOKUP.internal(Ecosystem.NEOFORGE);
				changed = true;
			}
		}
		}
		if (!changed) return false;

		ForbricLog.warn("[Forbric/MergedBaseCompat] routed KeyMapping.click to the populated (NeoForge) key lookup "
				+ "— the merge left it reading the Forge-side MAP, which is never written, so every consumeClick key "
				+ "(inventory/chat/command/drop) was dead");
		return true;
	}

	/**
	 * Gives {@code ParticleResources}' vanilla-typed {@code providers} field a live view of the one that is written.
	 *
	 * <p>The same failure class as {@link #routeKeyMappingClickToPopulatedLookup}, at field level. Vanilla declares
	 * {@code providers} as {@code Int2ObjectMap} keyed by particle id; NeoForge 26.2.0.88 RE-TYPES that field to
	 * {@code Map<Identifier, ?>}. Same name, different descriptor is legal, so the merge keeps both and the
	 * surviving {@code <init>} writes only NeoForge's. The vanilla-typed one is null for the life of the process.
	 *
	 * <p>The merge tool sees this pair and correctly declines to delete either — deleting the unwritten one trades
	 * an NPE for a {@code NoSuchFieldError} at the same instruction — and it cannot repair it: its
	 * exclusive-added-field initializer is scoped to fields an ecosystem ADDED, and this is a RE-TYPED VANILLA
	 * field, outside that set by construction. So the repair belongs here, where the whole class is in hand.
	 *
	 * <p>A view rather than a second map, because the two halves have to stay ONE mechanism. fabric-api's
	 * {@code DirectParticleProviderRegistry.register} reads the field DIRECTLY — {@code getfield providers} of the
	 * {@code Int2ObjectMap} descriptor, then {@code PARTICLE_TYPE.getId(type)}, then {@code put(int, provider)} —
	 * so rewriting accessors cannot reach it, and an empty map of its own would swallow the registration and leave
	 * the particle silently unrendered. Writes through the int-keyed face have to be visible to
	 * {@code ParticleEngine.makeParticle}, which reads the {@code Identifier}-keyed one.
	 *
	 * <p>The insert goes immediately after {@code <init>}'s write of the live map and BEFORE its
	 * {@code registerProviders()} call, not before {@code RETURN}: fabric-api's {@code ParticleResourcesMixin}
	 * injects at {@code registerProviders}'s RETURN, so a repair placed at the end of the constructor is still too
	 * late and reproduces the crash while looking correct.
	 *
	 * <p>It also repoints {@code getProvider} at the live map. MinecraftForge added {@code providersByName} and
	 * filled it from its own {@code register}, which the merge dropped — so the merged class initializes it, from
	 * a synthetic default AFTER {@code registerProviders} has already run, and nothing ever puts anything in it.
	 * The two maps held the same thing by construction (both keyed {@code getKey(type)}, same descriptor), so this
	 * is a rename.
	 */
	private static boolean giveTheVanillaParticleMapAViewOfTheLiveOne(ClassNode node) {
		if (!PARTICLE_RESOURCES.equals(node.name)) return false;
		// Only when the merge actually split it. A single-ecosystem or rebuilt base is already coherent.
		if (!hasField(node, "providers", NAME_KEYED) || !hasField(node, "providers", ID_KEYED)) return false;

		MethodNode init = findMethod(node, "<init>", "()V");
		if (init == null) return false;

		FieldInsnNode anchor = null;
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTFIELD
						|| !node.name.equals(field.owner) || !"providers".equals(field.name)) {
					continue;
				}
				if (ID_KEYED.equals(field.desc)) {
					// Already written by something — a rebuilt base, or this pass having run before. Stand down:
					// this is also what makes the pass idempotent.
					return false;
				}
				if (!NAME_KEYED.equals(field.desc)) continue;
				if (anchor != null || method != init) {
					ForbricLog.warn("[Forbric/MergedBaseCompat] ParticleResources writes its provider map more than "
							+ "once, or outside <init> — the view below would capture a map that is later replaced, "
							+ "so it is not installed");
					return false;
				}
				anchor = field;
			}
		}
		if (anchor == null) return false;

		InsnList view = new InsnList();
		view.add(new VarInsnNode(Opcodes.ALOAD, 0));
		view.add(new VarInsnNode(Opcodes.ALOAD, 0));
		view.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, "providers", NAME_KEYED));
		view.add(new MethodInsnNode(Opcodes.INVOKESTATIC, KERNEL_PARTICLES, "intKeyedView",
				"(Ljava/util/Map;)Ljava/lang/Object;", false));
		view.add(new TypeInsnNode(Opcodes.CHECKCAST, ID_KEYED.substring(1, ID_KEYED.length() - 1)));
		view.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, "providers", ID_KEYED));
		init.instructions.insert(anchor, view);
		init.maxStack = Math.max(init.maxStack, 2);

		routeGetProviderAtTheLiveMap(node);

		ForbricLog.warn("[Forbric/MergedBaseCompat] ParticleResources had two `providers` fields and only one was "
				+ "ever written — the vanilla-typed one, which fabric-api's particle registry reads directly, was "
				+ "null, so any mod using that API crashed inside Minecraft.<init>. It is now a live view of the "
				+ "map that IS written");
		return true;
	}

	/**
	 * Stops one ecosystem's condition dialect from failing the other ecosystem's data files — and with them the
	 * whole registry load.
	 *
	 * <p>The merged {@code RegistryLoadTask$PendingRegistration.loadFromResource} carries NeoForge's patch: stock
	 * Minecraft's body calls {@code Decoder.parse} straight, and the merged one wraps every element in
	 * {@code ConditionalOps.createConditionalCodec} first. There is no switch on it and no per-pack scoping, so
	 * EVERY datapack-registry element from EVERY pack is judged by NeoForge's evaluator.
	 *
	 * <p>A multi-loader mod ships one data tree carrying BOTH dialects — {@code "fabric:load_conditions"} and
	 * {@code "neoforge:conditions"} in the same file — which is what Architectury emits. Its Fabric build
	 * registers the condition type on the Fabric side only, so the NeoForge dispatch cannot resolve the id and
	 * {@code RegistryDataLoader} escalates that into "Failed to load registries due to errors". The server does
	 * not start and the world does not open: a fatal, produced by ordinary mod output.
	 *
	 * <p>{@code ICondition.CODEC} is a registry dispatch built in one static initializer and reused everywhere,
	 * including by {@code LIST_CODEC} two instructions later, so ONE insertion covers datapack registries,
	 * recipes, loot tables and advancements alike. The kernel's wrapper decodes an unknown type as a condition
	 * that does not veto, leaving the judgement to the ecosystem that owns the id.
	 *
	 * <p>Inserted rather than replaced, and stack-neutral: a {@code Codec} goes in and a {@code Codec} comes out,
	 * so the existing {@code PUTSTATIC} is untouched and there is no frame to recompute.
	 */
	private static boolean letForeignResourceConditionsThrough(ClassNode node) {
		if (!ICONDITION.equals(node.name)) return false;
		MethodNode clinit = findMethod(node, "<clinit>", "()V");
		if (clinit == null) return false;

		FieldInsnNode target = null;
		for (AbstractInsnNode insn = clinit.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC
					&& ICONDITION.equals(field.owner) && "CODEC".equals(field.name)
					&& CODEC_DESC.equals(field.desc)) {
				if (target != null) {
					ForbricLog.warn("[Forbric/MergedBaseCompat] ICondition.CODEC is assigned more than once — not "
							+ "wrapping it, because only one of the assignments would be the one that survives");
					return false;
				}
				target = field;
			}
		}
		if (target == null) return false;
		if (target.getPrevious() instanceof MethodInsnNode already
				&& KERNEL_NEO_CONDITIONS.equals(already.owner)) {
			return false;                       // already wrapped: idempotent
		}

		clinit.instructions.insertBefore(target, new MethodInsnNode(Opcodes.INVOKESTATIC, KERNEL_NEO_CONDITIONS,
				"lenient", "(" + CODEC_DESC + ")" + CODEC_DESC, false));
		ForbricLog.info("[Forbric/MergedBaseCompat] NeoForge's resource-condition codec now tolerates a condition "
				+ "type it does not own — the merged base runs that evaluator over EVERY datapack element from "
				+ "every pack, so a Fabric mod's own condition used to fail the whole registry load and the world "
				+ "with it");
		return true;
	}

	/**
	 * Converts a guest mixin's private "skip this file" sentinel before the merged reader casts it and dies.
	 *
	 * <p>fabric-api's {@code SimpleJsonResourceReloadListenerMixin} is a producer and a consumer that only work
	 * as a pair, and on the merged base exactly one of them applies. The producer — a {@code @WrapOperation} on
	 * {@code Codec.parse} — returns {@code DataResult.success(SKIP_DATA_MARKER)}, a bare {@code new Object()},
	 * when a file's conditions say no. The consumer, an {@code @Inject} that recognises the marker, targets
	 * {@code lambda$scanDirectory$0(Codec,Identifier,Map,Object)}; the merge left the class carrying TWO methods
	 * of that name and the live {@code invokedynamic} binds the OTHER one,
	 * {@code (Identifier,Identifier,Map,Optional)}. So the marker reaches {@code DataResult.ifSuccess}, whose
	 * consumer casts it to {@code Optional}, and the datapack load dies: "can't proceed with server load".
	 *
	 * <p>Both {@code scanDirectory} and {@code scanDirectoryWithModifier} are repaired, not just the one observed
	 * failing. They are the same shape with the same consumer contract, the second is the one recipes use, and
	 * this file already carries the lesson about patching a call site instead of the funnel and silently missing
	 * every recipe.
	 *
	 * <p>The {@code ifSuccess} CALL is replaced rather than its receiver wrapped, and that is not a style
	 * choice. The {@code invokedynamic} that builds the consumer pops three captured values first, so the
	 * {@code DataResult} is buried under them and is never on top of the stack at any instruction boundary
	 * before the call — an insertion there operates on the captured Map instead, which is an
	 * {@code IncompatibleClassChangeError} at the first datapack. An {@code invokestatic} of the same
	 * {@code (DataResult, Consumer) -> DataResult} shape moves nothing.
	 *
	 * <p>Idempotent by construction: the second pass finds no {@code ifSuccess} left to replace.
	 */
	private static boolean translateAGuestsPrivateSkipMarker(ClassNode node) {
		if (!JSON_RELOAD_LISTENER.equals(node.name)) return false;

		int repaired = 0;
		for (MethodNode method : node.methods) {
			if (!"scanDirectory".equals(method.name) && !"scanDirectoryWithModifier".equals(method.name)) continue;
			if (method.instructions == null) continue;

			AbstractInsnNode call = null;
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof MethodInsnNode m && "ifSuccess".equals(m.name)
						&& "com/mojang/serialization/DataResult".equals(m.owner)) {
					if (call != null) {
						ForbricLog.warn("[Forbric/MergedBaseCompat] %s.%s has more than one DataResult.ifSuccess — "
								+ "not repairing it, because which one receives the guest's skip marker is no "
								+ "longer decidable from the shape", node.name, method.name);
						call = null;
						break;
					}
					call = insn;
				}
			}
			if (call == null) continue;

			method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, KERNEL_FABRIC_CONDITIONS,
					"ifSuccessWithoutAForeignSkipMarker",
					"(" + DATA_RESULT + "Ljava/util/function/Consumer;)" + DATA_RESULT, false));
			repaired++;
		}
		if (repaired == 0) return false;

		ForbricLog.info("[Forbric/MergedBaseCompat] a guest mixin's private skip marker is now translated before "
				+ "%s casts it (%d reader(s) repaired) — fabric-api's condition mixin applies only half here, and "
				+ "the half that runs produces a bare Object where the half that does not would have removed the "
				+ "file. Unrepaired, one condition-gated data file whose condition is false stops the server "
				+ "starting at all", node.name, repaired);
		return true;
	}

	/**
	 * Lets a Fabric mod add a client reload listener the way Fabric mods always have, without killing the client.
	 *
	 * <p>{@code AddClientReloadListenersEvent.lookupName} names each listener already in the resource manager by
	 * asking {@code VanillaClientListeners.getNameForClass}, and when that returns null it THROWS: "A non-vanilla
	 * reload listener … was added via mixin before the AddClientReloadListenerEvent!". The assertion is written
	 * for an instance whose only mods are NeoForge mods. Adding a listener by mixin is ordinary Fabric practice —
	 * there is no event for it to go through — so on a tri-ecosystem instance it fires on CORRECT mod code, from
	 * inside {@code ClientHooks.initClientHooks}, which runs inside {@code Minecraft.<init>}: vistas took the whole
	 * client down before it drew a frame.
	 *
	 * <p>The name is a sort key and a registry key and nothing else, so a synthesised one leaves the listener
	 * registered, sorted and RUNNING — which is the difference between this and swallowing the exception. Only
	 * the lookup inside this event is redirected: NeoForge's own {@code ClientNeoForgeMod} asks the same method
	 * about its own listeners, and those are in the table.
	 *
	 * <p>One instruction: same opcode, same descriptor, same stack.
	 */
	private static boolean nameTheReloadListenersNeoForgeRefusesToName(ClassNode node) {
		if (!ADD_CLIENT_RELOAD_LISTENERS.equals(node.name)) return false;
		int redirected = 0;
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC
						|| !VANILLA_CLIENT_LISTENERS.equals(call.owner)
						|| !"getNameForClass".equals(call.name) || !NAME_FOR_CLASS.equals(call.desc)) {
					continue;
				}
				call.owner = KERNEL_RELOAD_NAMES;
				call.name = "nameFor";
				redirected++;
			}
		}
		if (redirected == 0) return false;
		ForbricLog.info("[Forbric/MergedBaseCompat] a client reload listener NeoForge cannot name is now given one "
				+ "(%d lookup(s) redirected) — it used to throw inside Minecraft.<init> over a Fabric mod adding a "
				+ "listener by mixin, which is how Fabric mods have always added them", redirected);
		return true;
	}

	/**
	 * Gives {@code fabric:load_conditions} an evaluator again, at the one place every consumer funnels through.
	 *
	 * <p>The other half of {@link #letForeignResourceConditionsThrough}. That one stopped NeoForge's evaluator
	 * failing a whole world load over an id it does not own; this one makes the answer come from the mod that
	 * does own it. fabric-api reads that key from exactly two mixins and the merged base defeats both — one
	 * anchors at a {@code Decoder.parse} NeoForge's patch replaced with {@code Codec.parse}, the other targets a
	 * lambda whose descriptor the same patch changed — and the kernel's own {@code defaultRequire} rewrite turns
	 * the first into a SILENT soft-skip. So every Fabric mod's conditional data file has loaded unconditionally
	 * here, and a config toggle meant to gate content did nothing.
	 *
	 * <p>{@code ConditionalOps} has four public factories and all four funnel into
	 * {@code createConditionalCodecWithConditions(Codec, String)}, so wrapping that one covers the datapack
	 * registries, recipes, loot tables and advancements together. A per-call-site patch would have missed
	 * recipes, which reach it through {@code scanDirectoryWithModifier} rather than {@code scanDirectory}.
	 *
	 * <p>Inserted immediately before the method's single {@code ARETURN}, where the finished {@code Codec} is
	 * already the only thing on the stack: a {@code Codec} goes in and a {@code Codec} comes out, so nothing
	 * moves and there is no frame to recompute. More than one {@code ARETURN} means the method is not the shape
	 * this reasoning was checked against, and the pass stands down whole rather than wrapping one exit.
	 */
	private static boolean letFabricResourceConditionsDecide(ClassNode node) {
		if (!CONDITIONAL_OPS.equals(node.name)) return false;
		MethodNode factory = findMethod(node, "createConditionalCodecWithConditions", CONDITIONAL_FACTORY);
		if (factory == null) return false;

		AbstractInsnNode exit = null;
		for (AbstractInsnNode insn = factory.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() != Opcodes.ARETURN) continue;
			if (exit != null) {
				ForbricLog.warn("[Forbric/MergedBaseCompat] ConditionalOps' codec factory has more than one exit — "
						+ "not wrapping it, because wrapping one of them would judge some data files and not "
						+ "others with no way to tell which");
				return false;
			}
			exit = insn;
		}
		if (exit == null) return false;
		if (exit.getPrevious() instanceof MethodInsnNode already
				&& KERNEL_FABRIC_CONDITIONS.equals(already.owner)) {
			return false;                       // already wrapped: idempotent
		}

		factory.instructions.insertBefore(exit, new MethodInsnNode(Opcodes.INVOKESTATIC, KERNEL_FABRIC_CONDITIONS,
				"alsoAskFabric", "(Lcom/mojang/serialization/Codec;)Lcom/mojang/serialization/Codec;", false));

		int funnelled = 0;
		for (MethodNode method : node.methods) {
			if (method == factory) continue;
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof MethodInsnNode call && CONDITIONAL_OPS.equals(call.owner)
						&& call.name.startsWith("createConditionalCodec")) {
					funnelled++;
					break;
				}
			}
		}
		ForbricLog.info("[Forbric/MergedBaseCompat] fabric:load_conditions has an evaluator again: ConditionalOps' "
				+ "one codec factory is wrapped and %d other public entry point(s) funnel through it — datapack "
				+ "registries, recipes, loot tables and advancements all decode through it. fabric-api's own two "
				+ "mixins for this cannot apply on the merged base", funnelled);
		return true;
	}

	/**
	 * Lets monster rooms generate again, by giving the NeoForge data map a vanilla fallback.
	 *
	 * <p>The merged {@code MonsterRoomFeature.randomEntityId} is two instructions:
	 * {@code invokestatic MonsterRoomHooks.getRandomMonsterRoomMob}. That reads a static {@code WeightedList} which
	 * only a {@code DataMapsUpdatedEvent} listener fills, and nothing in a Forbric instance had ever loaded a data
	 * map — a constant-pool scan of the whole merged base finds {@code DataMapLoader} named by nothing at all,
	 * because the merge kept MinecraftForge's {@code ReloadableServerResources}. So the list was null and the
	 * feature threw.
	 *
	 * <p>The kernel's previous answer was a {@code MethodBodyNeuter} on {@code MonsterRoomFeature.place}, which
	 * does not fail — it means no dungeon, and therefore no spawner and no dungeon chest, in EVERY world every
	 * player generates, with or without mods. A whole piece of vanilla, switched off silently, for everyone.
	 *
	 * <p>{@link net.forbric.kernel.runtime.KernelNeoWorldgen} now loads the data maps for real, so the primary
	 * path works and a NeoForge mod's additions count. This redirect is what makes that recoverable rather than
	 * load-bearing: when the data map is missing anyway, dungeons still generate from vanilla's own set. The two
	 * sets are the same distribution — vanilla's {@code MOBS} array is {@code {SKELETON, ZOMBIE, ZOMBIE, SPIDER}}
	 * and NeoForge's shipped data map is skeleton 100 / spider 100 / zombie 200 — so the fallback is vanilla's
	 * behaviour and not an approximation of it.
	 *
	 * <p>One instruction for one: the call is static, takes the same argument and returns the same type, so
	 * nothing on the stack or in a frame moves.
	 */
	private static boolean letDungeonsGenerateWithoutTheDataMap(ClassNode node) {
		if (!MONSTER_ROOM_FEATURE.equals(node.name)) return false;
		int redirected = 0;
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC
						|| !MONSTER_ROOM_HOOKS.equals(call.owner)
						|| !"getRandomMonsterRoomMob".equals(call.name)
						|| !RANDOM_MONSTER_ROOM_MOB.equals(call.desc)) {
					continue;
				}
				call.owner = KERNEL_NEO_WORLDGEN;
				call.name = "randomMonsterRoomMob";
				redirected++;
			}
		}
		if (redirected == 0) return false;
		ForbricLog.info("[Forbric/MergedBaseCompat] MonsterRoomFeature now picks its mob through the kernel "
				+ "(%d call site(s)) — NeoForge's data map when it has one, vanilla's own set when it does not. "
				+ "The alternative was the neutered place() this replaces, which meant no dungeon in any world",
				redirected);
		return true;
	}

	private static final String XOROSHIRO_RANDOM_SOURCE = "net/minecraft/world/level/levelgen/XoroshiroRandomSource";
	private static final String BIT_RANDOM_SOURCE = "net/minecraft/world/level/levelgen/BitRandomSource";
	/** 2^-53: the multiplier that turns 53 random bits into a double in [0,1). Exactly representable in both widths. */
	private static final float DOUBLE_UNIT_AS_FLOAT = (float) 0x1.0p-53;
	private static final double DOUBLE_UNIT = 0x1.0p-53;
	/**
	 * Switches the repair off, which puts the game back on the float-rounded draw.
	 *
	 * <p>It exists so gate-m31 can demonstrate its own teeth: a parity gate that has never been seen to go red is
	 * not evidence that the worlds match, only that the comparison ran. With this off, the gate's biome check
	 * must fail.
	 */
	static final String RANDOM_PRECISION_PROPERTY = "forbric.randomSourcePrecision";

	static boolean randomSourcePrecisionEnabled() {
		return !"off".equalsIgnoreCase(System.getProperty(RANDOM_PRECISION_PROPERTY, "on"));
	}

	/**
	 * Puts the game's random sources back in double precision.
	 *
	 * <p>Vanilla's two {@code nextDouble()} bodies scale 53 random bits by 2^-53 in double:
	 * {@code nextBits(53); l2d; ldc2_w 1.1102230246251565E-16; dmul}. The merged base does it in FLOAT —
	 * {@code l2f; ldc 1.110223E-16f; fmul; f2d} — in both {@code XoroshiroRandomSource.nextDouble()} and the
	 * {@code BitRandomSource.nextDouble()} default that {@code LegacyRandomSource} and {@code WorldgenRandom}
	 * inherit. The constant is right (2^-53 is exact as a float); the {@code l2f} is not, because it crushes a
	 * 53-bit mantissa into 24.
	 *
	 * <p>Two costs, and the second one is a contract violation rather than a rounding difference:
	 * <ul>
	 * <li>EVERY sample differs from vanilla's — measured over a million draws, one million differed, worst
	 * relative error 5.95e-8. {@code ImprovedNoise}'s constructor spends three {@code nextDouble() * 256.0} calls
	 * on {@code xo/yo/zo}, so every Perlin octave's origin is displaced and the whole density field moves with it.
	 * A same-seed A/B against pure vanilla 26.2 (both sides run twice, because vanilla's own block output is only
	 * reproducible where features do not read their neighbours) measured it: biomes differ in 11 of 1764 chunks
	 * and heightmaps in 90 of 400 fully generated ones, where vanilla against itself differs in 0 and 10.</li>
	 * <li>{@code nextDouble()} can return exactly {@code 1.0}, for every {@code bits >= 9007198986305536} — about
	 * one draw in 2^25. Every caller in the game assumes the half-open range; an index computed as
	 * {@code (int)(nextDouble() * size)} is then off the end of its array.</li>
	 * </ul>
	 *
	 * <p>This is not a patch either ecosystem wrote. {@code patched-mc-forge-26.2.jar} carries vanilla's
	 * {@code l2d/dmul}; {@code patched-mc-neoforge-26.2.jar} carries the float form, which is what NeoForge's
	 * decompile-recompile pipeline emitted, and the byte merge kept the NeoForge body. It names no class from
	 * either ecosystem, so {@code merge-conflicts.txt} — which reports conflicts by REFERENCE, on purpose — cannot
	 * see it and never did. That is the general shape to watch for: a purely numeric method can be re-typed by the
	 * pipeline and leave no trace in the conflict ledger.
	 *
	 * <p>Matched by SHAPE across the whole base rather than by a list of two class names, because the pipeline
	 * decides where this lands, not us; the two known sources are declared as REQUIRED anchors so a rebuild that
	 * moves or fixes them is reported rather than passed over in silence.
	 *
	 * <p>Stack depth is the one thing that moves: {@code l2f/fmul} peaks at two slots where {@code l2d/dmul} needs
	 * four. No branch is added and no frame changes, so widening {@code maxStack} is the whole adjustment.
	 */
	private static boolean restoreDoublePrecisionToTheRandomSources(ClassNode node) {
		if (!node.name.startsWith("net/minecraft/") || !randomSourcePrecisionEnabled()) return false;
		int repaired = 0;
		List<String> methods = new ArrayList<>();
		for (MethodNode method : node.methods) {
			boolean touched = false;
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn.getOpcode() != Opcodes.L2F) continue;
				AbstractInsnNode constant = nextReal(insn);
				if (!(constant instanceof LdcInsnNode ldc) || !(ldc.cst instanceof Float scale)
						|| scale.floatValue() != DOUBLE_UNIT_AS_FLOAT) {
					continue;
				}
				AbstractInsnNode multiply = nextReal(constant);
				if (multiply == null || multiply.getOpcode() != Opcodes.FMUL) continue;
				AbstractInsnNode widen = nextReal(multiply);
				if (widen == null || widen.getOpcode() != Opcodes.F2D) continue;

				InsnList code = method.instructions;
				InsnNode inDouble = new InsnNode(Opcodes.DMUL);
				code.set(insn, new InsnNode(Opcodes.L2D));
				code.set(constant, new LdcInsnNode(DOUBLE_UNIT));
				code.set(multiply, inDouble);
				code.remove(widen);
				insn = inDouble;
				touched = true;
				repaired++;
			}
			if (touched) {
				method.maxStack += 2;
				methods.add(method.name + method.desc);
			}
		}
		if (repaired == 0) return false;
		ForbricLog.info("[Forbric/MergedBaseCompat] %s scales its random bits in double again (%d site(s): %s) — the "
				+ "merged body rounded through float, which displaces every noise octave's origin and lets "
				+ "nextDouble() return exactly 1.0",
				node.name.replace('/', '.'), repaired, String.join(", ", methods));
		return true;
	}

	private static final double HALF_TURN_IN_DEGREES = 180.0;
	/** {@code (double)(float)Math.PI} — what the decompiler wrote where vanilla's source said {@code (float)Math.PI}. */
	private static final double PI_AS_FLOAT = (double) (float) Math.PI;
	/** Vanilla's own constant: the same expression folded in FLOAT at compile time, then widened. */
	private static final double RADIANS_TO_DEGREES = (double) (float) (180.0F / (float) Math.PI);

	/**
	 * Restores the radians-to-degrees constant vanilla folded, which the merged base recomputes at run time.
	 *
	 * <p>Vanilla's source multiplies by a compile-time constant: {@code (double)(180.0F / (float)Math.PI)}, which
	 * javac folds in FLOAT and widens, giving {@code ldc2_w 57.2957763671875; dmul}. The merged base instead
	 * carries the expression — {@code ldc2_w 180.0; dmul; ldc2_w 3.1415927410125732; ddiv} — and evaluates it in
	 * DOUBLE every time, which is a different number: 57.29577791868205. They differ by 1.55e-6, a relative
	 * 2.7e-8, and the merged one is the more accurate of the two. Accuracy is not the question; being the game
	 * the same seed and the same inputs produce elsewhere is.
	 *
	 * <p>45 sites across 31 methods, and they are the ones that turn a direction into a rotation:
	 * {@code Entity.lookAt}, {@code Mob.lookAt}, {@code MoveControl.tick} and its flying, swimming and
	 * mob-specific siblings, {@code LookControl.getYRotD}, {@code Projectile.shoot} and {@code updateRotation},
	 * {@code ProjectileUtil.rotateTowardsMovement}, {@code CommandSourceStack.facing}, the dragon phases,
	 * {@code WitherBoss.aiStep}, {@code SignBlockEntity.isFacingFrontText}. Vanilla 26.2 has ZERO sites of this
	 * shape; the merged base has 45.
	 *
	 * <p>Same origin as {@link #restoreDoublePrecisionToTheRandomSources(ClassNode)} and the same blind spot:
	 * NeoForge's decompile-recompile pipeline wrote the folded constant back out as its expression, the byte
	 * merge kept that body, and because the method names no class from any ecosystem,
	 * {@code merge-conflicts.txt} — which reports conflicts by reference — never mentioned it. A differential
	 * census of all 94,202 shared methods, normalised for everything a recompile may legally change, found
	 * exactly two families of this kind: that one and this one.
	 *
	 * <p>Four instructions become two, the multiply is reused where it stands, and the peak stack only falls, so
	 * nothing about the frame needs adjusting.
	 */
	private static boolean convertRadiansWithVanillasFoldedConstant(ClassNode node) {
		if (!node.name.startsWith("net/minecraft/")) return false;
		int folded = 0;
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof LdcInsnNode degrees) || !Double.valueOf(HALF_TURN_IN_DEGREES).equals(degrees.cst)) {
					continue;
				}
				AbstractInsnNode multiply = nextReal(insn);
				if (multiply == null || multiply.getOpcode() != Opcodes.DMUL) continue;
				AbstractInsnNode circle = nextReal(multiply);
				if (!(circle instanceof LdcInsnNode pi) || !Double.valueOf(PI_AS_FLOAT).equals(pi.cst)) continue;
				AbstractInsnNode divide = nextReal(circle);
				if (divide == null || divide.getOpcode() != Opcodes.DDIV) continue;

				degrees.cst = RADIANS_TO_DEGREES;
				method.instructions.remove(circle);
				method.instructions.remove(divide);
				insn = multiply;
				folded++;
			}
		}
		if (folded == 0) return false;
		ForbricLog.info("[Forbric/MergedBaseCompat] %s turns radians into degrees by vanilla's folded constant again "
				+ "(%d site(s)) — the merged body divided by pi at run time, which is a different number in the "
				+ "eighth digit and moves every angle computed from a vector",
				node.name.replace('/', '.'), folded);
		return true;
	}

	private static final String FRIENDLY_BYTE_BUF = "net/minecraft/network/FriendlyByteBuf";
	private static final String REGISTRY_FRIENDLY_BYTE_BUF = "net/minecraft/network/RegistryFriendlyByteBuf";
	private static final String PLAYER_ABILITIES_PACKET = "net/minecraft/network/protocol/game/ServerboundPlayerAbilitiesPacket";
	/** NeoForge's {@code IFriendlyByteBufExtension.writeByte(byte)}: {@code return self().writeByte(value);}, nothing else. */
	private static final String WRITE_BYTE_EXTENSION = "(B)Lnet/minecraft/network/FriendlyByteBuf;";
	/** Vanilla's {@code FriendlyByteBuf.writeByte(int)}, which both vanilla and MinecraftForge's game call. */
	private static final String WRITE_BYTE_VANILLA = "(I)Lnet/minecraft/network/FriendlyByteBuf;";
	static final String VANILLA_WRITE_BYTE_PROPERTY = "forbric.vanillaWriteByte";

	static boolean vanillaWriteByteEnabled() {
		return !"off".equalsIgnoreCase(System.getProperty(VANILLA_WRITE_BYTE_PROPERTY, "on"));
	}

	/**
	 * Calls vanilla's {@code FriendlyByteBuf.writeByte(int)} again where the merged body calls NeoForge's
	 * {@code writeByte(byte)}.
	 *
	 * <p>NeoForge's {@code IFriendlyByteBufExtension} declares {@code writeByte(byte)}, which only forwards to
	 * {@code writeByte(int)}. Recompiling vanilla's source with that interface in place, javac binds every
	 * {@code writeByte} handed a {@code byte} to the extension's overload — the more specific one — so NeoForge's game
	 * calls it where vanilla and MinecraftForge's game call {@code writeByte(int)}: fourteen sites in ten network
	 * {@code write} methods, through {@code FriendlyByteBuf} or {@code RegistryFriendlyByteBuf} as vanilla does. The
	 * merge kept NeoForge's bodies, and a mixin anchored on vanilla's call bound nothing: ViaFabricPlus' 1.15.2 ability
	 * flags redirect {@code ServerboundPlayerAbilitiesPacket.write}'s {@code writeByte(int)}, a required injector, so
	 * the strict policy stopped the client as soon as a world loaded.
	 *
	 * <p>The swap changes no behaviour: the same receiver and the same value reach the same method, and a {@code byte}
	 * is already an {@code int} on the operand stack, so no instruction is added and no frame changes. What it costs:
	 * a NeoForge mod anchored on {@code writeByte(byte)} in one of these vanilla methods no longer finds it — no mixin
	 * among the 861 mod jars this was measured on names that overload; ViaFabricPlus' names vanilla's.
	 * {@code -Dforbric.vanillaWriteByte=off} leaves NeoForge's calls.
	 */
	private static boolean callVanillasWriteByteAgain(ClassNode node) {
		if (!node.name.startsWith("net/minecraft/") || !vanillaWriteByteEnabled()) return false;
		int swapped = 0;
		List<String> methods = new ArrayList<>();
		for (MethodNode method : node.methods) {
			boolean touched = false;
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEVIRTUAL
						&& (call.owner.equals(FRIENDLY_BYTE_BUF) || call.owner.equals(REGISTRY_FRIENDLY_BYTE_BUF))
						&& call.name.equals("writeByte")
						&& call.desc.equals(WRITE_BYTE_EXTENSION)) {
					call.desc = WRITE_BYTE_VANILLA;
					touched = true;
					swapped++;
				}
			}
			if (touched) methods.add(method.name + method.desc);
		}
		if (swapped == 0) return false;
		ForbricLog.info("[Forbric/MergedBaseCompat] %s writes its bytes through vanilla's FriendlyByteBuf.writeByte(int) "
				+ "again (%d site(s): %s) — NeoForge's recompile bound them to its extension's writeByte(byte), which only "
				+ "forwards there, so a mixin anchored on vanilla's call found nothing",
				node.name.replace('/', '.'), swapped, String.join(", ", methods));
		return true;
	}

	private static final String CHUNK_STATUS = "net/minecraft/world/level/chunk/status/ChunkStatus";
	private static final String CHUNK_SAVE_HEIGHTMAPS = "chunkSaveHeightmaps";
	private static final String HEIGHTMAPS_AFTER = "heightmapsAfter";
	private static final String ENUM_SET_DESC = "Ljava/util/EnumSet;";
	static final String SAVED_HEIGHTMAPS_PROPERTY = "forbric.vanillaSavedHeightmaps";

	static boolean savedHeightmapsEnabled() {
		return !"off".equalsIgnoreCase(System.getProperty(SAVED_HEIGHTMAPS_PROPERTY, "on"));
	}

	/**
	 * Saves the heightmaps vanilla saves, and no others.
	 *
	 * <p>NeoForge gives {@code ChunkStatus} a second heightmap set — {@code chunkSaveHeightmaps}, which is
	 * {@code heightmapsAfter} plus {@code WORLD_SURFACE_WG} and {@code OCEAN_FLOOR_WG} for every status that is
	 * not a full chunk — and points all three of {@code SerializableChunkData}'s uses at it. MinecraftForge's
	 * patched jar does not; vanilla does not. So this is NeoForge's decision, not the pipeline's, and unlike its
	 * other decisions it changes what the world looks like.
	 *
	 * <p>The cost is not the extra bytes. Those two are WORLDGEN heightmaps: {@code ProtoChunk.setBlockState}
	 * stops maintaining them once a chunk passes CARVERS, so from that point they are a snapshot, and vanilla's
	 * answer is to never write them — a reloaded chunk rebuilds them from the blocks it actually has. Written and
	 * read back, they come back stale, and {@code PlacementUtils.HEIGHTMAP_WORLD_SURFACE} and
	 * {@code HEIGHTMAP_TOP_SOLID} are exactly what decide the Y a decoration is placed at. A chunk that was saved
	 * half-generated, unloaded and reloaded then decorates against a height that is no longer true.
	 *
	 * <p>Measured, on one seed, zero mods, five vanilla worlds against five Forbric ones: after the other two
	 * repairs the ONLY difference left that survives the noise filter is five chunks whose {@code WORLD_SURFACE}
	 * heightmap differs, and every one of them is a dead bush — 7 of 5,079 — placed on identical terracotta in
	 * identical badlands, in a chunk near spawn that the server had saved and reloaded. Blocks, block entities,
	 * biomes and structure starts are all identical.
	 *
	 * <p>One instruction's operand: the getter reads the vanilla-shaped field instead of NeoForge's widened one,
	 * which leaves both the write path and the read path agreeing with vanilla. The field and its constructor
	 * stay where they are, so anything that asks NeoForge's own accessor for them still gets an answer.
	 */
	private static boolean saveTheHeightmapsVanillaSaves(ClassNode node) {
		if (!CHUNK_STATUS.equals(node.name) || !savedHeightmapsEnabled()) return false;
		if (!hasField(node, HEIGHTMAPS_AFTER, ENUM_SET_DESC)) return false;
		int rebased = 0;
		for (MethodNode method : node.methods) {
			if (!"getChunkSaveHeightmaps".equals(method.name)) continue;
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof FieldInsnNode read) || read.getOpcode() != Opcodes.GETFIELD
						|| !CHUNK_STATUS.equals(read.owner) || !CHUNK_SAVE_HEIGHTMAPS.equals(read.name)) {
					continue;
				}
				read.name = HEIGHTMAPS_AFTER;
				rebased++;
			}
		}
		if (rebased == 0) return false;
		ForbricLog.info("[Forbric/MergedBaseCompat] ChunkStatus now reports vanilla's saved-heightmap set (%d read(s)) "
				+ "— NeoForge widened it with the two worldgen heightmaps, which an unfinished chunk then reloads "
				+ "stale, and those are what decide the Y a decoration is placed at", rebased);
		return true;
	}

	/**
	 * Puts NeoForge's biome/structure modifier pass behind a guard instead of behind a neuter.
	 *
	 * <p>{@code ServerLifecycleHooks.runModifiers} was neutered because {@code neoforge:biome_modifier} was not a
	 * declared datapack registry, and its first instruction is a {@code lookupOrThrow} for exactly that. It IS
	 * declared now — the kernel posts NeoForge's {@code DataPackRegistryEvent.NewRegistry} and both modifier
	 * registries come back among the declared ones — so the neuter costs every NeoForge mod that adds ores, mobs
	 * or features to a biome through {@code data/<ns>/neoforge/biome_modifier/*.json}.
	 *
	 * <p>Simply dropping the neuter is not the same thing, and the difference matters: the merged
	 * {@code DedicatedServer} and {@code IntegratedServer} both call NeoForge's {@code handleServerAboutToStart},
	 * which calls {@code runModifiers} FIRST and posts {@code ServerAboutToStartEvent} after it. An unguarded
	 * {@code lookupOrThrow} there does not cost the modifiers, it costs the boot — and it would do so on a
	 * user's machine, over a registry whose presence depends on what the kernel managed to declare that run.
	 *
	 * <p>So the CALL SITE moves to the kernel, which runs the same private method reflectively inside a
	 * try/catch and reports the modifier COUNTS either way. Counting is the point: "ran without throwing" and
	 * "applied something" are different claims, and only the second one tells a declared-but-empty registry
	 * apart from a working pipeline.
	 */
	private static boolean guardNeoForgesWorldModifierPass(ClassNode node) {
		if (!NEO_SERVER_LIFECYCLE_HOOKS.equals(node.name)) return false;
		int guarded = 0;
		for (MethodNode method : node.methods) {
			if (!"handleServerAboutToStart".equals(method.name)) continue;
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC
						|| !NEO_SERVER_LIFECYCLE_HOOKS.equals(call.owner)
						|| !"runModifiers".equals(call.name) || !RUN_MODIFIERS.equals(call.desc)) {
					continue;
				}
				call.owner = KERNEL_NEO_WORLDGEN;
				call.name = "beforeServerStart";
				guarded++;
			}
		}
		if (guarded == 0) return false;
		ForbricLog.info("[Forbric/MergedBaseCompat] NeoForge's biome/structure modifier pass now runs through the "
				+ "kernel's guard (%d call site(s)) — it used to be neutered outright, so every mod that changes a "
				+ "biome through a neoforge:biome_modifier did nothing at all", guarded);
		return true;
	}

	/** Removes only source-proved merge delegates that bypass a current public superclass implementation. */
	private boolean dropStubsThatBypassARealSuperclassMethod(ClassNode node) {
		return NativeMergeShapeRepair.production(node, classBytes).dropSuperclassStubs(node);
	}

	/**
	 * Fills in a {@code static final Logger} that survived the merge with nothing left to assign it.
	 *
	 * <p>When both families patch the same class, one family's {@code <clinit>} wins whole and the loser's
	 * assignments go with it — including assignments to fields the loser ADDED, which are kept as declarations.
	 * Such a field is then null forever, and there is no diagnostic: the class links, loads and works until
	 * something reads it.
	 *
	 * <p>A scan of the whole merged base finds exactly one logger in this state,
	 * {@code ResourceManagerRegistryLoadTask.LOGGER}, and it is read from the branch that handles a datapack
	 * entry a condition has switched OFF — which is what a Forge-family datapack does whenever it guards content
	 * on another mod being installed. So the branch meant to say "skipping this entry" threw instead, and the
	 * world would not open.
	 *
	 * <p>Only loggers, and only unwritten ones. A logger has one obvious correct value and building it needs
	 * nothing from the class; the other seven unwritten statics in this base carry codecs and callbacks that
	 * cannot be invented here and need the merge itself to stop dropping them.
	 */
	private static boolean giveTheUnwrittenLoggerAValue(ClassNode node) {
		boolean changed = false;
		for (FieldNode field : node.fields) {
			if ((field.access & Opcodes.ACC_STATIC) == 0) continue;
			if (!LOGGER_DESC.equals(field.desc)) continue;
			if (writesStatic(node, field.name)) continue;

			MethodNode clinit = findMethod(node, "<clinit>", "()V");
			if (clinit == null) {
				clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
				clinit.instructions.add(new InsnNode(Opcodes.RETURN));
				node.methods.add(clinit);
			}

			// At the TOP of <clinit>, not before the RETURN: anything else the initialiser does may log, and a
			// repair that lands last would leave exactly the window this is closing.
			InsnList assign = new InsnList();
			assign.add(new LdcInsnNode(Type.getObjectType(node.name)));
			assign.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "org/slf4j/LoggerFactory", "getLogger",
					"(Ljava/lang/Class;)Lorg/slf4j/Logger;", false));
			assign.add(new FieldInsnNode(Opcodes.PUTSTATIC, node.name, field.name, LOGGER_DESC));
			clinit.instructions.insert(assign);
			clinit.maxStack = Math.max(clinit.maxStack, 1);

			ForbricLog.warn("[Forbric/MergedBaseCompat] %s.%s is a logger the merge left with no assignment, so it "
					+ "was null forever and whichever branch reads it threw instead of logging. It is now "
					+ "initialised.", node.name.replace('/', '.'), field.name);
			changed = true;
		}
		return changed;
	}

	/** Whether anything in {@code node} assigns the static field {@code name}. */
	private static boolean writesStatic(ClassNode node, String name) {
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC
						&& node.name.equals(field.owner) && name.equals(field.name)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Points {@code getProvider} at the live map instead of the empty {@code providersByName}.
	 *
	 * <p>Only when nothing outside {@code <init>} writes {@code providersByName}: if a base ever keeps
	 * MinecraftForge's {@code register}, the field is live again and must be left alone. The declaration stays
	 * either way — removing it would break any access widener that named it, for no gain.
	 */
	private static void routeGetProviderAtTheLiveMap(ClassNode node) {
		if (!hasField(node, "providersByName", NAME_KEYED)) return;
		for (MethodNode method : node.methods) {
			if ("<init>".equals(method.name)) continue;
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
						&& node.name.equals(field.owner) && "providersByName".equals(field.name)) {
					return; // a live producer survived; nothing to reroute
				}
			}
		}
		MethodNode getProvider = findMethod(node, "getProvider",
				"(Lnet/minecraft/core/particles/ParticleType;)Lnet/minecraft/client/particle/ParticleProvider;");
		if (getProvider == null) return;
		for (AbstractInsnNode insn = getProvider.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
					&& node.name.equals(field.owner) && "providersByName".equals(field.name)
					&& NAME_KEYED.equals(field.desc)) {
				field.name = "providers";
			}
		}
	}

	/**
	 * Gives {@code KeyMapping} vanilla's {@code MAP:Ljava/util/Map;} back, as a view of the mappings by key.
	 *
	 * <p>Both ecosystems re-type vanilla's {@code MAP} to their own {@code KeyMappingLookup}; the merged class keeps
	 * those two ({@link #routeKeyMappingClickToPopulatedLookup}) and not vanilla's, so a mod compiled against vanilla
	 * that reads {@code KeyMapping.MAP} as a {@code Map} gets {@code NoSuchFieldError}. LiquidBounce reads it on every
	 * key press while a screen is open (its inventory movement), so the client died the first time a key was pressed
	 * in a world. Nothing in the merged game reads vanilla's descriptor, so the field is added rather than moved, and
	 * its value is {@code KernelKeyMappingMap}'s view of vanilla's {@code ALL}, which the merged class still keeps
	 * and fills: what vanilla's map holds, grouped on each read.
	 *
	 * <p>Public, because the access wideners that would make vanilla's private field accessible have already run when
	 * this repair adds it to preserve the public field access contract.
	 * Assigned right after {@code ALL} in {@code <clinit>}, before any mapping exists.
	 */
	private static boolean giveKeyMappingItsVanillaMap(ClassNode node) {
		if (!KEY_MAPPING.equals(node.name)) return false;
		if (hasField(node, "MAP", "Ljava/util/Map;") || !hasField(node, "ALL", "Ljava/util/Map;")) return false;
		if (!hasField(node, "MAP", "Lnet/neoforged/neoforge/client/settings/KeyMappingLookup;")) return false;
		MethodNode clinit = findMethod(node, "<clinit>", "()V");
		if (clinit == null) return false;
		FieldInsnNode all = null;
		for (AbstractInsnNode insn : clinit.instructions) {
			if (insn instanceof FieldInsnNode put && put.getOpcode() == Opcodes.PUTSTATIC && node.name.equals(put.owner)
					&& "ALL".equals(put.name) && "Ljava/util/Map;".equals(put.desc)) {
				if (all != null) return false;
				all = put;
			}
		}
		if (all == null) return false;
		node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "MAP", "Ljava/util/Map;",
				"Ljava/util/Map<Lcom/mojang/blaze3d/platform/InputConstants$Key;Ljava/util/List<Lnet/minecraft/client/KeyMapping;>;>;",
				null));
		InsnList view = new InsnList();
		view.add(new FieldInsnNode(Opcodes.GETSTATIC, node.name, "ALL", "Ljava/util/Map;"));
		view.add(new MethodInsnNode(Opcodes.INVOKESTATIC, KERNEL_KEY_MAPPING_MAP, "vanillaView",
				"(Ljava/util/Map;)Ljava/lang/Object;", false));
		view.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/Map"));
		view.add(new FieldInsnNode(Opcodes.PUTSTATIC, node.name, "MAP", "Ljava/util/Map;"));
		clinit.instructions.insert(all, view);
		clinit.maxStack = Math.max(clinit.maxStack, 1);
		ForbricLog.info("[Forbric/MergedBaseCompat] KeyMapping.MAP has vanilla's descriptor again, as a view of the key "
				+ "mappings by key — both ecosystems re-typed it to their own KeyMappingLookup, so a mod reading it as "
				+ "vanilla's Map could not link to it");
		return true;
	}

	/** Removes source-proved delegates only when the complete default hierarchy has one dispatch target. */
	private boolean dropInterfaceDefaultShadowingOverrides(ClassNode node) {
		return NativeMergeShapeRepair.production(node, classBytes).dropDefaultStubs(node);
	}

	private static final String STACK_COUNT_MESSAGE = "The stack count must be 1";

	/**
	 * Lets a creative tab SKIP an empty stack instead of aborting the whole creative menu.
	 *
	 * <p>{@code CreativeModeTab.Output.accept(ItemLike)} turns its argument into {@code new ItemStack(itemLike)},
	 * which collapses to {@code ItemStack.EMPTY} (count 0) whenever the block has no item form. NeoForge's output
	 * wrapper treats that as a programming error and throws {@code IllegalArgumentException: The stack count must
	 * be 1}; MinecraftForge's path just drops the entry.
	 *
	 * <p>On the merged base NeoForge won {@code CreativeModeTab.buildContents}, so a MINECRAFTFORGE mod's tab is
	 * validated by NEOFORGE's stricter contract — a cross-ecosystem split like the Forge/NeoForge {@code FluidType}
	 * one. Macaw's Bridges feeds its blocks in with {@code accept(ItemLike)}, one of them has no item, and the throw
	 * propagated out of {@code CreativeModeTabs.buildAllTabContents} into
	 * {@code CreativeModeInventoryScreen.<init>} — so opening the creative menu at all crashed the client, and NO
	 * tab (vanilla or modded) was reachable.
	 *
	 * <p>Rewriting the throw to a {@code return} makes the wrapper drop that one entry and keep building, which is
	 * the MinecraftForge behaviour the mod was written against. Only the throw is replaced; the count==1 fast path
	 * is untouched, so well-formed stacks still take the normal route.
	 */
	private static boolean tolerateEmptyCreativeTabStacks(ClassNode node) {
		if (!"net/neoforged/neoforge/event/EventHooks".equals(node.name) || node.methods == null) return false;

		boolean changed = false;
		for (MethodNode method : node.methods) {
			if (!method.name.startsWith("lambda$onCreativeModeTabBuildContents$")) continue;
			changed |= replaceStackCountThrowWithReturn(method);
		}
		if (!changed) return false;

		ForbricLog.warn("[Forbric/MergedBaseCompat] creative-tab output now SKIPS empty stacks instead of throwing "
				+ "— NeoForge won CreativeModeTab.buildContents on the merged base and its stricter contract was "
				+ "aborting the whole creative menu for MinecraftForge mods (Macaw's Bridges)");
		return true;
	}

	/** Replaces {@code throw new IllegalArgumentException("The stack count must be 1")} with a plain {@code return}. */
	private static boolean replaceStackCountThrowWithReturn(MethodNode method) {
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (!(insn instanceof LdcInsnNode ldc) || !STACK_COUNT_MESSAGE.equals(ldc.cst)) continue;

			AbstractInsnNode start = insn;
			while (start != null && !(start.getOpcode() == Opcodes.NEW && start instanceof TypeInsnNode type
					&& "java/lang/IllegalArgumentException".equals(type.desc))) {
				start = start.getPrevious();
			}
			AbstractInsnNode end = insn;
			while (end != null && end.getOpcode() != Opcodes.ATHROW) {
				end = end.getNext();
			}
			if (start == null || end == null) continue;

			// The whole new/dup/ldc/<init>/athrow run pushes and consumes only its own operands, so swapping it for a
			// RETURN leaves the stack exactly as the following frames already describe it.
			method.instructions.insertBefore(start, new MethodInsnNode(Opcodes.INVOKESTATIC,
					"net/forbric/kernel/boot/KernelLifecycle", "onCreativeTabEntrySkipped", "()V", false));
			method.instructions.insertBefore(start, new InsnNode(Opcodes.RETURN));
			for (AbstractInsnNode cur = start; cur != null;) {
				AbstractInsnNode next = cur == end ? null : cur.getNext();
				method.instructions.remove(cur);
				cur = next;
			}
			return true;
		}
		return false;
	}

	private static boolean hasMethod(ClassNode node, String name, String desc) {
		for (MethodNode method : node.methods) {
			if (method.name.equals(name) && method.desc.equals(desc)) return true;
		}
		return false;
	}

	/**
	 * Takes the loader brand out of the window title.
	 *
	 * <p>{@code Minecraft.createTitle} builds "Minecraft" and then, when the game reports itself as modified, splices
	 * in a space, the loader's name and an asterisk before the version — so the merged base, whose title patch is
	 * NeoForge's, puts "NeoForge" on the window of an instance that is running Fabric, MinecraftForge and NeoForge
	 * mods side by side. Naming one of the three is worse than naming none.
	 *
	 * <p>The brand and its leading space go; the asterisk stays, which is vanilla's own mark for a modified game and
	 * leaves the title reading "Minecraft* 26.2". Only that one append chain is touched, so a title patch that
	 * changes shape is left alone rather than half-rewritten.
	 */
	private static final String ITEM_STACK = "net/minecraft/world/item/ItemStack";
	private static final String ATTRIBUTE_MODIFIERS_TYPE = "net/minecraft/world/item/component/ItemAttributeModifiers";
	private static final String DATA_COMPONENTS = "net/minecraft/core/component/DataComponents";
	private static final String NEO_ATTRIBUTES = "getAttributeModifiers";

	/**
	 * Gives an item's attributes back to the mod that computes them — which is what elytra flight hangs off.
	 *
	 * <p>The merge split one mechanism down the middle. {@code LivingEntity.canGlide} came from NeoForge, and
	 * NeoForge's version does not look at the item at all: it asks whether the entity has the
	 * {@code neoforge:gliding_flight} attribute above zero. {@code ItemStack.forEachModifier} came from vanilla
	 * (Forge leaves it alone), and vanilla's version reads the raw {@code ATTRIBUTE_MODIFIERS} component. NeoForge's
	 * version calls {@code getAttributeModifiers()}, whose whole purpose is to post
	 * {@code ItemAttributeModifierEvent} — and {@code NeoForgeMod.onItemAttributeModifiers} is the ONLY thing
	 * anywhere that adds the gliding attribute, off the item's {@code minecraft:glider} component.
	 *
	 * <p>So the producer was on one side of the merge and the consumer on the other: the attribute is a
	 * {@code BooleanAttribute} defaulting to false, nothing ever raises it, {@code canGlide()} is permanently
	 * false, {@code tryToStartFallFlying} refuses and {@code updateFallFlying} clears the flag every tick. Elytra
	 * simply does not work, with no error anywhere.
	 *
	 * <p>The damage is wider than elytra — every mod that adds a modifier through that event was being ignored, and
	 * elytra is only the case vanilla itself routes through it. The repair points the read at NeoForge's computed
	 * answer: four instructions become one, same stack shape, no branch and no frame.
	 *
	 * <p>The merge-conflict report does not list this method. NeoForge's patch here is an unqualified call to a
	 * method on {@code ItemStack} itself — an interface default from {@code IItemStackExtension}, which the merged
	 * class still implements — so it names nothing under {@code net/neoforged/} for a detector to notice.
	 */
	private static boolean askNeoForgeWhatAnItemsAttributesAre(ClassNode node) {
		if (!ITEM_STACK.equals(node.name)) return false;
		boolean changed = false;
		for (MethodNode method : node.methods) {
			if (!"forEachModifier".equals(method.name) || method.instructions == null) continue;
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof FieldInsnNode type) || type.getOpcode() != Opcodes.GETSTATIC
						|| !DATA_COMPONENTS.equals(type.owner) || !"ATTRIBUTE_MODIFIERS".equals(type.name)) {
					continue;
				}
				AbstractInsnNode empty = nextReal(type);
				AbstractInsnNode fetch = nextReal(empty);
				AbstractInsnNode cast = nextReal(fetch);
				// The exact vanilla shape and nothing else: getOrDefault(ATTRIBUTE_MODIFIERS, EMPTY) then a cast.
				if (!(empty instanceof FieldInsnNode e) || e.getOpcode() != Opcodes.GETSTATIC
						|| !ATTRIBUTE_MODIFIERS_TYPE.equals(e.owner) || !"EMPTY".equals(e.name)) {
					continue;
				}
				if (!(fetch instanceof MethodInsnNode f) || !"getOrDefault".equals(f.name)) continue;
				if (!(cast instanceof TypeInsnNode c) || c.getOpcode() != Opcodes.CHECKCAST
						|| !ATTRIBUTE_MODIFIERS_TYPE.equals(c.desc)) {
					continue;
				}
				AbstractInsnNode after = cast.getNext();
				method.instructions.insert(cast, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ITEM_STACK,
						NEO_ATTRIBUTES, "()L" + ATTRIBUTE_MODIFIERS_TYPE + ";", false));
				for (AbstractInsnNode dead : new AbstractInsnNode[] { type, empty, fetch, cast }) {
					method.instructions.remove(dead);
				}
				insn = after == null ? method.instructions.getLast() : after;
				changed = true;
			}
		}
		if (changed) {
			ForbricLog.info("[Forbric/MergedBaseCompat] ItemStack.forEachModifier now asks NeoForge what an item's "
					+ "attributes are instead of reading the raw component — the merge took NeoForge's canGlide, which "
					+ "reads an attribute only NeoForge's ItemAttributeModifierEvent ever sets, and vanilla's reader, "
					+ "which never posts it. Elytra flight was the visible half of that");
		}
		return changed;
	}
	private static boolean attributesAlreadyComputed(ClassNode node) {
		if (!ITEM_STACK.equals(node.name)) return false;
		List<MethodNode> methods = node.methods.stream().filter(m -> "forEachModifier".equals(m.name)).toList();
		return !methods.isEmpty() && methods.stream().allMatch(m -> java.util.Arrays.stream(m.instructions.toArray()).anyMatch(i ->
				i instanceof MethodInsnNode call && node.name.equals(call.owner) && NEO_ATTRIBUTES.equals(call.name)
						&& ("()L" + ATTRIBUTE_MODIFIERS_TYPE + ";").equals(call.desc))
				&& java.util.Arrays.stream(m.instructions.toArray()).noneMatch(i -> i instanceof FieldInsnNode field
						&& DATA_COMPONENTS.equals(field.owner) && "ATTRIBUTE_MODIFIERS".equals(field.name)));
	}

	static final String LOOT_MODIFIER_MANAGER_NEO = ForeignType.LOOT_MODIFIER_MANAGER.internal(Ecosystem.NEOFORGE);
	static final String SIMPLE_JSON_LISTENER = "net/minecraft/server/packs/resources/SimpleJsonResourceReloadListener";
	static final String PREPARE = "prepare";
	static final String PREPARE_DESC = "(Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)Ljava/util/Map;";
	static final String KERNEL_LOOT_MODIFIERS = "net/forbric/kernel/runtime/KernelLootModifiers";
	static final String FEATURE_FLAGS = "net/minecraft/world/flag/FeatureFlags";
	static final String NEO_FEATURE_FLAG_LOADER = "net/neoforged/neoforge/common/util/flag/FeatureFlagLoader";
	static final String KERNEL_FEATURE_FLAGS = "net/forbric/kernel/runtime/KernelFeatureFlags";
	static final String LOAD_MODDED_FLAGS = "loadModdedFlags";
	static final String LOAD_MODDED_FLAGS_DESC = "(Lnet/minecraft/world/flag/FeatureFlagRegistry$Builder;)V";

	/**
	 * Sends {@code FeatureFlags.<clinit>}'s call to NeoForge's {@code FeatureFlagLoader.loadModdedFlags} to the kernel.
	 *
	 * <p>NeoForge reads each mod's declared flag file through {@code IModFile.getContents()}, and the kernel's mod
	 * files carry no jar contents, so that walk found nothing and a mod asking {@code FeatureFlags.REGISTRY} for
	 * its own flag died in its static initialiser. Same descriptor, same moment, owner swapped; the kernel helper
	 * reads the same file from the jar. Idempotent: an already-swapped call is left alone.
	 */
	private static boolean letModdedFeatureFlagsRegister(ClassNode node) {
		if (!FEATURE_FLAGS.equals(node.name)) return false;
		MethodNode clinit = findMethod(node, "<clinit>", "()V");
		if (clinit == null) return false;
		int swapped = 0;
		for (AbstractInsnNode insn = clinit.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
					&& NEO_FEATURE_FLAG_LOADER.equals(call.owner) && LOAD_MODDED_FLAGS.equals(call.name)
					&& LOAD_MODDED_FLAGS_DESC.equals(call.desc)) {
				call.owner = KERNEL_FEATURE_FLAGS;
				swapped++;
			}
		}
		if (swapped == 0) return false;
		ForbricLog.info("[Forbric/MergedBaseCompat] FeatureFlags now asks the kernel for NeoForge mods' declared feature flags — "
				+ "NeoForge's own loader walks jar contents the kernel's mod files do not carry, so those flags were never "
				+ "registered and a mod asking for its own died in its static initialiser");
		return true;
	}
	/**
	 * The vanilla atlas loader whose allocation the merge left gated on MinecraftForge's opt-in. Named for the
	 * anchor ledger only: the repair finds its target by the proof in {@link AtlasMipBoundsRepair}, never by name.
	 */
	static final String SPRITE_LOADER = "net/minecraft/client/renderer/texture/SpriteLoader";
	/** {@code -Dforbric.mipmapLowering=off} disables the allocation-boundary constraint check. */
	static final String MIPMAP_PROPERTY = "forbric.mipmapLowering";

	static boolean mipmapLoweringEnabled() {
		return !"off".equalsIgnoreCase(System.getProperty(MIPMAP_PROPERTY, "on"));
	}

	/**
	 * Bounds the selected mip level at the native allocation boundary. Configuration getters keep their real
	 * value and side effects; a level unsupported by the smallest image cannot be allocated regardless of policy.
	 * The dataflow proof derives the limit and chosen local from the actual branch and Stitcher consumer.
	 */
	private static boolean letTheAtlasLowerItsMipLevelLikeVanilla(ClassNode node) {
		if (!mipmapLoweringEnabled()) return false;
		return AtlasMipBoundsRepair.apply(node);
	}

	static final String INPUT_CONSTANTS = "com/mojang/blaze3d/platform/InputConstants";
	/** {@code -Dforbric.keyModifierSuffix=off} hands the whole value back to vanilla's parse, which throws on it. */
	static final String KEY_SUFFIX_PROPERTY = "forbric.keyModifierSuffix";

	static boolean keyModifierSuffixEnabled() {
		return !"off".equalsIgnoreCase(System.getProperty(KEY_SUFFIX_PROPERTY, "on"));
	}

	/**
	 * {@code InputConstants.getKey} must not be handed MinecraftForge's {@code ":MODIFIER"} suffix.
	 *
	 * <p>MinecraftForge extends a key binding with a modifier and WRITES it into options.txt as
	 * {@code key_key.jei.toggleOverlay:key.keyboard.o:CONTROL_OR_COMMAND}. Its own
	 * {@code Options.processOptionsKeysOnly} then reads that value and calls {@code InputConstants.getKey(value)}
	 * with the whole string BEFORE splitting the modifier off — and vanilla's {@code getKey} does
	 * {@code Integer.parseInt("o:CONTROL_OR_COMMAND")}. That is MinecraftForge's own code, unchanged by the merge:
	 * the same instruction order is in the forge-patched base, so this is not a merge artifact and switching it off
	 * does not restore anything.
	 *
	 * <p>What it costs is out of all proportion to one key: {@code Options.load} wraps the whole file in one
	 * try/catch, so a single modded binding with a modifier makes the client log "Failed to load options" and the
	 * player loses EVERY setting — video, controls, language, and the accessibility-onboarding flag, which then
	 * sits in front of the game on the next launch. JEI binds three of them by default.
	 *
	 * <p>The repair is the smallest thing that can be said: {@code name = name.split(":")[0]} at method entry. No
	 * key name in {@code Key.NAME_MAP} contains a colon, so a name without one is unchanged, and MinecraftForge's
	 * own modifier parse two instructions later still reads the suffix off the original value. Branch-free on
	 * purpose — this transformer writes with {@code ClassWriter(0)} and computes no frames.
	 */
	private static boolean dropTheKeyModifierSuffixBeforeParsingAKeyName(ClassNode node) {
		if (!INPUT_CONSTANTS.equals(node.name) || !keyModifierSuffixEnabled()) return false;
		MethodNode getKey = findMethod(node, "getKey", "(Ljava/lang/String;)Lcom/mojang/blaze3d/platform/InputConstants$Key;");
		if (getKey == null || getKey.instructions.size() == 0) return false;
		// Idempotent: the first instruction of a repaired method is the ALOAD 0 of this prologue followed by the
		// split. Re-running the pass over an already-written class must not stack a second copy.
		for (AbstractInsnNode insn = getKey.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof MethodInsnNode call && "java/lang/String".equals(call.owner)
					&& "split".equals(call.name)) {
				return false;
			}
		}
		InsnList prologue = new InsnList();
		prologue.add(new VarInsnNode(Opcodes.ALOAD, 0));
		prologue.add(new LdcInsnNode(":"));
		prologue.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "split",
				"(Ljava/lang/String;)[Ljava/lang/String;", false));
		prologue.add(new InsnNode(Opcodes.ICONST_0));
		prologue.add(new InsnNode(Opcodes.AALOAD));
		prologue.add(new VarInsnNode(Opcodes.ASTORE, 0));
		getKey.instructions.insert(prologue);
		getKey.maxStack = Math.max(getKey.maxStack, 2);
		ForbricLog.info("[Forbric/MergedBaseCompat] InputConstants.getKey now drops MinecraftForge's \":MODIFIER\" "
				+ "suffix before parsing a key name — one modded binding with a modifier used to throw out of "
				+ "options.txt parsing, and Options.load wraps the WHOLE file, so the player lost every setting");
		return true;
	}

	static final String WITHOUT_INDEX = "withoutTheLegacyIndex";
	static final String WITHOUT_INDEX_DESC = "(Lnet/minecraft/server/packs/resources/ResourceManager;)Lnet/minecraft/server/packs/resources/ResourceManager;";

	/**
	 * MinecraftForge's loot-modifier manager reads {@code loot_modifiers/global_loot_modifiers.json} BY NAME as
	 * its list of enabled modifiers, then scans the directory and drops what the list does not name; NeoForge's
	 * has no list-file concept, scans the same directory with {@code IGlobalLootModifier.DIRECT_CODEC}, and logs
	 * {@code Couldn't parse data file '…global_loot_modifiers'} for every index it meets — two permanent ERROR
	 * lines on every tri-ecosystem boot (the MinecraftForge carrier ships one, mods ship another), which is what
	 * makes a genuinely broken loot modifier indistinguishable from the furniture.
	 *
	 * <p>NeoForge's DIRECTORY scan now runs over a view of the resource manager that hides
	 * {@code *&#47;loot_modifiers/global_loot_modifiers.json}: its manager has no {@code prepare} of its own, so one is
	 * synthesized ({@code super.prepare(withoutTheLegacyIndex(rm), p)}). Universal jars still ship the index for their
	 * MinecraftForge half. (MinecraftForge's own manager got the same wrap; that half went with MinecraftForge.)
	 */
	private static boolean hideTheLegacyLootModifierIndexFromTheDirectoryScan(ClassNode node) {
		if (LOOT_MODIFIER_MANAGER_NEO.equals(node.name)) return synthesizeNeoForgePrepare(node);
		return false;
	}

	private static boolean synthesizeNeoForgePrepare(ClassNode node) {
		if (!SIMPLE_JSON_LISTENER.equals(node.superName)) {
			ForbricLog.warn("[Forbric/MergedBaseCompat] %s no longer extends SimpleJsonResourceReloadListener — the legacy "
					+ "loot-modifier index is not hidden from its scan", node.name.replace('/', '.'));
			return false;
		}
		if (findMethod(node, PREPARE, PREPARE_DESC) != null) return false;    // its own prepare now, or a second pass
		MethodNode prepare = new MethodNode(Opcodes.ACC_PROTECTED, PREPARE, PREPARE_DESC, null, null);
		prepare.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		prepare.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
		prepare.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, KERNEL_LOOT_MODIFIERS, WITHOUT_INDEX, WITHOUT_INDEX_DESC, false));
		prepare.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
		prepare.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, SIMPLE_JSON_LISTENER, PREPARE, PREPARE_DESC, false));
		prepare.instructions.add(new InsnNode(Opcodes.ARETURN));
		prepare.maxStack = 3;
		prepare.maxLocals = 3;
		node.methods.add(prepare);
		ForbricLog.info("[Forbric/MergedBaseCompat] NeoForge's LootModifierManager scans loot_modifiers/ without the legacy "
				+ "global_loot_modifiers.json index (applied at 1 site) — it has no list-file concept and logged a parse "
				+ "error for each one");
		return true;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// A pack.mcmeta overlay gated by a condition no evaluator here can judge
	// ---------------------------------------------------------------------------------------------------------------

	static final String OVERLAY_ENTRY = "net/minecraft/server/packs/OverlayMetadataSection$OverlayEntry";
	static final String LIST_CODEC_FOR_PACK_TYPE = "listCodecForPackType";
	static final String LIST_CODEC_DESC = "(Lnet/minecraft/server/packs/PackType;)Lcom/mojang/serialization/Codec;";
	static final String CONDITIONAL_OPS_NEO = "net/neoforged/neoforge/common/conditions/ConditionalOps";
	static final String DECODE_LIST_WITH_CONDITIONS = "decodeListWithElementConditions";
	static final String KERNEL_NEO_CONDITIONS_CLASS = "net/forbric/kernel/runtime/KernelNeoConditions";

	/**
	 * A data file gated by a condition the NeoForge evaluator cannot judge is IGNORED (the owning ecosystem's
	 * evaluator judges it afterwards — see {@code KernelNeoConditions}). A pack.mcmeta overlay entry has no
	 * afterwards: {@code Pack.readPackMetadata} unions both sections' overlays, so an ignored condition MOUNTS the
	 * directory. Measured on Terralith with {@code vanilla_stone_gen=false}: six placed-feature overrides under
	 * {@code enable.vanilla_stone_gen} went into the world anyway.
	 *
	 * <p>One stack-neutral insertion after {@code ConditionalOps.decodeListWithElementConditions} in
	 * {@code OverlayEntry.listCodecForPackType} — the funnel both the vanilla {@code overlays} and the
	 * {@code neoforge:overlays} section read through — wraps the list codec with
	 * {@code KernelNeoConditions.forOverlayEntries}, which makes the leniency answer a foreign type with a VETO for
	 * the duration of that decode. NeoForge's own decoder then drops the entry.
	 */
	private static boolean vetoUnjudgeableOverlayConditions(ClassNode node) {
		if (!OVERLAY_ENTRY.equals(node.name)) return false;
		MethodNode method = findMethod(node, LIST_CODEC_FOR_PACK_TYPE, LIST_CODEC_DESC);
		if (method == null) return false;
		MethodInsnNode site = null;
		int sites = 0;
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
					&& CONDITIONAL_OPS_NEO.equals(call.owner) && DECODE_LIST_WITH_CONDITIONS.equals(call.name)
					&& CODEC_TO_CODEC.equals(call.desc)) {
				sites++;
				site = call;
			}
		}
		if (sites != 1) {
			if (sites > 1) {
				ForbricLog.warn("[Forbric/MergedBaseCompat] %s.%s reads its overlay list through %d conditional codecs, "
						+ "not one — not wrapped", OVERLAY_ENTRY, LIST_CODEC_FOR_PACK_TYPE, sites);
			}
			return false;
		}
		if (nextReal(site) instanceof MethodInsnNode already && KERNEL_NEO_CONDITIONS_CLASS.equals(already.owner)) {
			return false;    // already wrapped: idempotent
		}
		method.instructions.insert(site, new MethodInsnNode(Opcodes.INVOKESTATIC, KERNEL_NEO_CONDITIONS_CLASS,
				"forOverlayEntries", CODEC_TO_CODEC, false));
		ForbricLog.info("[Forbric/MergedBaseCompat] pack.mcmeta overlay entries gated by a condition no evaluator here can "
				+ "judge are now VETOED through NeoForge's own drop path (applied at 1 site) — ignoring the condition used "
				+ "to mount content a mod's own config had turned off");
		return true;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// A javac switch map whose synthetic holder class the merge replaced
	// ---------------------------------------------------------------------------------------------------------------

	/** Inlines a missing compiler map only from its hash-verified native initializer and actual enum fields. */
	private boolean inlineTheSwitchMapTheMergeLost(ClassNode node) {
		return NativeMergeShapeRepair.production(node, classBytes).inlineLostSwitchMaps(node);
	}

	private static AbstractInsnNode nextReal(AbstractInsnNode cursor) {
		AbstractInsnNode next = cursor == null ? null : cursor.getNext();
		while (next != null && next.getOpcode() < 0) next = next.getNext();
		return next;
	}

	private static final String INTEGRATED_SERVER = "net/minecraft/client/server/IntegratedServer";
	private static final String TEARDOWN_PUBLISHED_STATE = "teardownPublishedState";
	private static final String FORBRIC_LOG = "net/forbric/kernel/util/ForbricLog";

	/**
	 * Stops a throw in {@code IntegratedServer.teardownPublishedState} from costing the world save.
	 *
	 * <p>{@code IntegratedServer.stopServer()} is two calls and a return:
	 *
	 * <pre>
	 *   0: aload_0; invokevirtual teardownPublishedState:()V
	 *   4: aload_0; invokespecial MinecraftServer.stopServer:()V
	 *   8: return
	 * </pre>
	 *
	 * <p>with no exception table. Everything durable happens in the SECOND call — {@code MinecraftServer
	 * .stopServer} is where "Saving players", {@code PlayerList.saveAll}, "Saving worlds" and the chunk flush
	 * live — so anything the first call throws takes the entire save with it. {@code MinecraftServer.runServer}
	 * catches it one frame up and logs "Exception stopping the server", which reads like a tidy-up problem.
	 *
	 * <p>It is not hypothetical. Measured on a real install: an Alt+F4 with a chat glyph still unbaked ran
	 * {@code teardownPublishedState -> updateCommandsAllowedForOtherPlayers -> LocalPlayer.refreshChatAbilities},
	 * which re-splits the chat log, which bakes a glyph, which asserts the render thread — on the server thread.
	 * The region files still reached disk because the chunk storage closes itself, but {@code level.dat} was an
	 * autosave old, so the player's position, inventory and the world clock were sixty seconds behind.
	 *
	 * <p>Not the kernel's damage: this ordering is byte-identical in the untouched MinecraftForge base and the
	 * untouched NeoForge base, so it is upstream shape. It is repaired here anyway because this is a base the
	 * kernel owns and the cost is a player's data.
	 *
	 * <p>The wrap is deliberately narrow — the try covers the teardown call and nothing else — and the order is
	 * left exactly as upstream wrote it. Reordering the two calls would also have saved first, but it would have
	 * moved when the LAN pinger stops and when the multiplayer scope flips, which is a behaviour change to buy
	 * something a two-instruction exception range already buys.
	 */
	private static boolean keepTheSaveOffTheTeardownsFailurePath(ClassNode node) {
		if (!INTEGRATED_SERVER.equals(node.name)) return false;
		MethodNode stop = findMethod(node, "stopServer", "()V");
		if (stop == null || stop.instructions == null || stop.instructions.size() == 0) return false;
		// An exception table here means a previous pass already wrapped it, or the shape is not the one read
		// above. Either way this pass has nothing it can safely say about the body.
		if (stop.tryCatchBlocks != null && !stop.tryCatchBlocks.isEmpty()) return false;

		MethodInsnNode teardown = null;
		for (AbstractInsnNode insn : stop.instructions) {
			if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEVIRTUAL
					&& TEARDOWN_PUBLISHED_STATE.equals(call.name) && "()V".equals(call.desc)) {
				teardown = call;
				break;
			}
		}
		if (teardown == null) return false;
		// The receiver push has to be inside the protected range too, or the handler would be entered with a
		// half-built stack. Only the `aload_0; invokevirtual` pair is a shape this pass understands.
		AbstractInsnNode receiver = previousReal(teardown.getPrevious());
		if (!(receiver instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD || load.var != 0) {
			return false;
		}
		// And the save must actually be downstream of it — wrapping a teardown that nothing follows would cost
		// the report without buying the save.
		if (!callsSuperStopServer(teardown)) return false;

		LabelNode start = new LabelNode();
		LabelNode end = new LabelNode();
		LabelNode handler = new LabelNode();
		LabelNode after = new LabelNode();
		stop.instructions.insertBefore(receiver, start);

		InsnList tail = new InsnList();
		tail.add(end);
		tail.add(new JumpInsnNode(Opcodes.GOTO, after));
		tail.add(handler);
		tail.add(new FrameNode(Opcodes.F_FULL, 1, new Object[] { node.name }, 1,
				new Object[] { "java/lang/Throwable" }));
		tail.add(new LdcInsnNode("[Forbric/Shutdown] the integrated server's published-state teardown threw on the "
				+ "way out - saving the world anyway. Upstream runs that teardown BEFORE MinecraftServer"
				+ ".stopServer, which is where players and worlds are written, so this used to end the process "
				+ "with level.dat still at the last autosave"));
		tail.add(new InsnNode(Opcodes.SWAP));
		tail.add(new MethodInsnNode(Opcodes.INVOKESTATIC, FORBRIC_LOG, "warn",
				"(Ljava/lang/String;Ljava/lang/Throwable;)V", false));
		tail.add(after);
		tail.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
		stop.instructions.insert(teardown, tail);

		stop.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Throwable"));
		stop.maxStack = Math.max(stop.maxStack, 2);
		ForbricLog.info("[Forbric/MergedBaseCompat] IntegratedServer.stopServer now saves even if the published-state "
				+ "teardown throws — upstream runs the teardown first and unguarded, so one throw on the way out "
				+ "skipped the player and world save entirely");
		return true;
	}

	/** Whether the super call that performs the save still follows the teardown in this body. */
	private static boolean callsSuperStopServer(MethodInsnNode teardown) {
		for (AbstractInsnNode insn = teardown.getNext(); insn != null; insn = insn.getNext()) {
			if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
					&& "stopServer".equals(call.name) && "()V".equals(call.desc)) {
				return true;
			}
		}
		return false;
	}

	private static boolean dropTheWindowTitlesLoaderBrand(ClassNode node) {
		if (!"net/minecraft/client/Minecraft".equals(node.name)) return false;
		MethodNode createTitle = findMethod(node, "createTitle", "()Ljava/lang/String;");
		if (createTitle == null) return false;

		for (AbstractInsnNode insn = createTitle.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (!(insn instanceof LdcInsnNode brand) || !LOADER_BRANDS.contains(brand.cst)) continue;
			AbstractInsnNode appendBrand = insn.getNext();
			if (!isStringBuilderAppend(appendBrand, "(Ljava/lang/String;)Ljava/lang/StringBuilder;")) continue;
			// The separator the brand arrives with: BIPUSH ' '; append(char). Without it the shape is not the one
			// this fixup was written for.
			AbstractInsnNode appendSpace = previousRealInsn(insn);
			AbstractInsnNode space = previousRealInsn(appendSpace);
			if (!isStringBuilderAppend(appendSpace, "(C)Ljava/lang/StringBuilder;")
					|| space == null || space.getOpcode() != Opcodes.BIPUSH
					|| ((org.objectweb.asm.tree.IntInsnNode) space).operand != ' ') {
				continue;
			}
			for (AbstractInsnNode dead : new AbstractInsnNode[] {space, appendSpace, insn, appendBrand}) {
				createTitle.instructions.remove(dead);
			}
			ForbricLog.info("[Forbric/MergedBaseCompat] took \"%s\" out of the window title — the merged base carries "
					+ "one loader's title patch, and this instance runs all three ecosystems", brand.cst);
			return true;
		}
		return false;
	}

	private static boolean isStringBuilderAppend(AbstractInsnNode insn, String desc) {
		return insn instanceof MethodInsnNode call && "java/lang/StringBuilder".equals(call.owner)
				&& "append".equals(call.name) && desc.equals(call.desc);
	}

	private static final java.util.Set<Object> LOADER_BRANDS = java.util.Set.of("NeoForge", "Forge", "Fabric");

	private static AbstractInsnNode previousRealInsn(AbstractInsnNode from) {
		if (from == null) return null;
		for (AbstractInsnNode insn = from.getPrevious(); insn != null; insn = insn.getPrevious()) {
			if (insn.getOpcode() >= 0) return insn;
		}
		return null;
	}

	private static MethodNode findMethod(ClassNode node, String name, String desc) {
		for (MethodNode method : node.methods) {
			if (method.name.equals(name) && method.desc.equals(desc)) return method;
		}
		return null;
	}

	private static boolean hasField(ClassNode node, String name, String desc) {
		for (FieldNode field : node.fields) {
			if (field.name.equals(name) && field.desc.equals(desc)) return true;
		}
		return false;
	}

	private static Handle repairLambdaHandle(ClassNode owner, Map<String, MethodNode> methods, MethodNode caller,
			InvokeDynamicInsnNode indy, Handle handle) {
		if (!owner.name.equals(handle.getOwner()) || !handle.getName().startsWith("lambda$")) return handle;

		MethodNode target = methods.get(handle.getName() + handle.getDesc());
		if (target == null) return handle;

		boolean methodStatic = (target.access & Opcodes.ACC_STATIC) != 0;
		boolean handleStatic = handle.getTag() == Opcodes.H_INVOKESTATIC;
		if (methodStatic == handleStatic) return handle;

		if (!methodStatic && handleStatic) {
			if (!capturesOwner(owner, indy.desc)) {
				if ((caller.access & Opcodes.ACC_STATIC) != 0 || !prependThisCapture(owner, caller, indy)) {
					return handle;
				}
			}
			ForbricLog.warn("[Forbric/MergedBaseCompat] repaired lambda bootstrap handle %s.%s%s "
					+ "from static to instance; invokedynamic is now %s",
					owner.name.replace('/', '.'), handle.getName(), handle.getDesc(), indy.desc);
			return new Handle(Opcodes.H_INVOKEVIRTUAL, handle.getOwner(), handle.getName(), handle.getDesc(), false);
		}

		ForbricLog.warn("[Forbric/MergedBaseCompat] repaired lambda bootstrap handle %s.%s%s from tag %d to %d",
				owner.name.replace('/', '.'), handle.getName(), handle.getDesc(), handle.getTag(), Opcodes.H_INVOKESTATIC);
		return new Handle(Opcodes.H_INVOKESTATIC, handle.getOwner(), handle.getName(), handle.getDesc(), false);
	}

	private static boolean capturesOwner(ClassNode owner, String invokedynamicDesc) {
		Type[] args = Type.getArgumentTypes(invokedynamicDesc);
		return args.length > 0 && args[0].getSort() == Type.OBJECT && owner.name.equals(args[0].getInternalName());
	}

	private static boolean prependThisCapture(ClassNode owner, MethodNode caller, InvokeDynamicInsnNode indy) {
		AbstractInsnNode insertionPoint = capturedArgsStart(indy);
		if (insertionPoint == null) return false;
		caller.instructions.insertBefore(insertionPoint, new VarInsnNode(Opcodes.ALOAD, 0));
		indy.desc = prependArgument(Type.getObjectType(owner.name), indy.desc);
		caller.maxStack = Math.max(caller.maxStack, caller.maxStack + 1);
		return true;
	}

	private static AbstractInsnNode capturedArgsStart(InvokeDynamicInsnNode indy) {
		Type[] args = Type.getArgumentTypes(indy.desc);
		if (args.length == 0) return indy;

		AbstractInsnNode cursor = indy.getPrevious();
		AbstractInsnNode first = null;
		for (int i = args.length - 1; i >= 0; i--) {
			cursor = previousReal(cursor);
			if (!isLocalLoadFor(args[i], cursor)) return null;
			first = cursor;
			cursor = cursor.getPrevious();
		}
		return first;
	}

	private static AbstractInsnNode previousReal(AbstractInsnNode cursor) {
		while (cursor != null && cursor.getOpcode() < 0) {
			cursor = cursor.getPrevious();
		}
		return cursor;
	}

	private static boolean isLocalLoadFor(Type type, AbstractInsnNode insn) {
		return insn instanceof VarInsnNode var && var.getOpcode() == loadOpcode(type);
	}

	private static int loadOpcode(Type type) {
		return switch (type.getSort()) {
			case Type.LONG -> Opcodes.LLOAD;
			case Type.FLOAT -> Opcodes.FLOAD;
			case Type.DOUBLE -> Opcodes.DLOAD;
			case Type.ARRAY, Type.OBJECT -> Opcodes.ALOAD;
			default -> Opcodes.ILOAD;
		};
	}

	private static String prependArgument(Type argument, String methodDesc) {
		Type[] oldArgs = Type.getArgumentTypes(methodDesc);
		Type[] newArgs = new Type[oldArgs.length + 1];
		newArgs[0] = argument;
		System.arraycopy(oldArgs, 0, newArgs, 1, oldArgs.length);
		return Type.getMethodDescriptor(Type.getReturnType(methodDesc), newArgs);
	}

	private static final String GUI_RENDERER = "net/minecraft/client/gui/render/GuiRenderer";
	private static final String PIP_RENDERERS = "pictureInPictureRenderers";
	private static final String PIP_POOLS = "pictureInPictureRendererPools";
	private static final String PIP_PREPARE = "preparePictureInPictureState";
	private static final String PIP_BUILDER_OWNER = "net/forbric/kernel/runtime/KernelForgePipRenderers";
	private static final String PIP_BRIDGE = "forbric$prepareOrphanedPip";

	/**
	 * Makes a picture-in-picture renderer registered the VANILLA way draw again, by giving NeoForge's pooled lookup
	 * a fallback to the map the merge orphaned.
	 *
	 * <p>{@code GuiRenderer} ends up with BOTH ecosystems' versions of the same job:
	 *
	 * <ul>
	 *   <li>{@code preparePictureInPictureState(T, int)} — vanilla's. Reads {@code pictureInPictureRenderers}, a
	 *       {@code Class -> PictureInPictureRenderer} map, and calls {@code prepare} on the one it finds. <b>Nothing
	 *       calls it.</b></li>
	 *   <li>{@code preparePictureInPictureState(T, int, boolean)} — NeoForge's, and the one {@code render()} calls.
	 *       Reads {@code pictureInPictureRendererPools} instead, and returns false for a state class with no pool.</li>
	 * </ul>
	 *
	 * <p>Every guest mod registers into the first map, because that is the only one vanilla has: Xaero's Minimap puts
	 * its {@code MinimapPipRenderer} there, malilib its block-state element renderer. Both then draw nothing at all —
	 * no exception, no log, the element is simply absent. Chasing it from the symptom is brutal, because every link
	 * before this one is intact: the mixins apply, the hooks are called every frame, the mod's own state is live. The
	 * lookup misses one map over.
	 *
	 * <p>So the null-pool branch now falls through to the orphaned map instead of returning false. Guest renderers get
	 * exactly vanilla's contract — one instance per state class, {@code prepare} called directly — and NeoForge's
	 * pooled renderers are untouched, which matters: a pool CLOSES the renderers a frame did not use, so handing a
	 * guest's single long-lived instance to one would free its GL target out from under it.
	 *
	 * <p>The bridge method is synthesized from the descriptors of the orphaned overload itself rather than from
	 * hard-coded names, so it stays correct if the merge shifts.
	 */
	private static boolean bridgeOrphanedPipRenderers(ClassNode node) {
		if (!GUI_RENDERER.equals(node.name)) return false;
		if (findField(node, PIP_RENDERERS) == null || findField(node, PIP_POOLS) == null) return false;
		if (findMethodByName(node, PIP_BRIDGE) != null) return false;

		MethodNode orphaned = null;
		MethodNode live = null;
		for (MethodNode method : node.methods) {
			if (!PIP_PREPARE.equals(method.name)) continue;
			if (Type.getReturnType(method.desc).getSort() == Type.BOOLEAN) live = method; else orphaned = method;
		}
		if (orphaned == null || live == null) return false;

		// One source for the state type: the CALL SITE's. Deriving the bridge's descriptor from the orphaned overload
		// instead would let the two drift apart if a future merge narrows one of them, and the only symptom would be a
		// NoSuchMethodError on the first frame that actually reaches an orphaned renderer.
		String stateDesc = Type.getArgumentTypes(live.desc)[0].getDescriptor();
		MethodNode bridge = buildPipBridge(node, orphaned, stateDesc);
		if (bridge == null || !redirectMissingPoolToBridge(node, live, stateDesc)) return false;

		node.methods.add(bridge);
		boolean filled = fillOrphanedPipMap(node, findField(node, PIP_RENDERERS));
		ForbricLog.warn("[Forbric/MergedBaseCompat] gave GuiRenderer's pooled picture-in-picture lookup a fallback to "
				+ "the orphaned vanilla map — NeoForge won preparePictureInPictureState, so every guest-registered "
				+ "GUI element (Xaero's minimap, malilib's overlays) was registered where nothing reads%s",
				filled ? ", and gave that map its only writer" : "");
		return true;
	}

	/**
	 * Assigns the orphaned map in {@code GuiRenderer.<init>}, from MinecraftForge's registration event.
	 *
	 * <p>The field is declared, read in one place, and <b>written nowhere</b>: NeoForge's constructor won the byte
	 * merge and fills its pooled map instead, so vanilla's plain one stays null. That is two failures in one. A
	 * MinecraftForge mod's picture-in-picture renderer has nothing to register into, because the event that would
	 * have filled this map is posted by nobody; and the fallback above reads the map WITHOUT a null check, so the
	 * first frame reaching a state class with no pool would throw inside the game's own render loop.
	 *
	 * <p>Appended before each RETURN of the constructor, which is where a final field may still be assigned.
	 */
	private static boolean fillOrphanedPipMap(ClassNode node, FieldNode renderers) {
		if (renderers == null) return false;
		MethodNode init = null;
		for (MethodNode method : node.methods) {
			if ("<init>".equals(method.name)) init = method;
		}
		if (init == null) return false;
		for (AbstractInsnNode insn : init.instructions.toArray()) {
			if (insn instanceof MethodInsnNode call && PIP_BUILDER_OWNER.equals(call.owner)) return false;
		}
		int listSlot = -1;
		int slot = 1;
		for (Type argument : Type.getArgumentTypes(init.desc)) {
			if ("Ljava/util/List;".equals(argument.getDescriptor())) listSlot = slot;
			slot += argument.getSize();
		}
		if (listSlot < 0) return false;
		// Both constructors erase to the same descriptor. Guest mixins can append ordinary renderers
		// to NeoForge's registration list, so filter only the pool's input and retain the original list.
		for (AbstractInsnNode insn : init.instructions.toArray()) {
			if (insn instanceof MethodInsnNode call && "createPools".equals(call.name)
					&& "net/neoforged/neoforge/client/gui/PictureInPictureRendererPool".equals(call.owner)
					&& "(Ljava/util/List;)Ljava/util/Map;".equals(call.desc)) {
				init.instructions.insertBefore(call, new MethodInsnNode(Opcodes.INVOKESTATIC, PIP_BUILDER_OWNER,
						"poolRegistrations", "(Ljava/util/List;)Ljava/util/List;", false));
			}
		}

		// Vanilla fills the map from ImmutableMap.builder(), the one point in this constructor a guest can hook: Create
		// wraps it to add its renderers, SuperMartijn642's core lib injects before it to swap the renderer list, others
		// modify or redirect it. NeoForge's constructor has no builder at all, so every such injector bound nothing. The
		// map is built in vanilla's shape: the builder is made where a guest expects it, after the list is final, and the
		// list's renderers and MinecraftForge's registrations are added to whatever builder the guests' injectors hand
		// back. One injection point serves every injector form, and the kernel writes no adapter for any of them.
		int builder = init.maxLocals;
		int appended = 0;
		for (AbstractInsnNode insn : init.instructions.toArray()) {
			if (insn.getOpcode() != Opcodes.RETURN) continue;
			InsnList assign = new InsnList();
			LabelNode live = new LabelNode(), end = new LabelNode();
			assign.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/google/common/collect/ImmutableMap", "builder",
					"()Lcom/google/common/collect/ImmutableMap$Builder;", false));
			assign.add(new VarInsnNode(Opcodes.ASTORE, builder));
			assign.add(live);
			assign.add(new VarInsnNode(Opcodes.ALOAD, 0));
			assign.add(new VarInsnNode(Opcodes.ALOAD, builder));
			assign.add(new VarInsnNode(Opcodes.ALOAD, listSlot));
			assign.add(new MethodInsnNode(Opcodes.INVOKESTATIC, PIP_BUILDER_OWNER, "build", "(Ljava/util/List;)Ljava/util/Map;",
					false));
			assign.add(new MethodInsnNode(Opcodes.INVOKESTATIC, PIP_BUILDER_OWNER, "complete",
					"(Lcom/google/common/collect/ImmutableMap$Builder;Ljava/util/Map;)Ljava/util/Map;", false));
			assign.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, renderers.name, renderers.desc));
			assign.add(end);
			init.instructions.insertBefore(insn, assign);
			if (init.localVariables != null) init.localVariables.add(new LocalVariableNode("builder",
					"Lcom/google/common/collect/ImmutableMap$Builder;", null, live, end, builder));
			appended++;
		}
		if (appended == 0) return false;
		init.maxLocals = builder + 1;
		init.maxStack = Math.max(init.maxStack, 3);
		for (MethodNode method : node.methods) {
			if (!"close".equals(method.name) || !"()V".equals(method.desc)) continue;
			for (AbstractInsnNode insn : method.instructions.toArray()) {
				if (insn.getOpcode() != Opcodes.RETURN) continue;
				InsnList close = new InsnList();
				close.add(new VarInsnNode(Opcodes.ALOAD, 0));
				close.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, renderers.name, renderers.desc));
				close.add(new MethodInsnNode(Opcodes.INVOKESTATIC, PIP_BUILDER_OWNER, "close", "(Ljava/util/Map;)V", false));
				method.instructions.insertBefore(insn, close);
			}
			method.maxStack = Math.max(method.maxStack, 1);
		}
		return true;
	}

	/**
	 * Builds {@code boolean forbric$prepareOrphanedPip(state, i)} — vanilla's lookup, with a boolean saying whether
	 * it found anything. Every field and call is cloned out of the orphaned overload, so nothing here is spelled twice;
	 * {@code stateDesc} comes from the CALL SITE so the two cannot disagree.
	 */
	private static MethodNode buildPipBridge(ClassNode node, MethodNode orphaned, String stateDesc) {
		FieldInsnNode renderers = null;
		FieldInsnNode renderState = null;
		FieldInsnNode dispatcher = null;
		TypeInsnNode rendererCast = null;
		MethodInsnNode mapGet = null;
		MethodInsnNode prepare = null;

		for (AbstractInsnNode insn = orphaned.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD) {
				if (PIP_RENDERERS.equals(field.name)) renderers = field;
				else if (renderState == null) renderState = field;
				else if (dispatcher == null) dispatcher = field;
			} else if (insn instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST) {
				rendererCast = cast;
			} else if (insn instanceof MethodInsnNode call) {
				if ("get".equals(call.name)) mapGet = call;
				else if ("prepare".equals(call.name)) prepare = call;
			}
		}
		if (renderers == null || renderState == null || dispatcher == null
				|| rendererCast == null || mapGet == null || prepare == null) {
			ForbricLog.debug("[Forbric/MergedBaseCompat] GuiRenderer's orphaned pip overload has an unexpected shape "
					+ "— leaving the pooled lookup alone");
			return null;
		}

		MethodNode bridge = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
				PIP_BRIDGE, "(" + stateDesc + "I)Z", null, null);
		LabelNode miss = new LabelNode();
		InsnList code = bridge.instructions;

		code.add(new VarInsnNode(Opcodes.ALOAD, 0));
		code.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, renderers.name, renderers.desc));
		code.add(new VarInsnNode(Opcodes.ALOAD, 1));
		// Object.getClass rather than the interface's, so this holds however the state type is declared.
		code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;",
				false));
		code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, mapGet.owner, mapGet.name, mapGet.desc, true));
		code.add(new TypeInsnNode(Opcodes.CHECKCAST, rendererCast.desc));
		code.add(new VarInsnNode(Opcodes.ASTORE, 3));
		code.add(new VarInsnNode(Opcodes.ALOAD, 3));
		code.add(new JumpInsnNode(Opcodes.IFNULL, miss));

		code.add(new VarInsnNode(Opcodes.ALOAD, 3));
		code.add(new VarInsnNode(Opcodes.ALOAD, 1));
		code.add(new VarInsnNode(Opcodes.ALOAD, 0));
		code.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, renderState.name, renderState.desc));
		code.add(new VarInsnNode(Opcodes.ALOAD, 0));
		code.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, dispatcher.name, dispatcher.desc));
		code.add(new VarInsnNode(Opcodes.ILOAD, 2));
		code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, prepare.owner, prepare.name, prepare.desc, false));
		code.add(new InsnNode(Opcodes.ICONST_1));
		code.add(new InsnNode(Opcodes.IRETURN));

		code.add(miss);
		// Both paths reach here with slot 3 holding the (null) renderer, so the frame simply appends it.
		code.add(new FrameNode(Opcodes.F_APPEND, 1, new Object[] {rendererCast.desc}, 0, null));
		code.add(new InsnNode(Opcodes.ICONST_0));
		code.add(new InsnNode(Opcodes.IRETURN));

		bridge.maxStack = 5;
		bridge.maxLocals = 4;
		return bridge;
	}

	/** Rewrites the live overload's "no pool for this state class" early return into a call to the bridge. */
	private static boolean redirectMissingPoolToBridge(ClassNode node, MethodNode live, String stateDesc) {
		for (AbstractInsnNode insn = live.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (!(insn instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETFIELD
					|| !PIP_POOLS.equals(field.name)) {
				continue;
			}

			AbstractInsnNode jump = insn;
			while (jump != null && !(jump instanceof JumpInsnNode)) jump = jump.getNext();
			if (jump == null || jump.getOpcode() != Opcodes.IFNONNULL) break;

			AbstractInsnNode falsy = jump.getNext();
			while (falsy != null && falsy.getOpcode() == -1) falsy = falsy.getNext();   // labels / line numbers
			if (falsy == null || falsy.getOpcode() != Opcodes.ICONST_0) break;

			AbstractInsnNode ret = falsy.getNext();
			while (ret != null && ret.getOpcode() == -1) ret = ret.getNext();
			if (ret == null || ret.getOpcode() != Opcodes.IRETURN) break;

			InsnList call = new InsnList();
			call.add(new VarInsnNode(Opcodes.ALOAD, 0));
			call.add(new VarInsnNode(Opcodes.ALOAD, 1));
			call.add(new VarInsnNode(Opcodes.ILOAD, 2));
			call.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.name, PIP_BRIDGE, "(" + stateDesc + "I)Z", false));
			live.instructions.insertBefore(falsy, call);
			live.instructions.remove(falsy);
			live.maxStack = Math.max(live.maxStack, 3);
			return true;
		}

		ForbricLog.debug("[Forbric/MergedBaseCompat] GuiRenderer's pooled pip lookup has an unexpected shape "
				+ "— leaving it alone");
		return false;
	}

	private static FieldNode findField(ClassNode node, String name) {
		if (node.fields == null) return null;
		for (FieldNode field : node.fields) {
			if (field.name.equals(name)) return field;
		}
		return null;
	}

	/** First method with this name, whatever its descriptor — distinct from {@link #findMethod(ClassNode,String,String)}. */
	private static MethodNode findMethodByName(ClassNode node, String name) {
		for (MethodNode method : node.methods) {
			if (method.name.equals(name)) return method;
		}
		return null;
	}

}
