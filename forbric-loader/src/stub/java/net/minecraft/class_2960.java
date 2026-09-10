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

package net.minecraft;

/**
 * Compile-only stub for the intermediary game type {@code class_2960} (Mojmap {@code Identifier} /
 * {@code ResourceLocation}). NOT packaged or on the runtime classpath — at boot, the real game class loaded
 * by Knot provides this name. The fake impl exists only so the registration shim compiles and unit tests can
 * exercise it without a game. The static factory's intermediary name {@code method_60655}
 * ({@code Identifier.fromNamespaceAndPath}) is verified against the 1.21.11 mappings.
 */
public final class class_2960 {
	private final String namespace;
	private final String path;

	private class_2960(String namespace, String path) {
		this.namespace = namespace;
		this.path = path;
	}

	/** {@code Identifier.fromNamespaceAndPath(String, String)}. */
	public static class_2960 method_60655(String namespace, String path) {
		return new class_2960(namespace, path);
	}

	public String method_12836() { // getNamespace()
		return namespace;
	}

	public String method_12832() { // getPath()
		return path;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof class_2960)) return false;
		class_2960 other = (class_2960) o;
		return namespace.equals(other.namespace) && path.equals(other.path);
	}

	@Override
	public int hashCode() {
		return namespace.hashCode() * 31 + path.hashCode();
	}

	@Override
	public String toString() {
		return namespace + ":" + path;
	}
}
