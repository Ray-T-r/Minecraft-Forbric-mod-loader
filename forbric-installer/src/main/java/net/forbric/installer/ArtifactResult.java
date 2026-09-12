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

package net.forbric.installer;

import java.nio.file.Path;

/**
 * The result of an install-time build: a jar on disk plus the metadata the version-profile writer needs. The two
 * built categories ({@code patched-mc}, {@code forge-runtime}) arrive in the manifest as placeholders with null
 * {@code file}/{@code sha1}; {@link Installer} fills them in from these results, keyed by coordinate.
 */
final class ArtifactResult {
	final String coordinate;
	final Path file;
	final String sha1;
	final long size;

	ArtifactResult(String coordinate, Path file, String sha1, long size) {
		this.coordinate = coordinate;
		this.file = file;
		this.sha1 = sha1;
		this.size = size;
	}
}
