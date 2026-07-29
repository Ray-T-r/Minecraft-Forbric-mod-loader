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

package net.forbric.loader.impl.forge.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.forbric.loader.impl.forge.runtime.ForbricDualLifecycle;

/**
 * Tri-in-one only: on the MERGED (Forge+NeoForge+Fabric) game base the dedicated-server {@code Main.main} calls
 * only NeoForge's {@code ServerModLoader.load(boolean)} (NeoForge won the byte-merge for that entry), so Forge's
 * own genuine mod-loading lifecycle never runs and its {@code ModList} is never populated. This injects at the
 * TAIL of NeoForge's {@code load(Z)} to drive Forge's {@code ServerModLoader.load()} right after NeoForge's
 * lifecycle finishes — see {@link ForbricDualLifecycle} for the full rationale and the registry-freeze handling.
 *
 * <p>No-ops on a pure-NeoForge (non-merged) base: {@code ForbricDualLifecycle} finds no traditional-Forge
 * {@code ServerModLoader} there and returns silently. String {@code targets} + a reflective body so this compiles
 * against sponge-mixin alone, with no NeoForge compile dependency; lives in the same {@code required: false}
 * {@code forbric-neoforge-bridge.mixins.json} as {@link NeoRegistryBridgeMixin} so it is skipped entirely when the
 * NeoForge target class isn't on the classpath (single-ecosystem Forge / Fabric-only deployments).
 */
@Mixin(targets = "net.neoforged.neoforge.server.loading.ServerModLoader")
public class NeoDualLifecycleMixin {
	@Inject(method = "load(Z)V", at = @At("TAIL"))
	private static void forbric$runForgeServerLifecycle(boolean gametest, CallbackInfo ci) {
		ForbricDualLifecycle.runForgeServerLifecycle(NeoDualLifecycleMixin.class.getClassLoader());
	}
}
