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

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;

import net.forbric.kernel.classloading.ForbricClassLoader;

/**
 * A full-power {@link MethodHandles.Lookup} whose lookup class lives on the GAME side.
 *
 * <p>Some ecosystem code (Forge's EventBus, which spins listener lambdas with {@code LambdaMetafactory}) requires
 * a full-power lookup — one holding the MODULE bit — over a class it is registering. A boot-side
 * {@code MethodHandles.lookup()} cannot produce that for a game class: teleporting it with
 * {@code privateLookupIn} across the boot→game module boundary drops the MODULE bit, and
 * {@code LambdaMetafactory} then rejects the caller ({@code LambdaConversionException: Invalid caller}).
 *
 * <p>The only way to mint a full-power lookup for a class is from code running in that class's own module. So the
 * helper whose {@code lookup()} is called lives in {@code net.forbric.kernel.runtime} — the kernel's game-side
 * source set, which {@code DelegationPolicy} pins {@code ALWAYS_GAME} — and is therefore defined by
 * {@link ForbricClassLoader}, in the game loader's unnamed module. {@link #privateLookupIn} then teleports that
 * WITHIN the game module, a full-power result LambdaMetafactory accepts.
 *
 * <p>The helper used to be emitted here as five ASM calls, because before the game-side source set had a
 * delivery path there was nowhere to compile it. It is now compiled from
 * {@code net.forbric.kernel.runtime.KernelGameLookupHelper} and carried inside the boot jar. Its absence is
 * reported ONCE at boot by {@link KernelRuntimeClasses}; the throw here is the second line of defence, and says
 * the same thing, because whoever reaches it may not have the top of the log.
 */
public final class KernelGameLookup {
	private static final String HELPER = "net.forbric.kernel.runtime.KernelGameLookupHelper";

	private static volatile MethodHandles.Lookup gameLookup;

	private KernelGameLookup() {
	}

	/** The game-side full-power lookup, defined+cached on first use. */
	public static MethodHandles.Lookup get(ForbricClassLoader loader) throws ReflectiveOperationException {
		MethodHandles.Lookup l = gameLookup;
		if (l != null) return l;

		synchronized (KernelGameLookup.class) {
			if (gameLookup != null) return gameLookup;

			Class<?> helper;
			try {
				helper = Class.forName(HELPER, true, loader);
			} catch (ClassNotFoundException absent) {
				throw new ClassNotFoundException(HELPER + " — the kernel's game-side jar is not on the loader. "
						+ "This boot jar was built without the staged game artifacts; rebuild with them present "
						+ "(../forbric-loader/run/) via ./gradlew jar", absent);
			}
			Method lookupM = helper.getMethod("lookup");
			gameLookup = (MethodHandles.Lookup) lookupM.invoke(null);
			return gameLookup;
		}
	}

	/**
	 * A full-power lookup over {@code target}, suitable for {@code LambdaMetafactory}. {@code target} must be a
	 * game-side class (defined by {@code loader}); the teleport stays within the game module.
	 */
	public static MethodHandles.Lookup privateLookupIn(Class<?> target, ForbricClassLoader loader)
			throws ReflectiveOperationException {
		return MethodHandles.privateLookupIn(target, get(loader));
	}
}
