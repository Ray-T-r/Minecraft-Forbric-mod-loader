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

import java.util.concurrent.atomic.AtomicBoolean;

import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;

/**
 * Registers a {@code ModelLoadingPlugin}, the way balm-fabric does. The plugin is only ever CALLED if
 * fabric-model-loading-api-v1's {@code ModelManagerMixin} applied — the whole point of trimming it instead of
 * pinning it. Linked only when {@link #install()} runs.
 */
final class ModelProbe {
	private static final AtomicBoolean SEEN_A_MODEL = new AtomicBoolean();

	private ModelProbe() {
	}

	static void install() {
		ModelLoadingPlugin.register(context -> {
			System.out.println("[ForbricFabricLive] ModelLoadingPlugin invoked");
			context.modifyModelOnLoad().register((model, ctx) -> {
				if (SEEN_A_MODEL.compareAndSet(false, true)) {
					System.out.println("[ForbricFabricLive] ModelModifier.OnLoad saw its first model: " + ctx.id());
				}
				return model;
			});
		});
		System.out.println("[ForbricFabricLive] ModelLoadingPlugin registered");
	}
}
