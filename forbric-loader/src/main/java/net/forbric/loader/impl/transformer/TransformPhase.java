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
 * The fixed, ordered stages of Forbric's single unified class-transformation pipeline.
 *
 * <p>Forbric hosts <em>one</em> transforming class loader. Both ecosystems' bytecode edits are
 * expressed as {@link ClassTransformer}s slotted into these phases, which always run in
 * {@linkplain #ordinal() declaration order}. This replaces Fabric's hardcoded
 * {@code entrypoint -> FabricTransformer -> Mixin} sequence and Forge's separately-owned
 * {@code IClassTransformer}/{@code ILaunchPluginService} chain with a single ordering all mods share.
 *
 * <p>{@link #MIXIN} is terminal and exclusive: it is applied last by the class delegate (which owns
 * the single {@code IMixinTransformer}), so no transformer may register into it via the
 * {@link TransformChain}.
 */
public enum TransformPhase {
	/** Game entrypoint/bootstrap patches injected by the {@code GameProvider} (e.g. hand control to Forbric). */
	RAW_PATCH,
	/** Remap a mod's bytecode from its source namespace (intermediary or SRG/Mojmap) to the canonical runtime namespace. */
	DEOBF_REMAP,
	/** Strip members annotated for the other physical side (Fabric {@code @Environment}). */
	ENV_STRIP,
	/** Apply the unified access model (Fabric Access Wideners + Forge Access Transformers). */
	ACCESS,
	/** Forge-style class transformers / coremods, ordered by sort index then topological pre-depends. */
	COREMOD,
	/** Fabric built-in transforms (package-access fixes, class tweaks). */
	FABRIC_BUILTIN,
	/** The single Mixin transformer. Terminal and exclusive — applied by the class delegate, not via the chain. */
	MIXIN;

	/** The last phase that the {@link TransformChain} itself runs; {@link #MIXIN} is applied separately. */
	public static final TransformPhase LAST_CHAIN_PHASE = FABRIC_BUILTIN;
}
