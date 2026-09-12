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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.util.ForbricLog;

/**
 * Every {@code net.forbric.kernel.runtime.} class the kernel names, and how each one comes into being.
 *
 * <h2>Why a registry, and why it is checked at boot</h2>
 *
 * <p>The boot side cannot name a game-side class as a TYPE — that is the whole reason the game side exists — so
 * it names them as STRINGS: a {@code Class.forName} argument here, an ASM internal name there. A string that
 * has drifted from what is actually delivered produces no compiler error and no warning. It produces a
 * {@code ClassNotFoundException} at the moment of USE, which for these classes is deep inside a mod's
 * construction, hours of log away from the build that dropped them.
 *
 * <p>The specific failure this exists to prevent: a boot jar built on a machine with no staged artifacts carries
 * no {@code forbric-kernel-runtime.jar} at all. It launches, boots, loads mods, and then dies on the first
 * game-side kernel class with a message that names the class and says nothing about the build. Checking the
 * whole registry once, immediately after the loader is built, turns that into one line at the top of the log
 * that names the cause and the fix.
 *
 * <p>{@link Origin#GENERATED} entries are deliberately NOT checked for presence: they are emitted by a boot-side
 * {@code ClassWriter} on demand and are correctly absent from the jar. They are listed anyway so this file is
 * the one place that answers "what lives on the game side", and so {@code KernelRuntimeClassesTest} can hold the
 * source tree to it — a registry nobody is forced to update is a registry that goes stale silently, which is the
 * same defect one level up.
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

	/** Binary name → how it is delivered. Insertion-ordered so the failure message reads in a stable order. */
	private static final Map<String, Origin> CLASSES = new LinkedHashMap<>();

	static {
		// The full-power game-side lookup Forge's EventBus needs to spin listener lambdas.
		CLASSES.put("net.forbric.kernel.runtime.KernelGameLookupHelper", Origin.COMPILED);
		// A concrete net.neoforged.fml.ModContainer subclass. Generated today; every type it touches is public,
		// so it is a candidate to become COMPILED — but it is the FALLBACK path (KernelModContainerFactory
		// prefers a genuine FMLModContainer), so moving it would change little and be exercised rarely.
		CLASSES.put("net.forbric.kernel.runtime.KernelModContainer", Origin.GENERATED);
		// Simultaneously a fabric-api HudElement and a NeoForge GuiLayer. It CANNOT be compiled: fabric-api is
		// not on the game source set's classpath and will never be. See KernelHudBridge.
		CLASSES.put("net.forbric.kernel.runtime.KernelHudLayer", Origin.GENERATED);
	}

	private KernelRuntimeClasses() {
	}

	/** The registry, for tests and diagnostics. */
	public static Map<String, Origin> all() {
		return Map.copyOf(CLASSES);
	}

	/** The names that must be present in an owned jar for this kernel to be complete. */
	public static List<String> compiled() {
		return CLASSES.entrySet().stream()
				.filter(e -> e.getValue() == Origin.COMPILED)
				.map(Map.Entry::getKey)
				.toList();
	}

	/**
	 * Loads every {@link Origin#COMPILED} class through {@code loader} and checks it landed on the game side.
	 *
	 * <p>A real load, not a resource probe, because the two failures worth separating are only distinguishable
	 * that way: a class that is not in any owned jar means the boot jar was built without staged artifacts, and
	 * a class that IS there but does not define means the pipeline that carries it broke. Those have different
	 * fixes, so they get different messages.
	 *
	 * <p>Runs after the transform chain and Mixin are installed, so these classes take exactly the path every
	 * game class takes. Nothing targets them, but a self-check that skipped the pipeline would not be checking
	 * the thing that can break.
	 *
	 * <p>{@code initialize = false}: proving the class links is the point; running its static initialiser at
	 * boot is not, and for a class that one day holds game state it would be actively wrong.
	 *
	 * @return true if every compiled game-side class is present and game-side
	 */
	public static boolean verify(ForbricClassLoader loader) {
		List<String> names = compiled();
		int ok = 0;

		for (String name : names) {
			try {
				Class.forName(name, false, loader);
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

			// No check that c.getClassLoader() == loader. It would read well and it can never fail: this
			// package is pinned ALWAYS_GAME, so loadClass routes it to defineGameClass, which either defines it
			// here or throws — there is no path on which it comes back from somewhere else. The invariant that
			// CAN break is the pin itself, and that is a pure function of DelegationPolicy, asserted in
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
}
