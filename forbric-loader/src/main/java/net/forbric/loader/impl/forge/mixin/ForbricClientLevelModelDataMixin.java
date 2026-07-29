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
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.forbric.loader.impl.forge.runtime.ForbricClientModelDataBridge;

@Mixin(targets = "net.minecraft.client.multiplayer.ClientLevel")
public class ForbricClientLevelModelDataMixin {
	@Unique
	private Object forbric$forgeModelDataManager;

	@Inject(method = "<init>", at = @At("TAIL"), require = 0)
	private void forbric$initForgeModelDataManager(CallbackInfo ci) {
		forbric$forgeModelDataManager = ForbricClientModelDataBridge.ensureForgeModelDataManager(
				this, forbric$forgeModelDataManager, ForbricClientLevelModelDataMixin.class.getClassLoader());
	}

	@Inject(
			method = "getModelDataManager()Lnet/minecraftforge/client/model/data/ModelDataManager;",
			at = @At("HEAD"),
			cancellable = true,
			require = 0)
	private void forbric$getForgeModelDataManager(CallbackInfoReturnable<Object> cir) {
		forbric$forgeModelDataManager = ForbricClientModelDataBridge.ensureForgeModelDataManager(
				this, forbric$forgeModelDataManager, ForbricClientLevelModelDataMixin.class.getClassLoader());
		if (forbric$forgeModelDataManager != null) cir.setReturnValue(forbric$forgeModelDataManager);
	}
}
