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

package net.forbric.kernel.runtime;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one thing {@link KernelForgeConditionsRealCodecTest} substitutes: the condition-type REGISTRY each family's
 * {@code ICondition.CODEC} dispatches through. Neither registry can be built outside a running game (MinecraftForge's
 * is created by its registry manager, NeoForge's is filled by mod loading), so the test rewrites the one lookup in
 * each {@code ICondition} that fetches the registry's codec to call {@link #codec} instead, and hands it a codec over
 * the real condition codecs ({@code NotCondition.CODEC}, {@code TagEmptyCondition.CODEC}, ...). Every codec,
 * decoder and condition test that runs is the families' own.
 *
 * <p>Loaded by the test's own loader, the game-side loader's parent, so it names no game type: the registry codecs
 * are built reflectively inside the game-side loader and kept here as plain objects, keyed by loader so that the
 * per-test loaders never see each other's.
 */
public final class ConditionRegistryStandIn {
	private static final Map<ClassLoader, Map<String, Object>> CODECS = new ConcurrentHashMap<>();

	private ConditionRegistryStandIn() {
	}

	static void install(ClassLoader game, String family, Object registryCodec) {
		CODECS.computeIfAbsent(game, ignored -> new ConcurrentHashMap<>()).put(family, registryCodec);
	}

	static void forget(ClassLoader game) {
		CODECS.remove(game);
	}

	/** Called from the rewritten {@code ICondition}: the registry codec this family's dispatch reads type ids with. */
	public static Object codec(String family, Class<?> caller) {
		Map<String, Object> codecs = CODECS.get(caller.getClassLoader());
		Object codec = codecs == null ? null : codecs.get(family);
		if (codec == null) throw new IllegalStateException("no stand-in condition registry installed for " + family);
		return codec;
	}
}
