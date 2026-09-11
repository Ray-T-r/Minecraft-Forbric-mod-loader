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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.fabricmc.loader.api.MappingResolver;

/**
 * Covers the mapping resolver's CONTRACT, which is the only thing about it that can be wrong.
 *
 * <p>Every lookup is the identity because the kernel runs the merged base in Mojmap and modern mods ship compiled
 * against Mojmap too. That makes the bodies trivial and the contract the whole risk: a mod branches on
 * {@code getCurrentRuntimeNamespace()} to decide whether it must remap at all, so a namespace name that does not
 * appear in {@code getNamespaces()}, or one spelled differently from what the identity mapping actually is, sends
 * that mod down its remapping path — where it would map already-Mojmap names through nothing and get them back
 * unchanged while believing they were translated.
 */
class KernelMappingResolverTest {
	@Test
	void theRuntimeNamespaceIsOneThisResolverAdmitsToHaving() {
		MappingResolver resolver = new KernelMappingResolver();
		assertTrue(resolver.getNamespaces().contains(resolver.getCurrentRuntimeNamespace()),
				"a runtime namespace missing from getNamespaces() is a resolver that disowns its own answers");
		assertEquals("named", resolver.getCurrentRuntimeNamespace(),
				"Mojmap is 'named'; any other spelling makes mods take their intermediary path");
	}

	@Test
	void everyLookupIsTheIdentityInBothDirections() {
		MappingResolver resolver = new KernelMappingResolver();
		assertEquals("net.minecraft.client.Minecraft",
				resolver.mapClassName("named", "net.minecraft.client.Minecraft"));
		assertEquals("net.minecraft.client.Minecraft",
				resolver.unmapClassName("named", "net.minecraft.client.Minecraft"));
		assertEquals("tick", resolver.mapMethodName("named", "net.minecraft.client.Minecraft", "tick", "()V"));
		assertEquals("level", resolver.mapFieldName("named", "net.minecraft.client.Minecraft", "level",
				"Lnet/minecraft/client/multiplayer/ClientLevel;"));
	}

	/**
	 * A query in a namespace the kernel does not carry returns the input too. That is the honest reply when no
	 * mapping data is loaded — and the same thing Fabric Loader does for a member it has no mapping for.
	 */
	@Test
	void anUnknownNamespaceGetsTheInputBackRatherThanNullOrAThrow() {
		MappingResolver resolver = new KernelMappingResolver();
		assertEquals("class_310", resolver.mapClassName("intermediary", "class_310"));
		assertEquals("method_1514", resolver.mapMethodName("intermediary", "class_310", "method_1514", "()V"));
	}
}
