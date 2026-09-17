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

package net.forbric.kernel.fabric;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Covers {@link KernelFabricLoader#sanitized} — what a mod gets when it asks for SANITISED launch arguments.
 *
 * <p>The flag was accepted and ignored, so the real arguments came back including {@code --accessToken}, which is
 * a live session credential. A mod asks for the sanitised form precisely when it is about to put them somewhere
 * that leaves the machine: a crash report, a debug dump, an uploaded log.
 */
class KernelFabricLoaderLaunchArgumentsTest {

	@Test
	void removesTheCredentialsAndTheValuesBehindThem() {
		String[] sanitized = KernelFabricLoader.sanitized(new String[] {
				"--username", "Jerry", "--accessToken", "eyJhbGciOi.secret", "--version", "26.2-forbric",
				"--uuid", "0d1e2f", "--xuid", "991122", "--gameDir", "."});

		assertArrayEquals(new String[] {"--version", "26.2-forbric", "--gameDir", "."}, sanitized);
		assertFalse(List.of(sanitized).contains("eyJhbGciOi.secret"),
				"the token's VALUE is the part that must not survive");
	}

	@Test
	void keepsArgumentsItDoesNotRecognise() {
		// The caller asked for the launch arguments, not for a whitelist. Dropping something unrecognised would
		// quietly change what a mod sees.
		String[] sanitized = KernelFabricLoader.sanitized(new String[] {"--someFutureFlag", "x", "--demo"});

		assertArrayEquals(new String[] {"--someFutureFlag", "x", "--demo"}, sanitized);
	}

	@Test
	void aCredentialFlagAtTheVeryEndDoesNotRunOffTheArray() {
		String[] sanitized = KernelFabricLoader.sanitized(new String[] {"--version", "26.2", "--accessToken"});

		assertArrayEquals(new String[] {"--version", "26.2"}, sanitized);
	}

	@Test
	void anEmptyLaunchStaysEmpty() {
		assertTrue(KernelFabricLoader.sanitized(new String[0]).length == 0);
	}

	@Test
	void removesEveryOccurrence() {
		// A launcher that passes a flag twice must not leave the second one behind.
		String[] sanitized = KernelFabricLoader.sanitized(new String[] {
				"--accessToken", "a", "--accessToken", "b", "--width", "854"});

		assertArrayEquals(new String[] {"--width", "854"}, sanitized);
	}
}
