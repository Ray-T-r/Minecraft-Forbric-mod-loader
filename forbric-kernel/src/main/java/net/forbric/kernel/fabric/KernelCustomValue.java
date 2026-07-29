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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.electronwill.nightconfig.core.UnmodifiableConfig;

import net.fabricmc.loader.api.metadata.CustomValue;

/**
 * The kernel's {@link CustomValue} tree, built from the night-config JSON model that reads
 * {@code fabric.mod.json}.
 *
 * <p>Mods read their own (and each other's) {@code "custom"} block through this — e.g. fabric-api's modules tag
 * themselves {@code "fabric-api:module-lifecycle": "stable"}, and Jade/ModMenu look up custom keys by name.
 */
public abstract class KernelCustomValue implements CustomValue {
	/** Converts a parsed night-config JSON value into the CustomValue tree. */
	public static CustomValue of(Object value) {
		if (value == null) return NullValue.INSTANCE;
		if (value instanceof UnmodifiableConfig) return objectOf((UnmodifiableConfig) value);
		if (value instanceof Map) return objectOf((Map<?, ?>) value);
		if (value instanceof List) return arrayOf((List<?>) value);
		if (value instanceof String) return new StringValue((String) value);
		if (value instanceof Number) return new NumberValue((Number) value);
		if (value instanceof Boolean) return new BooleanValue((Boolean) value);

		// Unknown scalar shapes degrade to their string form rather than failing the whole mod's metadata.
		return new StringValue(value.toString());
	}

	private static CvObject objectOf(UnmodifiableConfig config) {
		Map<String, CustomValue> entries = new LinkedHashMap<>();

		for (UnmodifiableConfig.Entry e : config.entrySet()) {
			entries.put(e.getKey(), of(e.getValue()));
		}

		return new ObjectValue(entries);
	}

	private static CvObject objectOf(Map<?, ?> map) {
		Map<String, CustomValue> entries = new LinkedHashMap<>();

		for (Map.Entry<?, ?> e : map.entrySet()) {
			entries.put(String.valueOf(e.getKey()), of(e.getValue()));
		}

		return new ObjectValue(entries);
	}

	private static CvArray arrayOf(List<?> list) {
		List<CustomValue> values = new ArrayList<>(list.size());

		for (Object o : list) {
			values.add(of(o));
		}

		return new ArrayValue(values);
	}

	@Override
	public CvObject getAsObject() {
		throw new ClassCastException("can't convert " + getType() + " to object");
	}

	@Override
	public CvArray getAsArray() {
		throw new ClassCastException("can't convert " + getType() + " to array");
	}

	@Override
	public String getAsString() {
		throw new ClassCastException("can't convert " + getType() + " to string");
	}

	@Override
	public Number getAsNumber() {
		throw new ClassCastException("can't convert " + getType() + " to number");
	}

	@Override
	public boolean getAsBoolean() {
		throw new ClassCastException("can't convert " + getType() + " to boolean");
	}

	static final class ObjectValue extends KernelCustomValue implements CvObject {
		private final Map<String, CustomValue> entries;

		ObjectValue(Map<String, CustomValue> entries) {
			this.entries = Collections.unmodifiableMap(entries);
		}

		@Override
		public CvType getType() {
			return CvType.OBJECT;
		}

		@Override
		public CvObject getAsObject() {
			return this;
		}

		@Override
		public int size() {
			return entries.size();
		}

		@Override
		public boolean containsKey(String key) {
			return entries.containsKey(key);
		}

		@Override
		public CustomValue get(String key) {
			return entries.get(key);
		}

		@Override
		public Iterator<Map.Entry<String, CustomValue>> iterator() {
			return entries.entrySet().iterator();
		}
	}

	static final class ArrayValue extends KernelCustomValue implements CvArray {
		private final List<CustomValue> values;

		ArrayValue(List<CustomValue> values) {
			this.values = Collections.unmodifiableList(values);
		}

		@Override
		public CvType getType() {
			return CvType.ARRAY;
		}

		@Override
		public CvArray getAsArray() {
			return this;
		}

		@Override
		public int size() {
			return values.size();
		}

		@Override
		public CustomValue get(int index) {
			return values.get(index);
		}

		@Override
		public Iterator<CustomValue> iterator() {
			return values.iterator();
		}
	}

	static final class StringValue extends KernelCustomValue {
		private final String value;

		StringValue(String value) {
			this.value = value;
		}

		@Override
		public CvType getType() {
			return CvType.STRING;
		}

		@Override
		public String getAsString() {
			return value;
		}
	}

	static final class NumberValue extends KernelCustomValue {
		private final Number value;

		NumberValue(Number value) {
			this.value = value;
		}

		@Override
		public CvType getType() {
			return CvType.NUMBER;
		}

		@Override
		public Number getAsNumber() {
			return value;
		}
	}

	static final class BooleanValue extends KernelCustomValue {
		private final boolean value;

		BooleanValue(boolean value) {
			this.value = value;
		}

		@Override
		public CvType getType() {
			return CvType.BOOLEAN;
		}

		@Override
		public boolean getAsBoolean() {
			return value;
		}
	}

	static final class NullValue extends KernelCustomValue {
		static final NullValue INSTANCE = new NullValue();

		@Override
		public CvType getType() {
			return CvType.NULL;
		}
	}
}
