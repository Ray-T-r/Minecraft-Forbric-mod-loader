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

package forbric.live;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * A guest mixin config plugin that reads {@code FMLEnvironment.dist} from its own class initializer.
 *
 * <p>Nothing here mixes anything in. This exists to occupy one specific instant: Mixin constructs every declared
 * {@code IMixinConfigPlugin} while it prepares configs, which happens on the FIRST class to pass through the
 * transformer — long before the kernel's main seeding pass. {@code FMLEnvironment}'s fields are
 * {@code static final}, decided once by whoever touches the class first, so a plugin that reads {@code dist}
 * there decides it for the whole process. Seeded too late, that value is null forever, and nothing throws:
 * supermartijn642's CoreLib lost every mixin to it, and libIPN's Kotlin constructor died on "dist must not be
 * null" several layers away.
 *
 * <p>In the initializer, not in {@code onLoad}: {@code onLoad} runs later, and would pin the wrong window.
 */
public class ForbricLiveMixinPlugin implements IMixinConfigPlugin {

	private static final net.minecraftforge.api.distmarker.Dist DIST =
			net.minecraftforge.fml.loading.FMLEnvironment.dist;

	static {
		System.out.println("[ForbricLive/PLUGIN] prepareConfigs saw dist=" + DIST);
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
