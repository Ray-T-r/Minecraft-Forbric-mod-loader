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

package net.forbric.kernel.classloading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.URL;

import org.junit.jupiter.api.Test;

/**
 * Covers {@link ForbricClassLoader#jarUrlOf} — which URL a defined class's code source points at.
 *
 * <p>Classes used to be defined with no protection domain, so every mod's {@code getProtectionDomain()
 * .getCodeSource()} answered null. A mod that ships data beside its own classes reads that to find its own jar,
 * and a null is an NPE inside the mod. The code source has to name the JAR, not the class entry inside it: that
 * is the spelling a caller turns back into a path.
 */
class ForbricClassLoaderCodeSourceTest {

	@Test
	void namesTheJarNotTheEntryInsideIt() throws Exception {
		URL entry = new URL("jar:file:/mods/journeymap-6.0.jar!/journeymap/client/Main.class");

		assertEquals("file:/mods/journeymap-6.0.jar", ForbricClassLoader.jarUrlOf(entry),
				"a caller turns this back into a path; the !/entry suffix would break that");
	}

	@Test
	void keepsANonJarUrlAsItIs() throws Exception {
		// A classes directory rather than a jar — still a real code source, just not an archive.
		URL dir = new URL("file:/build/classes/java/main/");

		assertEquals("file:/build/classes/java/main/", ForbricClassLoader.jarUrlOf(dir));
	}

	@Test
	void hasNoAnswerForNull() {
		// A generated class has no resource it came from, and the caller passes null for exactly that. The
		// "jar: URL with no !/ entry" case the code also guards cannot be built: java.net.URL rejects it at
		// construction with "no !/ in spec", so that branch is belt-and-braces rather than a reachable path.
		assertNull(ForbricClassLoader.jarUrlOf(null));
	}

	@Test
	void handlesASpaceInThePath() throws Exception {
		URL entry = new URL("jar:file:/My%20Mods/spark.jar!/me/lucko/spark/Spark.class");

		assertEquals("file:/My%20Mods/spark.jar", ForbricClassLoader.jarUrlOf(entry));
	}
}
