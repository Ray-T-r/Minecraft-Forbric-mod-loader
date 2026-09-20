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

package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.forbric.kernel.transform.LootTableEventBridgeInjector;

/**
 * The pinned mixin's sequence, over fake handles: REPLACE before MODIFY, the builder copied from what REPLACE
 * chose, the source it reports, ALL_LOADED then the map cleared, identity when off or when a handle throws.
 */
class LootTableEventDispatchTest {
	// What the fakes saw
	private static final List<String> CALLS = new ArrayList<>();
	private static Object replaceAnswer;
	private static Object replaceGotTable, replaceGotSource;
	private static Object modifyGotBuilder, modifyGotSource;
	private static boolean throwInReplace;
	private static final ThreadLocal<Map<Object, Object>> SOURCES = ThreadLocal.withInitial(HashMap::new);

	private static final Object DATA_PACK = "DATA_PACK", REPLACED = "REPLACED", VANILLA = "VANILLA";

	@BeforeEach
	void fakes() throws Exception {
		CALLS.clear();
		replaceAnswer = null;
		replaceGotTable = replaceGotSource = modifyGotBuilder = modifyGotSource = null;
		throwInReplace = false;
		SOURCES.remove();
		MethodHandles.Lookup l = MethodHandles.lookup();
		Class<?> me = LootTableEventDispatchTest.class;
		LootTableEventDispatch.bindForTest(new LootTableEventDispatch.Handles(
				l.findStatic(me, "replace", MethodType.methodType(Object.class, Object.class, Object.class, Object.class, Object.class)),
				l.findStatic(me, "modify", MethodType.methodType(void.class, Object.class, Object.class, Object.class, Object.class)),
				l.findStatic(me, "loaded", MethodType.methodType(void.class, Object.class, Object.class)),
				l.findStatic(me, "copyOf", MethodType.methodType(Object.class, Object.class)),
				l.findStatic(me, "build", MethodType.methodType(Object.class, Object.class)),
				DATA_PACK, REPLACED, SOURCES));
	}

	@AfterEach
	void reset() {
		System.clearProperty(LootTableEventBridgeInjector.PROPERTY);
		LootTableEventDispatch.bindForTest(null);
	}

	static Object replace(Object key, Object table, Object source, Object provider) {
		CALLS.add("replace");
		if (throwInReplace) throw new IllegalStateException("listener blew up");
		replaceGotTable = table;
		replaceGotSource = source;
		return replaceAnswer;
	}

	static void modify(Object key, Object builder, Object source, Object provider) {
		CALLS.add("modify");
		modifyGotBuilder = builder;
		modifyGotSource = source;
	}

	static void loaded(Object rm, Object registry) {
		CALLS.add("loaded");
	}

	static Object copyOf(Object table) {
		CALLS.add("copyOf");
		return "builder(" + table + ")";
	}

	static Object build(Object builder) {
		CALLS.add("build");
		return "built(" + builder + ")";
	}

	@Test
	void replaceDeclinedMeansModifySeesACopyOfTheOriginalFromTheDataPack() {
		Object out = LootTableEventDispatch.afterLoad("provider", "key", "minecraft:blocks/dirt", "original");
		assertEquals(List.of("replace", "copyOf", "modify", "build"), CALLS, "REPLACE strictly before MODIFY");
		assertEquals("original", replaceGotTable);
		assertEquals(DATA_PACK, replaceGotSource, "no entry in SOURCES → DATA_PACK");
		assertEquals("builder(original)", modifyGotBuilder, "the builder is copied from the ORIGINAL when REPLACE declines");
		assertEquals(DATA_PACK, modifyGotSource);
		assertEquals("built(builder(original))", out);
	}

	@Test
	void replaceTakenMeansModifySeesACopyOfTheReplacementMarkedReplaced() {
		replaceAnswer = "swapped";
		Object out = LootTableEventDispatch.afterLoad("provider", "key", "minecraft:blocks/dirt", "original");
		assertEquals("builder(swapped)", modifyGotBuilder, "the builder is copied from what REPLACE chose, not the original");
		assertEquals(REPLACED, modifyGotSource, "and MODIFY is told the table was replaced");
		assertEquals("built(builder(swapped))", out);
	}

	@Test
	void aRecordedSourcePassesThroughToBothEvents() {
		SOURCES.get().put("minecraft:blocks/dirt", VANILLA);
		LootTableEventDispatch.afterLoad("provider", "key", "minecraft:blocks/dirt", "original");
		assertEquals(VANILLA, replaceGotSource);
		assertEquals(VANILLA, modifyGotSource);
	}

	@Test
	void switchedOffItIsIdentityAndInvokesNothing() {
		System.setProperty(LootTableEventBridgeInjector.PROPERTY, "off");
		Object table = "original";
		assertSame(table, LootTableEventDispatch.afterLoad("provider", "key", "id", table));
		LootTableEventDispatch.allLoaded("rm", "registry");
		assertTrue(CALLS.isEmpty(), CALLS.toString());
	}

	@Test
	void allLoadedFiresTheEventThenClearsTheSourceMap() {
		SOURCES.get().put("x", VANILLA);
		LootTableEventDispatch.allLoaded("rm", "registry");
		assertEquals(List.of("loaded"), CALLS);
		assertTrue(SOURCES.get().isEmpty(), "the thread-local map is removed after ALL_LOADED, as the mixin does");
	}

	@Test
	void aThrowingListenerLeavesNeoForgesTableUntouched() {
		throwInReplace = true;
		Object table = "original";
		assertSame(table, LootTableEventDispatch.afterLoad("provider", "key", "id", table));
		assertEquals(List.of("replace"), CALLS, "nothing after the failure runs — no half-modified table");
	}

	@Test
	void withoutFabricTheDispatchIsIdentity() {
		LootTableEventDispatch.bindForTest(null);
		Object table = "original";
		assertSame(table, LootTableEventDispatch.afterLoad("provider", "key", "id", table));
		assertNull(replaceGotTable);
	}

	/** Pins that a substituted handle is really what afterLoad calls — not a cached resolution from a real loader. */
	@Test
	void theHandlesAreTheSeam() throws Exception {
		MethodHandle constant = MethodHandles.dropArguments(MethodHandles.constant(Object.class, "fromHandle"), 0,
				Object.class, Object.class, Object.class, Object.class);
		MethodHandles.Lookup l = MethodHandles.lookup();
		Class<?> me = LootTableEventDispatchTest.class;
		LootTableEventDispatch.bindForTest(new LootTableEventDispatch.Handles(constant,
				l.findStatic(me, "modify", MethodType.methodType(void.class, Object.class, Object.class, Object.class, Object.class)),
				l.findStatic(me, "loaded", MethodType.methodType(void.class, Object.class, Object.class)),
				l.findStatic(me, "copyOf", MethodType.methodType(Object.class, Object.class)),
				l.findStatic(me, "build", MethodType.methodType(Object.class, Object.class)),
				DATA_PACK, REPLACED, SOURCES));
		LootTableEventDispatch.afterLoad("provider", "key", "id", "original");
		assertEquals("builder(fromHandle)", modifyGotBuilder);
	}
}
