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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.forbric.api.CompatibilityFinding;
import net.forbric.api.Side;

/**
 * When and how the kernel's datapack-registry declaration (step 3a) may touch the two classes every world load
 * goes through: {@code RegistryDataLoader} and NeoForge's {@code DataPackRegistriesHooks}.
 *
 * <p>Both are initialised by that declaration, and their initialisers run mod code: a Fabric mod's
 * {@code @Inject} at {@code RegistryDataLoader.<clinit>} TAIL is the documented way to add a worldgen registry, and
 * WorldWeaver's runs every {@code wover.datapack.registry} entrypoint from there. On native Fabric that happens at
 * world load, after every main entrypoint. The kernel forced it from {@code Main.main} on a client, before any
 * Fabric main had run and with {@code minecraft:root} frozen — so wover-biome's codec registry, which its own main
 * would have created, was created from the initialiser instead and threw "Registry is already frozen". The
 * {@code ExceptionInInitializerError} marks both classes erroneous for the rest of the process, and every
 * {@code WorldLoader.load} and multiplayer join then dies on "Could not initialize class". This class holds the
 * decisions that keep that from happening, and the report for when it happens anyway.
 */
final class DatapackRegistryDeclaration {
	/**
	 * {@code -Dforbric.datapackDeclarationAfterFabric=off} declares from {@code Main.main} again on a client. Only
	 * the placement: the reconcile ({@link #RECONCILE_SWITCH}) and the poisoned-loader finding stay on, so on the
	 * sweep pack's client the old placement now ends in that CONFIRMED, required finding -- a strict launch stops
	 * with exit 78 where it used to reach a title screen that could load no world.
	 */
	static final String DEFERRAL_SWITCH = "forbric.datapackDeclarationAfterFabric";
	/** {@code -Dforbric.datapackRegistryReconcile=off} leaves NeoForge's list as its initialiser copied it. */
	static final String RECONCILE_SWITCH = "forbric.datapackRegistryReconcile";

	static final String LOADER = "net.minecraft.resources.RegistryDataLoader";
	static final String HOOKS = "net.neoforged.neoforge.registries.DataPackRegistriesHooks";

	/**
	 * A Mixin-merged injector method: {@code handler$cgo000$wover-core$wover_init}. Mixin names the merged method
	 * {@code <prefix>$<class id><method id>$<mod id>$<name>}, so the frame is the one place a stack names the mod
	 * whose injector was running. Mixin 0.8.7's {@code MethodMapper}: the class id is the mixin's index in hex with
	 * each digit shifted into letters and padded to at least three with {@code z} ({@code zza}, and longer past the
	 * 4096th mixin); the method id is {@code %03x}, a hex counter per name and descriptor. So
	 * {@code handler$zza00c$wover-core$wover_init} is the same mod's frame, once ten or more handlers share that name
	 * and descriptor -- common for one like {@code onInit(CallbackInfo)}. The old {@code [a-z]{3}\d{3}} matched
	 * neither a hex method id nor a longer class id, and the finding then named no mod.
	 */
	private static final Pattern MERGED_INJECTOR =
			Pattern.compile("^[a-zA-Z]+\\$[a-z]{3,}[0-9a-f]{3,}\\$([^$]+)\\$.+$");

	private DatapackRegistryDeclaration() {
	}

	/**
	 * Whether a client's step 3a waits for the Fabric entrypoints, i.e. runs at the end of
	 * {@code KernelLifecycle.onClientEntrypoints} instead of from {@code Main.main}.
	 *
	 * <p>It waits exactly when those entrypoints run in {@code Minecraft.<init>} — the default since the client's
	 * {@code main} entrypoints moved there, and the move that left step 3a stranded in front of them. Then the
	 * declaration runs where native Fabric first initialises {@code RegistryDataLoader} — after every main and
	 * client entrypoint, with the root frozen again — which is also where it already runs on the dedicated server
	 * and where the server's log proves it clean (all six wover registries, 54 in NeoForge's list). It is still
	 * before NeoForge's client setup, whose {@code RegisterDataMapTypesEvent} reads the declared list.
	 *
	 * <p>A dedicated server, a client with {@code -Dforbric.fabricMainInConstructor=off} and a pack without Fabric
	 * mods keep step 3a where it was: the mains already precede it, or there are none.
	 */
	static boolean waitsForFabric(Side side, boolean fabricActive, boolean mainsInConstructor) {
		if (!side.isClient() || !fabricActive || !mainsInConstructor) return false;
		return !"off".equalsIgnoreCase(System.getProperty(DEFERRAL_SWITCH, "on"));
	}

