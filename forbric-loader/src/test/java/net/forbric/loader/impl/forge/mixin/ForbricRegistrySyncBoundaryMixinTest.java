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

package net.forbric.loader.impl.forge.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

class ForbricRegistrySyncBoundaryMixinTest {
	@Test
	void sendRegistriesHookDoesNotMutateKnownPacks() throws Exception {
		ForbricRegistrySyncBoundaryMixin mixin = new ForbricRegistrySyncBoundaryMixin();
		Method method = ForbricRegistrySyncBoundaryMixin.class.getDeclaredMethod("forbric$prepareMergedRegistrySync",
				Consumer.class, Set.class, CallbackInfo.class);
		method.setAccessible(true);

		LinkedHashSet<String> knownPacks = new LinkedHashSet<>();
		knownPacks.add("forbric/forge/server_data/physicsmod");
		knownPacks.add("vanilla");

		method.invoke(mixin, (Consumer<Object>) ignored -> {
		}, knownPacks, new CallbackInfo("sendRegistries", false));

		assertEquals(Set.of("forbric/forge/server_data/physicsmod", "vanilla"), knownPacks);
	}
}
