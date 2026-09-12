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

package net.forbric.kernel.mixin;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.spongepowered.asm.service.IGlobalPropertyService;
import org.spongepowered.asm.service.IPropertyKey;

/** Mixin's cross-environment blackboard. A plain map: the kernel is the only Mixin host in this JVM. */
public final class ForbricGlobalPropertyService implements IGlobalPropertyService {
	private final Map<String, Object> values = new ConcurrentHashMap<>();

	@Override
	public IPropertyKey resolveKey(String name) {
		return new Key(name);
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T> T getProperty(IPropertyKey key) {
		return (T) values.get(key.toString());
	}

	@Override
	public void setProperty(IPropertyKey key, Object value) {
		if (value == null) {
			values.remove(key.toString());
		} else {
			values.put(key.toString(), value);
		}
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T> T getProperty(IPropertyKey key, T defaultValue) {
		Object value = values.get(key.toString());
		return value != null ? (T) value : defaultValue;
	}

	@Override
	public String getPropertyString(IPropertyKey key, String defaultValue) {
		Object value = values.get(key.toString());
		return value != null ? value.toString() : defaultValue;
	}

	/** Keys compare by name so a key resolved twice addresses the same slot. */
	private static final class Key implements IPropertyKey {
		private final String name;

		Key(String name) {
			this.name = name;
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof Key && name.equals(((Key) o).name);
		}

		@Override
		public int hashCode() {
			return name.hashCode();
		}

		@Override
		public String toString() {
			return name;
		}
	}
}
