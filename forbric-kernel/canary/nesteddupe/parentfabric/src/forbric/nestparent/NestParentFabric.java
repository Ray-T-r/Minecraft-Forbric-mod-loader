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

package forbric.nestparent;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/** The Fabric consumer, asking the same question from its own side. */
public final class NestParentFabric implements ModInitializer {
	@Override
	public void onInitialize() {
		System.out.println("[ForbricNestParent] fabric parent up");
		boolean visible;
		try {
			visible = FabricLoader.getInstance().isModLoaded("forbricnestlib");
		} catch (Throwable t) {
			visible = false;
			System.out.println("[ForbricNestParent] fabric isModLoaded threw: " + t);
		}
		System.out.println("[ForbricNestParent] fabric sees forbricnestlib=" + visible);
	}
}
