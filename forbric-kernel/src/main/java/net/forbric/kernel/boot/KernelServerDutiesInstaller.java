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

import java.util.ArrayList;
import java.util.List;

import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.ForbricLog;

/**
 * Puts the kernel's own server duties on NeoForge's game bus: the boot half of the game-side {@code KernelServerDuties}.
 *
 * <p>Each of these used to be installed from MinecraftForge-gated code, and so went wherever that code went.
 * {@link GameEventMultiplexer#install} probes {@code net.minecraftforge.event.ForgeEventFactory} before it installs anything,
 * and returns at once under {@code -Dforbric.unifiedEvents=off}; the game instance, the server-started work and (inside the
 * MinecraftForge break bridge) the experience relay were installed after that probe, so a base without MinecraftForge — the
 * genuine NeoForge base Stage 4 switches to, measured — and the switch both dropped them without a word. None of them forwards
 * anything to MinecraftForge. This installs them unconditionally, each on its own, so one that fails costs only itself.
 *
 * <p>Called from {@code KernelLifecycle.startGameBuses} right after the bridges and before the buses start. After them,
 * because a NeoForge bus runs the listeners of one priority in the order they were added: the started work registered here
 * must still run after MinecraftForge's own started duties (the login gate, the dead-event judgement whose findings the load
 * report carries), and each listener here keeps the place it had among the kernel's listeners on its event. The data-map watch
 * came last before and still does.
 */
public final class KernelServerDutiesInstaller {
	/** The duties, in the order they go on the bus; what {@link #install} answers when all of them did. */
	static final List<String> DUTIES = List.of("game-instance", "started-duties", "experience-relay", "data-map-watch");

	private KernelServerDutiesInstaller() {
	}

	/**
	 * Installs every duty on NeoForge's game bus, whatever else is on the classpath and whatever {@code -Dforbric.unifiedEvents}
	 * says.
	 *
	 * @return the duties that installed, in {@link #DUTIES} order; none when the game has no NeoForge bus
	 */
	public static List<String> install(ClassLoader cl) {
		Object neoBus;
		try {
			neoBus = Class.forName("net.neoforged.neoforge.common.NeoForge", false, cl).getField("EVENT_BUS").get(null);
		} catch (ClassNotFoundException absent) {
			// No NeoForge bus — nothing for these to go on, and no NeoForge event that would carry them.
			Caught.expected(absent);
			return List.of();
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Duties] could not read NeoForge's game bus — the kernel's own server duties are not "
					+ "installed: no Fabric game instance on a dedicated server, no load report or compatibility decision once a "
					+ "world is up, no relay of Block.tryDropExperience, no data-map watch");
			return List.of();
		}
		List<String> installed = new ArrayList<>();
		try {
			Class.forName("net.forbric.kernel.runtime.KernelServerDuties", true, cl).getMethod("installGameInstance", Object.class)
					.invoke(null, neoBus);
			installed.add("game-instance");
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/EventMux] the dedicated server will not be Fabric Loader's game instance — "
					+ "FabricLoader.getGameInstance() stays null there");
		}
		try {
			Class.forName("net.forbric.kernel.runtime.KernelServerDuties", true, cl).getMethod("installStartedDuties", Object.class)
					.invoke(null, neoBus);
			installed.add("started-duties");
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/EventMux] the kernel's server-started work did not install — no load report or "
					+ "compatibility decision once a world is up");
		}
		try {
			Class.forName("net.forbric.kernel.runtime.KernelServerDuties", true, cl).getMethod("installExperienceRelay", Object.class)
					.invoke(null, neoBus);
			installed.add("experience-relay");
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Duties] the relay of Block.tryDropExperience did not install — a Fabric mod's hooks in it "
					+ "never run, and NeoForge's experience stands for every break");
		}
		if (installDataMapWatch(cl, neoBus)) installed.add("data-map-watch");
		ForbricLog.debug("[Forbric/Duties] installed %d of %d kernel server duties: %s", installed.size(), DUTIES.size(),
				String.join(", ", installed));
		return List.copyOf(installed);
	}

	/**
	 * Watches NeoForge's own data-map reload path (registration, apply per reload, dispatch) so the kernel's about-to-start
	 * fallback can stand down when it ran. It forwards nothing; it lived in {@link GameEventMultiplexer}, and moved here with
	 * the other duties that are not about MinecraftForge.
	 */
	private static boolean installDataMapWatch(ClassLoader cl, Object neoBus) {
		try {
			Class.forName("net.forbric.kernel.runtime.KernelNeoDataMapWatch", true, cl)
					.getMethod("installDataMapWatch", Object.class).invoke(null, neoBus);
			ForbricLog.debug("[Forbric/EventMux] watching NeoForge's data-map reload path");
			return true;
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/EventMux] could not watch NeoForge's data-map reload path — the kernel's fallback "
					+ "will load the data maps as before");
			return false;
		}
	}
}
