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

package net.forbric.kernel.runtime;

import java.util.function.Consumer;
import java.util.function.Supplier;

/** Game-independent decisions used by the typed client-consumer funnel. */
public final class ForgeClientConsumerFlow {
	private ForgeClientConsumerFlow() {}

	/** The init negative control must never consult Forge manager tables which were not initialized. */
	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty("forbric.forgeClientConsumers"))
				&& !"off".equalsIgnoreCase(System.getProperty("forbric.forgeClientInit"));
	}

	/** Both families contribute to the caller's very same builder or cache map, in that order. */
	public static <T> void appendBoth(T target, Consumer<T> neo, Consumer<T> forge) {
		neo.accept(target);
		forge.accept(target);
	}

	/** Neo's missing factory returns null; Forge's own missing/null-result factory throws a known sentinel. */
	public static <T> T tooltip(Supplier<T> neo, Supplier<T> forge) {
		T primary = neo.get();
		if (primary != null) return primary;
		try {
			return forge.get();
		} catch (IllegalArgumentException failure) {
			StackTraceElement[] frames = failure.getStackTrace();
			// Checking the throw site as well as the text preserves a mod factory's own IAE, including one
			// with identical text. Other linkage, initialization and factory exceptions always propagate.
			if ("Unknown TooltipComponent".equals(failure.getMessage()) && frames.length > 0
					&& frames[0].getClassName().equals("net.minecraftforge.client.gui.ClientTooltipComponentManager")
					&& frames[0].getMethodName().equals("createClientTooltipComponent")) return null;
			throw failure;
		}
	}

	/**
	 * Both managers seed their maps with the same vanilla editor instances. A non-null Neo default therefore
	 * cannot hide a Forge override. Two distinct custom editors cannot both own the same UI: keep Neo's choice
	 * and report the actual conflict, rather than silently treating Forge's registration as a fallback miss.
	 */
	public static <T> T preset(T vanilla, T neo, T forge, Runnable conflict) {
		if (forge == null || neo == forge) return neo;
		if (neo == null || neo == vanilla) return forge;
		if (forge != vanilla) conflict.run();
		return neo;
	}
}
