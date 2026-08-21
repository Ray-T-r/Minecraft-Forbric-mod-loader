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

package net.forbric.kernel.classloading;

/**
 * The explicit boot↔game delegation table for {@link ForbricClassLoader}.
 *
 * <p>This restates the old "Knot classloader split" law for the sovereign kernel: any class that links against
 * game / ecosystem types must be defined by the one transforming class loader that defines those types; anything
 * shared with the boot side (the transform machinery itself, the Mixin library, logging, the kernel's own boot
 * classes) must be parent-loaded so exactly one copy exists.
 *
 * <p>Policy (checked in order):
 * <ol>
 *   <li><b>ALWAYS_PARENT</b> — never define here even if the bytes are reachable: the JDK, the ASM + Mixin
 *       libraries the transformer itself runs on, log4j/slf4j (one logging instance shared by game and kernel),
 *       NightConfig (a carrier bundles an old unshaded copy that would otherwise win child-first), the vendored
 *       Fabric mod-facing API, and the kernel's BOOT packages (everything under {@code net.forbric.kernel}
 *       EXCEPT {@code net.forbric.kernel.runtime}, which is game-side).</li>
 *   <li><b>ALWAYS_GAME</b> — always define here (with transforms), because these are the game + ecosystems and
 *       the kernel's game-side runtime: {@code net.minecraft}, {@code com.mojang.blaze3d}, {@code net.minecraftforge},
 *       {@code net.neoforged}, {@code net.fabricmc.fabric}, {@code net.forbric.kernel.runtime}, and MixinExtras'
 *       generated-class package.</li>
 *   <li><b>otherwise</b> — child-first: define here iff the class is present in one of this loader's own jars,
 *       else delegate to the parent (this catches game/mod classes without hardcoding every package, and lets MC
 *       libraries like DataFixerUpper / Brigadier / netty / guava stay parent-loaded).</li>
 * </ol>
 */
public final class DelegationPolicy {
	private DelegationPolicy() {
	}

	private static final String[] ALWAYS_PARENT = {
			"java.", "jdk.", "sun.", "javax.", "org.w3c.", "org.xml.",
			"org.objectweb.asm.",
			"org.spongepowered.asm.", "org.spongepowered.include.",
			"org.apache.logging.log4j.", "org.slf4j.",
			// NightConfig, and the reason it is pinned. The MinecraftForge runtime carrier bundles a copy at the
			// UNSHADED package name, and in that copy StampedConfig.valueMap() is the 3.7.4 stub that throws
			// "StampedConfig does not support valueMap() yet." Because the carrier is one of this loader's own
			// jars, child-first handed every NightConfig class to that copy — shadowing the working 3.8.x on the
			// parent classpath — while the merged base's NeoForge half was compiled against 3.8.x, where
			// valueMap() returns a real view. Any config read that descends a dotted path into a sub-config then
			// died: a mod's config file is stored as nested tables, so `get("mixin.perf.surface")` has to descend.
			// zfastnoise is where this surfaced (its mixin plugin reads config in its constructor, so the plugin
			// could not even be built), but nothing about it is zfastnoise-specific — it was one throw away from
			// any mod that keeps nested config. Pin the package so exactly one NightConfig exists and it is the
			// one we chose, not whichever carrier happens to shade it.
			"com.electronwill.nightconfig.",
			"net.fabricmc.api.",
			"net.fabricmc.loader.api.",
			"net.forbric.kernel.boot.",
			"net.forbric.kernel.classloading.",
			"net.forbric.kernel.transform.",
			"net.forbric.kernel.mixin.",
			"net.forbric.kernel.access.",
			"net.forbric.kernel.mapping.",
			"net.forbric.kernel.metadata.",
			"net.forbric.kernel.discovery.",
			"net.forbric.kernel.fabric.",
			"net.forbric.kernel.util.",
			"net.forbric.kernel.api.",
	};

	private static final String[] ALWAYS_GAME = {
			"net.minecraft.",
			"com.mojang.blaze3d.",
			"net.minecraftforge.",
			"net.neoforged.",
			"net.fabricmc.fabric.",
			"net.forbric.kernel.runtime.",
			"com.llamalad7.mixinextras.",
			// Mixin GENERATES these on demand (e.g. the @Args argument classes) and they must be defined by the
			// loader that defines their targets — even though the rest of org.spongepowered.asm is parent-loaded.
			"org.spongepowered.asm.synthetic.",
	};

	/**
	 * True if {@code className} must be loaded by the parent, never defined by the transforming loader.
	 *
	 * <p>{@link #alwaysGame} wins: {@code org.spongepowered.asm.synthetic.} sits inside the parent-loaded
	 * {@code org.spongepowered.asm.} but must be game-side, and this keeps that the only place the exception
	 * is expressed.
	 */
	public static boolean alwaysParent(String className) {
		if (alwaysGame(className)) return false;

		return startsWithAny(className, ALWAYS_PARENT);
	}

	/** True if {@code className} must always be defined (and transformed) by the game-side transforming loader. */
	public static boolean alwaysGame(String className) {
		return startsWithAny(className, ALWAYS_GAME);
	}

	private static boolean startsWithAny(String s, String[] prefixes) {
		for (String p : prefixes) {
			if (s.startsWith(p)) return true;
		}
		return false;
	}
}
