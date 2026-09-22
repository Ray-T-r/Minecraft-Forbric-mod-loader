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

package net.forbric.kernel.boot;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.util.ForbricLog;

/**
 * The boot→game seam: every {@code net.forbric.kernel.runtime.} class the boot side names, and every method it
 * calls on one.
 *
 * <h2>Why a registry, and why it is checked at boot</h2>
 *
 * <p>The boot side cannot name a game-side class as a TYPE — that is the whole reason the game side exists — so
 * it names them as STRINGS: a {@code Class.forName} argument, a {@code getMethod} name, an ASM internal name.
 * Nothing in the compiler relates a string to the code that satisfies it. A string that has drifted produces no
 * error and no warning; it produces a {@code ClassNotFoundException} or {@code NoSuchMethodException} at the
 * moment of USE, which for these is the middle of a mod's construction.
 *
 * <p>Two failures are worth separating and both are caught here. A boot jar built on a machine with no staged
 * artifacts carries no {@code forbric-kernel-runtime.jar} at all — it launches, boots, loads mods, and dies on
 * the first game-side class with a message that names the class and says nothing about the build. And a
 * game-side method renamed without its caller produces the same shape of report one layer deeper. Checking the
 * whole seam once, right after the pipeline is assembled, turns both into one line at the top of the log.
 *
 * <p>This lists the SEAM, not the whole game side. A name belongs here when boot-side code spells it; the classes
 * a game-side class reaches on its own are ordinary Java to it, checked by javac, and listing them would be
 * listing things that cannot drift. {@code KernelRuntimeClassesTest} enforces that correspondence in both
 * directions — a registry nobody is forced to update goes stale silently, which is the defect it exists to
 * prevent, one level up.
 *
 * <p>{@link Origin#GENERATED} entries are deliberately NOT checked: they are emitted by a boot-side
 * {@code ClassWriter} on demand and are correctly absent from the jar. They are listed anyway so this file is the
 * one place that answers "what is on the game side, and how does it get there".
 */
public final class KernelRuntimeClasses {
	/** How a game-side kernel class comes into being. */
	public enum Origin {
		/** Compiled from {@code src/runtime/java} and delivered in {@code forbric-kernel-runtime.jar}. */
		COMPILED,
		/**
		 * Emitted at runtime by a boot-side ASM {@code ClassWriter}. Correct — and unavoidable — where the class
		 * must implement a type that is NOT on the game source set's compile classpath, i.e. anything from
		 * fabric-api, which is a mod the user installs rather than a staged artifact.
		 */
		GENERATED,
	}

	/**
	 * A static method the boot side calls across the seam.
	 *
	 * <p>Every type named here is a JDK type on purpose. Game objects cross this boundary as {@code Object} —
	 * they have to, the boot side cannot name them — so the signature is expressible on both sides, and that is
	 * what makes it checkable from here at all.
	 */
	public record Call(String name, Class<?> returns, Class<?>... parameters) {
	}

	private record Entry(Origin origin, List<Call> calls) {
	}

	/** Binary name → how it is delivered and what is called on it. Insertion-ordered for a stable message. */
	private static final Map<String, Entry> CLASSES = new LinkedHashMap<>();

