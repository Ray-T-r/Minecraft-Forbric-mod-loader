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

package net.forbric.api;

/**
 * The NeoForge types the kernel names by string, one row per concept.
 *
 * <p>The table held a PAIR per concept while Forbric drove both Forge-family ecosystems: MinecraftForge and NeoForge ship
 * the same concept under different names, and two literals side by side at every site invited handling one family and
 * forgetting the other. MinecraftForge's column went with {@code Ecosystem.FORGE} in Stage 4 of its removal; collapsing
 * what is left into plain constants (or inlining them) is a naming change left for later. Until then {@link #binary}
 * still takes the ecosystem, and answers {@code null} for Fabric.
 *
 * <h2>Why a table and not a prefix rule</h2>
 *
 * <p>Because there is no prefix rule. NeoForge splits across THREE roots: what descends from FML keeps
 * {@code net.neoforged.} ({@code fml.*}, {@code bus.*}, {@code api.distmarker.*}); the mod-facing game API sits
 * under {@code net.neoforged.neoforge.} ({@code registries.*}, {@code client.*}, {@code common.*}, {@code event.*});
 * and the loader SPI sits under {@code net.neoforged.neoforgespi.} ({@code language.IModInfo},
 * {@code language.IConfigurable}). So {@code fml.ModList} is {@code net.neoforged.fml.ModList}, while
 * {@code registries.GameData} is {@code net.neoforged.neoforge.registries.GameData}. A swap-the-prefix helper gets
 * these wrong, silently, and a name that does not resolve here does not throw -- it just means a transform never fires.
 *
 * <h2>What this deliberately does NOT carry</h2>
 *
 * <p>Only the NAME. Descriptors stay at the call site as data ({@code LifecycleHookInjector}'s server trigger,
 * {@code ServerModLoader.load(Z)V}, is the standing example).
 */
