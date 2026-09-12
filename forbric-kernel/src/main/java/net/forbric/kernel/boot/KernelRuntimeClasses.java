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
				new Call("modInfo", Object.class, String.class, Path.class))));
		// The traditional-Forge loading context: BusGroup + FMLModContainer + FMLJavaModLoadingContext, and the
		// IModInfo they carry. A separate factory from KernelContainers because traditional Forge differs from
		// NeoForge at every joint the kernel touches. See KernelForgeModContext.
		CLASSES.put("net.forbric.kernel.runtime.KernelForgeContainers", new Entry(Origin.COMPILED, List.of(
				new Call("create", KernelForgeModContext.Handle.class, String.class),
				new Call("setActiveContainer", void.class, Object.class),
				new Call("constructMod", Object.class, String.class, KernelForgeModContext.Handle.class),
				new Call("startup", void.class, Object.class))));
		// Materialises the kernel's own annotation scan into NeoForge's ModFileScanData. The scan itself is
		// boot-side bytecode work; only this last step needs game types. See ModFileScanner.
		CLASSES.put("net.forbric.kernel.runtime.KernelScanData", new Entry(Origin.COMPILED, List.of(
				new Call("build", Object.class, List.class, List.class))));
		// The Neo->Forge server-tick re-emission. Two entries rather than one taking the kind, because the two
		// MinecraftForge hooks share a descriptor and a crossed pairing would compile. See KernelGameTickEvents.
		CLASSES.put("net.forbric.kernel.runtime.KernelGameTickEvents", new Entry(Origin.COMPILED, List.of(
				new Call("installPre", void.class, Object.class),
				new Call("installPost", void.class, Object.class))));
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
