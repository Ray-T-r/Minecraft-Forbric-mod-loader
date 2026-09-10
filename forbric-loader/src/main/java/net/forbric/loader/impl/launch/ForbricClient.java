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

package net.forbric.loader.impl.launch;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.launch.knot.Knot;

/**
 * Forbric's client entry point. Runs unified discovery (reporting Fabric AND Forge mods), then boots the
 * game on the client side through the reused Fabric substrate (Knot).
 *
 * <p>Use this as the launch {@code mainClass} in place of {@code net.fabricmc.loader.impl.launch.knot.KnotClient}.
 */
public final class ForbricClient {
	private ForbricClient() {
	}

	public static void main(String[] args) {
		ForbricBootstrap.run(args, "client");
		Knot.launch(args, EnvType.CLIENT);
	}
}
