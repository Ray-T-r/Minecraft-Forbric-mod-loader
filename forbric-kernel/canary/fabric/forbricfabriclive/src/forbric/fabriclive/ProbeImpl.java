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

package forbric.fabriclive;

/**
 * Backs the custom {@code forbric:probe} entrypoint key in both declaration forms the default language adapter
 * must support: a plain class (instantiated via its no-arg constructor) and a {@code Class::STATIC_FIELD}
 * reference.
 */
public final class ProbeImpl implements Runnable {
	/** The {@code forbric.fabriclive.ProbeImpl::STATIC_PROBE} declaration resolves to this field. */
	public static final Runnable STATIC_PROBE =
			() -> System.out.println("[ForbricFabricLive] probe via Class::STATIC_FIELD entrypoint");

	@Override
	public void run() {
		System.out.println("[ForbricFabricLive] probe via plain-class entrypoint");
	}
}
