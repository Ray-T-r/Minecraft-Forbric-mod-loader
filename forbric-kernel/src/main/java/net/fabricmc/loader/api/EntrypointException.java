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

package net.fabricmc.loader.api;

/**
 * Thrown when the entries of an entrypoint cannot be loaded: {@link FabricLoader#getEntrypoints} and
 * {@link FabricLoader#getEntrypointContainers} gather every failing entry of the key into one, and a container's
 * {@code getEntrypoint()} throws one for its own entry.
 *
 * <p>Part of the mod-facing ABI, reproduced from Fabric Loader (Apache-2.0, Copyright FabricMC) with its exact
 * constructors and messages. A mod that only CATCHES it names it in its exception table, so the class must exist the
 * moment the catching method links, thrown or not: JEI's Fabric client lifecycle catches it around its plugin lookup,
 * and without the class its whole client startup died on {@code NoClassDefFoundError}.
 */
public class EntrypointException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	private final String key;

	public EntrypointException(String key, Throwable cause) {
		super("Exception while loading entries for entrypoint '" + key + "'!", cause);
		this.key = key;
	}

	public EntrypointException(String key, String causingMod, Throwable cause) {
		super("Exception while loading entries for entrypoint '" + key + "' provided by '" + causingMod + "'", cause);
		this.key = key;
	}

	@Deprecated
	public EntrypointException(String message) {
		super(message);
		this.key = "";
	}

	@Deprecated
	public EntrypointException(Throwable cause) {
		super(cause);
		this.key = "";
	}

	/** The entrypoint key whose entries failed to load. */
	public String getKey() {
		return key;
	}
}
