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
import org.spongepowered.asm.mixin.injection.Redirect;

import net.forbric.loader.impl.forge.runtime.ForbricClientModelDataBridge;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraftforge.client.model.data.ModelDataManager;

@Mixin(targets = "net.minecraft.client.renderer.extract.LevelExtractor")
public class ForbricLevelExtractorModelDataMixin {
	@Redirect(
			method = "extractBlockDestroyAnimation",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/multiplayer/ClientLevel;"
							+ "getModelDataManager()Lnet/minecraftforge/client/model/data/ModelDataManager;"),
			require = 0)
	private ModelDataManager forbric$getForgeModelDataManager(ClientLevel clientLevel) {
		return (ModelDataManager) ForbricClientModelDataBridge.ensureForgeModelDataManager(
				clientLevel, null, ForbricLevelExtractorModelDataMixin.class.getClassLoader());
	}
}
