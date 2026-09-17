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

package net.fabricmc.loader.api.metadata.version;

import net.fabricmc.loader.api.Version;

/**
 * A bounded version range, as {@link VersionPredicate#getInterval()} would describe one.
 *
 * <p>Present so a mod that NAMES the type — in a field, a cast or a method signature — links. The kernel's
 * predicate answers {@code null} for its interval rather than inventing bounds, so the only instance a mod can
 * hold from this API is {@link #INFINITE}, which is the honest one: it admits everything.
 */
public interface VersionInterval {
	/** Admits every version; both bounds absent. */
	VersionInterval INFINITE = new VersionInterval() {
		@Override
		public boolean isSemantic() {
			return false;
		}

		@Override
		public Version getMin() {
			return null;
		}

		@Override
		public boolean isMinInclusive() {
			return false;
		}

		@Override
		public Version getMax() {
			return null;
		}

		@Override
		public boolean isMaxInclusive() {
			return false;
		}

		@Override
		public String toString() {
			return "(-∞,∞)";
		}
	};

	boolean isSemantic();

	Version getMin();

	boolean isMinInclusive();

	Version getMax();

	boolean isMaxInclusive();
}
