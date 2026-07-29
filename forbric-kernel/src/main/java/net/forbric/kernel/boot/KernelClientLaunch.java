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

package net.forbric.kernel.boot;

/**
 * The client boot entry (BOOT side). Thin wrapper over {@link KernelBoot} in {@code CLIENT} mode; shares the whole
 * flow with {@link KernelServerLaunch}.
 *
 * <p>The launch script must add the client-only JVM setup the merged base's rendering stack needs — on macOS
 * {@code -XstartOnFirstThread} (GLFW must own the main thread) and {@code -Djava.library.path} pointing at the
 * LWJGL natives. Everything after {@code --} is forwarded to {@code net.minecraft.client.main.Main} (which, unlike
 * the dedicated server, accepts {@code --gameDir} / {@code --assetsDir} / {@code --assetIndex} / account args).
 */
public final class KernelClientLaunch {
	private KernelClientLaunch() {
	}

	public static void main(String[] args) throws Throwable {
		KernelBoot.launch(KernelBoot.Side.CLIENT, args);
	}
}
