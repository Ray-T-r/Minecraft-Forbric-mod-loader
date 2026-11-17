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

package net.forbric.kernel.runtime;

import java.util.function.Predicate;

/**
 * Which pack the data file being decoded on this thread came from, so NeoForge's condition leniency
 * ({@link KernelNeoConditions#lenient}) applies only where the file's own ecosystem tolerates a condition NeoForge cannot
 * judge.
 *
 * <p>Native NeoForge skips a data file whose {@code neoforge:conditions} name a type its registry lacks ("Couldn't parse
 * data file … Unknown registry key … neoforge:condition_codecs"). A Fabric mod's data is different: a multi-loader
 * mod's Fabric build ships the NeoForge dialect beside {@code fabric:load_conditions} and registers its own condition
 * type on the Fabric side only, and on Fabric nothing ever reads the NeoForge key. Waystones' Fabric jar (26.2.0.12) is
 * one: its lithostitched worldgen modifiers carry {@code neoforge:conditions} naming
 * {@code waystones:force_spawn_in_villages}, a datapack-registry element whose failed parse fails the whole registry load.
 * (Terralith's {@code terralith:config}, which this text cited first, sits only in {@code pack.mcmeta} overlay entries,
 * where the overlay veto decides in every pack, so gate-m8 never reaches this check.) So the leniency stays for a pack Fabric's resource loader serves for a Fabric mod, and every other pack
 * — a NeoForge mod's, vanilla's, a world datapack — gets NeoForge's own verdict (decision D69 of the MinecraftForge
 * removal, gate-m21's {@code dual_forge}).
 *
 * <p>The pack is known where the file is opened: {@code ForbricMergedBaseCompatTransformer} makes
 * {@code Resource.open()} hand its source pack here first. Most readers the base runs NeoForge's evaluator in open one file
 * and decode it on the same thread before they open the next — datapack registries
 * ({@code RegistryLoadTask$PendingRegistration.loadFromResource}), the reloadable registries, loot, advancements and
 * NeoForge's loot modifiers ({@code SimpleJsonResourceReloadListener.scanDirectory}), data maps
 * ({@code DataMapLoader.readData}), and on a client models, atlas sources and animations — so there the last pack opened
 * on a thread is the decoded file's (the census of those readers is in lane S's C5 notes, {@code reader-census.out}).
 * Recipes are the exception: {@code SimpleJsonResourceReloadListener.scanDirectoryWithModifier}, which only
 * {@code RecipeManager} calls, opens and parses EVERY recipe file first, posts NeoForge's {@code ModifyRecipeJsonsEvent}
 * over the parsed map, and only then decodes them all. The same transformer therefore records each parsed element's pack
 * ({@link #parsed}) and names it again right before that element's decode ({@link #decoding}); an element nobody parsed
 * from a file (one a NeoForge mod added in the event) has no pack and gets NeoForge's verdict. Each scan clears what it
 * recorded when it returns ({@link #endTwoPassScan}, {@link #doneReading}), so a later decode never inherits a pack. A
 * decode with no file opened on its thread gets NeoForge's verdict.
 *
 * <p>A pack with overlays is a {@code CompositePackResources} around the mod's own pack (fabric-resource-loader's
 * {@code ModPackResourcesFactory.openFull}), and that composite is the {@code Resource}'s source; the pack judged is its
 * primary ({@link #primary}).
 *
 * <p>{@code -Dforbric.neoConditions.fabricPacksOnly=off} applies the leniency to every pack again, as Forbric 0.3.x
 * (v0.3.1-beta2) and the 0.4.0-dev builds before D69 did (the red control of D69: gate-m21's {@code dual_forge} file
 * is then kept). Read per decode.
 */
public final class KernelConditionSources {
	/** {@code off}: tolerate an unregistered condition type in every pack's data files, as before D69. */
	public static final String PROPERTY = "forbric.neoConditions.fabricPacksOnly";

	/** fabric-resource-loader v1's per-mod pack, and v0's, which the v1 one still implements in older builds. */
	static final String[] FABRIC_MOD_PACKS = {
			"net.fabricmc.fabric.api.resource.v1.pack.ModPackResources", "net.fabricmc.fabric.api.resource.ModResourcePack" };

	private static final ThreadLocal<Object> READING = new ThreadLocal<>();

	/**
	 * Per two-pass scan on this thread (innermost first): each parsed element, by identity, and the pack it was read from.
	 * A stack, so a scan started from inside another's modifier keeps its own; deeper than {@link #MAX_SCANS} only after
	 * scans that ended by an exception, which are then dropped.
	 */
	private static final ThreadLocal<java.util.ArrayDeque<java.util.IdentityHashMap<Object, Object>>> SCANS =
			ThreadLocal.withInitial(java.util.ArrayDeque::new);

	static final int MAX_SCANS = 8;

	static final String COMPOSITE_PACK = "net.minecraft.server.packs.CompositePackResources";

	/** {@code CompositePackResources.primaryPackResources}, readable, for a composite class; null for any other pack. */
	private static final ClassValue<java.lang.reflect.Field> PRIMARY = new ClassValue<>() {
		@Override
		protected java.lang.reflect.Field computeValue(Class<?> type) {
			for (Class<?> c = type; c != null; c = c.getSuperclass()) {
				if (!COMPOSITE_PACK.equals(c.getName())) continue;
				try {
					java.lang.reflect.Field field = c.getDeclaredField("primaryPackResources");
					field.setAccessible(true);
					return field;
				} catch (ReflectiveOperationException | RuntimeException unreadable) {
					net.forbric.kernel.util.Caught.warn(unreadable, "[Forbric/Conditions] cannot read the primary pack of %s — a "
							+ "Fabric mod's pack with overlays is judged as not a Fabric mod's", type.getName());
					return null;
				}
			}
			return null;
		}
	};

