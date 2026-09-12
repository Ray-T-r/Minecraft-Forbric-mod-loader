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

import java.lang.reflect.InvocationTargetException;

/**
 * The small reflective courtesies the whole kernel needs, in the one package both sides can reach.
 *
 * <p>{@code net.forbric.kernel.util} is pinned {@code ALWAYS_PARENT}, so there is exactly one copy of this in the
 * JVM and the GAME side can call it too. That is why it lives here rather than beside its callers: it started in
 * {@code KernelBusSupport}, which is package-private in {@code net.forbric.kernel.boot} and therefore invisible
 * to {@code net.forbric.kernel.runtime}. Moving the one member the game side needs is the alternative to making
 * that whole class public, which would export {@code makeModBus} and {@code singleArgMethod} along with it.
 */
public final class Reflect {
	private Reflect() {
	}

	/**
	 * The exception a reflective call actually threw, rather than the wrapper the call site sees.
	 *
	 * <p>Every failure reached through {@code Method.invoke} arrives as an {@code InvocationTargetException} whose
	 * own message is {@code null}. Logging that is logging nothing: the reader gets a stack trace ending at the
	 * reflection machinery and no statement of what went wrong. Peeling exactly one layer — and ONLY for that
	 * exception type — gives the real cause without hiding a genuine reflection failure behind it.
	 */
	public static Throwable unwrap(Throwable t) {
		return t instanceof InvocationTargetException && t.getCause() != null ? t.getCause() : t;
	}
}
