/*
 * Copyright 2016 FabricMC
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

package net.fabricmc.loader.api;

import java.util.Optional;

/** A SemVer-shaped {@link Version}. Forbric deviation: {@link #parse} routes to the kernel's parser. */
public interface SemanticVersion extends Version {
	int COMPONENT_WILDCARD = Integer.MIN_VALUE;

	int getVersionComponentCount();

	int getVersionComponent(int pos);

	Optional<String> getPrereleaseKey();

	Optional<String> getBuildKey();

	boolean hasWildcard();

	/** @deprecated use {@link #compareTo(Version)} */
	@Deprecated
	default int compareTo(SemanticVersion o) {
		return compareTo((Version) o);
	}

	static SemanticVersion parse(String s) throws VersionParsingException {
		return net.forbric.kernel.fabric.KernelVersion.parseSemantic(s);
	}
}
