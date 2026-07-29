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

import net.forbric.loader.impl.forge.runtime.ForbricClientShutdown;

/**
 * At the very end of {@code Minecraft.close()} (main thread, runs before {@code Main.main} starts the
 * 15s {@code ClientShutdownWatchdog}), shut down the non-daemon FML/Forge background executors Forbric's
 * merged lifecycle never closes — otherwise the JVM cannot exit and the watchdog fires a "Client shutdown
 * from post-main" crash ~15s after every quit. See {@link ForbricClientShutdown}. {@code require = 0}:
 * a no-op if the target/handler is absent (never blocks shutdown itself).
 */
@Mixin(targets = "net.minecraft.client.Minecraft")
public class ForbricClientShutdownMixin {
	@Inject(method = "close", at = @At("TAIL"), require = 0)
	private void forbric$stopLeakedBackgroundExecutors(CallbackInfo ci) {
		ForbricClientShutdown.stopLeakedBackgroundExecutors(this.getClass().getClassLoader());
	}
}
