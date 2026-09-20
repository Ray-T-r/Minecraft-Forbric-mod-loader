/*
 * Copyright 2026 The Forbric Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KernelForgeSpawnPlacementsTest {
	@BeforeEach @AfterEach void reset() { System.clearProperty("forbric.forgeSpawnPlacements"); }

	@Test void forgeIsSeededFromVanillaAndBothFamiliesPostExactlyOnce() throws Exception {
		ForgeSpawnFixture fixture = new ForgeSpawnFixture(false);
		Object type = fixture.entity(), original = fixture.constant(true), placement = fixture.placement(), height = fixture.heightmap();
		fixture.seed(type, original, placement, height); Object event = fixture.event(); List<String> order = new ArrayList<>();
		List<Object> seeded = new ArrayList<>();
		fixture.forgeListener = forge -> {
			order.add("forge");
			try {
				Map<Object, Object> map = fixture.eventMap(forge); seeded.add(java.util.Set.copyOf(map.keySet()));
				Object predicate = map.get(type);
				seeded.add(ForgeSpawnFixture.get(predicate.getClass(), predicate, "originalPredicate"));
				seeded.add(predicate.getClass().getMethod("getSpawnType").invoke(predicate));
				seeded.add(predicate.getClass().getMethod("getHeightmapType").invoke(predicate));
			} catch (Exception e) { throw new AssertionError(e); }
		};
		fixture.onNeo(neo -> order.add("neo")); fixture.run(event);
		assertEquals(List.of(java.util.Set.of(type), original, placement, height), seeded);
		assertEquals(List.of("forge", "neo"), order); assertEquals(1, fixture.forgePosts); assertEquals(1, fixture.neoPosts());
		assertSame(event, fixture.lastNeoEvent(), "the original merged lambda must retain the same map it seeded");
	}

	@Test void forgeReplacementDoesNotDisableALaterNeoOr() throws Exception {
		ForgeSpawnFixture fixture = new ForgeSpawnFixture(false);
		Object type = fixture.entity(), placement = fixture.placement(), height = fixture.heightmap();
		fixture.seed(type, fixture.constant(true), placement, height); Object event = fixture.event();
		Object before = fixture.eventMap(event).get(type), no = fixture.constant(false), yes = fixture.constant(true);
		fixture.forgeListener = forge -> fixture.register(forge, type, no, "REPLACE", null, null);
		fixture.onNeo(neo -> fixture.register(neo, type, yes, "OR", null, null)); fixture.run(event);
		Object after = fixture.eventMap(event).get(type); assertNotSame(before, after);
		assertNull(ForgeSpawnFixture.get(after.getClass(), after, "replacementPredicate"),
				"the Forge result must become Neo's original, never its priority REPLACE predicate");
		assertTrue(fixture.evaluate(after), "Forge false followed by Neo OR(true) must be true");
	}

	@Test void forgeReplacementDoesNotDisableALaterNeoAnd() throws Exception {
		ForgeSpawnFixture fixture = new ForgeSpawnFixture(false);
		Object type = fixture.entity(); fixture.seed(type, fixture.constant(false), fixture.placement(), fixture.heightmap());
		Object event = fixture.event(), yes = fixture.constant(true), no = fixture.constant(false);
		fixture.forgeListener = forge -> fixture.register(forge, type, yes, "REPLACE", null, null);
		fixture.onNeo(neo -> fixture.register(neo, type, no, "AND", null, null)); fixture.run(event);
		assertFalse(fixture.evaluate(fixture.eventMap(event).get(type)), "Forge true followed by Neo AND(false) must be false");
	}

	@Test void untouchedEntriesAndSamePredicateReplacementKeepTheirNeoBuilders() throws Exception {
		ForgeSpawnFixture fixture = new ForgeSpawnFixture(false);
		Object untouched = fixture.entity(), same = fixture.entity(), original = fixture.constant(true);
		fixture.seed(untouched, fixture.constant(true), fixture.placement(), fixture.heightmap());
		fixture.seed(same, original, fixture.placement(), fixture.heightmap()); Object event = fixture.event();
		Map<Object, Object> before = Map.copyOf(fixture.eventMap(event));
		fixture.forgeListener = forge -> fixture.register(forge, same, original, "REPLACE", null, null);
		fixture.run(event);
		before.forEach((type, value) -> {
			try { assertSame(value, fixture.eventMap(event).get(type), "build() returning fresh lambdas is not a change"); }
			catch (Exception e) { throw new AssertionError(e); }
		});
	}

	@Test void andOrChangesAreDetectedWithoutAnyReplacementOrLocationChange() throws Exception {
        for (String operation : List.of("AND", "OR")) {
            ForgeSpawnFixture fixture = new ForgeSpawnFixture(false);
            boolean beforeValue = operation.equals("AND");
            Object type = fixture.entity(); fixture.seed(type, fixture.constant(beforeValue), fixture.placement(), fixture.heightmap());
            Object event = fixture.event(), before = fixture.eventMap(event).get(type), rule = fixture.constant(!beforeValue);
            fixture.forgeListener = forge -> fixture.register(forge, type, rule, operation, null, null); fixture.run(event);
            assertNotSame(before, fixture.eventMap(event).get(type));
            assertEquals(!beforeValue, fixture.evaluate(fixture.eventMap(event).get(type)), operation);
        }
    }

	@Test void newEntriesAndHeightmapAndPlacementReachTheExistingWriteback() throws Exception {
		ForgeSpawnFixture fixture = new ForgeSpawnFixture(false);
		Object existing = fixture.entity(), added = fixture.entity(), unchanged = fixture.entity();
		Object original = fixture.constant(true), addedRule = fixture.constant(false), oldPlacement = fixture.placement(), oldHeight = fixture.heightmap();
		Object newPlacement = fixture.placement(), newHeight = fixture.heightmap();
		fixture.seed(existing, original, oldPlacement, oldHeight);
		fixture.seed(unchanged, original, oldPlacement, oldHeight);
		Object event = fixture.event(), untouched = fixture.eventMap(event).get(unchanged);
		fixture.forgeListener = forge -> {
			fixture.register(forge, existing, original, "REPLACE", newPlacement, newHeight);
			fixture.register(forge, added, addedRule, "REPLACE", newPlacement, newHeight);
		};
		fixture.run(event);
		assertSame(untouched, fixture.eventMap(event).get(unchanged));
		assertFalse(fixture.vanilla.containsKey(added), "only the original merged tail writes DATA_BY_TYPE");
		fixture.writeBack(event);
		for (Object type : List.of(existing, added)) {
			Object data = fixture.vanilla.get(type); assertNotNull(data);
			assertSame(newPlacement, ForgeSpawnFixture.get(fixture.dataType, data, "placement"));
			assertSame(newHeight, ForgeSpawnFixture.get(fixture.dataType, data, "heightMap"));
		}
		assertFalse(fixture.evaluate(fixture.eventMap(event).get(added)));
	}

	@Test void aForgeListenerFailureLeavesTheNeoMapIntactAndStillPostsNeo() throws Exception {
		ForgeSpawnFixture fixture = new ForgeSpawnFixture(false);
		Object type = fixture.entity(); fixture.seed(type, fixture.constant(true), fixture.placement(), fixture.heightmap());
		Object event = fixture.event(), before = fixture.eventMap(event).get(type), no = fixture.constant(false);
		fixture.forgeListener = forge -> { fixture.register(forge, type, no, "REPLACE", null, null); throw new IllegalStateException("fixture listener"); };
		fixture.run(event);
		assertSame(before, fixture.eventMap(event).get(type)); assertEquals(1, fixture.forgePosts); assertEquals(1, fixture.neoPosts());
	}

	@Test void disabledRuntimeAndUnrelatedEventsDoNotResolveForge() throws Exception {
		ForgeSpawnFixture fixture = new ForgeSpawnFixture(true);
		Object event = fixture.event(); System.setProperty("forbric.forgeSpawnPlacements", "off"); fixture.run(event);
		assertEquals(1, fixture.neoPosts()); assertSame(event, fixture.lastNeoEvent());
		System.clearProperty("forbric.forgeSpawnPlacements");
		Object unrelated = fixture.loadClass("fixture.OtherModBusEvent").getConstructor().newInstance(); fixture.run(unrelated);
		assertEquals(2, fixture.neoPosts()); assertSame(unrelated, fixture.lastNeoEvent());
	}
}
