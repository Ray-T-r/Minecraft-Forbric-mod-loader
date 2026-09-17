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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import net.forbric.api.ForeignType;

/**
 * The boot-side door to the traditional-Forge setup phases, on an instance that has no MinecraftForge mods.
 *
 * <h2>What this protects</h2>
 *
 * <p>The phase itself is game-side now, in a class that names six MinecraftForge event types. On a
 * NeoForge-only instance those types are absent, so merely NAMING that class is a {@code NoClassDefFoundError} —
 * not the quiet "this carrier has no traditional Forge" the caller handles. The door therefore has to answer
 * "nobody to post to" BEFORE it resolves anything, and the order of those two lines is the whole guarantee.
 *
 * <p>Nothing in the compiler relates them: moving the empty check below the resolution compiles, passes every
 * test that runs with MinecraftForge present, and breaks only the instances that do not have it — which are the
 * ones nobody develops on. So the assertion is made against a loader that reports what it was ASKED for, rather
 * than against a return value that would be 0 either way.
 */
class KernelForgeSetupPhaseDoorTest {

	/** A loader that records every name asked of it and refuses the game side, as a Neo-only instance would. */
	private static final class Watcher extends ClassLoader {
		private final List<String> asked = new CopyOnWriteArrayList<>();

		Watcher() {
			super(Watcher.class.getClassLoader());
		}

		@Override
		public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			asked.add(name);
			if (name.startsWith("net.forbric.kernel.runtime.")) {
				throw new ClassNotFoundException(name + " — as on an instance with no MinecraftForge carrier");
			}
			return super.loadClass(name, resolve);
		}
	}

	@Test
	void withNoMinecraftForgeModsTheGameSideClassIsNeverNamed() throws Exception {
		Watcher loader = new Watcher();

		int fired = KernelForgeModContext.fireSetupPhase(
				loader, List.of(), ForeignType.FML_COMMON_SETUP_EVENT, "common setup");

		assertEquals(0, fired, "there was nobody to post to");
		assertTrue(loader.asked.stream().noneMatch(n -> n.startsWith("net.forbric.kernel.runtime.")),
				"the door resolved a game-side class before checking whether any mod wanted the event. On a "
						+ "NeoForge-only instance that is a NoClassDefFoundError out of a class naming six "
						+ "MinecraftForge event types, not the absent-carrier path. Asked for: " + loader.asked);
	}

	@Test
	void everyPhaseTheLifecyclePostsHasTheSameGuard() throws Exception {
		// The guard belongs to the door, not to one event, and a future phase added without it would be just as
		// invisible. All six the lifecycle posts, so the claim is about the door rather than about one caller.
		List<ForeignType> phases = List.of(
				ForeignType.FML_COMMON_SETUP_EVENT,
				ForeignType.FML_CLIENT_SETUP_EVENT,
				ForeignType.FML_DEDICATED_SERVER_SETUP_EVENT,
				ForeignType.INTER_MOD_ENQUEUE_EVENT,
				ForeignType.INTER_MOD_PROCESS_EVENT,
				ForeignType.FML_LOAD_COMPLETE_EVENT);

		for (ForeignType phase : phases) {
			Watcher loader = new Watcher();
			assertEquals(0, KernelForgeModContext.fireSetupPhase(loader, List.of(), phase, "x"));
			assertTrue(loader.asked.stream().noneMatch(n -> n.startsWith("net.forbric.kernel.runtime.")),
					phase + " resolved the game side with no mods to post to");
		}
	}
}
