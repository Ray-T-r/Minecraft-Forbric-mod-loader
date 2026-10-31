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
 * <p>Each of these used to be installed from MinecraftForge-gated code (the NeoForge-to-MinecraftForge forward pass, which
 * probed for MinecraftForge before it installed anything), so a base without MinecraftForge — the genuine NeoForge base,
 * measured — dropped them without a word. None of them is about MinecraftForge. This installs them unconditionally, each on
 * its own, so one that fails costs only itself.
 *
 * <p>Called from {@code KernelLifecycle.startGameBuses} right before NeoForge's game bus starts, so every duty is on the bus
 * before the first game event dispatches. The data-map watch goes on last.
 *
 * <p>Its own lines say {@code [Forbric/Duties]}. No gate or test greps the duties' lines under that prefix or the old
 * {@code [Forbric/EventMux]} one (only failure and debug lines, checked against {@code run/*.sh}, {@code run/compat} and the
 * unit tests). The runtime warnings in {@code KernelServerDuties} keep the words they had, prefix included.
 */
public final class KernelServerDutiesInstaller {
	/** The duties, in the order they go on the bus; what {@link #install} answers when all of them did. */
	static final List<String> DUTIES = List.of("game-instance", "started-duties", "experience-relay", "data-map-watch");

	private KernelServerDutiesInstaller() {
	}

	/**
	 * Installs every duty on NeoForge's game bus, whatever else is on the classpath.
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
			Caught.warn(t, "[Forbric/Duties] the dedicated server will not be Fabric Loader's game instance — "
					+ "FabricLoader.getGameInstance() stays null there");
		}
		try {
			Class.forName("net.forbric.kernel.runtime.KernelServerDuties", true, cl).getMethod("installStartedDuties", Object.class)
					.invoke(null, neoBus);
			installed.add("started-duties");
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Duties] the kernel's server-started work did not install — no load report or "
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
	 * fallback can stand down when it ran. It forwards nothing.
	 */
	private static boolean installDataMapWatch(ClassLoader cl, Object neoBus) {
		try {
			Class.forName("net.forbric.kernel.runtime.KernelNeoDataMapWatch", true, cl)
					.getMethod("installDataMapWatch", Object.class).invoke(null, neoBus);
			ForbricLog.debug("[Forbric/Duties] watching NeoForge's data-map reload path");
			return true;
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Duties] could not watch NeoForge's data-map reload path — the kernel's fallback "
					+ "will load the data maps as before");
			return false;
		}
	}
}
