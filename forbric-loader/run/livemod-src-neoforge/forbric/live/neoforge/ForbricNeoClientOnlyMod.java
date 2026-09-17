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

package forbric.live.neoforge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

/**
 * A NeoForge {@code @Mod} that declares it belongs to the CLIENT only.
 *
 * <p>Its whole job is to be constructed on one side and not the other. The kernel used to ignore the annotation's
 * {@code dist} and construct every {@code @Mod} on every side; Sodium's client-only entry point is the real case,
 * and on a dedicated server the first client type its constructor touches throws with the mod's name on it.
 */
@Mod(value = "forbricneoclientonly", dist = {Dist.CLIENT})
public class ForbricNeoClientOnlyMod {
	public ForbricNeoClientOnlyMod(IEventBus modBus) {
		System.out.println("[ForbricNeoClientOnly] client-only @Mod CONSTRUCTED");
	}
}
