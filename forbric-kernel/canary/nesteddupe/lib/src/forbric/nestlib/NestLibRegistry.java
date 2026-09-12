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

package forbric.nestlib;

/**
 * The one-shot a real shared library has, reproduced.
 *
 * <p>Xaero's {@code xaerolib} registers a config channel in {@code XaeroLib.<init>} and throws
 * {@code IllegalArgumentException: Attempted to register a duplicate config channel} on a second registration.
 * This is that, in four lines: whichever platform bootstrap runs first claims the library, and a second one is an
 * error. Both nested copies of this library carry this same class, so on a classpath holding both, one copy of it
 * is loaded (first URL wins) and BOTH bootstraps reach it — which is exactly how the real defect showed up.
 */
public final class NestLibRegistry {
	private static volatile String owner;

	private NestLibRegistry() {
	}

	public static void register(String platform) {
		String existing = owner;
		if (existing != null) {
			// Matches the shape of the real failure: it is thrown from the constructor, so the second bootstrap is
			// left half-built while the first one keeps running.
			throw new IllegalStateException("[ForbricNestLib] DUPLICATE registration: already claimed by "
					+ existing + ", now " + platform);
		}
		owner = platform;
		System.out.println("[ForbricNestLib] claimed by " + platform);
	}
}
