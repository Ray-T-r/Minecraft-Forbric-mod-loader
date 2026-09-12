/*
 * Copyright 2016 FabricMC
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

package net.fabricmc.loader.api.entrypoint;

/**
 * Entrypoint getting invoked just before launching the game.
 *
 * <p>Declared under the {@code preLaunch} key. It runs before the game's classes are widely loaded, so it must not
 * touch game classes.
 */
@FunctionalInterface
public interface PreLaunchEntrypoint {
	void onPreLaunch();
}
