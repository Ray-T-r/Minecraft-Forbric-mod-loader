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

package forbric.live.neoforge;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * A mixin config plugin whose class initializer asks NeoForge which side this is.
 *
 * <p>Mixin constructs every declared {@code IMixinConfigPlugin} while it prepares configs, which is before the
 * kernel's main seeding pass and before any mod is constructed. A plugin asking the loader anything there is asking
 * a loader that may not be set up yet — the MinecraftForge canary's twin of this class is how the kernel found
 * that its {@code FMLEnvironment.dist} was decided once, by whoever touched it first, and came out null for the
 * whole run. NeoForge reads the answer through {@code FMLLoader.getCurrent()} instead of caching it, so the failure
 * here would be a throw rather than a null, and both are told apart from the right answer by the gate.
 *
 * <p>It also proves the {@code [[mixins]]} declaration of a NeoForge mod reaches Mixin at all: a config that is
 * never handed over constructs no plugin and prints nothing.
 */
public class ForbricNeoLiveMixinPlugin implements IMixinConfigPlugin {

	static {
		String dist;
		try {
			dist = String.valueOf(net.neoforged.fml.loading.FMLEnvironment.getDist());
		} catch (Throwable t) {
			dist = "FAILED " + t;
		}
		System.out.println("[ForbricNeoLive/PLUGIN] prepareConfigs saw dist=" + dist);
	}

	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
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
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo info) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo info) {
	}
}
