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

import java.util.Optional;

/**
 * Carries NeoForge's stencil test from {@code RenderPipeline$Builder.buildSnippet} into the vanilla-shaped
 * 11-argument {@code RenderPipeline$Snippet} constructor, so the call site can construct through that
 * constructor (which fabric-rendering-v1's {@code @WrapOperation} handler matches) without losing the twelfth
 * argument NeoForge added.
 *
 * <p>{@code SnippetConstructorFunnel} makes {@code buildSnippet} call {@link #scope} with the Builder's stencil
 * test right before {@code new Snippet(11 args)}, and makes the 11-arg constructor read {@link #take} where it
 * used to read {@code Optional.empty()} before delegating to the 12-arg constructor. {@link #clear} follows the
 * construction so a value never outlives its call site. Thread-local: pipelines are built on whichever thread
 * registers them, never two on one thread at once, and a construction by anyone else on the same thread between
 * {@code scope} and the constructor is impossible on the rewritten straight-line path.
 */
public final class KernelSnippets {
	private static final ThreadLocal<Optional<?>> SCOPE = new ThreadLocal<>();

	private KernelSnippets() {
	}

	/** The stencil test the next 11-arg Snippet construction on this thread should carry. */
	public static void scope(Optional<?> stencilTest) {
		SCOPE.set(stencilTest == null ? Optional.empty() : stencilTest);
	}

	/** The scoped stencil test, or {@code Optional.empty()} when nothing is scoped; consumed on read. */
	public static Optional<?> take() {
		Optional<?> scoped = SCOPE.get();
		SCOPE.remove();
		return scoped == null ? Optional.empty() : scoped;
	}

	/** Discards a scoped value the construction did not consume (a wrap that never called the original). */
	public static void clear() {
		SCOPE.remove();
	}
}
