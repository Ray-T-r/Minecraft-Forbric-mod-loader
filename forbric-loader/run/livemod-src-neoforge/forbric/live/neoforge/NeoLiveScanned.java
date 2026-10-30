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

package forbric.live.neoforge;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The annotation the canary looks itself up by in the scan index.
 *
 * <p>CLASS retention on purpose, the way {@code @JeiPlugin} is: the index is built from bytecode, and an index that
 * read only visible annotations would find nothing here. One enum member and one string member, because an enum
 * member is the one whose shape the two Forge families disagree on.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.FIELD)
public @interface NeoLiveScanned {
	Kind kind();

	String note();

	enum Kind {
		FIRST,
		SECOND,
	}
}