	private static final ClassValue<Boolean> FABRIC_MOD_PACK = new ClassValue<>() {
		@Override
		protected Boolean computeValue(Class<?> type) {
			return implementsByName(type);
		}
	};

	/** Whether a pack is a Fabric mod's; substitutable so a unit test needs no fabric-api on its class path. */
	private static volatile Predicate<Object> fabricModPack = pack -> FABRIC_MOD_PACK.get(pack.getClass());

	private KernelConditionSources() {
	}

	/**
	 * Called at the head of {@code Resource.open()} with that resource's source pack: the file about to be read on this
	 * thread is that pack's. Never throws; a cheap thread-local store.
	 */
	public static void reading(Object pack) {
		READING.set(pack);
	}

	public static boolean fabricPacksOnly() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/**
	 * Whether the data file being decoded on this thread may carry a condition type NeoForge's registry lacks and still
	 * load: always with the switch off; otherwise only when it was opened from a Fabric mod's pack.
	 */
	public static boolean foreignConditionsTolerated() {
		if (!fabricPacksOnly()) return true;
		Object pack = READING.get();
		return pack != null && fabricModPack.test(primary(pack));
	}

	/**
	 * The pack a composite stands for: a {@code CompositePackResources}' primary pack (the mod's own pack, with its
	 * overlays stacked on it), followed through nested composites; any other pack is itself.
	 */
	static Object primary(Object pack) {
		for (int depth = 0; pack != null && depth < MAX_SCANS; depth++) {
			java.lang.reflect.Field field = PRIMARY.get(pack.getClass());
			if (field == null) return pack;
			try {
				Object inner = field.get(pack);
				if (inner == null) return pack;
				pack = inner;
			} catch (IllegalAccessException | RuntimeException unreadable) {
				net.forbric.kernel.util.Caught.warn(unreadable, "[Forbric/Conditions] cannot read the primary pack of %s — "
						+ "judged as not a Fabric mod's", pack.getClass().getName());
				return pack;
			}
		}
		return pack;
	}

	/**
	 * Called at the head of {@code SimpleJsonResourceReloadListener.scanDirectoryWithModifier}: a two-pass scan starts on
	 * this thread, with nothing recorded and no pack named.
	 */
	public static void beginTwoPassScan() {
		java.util.ArrayDeque<java.util.IdentityHashMap<Object, Object>> scans = SCANS.get();
		if (scans.size() >= MAX_SCANS) scans.clear();
		scans.push(new java.util.IdentityHashMap<>());
		READING.remove();
	}

	/**
	 * Called with each element the first pass parsed, right after its parse: it was read from the pack opened last on this
	 * thread, which is remembered for its decode in the second pass. Outside a two-pass scan, nothing happens.
	 */
	public static void parsed(Object json) {
		java.util.ArrayDeque<java.util.IdentityHashMap<Object, Object>> scans = SCANS.get();
		Object pack = READING.get();
		if (json != null && pack != null && !scans.isEmpty()) scans.peek().put(json, pack);
	}

	/**
	 * Called right before the second pass decodes {@code json}: the pack it was parsed from is the one being read now, or
	 * none when it was not parsed from a file of this scan (a NeoForge mod added or replaced it in
	 * {@code ModifyRecipeJsonsEvent}), which NeoForge's codec then judges.
	 */
	public static void decoding(Object json) {
		java.util.ArrayDeque<java.util.IdentityHashMap<Object, Object>> scans = SCANS.get();
		Object pack = scans.isEmpty() || json == null ? null : scans.peek().get(json);
		if (pack == null) READING.remove();
		else READING.set(pack);
	}

	/** Called where a two-pass scan returns: its record goes, and no pack stays named. */
	public static void endTwoPassScan() {
		java.util.ArrayDeque<java.util.IdentityHashMap<Object, Object>> scans = SCANS.get();
		if (!scans.isEmpty()) scans.pop();
		READING.remove();
	}

	/** Called where a one-pass scan returns: the last file's pack stays named for no later decode. */
	public static void doneReading() {
		READING.remove();
	}

	/** The id of the pack the file being decoded on this thread came from, for a log line; {@code "?"} when unknown. */
	public static String currentPackId() {
		Object pack = READING.get();
		if (pack == null) return "?";
		try {
			return String.valueOf(pack.getClass().getMethod("packId").invoke(pack));
		} catch (ReflectiveOperationException | RuntimeException unreadable) {
			net.forbric.kernel.util.Caught.expected(unreadable);
			return pack.getClass().getName();
		}
	}

	static boolean implementsByName(Class<?> type) {
		for (Class<?> c = type; c != null; c = c.getSuperclass()) {
			for (Class<?> each : c.getInterfaces()) {
				if (isFabricModPackInterface(each)) return true;
			}
		}
		return false;
	}

	private static boolean isFabricModPackInterface(Class<?> type) {
		for (String name : FABRIC_MOD_PACKS) if (name.equals(type.getName())) return true;
		for (Class<?> parent : type.getInterfaces()) if (isFabricModPackInterface(parent)) return true;
		return false;
	}

	/** Test seam: forget the pack recorded on this thread, and every scan's record. */
	static void forgetForTest() {
		READING.remove();
		SCANS.remove();
	}

	/** Test seam: the two-pass scans open on this thread. */
	static int openScansForTest() {
		return SCANS.get().size();
	}

	/** Test seam: which packs count as a Fabric mod's. */
	static void bindFabricModPacksForTest(Predicate<Object> isFabricModPack) {
		fabricModPack = isFabricModPack;
	}
}
