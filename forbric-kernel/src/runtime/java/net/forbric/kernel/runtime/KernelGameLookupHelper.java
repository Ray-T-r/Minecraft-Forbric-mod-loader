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

import java.lang.invoke.MethodHandles;

/**
 * Mints a full-power {@link MethodHandles.Lookup} whose lookup class is GAME-side.
 *
 * <p>Forge's EventBus spins its listeners with {@code LambdaMetafactory}, which demands a full-power lookup —
 * one still holding the MODULE bit — over the class being registered. A boot-side {@code MethodHandles.lookup()}
 * cannot produce one for a game class: teleporting it with {@code privateLookupIn} crosses the boot→game module
 * boundary and drops that bit, and {@code LambdaMetafactory} then rejects the caller outright
 * ({@code LambdaConversionException: Invalid caller}).
 *
 * <p>A lookup's power comes from where the code calling {@code MethodHandles.lookup()} lives, so the only way to
 * mint one for the game module is to run that call inside it. This class is defined by
 * {@code ForbricClassLoader} (the package is pinned {@code ALWAYS_GAME}), so the lookup it returns is game-side
 * and in the game loader's unnamed module. {@code KernelGameLookup.privateLookupIn} then teleports it WITHIN
 * that module, which keeps every bit.
 *
 * <p>The kernel used to emit these same instructions with an ASM {@code ClassWriter} at boot, because before this
 * source set had a delivery path there was nowhere for such a class to be compiled. It is here now as the first
 * inhabitant of the game side: the class it replaces was five ASM calls that no compiler ever checked, and if
 * this file is reachable at all then the whole game-side pipeline — compile against the staged jars, pack into
 * {@code forbric-kernel-runtime.jar}, carry it inside the boot jar, extract it, own it, define it — is working.
 * It is deliberately the smallest thing that can prove that, so that when the pipeline breaks, nothing else is
 * broken at the same time.
 */
public final class KernelGameLookupHelper {
	private KernelGameLookupHelper() {
	}

	/** A full-power lookup whose lookup class is this one — i.e. game-side, in the game loader's module. */
	public static MethodHandles.Lookup lookup() {
		return MethodHandles.lookup();
	}
}
