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

import net.forbric.loader.impl.compat.ForbricCustomPayloadInterop;
import net.minecraft.server.network.ConfigurationTask;

/**
 * Fabric and NeoForge both negotiate common networking over {@code c:version}/{@code c:register}, while vanilla's
 * configuration queue compares concrete task ids. On the merged base those task ids are aliases for the same
 * wire-level handshake, so accept either owner at the completion boundary.
 */
@Mixin(targets = "net.minecraft.server.network.ServerConfigurationPacketListenerImpl")
public class ForbricCommonNetworkTaskMixin {
	@Inject(method = "finishCurrentTask", at = @At("HEAD"), cancellable = true, require = 0)
	private void forbric$finishEquivalentCommonNetworkTask(ConfigurationTask.Type type, CallbackInfo ci) {
		if (ForbricCustomPayloadInterop.finishEquivalentCommonTask(this, type)) ci.cancel();
	}
}