	static {
		// The full-power game-side lookup Forge's EventBus needs to spin listener lambdas. See KernelGameLookup.
		CLASSES.put("net.forbric.kernel.runtime.KernelGameLookupHelper", new Entry(Origin.COMPILED, List.of(
				new Call("lookup", MethodHandles.Lookup.class))));
		// The mod-container factory: the whole IModInfo/IModFileInfo/IModFile/IConfigurable chain plus the
		// ModContainer itself. Only this entry point is named from the boot side; the five classes behind it are
		// reached through it, game-side, with the compiler checking every call. See KernelModContainerFactory.
		CLASSES.put("net.forbric.kernel.runtime.KernelContainers", new Entry(Origin.COMPILED, List.of(
				new Call("container", Object.class, String.class, Object.class, Path.class),
				new Call("modInfo", Object.class, String.class, Path.class),
				new Call("minecraftContainer", Object.class))));
		// The traditional-Forge loading context: BusGroup + FMLModContainer + FMLJavaModLoadingContext, and the
		// IModInfo they carry. A separate factory from KernelContainers because traditional Forge differs from
		// NeoForge at every joint the kernel touches. See KernelForgeModContext.
		// Its one entry point takes six GAME types, which cannot be named from here — the descriptor the
		// transformer writes is the contract, and TransformerAnchorCensusTest is what holds the two in step.
		CLASSES.put("net.forbric.kernel.runtime.KernelItemTooltips", new Entry(Origin.COMPILED, List.of()));
		// Every parameter is Object (netty is not on the runtime source set's compile path), so the call CAN be
		// checked: a rename on either side becomes one line at the top of the log instead of an AbstractMethodError
		// inside the netty pipeline.
		CLASSES.put("net.forbric.kernel.runtime.KernelPacketContext", new Entry(Origin.COMPILED, List.of(
				new Call("encodeInFabricContext", void.class, Object.class, Object.class, Object.class,
						Object.class))));
		CLASSES.put("net.forbric.kernel.runtime.KernelForgeContainers", new Entry(Origin.COMPILED, List.of(
				new Call("create", KernelForgeModContext.Handle.class, String.class),
				new Call("setActiveContainer", void.class, Object.class),
				new Call("constructMod", Object.class, String.class, KernelForgeModContext.Handle.class),
				new Call("startup", void.class, Object.class))));
		// The traditional-Forge setup phases: build one mod-lifecycle event, post it at every MinecraftForge mod,
		// drain the stage's deferred queue. Separate from KernelForgeContainers because a phase is a different
		// question from a container, and because this one names six event types a NeoForge-only instance must never
		// be made to resolve. See KernelForgeSetup.
		CLASSES.put("net.forbric.kernel.runtime.KernelForgeSetup", new Entry(Origin.COMPILED, List.of(
				new Call("firePhase", int.class, List.class, net.forbric.api.ForeignType.class, String.class))));
		// Which registries traditional Forge's RegisterEvent is posted for, and how one is built and posted. The
		// dispatch LOOP stays boot-side (KernelForgeModContext.dispatchIsolated) because it names no game type and
		// the per-(registry, mod) isolation is the property worth asserting off-game. See KernelForgeRegistries.
		CLASSES.put("net.forbric.kernel.runtime.KernelForgeRegistries", new Entry(Origin.COMPILED, List.of(
				new Call("targets", List.class),
				new Call("post", void.class, Object.class, Object[].class))));
		// Serving a Forge-family mod jar's own data/ to the server datapack repository. The POLICY — which jars
		// carry data, who owns them, what each pack is called and how they stack — stays boot-side in
		// KernelDataPacks, where it names no game type and is tested as such. See KernelDataPackSource.
		CLASSES.put("net.forbric.kernel.runtime.KernelDataPackSource", new Entry(Origin.COMPILED, List.of(
				new Call("buildPack", Object.class, String.class, Path.class, Object.class),
				new Call("addSource", void.class, Object.class, List.class, String.class))));
		// Serving an ecosystem jar's assets/ to the CLIENT resource repository: every Pack, the visible parent
		// that lets a player's own pack sit above mod textures, the overlay count and the RepositorySource. The
		// policy stays boot-side in KernelClientPacks. See KernelClientPackSource.
		CLASSES.put("net.forbric.kernel.runtime.KernelClientPackSource", new Entry(Origin.COMPILED, List.of(
				new Call("buildPack", Object.class, String.class, Path.class, boolean.class),
				new Call("buildParentPack", Object.class, String.class, List.class),
				new Call("withOverlays", int.class, List.class),
				new Call("addSource", void.class, Object.class, List.class, String.class))));
		// The registry repairs the kernel makes once its registration window closes. WHEN each runs is
		// hand-ordered in KernelLifecycle.closeRegistrationWindow and stays there; only what each DOES is here.
		// See KernelRegistryContent.
		CLASSES.put("net.forbric.kernel.runtime.KernelRegistryContent", new Entry(Origin.COMPILED, List.of(
				new Call("rebuildBlockStateIds", int.class),
				new Call("initialiseBlockStateCaches", int.class),
				new Call("initialiseBlockInfoCaches", boolean.class),
				new Call("sortCreativeTabs", void.class),
				new Call("startCreativeTabProbe", void.class),
				new Call("linkBlockItems", int.class),
				new Call("logRegisteredContent", void.class))));
		// Loading NeoForge's configs: the early pass and the late pass that catches what it could not have seen.
		// WHICH types each covers stays boot-side, where lateConfigTypes has a test. See KernelConfigLoad.
		// Key mappings a mod registered on a bus that is not in ModList, so the one fan-out from Options.<init>
		// never reached it. See KernelForeignShimKeys and KernelForeignShimContext.
		CLASSES.put("net.forbric.kernel.runtime.KernelForeignShimKeys", new Entry(Origin.COMPILED, List.of(
				new Call("deliver", int.class, java.util.Collection.class))));
		CLASSES.put("net.forbric.kernel.runtime.KernelConfigLoad", new Entry(Origin.COMPILED, List.of(
				new Call("loadEarly", void.class, List.class),
				new Call("openLate", List.class, List.class))));
		CLASSES.put("net.forbric.kernel.runtime.KernelForgeConfigLoad", new Entry(Origin.COMPILED, List.of(
				new Call("loadEarly", void.class, List.class),
				new Call("openLate", List.class, List.class))));
		// Called by game bytecode; its signatures name Minecraft client types, not boot-side seam types.
		CLASSES.put("net.forbric.kernel.runtime.KernelForgeClientInit", new Entry(Origin.COMPILED, List.of()));
		CLASSES.put("net.forbric.kernel.runtime.KernelCompatibilityPrompts", new Entry(Origin.COMPILED, List.of()));
		CLASSES.put("net.forbric.kernel.runtime.KernelPortalSpawn", new Entry(Origin.COMPILED, List.of()));
		CLASSES.put("net.forbric.kernel.runtime.KernelForgeReload", new Entry(Origin.COMPILED, List.of()));
		CLASSES.put("net.forbric.kernel.runtime.KernelLootBridge", new Entry(Origin.COMPILED, List.of(
				new Call("install", void.class))));
		CLASSES.put("net.forbric.kernel.runtime.KernelLootModifiers", new Entry(Origin.COMPILED, List.of()));
		CLASSES.put("net.forbric.kernel.runtime.KernelFeatureFlags", new Entry(Origin.COMPILED, List.of()));
		CLASSES.put("net.forbric.kernel.runtime.KernelSnippets", new Entry(Origin.COMPILED, List.of()));
		CLASSES.put("net.forbric.kernel.runtime.KernelWidenedFields", new Entry(Origin.COMPILED, List.of()));
		CLASSES.put("net.forbric.kernel.runtime.KernelForgeIngredients", new Entry(Origin.COMPILED, List.of()));
		CLASSES.put("net.forbric.kernel.runtime.KernelForgeFluids", new Entry(Origin.COMPILED, List.of()));
		CLASSES.put("net.forbric.kernel.runtime.KernelForgeWorldgen", new Entry(Origin.COMPILED, List.of(
				new Call("declareForgeModifierRegistries", void.class, Object.class))));
		CLASSES.put("net.forbric.kernel.runtime.KernelForgeCapabilities", new Entry(Origin.COMPILED, List.of(
				new Call("injectCapabilities", int.class))));
		CLASSES.put("net.forbric.kernel.runtime.KernelForgeBlockColors", new Entry(Origin.COMPILED, List.of()));
		CLASSES.put("net.forbric.kernel.runtime.KernelForgeOptions", new Entry(Origin.COMPILED, List.of()));
		CLASSES.put("net.forbric.kernel.runtime.KernelForgeClientConsumers", new Entry(Origin.COMPILED, List.of()));
		CLASSES.put("net.forbric.kernel.runtime.KernelForgeCreativeTabs", new Entry(Origin.COMPILED, List.of()));
		CLASSES.put("net.forbric.kernel.runtime.KernelForgeSpawnPlacements", new Entry(Origin.COMPILED, List.of()));
		// The NeoForge setup phases. A twin of KernelForgeSetup rather than a merge of it: NeoForge dispatches on
		// a per-mod IEventBus while EventBus 7 resolves a bus from the EVENT plus that mod's BusGroup, and folding
		// the two would be the averaging-away ForeignType's javadoc warns about. See KernelNeoSetup.
		CLASSES.put("net.forbric.kernel.runtime.KernelNeoSetup", new Entry(Origin.COMPILED, List.of(
				new Call("firePhase", int.class, Map.class, net.forbric.api.ForeignType.class, String.class))));
		// NeoForge's registry phase: NewRegistryEvent, then RegisterEvent for every registry in NeoForge's own
		// order. The twin of KernelForgeRegistries; which buses and which order stay boot-side, as does the
		// unfreeze/freeze window it all runs inside. See KernelNeoRegistries.
		CLASSES.put("net.forbric.kernel.runtime.KernelNeoRegistries", new Entry(Origin.COMPILED, List.of(
				new Call("collect", List.class, boolean.class),
				new Call("fireRegisterEvents", int.class, List.class, List.class),
				new Call("postNewRegistryEvent", int.class, List.class))));
		// Materialises the kernel's own annotation scan into NeoForge's ModFileScanData. The scan itself is
		// boot-side bytecode work; only this last step needs game types. See ModFileScanner.
		CLASSES.put("net.forbric.kernel.runtime.KernelScanData", new Entry(Origin.COMPILED, List.of(
				new Call("build", Object.class, List.class, List.class))));
		// The same scan, materialised into MinecraftForge's own ModFileScanData. A second compiled file rather
		// than a shared reflective builder: the two SPIs are separate classes with identical shapes, and javac
		// checking each against the one it targets is the only thing that stops a reorder from going quiet.
		CLASSES.put("net.forbric.kernel.runtime.KernelForgeScanData", new Entry(Origin.COMPILED, List.of(
				new Call("build", Object.class, List.class, List.class))));
		// Fills the client ResourceManager with its selected packs before mod setup, because MinecraftForge runs
		// mod loading inside the first resource reload and the kernel's window is before it. See KernelClientResources.
		CLASSES.put("net.forbric.kernel.runtime.KernelClientResources", new Entry(Origin.COMPILED, List.of(
				new Call("preload", int.class))));
		// The Neo->Forge server-tick re-emission. Two entries rather than one taking the kind, because the two
		// MinecraftForge hooks share a descriptor and a crossed pairing would compile. See KernelGameTickEvents.
		CLASSES.put("net.forbric.kernel.runtime.KernelGameTickEvents", new Entry(Origin.COMPILED, List.of(
				new Call("installPre", void.class, Object.class),
				new Call("installPost", void.class, Object.class),
				new Call("installLevelPre", void.class, Object.class),
				new Call("installLevelPost", void.class, Object.class),
				new Call("installPlayerPre", void.class, Object.class),
				new Call("installPlayerPost", void.class, Object.class))));
		// Commands and the player lifecycle: the merged base calls only NeoForge's hooks at those sites.
		CLASSES.put("net.forbric.kernel.runtime.KernelGamePlayerEvents", new Entry(Origin.COMPILED, List.of(
				new Call("installCommands", void.class, Object.class),
				new Call("installLoggedIn", void.class, Object.class),
				new Call("installLoggedOut", void.class, Object.class),
				new Call("installRespawn", void.class, Object.class),
				new Call("installChangedDimension", void.class, Object.class))));
		// The cancellable entity events: these carry a MinecraftForge mod's veto back onto the NeoForge event,
		// so a renamed entry point costs a whole class of mods their ability to say no.
		CLASSES.put("net.forbric.kernel.runtime.KernelGameEntityEvents", new Entry(Origin.COMPILED, List.of(
				new Call("installLivingDeath", void.class, Object.class),
				new Call("installLivingDrops", void.class, Object.class),
				new Call("installEntityJoinLevel", void.class, Object.class))));
		// The cancellable BLOCK events, apart from the entity ones because they name NeoForge's block-event
		// package; a renamed entry point here is a protection mod that stops protecting, silently.
		CLASSES.put("net.forbric.kernel.runtime.KernelGameBlockEvents", new Entry(Origin.COMPILED, List.of(
				new Call("installBlockBreak", void.class, Object.class),
				new Call("installRightClickBlock", void.class, Object.class),
				new Call("installLeftClickBlock", void.class, Object.class),
				new Call("installRightClickItem", void.class, Object.class),
				new Call("installEntityPlace", void.class, Object.class))));
		// MinecraftForge's picture-in-picture renderers. The merged GuiRenderer's constructor calls build()
		// directly, so a renamed entry point here is a NoSuchMethodError inside the game's own constructor.
		CLASSES.put("net.forbric.kernel.runtime.KernelForgePipRenderers", new Entry(Origin.COMPILED, List.of(
				new Call("build", java.util.Map.class))));
		// MinecraftForge's HUD overlay stack, which the merged base has no reference to at all. Client only, and
		// a renamed entry point here is a mod's overlay silently not drawing.
		CLASSES.put("net.forbric.kernel.runtime.KernelForgeOverlayLayers", new Entry(Origin.COMPILED, List.of(
				new Call("install", void.class, Object.class))));
		// The CLIENT tick, in its own class because it names NeoForge's client event package — a dedicated server
		// must never be made to resolve those types, and keeping them apart means it never loads the class.
		CLASSES.put("net.forbric.kernel.runtime.KernelGameClientTickEvents", new Entry(Origin.COMPILED, List.of(
				new Call("installPre", void.class, Object.class),
				new Call("installPost", void.class, Object.class))));
		// The Neo->Forge RENDER FRAME re-emission. Its own entry for the same reason the client tick has one
		// separate from the game tick: it names a different NeoForge client event, and a carrier missing that
		// type must not take the tick bridge down with it.
		CLASSES.put("net.forbric.kernel.runtime.KernelGameRenderFrameEvents", new Entry(Origin.COMPILED, List.of(
				new Call("installPre", void.class, Object.class),
				new Call("installPost", void.class, Object.class))));
		// The Neo->Forge SCREEN MOUSE re-emission. Its own entry again: the merged MouseHandler is a third
		// producer, separate from the client tick and the render frame, and a carrier missing NeoForge's screen
		// event types must cost only this family rather than the two beside it.
		CLASSES.put("net.forbric.kernel.runtime.KernelGameScreenMouseEvents", new Entry(Origin.COMPILED, List.of(
				new Call("installPressedPre", void.class, Object.class),
				new Call("installReleasedPre", void.class, Object.class),
				new Call("installDragPre", void.class, Object.class),
				new Call("installScrollPost", void.class, Object.class))));
		// The Neo->Forge LEVEL LIFECYCLE re-emission. Its own entry for the same reason: load/unload/save are
		// posted from Minecraft, ClientLevel, MinecraftServer and ServerLevel, a producer set shared with none
		// of the bridges above, so a carrier missing NeoForge's level event types must cost only this family.
		CLASSES.put("net.forbric.kernel.runtime.KernelGameLevelEvents", new Entry(Origin.COMPILED, List.of(
				new Call("installLevelLoad", void.class, Object.class),
				new Call("installLevelUnload", void.class, Object.class),
				new Call("installLevelSave", void.class, Object.class))));
		// The bridges whose MinecraftForge hook returns a value, kept apart from the observing ones because
		// dropping that value is a different and worse failure than not bridging at all.
		CLASSES.put("net.forbric.kernel.runtime.KernelGameResultBridges", new Entry(Origin.COMPILED, List.of(
				new Call("installItemUseFinish", void.class, Object.class),
				new Call("installPortalSpawn", void.class, Object.class))));
		// Entity tracking, bridged as a pair: start without stop is a leak rather than a silence.
		CLASSES.put("net.forbric.kernel.runtime.KernelGamePlayerTrackingEvents", new Entry(Origin.COMPILED, List.of(
				new Call("installStartTracking", void.class, Object.class),
				new Call("installStopTracking", void.class, Object.class))));
		// The Neo->Forge server start/stop re-emission, which also opens MinecraftForge's login gate. Separate
		// from the tick bridge so a carrier missing one pair's types cannot take the other down with it.
		CLASSES.put("net.forbric.kernel.runtime.KernelGameServerLifecycle", new Entry(Origin.COMPILED, List.of(
				new Call("installStarting", void.class, Object.class),
				new Call("installStarted", void.class, Object.class),
				new Call("installStopping", void.class, Object.class),
				new Call("installStopped", void.class, Object.class))));
		// MinecraftForge's about-to-start, forwarded in three separately guarded pieces because its middle
		// piece always throws under the kernel. See KernelGameServerAboutToStart.
		CLASSES.put("net.forbric.kernel.runtime.KernelGameServerAboutToStart", new Entry(Origin.COMPILED, List.of(
				new Call("install", void.class, Object.class),
				new Call("forgetCurrentServer", void.class))));
		// The unified Mods screen. Named by ModsButtonRedirector as an ASM internal name rather than called, so
		// it has no Call entries — the seam is the class existing and carrying a (Screen) constructor, and a
		// missing runtime jar would otherwise surface as a NoClassDefFoundError the moment a player opens the
		// pause menu.
		CLASSES.put("net.forbric.kernel.runtime.KernelModListScreen", new Entry(Origin.COMPILED, List.of()));
		// The three config-screen registries behind that screen's Config button. Called by the client smoke,
		// which is the only thing that can prove a screen belonging to another mod actually opens.
		CLASSES.put("net.forbric.kernel.runtime.KernelModConfigScreens", new Entry(Origin.COMPILED, List.of(
				new Call("summary", String.class),
				new Call("firstWithConfig", net.forbric.api.ModCatalog.Entry.class, String.class),
				new Call("openById", Object.class, String.class, Object.class))));
		// The only MOD-bus bridge: Forge's client reload listeners into NeoForge's sorted graph.
		CLASSES.put("net.forbric.kernel.runtime.KernelGameClientReload", new Entry(Origin.COMPILED, List.of(
				new Call("install", void.class, Object.class))));
		// The MinecraftForge face of KeyMapping: the merged class kept both ecosystems' key-conflict fields and
		// only NeoForge's accessors, and only NeoForge's are read. See ForbricMergedBaseCompatTransformer.
		CLASSES.put("net.forbric.kernel.runtime.KernelForgeKeyBindings", new Entry(Origin.COMPILED, List.of(
				new Call("toNeoContext", Object.class, Object.class),
				new Call("toForgeContext", Object.class, Object.class),
				new Call("toNeoModifier", Object.class, Object.class),
				new Call("toForgeModifier", Object.class, Object.class))));
		// The ABI shim's landing site: ForgeConfigAPIPort's registrations, re-aimed at the real ConfigTracker.
		// Named by PortingLayerAbiInjector as generated bytecode, so it needs an entry here to stay honest.
		CLASSES.put("net.forbric.kernel.runtime.KernelConfigPortBridge", new Entry(Origin.COMPILED, List.of()));
		// The merged base's ParticleResources carries two same-named `providers` fields — vanilla's int-keyed one
		// and NeoForge's Identifier-keyed one — and only NeoForge's is written. This turns the other into a live
		// view of it, because fabric-api reads that field DIRECTLY.
		CLASSES.put("net.forbric.kernel.runtime.KernelParticleProviders", new Entry(Origin.COMPILED, List.of(
				new Call("intKeyedView", Object.class, java.util.Map.class))));
		// The merged base gave ChunkGenerator.featuresPerStep MinecraftForge's ClearableLazy descriptor and lost
		// vanilla's, which fabric-api's biome API writes directly. The transformer puts vanilla's back; this is
		// the one use that still needs MinecraftForge's type. See ForbricMergedBaseCompatTransformer.
		CLASSES.put("net.forbric.kernel.runtime.KernelChunkGenerator", new Entry(Origin.COMPILED, List.of(
				new Call("invalidate", void.class, java.util.function.Supplier.class))));
		// NeoForge worldgen the merge left with no driver: its data maps (nothing named DataMapLoader at all),
		// its biome/structure modifier pass, and the monster-room mob pick that the kernel used to answer by
		// neutering the whole dungeon feature. Its two entry points are called from REWRITTEN CALL SITES, so
		// their descriptors are the ones the merged base and the carrier already had — game types, not the JDK
		// types this registry's own seams use. Listed with no calls for that reason, as KernelConfigPortBridge is.
		CLASSES.put("net.forbric.kernel.runtime.KernelNeoWorldgen", new Entry(Origin.COMPILED, List.of()));
		// Called from an inserted instruction in FuelValues.burnDuration, not from boot code, so there is no
		// entry point to declare — only that the class has to be here for the redirect to land on something.
		CLASSES.put("net.forbric.kernel.runtime.KernelFuelValues", new Entry(Origin.COMPILED, List.of()));
		// Same shape as the one above: reached from a redirected instruction in BaseSpawner, not from boot code.
		CLASSES.put("net.forbric.kernel.runtime.KernelSpawnerFinalize", new Entry(Origin.COMPILED, List.of()));
		CLASSES.put("net.forbric.kernel.runtime.KernelPackFinders", new Entry(Origin.COMPILED, List.of()));
		CLASSES.put("net.forbric.kernel.runtime.KernelNeoDataMapWatch", new Entry(Origin.COMPILED, List.of(
				new Call("installDataMapWatch", void.class, Object.class))));
		// NeoForge's condition evaluator runs over every datapack element from every pack in the merged base, so
		// a Fabric mod's own condition id failed the whole registry load. This wraps ICondition.CODEC; the call
		// site is an inserted instruction in that class's <clinit>, in Codec, which no JDK type can stand for.
		CLASSES.put("net.forbric.kernel.runtime.KernelNeoConditions", new Entry(Origin.COMPILED, List.of()));
		// The THIRD evaluator, and the one nothing covered: the merged ResourceManagerRegistryLoadTask.load calls
		// MinecraftForge's ConditionCodec.wrap while its own lambda builds NeoForge's ConditionalOps, and LootPool
		// names the MinecraftForge one too. Same shape as above, same reason it carries no stand-in descriptor.
		CLASSES.put("net.forbric.kernel.runtime.KernelForgeConditions", new Entry(Origin.COMPILED, List.of()));
		// Both ecosystems collect mod entity attributes into a map of their own and the merge kept only NeoForge's
		// reader in DefaultAttributes, so a traditional MinecraftForge mod's entities had no attributes at all.
		// attributesView() is called from a REWRITTEN CALL SITE and so carries the descriptor that site had.
		CLASSES.put("net.forbric.kernel.runtime.KernelForgeAttributes", new Entry(Origin.COMPILED, List.of(
				new Call("fireForgeAttributeEvents", void.class))));
		// NeoForge refuses to NAME a client reload listener a mixin added, and throws inside Minecraft.<init>.
		// Called from a REWRITTEN CALL SITE, so it carries that site's game-typed descriptor.
		CLASSES.put("net.forbric.kernel.runtime.KernelClientReloadNames", new Entry(Origin.COMPILED, List.of()));
		// fabric-api's own two mixins for fabric:load_conditions cannot apply on the merged base, so nothing
		// evaluated them. Wrapped into ConditionalOps' one codec factory by an inserted instruction, in Codec,
		// which no JDK type can stand for.
		CLASSES.put("net.forbric.kernel.runtime.KernelFabricConditions", new Entry(Origin.COMPILED, List.of()));
		// Simultaneously a fabric-api HudElement and a NeoForge GuiLayer. It CANNOT be compiled: fabric-api is
		// not on the game source set's classpath and will never be. See KernelHudBridge.
		CLASSES.put("net.forbric.kernel.runtime.KernelHudLayer", new Entry(Origin.GENERATED, List.of()));
	}

