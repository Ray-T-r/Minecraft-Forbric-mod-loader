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

package net.forbric.loader.impl.util;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The version gate: mod ids that DECLARE themselves incompatible with the running Minecraft version.
 *
 * <p>{@code ForbricBootstrap} computes the set once at boot (fail-open: only a mod whose own {@code minecraft}
 * dependency constraint excludes the running version lands here) and publishes it as the
 * {@value #PROPERTY} system property. It already drives whole-config mixin suppression; the loading-side
 * consumers ({@code ForbricFmlBootstrap.collectForgeJars}, {@code ForbricNeoForgeRuntime.collectNeoForgeJars},
 * {@code ForbricForgeClientPacks}) use the same signal to keep such a mod out of FML discovery entirely —
 * a mod built for another MC version must not enter {@code ModSorter} (a language-loader or dependency
 * mismatch there aborts, or poisons, the WHOLE ecosystem's loading, not just the one mod).
 */
public final class ForbricVersionGate {
	/** CSV of mod ids that declare incompatibility with the running MC version (set by ForbricBootstrap). */
	public static final String PROPERTY = "forbric.versionIncompatibleMods";

	private ForbricVersionGate() {
	}

	/** The published set (empty when the property is unset). */
	public static Set<String> versionIncompatibleModIds() {
		String csv = System.getProperty(PROPERTY, "");
		if (csv.isEmpty()) return Collections.emptySet();
		Set<String> ids = new LinkedHashSet<>();
		for (String id : csv.split(",")) {
			if (!id.isBlank()) ids.add(id.trim());
		}
		return ids;
	}

	/** Whether {@code modId} declared itself incompatible with the running MC version. */
	public static boolean isVersionIncompatible(String modId) {
		return versionIncompatibleModIds().contains(modId);
	}
}
