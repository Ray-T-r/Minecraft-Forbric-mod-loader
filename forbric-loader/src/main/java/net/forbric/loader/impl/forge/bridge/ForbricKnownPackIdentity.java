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

package net.forbric.loader.impl.forge.bridge;

/**
 * Stable known-pack identity for Forbric-synthesized Forge-family packs.
 */
public final class ForbricKnownPackIdentity {
	public static final String KNOWN_PACK_NAMESPACE = "forbric";
	public static final String CLIENT_RESOURCES = "client_resources";
	public static final String SERVER_DATA = "server_data";

	private ForbricKnownPackIdentity() {
	}

	public static Descriptor descriptor(String ecosystem, String packType, String modId, String version) {
		String normalizedVersion = normalizeVersion(version);
		String knownPackId = ecosystem + "/" + packType + "/" + modId;
		String locationId = KNOWN_PACK_NAMESPACE + "/" + knownPackId;
		String title = "Forbric " + ecosystem + " " + packType + ": " + modId;
		return new Descriptor(locationId, KNOWN_PACK_NAMESPACE, knownPackId, normalizedVersion, title);
	}

	static String normalizeVersion(String version) {
		if (version == null) return "0";
		String trimmed = version.trim();
		return trimmed.isEmpty() ? "0" : trimmed;
	}

	public record Descriptor(String locationId, String knownPackNamespace, String knownPackId, String version,
			String titleText) {
	}
}
