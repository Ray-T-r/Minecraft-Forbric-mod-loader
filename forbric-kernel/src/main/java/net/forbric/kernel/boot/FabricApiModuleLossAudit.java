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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.forbric.api.ModCatalog;
import net.forbric.api.Side;
import net.forbric.kernel.transform.GuestInjectorPruner;
import net.forbric.kernel.transform.LootTableEventBridgeInjector;
import net.forbric.kernel.util.ByteScan;
import net.forbric.kernel.util.ForbricLog;

/**
 * Names every installed mod that uses a fabric-api surface the merged base still switches off, and marks it
 * DEGRADED in the catalog so the loss reaches the log and {@code .forbric-kernel/load-report.txt} instead of
 * failing silently somewhere downstream.
 *
 * <p>{@link net.forbric.kernel.mixin.MergedBaseMixinCompat} pins a handful of fabric-api mixins that cannot fit
 * the merged base. Most of what they carried is restored by other means now (loot events by
 * {@link LootTableEventDispatch}, model-loading plugins by {@link GuestInjectorPruner}); what is NOT is listed
 * here, one surface per row, with the predicate that says whether it is still lost on THIS boot — so switching a
 * restoration off with its {@code -Dforbric.<x>=off} makes its users show up here, and the row goes quiet again
 * the day the surface is restored. Same needle/note/report shape as {@link CapabilityUseAudit}.
 *
 * <p>A constant-pool needle over-reports a mod that names the API only in an optional compat class. That is the
 * safe direction: a DEGRADED row that turns out not to matter costs a glance; a silent loss costs a bug hunt.
 * fabric-api's own module jars name every surface they define and are never users, so they are skipped by
 * catalog identity (mod id {@code fabric-api}, or bundled by it).
 */
public final class FabricApiModuleLossAudit {
	static final String SWITCH = "forbric.fabricApiAudit";
	static final String FABRIC_API = "fabric-api";

	/**
	 * One surface that is (or can be) switched off.
	 *
	 * @param module    the fabric-api module that defines it
	 * @param needle    the internal name (or package prefix) a user's constant pool carries
	 * @param side      the only side the surface exists on, or {@code null} for both
	 * @param cost      what a user loses, in one sentence
	 * @param stillLost whether it is lost on this boot (kill switches bind here)
	 */
	record Loss(String module, String needle, Side side, String cost, BooleanSupplier stillLost) {
	}

	static final List<Loss> LOSSES = List.of(
			new Loss("fabric-loot-api-v3", "net/fabricmc/fabric/api/loot/v3/LootTableEvents", null,
					"LootTableEvents.REPLACE, MODIFY and ALL_LOADED never fire",
					() -> !LootTableEventBridgeInjector.enabled()),
			new Loss("fabric-model-loading-api-v1", "net/fabricmc/fabric/api/client/model/loading/v1/", Side.CLIENT,
					"ModelLoadingPlugin is never invoked",
					() -> !GuestInjectorPruner.enabled()),
			new Loss("fabric-model-loading-api-v1", "net/fabricmc/fabric/api/client/model/loading/v1/UnbakedModelDeserializer",
					Side.CLIENT, "fabric:type model formats are parsed by NeoForge's loader instead", () -> true),
			new Loss("fabric-creative-tab-api-v1", "net/fabricmc/fabric/api/client/creativetab/v1/", Side.CLIENT,
					"FabricCreativeModeInventoryScreen is not implanted — a cast to it throws ClassCastException",
					() -> true),
			// The CLASS, not the package: lithostitched names DynamicRegistries in the same package, which works.
			new Loss("fabric-registry-sync-v0", "net/fabricmc/fabric/api/event/registry/DynamicRegistrySetupCallback", null,
					"DynamicRegistrySetupCallback never fires (RegistryDataLoaderMixin is pinned)", () -> true));

	private static final byte[][] NEEDLES = needles();
	private static final Map<Loss, Set<String>> USERS = new LinkedHashMap<>();

