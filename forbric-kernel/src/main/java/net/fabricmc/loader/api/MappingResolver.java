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

import java.util.Collection;

/**
 * Mapping resolution helper. The target namespace is always the one the loader currently runs in — for the
 * Forbric kernel that is {@code named} (Mojmap), because the merged base is Mojmap-native.
 */
public interface MappingResolver {
	Collection<String> getNamespaces();

	String getCurrentRuntimeNamespace();

	String mapClassName(String namespace, String className);

	String unmapClassName(String targetNamespace, String className);

	String mapFieldName(String namespace, String owner, String name, String descriptor);

	String mapMethodName(String namespace, String owner, String name, String descriptor);
}
