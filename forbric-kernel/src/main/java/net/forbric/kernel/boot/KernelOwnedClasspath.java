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

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** One owned order for class definition, Mixin's bytecode reads, and Mixin config resources. */
final class KernelOwnedClasspath {
	static final String SWITCH = "forbric.kernelBundledFirst";

	private KernelOwnedClasspath() { }

	static boolean bundledFirst() {
		return !"off".equalsIgnoreCase(System.getProperty(SWITCH, "on"));
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
		append(owned, forgeGuests);
		for (Path jar : fabricGuests) {
			// Preserve the existing cross-family dedupe; a universal jar needs only one owned URL.
			if (!forgeGuests.contains(jar)) owned.add(jar.toUri().toURL());
		}
		if (!suppliedFirst) append(owned, bundled);
		return owned;
	}

	private static void append(List<URL> owned, List<Path> jars) throws MalformedURLException {
		for (Path jar : jars) owned.add(jar.toUri().toURL());
	}
}
