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
 * Thrown by a {@link LanguageAdapter} that cannot turn an entrypoint definition into an instance.
 *
 * <p>Part of the mod-facing ABI, reproduced from Fabric Loader (Apache-2.0, Copyright FabricMC) because a
 * third-party adapter's compiled bytecode constructs exactly these signatures — {@code fabric-language-kotlin}'s
 * {@code KotlinAdapter} uses both the message and the cause form.
 */
public class LanguageAdapterException extends Exception {
	private static final long serialVersionUID = 1L;

	public LanguageAdapterException(String message) {
		super(message);
	}

	public LanguageAdapterException(Throwable cause) {
		super(cause);
	}

	public LanguageAdapterException(String message, Throwable cause) {
		super(message, cause);
	}
}
