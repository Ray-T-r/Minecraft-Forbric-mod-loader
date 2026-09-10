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

import java.util.function.Consumer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.forbric.loader.impl.forge.runtime.ForbricRegistryBridge;

/**
 * Merged-base registry/tag sync boundary: before the server serializes the configuration registry packet, make
 * sure Forge {@code NamespacedWrapper} registries have a bound frozen tag snapshot. This is a lifecycle invariant,
 * not a mod-specific workaround; it prevents any later open/close drift from surfacing as vanilla's
 * {@code Tags not bound} hard crash during configuration handshake.
 */
@Mixin(targets = "net.minecraft.server.network.config.SynchronizeRegistriesTask")
public class ForbricRegistrySyncBoundaryMixin {
	@Inject(method = "sendRegistries", at = @At("HEAD"), require = 0)
	private void forbric$prepareMergedRegistrySync(Consumer<?> sender, java.util.Set<?> knownPacks, CallbackInfo ci) {
		ClassLoader cl = ForbricRegistrySyncBoundaryMixin.class.getClassLoader();
		ForbricRegistryBridge.ensureForgeWrapperTagsBound(cl);
	}
}
