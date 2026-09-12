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

package net.fabricmc.loader.api.metadata;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import net.fabricmc.loader.api.Version;

/** The metadata of a mod. Fabric Loader ABI (Apache-2.0, Copyright FabricMC). */
public interface ModMetadata {
	/** {@code fabric} or {@code builtin}. */
	String getType();

	String getId();

	Collection<String> getProvides();

	Version getVersion();

	ModEnvironment getEnvironment();

	Collection<ModDependency> getDependencies();

	/** @deprecated filter {@link #getDependencies()} by {@link ModDependency.Kind#DEPENDS} */
	@Deprecated
	default Collection<ModDependency> getDepends() {
		return getDependencies().stream().filter(d -> d.getKind() == ModDependency.Kind.DEPENDS).collect(Collectors.toList());
	}

	/** @deprecated filter {@link #getDependencies()} by {@link ModDependency.Kind#RECOMMENDS} */
	@Deprecated
	default Collection<ModDependency> getRecommends() {
		return getDependencies().stream().filter(d -> d.getKind() == ModDependency.Kind.RECOMMENDS).collect(Collectors.toList());
	}

	/** @deprecated filter {@link #getDependencies()} by {@link ModDependency.Kind#SUGGESTS} */
	@Deprecated
	default Collection<ModDependency> getSuggests() {
		return getDependencies().stream().filter(d -> d.getKind() == ModDependency.Kind.SUGGESTS).collect(Collectors.toList());
	}

	/** @deprecated filter {@link #getDependencies()} by {@link ModDependency.Kind#CONFLICTS} */
	@Deprecated
	default Collection<ModDependency> getConflicts() {
		return getDependencies().stream().filter(d -> d.getKind() == ModDependency.Kind.CONFLICTS).collect(Collectors.toList());
	}

	/** @deprecated filter {@link #getDependencies()} by {@link ModDependency.Kind#BREAKS} */
	@Deprecated
	default Collection<ModDependency> getBreaks() {
		return getDependencies().stream().filter(d -> d.getKind() == ModDependency.Kind.BREAKS).collect(Collectors.toList());
	}

	String getName();

	String getDescription();

	Collection<Person> getAuthors();

	Collection<Person> getContributors();

	ContactInformation getContact();

	Collection<String> getLicense();

	Optional<String> getIconPath(int size);

	boolean containsCustomValue(String key);

	CustomValue getCustomValue(String key);

	Map<String, CustomValue> getCustomValues();

	/** @deprecated use {@link #containsCustomValue} */
	@Deprecated
	boolean containsCustomElement(String key);
}
