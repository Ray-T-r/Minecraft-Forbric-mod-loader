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

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

public final class ForbricNeoBridgeMixinPlugin implements IMixinConfigPlugin {
	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (mixinClassName.endsWith(".ForbricClientLevelModelDataMixin")
				|| mixinClassName.endsWith(".ForbricLevelExtractorModelDataMixin")) {
			return hasMergedForgeNeoClientModelData();
		}
		return true;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	private static boolean hasMergedForgeNeoClientModelData() {
		ClassLoader cl = ForbricNeoBridgeMixinPlugin.class.getClassLoader();
		return hasClassResource(cl, "net.minecraftforge.registries.NamespacedWrapper")
				&& hasClassResource(cl, "net.neoforged.neoforge.network.registration.NetworkRegistry")
				&& hasClassResource(cl, "net.minecraftforge.client.model.data.ModelDataManager")
				&& hasClassResource(cl, "net.neoforged.neoforge.model.data.ModelDataManager");
	}

	private static boolean hasClassResource(ClassLoader cl, String name) {
		return cl.getResource(name.replace('.', '/') + ".class") != null;
	}
}
