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

import java.util.Locale;

/**
 * Which physical side this process is: the one side vocabulary for all three ecosystems.
 *
 * <p>The kernel had four spellings of this. {@code net.fabricmc.api.EnvType} stood in as the kernel's own notion;
 * sixteen methods took a bare {@code boolean client}; the string {@code client ? "CLIENT" : "DEDICATED_SERVER"}
 * was written out six times in four files; and {@code KernelBoot.Side} carried the boot-time version of it. None
 * of them is wrong on its own. What they cost is that nothing connects them, so a site can disagree with the
 * others and compile — which two of them did:
 *
 * <ul>
 *   <li>{@code PassiveSeeder.seedForgeFmlLoader} hardcodes {@code DEDICATED_SERVER}, so on a Forbric CLIENT,
 *       traditional MinecraftForge believes it is a dedicated server.</li>
 *   <li>{@code KernelBoot} passed {@code side == Side.SERVER} as FML's {@code production} flag, which is the
 *       dev-vs-shipped axis and has nothing to do with which side is running — so every client boot announced
 *       {@code production=false}.</li>
 * </ul>
 *
 * <p>Both were expressible because a side had been taken apart into loose booleans and strings before it reached
 * the code that needed it. This type exists so it arrives whole.
 *
 * <h2>The constant names are the Forge families' own</h2>
 *
 * <p>{@link #distName()} is the name of the matching constant in {@code Dist} — and it is the SAME name in
 * MinecraftForge's {@code net.minecraftforge.api.distmarker.Dist} and NeoForge's
 * {@code net.neoforged.api.distmarker.Dist} (see {@link ForeignType#DIST}; the class names differ, the constants
 * do not). Fabric's {@code EnvType} spells the server side {@code SERVER} instead, which is why the conversion
 * to and from it stays on the kernel side rather than being folded in here — this package names no ecosystem's
 * types.
 */
public enum Side {
	CLIENT,
	DEDICATED_SERVER;

	/** True on the physical client. The integrated server runs here too — it is not {@link #DEDICATED_SERVER}. */
	public boolean isClient() {
		return this == CLIENT;
	}

	/** The name of the corresponding {@code Dist} constant, in either Forge family. */
	public String distName() {
		return name();
	}

	/**
	 * Parses a {@code Dist} or {@code EnvType} constant name.
	 *
	 * <p>Accepts Fabric's {@code SERVER} as well as the Forge families' {@code DEDICATED_SERVER}, because this is
	 * the seam where the two spellings meet and a caller should not have to know which one it is holding.
	 * Anything unrecognised is {@code null} — an unreadable side must not silently become a real one, since every
	 * use of it branches on which half of the game is running.
	 */
	public static Side parse(String raw) {
		if (raw == null) return null;
		String value = raw.trim().toUpperCase(Locale.ROOT);
		if (value.equals("CLIENT")) return CLIENT;
		if (value.equals("DEDICATED_SERVER") || value.equals("SERVER")) return DEDICATED_SERVER;
		return null;
	}
}
