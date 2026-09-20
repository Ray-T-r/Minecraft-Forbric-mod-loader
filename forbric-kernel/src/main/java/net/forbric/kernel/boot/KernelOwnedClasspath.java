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

import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.forbric.kernel.fabric.FabricModDiscovery;
import net.forbric.kernel.fabric.FabricModMetadataParser;
import net.forbric.kernel.fabric.KernelModMetadata;
import net.forbric.kernel.fabric.KernelVersion;
import net.forbric.kernel.util.ForbricLog;

/** One owned order for class definition, Mixin's bytecode reads, and Mixin config resources. */
final class KernelOwnedClasspath {
	static final String SWITCH = "forbric.kernelBundledFirst";
	static final String VERSION_ORDER_SWITCH = "forbric.duplicateVersionOrder";

	private KernelOwnedClasspath() { }

	static boolean bundledFirst() {
		return !"off".equalsIgnoreCase(System.getProperty(SWITCH, "on"));
	}

	/** {@code -Dforbric.duplicateVersionOrder=off} goes back to "whichever copy was discovered first wins". */
	static boolean versionOrder() {
		return !"off".equalsIgnoreCase(System.getProperty(VERSION_ORDER_SWITCH, "on"));
	}

	/**
	 * Keep the base/carriers and Minecraft libraries ahead of mods, and give the kernel's supplied game-side
	 * libraries the same protection from guest copies. Badpackets' recursive JiJ supplied MixinExtras 0.3.5;
	 * when the kernel's 0.5.4 was appended last, that old copy won and could not parse Fabric API's EXPRESSION
	 * injection point. All jars remain game-owned: changing parent delegation would break generated LocalRefs.
	 */
	static List<URL> compose(List<URL> baseAndCarriers, List<Path> minecraftLibraries,
			List<Path> forgeGuests, List<Path> fabricGuests, List<Path> bundled) throws MalformedURLException {
		List<URL> owned = new ArrayList<>(baseAndCarriers);
		append(owned, minecraftLibraries);
		boolean suppliedFirst = bundledFirst();
		if (suppliedFirst) append(owned, bundled);
		List<Path> guests = new ArrayList<>(forgeGuests);
		for (Path jar : fabricGuests) {
			// Preserve the existing cross-family dedupe; a universal jar needs only one owned URL.
			if (!guests.contains(jar)) guests.add(jar);
		}
		append(owned, newestFirstWithinEachModId(guests));
		if (!suppliedFirst) append(owned, bundled);
		return owned;
	}

	/**
	 * Where two guest jars carry the SAME Fabric mod at different versions, puts the higher one first.
	 *
	 * <p>Not a withdrawal — ordering. The nested arbitration deliberately leaves same-family duplicates on the
	 * classpath ("Same-family duplicates are the ordinary shape of JarJar"): taking one off cost Sodium its
	 * ServiceLoader lookup once already. But leaving them in DISCOVERY order means first-URL-wins decides which
	 * build answers, and discovery order is jar-file order, which is nothing.
	 *
	 * <p>Distant Horizons is what found this: it nests an entire fabric-api 0.149.0, 45 modules of it, and the
	 * kernel put those ahead of the fabric-api 0.161.0 the player installed. Architectury then called
	 * {@code ScreenKeyboardEvents.allowCharType}, added after 0.149.0, and the CLIENT died in
	 * {@code Minecraft.<init>} with a NoSuchMethodError naming a fabric-api class — which reads as a broken mod,
	 * not as a shadowed library. Both genuine loaders resolve a duplicate by version.
	 *
	 * <p>Only members of a contested id move, and they move into each other's slots: everything else keeps the
	 * order the rest of the boot chose, because that order carries its own decisions (carriers before guests,
	 * libraries before mods).
	 */
	static List<Path> newestFirstWithinEachModId(List<Path> guests) {
		if (!versionOrder() || guests.size() < 2) return guests;

		Map<String, List<Integer>> slotsById = new LinkedHashMap<>();
		Map<Integer, String> versionBySlot = new LinkedHashMap<>();
		for (int i = 0; i < guests.size(); i++) {
			String[] idAndVersion = fabricIdentity(guests.get(i));
			if (idAndVersion == null) continue;
			slotsById.computeIfAbsent(idAndVersion[0], k -> new ArrayList<>()).add(i);
			versionBySlot.put(i, idAndVersion[1]);
		}

		List<Path> ordered = new ArrayList<>(guests);
		for (Map.Entry<String, List<Integer>> group : slotsById.entrySet()) {
			List<Integer> slots = group.getValue();
			if (slots.size() < 2) continue;
			List<Integer> byVersion = new ArrayList<>(slots);
			// Stable: equal or unparseable versions keep discovery order, so this never invents a decision.
			byVersion.sort((a, b) -> compareVersions(versionBySlot.get(b), versionBySlot.get(a)));
			if (byVersion.equals(slots)) continue;
			for (int i = 0; i < slots.size(); i++) ordered.set(slots.get(i), guests.get(byVersion.get(i)));
			ForbricLog.info("[Forbric/Boot] '%s' is on the classpath %d times — reading it from %s (%s), the "
					+ "highest version present. First-URL-wins would otherwise have given it to %s (%s), and a "
					+ "caller of anything the older build lacks gets a NoSuchMethodError naming that build",
					group.getKey(), slots.size(), guests.get(byVersion.get(0)).getFileName(),
					versionBySlot.get(byVersion.get(0)), guests.get(slots.get(0)).getFileName(),
					versionBySlot.get(slots.get(0)));
		}
		return ordered;
	}

	/** {@code {id, version}} from a jar's {@code fabric.mod.json}, or null for a jar that has none. */
	private static String[] fabricIdentity(Path jar) {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry(FabricModDiscovery.MANIFEST);
			if (entry == null) return null;
			try (InputStream in = zip.getInputStream(entry)) {
				KernelModMetadata metadata = FabricModMetadataParser.read(in);
				String id = metadata.getId();
				if (id == null || id.isBlank()) return null;
				return new String[] {id, String.valueOf(metadata.getVersion().getFriendlyString())};
			}
		} catch (Exception unreadable) {
			ForbricLog.debug("[Forbric/Boot] could not read %s for duplicate-version ordering: %s",
					jar.getFileName(), String.valueOf(unreadable));
			return null;
		}
	}

	/** Positive when {@code a} is the higher version. Anything unparseable compares equal, so nothing moves. */
	private static int compareVersions(String a, String b) {
		if (a == null || b == null || a.equals(b)) return 0;
		try {
			return KernelVersion.parse(a).compareTo(KernelVersion.parse(b));
		} catch (Exception unparseable) {
			return 0;
		}
	}

	private static void append(List<URL> owned, List<Path> jars) throws MalformedURLException {
		for (Path jar : jars) owned.add(jar.toUri().toURL());
	}
}
