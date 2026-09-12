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
 * The Forge-family types the kernel names by string, as ONE row per concept instead of two literals per call site.
 *
 * <p>The two Forge-family ecosystems ship the same concept under different names, and the kernel has to name both
 * because it drives both. Written inline that is two string constants sitting next to each other at every site --
 * 25 such concepts across transform/, boot/ and interop/ -- and each pair is an invitation to handle one family and forget
 * the other. {@code ClientPackHookInjector} and {@code ForeignModPresenceInjector} both carry exactly that shape.
 *
 * <h2>Why a table of pairs and not a prefix rule</h2>
 *
 * <p>Because there is no prefix rule. NeoForge splits across THREE roots: what descends from FML keeps
 * {@code net.neoforged.} ({@code fml.*}, {@code bus.*}, {@code api.distmarker.*}); the mod-facing game API sits
 * under {@code net.neoforged.neoforge.} ({@code registries.*}, {@code client.*}, {@code common.*}, {@code event.*});
 * and the loader SPI the two families share by shape sits under {@code net.neoforged.neoforgespi.}
 * ({@code language.IModInfo}, {@code language.IConfigurable}) against MinecraftForge's {@code forgespi.*}.
 * MinecraftForge has one root for all three. So {@code fml.ModList} pairs
 * {@code net.minecraftforge.fml.ModList} with {@code net.neoforged.fml.ModList}, while {@code registries.GameData}
 * pairs {@code net.minecraftforge.registries.GameData} with {@code net.neoforged.neoforge.registries.GameData}.
 * The package path does not have to match either: {@code NetworkRegistry} is {@code network.NetworkRegistry} on
 * one side and {@code network.registration.NetworkRegistry} on the other.
 * A swap-the-prefix helper gets these wrong, silently, and a name that does not resolve here does not
 * throw -- it just means a transform never fires.
 *
 * <h2>What this deliberately does NOT unify</h2>
 *
 * <p>Only the NAME. Where the two families' members differ in shape, that difference stays at the call site as
 * data. {@code LifecycleHookInjector}'s two server triggers are the standing example: same concept
 * ({@code ServerModLoader.load}), but NeoForge's is {@code (Z)V} and MinecraftForge's is {@code ()V}, and they
 * redirect to different kernel hooks. Folding descriptors in here would be the same mistake that cost
 * {@code KernelEventSubscribers} three simultaneous bugs -- a hub carries per-family divergence as data, it does
 * not average it away.
 */
public enum ForeignType {
	CLIENT_MOD_LOADER("net.minecraftforge.client.loading.ClientModLoader",
			"net.neoforged.neoforge.client.loading.ClientModLoader"),
	CONFIG_TRACKER("net.minecraftforge.fml.config.ConfigTracker",
			"net.neoforged.fml.config.ConfigTracker"),
	CONFIGURABLE("net.minecraftforge.forgespi.language.IConfigurable",
			"net.neoforged.neoforgespi.language.IConfigurable"),
	DIST("net.minecraftforge.api.distmarker.Dist",
			"net.neoforged.api.distmarker.Dist"),
	FML_LOADER("net.minecraftforge.fml.loading.FMLLoader",
			"net.neoforged.fml.loading.FMLLoader"),
	FML_MOD_CONTAINER("net.minecraftforge.fml.javafmlmod.FMLModContainer",
			"net.neoforged.fml.javafmlmod.FMLModContainer"),
	FML_PATHS("net.minecraftforge.fml.loading.FMLPaths",
			"net.neoforged.fml.loading.FMLPaths"),
	GAME_DATA("net.minecraftforge.registries.GameData",
			"net.neoforged.neoforge.registries.GameData"),
	KEY_MAPPING_LOOKUP("net.minecraftforge.client.settings.KeyMappingLookup",
			"net.neoforged.neoforge.client.settings.KeyMappingLookup"),
	LOADING_MOD_LIST("net.minecraftforge.fml.loading.LoadingModList",
			"net.neoforged.fml.loading.LoadingModList"),
	MOD_BUS_EVENT("net.minecraftforge.fml.event.IModBusEvent",
			"net.neoforged.fml.event.IModBusEvent"),
	MOD_CONFIG_TYPE("net.minecraftforge.fml.config.ModConfig$Type",
			"net.neoforged.fml.config.ModConfig$Type"),
	MOD_CONTAINER("net.minecraftforge.fml.ModContainer",
			"net.neoforged.fml.ModContainer"),
	MOD_FILE("net.minecraftforge.fml.loading.moddiscovery.ModFile",
			"net.neoforged.fml.loading.moddiscovery.ModFile"),
	MOD_FILE_INFO("net.minecraftforge.fml.loading.moddiscovery.ModFileInfo",
			"net.neoforged.fml.loading.moddiscovery.ModFileInfo"),
	MOD_INFO("net.minecraftforge.fml.loading.moddiscovery.ModInfo",
			"net.neoforged.fml.loading.moddiscovery.ModInfo"),
	MOD_INFO_SPI("net.minecraftforge.forgespi.language.IModInfo",
			"net.neoforged.neoforgespi.language.IModInfo"),
	MOD_LIST("net.minecraftforge.fml.ModList",
			"net.neoforged.fml.ModList"),
	MOD_LOADING_CONTEXT("net.minecraftforge.fml.ModLoadingContext",
			"net.neoforged.fml.ModLoadingContext"),
	NEW_REGISTRY_EVENT("net.minecraftforge.registries.NewRegistryEvent",
			"net.neoforged.neoforge.registries.NewRegistryEvent"),
	NETWORK_REGISTRY("net.minecraftforge.network.NetworkRegistry",
			"net.neoforged.neoforge.network.registration.NetworkRegistry"),
	REGISTER_EVENT("net.minecraftforge.registries.RegisterEvent",
			"net.neoforged.neoforge.registries.RegisterEvent"),
	REGISTRY_MANAGER("net.minecraftforge.registries.RegistryManager",
			"net.neoforged.neoforge.registries.RegistryManager"),
	SERVER_LIFECYCLE_HOOKS("net.minecraftforge.server.ServerLifecycleHooks",
			"net.neoforged.neoforge.server.ServerLifecycleHooks"),
	SERVER_MOD_LOADER("net.minecraftforge.server.loading.ServerModLoader",
			"net.neoforged.neoforge.server.loading.ServerModLoader");

	private final String forge;
	private final String neoforge;

	ForeignType(String forge, String neoforge) {
		this.forge = forge;
		this.neoforge = neoforge;
	}

	/** The binary (dotted) name in {@code ecosystem}, or {@code null} for {@link Ecosystem#FABRIC}. */
	public String binary(Ecosystem ecosystem) {
		return switch (ecosystem) {
			case FORGE -> forge;
			case NEOFORGE -> neoforge;
			case FABRIC -> null;
		};
	}

	/** The internal (slash) name in {@code ecosystem}, for ASM. */
	public String internal(Ecosystem ecosystem) {
		String binary = binary(ecosystem);
		return binary == null ? null : binary.replace('.', '/');
	}

	/** Whether {@code binaryName} is this concept in EITHER Forge-family ecosystem. */
	public boolean matches(String binaryName) {
		return forge.equals(binaryName) || neoforge.equals(binaryName);
	}
}
