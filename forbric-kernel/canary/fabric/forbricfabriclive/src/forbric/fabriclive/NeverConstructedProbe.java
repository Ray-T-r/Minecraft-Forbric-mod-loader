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

package forbric.fabriclive;

/**
 * Declared under {@code forbric:probe} but NOT a {@code Runnable}, so nothing ever constructs it.
 *
 * <p>Its only job is its static initialiser. Working out which declarations can satisfy a requested type used to
 * load each candidate class WITH initialisation, so this class's initialiser ran during enumeration even though
 * the entrypoint was never used. A mod that does real work in a static initialiser therefore did it at the wrong
 * moment, and a throw there is permanent: a class initialiser is a one-shot.
 */
public final class NeverConstructedProbe {
	static {
		System.out.println("[ForbricFabricLive] NeverConstructedProbe static initialiser RAN");
	}

	private NeverConstructedProbe() {
	}
}
