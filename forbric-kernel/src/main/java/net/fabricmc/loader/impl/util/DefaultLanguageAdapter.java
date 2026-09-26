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

package net.fabricmc.loader.impl.util;

import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.api.LanguageAdapterException;
import net.fabricmc.loader.api.ModContainer;

/**
 * Fabric Loader's plain-Java adapter, by the name a mod reaching into the loader's internals asks for it.
 *
 * <p>SuperMartijn642's Core Lib passes {@code DefaultLanguageAdapter.INSTANCE} when it adds an entrypoint to
 * {@code EntrypointStorage} itself. Resolution is the kernel's own default ({@code LanguageAdapter.getDefault()}),
 * the same one every adapter-less entrypoint in {@code fabric.mod.json} goes through, so an entrypoint added this
 * way is built exactly like a declared one. {@code -Dforbric.fabricImpl=off} withholds it from mods.
 */
public final class DefaultLanguageAdapter implements LanguageAdapter {
	public static final DefaultLanguageAdapter INSTANCE = new DefaultLanguageAdapter();

	private DefaultLanguageAdapter() {
	}

	@Override
	public <T> T create(ModContainer mod, String value, Class<T> type) throws LanguageAdapterException {
		return LanguageAdapter.getDefault().create(mod, value, type);
	}
}
