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

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/** The canary's {@code client} entrypoint: the client-only fabric-api surfaces, each gated on its module. */
public final class ForbricFabricLiveClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		System.out.println("[ForbricFabricLive] onInitializeClient (Fabric client entrypoint)");
		if (FabricLoader.getInstance().isModLoaded("fabric-model-loading-api-v1")) ModelProbe.install();
	}
}