	private KernelRuntimeClasses() {
	}

	/** Every registered game-side class, mapped to how it is delivered. */
	public static Map<String, Origin> all() {
		Map<String, Origin> out = new LinkedHashMap<>();
		CLASSES.forEach((name, entry) -> out.put(name, entry.origin()));
		return Map.copyOf(out);
	}

	/** The names that must be present in an owned jar for this kernel to be complete. */
	public static List<String> compiled() {
		return CLASSES.entrySet().stream()
				.filter(e -> e.getValue().origin() == Origin.COMPILED)
				.map(Map.Entry::getKey)
				.toList();
	}

	/** The calls the boot side makes on {@code binaryName}; empty if it is not registered. */
	public static List<Call> callsOn(String binaryName) {
		Entry entry = CLASSES.get(binaryName);
		return entry == null ? List.of() : entry.calls();
	}

	/**
	 * Loads every {@link Origin#COMPILED} class through {@code loader} and resolves every method the boot side
	 * calls on it.
	 *
	 * <p>A real load, not a resource probe, because the two failures worth separating are only distinguishable
	 * that way: a class that is in no owned jar means the boot jar was built without staged artifacts, and a class
	 * that IS there but does not define means the pipeline carrying it broke. Those have different fixes, so they
	 * get different messages.
	 *
	 * <p>Runs after the transform chain and Mixin are installed, so these classes take exactly the path every game
	 * class takes. Nothing targets them, but a self-check that skipped the pipeline would not be checking the
	 * thing that can break.
	 *
	 * <p>{@code initialize = false}: proving the class links is the point; running its static initialiser at boot
	 * is not, and for a class that one day holds game state it would be actively wrong.
	 *
	 * @return true if the whole seam is present and callable
	 */
	public static boolean verify(ForbricClassLoader loader) {
		List<String> names = compiled();
		int ok = 0;

		for (String name : names) {
			Class<?> c;
			try {
				c = Class.forName(name, false, loader);
			} catch (ClassNotFoundException | LinkageError absent) {
				if (loader.findResource(name.replace('.', '/') + ".class") == null) {
					ForbricLog.error("[Forbric/Runtime] the kernel's own game-side class %s is in no owned jar. "
							+ "This boot jar was built without the staged game artifacts, so "
							+ "forbric-kernel-runtime.jar was never packed into it, and everything needing a "
							+ "game-side kernel class will fail to link far from here. Fix: put the staged jars "
							+ "in ../forbric-loader/run/ and rebuild with ./gradlew jar", name);
				} else {
					ForbricLog.error("[Forbric/Runtime] the kernel's own game-side class %s is present in an "
							+ "owned jar but would not define: %s", name, String.valueOf(absent));
				}
				continue;
			}

			List<String> broken = unresolvable(c, callsOn(name));
			if (!broken.isEmpty()) {
				ForbricLog.error("[Forbric/Runtime] %s is there but the boot side calls methods it does not have: "
						+ "%s. Boot-side call sites name these as strings, so this is not a compile error on "
						+ "either side — it would have surfaced inside mod construction instead",
						name, String.join(", ", broken));
				continue;
			}

			// No check that c.getClassLoader() == loader. It would read well and it can never fail: this package
			// is pinned ALWAYS_GAME, so loadClass routes it to defineGameClass, which either defines it here or
			// throws — there is no path on which it comes back from somewhere else. The invariant that CAN break
			// is the pin itself, and that is a pure function of DelegationPolicy, asserted in
			// KernelRuntimeClassesTest where it can actually be made to fail.
			ok++;
		}

		if (ok == names.size()) {
			ForbricLog.info("[Forbric/Runtime] game-side kernel classes: %d/%d linked", ok, names.size());
			return true;
		}

		ForbricLog.error("[Forbric/Runtime] game-side kernel classes: %d/%d linked", ok, names.size());
		return false;
	}

	/** The calls {@code c} cannot satisfy, described the way a reader would need to fix them. */
	private static List<String> unresolvable(Class<?> c, List<Call> calls) {
		List<String> broken = new ArrayList<>();

		for (Call call : calls) {
			try {
				Method m = c.getMethod(call.name(), call.parameters());
				if (!call.returns().isAssignableFrom(m.getReturnType())) {
					broken.add(call.name() + " returns " + m.getReturnType().getSimpleName() + ", not "
							+ call.returns().getSimpleName());
				}
			} catch (NoSuchMethodException missing) {
				broken.add(call.name() + describe(call.parameters()));
			}
		}

		return broken;
	}

	private static String describe(Class<?>[] parameters) {
		List<String> names = new ArrayList<>();
		for (Class<?> p : parameters) names.add(p.getSimpleName());
		return "(" + String.join(", ", names) + ")";
	}
}
