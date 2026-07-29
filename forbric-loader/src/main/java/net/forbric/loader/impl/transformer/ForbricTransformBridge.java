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

package net.forbric.loader.impl.transformer;

/**
 * The single seam by which Forbric's unified {@link TransformChain} runs inside the substrate's one
 * pre-Mixin byte path ({@code FabricTransformer.transform}). It is installed once during
 * {@code ForbricBootstrap.run()} and consulted for every game and mod class the Knot delegate loads, so
 * Forbric's Access Transformers, remappers and coremods apply to game classes on the real load path,
 * before Mixin.
 *
 * <p>Until {@link #install installed} (e.g. in unit tests, or before Forbric finishes bootstrap) this is a
 * transparent pass-through, so the substrate edit that calls it is harmless on its own.
 */
public final class ForbricTransformBridge {
	private static volatile TransformChain chain;
	private static volatile TransformContext context;

	private ForbricTransformBridge() {
	}

	/** Installs the chain + the context every class is transformed under. */
	public static void install(TransformChain chain, TransformContext context) {
		ForbricTransformBridge.context = context;
		ForbricTransformBridge.chain = chain;
	}

	public static boolean isInstalled() {
		return chain != null;
	}

	/**
	 * Runs the installed pre-Mixin chain over a class, or returns {@code bytes} unchanged if nothing is
	 * installed. {@code name} is the binary (dot-separated) class name.
	 */
	public static byte[] apply(String name, byte[] bytes) {
		TransformChain c = chain;
		if (c == null || bytes == null) return bytes;
		return c.applyBeforeMixin(name, bytes, context);
	}
}
