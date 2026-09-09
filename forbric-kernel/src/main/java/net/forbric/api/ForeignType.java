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
 * 22 such concepts across transform/ and boot/ -- and each pair is an invitation to handle one family and forget
 * the other. {@code ClientPackHookInjector} and {@code ForeignModPresenceInjector} both carry exactly that shape.
 *
 * <h2>Why a table of pairs and not a prefix rule</h2>
 *
 * <p>Because there is no prefix rule. NeoForge splits across TWO roots: what descends from FML keeps
 * {@code net.neoforged.} ({@code fml.*}, {@code bus.*}, {@code api.distmarker.*}) while the mod-facing game API sits
 * under {@code net.neoforged.neoforge.} ({@code registries.*}, {@code client.*}, {@code common.*}, {@code event.*}).
 * MinecraftForge has one root for both. So {@code fml.ModList} pairs
 * {@code net.minecraftforge.fml.ModList} with {@code net.neoforged.fml.ModList}, while {@code registries.GameData}
 * pairs {@code net.minecraftforge.registries.GameData} with {@code net.neoforged.neoforge.registries.GameData}.
 * A swap-the-prefix helper gets the second one wrong, silently, and a name that does not resolve here does not
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
	MOD_LIST("net.minecraftforge.fml.ModList", "net.neoforged.fml.ModList"),
	MOD_CONTAINER("net.minecraftforge.fml.ModContainer", "net.neoforged.fml.ModContainer"),
	CLIENT_MOD_LOADER("net.minecraftforge.client.loading.ClientModLoader",
			"net.neoforged.neoforge.client.loading.ClientModLoader"),
	SERVER_MOD_LOADER("net.minecraftforge.server.loading.ServerModLoader",
			"net.neoforged.neoforge.server.loading.ServerModLoader"),
	GAME_DATA("net.minecraftforge.registries.GameData", "net.neoforged.neoforge.registries.GameData");

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
