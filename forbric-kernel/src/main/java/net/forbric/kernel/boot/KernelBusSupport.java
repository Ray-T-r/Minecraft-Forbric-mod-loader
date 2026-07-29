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

import java.lang.reflect.InvocationTargetException;

/** Shared reflective helpers for the kernel's native ecosystem-event plumbing. */
final class KernelBusSupport {
	private KernelBusSupport() {
	}

	/**
	 * Builds a NeoForge mod-event bus exactly as the genuine {@code FMLModContainer} does:
	 * {@code BusBuilder.builder().markerType(IModBusEvent).allowPerPhasePost().build()}. The
	 * {@code allowPerPhasePost()} is load-bearing on the client: phase-specific mod-bus events such as
	 * {@code RegisterKeyMappingsEvent} are dispatched via the {@code post(EventPriority, Event)} variant, which a bus
	 * without that option rejects with {@code IllegalStateException: This bus does not allow calling phase-specific
	 * post} (caught empirically in {@code Options.<init>} → {@code ClientHooks.onRegisterKeyMappings}).
	 */
	static Object makeModBus(ClassLoader cl) throws Exception {
		Class<?> busBuilder = Class.forName("net.neoforged.bus.api.BusBuilder", false, cl);
		Object builder = busBuilder.getMethod("builder").invoke(null);
		try {
			Class<?> modBusEvent = Class.forName("net.neoforged.fml.event.IModBusEvent", false, cl);
			builder = busBuilder.getMethod("markerType", Class.class).invoke(builder, modBusEvent);
		} catch (Throwable ignored) {
			// markerType is best-effort
		}
		try {
			builder = busBuilder.getMethod("allowPerPhasePost").invoke(builder);
		} catch (Throwable ignored) {
			// allowPerPhasePost is best-effort (older bus APIs lack it)
		}
		return builder.getClass().getMethod("build").invoke(builder);
	}

	static Throwable unwrap(Throwable t) {
		return t instanceof InvocationTargetException && t.getCause() != null ? t.getCause() : t;
	}
}
