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

package net.forbric.kernel.transform;

/**
 * A single bytecode transform step registered into a {@link TransformPhase} of the {@link TransformChain}.
 *
 * <p>This is the one SPI both ecosystems are adapted to: Fabric's built-in transforms are wrapped as
 * {@code ClassTransformer}s, and Forge {@code IClassTransformer}/{@code ILaunchPluginService} plugins are
 * wrapped by an adapter into {@code ClassTransformer}s too. Implementations must be thread-safe; the
 * unified class loader is parallel-capable.
 */
public interface ClassTransformer {
	/**
	 * Transforms a class.
	 *
	 * @param className  the binary (dot-separated) name of the class, e.g. {@code net.minecraft.world.World}
	 * @param classBytes the current class bytes (never {@code null})
	 * @param context    the per-invocation context
	 * @return the new class bytes, or {@code classBytes} unchanged if this transformer made no edit.
	 *         Returning {@code null} is treated as "unchanged".
	 */
	byte[] transform(String className, byte[] classBytes, TransformContext context);

	/** A stable, unique-within-its-phase name, used for {@code predepends} ordering and diagnostics. */
	default String name() {
		return getClass().getName();
	}

	/**
	 * The classes this transformer must edit when they are loaded, so that its failing to edit one is noticed.
	 *
	 * <p>Returning {@link AnchorSet#undeclared()} is the pre-migration default and means only that this
	 * transformer has not been converted yet. A transformer that genuinely has no fixed target says so with
	 * {@link AnchorSet#scanned(String)}, which keeps "cannot declare" distinguishable from "has not declared".
	 *
	 * <p>What is declared here is a class name and nothing else. The hit signal is the one {@link #transform}
	 * already contracts for -- the same array back means no edit -- so this never restates the match, and
	 * therefore cannot disagree with it.
	 */
	default AnchorSet anchors() {
		return AnchorSet.undeclared();
	}
}
