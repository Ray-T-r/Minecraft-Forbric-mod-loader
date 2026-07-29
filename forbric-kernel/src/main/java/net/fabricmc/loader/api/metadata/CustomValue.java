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

import java.util.Map;

/** A custom value in the {@code fabric.mod.json}. Fabric Loader ABI (Apache-2.0, Copyright FabricMC). */
public interface CustomValue {
	CvType getType();

	/** @throws ClassCastException if this value is not an object */
	CvObject getAsObject();

	/** @throws ClassCastException if this value is not an array */
	CvArray getAsArray();

	/** @throws ClassCastException if this value is not a string */
	String getAsString();

	/** @throws ClassCastException if this value is not a number */
	Number getAsNumber();

	/** @throws ClassCastException if this value is not a boolean */
	boolean getAsBoolean();

	interface CvObject extends CustomValue, Iterable<Map.Entry<String, CustomValue>> {
		int size();

		boolean containsKey(String key);

		CustomValue get(String key);
	}

	interface CvArray extends CustomValue, Iterable<CustomValue> {
		int size();

		CustomValue get(int index);
	}

	enum CvType {
		OBJECT, ARRAY, STRING, NUMBER, BOOLEAN, NULL;
	}
}
