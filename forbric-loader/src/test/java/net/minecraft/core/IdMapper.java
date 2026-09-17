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

package net.minecraft.core;

import java.util.LinkedHashMap;
import java.util.Map;

public class IdMapper<T> {
	private final Map<T, Integer> ids = new LinkedHashMap<>();

	public int getId(Object value) {
		return ids.getOrDefault(value, -1);
	}

	public void add(Object value) {
		@SuppressWarnings("unchecked")
		T cast = (T) value;
		ids.put(cast, ids.size());
	}

	public void clear() {
		ids.clear();
	}

	public int size() {
		return ids.size();
	}
}
