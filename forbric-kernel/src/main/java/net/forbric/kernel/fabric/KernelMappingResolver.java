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

import java.util.Collection;
import java.util.List;

import net.fabricmc.loader.api.MappingResolver;

/**
 * The kernel's {@link MappingResolver}.
 *
 * <p>The sovereign kernel runs the merged base in the <b>named</b> (Mojmap) namespace, and modern Fabric mods
 * for this game version ship already compiled against Mojmap too — a constant-pool scan of fabric-api 0.154.0
 * and Jade 26.2.9 finds zero {@code class_}/{@code method_}/{@code field_} intermediary symbols. So no runtime
 * remapping happens and every lookup is the identity.
 *
 * <p>This is deliberately not a stub: mods query the resolver precisely so they need not assume, and returning
 * the input for a query in the runtime namespace is the correct answer, not a fallback. A query naming a
 * namespace the kernel does not carry (e.g. {@code intermediary}) also returns the input — the honest reply
 * when no mapping data is loaded, and the same thing Fabric Loader does for an unmapped member.
 */
public final class KernelMappingResolver implements MappingResolver {
	/**
	 * The namespace the kernel runs the game in: Mojang's own names, which Fabric calls {@code official}.
	 *
	 * <p>This said {@code named}, and that is not what any real instance of this Fabric Loader reports.
	 * {@code javap} on {@code MappingConfiguration} in fabric-loader 0.19.5: the runtime namespace is read from
	 * {@code fabric.runtimeMappingNamespace} and falls back to the literal {@code official}. The kernel runs the
	 * game under Mojang's names, so {@code official} is both the truthful answer and the one a mod compares
	 * against — a mod branching on this was told a namespace that exists nowhere and took the other branch.
	 *
	 * <p>The override property is honoured for the same reason it exists there: an instance that really is
	 * running under another naming can say so.
	 */
	public static final String RUNTIME_NAMESPACE =
			System.getProperty("fabric.runtimeMappingNamespace", "official");

	@Override
	public Collection<String> getNamespaces() {
		return List.of(RUNTIME_NAMESPACE);
	}

	@Override
	public String getCurrentRuntimeNamespace() {
		return RUNTIME_NAMESPACE;
	}

	@Override
	public String mapClassName(String namespace, String className) {
		return className;
	}

	@Override
	public String unmapClassName(String targetNamespace, String className) {
		return className;
	}

	@Override
	public String mapFieldName(String namespace, String owner, String name, String descriptor) {
		return name;
	}

	@Override
	public String mapMethodName(String namespace, String owner, String name, String descriptor) {
		return name;
	}
}
