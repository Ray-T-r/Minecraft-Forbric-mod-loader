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

import net.forbric.loader.impl.forge.runtime.ForbricRegistryBridge;

/**
 * Tri-in-one only: opens Forge's {@code NamespacedWrapper} registry-frozen window around NeoForge's genuine
 * {@code CommonModLoader.begin(...)} (which runs {@code gatherAndInitializeMods}, "Registry initialization",
 * and "Config loading"), so NeoForge's own registry-init task can write to registries the MERGED game base
 * wrapped Forge's way. See {@link ForbricRegistryBridge} for the full rationale.
 *
 * <p>No-ops on a pure-NeoForge (non-merged) base: {@code ForbricRegistryBridge} finds no {@code NamespacedWrapper}
 * instances there and does nothing. String {@code targets} + reflective body so this compiles against
 * sponge-mixin alone, with no NeoForge compile dependency.
 */
@Mixin(targets = "net.neoforged.neoforge.internal.CommonModLoader")
public class NeoRegistryBridgeMixin {
	@Inject(method = "begin", at = @At("HEAD"))
	private static void forbric$openForgeRegistryWindow(CallbackInfo ci) {
		ForbricRegistryBridge.setForgeWrappersFrozen(NeoRegistryBridgeMixin.class.getClassLoader(), false);
	}

	@Inject(method = "begin", at = @At("RETURN"))
	private static void forbric$closeForgeRegistryWindow(CallbackInfo ci) {
		ForbricRegistryBridge.setForgeWrappersFrozen(NeoRegistryBridgeMixin.class.getClassLoader(), true);
	}
}