	private FabricApiModuleLossAudit() {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(SWITCH, "on"));
	}

	private static byte[][] needles() {
		byte[][] out = new byte[LOSSES.size()][];
		for (int i = 0; i < out.length; i++) out[i] = ByteScan.needle(LOSSES.get(i).needle());
		return out;
	}

	/** Every class of every jar, one pass; a jar that cannot be read is skipped, never refused. */
	public static void scan(List<Path> jars) {
		if (!enabled()) return;
		for (Path jar : jars) {
			String name = jar.getFileName().toString();
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				for (ZipEntry entry : zip.stream().toList()) {
					if (!entry.getName().endsWith(".class")) continue;
					try (InputStream in = zip.getInputStream(entry)) {
						note(name, in.readAllBytes());
					}
				}
			} catch (IOException unreadable) {
				ForbricLog.debug("[Forbric/FabricApi] could not read %s: %s", name, unreadable);
			}
		}
	}

	/** Records {@code jarName} under every surface {@code classBytes} names. */
	public static void note(String jarName, byte[] classBytes) {
		if (jarName == null || classBytes == null || !ByteScan.containsAny(classBytes, NEEDLES)) return;
		for (int i = 0; i < NEEDLES.length; i++) {
			if (!ByteScan.contains(classBytes, NEEDLES[i])) continue;
			synchronized (USERS) {
				USERS.computeIfAbsent(LOSSES.get(i), l -> new LinkedHashSet<>()).add(jarName);
			}
		}
	}

	/**
	 * After the catalog is published: one WARN per surface still lost on {@code side} with a third-party user,
	 * and DEGRADED on every catalog entry whose jar names it.
	 */
	public static void report(Side side) {
		if (!enabled()) return;
		Map<Loss, Set<String>> users;
		synchronized (USERS) {
			if (USERS.isEmpty()) return;
			users = new LinkedHashMap<>();
			for (var e : USERS.entrySet()) users.put(e.getKey(), Set.copyOf(e.getValue()));
		}
		List<ModCatalog.Entry> catalog = ModCatalog.everything();
		for (Loss loss : LOSSES) {
			Set<String> jars = users.get(loss);
			if (jars == null || jars.isEmpty()) continue;
			if (loss.side() != null && loss.side() != side) continue;
			if (!loss.stillLost().getAsBoolean()) continue;

			List<String> named = new ArrayList<>();
			Set<String> thirdParty = new LinkedHashSet<>();
			for (ModCatalog.Entry entry : catalog) {
				if (entry.jar() == null || !jars.contains(entry.jar())) continue;
				if (FABRIC_API.equals(entry.modId()) || FABRIC_API.equals(entry.bundledBy()) || loss.module().equals(entry.modId())) continue;
				ModCatalog.mark(entry.modId(), ModCatalog.Status.DEGRADED, loss.module() + ": " + loss.cost());
				named.add(entry.modId());
				thirdParty.add(entry.jar());
			}
			if (thirdParty.isEmpty()) continue;
			ForbricLog.warn("[Forbric/FabricApi] %d mod jar(s) use %s's %s, which is switched off on the merged base — %s. "
					+ "Marked DEGRADED: %s (jars: %s)", thirdParty.size(), loss.module(), simple(loss.needle()),
					loss.cost(), named, thirdParty);
		}
	}

	private static String simple(String needle) {
		String trimmed = needle.endsWith("/") ? needle.substring(0, needle.length() - 1) : needle;
		return trimmed.substring(trimmed.lastIndexOf('/') + 1);
	}

	/** The jars recorded so far, per surface. Package-private: for the test. */
	static Map<Loss, Set<String>> users() {
		synchronized (USERS) {
			Map<Loss, Set<String>> out = new LinkedHashMap<>();
			for (var e : USERS.entrySet()) out.put(e.getKey(), Set.copyOf(e.getValue()));
			return out;
		}
	}

	/** Test seam: forget what has been recorded. */
	static void reset() {
		synchronized (USERS) {
			USERS.clear();
		}
	}
}
