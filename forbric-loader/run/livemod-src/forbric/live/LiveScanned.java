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

package forbric.live;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A mod's own annotation, of the shape real Forge mods use to find their own members through
 * {@code ModList.getAllScanData()} — SuperMartijn642's {@code @RegistryEntryAcceptor}, JEI's {@code @JeiPlugin},
 * Jade's plugin marker.
 *
 * <p>CLASS retention, like every one of those: the index is built from bytecode, so a visible-only scan would find
 * none of them. It carries an ENUM member on purpose — FML wraps an enum member in a game-side type and the two
 * ecosystems do not agree on which, so a bare String there is a {@code ClassCastException} inside the mod.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.FIELD)
public @interface LiveScanned {
	Kind kind();

	String note();

	enum Kind {
		FIRST,
		SECOND,
	}
}
