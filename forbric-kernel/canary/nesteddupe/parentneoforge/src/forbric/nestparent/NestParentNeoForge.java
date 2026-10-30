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

package forbric.nestparent;

import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;

/**
 * The NeoForge parent: JarJar-nests the NeoForge build of {@code forbricnestlib}, as the MinecraftForge parent nests
 * the Forge build, and asks its own loader whether the library is there.
 *
 * <p>Asked from the mod, not read off the arbiter's log: when the Fabric build wins, the NeoForge side keeps seeing the
 * library only through the cross-ecosystem presence answer, and that answer is what a real parent branches on.
 */
@Mod("forbricnestneo")
public final class NestParentNeoForge {
	public NestParentNeoForge() {
		System.out.println("[ForbricNestParent] neoforge parent up");
		boolean visible;
		try {
			visible = ModList.get().isLoaded("forbricnestlib");
		} catch (Throwable t) {
			visible = false;
			System.out.println("[ForbricNestParent] neoforge isLoaded threw: " + t);
		}
		System.out.println("[ForbricNestParent] neoforge sees forbricnestlib=" + visible);
	}
}
