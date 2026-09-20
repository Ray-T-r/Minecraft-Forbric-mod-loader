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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.api.Ecosystem;
import net.forbric.api.EventBridges;
import net.forbric.api.ForeignType;
import net.forbric.api.GameEventBridge;
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
		// Forty independent repairs behind one `changed` flag -- dungeon generation, key mappings, the
		// particle map, default attributes, the save on teardown. Each one can stop applying on its own, and a
		// single class-level answer cannot see that. This is the largest reservoir of the failure this mechanism
		// exists for, and it needs one claim per repair rather than one anchor per class.
		return AnchorSet.scanned("40 independent repairs across the whole base, each needing its own claim");
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0) return classBytes;
		try {
			boolean namedOldLoader = stillNamesTheOldLoader(classBytes);
			ClassNode node = new ClassNode();
			new ClassReader(classBytes).accept(node, 0);
			boolean changed = repairLambdaBootstrapHandles(node);
			changed |= addBlockStateModelConflictResolvers(node);
			changed |= addMissingForgeFluidTypeBridge(node);
			changed |= addMissingForgeKeyMappingLookupInitializer(node);
			changed |= routeKeyMappingClickToPopulatedLookup(node);
			changed |= giveKeyMappingItsMinecraftForgeFace(node);
			changed |= giveTheVanillaParticleMapAViewOfTheLiveOne(node);
			changed |= giveFeaturesPerStepItsVanillaDescriptorBack(node);
			changed |= letDungeonsGenerateWithoutTheDataMap(node);
			changed |= guardNeoForgesWorldModifierPass(node);
			changed |= letForeignResourceConditionsThrough(node);
			changed |= letForeignResourceConditionsThroughMinecraftForge(node);
			changed |= letFabricResourceConditionsDecide(node);
			changed |= translateAGuestsPrivateSkipMarker(node);
			changed |= serveDefaultAttributesBothEcosystems(node);
			changed |= restoreForgeClientInit(node);
			changed |= restoreForgeGeometryReload(node);
			changed |= nameTheReloadListenersNeoForgeRefusesToName(node);
			changed |= dropInterfaceDefaultShadowingOverrides(node);
			changed |= tolerateEmptyCreativeTabStacks(node);
			changed |= routePlaceItemHookToNeoForge(node);
			changed |= bridgeOrphanedPipRenderers(node);
			changed |= keepForgeOutboundProtocolCurrent(node);
			changed |= surviveTheMissingForgeModelDataManager(node);
			changed |= dropTheWindowTitlesLoaderBrand(node);
			changed |= keepTheSaveOffTheTeardownsFailurePath(node);
			changed |= askNeoForgeWhatAnItemsAttributesAre(node);
			changed |= readTheSpawnReasonThatIsActuallyWritten(node);
			changed |= giveTheUnwrittenLoggerAValue(node);
			changed |= addTheMissingCapabilityLifecycleStubs(node);
			changed |= addTheMissingNbtBuilderFactory(node);
			changed |= postMinecraftForgesReloadListenerEvent(node);
			changed |= giveMinecraftForgesReloadEventItsConditionContext(node);
			changed |= letMinecraftForgeIngredientTypesDecode(node);
			changed |= letMinecraftForgeFluidsChooseTheirModel(node);
			changed |= giveMinecraftForgesParticleLookupItsFirstVariant(node);
			changed |= dropStubsThatBypassARealSuperclassMethod(node);
			changed |= inlineTheSwitchMapTheMergeLost(node);
			changed |= vetoUnjudgeableOverlayConditions(node);
			changed |= hideTheLegacyLootModifierIndexFromTheDirectoryScan(node);
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
			ForbricLog.warn("[Forbric/MergedBaseCompat] could not inspect " + className, e);
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
	private static final String MF_CONTEXT = "Lnet/minecraftforge/client/settings/IKeyConflictContext;";
	private static final String NEO_CONTEXT = "Lnet/neoforged/neoforge/client/settings/IKeyConflictContext;";
	private static final String MF_MODIFIER = "Lnet/minecraftforge/client/settings/KeyModifier;";
	private static final String NEO_MODIFIER = "Lnet/neoforged/neoforge/client/settings/KeyModifier;";
	private static final String INPUT_KEY = "Lcom/mojang/blaze3d/platform/InputConstants$Key;";
	private static final String KERNEL_KEYS = "net/forbric/kernel/runtime/KernelForgeKeyBindings";

	private static final String PARTICLE_RESOURCES = "net/minecraft/client/particle/ParticleResources";

	private static final String CHUNK_GENERATOR = "net/minecraft/world/level/chunk/ChunkGenerator";
	private static final String FEATURES_PER_STEP = "featuresPerStep";
	private static final String CLEARABLE_LAZY = "net/minecraftforge/common/util/ClearableLazy";
	private static final String CLEARABLE_LAZY_DESC = "L" + CLEARABLE_LAZY + ";";
	private static final String SUPPLIER = "java/util/function/Supplier";
	private static final String SUPPLIER_DESC = "L" + SUPPLIER + ";";
	private static final String KERNEL_CHUNK_GENERATOR = "net/forbric/kernel/runtime/KernelChunkGenerator";

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
	private static final String FORGE_ICONDITION = ForeignType.ICONDITION.internal(Ecosystem.FORGE);
	private static final String KERNEL_FORGE_CONDITIONS = "net/forbric/kernel/runtime/KernelForgeConditions";
	private static final String KERNEL_FORGE_RELOAD = "net/forbric/kernel/runtime/KernelForgeReload";
	private static final String KERNEL_FORGE_INGREDIENTS = "net/forbric/kernel/runtime/KernelForgeIngredients";
	private static final String KERNEL_FORGE_FLUIDS = "net/forbric/kernel/runtime/KernelForgeFluids";
	private static final String FLUID_RENDERER = "net/minecraft/client/renderer/block/FluidRenderer";
	private static final String FLUID_MODEL = "Lnet/minecraft/client/renderer/block/FluidModel;";
	private static final String FLUID_STATE = "Lnet/minecraft/world/level/material/FluidState;";
	private static final String TESSELATE_DESC = "(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;"
			+ "Lnet/minecraft/client/renderer/block/FluidRenderer$Output;Lnet/minecraft/world/level/block/state/BlockState;"
			+ FLUID_STATE + ")V";
	private static final String FLUID_MODEL_FUNNEL_DESC = "(" + FLUID_MODEL + FLUID_STATE
			+ "Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;)" + FLUID_MODEL;
	private static final String WEIGHTED_VARIANTS = "net/minecraft/client/renderer/block/dispatch/WeightedVariants";
	private static final String BLOCK_STATE_MODEL = "net/minecraft/client/renderer/block/dispatch/BlockStateModel";
	/** NeoForge-only: MinecraftForge composes its ingredient codec in ForgeHooks, so ForeignType has no pair. */
	private static final String NEO_INGREDIENT_CODECS = "net/neoforged/neoforge/common/crafting/IngredientCodecs";
	static final String CODEC_TO_CODEC = "(Lcom/mojang/serialization/Codec;)Lcom/mojang/serialization/Codec;";
	private static final String RELOADABLE_SERVER_RESOURCES = "net/minecraft/server/ReloadableServerResources";
	private static final String RELOAD_HOOK_DESC = "(L" + RELOADABLE_SERVER_RESOURCES
			+ ";Lnet/minecraft/core/RegistryAccess;Ljava/util/Map;)Ljava/util/List;";
	/** The carrier's own reload event; NeoForge's twin has a different name, so ForeignType has no pair. */
	private static final String FORGE_RELOAD_EVENT = "net/minecraftforge/event/AddReloadListenerEvent";
	private static final String FORGE_CONDITION_CONTEXT_DESC = "()L" + FORGE_ICONDITION + "$IContext;";
	private static final String JSON_RELOAD_LISTENER = "net/minecraft/server/packs/resources/SimpleJsonResourceReloadListener";
	private static final String DATA_RESULT = "Lcom/mojang/serialization/DataResult;";

	private static final String CONDITIONAL_OPS = "net/neoforged/neoforge/common/conditions/ConditionalOps";
	private static final String CONDITIONAL_FACTORY =
			"(Lcom/mojang/serialization/Codec;Ljava/lang/String;)Lcom/mojang/serialization/Codec;";
	private static final String KERNEL_FABRIC_CONDITIONS = "net/forbric/kernel/runtime/KernelFabricConditions";

	private static final String DEFAULT_ATTRIBUTES = "net/minecraft/world/entity/ai/attributes/DefaultAttributes";
	private static final String NEO_COMMON_HOOKS = "net/neoforged/neoforge/common/CommonHooks";
	private static final String ATTRIBUTES_VIEW = "()Ljava/util/Map;";
	private static final String KERNEL_FORGE_ATTRIBUTES = "net/forbric/kernel/runtime/KernelForgeAttributes";

	private static final String ADD_CLIENT_RELOAD_LISTENERS =
			"net/neoforged/neoforge/client/event/AddClientReloadListenersEvent";
	private static final String VANILLA_CLIENT_LISTENERS =
			"net/neoforged/neoforge/client/resources/VanillaClientListeners";
	private static final String NAME_FOR_CLASS =
			"(Ljava/lang/Class;)Lnet/minecraft/resources/Identifier;";
	private static final String KERNEL_RELOAD_NAMES = "net/forbric/kernel/runtime/KernelClientReloadNames";
	/** NeoForge's retyping of vanilla's {@code providers}: the one the merged {@code <init>} actually writes. */
	/**
	 * The methods measured to be merge-injected in this shape, and worth removing.
	 *
	 * <p>Derived, not guessed: every pure interface-default delegate in the merged base that shadows a real
	 * superclass method was differenced against both unmerged bases. 253 of 256 exist only after the merge, but
	 * three do not — vanilla writes the same shape on purpose — so the shape alone cannot decide. This entry is
	 * the one whose occurrences are all merge-introduced and whose bypassed method does something visible: it is
	 * what applies a team's colour and prefix to a name.
	 */
	private static final java.util.Set<String> MEASURED_MERGE_STUBS =
			java.util.Set.of("getDisplayName()Lnet/minecraft/network/chat/Component;");

	/**
	 * The classes MinecraftForge rooted its capability system at, and the merge rooted at NeoForge's attachment
	 * holder instead. {@code LevelChunk} is absent on purpose: it kept both methods through the merge.
	 */
	private static final java.util.Set<String> CAPABILITY_ROOTS = java.util.Set.of(
			"net/minecraft/world/entity/Entity",
			"net/minecraft/world/level/block/entity/BlockEntity",
			"net/minecraft/world/level/Level");
	/** {@code org.slf4j.Logger}, the one unwritten static the merge leaves that has an obvious correct value. */
	private static final String LOGGER_DESC = "Lorg/slf4j/Logger;";
	/** {@code EntitySpawnReason}, the type of both of the merged {@code Mob}'s spawn fields. */
	private static final String SPAWN_REASON = "Lnet/minecraft/world/entity/EntitySpawnReason;";
	private static final String NAME_KEYED = "Ljava/util/Map;";
	/** Vanilla's own descriptor for it, and the one fabric-api reads. */
	private static final String ID_KEYED = "Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;";
	private static final String KERNEL_PARTICLES = "net/forbric/kernel/runtime/KernelParticleProviders";
	/** Old owner → the kernel class that now carries the method, for hooks the merged base still names. */
	private static final Map<String, String> LEGACY_INTEROP_OWNERS = Map.of(
			"net/forbric/loader/impl/compat/ForbricCustomPayloadInterop", "net/forbric/kernel/interop/PayloadInterop",
			"net/forbric/loader/impl/forge/runtime/ForbricClientShutdown", "net/forbric/kernel/interop/ClientShutdown",
			"net/forbric/loader/impl/forge/runtime/ForbricForgeRuntimeInterop",
			"net/forbric/kernel/interop/ForgeRuntimeInterop");

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
	 * Gives {@code CompoundTag} back the {@code builder()} static every {@code IForgeBlockPos.toCompoundTag()} and
	 * {@code ForgeHooks.createEmptyStructure} links against.
	 *
	 * <p>Genuine Forge patches {@code public static INBTBuilder$Builder builder()} into {@code CompoundTag} with a
	 * body that {@code new}s {@code CompoundTag$1} — an anonymous class the byte merge could not carry, because the
	 * merged {@code CompoundTag$1} is a DIFFERENT anonymous class (the "pipeline-divergent anonymous sibling" in
	 * merge-conflicts.txt). So the method was dropped whole, and a Forge mod is one ordinary call away from
	 * {@code NoSuchMethodError} with a stack that names the mod, not the merge.
	 *
	 * <p>The body emitted here is not Forge's: it is {@code INBTBuilder.nbt()}'s own four instructions
	 * ({@code NEW INBTBuilder$Builder; DUP; INVOKESPECIAL <init>; ARETURN}), which is what Forge's
	 * {@code CompoundTag$1.nbt()} reduces to — the anonymous class only existed to implement the interface. Nothing
	 * is invented: the carrier type is real, its no-arg constructor is public, and the descriptor is the one the
	 * carrier's call sites carry. {@link ForeignType} does not apply: NeoForge has no {@code CompoundTag.builder}.
	 * A rebuilt base that carries the method makes this stand down.
	 */
	private static boolean addTheMissingNbtBuilderFactory(ClassNode node) {
		if (!"net/minecraft/nbt/CompoundTag".equals(node.name)) return false;
		if (hasMethod(node, "builder", NBT_BUILDER_FACTORY_DESC)) return false;

		MethodNode factory = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "builder",
				NBT_BUILDER_FACTORY_DESC, null, null);
		factory.instructions.add(new TypeInsnNode(Opcodes.NEW, FORGE_NBT_BUILDER));
		factory.instructions.add(new InsnNode(Opcodes.DUP));
		factory.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, FORGE_NBT_BUILDER, "<init>", "()V", false));
		factory.instructions.add(new InsnNode(Opcodes.ARETURN));
		factory.maxStack = 2;
		factory.maxLocals = 0;
		node.methods.add(factory);
		ForbricLog.warn("[Forbric/MergedBaseCompat] CompoundTag.builder() — 1 method added: genuine Forge's body news "
				+ "CompoundTag$1, an anonymous class the merge could not carry (the merged CompoundTag$1 is a different "
				+ "class), so the body emitted is INBTBuilder.nbt()'s own; IForgeBlockPos.toCompoundTag() and "
				+ "ForgeHooks.createEmptyStructure link again");
		return true;
	}

	/**
	 * Posts MinecraftForge's {@code AddReloadListenerEvent} from the merged server reload.
	 *
	 * <p>Merged {@code ReloadableServerResources.lambda$loadResources$2} calls only NeoForge's
	 * {@code EventHooks.onResourceReload}; the merged base names Forge's event nowhere. One owner redirect, same
	 * name and descriptor, to {@code KernelForgeReload.onResourceReload}, whose body calls NeoForge's hook and then
	 * the carrier's own {@code ForgeEventFactory.onResourceReload}. Exactly one call site is expected; more means
	 * an unrecognised base and the repair stands down whole. Idempotent: a second pass finds no NeoForge-owned call.
	 * The kill switch lives in the helper ({@code -Dforbric.forgeReloadListeners=off}), so the redirect is inert
	 * rather than absent when it is off.
	 */
	private static boolean postMinecraftForgesReloadListenerEvent(ClassNode node) {
		if (!RELOADABLE_SERVER_RESOURCES.equals(node.name)) return false;
		String neo = ForeignType.EVENT_HOOKS.internal(Ecosystem.NEOFORGE);
		List<MethodInsnNode> calls = new java.util.ArrayList<>();
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
						&& neo.equals(call.owner) && "onResourceReload".equals(call.name)
						&& RELOAD_HOOK_DESC.equals(call.desc)) {
					calls.add(call);
				}
			}
		}
		if (calls.isEmpty()) return false;
		if (calls.size() != 1) {
			ForbricLog.warn("[Forbric/MergedBaseCompat] ReloadableServerResources calls EventHooks.onResourceReload "
					+ "%d times, not once — not redirecting any of them, because MinecraftForge's reload event would "
					+ "then be posted for some reloads and not others", calls.size());
			return false;
		}
		calls.getFirst().owner = KERNEL_FORGE_RELOAD;
		ForbricLog.info("[Forbric/MergedBaseCompat] ReloadableServerResources now posts both families' reload-listener "
				+ "events (1 call site) — the merged base posted only NeoForge's, so a traditional-Forge mod's "
				+ "AddReloadListenerEvent listeners never ran and its JSON data loaders were never registered");
		return true;
	}

	/**
	 * Gives MinecraftForge's {@code AddReloadListenerEvent.getConditionContext()} an answer instead of a
	 * {@code NoSuchMethodError}.
	 *
	 * <p>The carrier compiles it as {@code invokevirtual ReloadableServerResources.getConditionContext()} returning
	 * Forge's {@code ICondition$IContext}; the merged class declares only the NeoForge-typed overload. The one
	 * invocation is rewritten to {@code invokestatic KernelForgeConditions.contextOf(ReloadableServerResources)} —
	 * the receiver already on the stack becomes the argument, the Forge-typed context comes back, nothing else
	 * moves. This edits a CARRIER class, as {@link #nameTheReloadListenersNeoForgeRefusesToName} does. Exactly one
	 * site expected; idempotent once the kernel owner is present.
	 */
	private static boolean giveMinecraftForgesReloadEventItsConditionContext(ClassNode node) {
		if (!FORGE_RELOAD_EVENT.equals(node.name)) return false;
		List<MethodInsnNode> calls = new java.util.ArrayList<>();
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof MethodInsnNode call)) continue;
				if (KERNEL_FORGE_CONDITIONS.equals(call.owner) && "contextOf".equals(call.name)) return false;
				if (call.getOpcode() == Opcodes.INVOKEVIRTUAL && RELOADABLE_SERVER_RESOURCES.equals(call.owner)
						&& "getConditionContext".equals(call.name) && FORGE_CONDITION_CONTEXT_DESC.equals(call.desc)) {
					calls.add(call);
				}
			}
		}
		if (calls.size() != 1) {
			if (!calls.isEmpty()) {
				ForbricLog.warn("[Forbric/MergedBaseCompat] AddReloadListenerEvent asks for its condition context at "
						+ "%d sites, not one — leaving it alone", calls.size());
			}
			return false;
		}
		MethodInsnNode call = calls.getFirst();
		call.setOpcode(Opcodes.INVOKESTATIC);
		call.owner = KERNEL_FORGE_CONDITIONS;
		call.name = "contextOf";
		call.desc = "(L" + RELOADABLE_SERVER_RESOURCES + ";)L" + FORGE_ICONDITION + "$IContext;";
		call.itf = false;
		ForbricLog.info("[Forbric/MergedBaseCompat] MinecraftForge's AddReloadListenerEvent now gets a condition context "
				+ "adapted from NeoForge's (1 call site) — the Forge-typed accessor it compiled against does not "
				+ "exist on the merged ReloadableServerResources, so asking for it was a NoSuchMethodError");
		return true;
	}

	/**
	 * Lets MinecraftForge ingredient types decode through the carrier's own dispatch.
	 *
	 * <p>Merged {@code Ingredient.<clinit>} stores {@code IngredientCodecs.codec(base)} into the single
	 * {@code CODEC} with no Forge dispatch in front of it, so {@code forge:intersection} & co. were a recipe
	 * parsing error. One instruction inserted immediately before that {@code PUTSTATIC}:
	 * {@code KernelForgeIngredients.alsoAskMinecraftForge(Codec)Codec}, which returns
	 * {@code ForgeHooks.ingredientBaseCodec(neo)} — Forge's real {@code either(registry dispatch, base)} with the
	 * NeoForge codec as its base. Raw {@code Codec} in and out, stack unchanged; the shape of
	 * {@link #letFabricResourceConditionsDecide}. Recognised only when the previous real instruction is NeoForge's
	 * factory; already-wrapped stands down (idempotent), anything else stands down and says so. The kill switch
	 * lives in the helper ({@code -Dforbric.forgeIngredients=off}).
	 */
	private static boolean letMinecraftForgeIngredientTypesDecode(ClassNode node) {
		if (!"net/minecraft/world/item/crafting/Ingredient".equals(node.name)) return false;
		MethodNode clinit = findMethod(node, "<clinit>", "()V");
		if (clinit == null) return false;
		FieldInsnNode store = null;
		for (AbstractInsnNode insn = clinit.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC
					&& node.name.equals(field.owner) && "CODEC".equals(field.name)
					&& "Lcom/mojang/serialization/Codec;".equals(field.desc)) {
				if (store != null) {
					ForbricLog.warn("[Forbric/MergedBaseCompat] Ingredient.<clinit> stores CODEC more than once — not "
							+ "wrapping it, because the Forge dispatch would then cover one store and not the other");
					return false;
				}
				store = field;
			}
		}
		if (store == null) return false;
		AbstractInsnNode previous = store.getPrevious();
		while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
		if (previous instanceof MethodInsnNode already && KERNEL_FORGE_INGREDIENTS.equals(already.owner)) {
			return false;                       // already wrapped: idempotent
		}
		if (!(previous instanceof MethodInsnNode factory) || factory.getOpcode() != Opcodes.INVOKESTATIC
				|| !NEO_INGREDIENT_CODECS.equals(factory.owner) || !"codec".equals(factory.name)
				|| !CODEC_TO_CODEC.equals(factory.desc)) {
			ForbricLog.warn("[Forbric/MergedBaseCompat] Ingredient.CODEC is not stored straight from NeoForge's "
					+ "IngredientCodecs.codec — leaving it alone rather than wrapping an unrecognised shape");
			return false;
		}
		clinit.instructions.insertBefore(store, new MethodInsnNode(Opcodes.INVOKESTATIC, KERNEL_FORGE_INGREDIENTS,
				"alsoAskMinecraftForge", CODEC_TO_CODEC, false));
		ForbricLog.info("[Forbric/MergedBaseCompat] Ingredient.CODEC now asks MinecraftForge's ingredient serializers "
				+ "before NeoForge's — forge:intersection/difference/compound/nbt and mod-registered Forge ingredient "
				+ "types were a recipe parsing error on the merged base");
		return true;
	}

	/**
	 * Lets a MinecraftForge fluid supply its own render model and tint from {@code FluidRenderer.tesselate}.
	 *
	 * <p>Vanilla 26.2's {@code FluidStateModelSet} knows water and lava and answers the missing model for anything
	 * else; genuine Forge's only seam is inside {@code tesselate} — after the model lookup it asks
	 * {@code IClientFluidTypeExtensions.of(fluidState).getModel(...)}, and where the model carries no tint source it
	 * asks {@code getTintColor()} instead of {@code -1}. The merge kept NeoForge's tesselate, with neither ask, so
	 * every Forge modded fluid drew as the missing texture. Two sites, one repair, one flag:
	 * <ul>
	 * <li>A: after the single {@code FluidStateModelSet.get(FluidState)} and its {@code ASTORE n}, insert
	 * {@code ALOAD n; ALOAD 5; ALOAD 1; ALOAD 2; INVOKESTATIC KernelForgeFluids.model; ASTORE n} — stack empty in,
	 * empty out, no label crossed (locals: this=0, level=1, pos=2, output=3, blockState=4, fluidState=5).</li>
	 * <li>B: the {@code IFNULL} after {@code FluidModel.fluidTintSource()} targets {@code ICONST_M1; ISTORE k}; the
	 * constant becomes {@code ALOAD 5; INVOKESTATIC KernelForgeFluids.tintColor} — an int is pushed on both arms,
	 * the label keeps its empty-stack frame.</li>
	 * </ul>
	 * Whole-or-nothing: unless both shapes match exactly once, nothing is edited and the reason is logged.
	 * Idempotent once the kernel owner is named. The flag ({@code -Dforbric.forgeFluidModels=off}) lives in the
	 * helper, which then returns the model by identity and {@code -1}.
	 */
	private static boolean letMinecraftForgeFluidsChooseTheirModel(ClassNode node) {
		if (!FLUID_RENDERER.equals(node.name)) return false;
		MethodNode tesselate = findMethod(node, "tesselate", TESSELATE_DESC);
		if (tesselate == null) return false;

		VarInsnNode modelStore = null;
		InsnNode minusOne = null;
		int lookups = 0, tintArms = 0;
		for (AbstractInsnNode insn = tesselate.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof MethodInsnNode call && KERNEL_FORGE_FLUIDS.equals(call.owner)) return false;
			if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEVIRTUAL
					&& "net/minecraft/client/renderer/block/FluidStateModelSet".equals(call.owner)
					&& "get".equals(call.name) && ("(" + FLUID_STATE + ")" + FLUID_MODEL).equals(call.desc)) {
				lookups++;
				if (nextReal(call) instanceof VarInsnNode store && store.getOpcode() == Opcodes.ASTORE) modelStore = store;
			}
			if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEVIRTUAL
					&& "net/minecraft/client/renderer/block/FluidModel".equals(call.owner)
					&& "fluidTintSource".equals(call.name)
					&& nextReal(call) instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFNULL) {
				AbstractInsnNode target = jump.label;
				while (target != null && target.getOpcode() < 0) target = target.getNext();
				if (target instanceof InsnNode constant && constant.getOpcode() == Opcodes.ICONST_M1
						&& nextReal(constant) instanceof VarInsnNode store && store.getOpcode() == Opcodes.ISTORE) {
					tintArms++;
					minusOne = constant;
				}
			}
		}
		if (lookups != 1 || modelStore == null || tintArms != 1) {
			ForbricLog.warn("[Forbric/MergedBaseCompat] FluidRenderer.tesselate does not have the expected shape "
					+ "(%d model lookup(s), store %s, %d tint fallback arm(s)) — leaving MinecraftForge fluid models "
					+ "unbridged rather than editing half of it", lookups, modelStore != null, tintArms);
			return false;
		}

		int slot = modelStore.var;
		InsnList funnel = new InsnList();
		funnel.add(new VarInsnNode(Opcodes.ALOAD, slot));
		funnel.add(new VarInsnNode(Opcodes.ALOAD, 5));
		funnel.add(new VarInsnNode(Opcodes.ALOAD, 1));
		funnel.add(new VarInsnNode(Opcodes.ALOAD, 2));
		funnel.add(new MethodInsnNode(Opcodes.INVOKESTATIC, KERNEL_FORGE_FLUIDS, "model", FLUID_MODEL_FUNNEL_DESC, false));
		funnel.add(new VarInsnNode(Opcodes.ASTORE, slot));
		tesselate.instructions.insert(modelStore, funnel);

		InsnList tint = new InsnList();
		tint.add(new VarInsnNode(Opcodes.ALOAD, 5));
		tint.add(new MethodInsnNode(Opcodes.INVOKESTATIC, KERNEL_FORGE_FLUIDS, "tintColor", "(" + FLUID_STATE + ")I", false));
		tesselate.instructions.insert(minusOne, tint);
		tesselate.instructions.remove(minusOne);
		tesselate.maxStack = Math.max(tesselate.maxStack, 4);
		ForbricLog.info("[Forbric/MergedBaseCompat] FluidRenderer.tesselate now asks a MinecraftForge fluid's client "
				+ "extensions for its model and tint — the merge kept NeoForge's tesselate, which never asks, so every "
				+ "Forge modded fluid drew as the missing texture");
		return true;
	}

	/**
	 * Writes {@code WeightedVariants.first} in {@code <init>}, from the local the merged constructor already computes.
	 *
	 * <p>Forge's {@code particleMaterial(ModelData)} reads {@code first} (its only reader in the base) and the merge
	 * dropped the write, so a Forge mod asking a weighted block model for its particle sprite the Forge way NPEs.
	 * Genuine Forge's constructor writes it from the same {@code getFirst()/value()} chain the merged constructor
	 * still computes into local 2; three instructions after that {@code ASTORE 2} restore it. Stands down if
	 * anything already writes the field (rebuilt base) or the chain has a different shape.
	 */
	private static boolean giveMinecraftForgesParticleLookupItsFirstVariant(ClassNode node) {
		if (!WEIGHTED_VARIANTS.equals(node.name)) return false;
		String desc = "L" + BLOCK_STATE_MODEL + ";";
		if (!hasField(node, "first", desc)) return false;
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
						&& WEIGHTED_VARIANTS.equals(field.owner) && "first".equals(field.name)) return false;
			}
		}
		MethodNode init = findMethod(node, "<init>", "(Lnet/minecraft/util/random/WeightedList;)V");
		if (init == null) return false;
		VarInsnNode store = null;
		for (AbstractInsnNode insn = init.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (!(insn instanceof VarInsnNode var) || var.getOpcode() != Opcodes.ASTORE || var.var != 2) continue;
			// previousReal answers the nearest real instruction AT or before its cursor, so step off each one first.
			AbstractInsnNode a = previousReal(var.getPrevious()), b = a == null ? null : previousReal(a.getPrevious()),
					c = b == null ? null : previousReal(b.getPrevious()), d = c == null ? null : previousReal(c.getPrevious());
			if (a instanceof TypeInsnNode castModel && castModel.getOpcode() == Opcodes.CHECKCAST
					&& BLOCK_STATE_MODEL.equals(castModel.desc)
					&& b instanceof MethodInsnNode value && "net/minecraft/util/random/Weighted".equals(value.owner)
					&& "value".equals(value.name)
					&& c instanceof TypeInsnNode castWeighted && castWeighted.getOpcode() == Opcodes.CHECKCAST
					&& "net/minecraft/util/random/Weighted".equals(castWeighted.desc)
					&& d instanceof MethodInsnNode first && "getFirst".equals(first.name)) {
				store = var;
				break;
			}
		}
		if (store == null) {
			ForbricLog.warn("[Forbric/MergedBaseCompat] WeightedVariants.<init> no longer computes the first model into "
					+ "local 2 the way the merge left it — not writing 'first'");
			return false;
		}
		InsnList write = new InsnList();
		write.add(new VarInsnNode(Opcodes.ALOAD, 0));
		write.add(new VarInsnNode(Opcodes.ALOAD, 2));
		write.add(new FieldInsnNode(Opcodes.PUTFIELD, WEIGHTED_VARIANTS, "first", desc));
		init.instructions.insert(store, write);
		init.maxStack = Math.max(init.maxStack, 2);
		ForbricLog.warn("[Forbric/MergedBaseCompat] WeightedVariants.first is written again (1 field, in <init>) — "
				+ "Forge's particleMaterial(ModelData) is its only reader and the merge dropped genuine Forge's write");
		return true;
	}

	private static boolean addMissingForgeFluidTypeBridge(ClassNode node) {
		if ((node.access & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT)) != 0) return false;
		if (!node.name.startsWith("net/minecraft/world/level/material/")) return false;
		if (!node.interfaces.contains("net/neoforged/neoforge/common/extensions/IFluidExtension")) return false;
		if (hasMethod(node, "getFluidType", "()Lnet/minecraftforge/fluids/FluidType;")) return false;

		MethodNode bridge = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
				"getFluidType", "()Lnet/minecraftforge/fluids/FluidType;", null, null);
		bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
				"net/forbric/kernel/interop/ForgeRuntimeInterop",
				"forgeFluidType", "(Ljava/lang/Object;)Ljava/lang/Object;", false));
		bridge.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraftforge/fluids/FluidType"));
		bridge.instructions.add(new InsnNode(Opcodes.ARETURN));
		bridge.maxStack = 1;
		bridge.maxLocals = 1;
		node.methods.add(bridge);
		ForbricLog.warn("[Forbric/MergedBaseCompat] added Forge FluidType bridge to %s",
				node.name.replace('/', '.'));
		return true;
	}

	private static boolean addMissingForgeKeyMappingLookupInitializer(ClassNode node) {
		if (!"net/minecraft/client/KeyMapping".equals(node.name)) return false;
		String forgeLookup = "Lnet/minecraftforge/client/settings/KeyMappingLookup;";
		if (!hasField(node, "MAP", forgeLookup) || initializesStaticField(node, "MAP", forgeLookup)) return false;

		MethodNode clinit = findMethod(node, "<clinit>", "()V");
		if (clinit == null) {
			clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
			clinit.instructions.add(new InsnNode(Opcodes.RETURN));
			clinit.maxLocals = 0;
			node.methods.add(clinit);
		}

		boolean inserted = false;
		for (AbstractInsnNode insn = clinit.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() != Opcodes.RETURN) continue;
			clinit.instructions.insertBefore(insn, new TypeInsnNode(Opcodes.NEW,
					ForeignType.KEY_MAPPING_LOOKUP.internal(Ecosystem.FORGE)));
			clinit.instructions.insertBefore(insn, new InsnNode(Opcodes.DUP));
			clinit.instructions.insertBefore(insn, new MethodInsnNode(Opcodes.INVOKESPECIAL,
					ForeignType.KEY_MAPPING_LOOKUP.internal(Ecosystem.FORGE), "<init>", "()V", false));
			clinit.instructions.insertBefore(insn, new FieldInsnNode(Opcodes.PUTSTATIC,
					"net/minecraft/client/KeyMapping", "MAP", forgeLookup));
			inserted = true;
		}
		if (!inserted) return false;

		clinit.maxStack = Math.max(clinit.maxStack, 2);
		ForbricLog.warn("[Forbric/MergedBaseCompat] initialized Forge KeyMapping lookup on merged client base");
		return true;
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
	 * read and the call is descriptor-identical. {@link #addMissingForgeKeyMappingLookupInitializer} still runs, so
	 * the Forge lookup stays non-null for any Forge code that reaches for it directly.
	 */
	private static boolean routeKeyMappingClickToPopulatedLookup(ClassNode node) {
		if (!"net/minecraft/client/KeyMapping".equals(node.name)) return false;

		String forgeLookup = "Lnet/minecraftforge/client/settings/KeyMappingLookup;";
		String neoLookup = "Lnet/neoforged/neoforge/client/settings/KeyMappingLookup;";
		// Only meaningful when the merge actually produced BOTH lookups; a single-ecosystem base is already coherent.
		if (!hasField(node, "MAP", forgeLookup) || !hasField(node, "MAP", neoLookup)) return false;

		MethodNode lookup = findMethod(node, "forAllKeyMappings",
				"(Lcom/mojang/blaze3d/platform/InputConstants$Key;Ljava/util/function/Consumer;)V");
		if (lookup == null) return false;

		boolean changed = false;
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
	 * Gives {@code ChunkGenerator.featuresPerStep} vanilla's descriptor back, and routes the one use that needed
	 * MinecraftForge's through a guard.
	 *
	 * <h2>Why this one is not the ParticleResources shape</h2>
	 *
	 * <p>{@link #giveTheVanillaParticleMapAViewOfTheLiveOne} repairs a field the merge kept TWICE, one of them
	 * unwritten. This is the other half of that family, and the worse half: MinecraftForge RE-TYPES the vanilla
	 * field — {@code Supplier<List<StepFeatureData>>} becomes its own {@code ClearableLazy<...>}, so that
	 * {@code refreshFeaturesPerStep()} has something to invalidate — and the merge keeps only MinecraftForge's
	 * declaration. Vanilla's descriptor does not exist at all, so there is no unwritten field to give a view to.
	 *
	 * <p>A whole-artifact census of the merged base against stock 26.2 finds six vanilla fields in this state;
	 * this is the one that costs a boot. fabric-api's {@code fabric-biome-api-v1} does not use an {@code @Accessor}
	 * — {@code BiomeModificationImpl.lambda$finalizeWorldGen$1} is a plain access-widened
	 * {@code putfield ChunkGenerator.featuresPerStep : Ljava/util/function/Supplier;} — so it gets
	 * {@code NoSuchFieldError} and the DEDICATED SERVER DOES NOT START the moment any Fabric biome modification
	 * applies. Installing balm, a library a large part of the Fabric ecosystem depends on, is enough to trigger it.
	 * lithostitched's {@code @Accessor setFeaturesPerStep(Supplier)} fails to bind for the same reason, from the
	 * other ecosystem.
	 *
	 * <h2>Why the repair is to move the field back rather than to add a second one</h2>
	 *
	 * <p>Because both descriptors can be satisfied by ONE field: {@code ClearableLazy extends Lazy extends
	 * Supplier}, so the value MinecraftForge's constructor already stores IS a {@code Supplier}. Declaring the
	 * field with vanilla's descriptor therefore keeps every existing reader correct while making the vanilla
	 * descriptor — the one two ecosystems' mods spell — exist again. Adding a second, vanilla-typed field instead
	 * would give fabric-api somewhere to write that nothing reads: the biome list would never be recomputed, the
	 * server would boot, and the modification would silently not apply. That is the failure this project has paid
	 * for more than once, and it is worse than the crash.
	 *
	 * <p>The rewrite is small and complete because the field has exactly FOUR instruction sites, all inside
	 * {@code ChunkGenerator} itself — verified by a constant-pool scan of the whole merged base and of both
	 * carriers, which find no other class naming it:
	 * <ul>
	 *   <li>{@code <init>}: {@code PUTFIELD} of {@code ClearableLazy.concurrentOf(...)} — descriptor only;</li>
	 *   <li>{@code validate()} and {@code applyBiomeDecoration(...)}: {@code GETFIELD} then
	 *       {@code ClearableLazy.get()} — retargeted to {@code Supplier.get()}, same descriptor, same stack;</li>
	 *   <li>{@code refreshFeaturesPerStep()}: {@code GETFIELD} then {@code ClearableLazy.invalidate()} — the one
	 *       use a plain {@code Supplier} cannot serve, so it goes to {@code KernelChunkGenerator.invalidate}.</li>
	 * </ul>
	 *
	 * <p>A bare {@code CHECKCAST} in {@code refreshFeaturesPerStep} would compile and look right, and then throw
	 * {@code ClassCastException} in worldgen the first time a Fabric modification had replaced the value — turning
	 * this fix into a different crash for the same mods. The guard also reports that state once, which is the only
	 * place either ecosystem could learn that MinecraftForge's refresh has become a no-op.
	 *
	 * <p>Stands down whole if it meets a site it does not recognise: a half-rewritten field is a
	 * {@code NoSuchFieldError} somewhere less legible than here. Idempotent by the same guard — after one pass no
	 * {@code ClearableLazy}-typed declaration remains, so the second pass finds nothing.
	 */
	private static boolean giveFeaturesPerStepItsVanillaDescriptorBack(ClassNode node) {
		if (!CHUNK_GENERATOR.equals(node.name)) return false;
		FieldNode field = null;
		for (FieldNode candidate : node.fields) {
			if (FEATURES_PER_STEP.equals(candidate.name) && CLEARABLE_LAZY_DESC.equals(candidate.desc)) {
				field = candidate;
			}
		}
		// Absent means vanilla's descriptor is already the only one — a rebuilt base, or this pass having run.
		if (field == null) return false;
		if (hasField(node, FEATURES_PER_STEP, SUPPLIER_DESC)) {
			// Both declarations present is the ParticleResources shape, not this one, and retyping would then
			// produce two fields with the same name AND descriptor, which is not a legal class.
			ForbricLog.warn("[Forbric/MergedBaseCompat] ChunkGenerator declares featuresPerStep with BOTH "
					+ "descriptors — that is the duplicate-field shape, which this repair must not touch");
			return false;
		}

		// Collect first, rewrite second: every site has to be one of the three known shapes, or none is changed.
		List<FieldInsnNode> sites = new ArrayList<>();
		List<MethodInsnNode> gets = new ArrayList<>();
		List<MethodInsnNode> invalidations = new ArrayList<>();
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof FieldInsnNode access) || !node.name.equals(access.owner)
						|| !FEATURES_PER_STEP.equals(access.name) || !CLEARABLE_LAZY_DESC.equals(access.desc)) {
					continue;
				}
				sites.add(access);
				if (access.getOpcode() == Opcodes.PUTFIELD) continue;
				if (access.getOpcode() != Opcodes.GETFIELD) {
					ForbricLog.warn("[Forbric/MergedBaseCompat] ChunkGenerator.featuresPerStep is accessed as a "
							+ "STATIC field in %s%s — not a shape this repair knows, so the field keeps "
							+ "MinecraftForge's descriptor and fabric-api's biome API stays broken",
							method.name, method.desc);
					return false;
				}
				AbstractInsnNode next = access.getNext();
				if (next instanceof MethodInsnNode call && CLEARABLE_LAZY.equals(call.owner)) {
					if ("get".equals(call.name) && "()Ljava/lang/Object;".equals(call.desc)) {
						gets.add(call);
						continue;
					}
					if ("invalidate".equals(call.name) && "()V".equals(call.desc)) {
						invalidations.add(call);
						continue;
					}
				}
				ForbricLog.warn("[Forbric/MergedBaseCompat] ChunkGenerator.featuresPerStep is read in %s%s and then "
						+ "used in a way this repair does not recognise — standing down whole rather than leaving "
						+ "the field half-retyped", method.name, method.desc);
				return false;
			}
		}
		if (sites.isEmpty()) return false;

		field.desc = SUPPLIER_DESC;
		// And the ACCESS the descriptor implies, which is not a tidy-up. fabric-api asks for exactly this field by
		// (owner, name, DESCRIPTOR) in fabric-biome-api-v1.classtweaker — "accessible" and "mutable" — and the
		// kernel applies class tweakers in the ACCESS phase, one phase BEFORE this one. So the request could not
		// have matched the ClearableLazy-typed declaration and the field is still private final here. Restoring
		// the descriptor alone therefore does not fix the boot, it only changes which error ends it:
		// NoSuchFieldError becomes IllegalAccessError, at the same cross-class PUTFIELD in BiomeModificationImpl.
		field.access = (field.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED | Opcodes.ACC_FINAL))
				| Opcodes.ACC_PUBLIC;
		// The generic signature is metadata, but a stale one contradicts the descriptor for anything that reads
		// both (reflection, and this project's own artifact scans). Swap the prefix when it is the expected shape.
		if (field.signature != null) {
			String lazyPrefix = "L" + CLEARABLE_LAZY + "<";
			field.signature = field.signature.startsWith(lazyPrefix)
					? SUPPLIER_DESC.substring(0, SUPPLIER_DESC.length() - 1) + "<"
							+ field.signature.substring(lazyPrefix.length())
					: null;
		}
		for (FieldInsnNode access : sites) {
			access.desc = SUPPLIER_DESC;
		}
		for (MethodInsnNode get : gets) {
			get.owner = SUPPLIER;
			get.itf = true;
		}
		for (MethodInsnNode invalidate : invalidations) {
			// GETFIELD leaves exactly the receiver on the stack, which is this static call's only argument, so the
			// replacement is one instruction for one instruction: no stack depth change, no frame to recompute.
			invalidate.setOpcode(Opcodes.INVOKESTATIC);
			invalidate.owner = KERNEL_CHUNK_GENERATOR;
			invalidate.name = "invalidate";
			invalidate.desc = "(" + SUPPLIER_DESC + ")V";
			invalidate.itf = false;
		}

		ForbricLog.warn("[Forbric/MergedBaseCompat] ChunkGenerator.featuresPerStep carried MinecraftForge's "
				+ "ClearableLazy descriptor and vanilla's had stopped existing, so fabric-api's biome API — which "
				+ "writes that field directly — threw NoSuchFieldError and the server did not start. The field is "
				+ "vanilla-typed again (%d access site(s), %d read(s) retargeted, %d invalidation(s) guarded)",
				sites.size(), gets.size(), invalidations.size());
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
	 * The same wrap on MinecraftForge's {@code ICondition.CODEC} — the THIRD strict evaluator, and the one that
	 * had not been hit yet.
	 *
	 * <p>The merged {@code ResourceManagerRegistryLoadTask.load} calls
	 * {@code ConditionCodec.wrap} at offset 15 while its own {@code lambda$load$1} builds NeoForge's
	 * {@code ConditionalOps}: both ecosystems' evaluators are live in the same method, over every datapack
	 * registry element. {@code LootPool} names the MinecraftForge one too. So a mod whose condition type only
	 * MinecraftForge cannot resolve fails a world load exactly the way waystones did on the NeoForge side.
	 *
	 * <p>Not {@code SAFE_CODEC}, which MinecraftForge already ships and which looks like the answer:
	 * {@code <clinit>} offsets 24-35 show it is {@code CODEC.orElse(FalseCondition.INSTANCE)}, so an unparseable
	 * condition evaluates FALSE and the element is dropped. Silently missing content is worse than the crash.
	 */
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

	private static boolean letForeignResourceConditionsThroughMinecraftForge(ClassNode node) {
		if (!FORGE_ICONDITION.equals(node.name)) return false;
		MethodNode clinit = findMethod(node, "<clinit>", "()V");
		if (clinit == null) return false;

		FieldInsnNode target = null;
		for (AbstractInsnNode insn = clinit.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC
					&& FORGE_ICONDITION.equals(field.owner) && "CODEC".equals(field.name)
					&& CODEC_DESC.equals(field.desc)) {
				if (target != null) {
					ForbricLog.warn("[Forbric/MergedBaseCompat] MinecraftForge's ICondition.CODEC is assigned more "
							+ "than once — not wrapping it, because only one of the assignments would survive");
					return false;
				}
				target = field;
			}
		}
		if (target == null) return false;
		if (target.getPrevious() instanceof MethodInsnNode already
				&& KERNEL_FORGE_CONDITIONS.equals(already.owner)) {
			return false;                       // already wrapped: idempotent
		}

		clinit.instructions.insertBefore(target, new MethodInsnNode(Opcodes.INVOKESTATIC, KERNEL_FORGE_CONDITIONS,
				"lenient", "(" + CODEC_DESC + ")" + CODEC_DESC, false));
		ForbricLog.info("[Forbric/MergedBaseCompat] MinecraftForge's resource-condition codec now tolerates a "
				+ "condition type it does not own — the merged base runs that evaluator over every datapack "
				+ "registry element AND every loot pool, so another ecosystem's condition used to fail the whole "
				+ "registry load and the world with it. OPTIONAL_FEILD_CODEC and SAFE_CODEC derive from CODEC "
				+ "later in the same <clinit>, so all three readers inherit this");
		return true;
	}

	/**
	 * Makes {@code DefaultAttributes} read BOTH ecosystems' mod-attribute maps, not just the one that won the merge.
	 *
	 * <p>Both families collect a mod's entity attributes into a map of their own —
	 * {@code ForgeHooks.FORGE_ATTRIBUTES} and NeoForge's {@code CommonHooks} equivalent — and vanilla's
	 * {@code DefaultAttributes} is the single consumer both patch. The merge keeps one patch, and it kept
	 * NeoForge's: {@code javap} of the merged class shows {@code getSupplier} and {@code hasSupplier} each calling
	 * {@code CommonHooks.getAttributesView()}, and a constant-pool scan of the whole merged base finds
	 * {@code EntityAttributeCreationEvent} named nowhere.
	 *
	 * <p>So a traditional MinecraftForge mod's attributes went into a map with no reader — the producer/consumer
	 * split this project has hit at field level before, here at method level. An {@code AttributeSupplier} is what
	 * gives a living entity its health and movement and an entity without one is refused, so it is not a
	 * degradation: {@code cursed_breeding} logged "has no attributes" 348 times in one boot and its mobs could not
	 * exist.
	 *
	 * <p>Both call sites take no arguments and return {@code Map}, so each is an owner/name replacement on one
	 * instruction with nothing on the stack moved.
	 */
	private static boolean serveDefaultAttributesBothEcosystems(ClassNode node) {
		if (!DEFAULT_ATTRIBUTES.equals(node.name)) return false;
		int redirected = 0;
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC
						|| !NEO_COMMON_HOOKS.equals(call.owner) || !"getAttributesView".equals(call.name)
						|| !ATTRIBUTES_VIEW.equals(call.desc)) {
					continue;
				}
				call.owner = KERNEL_FORGE_ATTRIBUTES;
				call.name = "attributesView";
				redirected++;
			}
		}
		if (redirected == 0) return false;
		ForbricLog.info("[Forbric/MergedBaseCompat] DefaultAttributes now reads both ecosystems' mod-attribute maps "
				+ "(%d call site(s)) — the merge kept only NeoForge's reader, so a traditional MinecraftForge mod's "
				+ "entities had no attributes and could not exist", redirected);
		return true;
	}

	static boolean restoreForgeClientInit(ClassNode node) {
		if (!"net/minecraft/client/Minecraft".equals(node.name)
				|| "off".equalsIgnoreCase(System.getProperty("forbric.forgeClientInit", "on"))) return false;
		String owner = ForeignType.CLIENT_HOOKS.internal(Ecosystem.NEOFORGE);
		String target = "net/forbric/kernel/runtime/KernelForgeClientInit";
		String init = "(Lnet/minecraft/client/Minecraft;Lnet/minecraft/server/packs/resources/ReloadableResourceManager;)V";
		String particles = "(Lnet/minecraft/client/particle/ParticleResources;)V";
		List<MethodInsnNode> matches = new java.util.ArrayList<>();
		MethodNode constructor = null;
		int initializers = 0, providers = 0;
		for (MethodNode method : node.methods) {
			if (!"<init>".equals(method.name)) continue;
			for (AbstractInsnNode instruction : method.instructions) {
				if (!(instruction instanceof MethodInsnNode call)) continue;
				if (!"initClientHooks".equals(call.name) && !"onRegisterParticleProviders".equals(call.name)) continue;
				if (target.equals(call.owner)) return false;
				if (!owner.equals(call.owner)) continue;
				if (call.getOpcode() != Opcodes.INVOKESTATIC || call.itf) return false;
				if ("initClientHooks".equals(call.name) && init.equals(call.desc)) initializers++;
				else if ("onRegisterParticleProviders".equals(call.name) && particles.equals(call.desc)) providers++;
				else return false;
				if (constructor != null && constructor != method) return false;
				constructor = method;
				matches.add(call);
			}
		}
		if (initializers != 1 || providers != 1) return false;
		for (MethodInsnNode call : matches) call.owner = target;
		// Both sites land or neither does (the checks above are whole-or-nothing), so both bridges are recorded
		// here; EventBridges.verify(CLIENT_INIT) names them at the client setup hook if this repair stood down.
		EventBridges.installed(GameEventBridge.CLIENT_INIT_HOOKS);
		EventBridges.installed(GameEventBridge.PARTICLE_PROVIDERS);
		ForbricLog.info("[Forbric/MergedBaseCompat] Minecraft now initializes both Forge families' client hooks and particles");
		return true;
	}

	static boolean restoreForgeGeometryReload(ClassNode node) {
		if (!"net/minecraft/client/resources/model/ModelManager".equals(node.name)
				|| "off".equalsIgnoreCase(System.getProperty("forbric.forgeClientInit", "on"))) return false;
		String desc = "(Lnet/minecraft/server/packs/resources/PreparableReloadListener$SharedState;Ljava/util/concurrent/Executor;"
				+ "Lnet/minecraft/server/packs/resources/PreparableReloadListener$PreparationBarrier;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;";
		MethodNode method = findMethod(node, "reload", desc);
		if (method == null || (method.access & Opcodes.ACC_STATIC) != 0) return false;
		for (AbstractInsnNode instruction : method.instructions) {
			if (instruction instanceof MethodInsnNode call
					&& (("net/forbric/kernel/runtime/KernelForgeClientInit".equals(call.owner)
							&& "initGeometryLoaders".equals(call.name))
						|| ("net/minecraftforge/client/model/geometry/GeometryLoaderManager".equals(call.owner)
							&& "init".equals(call.name)))) return false;
		}
		AbstractInsnNode first = method.instructions.getFirst();
		while (first != null && first.getOpcode() < 0) first = first.getNext();
		if (!(first instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD || load.var != 1) return false;
		AbstractInsnNode next = first.getNext();
		while (next != null && next.getOpcode() < 0) next = next.getNext();
		if (!(next instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKEVIRTUAL
				|| !"net/minecraft/server/packs/resources/PreparableReloadListener$SharedState".equals(call.owner)
				|| !"resourceManager".equals(call.name)
				|| !"()Lnet/minecraft/server/packs/resources/ResourceManager;".equals(call.desc)) return false;
		method.instructions.insertBefore(first, new MethodInsnNode(Opcodes.INVOKESTATIC,
				"net/forbric/kernel/runtime/KernelForgeClientInit", "initGeometryLoaders", "()V", false));
		ForbricLog.info("[Forbric/MergedBaseCompat] ModelManager initializes Forge geometry loaders on every resource reload");
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

	/**
	 * Removes a method whose whole body delegates to an interface default, when a SUPERCLASS has a real one.
	 *
	 * <h2>What the merge does</h2>
	 *
	 * <p>It injects these delegates blindly. Measured across the whole merged base against both unmerged bases:
	 * 256 methods are pure {@code Iface.super.<same method>} delegates that shadow a superclass with a real
	 * implementation, and 253 of them exist in neither unmerged base — so they are the merge's doing. The three
	 * that are not are vanilla's own and are left alone by the rule below, because their superclass merely
	 * delegates the same way.
	 *
	 * <p>What that costs depends on the method. {@code VehicleEntity.getDisplayName()} shadows
	 * {@code Entity.getDisplayName()}, which is the method that applies team colours and prefixes — so a boat or
	 * minecart loses its team formatting in every name it is shown under.
	 *
	 * <h2>Why THIS rule and not the older one</h2>
	 *
	 * <p>{@link #dropInterfaceDefaultShadowingOverrides} does the same thing for a hand-kept allowlist, and that
	 * allowlist exists because a wider version once crashed every GUI screen at the title with
	 * {@code IncompatibleClassChangeError: Conflicting default methods}: {@code getRectangle} is supplied as a
	 * default by two unrelated interfaces, so removing the override left two competing candidates.
	 *
	 * <p>The condition here cannot hit that. A concrete superclass method always wins over any interface default,
	 * so when the chain HAS one there is nothing for defaults to compete over — the crash happened precisely in
	 * classes whose chain had none. That makes this rule both wider and safer than the list it complements, and
	 * it needs no list to maintain.
	 *
	 * <h2>Why it is still limited to one method</h2>
	 *
	 * <p>Because VANILLA writes this shape on purpose too. {@code AbstractContainerWidget.nextFocusPath} is a
	 * pure delegate over a superclass with a real implementation, and it is present in BOTH unmerged bases — so
	 * "delegate over a real superclass method" alone does not mean "merge damage", and a rule keyed on the shape
	 * would quietly change vanilla's own behaviour. A first version of this rule did exactly that, and the test
	 * beside it caught it.
	 *
	 * <p>So the shape is necessary but not sufficient, and the set is measured rather than guessed: every such
	 * method in the merged base was differenced against both unmerged bases, and
	 * {@code getDisplayName()Lnet/minecraft/network/chat/Component;} is the entry whose 20 occurrences are all
	 * merge-introduced AND whose bypassed implementation does something a player can see. Widening it means
	 * repeating that measurement, not adding a name.
	 *
	 * <p>Stands down entirely without a class resolver: it cannot answer its own question without reading the
	 * superclass chain, and guessing is what the allowlist exists to avoid.
	 */
	private boolean dropStubsThatBypassARealSuperclassMethod(ClassNode node) {
		if (classBytes == null || node.superName == null || node.methods == null) return false;

		List<MethodNode> shadowing = new java.util.ArrayList<>();
		for (MethodNode method : node.methods) {
			if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0) continue;
			if (!MEASURED_MERGE_STUBS.contains(method.name + method.desc)) continue;
			if (!isPureInterfaceDelegate(method)) continue;
			if (!superclassHasARealImplementation(node.superName, method.name, method.desc)) continue;
			shadowing.add(method);
		}
		if (shadowing.isEmpty()) return false;

		node.methods.removeAll(shadowing);
		for (MethodNode dropped : shadowing) {
			ForbricLog.debug("[Forbric/MergedBaseCompat] dropped %s.%s%s — its whole body handed off to an "
					+ "interface default while its superclass has a real implementation",
					node.name.replace('/', '.'), dropped.name, dropped.desc);
		}
		return true;
	}

	/** Whether {@code method}'s entire body is {@code SomeInterface.super.<this very method>(args…)}. */
	private static boolean isPureInterfaceDelegate(MethodNode method) {
		if (method.instructions == null) return false;

		List<AbstractInsnNode> body = new java.util.ArrayList<>();
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() >= 0) body.add(insn);
		}

		Type[] args = Type.getArgumentTypes(method.desc);
		// this + one load per parameter + the interface-default call + the return, and NOTHING else.
		if (body.size() != args.length + 3) return false;

		if (!(body.get(0) instanceof VarInsnNode self) || self.getOpcode() != Opcodes.ALOAD || self.var != 0) {
			return false;
		}

		int slot = 1;
		for (int i = 0; i < args.length; i++) {
			if (!(body.get(1 + i) instanceof VarInsnNode load)
					|| load.getOpcode() != args[i].getOpcode(Opcodes.ILOAD) || load.var != slot) {
				return false;
			}
			slot += args[i].getSize();
		}

		if (!(body.get(args.length + 1) instanceof MethodInsnNode call)) return false;
		if (call.getOpcode() != Opcodes.INVOKESPECIAL || !call.itf) return false;
		return call.name.equals(method.name) && call.desc.equals(method.desc);
	}

	/**
	 * Whether the superclass chain declares this method with a body that is NOT itself such a delegate.
	 *
	 * <p>A superclass that delegates the same way is not something to be shadowed — removing the subclass's copy
	 * would change nothing — and those are exactly the three cases that exist in the unmerged bases too.
	 *
	 * <p>A class the resolver cannot produce ends the walk with "no": the honest answer when the chain cannot be
	 * read is that nothing is known to be shadowed, and the method stays.
	 */
	private boolean superclassHasARealImplementation(String superName, String name, String desc) {
		for (String at = superName; at != null; ) {
			byte[] bytes = classBytes.apply(at.replace('.', '/') + ".class");
			if (bytes == null) return false;

			ClassNode parent = new ClassNode();
			try {
				new ClassReader(bytes).accept(parent, ClassReader.SKIP_FRAMES);
			} catch (RuntimeException unreadable) {
				return false;
			}

			for (MethodNode m : parent.methods) {
				if (!m.name.equals(name) || !m.desc.equals(desc)) continue;
				if ((m.access & Opcodes.ACC_ABSTRACT) != 0) return false;
				return !isPureInterfaceDelegate(m);
			}
			at = parent.superName;
		}
		return false;
	}

	/**
	 * Gives the three root game types the capability lifecycle methods their own merged code calls.
	 *
	 * <p>The merge put each root class under NeoForge's {@code AttachmentHolder}, which dropped MinecraftForge's
	 * capability superclass and the two lifecycle methods that came with it — while keeping MinecraftForge's
	 * method BODIES further down. {@code javap} on the merged {@code BlockEntity}: {@code onChunkUnloaded()} is
	 * MinecraftForge's body and its one instruction is {@code invokevirtual BlockEntity.invalidateCaps}, a method
	 * that resolves nowhere. Walking {@code BlockEntity} to {@code Object} finds no declaration, and the one
	 * interface that could supply a default declares only {@code onChunkUnloaded} itself.
	 *
	 * <p>Nothing in the merged base calls that today — NeoForge's half removed the call site — so this is not a
	 * live crash. It is a live TRAP: a MinecraftForge mod's block entity that overrides {@code invalidateCaps} and
	 * calls {@code super}, which is ordinary in storage and machinery mods, links against a method that is not
	 * there and dies at that call with a message naming neither the merge nor the kernel.
	 *
	 * <p>No-ops, deliberately, and this is NOT a capability system. There is nothing here to invalidate or revive:
	 * the merged classes carry no MinecraftForge capability provider. A no-op makes the call link and do the
	 * nothing that is already happening. Actually attaching capabilities means giving these classes a provider,
	 * which is a merge-tool change, not a transformer one.
	 */
	private static boolean addTheMissingCapabilityLifecycleStubs(ClassNode node) {
		if (!CAPABILITY_ROOTS.contains(node.name)) return false;

		boolean changed = false;
		for (String name : new String[] {"invalidateCaps", "reviveCaps"}) {
			if (findMethod(node, name, "()V") != null) continue;

			MethodNode stub = new MethodNode(Opcodes.ACC_PUBLIC, name, "()V", null, null);
			stub.instructions.add(new InsnNode(Opcodes.RETURN));
			stub.maxStack = 0;
			stub.maxLocals = 1;
			node.methods.add(stub);
			changed = true;
		}

		if (changed) {
			ForbricLog.warn("[Forbric/MergedBaseCompat] %s had no capability lifecycle methods while its own merged "
					+ "code still calls them — a MinecraftForge mod overriding one and calling super would have "
					+ "died on a method that resolves nowhere. They now exist and do nothing, which is what is "
					+ "already happening: these classes carry no capability provider.", node.name.replace('/', '.'));
		}
		return changed;
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
	 * Points {@code Mob.getSpawnReason()} at the spawn field the game actually writes.
	 *
	 * <p>The merge left {@code Mob} carrying both families' spawn fields under different names —
	 * {@code spawnType} and {@code spawnReason} — and every producer writes {@code spawnType}. {@code javap} on
	 * the merged {@code Mob}: two {@code putfield spawnType}, zero {@code putfield spawnReason}. So
	 * {@code getSpawnReason()} returned null for every mob that has ever existed.
	 *
	 * <p>What that costs is a whole category of mod behaviour rather than a crash: "was this mob spawned
	 * naturally, from a spawner, by a spawn egg, or by a command" is how mob-drop, anti-farm, difficulty and
	 * quest mods decide whether to act at all, and a null sends every one of them down the same branch — usually
	 * the one that does nothing, silently.
	 *
	 * <p>Only the read moves. The field declaration stays, because an access widener or a mixin may name it, and
	 * removing it would cost more than the dead field does.
	 */
	private static boolean readTheSpawnReasonThatIsActuallyWritten(ClassNode node) {
		if (!"net/minecraft/world/entity/Mob".equals(node.name)) return false;
		if (!hasField(node, "spawnReason", SPAWN_REASON) || !hasField(node, "spawnType", SPAWN_REASON)) return false;

		// If anything ever writes spawnReason, the field is live and must be left alone — the same guard the
		// particle-map reroute uses, and for the same reason: a future base may keep the other family's producer.
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD
						&& node.name.equals(field.owner) && "spawnReason".equals(field.name)) {
					return false;
				}
			}
		}

		boolean changed = false;
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
						&& node.name.equals(field.owner) && "spawnReason".equals(field.name)
						&& SPAWN_REASON.equals(field.desc)) {
					field.name = "spawnType";
					changed = true;
				}
			}
		}
		if (changed) {
			ForbricLog.warn("[Forbric/MergedBaseCompat] Mob.getSpawnReason() read a field nothing ever writes, so "
					+ "it answered null for every mob — mods that branch on how a mob was spawned (spawner, egg, "
					+ "command, natural) all took the same branch. It now reads the field the game writes");
		}
		return changed;
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
	 * Gives {@code KeyMapping} back the MinecraftForge-typed accessors the merge dropped, and makes them mean
	 * something.
	 *
	 * <p>Two halves of one defect, both found by onekeyminer dying in its client setup with
	 * {@code AbstractMethodError: KeyMapping.setKeyConflictContext(…IKeyConflictContext) is abstract}.
	 *
	 * <p>First: the merged class kept NeoForge's accessors and MinecraftForge's constructors and FIELDS, but not
	 * MinecraftForge's accessors — an abstract method has no body for the splice to take. Any Forge mod that
	 * configures a keybinding therefore fails, and it fails in a class initializer, so the mod loses everything
	 * downstream of it.
	 *
	 * <p>Second, and the reason re-adding the methods over the MinecraftForge fields would have been worse than
	 * the crash: those fields are DEAD. Every live consumer reads NeoForge's — {@code same()} resolves conflicts
	 * through the NeoForge-typed {@code getKeyConflictContext}, and {@code isActiveAndMatches} /
	 * {@code setToDefault} / {@code isConflictContextAndModifierActive} all delegate into
	 * {@code IKeyMappingExtension}. A setter that wrote the MinecraftForge field would stop the crash and leave
	 * the binding behaving as though the mod had never set a context at all.
	 *
	 * <p>So the MinecraftForge face is adapted onto the NeoForge state, in both directions, through
	 * {@code KernelForgeKeyBindings}. The same reason applies to the MinecraftForge-typed CONSTRUCTORS, which
	 * write only the dead fields and leave the NeoForge ones null — a mod using one gets a mapping whose first
	 * conflict check is a NullPointerException. Each of them gains a tail that mirrors what it wrote into the
	 * fields the game reads.
	 */
	private static boolean giveKeyMappingItsMinecraftForgeFace(ClassNode node) {
		if (!KEY_MAPPING.equals(node.name)) return false;
		// Only when the merge actually split it: both sides' state present, only one side's accessors.
		if (!hasField(node, "keyConflictContext", MF_CONTEXT) || !hasField(node, "keyConflictContext", NEO_CONTEXT)) {
			return false;
		}
		if (findMethod(node, "setKeyConflictContext", "(" + MF_CONTEXT + ")V") != null) return false;

		addAdapted(node, "setKeyConflictContext", "(" + MF_CONTEXT + ")V", "(" + NEO_CONTEXT + ")V",
				"toNeoContext", MF_CONTEXT, NEO_CONTEXT);
		addAdapted(node, "getKeyConflictContext", "()" + MF_CONTEXT, "()" + NEO_CONTEXT,
				"toForgeContext", NEO_CONTEXT, MF_CONTEXT);
		addAdapted(node, "getKeyModifier", "()" + MF_MODIFIER, "()" + NEO_MODIFIER,
				"toForgeModifier", NEO_MODIFIER, MF_MODIFIER);
		addAdapted(node, "getDefaultKeyModifier", "()" + MF_MODIFIER, "()" + NEO_MODIFIER,
				"toForgeModifier", NEO_MODIFIER, MF_MODIFIER);
		addSetKeyModifierAndCode(node);
		int mirrored = mirrorForgeConstructorsIntoTheLiveFields(node);

		ForbricLog.warn("[Forbric/MergedBaseCompat] gave KeyMapping its MinecraftForge accessors back and pointed "
				+ "them at the NeoForge state the game actually reads — the merge kept both ecosystems' fields but "
				+ "only one side's accessors, and the other side's fields are read by nothing (%d constructor(s) "
				+ "also mirrored)", mirrored);
		return true;
	}

	/**
	 * Adds {@code name+forgeDesc} as a one-line delegate to {@code name+neoDesc}, converting through the kernel.
	 *
	 * <p>A getter pair differs only in return type, which no Java source can express and the JVM is perfectly
	 * happy with — the descriptor is part of the identity.
	 */
	private static void addAdapted(ClassNode node, String name, String forgeDesc, String neoDesc,
			String converter, String fromDesc, String toDesc) {
		boolean setter = forgeDesc.endsWith(")V");
		MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC, name, forgeDesc, null, null);
		m.visitVarInsn(Opcodes.ALOAD, 0);
		if (setter) {
			m.visitVarInsn(Opcodes.ALOAD, 1);
			m.visitMethodInsn(Opcodes.INVOKESTATIC, KERNEL_KEYS, converter,
					"(Ljava/lang/Object;)Ljava/lang/Object;", false);
			m.visitTypeInsn(Opcodes.CHECKCAST, internal(toDesc));
			m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, node.name, name, neoDesc, false);
			m.visitInsn(Opcodes.RETURN);
			m.maxStack = 2;
			m.maxLocals = 2;
		} else {
			m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, node.name, name, neoDesc, false);
			m.visitMethodInsn(Opcodes.INVOKESTATIC, KERNEL_KEYS, converter,
					"(Ljava/lang/Object;)Ljava/lang/Object;", false);
			m.visitTypeInsn(Opcodes.CHECKCAST, internal(toDesc));
			m.visitInsn(Opcodes.ARETURN);
			m.maxStack = 1;
			m.maxLocals = 1;
		}
		node.methods.add(m);
	}

	/** The two-argument setter, whose second argument passes through untouched. */
	private static void addSetKeyModifierAndCode(ClassNode node) {
		String forgeDesc = "(" + MF_MODIFIER + INPUT_KEY + ")V";
		String neoDesc = "(" + NEO_MODIFIER + INPUT_KEY + ")V";
		if (findMethod(node, "setKeyModifierAndCode", forgeDesc) != null) return;
		if (findMethod(node, "setKeyModifierAndCode", neoDesc) == null) return;
		MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC, "setKeyModifierAndCode", forgeDesc, null, null);
		m.visitVarInsn(Opcodes.ALOAD, 0);
		m.visitVarInsn(Opcodes.ALOAD, 1);
		m.visitMethodInsn(Opcodes.INVOKESTATIC, KERNEL_KEYS, "toNeoModifier",
				"(Ljava/lang/Object;)Ljava/lang/Object;", false);
		m.visitTypeInsn(Opcodes.CHECKCAST, internal(NEO_MODIFIER));
		m.visitVarInsn(Opcodes.ALOAD, 2);
		m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, node.name, "setKeyModifierAndCode", neoDesc, false);
		m.visitInsn(Opcodes.RETURN);
		m.maxStack = 3;
		m.maxLocals = 3;
		node.methods.add(m);
	}

	/**
	 * Copies what a MinecraftForge-typed constructor wrote into the fields the game reads.
	 *
	 * <p>Appended before every RETURN rather than woven into the assignments: the constructor may write its
	 * fields in any order, and only at the end is the final value known. Fields, not the new setters — a setter
	 * would re-enter the lookup registration the constructor has already done.
	 */
	private static int mirrorForgeConstructorsIntoTheLiveFields(ClassNode node) {
		String forgeLookup = "L" + ForeignType.KEY_MAPPING_LOOKUP.internal(Ecosystem.FORGE) + ";";
		String neoLookup = "L" + ForeignType.KEY_MAPPING_LOOKUP.internal(Ecosystem.NEOFORGE) + ";";

		int mirrored = 0;
		for (MethodNode m : node.methods) {
			if (!"<init>".equals(m.name) || m.instructions == null) continue;
			if (!m.desc.contains(MF_CONTEXT) && !m.desc.contains(MF_MODIFIER)) continue;

			// WHERE, not just what. The constructor's own tail registers the binding:
			//
			//     88  ALL.put(name, this)
			//     99  GETSTATIC KeyMapping.MAP : Lnet/minecraftforge/.../KeyMappingLookup;
			//    102  ALOAD key ; ALOAD this
			//    105  INVOKEVIRTUAL  net/minecraftforge/.../KeyMappingLookup.put(Key, KeyMapping)V
			//    108  RETURN
			//
			// and that put reads the mapping back through getKeyModifier() — the MinecraftForge-faced accessor
			// this transformer adds, which reads the NEOFORGE field. Mirroring before the RETURN put the write
			// AFTER the read, so the Neo field was still null at offset 105, toForgeModifier answered for a null,
			// and Forge's lookup did computeIfAbsent on the result. Every Forge-typed key binding died in its
			// own <clinit> with an NPE raised inside MinecraftForge's code.
			//
			// So the mirror goes before the FIRST access of either lookup, and if there is none it falls back to
			// the RETURNs — the shorter constructors delegate and have no put of their own.
			AbstractInsnNode anchor = firstLookupAccess(m, forgeLookup, neoLookup);
			boolean any = false;
			if (anchor != null) {
				m.instructions.insertBefore(anchor, mirrorFields(node));
				any = true;
			} else {
				for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
					if (insn.getOpcode() != Opcodes.RETURN) continue;
					m.instructions.insertBefore(insn, mirrorFields(node));
					any = true;
				}
			}

			// And the registration itself. Both getAll overloads read NeoForge's MAP (routeKeyMappingClickToPopulatedLookup
			// points the last one that did not at it), so a binding put into MinecraftForge's lookup is in a map
			// nothing ever reads: the key would exist, bind, show in the Controls screen and never fire. The two
			// put methods are descriptor-identical — (InputConstants$Key, KeyMapping)V — so this is a field
			// descriptor and an owner, nothing more.
			if (retargetLookupRegistration(m, forgeLookup, neoLookup)) any = true;

			if (any) {
				m.maxStack = Math.max(m.maxStack, 3);
				mirrored++;
			}
		}
		return mirrored;
	}

	/** The three field copies, as one list. Built per insertion point because an InsnList can only be added once. */
	private static InsnList mirrorFields(ClassNode node) {
		InsnList mirror = new InsnList();
		mirror.add(field(node, "keyConflictContext", MF_CONTEXT, NEO_CONTEXT, "toNeoContext"));
		mirror.add(field(node, "keyModifier", MF_MODIFIER, NEO_MODIFIER, "toNeoModifier"));
		mirror.add(field(node, "keyModifierDefault", MF_MODIFIER, NEO_MODIFIER, "toNeoModifier"));
		return mirror;
	}

	/** The first read of either family's {@code MAP}, which is where the constructor starts registering. */
	private static AbstractInsnNode firstLookupAccess(MethodNode m, String forgeLookup, String neoLookup) {
		for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() == Opcodes.GETSTATIC && insn instanceof FieldInsnNode field
					&& "MAP".equals(field.name)
					&& (forgeLookup.equals(field.desc) || neoLookup.equals(field.desc))) {
				return insn;
			}
		}
		return null;
	}

	/** Points a constructor's own {@code MAP.put} at the lookup the game reads. True when anything moved. */
	private static boolean retargetLookupRegistration(MethodNode m, String forgeLookup, String neoLookup) {
		boolean changed = false;
		for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() == Opcodes.GETSTATIC && insn instanceof FieldInsnNode field
					&& "MAP".equals(field.name) && forgeLookup.equals(field.desc)) {
				field.desc = neoLookup;
				changed = true;
			} else if (insn instanceof MethodInsnNode call && "put".equals(call.name)
					&& ForeignType.KEY_MAPPING_LOOKUP.internal(Ecosystem.FORGE).equals(call.owner)) {
				call.owner = ForeignType.KEY_MAPPING_LOOKUP.internal(Ecosystem.NEOFORGE);
				changed = true;
			}
		}
		return changed;
	}

	/** {@code this.<neo> = convert(this.<forge>)}, or nothing when either field is absent. */
	private static InsnList field(ClassNode node, String name, String fromDesc, String toDesc, String converter) {
		InsnList out = new InsnList();
		if (!hasField(node, name, fromDesc) || !hasField(node, name, toDesc)) return out;
		out.add(new VarInsnNode(Opcodes.ALOAD, 0));
		out.add(new VarInsnNode(Opcodes.ALOAD, 0));
		out.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, name, fromDesc));
		out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, KERNEL_KEYS, converter,
				"(Ljava/lang/Object;)Ljava/lang/Object;", false));
		out.add(new TypeInsnNode(Opcodes.CHECKCAST, internal(toDesc)));
		out.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, name, toDesc));
		return out;
	}

	/** {@code Lsome/Type;} to {@code some/Type}. */
	private static String internal(String descriptor) {
		return descriptor.substring(1, descriptor.length() - 1);
	}

	/**
	 * Removes GUI overrides whose whole body is {@code SomeInterface.super.sameMethod(args)}.
	 *
	 * <p>The byte-merge gives many client GUI classes an {@code implements ContainerEventHandler} they do not have in
	 * vanilla, plus a {@code keyPressed(KeyEvent)} override that does nothing but call the INTERFACE DEFAULT. On a
	 * plain widget that is harmless — nothing in its superclass chain declares {@code keyPressed}, so the default
	 * applies either way. On a {@code Screen} subclass it is a silent functional break: a class method beats an
	 * interface default, so the injected override SHADOWS {@code Screen.keyPressed} — and {@code Screen.keyPressed}
	 * is the only place the {@code isEscape() -> shouldCloseOnEsc() -> onClose()} branch lives.
	 *
	 * <p>Symptom: ESC cannot close the pause menu (or the options/world-selection screens), while ESC still closes
	 * the inventory, because {@code AbstractContainerScreen} carries its own ESC handling. Verified against the
	 * unmerged 26.2 client: vanilla {@code PauseScreen} is {@code extends Screen} with NO {@code keyPressed} override
	 * and no {@code ContainerEventHandler}, so the override is purely a merge artifact.
	 *
	 * <p>Deleting it is safe in BOTH shapes for THIS method, which is why this needs no class-hierarchy walk: for a
	 * {@code Screen} subclass the inherited {@code Screen.keyPressed} takes over (exactly vanilla dispatch), and for
	 * a widget with no superclass declaration the interface default still resolves — the same method that was being
	 * called explicitly. {@code ContainerEventHandler} extends {@code GuiEventListener}, so the two {@code keyPressed}
	 * defaults are ordered by specificity and deleting the override cannot create an ambiguity.
	 *
	 * <p><b>Restricted to methods {@code ContainerEventHandler} itself refines, and that restriction is
	 * load-bearing.</b> The same "body is only {@code Iface.super.same()}" shape ALSO expresses Java's mandatory
	 * diamond disambiguation: when two UNRELATED interfaces each supply the default, the class must override to pick
	 * one, and deleting that is not a no-op but unresolvable. Generalising by shape alone removed
	 * {@code getRectangle} — supplied by both {@code LayoutElement} and {@code GuiEventListener} — and every GUI
	 * screen died at the title screen on {@code IncompatibleClassChangeError: Conflicting default methods}.
	 *
	 * <p>{@link #SHADOWABLE} is exactly the set where that cannot happen: each entry is declared {@code default} by
	 * BOTH {@code ContainerEventHandler} and {@code GuiEventListener}, and since
	 * {@code ContainerEventHandler extends GuiEventListener} its version is strictly more specific, so removing an
	 * override always resolves to one winner. Verified against the merged jar: no other interface anywhere in
	 * {@code net/minecraft/client/gui/} declares any of them — whereas {@code getRectangle}, the one that broke, is
	 * NOT refined by {@code ContainerEventHandler} and so is correctly excluded by this rule.
	 *
	 * <p>Why the whole set and not just the one method that was reported: the merge injects these blindly, and each
	 * one silently shadows whatever real implementation the superclass chain had. {@code keyPressed} cost ESC on the
	 * pause menu; {@code mouseScrolled} cost ALL list scrolling ({@code AbstractContainerWidget} shadowed
	 * {@code AbstractScrollArea}'s real wheel handling, and {@code AbstractSelectionList} sits under it, so every
	 * scrollable list — mod list, world list, options — was dead); the click/drag/char entries are the same latent
	 * bug on paths nobody has exercised yet. Removing a delegate whose superclass chain has no real implementation
	 * is a no-op, so applying this to the whole set costs nothing and closes the rest of the family.
	 */
	private static final String CONTAINER_EVENT_HANDLER =
			"net/minecraft/client/gui/components/events/ContainerEventHandler";

	/** name+descriptor of every {@code ContainerEventHandler} default that also refines a {@code GuiEventListener} one. */
	private static final java.util.Set<String> SHADOWABLE = java.util.Set.of(
			"keyPressed(Lnet/minecraft/client/input/KeyEvent;)Z",
			"keyReleased(Lnet/minecraft/client/input/KeyEvent;)Z",
			"charTyped(Lnet/minecraft/client/input/CharacterEvent;)Z",
			"preeditUpdated(Lnet/minecraft/client/input/PreeditEvent;)Z",
			"mouseScrolled(DDDD)Z",
			"mouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z",
			"mouseReleased(Lnet/minecraft/client/input/MouseButtonEvent;)Z",
			"mouseDragged(Lnet/minecraft/client/input/MouseButtonEvent;DD)Z");

	private static boolean dropInterfaceDefaultShadowingOverrides(ClassNode node) {
		if (!node.name.startsWith("net/minecraft/client/gui/")) return false;
		if (node.interfaces == null || !node.interfaces.contains(CONTAINER_EVENT_HANDLER) || node.methods == null) {
			return false;
		}

		int before = node.methods.size();
		node.methods.removeIf(method -> isPureInterfaceDefaultDelegate(node, method));
		int removed = before - node.methods.size();
		if (removed == 0) return false;

		ForbricLog.debug("[Forbric/MergedBaseCompat] dropped %d interface-default-shadowing override(s) from %s",
				removed, node.name.replace('/', '.'));
		return true;
	}

	/**
	 * Whether {@code method}'s entire body is {@code ContainerEventHandler.super.<same method>(args…)}, for one of
	 * the {@link #SHADOWABLE} methods. Keyed to that set on purpose — see
	 * {@link #dropInterfaceDefaultShadowingOverrides} for why matching on body shape alone is unsafe.
	 */
	private static boolean isPureInterfaceDefaultDelegate(ClassNode node, MethodNode method) {
		if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0) return false;
		if (!SHADOWABLE.contains(method.name + method.desc)) return false;
		if (method.instructions == null) return false;

		java.util.List<AbstractInsnNode> body = new java.util.ArrayList<>();
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() >= 0) body.add(insn);
		}

		Type[] args = Type.getArgumentTypes(method.desc);
		// this + one load per parameter + the interface-default call + the return, and NOTHING else.
		if (body.size() != args.length + 3) return false;

		if (!(body.get(0) instanceof VarInsnNode self) || self.getOpcode() != Opcodes.ALOAD || self.var != 0) {
			return false;
		}

		int slot = 1;
		for (int i = 0; i < args.length; i++) {
			if (!(body.get(1 + i) instanceof VarInsnNode load)
					|| load.getOpcode() != args[i].getOpcode(Opcodes.ILOAD) || load.var != slot) {
				return false;
			}
			slot += args[i].getSize();
		}

		if (!(body.get(args.length + 1) instanceof MethodInsnNode call)) return false;
		if (call.getOpcode() != Opcodes.INVOKESPECIAL || !call.itf) return false;
		if (!call.name.equals(method.name) || !call.desc.equals(method.desc)) return false;
		if (!CONTAINER_EVENT_HANDLER.equals(call.owner)) return false;

		return body.get(args.length + 2).getOpcode() == Type.getReturnType(method.desc).getOpcode(Opcodes.IRETURN);
	}

	/**
	 * Sends the block-placement hook to NeoForge, whose type the merged snapshot list actually has.
	 *
	 * <p>{@code Level.capturedBlockSnapshots} survived the merge as
	 * {@code ArrayList<net.neoforged.neoforge.common.util.BlockSnapshot>} — NeoForge's element type won, and there is
	 * only ONE such field. But {@code ItemStack.useOn} kept calling MINECRAFTFORGE's
	 * {@code ForgeHooks.onPlaceItemIntoWorld}, which drains that same list expecting
	 * {@code net.minecraftforge.common.util.BlockSnapshot}. So placing ANY block threw
	 * {@code ClassCastException: neoforge…BlockSnapshot cannot be cast to minecraftforge…BlockSnapshot} on the
	 * server thread while handling {@code use_item_on} — the integrated server died the instant you right-clicked.
	 *
	 * <p>{@code CommonHooks.onPlaceItemIntoWorld(UseOnContext)} is NeoForge's counterpart with an IDENTICAL
	 * descriptor, so retargeting the {@code invokestatic} is type-exact and makes the consumer match the producer.
	 * Cost: MinecraftForge mods' {@code BlockEvent.EntityPlaceEvent} no longer fires (NeoForge's does). That is the
	 * same trade the merge already made for the snapshot type itself — the alternative is that nobody can place
	 * anything at all.
	 */
	private static boolean routePlaceItemHookToNeoForge(ClassNode node) {
		if (!node.name.startsWith("net/minecraft/") || node.methods == null) return false;

		boolean changed = false;
		for (MethodNode method : node.methods) {
			if (method.instructions == null) continue;

			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC) continue;
				if (!"net/minecraftforge/common/ForgeHooks".equals(call.owner)
						|| !"onPlaceItemIntoWorld".equals(call.name)) {
					continue;
				}
				call.owner = "net/neoforged/neoforge/common/CommonHooks";
				changed = true;
			}
		}
		if (!changed) return false;

		ForbricLog.warn("[Forbric/MergedBaseCompat] routed %s's block-placement hook to NeoForge — the merged "
				+ "Level.capturedBlockSnapshots holds NeoForge BlockSnapshots, so MinecraftForge's hook threw "
				+ "ClassCastException on every block placed", node.name.replace('/', '.'));
		return true;
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
	 * MinecraftForge's network channels pick the vanilla packet type for an outgoing payload from
	 * {@code Connection.getProtocol()}, which reads a Forge-added {@code outboundProtocol} field. Forge's patch keeps
	 * that field current from inside {@code setupOutboundProtocol} — a lambda chained onto the pipeline task — and
	 * NeoForge won the merge of that method, so the lambda survives in the class and nothing calls it. The
	 * constructor still seeds the field with the handshake protocol on the CLIENT flow (the server flow leaves it
	 * null, and {@code getProtocol} then falls back to the inbound field, which the merged
	 * {@code setupInboundProtocol} does maintain — so the server side never showed this). A Forbric client thus
	 * reports HANDSHAKING for the life of the connection and every Forge channel send from the client throws
	 * "Unsupported protocol HANDSHAKING in Forge Networking Channel" — its own channel declaration
	 * ({@code ChannelListManager.addChannels}) first of all, so the server's {@code Channel.isRemotePresent} never
	 * saw the client's channels.
	 *
	 * <p>Store the new protocol at the head of {@code setupOutboundProtocol}. Synchronous rather than
	 * pipeline-ordered, which for this field's one reader is the better contract: a payload built after the switch
	 * must already be a packet of the new protocol, because the pipeline task is queued ahead of it.
	 */
	private static boolean keepForgeOutboundProtocolCurrent(ClassNode node) {
		if (!"net/minecraft/network/Connection".equals(node.name)) return false;
		String protocolInfo = "Lnet/minecraft/network/ProtocolInfo;";
		if (!hasField(node, "outboundProtocol", protocolInfo)) return false;
		MethodNode setup = findMethod(node, "setupOutboundProtocol", "(" + protocolInfo + ")V");
		if (setup == null) return false;

		// Coherent already (a single-ecosystem base, or a merge that kept Forge's body): the method stores the field
		// itself, or still chains the lambda that does.
		if (writesField(setup, "outboundProtocol")) return false;
		for (AbstractInsnNode insn = setup.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (!(insn instanceof InvokeDynamicInsnNode indy)) continue;
			for (Object arg : indy.bsmArgs) {
				if (arg instanceof Handle handle && node.name.equals(handle.getOwner())) {
					MethodNode lambda = findMethod(node, handle.getName(), handle.getDesc());
					if (lambda != null && writesField(lambda, "outboundProtocol")) return false;
				}
			}
		}

		InsnList store = new InsnList();
		store.add(new VarInsnNode(Opcodes.ALOAD, 0));
		store.add(new VarInsnNode(Opcodes.ALOAD, 1));
		store.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, "outboundProtocol", protocolInfo));
		setup.instructions.insert(store);
		setup.maxStack = Math.max(setup.maxStack, 2);
		ForbricLog.warn("[Forbric/MergedBaseCompat] Connection.setupOutboundProtocol now updates MinecraftForge's "
				+ "outboundProtocol — the merge dropped the lambda that did, so a client Connection reported HANDSHAKING "
				+ "forever and every Forge channel send from the client threw");
		return true;
	}

	private static boolean writesField(MethodNode method, String fieldName) {
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() == Opcodes.PUTFIELD && insn instanceof FieldInsnNode field && fieldName.equals(field.name)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Stops the block-breaking overlay from crashing the render frame.
	 *
	 * <p>{@code LevelExtractor.extractBlockDestroyAnimation} asks the level for MinecraftForge's
	 * {@code ModelDataManager} and dereferences it without a check. On a single-ecosystem base that is safe, because
	 * Forge's own {@code ClientLevel} patch overrides the accessor; on the merged base NeoForge's override won, and
	 * because the two return different types it does not override Forge's at all — so the call lands on Forge's
	 * interface default, whose whole body is {@code return null}. Every frame drawn while any block is being broken
	 * then dies with "Description: Render Frame", which is why this only showed up once, in a run where a break
	 * animation happened to be on screen.
	 *
	 * <p>There is nothing to route it to: no path on this base ever builds a Forge-typed manager, so no Forge-typed
	 * model data exists to find. The call therefore becomes the value Forge's own lookup returns for a position it
	 * is not tracking — {@code ModelData.EMPTY} — which is what the overlay would have drawn with anyway. A mod's
	 * dynamic model data still reaches the block itself through NeoForge's manager, which the level does have; only
	 * the break overlay draws with defaults.
	 */
	private static boolean surviveTheMissingForgeModelDataManager(ClassNode node) {
		if (!"net/minecraft/client/renderer/extract/LevelExtractor".equals(node.name)) return false;

		boolean changed = false;
		for (MethodNode m : node.methods) {
			List<MethodInsnNode> lookups = new ArrayList<>();
			for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn.getOpcode() == Opcodes.INVOKEVIRTUAL && insn instanceof MethodInsnNode call
						&& FORGE_MODEL_DATA_MANAGER.equals(call.owner) && "getAtOrEmpty".equals(call.name)) {
					lookups.add(call);
				}
			}
			for (MethodInsnNode lookup : lookups) {
				// The receiver expression, exactly: ALOAD this; GETFIELD level; INVOKEVIRTUAL getModelDataManager;
				// then the position argument. Anything else means the method was rewritten upstream — leave it be.
				AbstractInsnNode pos = previousRealInsn(lookup);
				AbstractInsnNode manager = previousRealInsn(pos);
				AbstractInsnNode level = previousRealInsn(manager);
				AbstractInsnNode self = previousRealInsn(level);
				if (pos == null || pos.getOpcode() != Opcodes.ALOAD
						|| !(manager instanceof MethodInsnNode get) || !"getModelDataManager".equals(get.name)
						|| level == null || level.getOpcode() != Opcodes.GETFIELD
						|| self == null || self.getOpcode() != Opcodes.ALOAD) {
					continue;
				}
				// The constant goes in where the receiver expression began, BEFORE the five are unlinked: a removed
				// node's neighbours are no longer a usable anchor.
				m.instructions.insertBefore(self,
						new FieldInsnNode(Opcodes.GETSTATIC, FORGE_MODEL_DATA, "EMPTY", "L" + FORGE_MODEL_DATA + ";"));
				for (AbstractInsnNode dead : new AbstractInsnNode[] {self, level, manager, pos, lookup}) {
					m.instructions.remove(dead);
				}
				changed = true;
			}
		}
		if (!changed) return false;
		ForbricLog.warn("[Forbric/MergedBaseCompat] the block-breaking overlay no longer asks for MinecraftForge's "
				+ "model-data manager — NeoForge won the level's accessor, so Forge's returned null and every frame "
				+ "drawn while a block was being broken crashed the game");
		return true;
	}

	private static final String FORGE_MODEL_DATA_MANAGER = "net/minecraftforge/client/model/data/ModelDataManager";
	private static final String FORGE_MODEL_DATA = "net/minecraftforge/client/model/data/ModelData";
	/** Forge-only, like {@link #FORGE_MODEL_DATA}: NeoForge has no INBTBuilder, so ForeignType has no pair for it. */
	private static final String FORGE_NBT_BUILDER = "net/minecraftforge/common/util/INBTBuilder$Builder";
	private static final String NBT_BUILDER_FACTORY_DESC = "()L" + FORGE_NBT_BUILDER + ";";

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

	/** The next instruction that is not a label, line number or frame. */
	// ---------------------------------------------------------------------------------------------------------------
	// The legacy global_loot_modifiers.json index, seen by two managers with two ideas of what it is
	// ---------------------------------------------------------------------------------------------------------------

	static final String LOOT_MODIFIER_MANAGER_FORGE = ForeignType.LOOT_MODIFIER_MANAGER.internal(Ecosystem.FORGE);
	static final String LOOT_MODIFIER_MANAGER_NEO = ForeignType.LOOT_MODIFIER_MANAGER.internal(Ecosystem.NEOFORGE);
	static final String SIMPLE_JSON_LISTENER = "net/minecraft/server/packs/resources/SimpleJsonResourceReloadListener";
	static final String PREPARE = "prepare";
	static final String PREPARE_DESC = "(Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)Ljava/util/Map;";
	static final String KERNEL_LOOT_MODIFIERS = "net/forbric/kernel/runtime/KernelLootModifiers";
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
	 * <p>Both managers' DIRECTORY scans now run over a view of the resource manager that hides
	 * {@code *&#47;loot_modifiers/global_loot_modifiers.json}; MinecraftForge's own by-name read of its index is on
	 * the original manager and untouched, so the one path that owns the file keeps it. NeoForge's manager has no
	 * {@code prepare} of its own, so one is synthesized ({@code super.prepare(withoutTheLegacyIndex(rm), p)});
	 * MinecraftForge's existing {@code prepare} gets the same wrap on the {@code aload_1} feeding its
	 * {@code super.prepare} call. Keyed on the {@code LOOT_MODIFIER_MANAGER} pair; each half stands down on its own.
	 */
	private static boolean hideTheLegacyLootModifierIndexFromTheDirectoryScan(ClassNode node) {
		if (LOOT_MODIFIER_MANAGER_NEO.equals(node.name)) return synthesizeNeoForgePrepare(node);
		if (LOOT_MODIFIER_MANAGER_FORGE.equals(node.name)) return wrapMinecraftForgePrepare(node);
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

	private static boolean wrapMinecraftForgePrepare(ClassNode node) {
		MethodNode prepare = findMethod(node, PREPARE, PREPARE_DESC);
		if (prepare == null) return false;
		MethodInsnNode site = null;
		int sites = 0;
		for (AbstractInsnNode insn = prepare.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL && SIMPLE_JSON_LISTENER.equals(call.owner)
					&& PREPARE.equals(call.name) && PREPARE_DESC.equals(call.desc)) {
				sites++;
				site = call;
			}
			if (insn instanceof MethodInsnNode call && KERNEL_LOOT_MODIFIERS.equals(call.owner)) return false;    // second pass
		}
		if (sites != 1) {
			ForbricLog.warn("[Forbric/MergedBaseCompat] MinecraftForge's LootModifierManager.prepare calls super.prepare %d "
					+ "time(s), not once — its directory scan is not wrapped", sites);
			return false;
		}
		// aload_0; aload_1; aload_2; invokespecial — wrap the manager argument, the aload_1 two instructions back.
		AbstractInsnNode profiler = previousReal(site.getPrevious());
		AbstractInsnNode manager = previousReal(profiler.getPrevious());
		if (!(profiler instanceof VarInsnNode p) || p.var != 2 || !(manager instanceof VarInsnNode m) || m.var != 1) {
			ForbricLog.warn("[Forbric/MergedBaseCompat] MinecraftForge's LootModifierManager.prepare feeds super.prepare in a "
					+ "shape that is not aload_1/aload_2 — its directory scan is not wrapped");
			return false;
		}
		prepare.instructions.insert(manager, new MethodInsnNode(Opcodes.INVOKESTATIC, KERNEL_LOOT_MODIFIERS, WITHOUT_INDEX,
				WITHOUT_INDEX_DESC, false));
		ForbricLog.info("[Forbric/MergedBaseCompat] MinecraftForge's LootModifierManager scans loot_modifiers/ without the legacy "
				+ "index too (applied at 1 site) — it still reads its own index by name, on the original manager");
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

	/**
	 * One {@code switch} over an enum whose javac-generated {@code $SwitchMap$} holder class lost the merge.
	 *
	 * @param user     the class whose method switches
	 * @param holder   the synthetic inner class javac put the map in ({@code Owner$N})
	 * @param field    the map field ({@code $SwitchMap$<enum with $ for .>})
	 * @param enumType the enum switched over
	 * @param cases    case index (the value the map stored, 1-based) → enum constant name
	 */
	record LostSwitchMap(String user, String holder, String field, String enumType, Map<Integer, String> cases) {
	}

	/**
	 * javac compiles {@code switch (direction)} through a synthetic {@code Owner$N} class holding
	 * {@code static final int[] $SwitchMap$…}, numbered with the other anonymous classes of {@code Owner}. Both
	 * families patch {@code AbstractFurnaceBlockEntity}: MinecraftForge's {@code $2} is the switch map its
	 * {@code getCapability} needs, NeoForge's {@code $2} is a {@code SnapshotJournal} — and the merge kept ONE
	 * class per name. MinecraftForge's body then reads a field NeoForge's class never had, and every Forge pipe
	 * or hopper asking a furnace for {@code ITEM_HANDLER} dies with {@code NoSuchFieldError: $SwitchMap$…}.
	 * Found by the E7 furnace probe on gate-m29; a census of the whole base (in the test) finds exactly this one.
	 */
	static final List<LostSwitchMap> LOST_SWITCH_MAPS = List.of(new LostSwitchMap(
			"net/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity",
			"net/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity$2",
			"$SwitchMap$net$minecraft$core$Direction",
			"net/minecraft/core/Direction",
			Map.of(1, "UP", 2, "DOWN")));

	/**
	 * Replaces {@code getstatic $SwitchMap; <load>; invokevirtual ordinal; iaload; lookupswitch/tableswitch} with a
	 * chain of {@code <load>; getstatic Enum.CONST; if_acmpeq <case label>} ending in {@code goto <default>} —
	 * the same three-way decision without the holder class. The branch targets are the switch's own labels, so
	 * the frames already there stay right; the sequence replaced was straight-line with an empty stack before it
	 * and after it, and the replacement is too. Both-or-nothing: a switch key the table does not name, or a
	 * shape other than the one javac emits, leaves the method untouched.
	 */
	private static boolean inlineTheSwitchMapTheMergeLost(ClassNode node) {
		int inlined = 0;
		for (LostSwitchMap lost : LOST_SWITCH_MAPS) {
			if (!lost.user().equals(node.name)) continue;
			for (MethodNode method : node.methods) {
				for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
					if (!(insn instanceof FieldInsnNode get) || get.getOpcode() != Opcodes.GETSTATIC
							|| !lost.holder().equals(get.owner) || !lost.field().equals(get.name)) {
						continue;
					}
					AbstractInsnNode load = nextReal(get);
					AbstractInsnNode ordinal = nextReal(load);
					AbstractInsnNode iaload = nextReal(ordinal);
					AbstractInsnNode sw = nextReal(iaload);
					if (!(load instanceof VarInsnNode var) || var.getOpcode() != Opcodes.ALOAD
							|| !(ordinal instanceof MethodInsnNode call) || !"ordinal".equals(call.name)
							|| !lost.enumType().equals(call.owner) || iaload == null || iaload.getOpcode() != Opcodes.IALOAD
							|| !(sw instanceof org.objectweb.asm.tree.LookupSwitchInsnNode
									|| sw instanceof org.objectweb.asm.tree.TableSwitchInsnNode)) {
						ForbricLog.warn("[Forbric/MergedBaseCompat] %s.%s reads %s.%s in a shape that is not javac's switch "
								+ "map — not inlined", node.name.replace('/', '.'), method.name, lost.holder(), lost.field());
						continue;
					}
					List<Integer> keys = new ArrayList<>();
					List<LabelNode> labels = new ArrayList<>();
					LabelNode dflt;
					if (sw instanceof org.objectweb.asm.tree.LookupSwitchInsnNode lookup) {
						keys.addAll(lookup.keys);
						labels.addAll(lookup.labels);
						dflt = lookup.dflt;
					} else {
						org.objectweb.asm.tree.TableSwitchInsnNode table = (org.objectweb.asm.tree.TableSwitchInsnNode) sw;
						for (int k = table.min; k <= table.max; k++) keys.add(k);
						labels.addAll(table.labels);
						dflt = table.dflt;
					}
					boolean allNamed = true;
					for (int key : keys) if (!lost.cases().containsKey(key)) allNamed = false;
					if (!allNamed) {
						ForbricLog.warn("[Forbric/MergedBaseCompat] %s.%s switches on a case the table does not name (%s) "
								+ "— not inlined", node.name.replace('/', '.'), method.name, keys);
						continue;
					}
					InsnList chain = new InsnList();
					String enumDesc = "L" + lost.enumType() + ";";
					for (int i = 0; i < keys.size(); i++) {
						chain.add(new VarInsnNode(Opcodes.ALOAD, var.var));
						chain.add(new FieldInsnNode(Opcodes.GETSTATIC, lost.enumType(), lost.cases().get(keys.get(i)), enumDesc));
						chain.add(new JumpInsnNode(Opcodes.IF_ACMPEQ, labels.get(i)));
					}
					chain.add(new JumpInsnNode(Opcodes.GOTO, dflt));
					AbstractInsnNode last = chain.getLast();    // insertBefore empties `chain`
					method.instructions.insertBefore(get, chain);
					// Drop the five instructions, leaving any label/line/frame nodes between them where they are.
					for (AbstractInsnNode victim : List.of(get, load, ordinal, iaload, sw)) method.instructions.remove(victim);
					method.maxStack = Math.max(method.maxStack, 2);
					inlined++;
					insn = last;
				}
			}
		}
		if (inlined == 0) return false;
		ForbricLog.info("[Forbric/MergedBaseCompat] %s decides %d enum switch(es) by direct comparison — javac's "
				+ "$SwitchMap$ holder class for them was MinecraftForge's, and the merge kept NeoForge's class of the "
				+ "same name instead, so the read was a NoSuchFieldError on every Forge ITEM_HANDLER ask of a furnace",
				node.name.replace('/', '.'), inlined);
		return true;
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

	private static boolean initializesStaticField(ClassNode node, String name, String desc) {
		for (MethodNode method : node.methods) {
			if (!method.name.equals("<clinit>") || !method.desc.equals("()V")) continue;
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC
						&& field.owner.equals(node.name) && field.name.equals(name) && field.desc.equals(desc)) {
					return true;
				}
			}
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
		ForbricLog.warn("[Forbric/MergedBaseCompat] gave GuiRenderer's pooled picture-in-picture lookup a fallback to "
				+ "the orphaned vanilla map — NeoForge won preparePictureInPictureState, so every guest-registered "
				+ "GUI element (Xaero's minimap, malilib's overlays) was registered where nothing reads");
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