	/**
	 * Puts back into NeoForge's list what a {@code RegistryDataLoader.<clinit>} TAIL injector added after NeoForge
	 * had already copied it. Returns what it declared.
	 *
	 * <p>The two initialisers are circular in the merged base, and which one runs first decides what NeoForge sees.
	 * {@code DataPackRegistriesHooks.<clinit>} copies {@code RegistryDataLoader.WORLDGEN_REGISTRIES}, and NeoForge
	 * patched {@code RegistryDataLoader.<clinit>} to call {@code DataPackRegistriesHooks.grabNetworkableRegistries}
	 * just before it returns. Hooks first — step 3a's order, and native NeoForge's — and the copy happens after the
	 * whole loader initialiser, TAIL injectors included. Loader first, and the copy happens nested inside that call,
	 * BEFORE the TAIL injectors run: NeoForge's list is vanilla's 46, the merged {@code WorldLoader} loads only that
	 * list, and wover's six registries are missing at world load without a word. Nothing the kernel controls picks
	 * the order: fabric-api's {@code DynamicRegistriesImpl.<clinit>} reads {@code WORLDGEN_REGISTRIES}, so a mod
	 * that calls {@code DynamicRegistries} from its main initialises the loader first (PuzzlesLib and Balm both
	 * can), and so does anything that reads {@code SYNCHRONIZED_REGISTRIES}.
	 *
	 * <p>Hence a reconcile that does not care about the order: whatever the loader's final list holds and NeoForge's
	 * lacks is declared to NeoForge by key, the SAME entry object, so its validator and builder hooks travel with it
	 * exactly as the Hooks-first copy would have carried them. Declaring by key means an entry the injector
	 * REPLACED in place cannot be fixed here; {@code replaced} collects those so the caller can name them.
	 */
	static List<Object> reconcile(List<?> loaderList, List<?> neoList, Function<Object, Object> keyOf,
			Consumer<Object> declare, List<Object> replaced) {
		if ("off".equalsIgnoreCase(System.getProperty(RECONCILE_SWITCH, "on"))) return List.of();

		Set<Object> neoKeys = new HashSet<>();
		Set<Object> neoEntries = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Object entry : neoList) {
			neoKeys.add(keyOf.apply(entry));
			neoEntries.add(entry);
		}
		List<Object> declared = new ArrayList<>();
		for (Object entry : loaderList) {
			Object key = keyOf.apply(entry);
			if (neoKeys.add(key)) {
				declare.accept(entry);
				declared.add(entry);
			} else if (replaced != null && !neoEntries.contains(entry)) {
				replaced.add(key);
			}
		}
		return declared;
	}

	/**
	 * The finding for a declaration that failed because one of the two classes failed to initialise, or null
	 * when the failure was anything else.
	 *
	 * <p>Worth its own report because the WARN it replaces blamed the wrong thing and understated the cost: it
	 * said a mod with its own worldgen registry would fail at world load. What a poisoned
	 * {@code RegistryDataLoader} costs is every world — none can be created, loaded or joined for the rest of the
	 * session — plus NeoForge's data maps, whose registration reads the same class. And the mods it breaks
	 * (wover-biome and wover-generator, whose own mains then fail on {@code NoClassDefFoundError}) are reported as
	 * "its main entrypoint threw", which points at them rather than at the initialiser.
	 */
	static CompatibilityFinding poisonedLoader(Throwable failure) {
		String poisoned = null;
		String injectorMod = null;
		String injectorFrame = null;
		Throwable root = failure;
		Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Throwable t = failure; t != null && seen.add(t); t = t.getCause()) {
			root = t;
			if (t instanceof NoClassDefFoundError && t.getMessage() != null) {
				for (String cls : List.of(LOADER, HOOKS)) {
					if (poisoned == null && couldNotInitialize(t.getMessage(), cls)) poisoned = cls;
				}
			}
			for (StackTraceElement frame : t.getStackTrace()) {
				String cls = frame.getClassName();
				if (!LOADER.equals(cls) && !HOOKS.equals(cls)) continue;
				if ("<clinit>".equals(frame.getMethodName())) {
					// The innermost initialiser is the one the error escaped first; keep the loader over the hooks.
					if (poisoned == null || LOADER.equals(cls)) poisoned = cls;
				} else if (injectorMod == null) {
					Matcher m = MERGED_INJECTOR.matcher(frame.getMethodName());
					if (m.matches()) {
						injectorMod = m.group(1);
						injectorFrame = simpleName(cls) + "." + frame.getMethodName();
					}
				}
			}
		}
		if (poisoned == null) return null;

		String by = injectorMod == null ? "" : ", while a mixin from " + injectorMod + " ran in it";
		List<String> evidence = new ArrayList<>();
		evidence.add("class=" + poisoned);
		if (injectorFrame != null) evidence.add("injector=" + injectorFrame);
		evidence.add("cause=" + root);
		return new CompatibilityFinding("datapack-loader-initialization", "forbric", "World loading",
				"KernelLifecycle datapack-registry declaration", CompatibilityFinding.Confidence.CONFIRMED, true,
				"The world loader failed to initialise (" + simpleName(poisoned) + by + "): no world can be "
						+ "created, loaded or joined this session, and datapack registries and NeoForge data maps "
						+ "are unavailable",
				evidence);
	}

	/**
	 * Whether the JVM's "Could not initialize class" message names {@code cls} itself: not a nested class of it such
	 * as {@code RegistryDataLoader$RegistryData}, which a plain prefix match took for the loader.
	 */
	static boolean couldNotInitialize(String message, String cls) {
		return Pattern.compile("Could not initialize class " + Pattern.quote(cls) + "(?![\\w$])").matcher(message).find();
	}

	private static String simpleName(String binary) {
		return binary.substring(binary.lastIndexOf('.') + 1);
	}
}