public enum ForeignType {
	CONFIG_SPEC("net.neoforged.fml.config.IConfigSpec"),
	CLIENT_HOOKS("net.neoforged.neoforge.client.ClientHooks"),
	CLIENT_MOD_LOADER("net.neoforged.neoforge.client.loading.ClientModLoader"),
	CLIENT_TOOLTIP_COMPONENT_MANAGER("net.neoforged.neoforge.client.gui.ClientTooltipComponentManager"),
	COLOR_RESOLVER_MANAGER("net.neoforged.neoforge.client.ColorResolverManager"),
	CONFIG_TRACKER("net.neoforged.fml.config.ConfigTracker"),
	CONFIGURABLE("net.neoforged.neoforgespi.language.IConfigurable"),
	DIST("net.neoforged.api.distmarker.Dist"),
	EVENT_HOOKS("net.neoforged.neoforge.event.EventHooks"),
	/** The base of every event NeoForge's bus dispatches. */
	EVENT("net.neoforged.bus.api.Event"),
	EVENT_BUS("net.neoforged.bus.EventBus"),
	EVENT_LISTENER("net.neoforged.bus.api.EventListener"),
	DATAPACK_NEW_REGISTRY_EVENT("net.neoforged.neoforge.registries.DataPackRegistryEvent$NewRegistry"),
	DATAPACK_REGISTRY_DATA("net.neoforged.neoforge.registries.DataPackRegistryEvent$DataPackRegistryData"),
	FLUID_INTERACTION_REGISTRY("net.neoforged.neoforge.fluids.FluidInteractionRegistry"),
	BLOCK_TINT_EVENT("net.neoforged.neoforge.client.event.RegisterColorHandlersEvent$BlockTintSources"),
	/** Whether a body breathes and how much air it loses or regains; NeoForge's base posts it. */
	LIVING_BREATHE_EVENT("net.neoforged.neoforge.event.entity.living.LivingBreatheEvent"),
	/** A body out of air about to drown, and what drowning costs it; NeoForge's base posts it. */
	LIVING_DROWN_EVENT("net.neoforged.neoforge.event.entity.living.LivingDrownEvent"),
	/** Custom skull models, posted through the kernel's client init. */
	CREATE_SKULL_MODELS_EVENT("net.neoforged.neoforge.client.event.EntityRenderersEvent$CreateSkullModels"),
	/** The earliest mod-bus phase there is: genuine FML posts it at each container the moment it is built. */
	FML_CONSTRUCT_MOD_EVENT("net.neoforged.fml.event.lifecycle.FMLConstructModEvent"),
	FML_CLIENT_SETUP_EVENT("net.neoforged.fml.event.lifecycle.FMLClientSetupEvent"),
	FML_COMMON_SETUP_EVENT("net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent"),
	/**
	 * FML's own settings ({@code fml.toml}). NeoForge's is never loaded, so the kernel gives it the one answer that is not
	 * its default under Forbric: there is no NeoForge early window.
	 */
	FML_CONFIG("net.neoforged.fml.loading.FMLConfig"),
	FML_DEDICATED_SERVER_SETUP_EVENT("net.neoforged.fml.event.lifecycle.FMLDedicatedServerSetupEvent"),
	FML_LOAD_COMPLETE_EVENT("net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent"),
	/** The cache of {@link #FML_LOADER}'s answers (stateless in NeoForge). */
	FML_ENVIRONMENT("net.neoforged.fml.loading.FMLEnvironment"),
	FML_LOADER("net.neoforged.fml.loading.FMLLoader"),
	FML_MOD_CONTAINER("net.neoforged.fml.javafmlmod.FMLModContainer"),
	FML_MOD_LOADER("net.neoforged.fml.ModLoader"),
	FML_PATHS("net.neoforged.fml.loading.FMLPaths"),
	GAME_DATA("net.neoforged.neoforge.registries.GameData"),
	/**
	 * NeoForge's own built-in translations — the table its screens read before any resource pack exists. Its loader is
	 * called from the client mod loader the kernel replaces, so the kernel calls it.
	 */
	LANGUAGE_HOOK("net.neoforged.neoforge.server.LanguageHook"),
	LOADING_MOD_LIST("net.neoforged.fml.loading.LoadingModList"),
	INTER_MOD_ENQUEUE_EVENT("net.neoforged.fml.event.lifecycle.InterModEnqueueEvent"),
	INTER_MOD_PROCESS_EVENT("net.neoforged.fml.event.lifecycle.InterModProcessEvent"),
	MOD_LIST_SCREEN("net.neoforged.neoforge.client.gui.modlist.ModListScreen"),
	MOD_BUS_EVENT("net.neoforged.fml.event.IModBusEvent"),
	MOD_CONFIG_TYPE("net.neoforged.fml.config.ModConfig$Type"),
	MOD_CONTAINER("net.neoforged.fml.ModContainer"),
	MOD_FILE("net.neoforged.fml.loading.moddiscovery.ModFile"),
	MOD_FILE_TYPE("net.neoforged.neoforgespi.locating.IModFile$Type"),
	/**
	 * NeoForge's annotation index for one mod file. The kernel seeds the loading list with files that must answer
	 * {@code getScanResult()}, or NeoForge's annotation walkers throw "Scanning of this mod file has not started yet."
	 */
	MOD_FILE_SCAN_DATA("net.neoforged.neoforgespi.language.ModFileScanData"),
	/**
	 * NeoForge's rewriter for enums a mod may add constants to: the kernel supplies the class node and NeoForge's compiled
	 * processor does the rewrite.
	 */
	RUNTIME_ENUM_EXTENDER("net.neoforged.fml.common.asm.enumextension.RuntimeEnumExtender"),
	MOD_FILE_INFO("net.neoforged.fml.loading.moddiscovery.ModFileInfo"),
	MOD_INFO("net.neoforged.fml.loading.moddiscovery.ModInfo"),
	MOD_INFO_SPI("net.neoforged.neoforgespi.language.IModInfo"),
	MOD_LIST("net.neoforged.fml.ModList"),
	MOD_LOADING_CONTEXT("net.neoforged.fml.ModLoadingContext"),
	NEW_REGISTRY_EVENT("net.neoforged.neoforge.registries.NewRegistryEvent"),
	/**
	 * The resource-condition type NeoForge dispatches datapack elements through; a condition it cannot resolve used to
	 * fail the whole registry load, and the world with it.
	 */
	ICONDITION("net.neoforged.neoforge.common.conditions.ICondition"),
	NETWORK_REGISTRY("net.neoforged.neoforge.network.registration.NetworkRegistry"),
	PRESET_EDITOR_MANAGER("net.neoforged.neoforge.client.PresetEditorManager"),
	REGISTER_EVENT("net.neoforged.neoforge.registries.RegisterEvent"),
	REGISTRY_MANAGER("net.neoforged.neoforge.registries.RegistryManager"),
	RESOURCE_PACK_LOADER("net.neoforged.neoforge.resource.ResourcePackLoader"),
	SERVER_LIFECYCLE_HOOKS("net.neoforged.neoforge.server.ServerLifecycleHooks"),
	SERVER_MOD_LOADER("net.neoforged.neoforge.server.loading.ServerModLoader"),
	SPAWN_PLACEMENT_EVENT("net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent"),
	BIOME_MODIFIER("net.neoforged.neoforge.common.world.BiomeModifier"),
	STRUCTURE_MODIFIER("net.neoforged.neoforge.common.world.StructureModifier"),
	MODIFIABLE_BIOME_INFO("net.neoforged.neoforge.common.world.ModifiableBiomeInfo"),
	MODIFIABLE_STRUCTURE_INFO("net.neoforged.neoforge.common.world.ModifiableStructureInfo"),
	MODIFIER_REGISTRY_KEYS("net.neoforged.neoforge.registries.NeoForgeRegistries$Keys"),
	MOB_SPAWN_SETTINGS_BUILDER("net.neoforged.neoforge.common.world.MobSpawnSettingsBuilder"),
	REMOVE_SPAWNS_BIOME_MODIFIER("net.neoforged.neoforge.common.world.BiomeModifiers$RemoveSpawnsBiomeModifier"),
	/** The static hook class NeoForge's patched game calls to post its events. */
	EVENT_FACTORY("net.neoforged.neoforge.event.EventHooks"),
	/**
	 * NeoForge's fluid type: ForeignFluidTypeInjector gives a fluid without one the type its tags imply.
	 */
	FLUID_TYPE("net.neoforged.neoforge.fluids.FluidType"),
	/** NeoForge's client extensions of a fluid type (its model, tint and fog colour). */
	CLIENT_FLUID_TYPE_EXTENSIONS("net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions"),
	/** NeoForge's client-command registration event, handing out the one {@code CommandDispatcher}. */
	CLIENT_COMMANDS_EVENT("net.neoforged.neoforge.client.event.RegisterClientCommandsEvent"),
	/** NeoForge's global-loot-modifier reload listener. */
	LOOT_MODIFIER_MANAGER("net.neoforged.neoforge.common.loot.LootModifierManager"),
	/**
	 * NeoForge's per-tab creative search keys. NeoForge's creative screen reads it, and so does the client-smoke census,
	 * to count the trees.
	 */
	CREATIVE_SEARCH_REGISTRY("net.neoforged.neoforge.client.CreativeModeTabSearchRegistry");

	private final String neoforge;

	ForeignType(String neoforge) {
		this.neoforge = neoforge;
	}

	/** The binary (dotted) name in {@code ecosystem}, or {@code null} for {@link Ecosystem#FABRIC}. */
	public String binary(Ecosystem ecosystem) {
		return switch (ecosystem) {
			case NEOFORGE -> neoforge;
			case FABRIC -> null;
		};
	}

	/** The internal (slash) name in {@code ecosystem}, for ASM. */
	public String internal(Ecosystem ecosystem) {
		String binary = binary(ecosystem);
		return binary == null ? null : binary.replace('.', '/');
	}

	/** Whether {@code binaryName} is this concept's NeoForge class. */
	public boolean matches(String binaryName) {
		return neoforge.equals(binaryName);
	}
}
