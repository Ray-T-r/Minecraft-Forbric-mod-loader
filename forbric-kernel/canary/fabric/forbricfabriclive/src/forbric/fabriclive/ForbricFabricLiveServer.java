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

package forbric.fabriclive;

import net.fabricmc.api.DedicatedServerModInitializer;

import net.minecraft.core.registries.BuiltInRegistries;

/**
 * The canary's {@code server} entrypoint. Fabric runs the side-specific initializer after {@code main}, so this
 * also proves ordering — and it reads back the content {@code main} registered.
 */
public final class ForbricFabricLiveServer implements DedicatedServerModInitializer {
	@Override
	public void onInitializeServer() {
		boolean stillThere = BuiltInRegistries.CUSTOM_STAT.containsKey(ForbricFabricLive.CANARY_STAT);
		System.out.println("[ForbricFabricLive] onInitializeServer (Fabric server entrypoint), "
				+ "registered content survives=" + stillThere);
	}
}
