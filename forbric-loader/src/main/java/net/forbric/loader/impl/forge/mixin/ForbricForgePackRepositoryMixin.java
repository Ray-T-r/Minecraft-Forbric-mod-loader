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

import net.forbric.loader.impl.forge.bridge.ForbricForgeKnownPacks;

/**
 * Adds symmetric server-data known packs for wrapped traditional-Forge mods on merged PackRepositories.
 */
@Mixin(targets = "net.minecraft.server.packs.repository.PackRepository")
public class ForbricForgePackRepositoryMixin {
	@Inject(method = "<init>([Lnet/minecraft/server/packs/repository/RepositorySource;)V", at = @At("TAIL"), require = 0)
	private void forbric$addMergedForgeServerDataPacks(CallbackInfo ci) {
		ForbricForgeKnownPacks.addServerDataPacksIfApplicable(this, ForbricForgePackRepositoryMixin.class.getClassLoader());
	}
}
