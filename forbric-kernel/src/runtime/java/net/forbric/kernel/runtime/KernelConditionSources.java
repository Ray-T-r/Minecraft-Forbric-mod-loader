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
 * type on the Fabric side only (Terralith's {@code terralith:config}, gate-m8), and on Fabric nothing ever reads the
 * NeoForge key. So the leniency stays for a pack Fabric's resource loader serves for a Fabric mod, and every other pack
 * — a NeoForge mod's, vanilla's, a world datapack — gets NeoForge's own verdict (decision D69 of the MinecraftForge
 * removal, gate-m21's {@code dual_forge}).
 *
 * <p>The pack is known where the file is opened: {@code ForbricMergedBaseCompatTransformer} makes
 * {@code Resource.open()} hand its source pack here first. Every reader the base runs NeoForge's evaluator in —
 * datapack registries ({@code RegistryLoadTask$PendingRegistration.loadFromResource}), the reloadable registries,
 * recipes, loot and data maps ({@code SimpleJsonResourceReloadListener.scanDirectory} and NeoForge's loaders) — opens
 * the file and decodes it on the same thread before it opens the next, so the last pack opened on a thread is the
 * decoded file's. A decode with no file opened on its thread gets NeoForge's verdict.
 *
 * <p>{@code -Dforbric.neoConditions.fabricPacksOnly=off} applies the leniency to every pack again, as Forbric 0.4 did
 * (the red control of D69: gate-m21's {@code dual_forge} file is then kept). Read per decode.
 */
public final class KernelConditionSources {
	/** {@code off}: tolerate an unregistered condition type in every pack's data files, as before D69. */
	public static final String PROPERTY = "forbric.neoConditions.fabricPacksOnly";

	/** fabric-resource-loader v1's per-mod pack, and v0's, which the v1 one still implements in older builds. */
	static final String[] FABRIC_MOD_PACKS = {
			"net.fabricmc.fabric.api.resource.v1.pack.ModPackResources", "net.fabricmc.fabric.api.resource.ModResourcePack" };

	private static final ThreadLocal<Object> READING = new ThreadLocal<>();

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
		return pack != null && fabricModPack.test(pack);
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

	/** Test seam: forget the pack recorded on this thread. */
	static void forgetForTest() {
		READING.remove();
	}

	/** Test seam: which packs count as a Fabric mod's. */
	static void bindFabricModPacksForTest(Predicate<Object> isFabricModPack) {
		fabricModPack = isFabricModPack;
	}
}
