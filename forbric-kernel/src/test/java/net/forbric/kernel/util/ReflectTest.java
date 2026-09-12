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

package net.forbric.kernel.util;

import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.InvocationTargetException;

import org.junit.jupiter.api.Test;

/**
 * Peeling the wrapper off a reflective failure — one layer, and only for the one exception type.
 *
 * <p>Every kernel diagnostic that reports a reflective call's failure goes through this. Get it wrong in the
 * direction of peeling too eagerly and a genuine reflection fault is reported as whatever it happened to wrap;
 * get it wrong in the direction of not peeling and the log carries an {@code InvocationTargetException} whose
 * own message is null, which says nothing at all.
 */
class ReflectTest {

	@Test
	void itPeelsAnInvocationTargetExceptionAndNothingElse() {
		RuntimeException real = new IllegalStateException("the real one");

		assertSame(real, Reflect.unwrap(new InvocationTargetException(real)));
		assertSame(real, Reflect.unwrap(real), "a plain throwable is its own cause here");

		InvocationTargetException causeless = new InvocationTargetException(null);
		assertSame(causeless, Reflect.unwrap(causeless), "an ITE with no cause must not become null");
	}

	/** Exactly one layer: a cause that is itself an ITE stays wrapped, so nothing is lost silently. */
	@Test
	void itPeelsOneLayerNotAllOfThem() {
		RuntimeException real = new IllegalStateException("the real one");
		InvocationTargetException inner = new InvocationTargetException(real);

		assertSame(inner, Reflect.unwrap(new InvocationTargetException(inner)),
				"peeling recursively would report a failure from deeper than the call that was made");
	}
}
