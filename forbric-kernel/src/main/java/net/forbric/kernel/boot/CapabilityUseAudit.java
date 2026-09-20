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

import java.util.LinkedHashSet;
import java.util.Set;

import net.forbric.kernel.util.ByteScan;
import net.forbric.kernel.util.ForbricLog;

/**
 * Names the mods that ask for a capability system the merged game does not have.
 *
 * <h2>What is missing, and why this is a warning rather than a repair</h2>
 *
 * <p>Traditional MinecraftForge attaches capabilities — item handlers, fluid tanks, energy storage — to entities,
 * block entities and levels through a provider on a common superclass. The merge puts those three root types
 * under NeoForge's attachment system instead, so that superclass and its storage are gone. A mod calling
 * {@code getCapability} therefore has nothing to call it on.
 *
 * <p>Attaching capabilities for real means giving those root types a provider, which is a change to how the
 * merge builds them, not something a transformer can add afterwards. Until then the honest thing is to say which
 * mods are affected, because the failure otherwise arrives as a missing-method error from inside the mod, on a
 * line the player cannot connect to anything.
 *
 * <p>Detected by looking for the package name in the raw class bytes, where a type it names appears verbatim in
 * the constant pool. That makes a "no" final rather than a guess, and it costs one pass over bytes the
 * subscriber scan is already reading.
 */
public final class CapabilityUseAudit {
	/** The package a traditional MinecraftForge mod names to reach the capability system. */
	static final String PACKAGE = "net/minecraftforge/common/capabilities";

	private static final byte[] NEEDLE = ByteScan.needle(PACKAGE);

	private static final Set<String> USERS = new LinkedHashSet<>();

	private CapabilityUseAudit() {
	}

	/** Records that a jar names the capability package. Called per class from the scan that already reads them. */
	public static void note(String jarName, byte[] classBytes) {
		if (jarName == null || !ByteScan.contains(classBytes, NEEDLE)) return;
		synchronized (USERS) {
			USERS.add(jarName);
		}
	}

	/** Whether {@code classBytes} names the capability package at all. Split out so a test can drive it. */
	static boolean namesTheCapabilityPackage(byte[] classBytes) {
		return ByteScan.contains(classBytes, NEEDLE);
	}

	/** One line naming every affected mod, or nothing at all when none is. */
	/** The three roots the composition shim must have reached before mod loading for the feature to exist. */
	static final Set<String> ROOTS = Set.of("net/minecraft/world/entity/Entity",
			"net/minecraft/world/level/block/entity/BlockEntity", "net/minecraft/world/level/Level");

	/**
	 * With the shim on and every root composed, one INFO line; otherwise every mod in the jars that use the
	 * capability package is marked DEGRADED by id and the WARN names the mods, not a count. Attribution goes
	 * through the catalog by jar name; {@link net.forbric.api.ModCatalog#mark} drops what it does not know.
	 */
	public static void report(boolean shimActive, Set<String> composedRoots) {
		Set<String> users;
		synchronized (USERS) {
			if (USERS.isEmpty()) return;
			users = Set.copyOf(USERS);
		}
		Set<String> missing = new LinkedHashSet<>(ROOTS);
		missing.removeAll(composedRoots);
		if (shimActive && missing.isEmpty()) {
			ForbricLog.info("[Forbric/Capabilities] %d mod jar(s) use MinecraftForge's capability system — composed into "
					+ "Entity/BlockEntity/Level (ServerLevel and LevelChunk when the world loads): %s", users.size(), users);
			return;
		}
		String why = shimActive
				? "this instance did not compose MinecraftForge capabilities into " + simple(missing)
				: "-Dforbric.forgeCapabilities=off: this instance does not carry MinecraftForge capabilities";
		java.util.List<String> named = new java.util.ArrayList<>();
		for (net.forbric.api.ModCatalog.Entry entry : net.forbric.api.ModCatalog.everything()) {
			if (entry.jar() == null || !users.contains(entry.jar())) continue;
			net.forbric.api.ModCatalog.mark(entry.modId(), net.forbric.api.ModCatalog.Status.DEGRADED,
					"uses MinecraftForge capabilities; " + why);
			named.add(entry.modId());
		}
		ForbricLog.warn("[Forbric/Capabilities] %d mod jar(s) use MinecraftForge's capability system and will find it "
				+ "inert — %s. Marked DEGRADED: %s (jars: %s)", users.size(), why, named, users);
	}

	private static java.util.List<String> simple(Set<String> internal) {
		java.util.List<String> out = new java.util.ArrayList<>();
		for (String name : internal) out.add(name.substring(name.lastIndexOf('/') + 1));
		return out;
	}

	/** The jars recorded so far. Package-private: the report is the product; this is for the test. */
	static Set<String> users() {
		synchronized (USERS) {
			return Set.copyOf(USERS);
		}
	}

	/** Test seam: forget what has been recorded. */
	static void reset() {
		synchronized (USERS) {
			USERS.clear();
		}
	}
}
