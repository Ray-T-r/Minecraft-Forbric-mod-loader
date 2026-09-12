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

package net.forbric.kernel.mixin;

import org.spongepowered.asm.service.IMixinServiceBootstrap;

/** Names the kernel's Mixin service to Mixin's own bootstrap. The kernel has already set itself up by then. */
public final class ForbricMixinServiceBootstrap implements IMixinServiceBootstrap {
	@Override
	public String getName() {
		return "Forbric";
	}

	@Override
	public String getServiceClassName() {
		return "net.forbric.kernel.mixin.ForbricMixinService";
	}

	@Override
	public void bootstrap() {
		// Nothing to do: KernelMixinBootstrap binds the loader before MixinBootstrap.init() runs.
	}
}
