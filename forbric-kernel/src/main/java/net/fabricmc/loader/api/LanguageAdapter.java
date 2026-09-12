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
 * Turns an entrypoint definition written in another JVM language into an instance of the requested type.
 *
 * <p>A mod declares one with {@code "languageAdapters": {"kotlin": "net.fabricmc.language.kotlin.KotlinAdapter"}}
 * and other mods then name it per entrypoint: {@code {"adapter": "kotlin", "value": "dev.isxander.zoomify.Zoomify"}}.
 * The adapter is itself an ordinary mod class, so it is loaded from the game loader like any other.
 *
 * <p>Part of the mod-facing ABI, reproduced from Fabric Loader (Apache-2.0, Copyright FabricMC) because a
 * third-party adapter's compiled bytecode implements exactly this interface and calls exactly this
 * {@link #getDefault()}.
 *
 * <p>Forbric deviation: {@link #getDefault()} resolves the sovereign kernel's own default adapter — the same
 * class/static-field/method-reference resolution the kernel applies to an entrypoint with no adapter named.
 * Adapters delegate to it for anything they do not handle themselves; {@code KotlinAdapter} does so for a plain
 * Java class.
 */
public interface LanguageAdapter {
	/** The adapter used when an entrypoint names none: plain Java resolution (kernel-backed). */
	static LanguageAdapter getDefault() {
		return net.forbric.kernel.fabric.KernelLanguageAdapters.defaultAdapter();
	}

	/**
	 * Creates the entrypoint instance.
	 *
	 * @param mod   the mod the entrypoint belongs to
	 * @param value the definition from {@code fabric.mod.json}, e.g. {@code a.b.C} or {@code a.b.C::member}
	 * @param type  the interface the caller needs back
	 * @throws LanguageAdapterException if the definition cannot be resolved to an instance of {@code type}
	 */
	<T> T create(ModContainer mod, String value, Class<T> type) throws LanguageAdapterException;
}
