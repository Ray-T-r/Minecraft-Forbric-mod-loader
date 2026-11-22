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
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.discovery.ModAnnotationScanner.ModClassInfo;

/**
 * {@link KernelModLoader#nativeClassOrder}: one mod's {@code @Mod} classes in the order NeoForge's
 * {@code FMLJavaModLanguageProvider.loadMod} sorts them — fewest {@code depends} first, then the class that belongs to
 * more sides — not the scanner's class-name order.
 *
 * <p>Paraglider's {@code ParagliderClientMod} ({@code dist = CLIENT}) sorts before {@code ParagliderMod} by name, and its
 * constructor calls {@code ParagliderMod.instance()}, which the common class's constructor publishes: constructed in name
 * order it threw "Mod instance not ready yet" on every client.
 */
class KernelModLoaderClassOrderTest {
	@AfterEach
	void clearSwitch() {
		System.clearProperty(KernelModLoader.CLASS_ORDER_SWITCH);
	}

	private static ModClassInfo neo(String className, String modId, Set<String> dists, List<String> depends) {
		return new ModClassInfo(className, modId, Ecosystem.NEOFORGE, dists, depends);
	}

	@Test
	void aModsCommonClassIsConstructedBeforeItsClientClass() {
		ModClassInfo client = neo("tictim.paraglider.ParagliderClientMod", "paraglider", Set.of("CLIENT"), List.of());
		ModClassInfo common = neo("tictim.paraglider.ParagliderMod", "paraglider", Set.of(), List.of());

		List<ModClassInfo> ordered = KernelModLoader.nativeClassOrder(List.of(client, common));

		assertEquals(List.of(common, client), ordered);
	}

	@Test
	void fewerDependsComeFirstAndOutrankSides() {
		// depends is the first key: a both-sides class naming one mod still waits for a client class naming none.
		ModClassInfo compat = neo("a.Compat", "m", Set.of(), List.of("jei"));
		ModClassInfo client = neo("b.Client", "m", Set.of("CLIENT"), List.of());
		ModClassInfo common = neo("c.Common", "m", Set.of(), List.of());

		assertEquals(List.of(common, client, compat), KernelModLoader.nativeClassOrder(List.of(compat, client, common)));
	}

	@Test
	void otherModsKeepTheirSlotsAndTiesKeepTheirOrder() {
		ModClassInfo lib = neo("lib.Lib", "lib", Set.of(), List.of());
		ModClassInfo client = neo("m.AClient", "m", Set.of("CLIENT"), List.of());
		ModClassInfo other = neo("o.Other", "other", Set.of(), List.of());
		ModClassInfo common = neo("m.BCommon", "m", Set.of(), List.of());
		ModClassInfo second = neo("m.CCommon", "m", Set.of("CLIENT", "DEDICATED_SERVER"), List.of());

		assertEquals(List.of(lib, common, other, second, client),
				KernelModLoader.nativeClassOrder(List.of(lib, client, other, common, second)));
	}

	@Test
	void theSwitchGivesBackTheFoundOrder() {
		System.setProperty(KernelModLoader.CLASS_ORDER_SWITCH, "off");
		List<ModClassInfo> found = List.of(neo("a.Client", "m", Set.of("CLIENT"), List.of()),
				neo("b.Common", "m", Set.of(), List.of()));

		assertSame(found, KernelModLoader.nativeClassOrder(found));
	}

	/**
	 * End to end through the construction dispatch, on a real multi-threaded pool: the client class reads what the
	 * common class's constructor published. Deterministic, because the dispatch runs one mod's classes one after another
	 * in the order given; before the fix that order was the name order and the client class always ran first.
	 */
	@Test
	void theClientClassSeesWhatTheCommonClassPublished() {
		ModClassInfo client = neo("m.AClient", "m", Set.of("CLIENT"), List.of());
		ModClassInfo common = neo("m.BCommon", "m", Set.of(), List.of());
		List<ModClassInfo> ordered = KernelModLoader.nativeClassOrder(List.of(client, common));

		List<String> seen = Collections.synchronizedList(new ArrayList<>());
		Object[] instance = new Object[1];
		ExecutorService pool = Executors.newFixedThreadPool(4);
		try {
			List<Throwable> threw = NeoParallelDispatch.run(pool, ordered, info -> info.modId, java.util.Map.of(),
					info -> {
						if (info == common) {
							synchronized (instance) {
								instance[0] = info;
							}
						} else {
							synchronized (instance) {
								if (instance[0] == null) throw new IllegalStateException("Mod instance not ready yet");
							}
						}
						seen.add(info.className);
					});
			assertEquals(java.util.Arrays.asList(null, null), threw);
		} finally {
			pool.shutdownNow();
		}
		assertEquals(List.of("m.BCommon", "m.AClient"), seen);
	}
}
