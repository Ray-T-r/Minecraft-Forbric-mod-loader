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

import java.util.List;
import java.util.Objects;

/** Transfers captured Forge listeners through the synchronous NeoForge registration dispatch. */
public final class ForgeClientReloadCapture {

	private static final ThreadLocal<Capture<?>> CURRENT = new ThreadLocal<>();

	private ForgeClientReloadCapture() {
	}

	/**
	 * Makes a snapshot available to {@link #drain()} for the duration of {@code dispatch}.
	 * An enclosing capture is restored even if dispatch fails; exceptions propagate unchanged.
	 *
	 * @return whether this capture was drained before dispatch completed successfully
	 */
	public static <T> boolean withCaptured(List<T> listeners, Runnable dispatch) {
		Objects.requireNonNull(dispatch, "dispatch");
		Capture<T> capture = new Capture<>(List.copyOf(listeners));
		Capture<?> previous = CURRENT.get();
		CURRENT.set(capture);
		try {
			dispatch.run();
			return capture.drained;
		} finally {
			if (previous == null) CURRENT.remove();
			else CURRENT.set(previous);
		}
	}

	/**
	 * Returns the current snapshot once, then an empty list on later calls in the same scope.
	 * {@code null} means there is no capture; an empty capture is still consumed and returns a non-null list.
	 * The caller must request the same listener type supplied to {@link #withCaptured(List, Runnable)}.
	 */
	@SuppressWarnings("unchecked")
	public static <T> List<T> drain() {
		Capture<?> capture = CURRENT.get();
		if (capture == null) return null;
		if (capture.drained) return List.of();
		capture.drained = true;
		return (List<T>) capture.listeners;
	}

	private static final class Capture<T> {
		private final List<T> listeners;
		private boolean drained;

		private Capture(List<T> listeners) {
			this.listeners = listeners;
		}
	}
}
