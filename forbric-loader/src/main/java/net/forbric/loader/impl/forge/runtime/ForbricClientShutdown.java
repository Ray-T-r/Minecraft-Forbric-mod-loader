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

package net.forbric.loader.impl.forge.runtime;

import net.forbric.loader.impl.util.ForbricLog;

/**
 * Shuts down non-daemon background executors that Forge/NeoForge's FML leaves running, so a merged (or
 * single-Forge) client can EXIT the JVM naturally at the end of {@code Minecraft.close()} instead of
 * lingering until vanilla's 15-second {@code ClientShutdownWatchdog} fires a "Client shutdown from
 * post-main" crash.
 *
 * <p>Why these leak on Forbric: FML normally closes these in its own shutdown path, but the merged
 * lifecycle drives FML's start reflectively and deliberately does NOT run FML's full shutdown (see the
 * "handleServerStopped not called wholesale" note in the client world-join work), so nothing closes them.
 *
 * <p>Identified leaker (via JFR {@code jdk.ThreadStart}): nightconfig's config file-watcher
 * ({@code com.electronwill.nightconfig.core.file.FileWatcher$FsWatcher} → {@code DebouncedRunnable} →
 * {@code Executors.newScheduledThreadPool(1)} with NO thread factory ⇒ a non-daemon
 * {@code ScheduledThreadPoolExecutor} that parks forever on {@code DelayedWorkQueue.take}). Forge's config
 * system uses the {@code FileWatcher.defaultInstance()} singleton; {@code stop()} shuts down both its watch
 * thread and that scheduler.
 *
 * <p>Reflection-only (no compile/runtime dep on nightconfig or the game); a no-op where the class is absent
 * (e.g. a pure-Fabric base). Idempotent.
 */
public final class ForbricClientShutdown {
	private static volatile boolean ran;

	private ForbricClientShutdown() {
	}

	/** Called at the TAIL of {@code Minecraft.close()} (main thread, before {@code startShutdownWatchdog}). */
	public static void stopLeakedBackgroundExecutors(ClassLoader cl) {
		if (ran) return;
		ran = true;
		stopNightconfigFileWatcher(cl);
	}

	private static void stopNightconfigFileWatcher(ClassLoader cl) {
		Class<?> fileWatcher;
		try {
			// false = don't initialize; if the default instance was never created, static holder stays null
			// and defaultInstance() below would create+immediately-stop one (harmless), so gate on the field.
			fileWatcher = Class.forName("com.electronwill.nightconfig.core.file.FileWatcher", false, cl);
		} catch (ClassNotFoundException noForgeConfigSystem) {
			return; // pure-Fabric / non-Forge base — nothing to close
		}
		try {
			java.lang.reflect.Field defaultField = null;
			for (java.lang.reflect.Field f : fileWatcher.getDeclaredFields()) {
				if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) && f.getType() == fileWatcher) {
					defaultField = f; // the "DEFAULT_INSTANCE" singleton holder, whatever its exact name
					break;
				}
			}
			Object instance;
			if (defaultField != null) {
				defaultField.setAccessible(true);
				instance = defaultField.get(null);
				if (instance == null) return; // never created ⇒ no watcher thread/executor exists ⇒ nothing to leak
			} else {
				// Fallback: no discoverable holder field — ask for the singleton (may create one; stop() cleans it).
				instance = fileWatcher.getMethod("defaultInstance").invoke(null);
				if (instance == null) return;
			}
			fileWatcher.getMethod("stop").invoke(instance);
			ForbricLog.info("[Forbric/Shutdown] stopped nightconfig config file-watcher "
					+ "(leaked non-daemon executor) so the JVM can exit without the shutdown watchdog");
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Shutdown] could not stop nightconfig file-watcher; "
					+ "JVM exit may stall until the shutdown watchdog", t);
		}
	}
}
