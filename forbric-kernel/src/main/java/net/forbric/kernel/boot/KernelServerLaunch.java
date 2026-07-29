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
 * The dedicated-server boot entry (BOOT side). Thin wrapper over {@link KernelBoot}; the whole flow — sovereign
 * {@link net.forbric.kernel.classloading.ForbricClassLoader}, transform pipeline, tri-ecosystem discovery, Mixin,
 * native registration window — is shared with the client so the two sides cannot drift.
 *
 * <p>Owned (transform-loaded) jars are passed with {@code --gameJar} / {@code --runtimeJar}; MC libraries with
 * {@code --libraryPath} (also owned). Everything after {@code --} is forwarded to {@code net.minecraft.server.Main}.
 */
public final class KernelServerLaunch {
	private KernelServerLaunch() {
	}

	public static void main(String[] args) throws Throwable {
		KernelBoot.launch(KernelBoot.Side.SERVER, args);
	}
}
