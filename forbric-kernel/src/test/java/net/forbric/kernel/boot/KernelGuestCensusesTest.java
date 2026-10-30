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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import net.forbric.kernel.ExpectCaught;
import net.forbric.kernel.GateLogContract;

/**
 * The guest censuses {@code KernelLifecycle.reportGuestCensuses} logs after the subscriber pass, inside
 * {@code registerNeoForgeContent}'s try.
 *
 * <p>They used to be the last lines of {@code KernelEventSubscribers.registerAll}, which its caller isolates; moved out of it,
 * a census that threw escaped into the registration window's outer catch and cost RegisterEvent, the Fabric mains and every
 * step after them, for a log line. Each is isolated again, and on its own.
 */
class KernelGuestCensusesTest {
	@Test
	@ExpectCaught("a census that breaks")
	void aCensusThatThrowsCostsOnlyItsOwnLine() throws Exception {
		Supplier<String> broken = () -> {
			throw new IllegalStateException("a census that breaks");
		};
		String[] out = new String[1];
		GateLogContract.capture(() -> {
			assertDoesNotThrow(() -> KernelLifecycle.reportGuestCensuses(List.of(Map.entry("first", broken),
					Map.entry("second", () -> "the second census line"))), "a census that throws never reaches the window");
			return null;
		}, out);
		assertTrue(out[0].contains("could not take the first census"), "the broken census is named: " + out[0]);
		assertTrue(out[0].contains("the second census line"), "and the one after it is still logged: " + out[0]);
	}

	/** What production hands that loop: both lines gates read, in the order they were logged before the move. */
	@Test
	void theProductionCensusesAreThePartialMixinsThenTheUnmodelledAccessors() throws Exception {
		String[] out = new String[1];
		GateLogContract.capture(() -> {
			KernelLifecycle.reportGuestCensuses();
			return null;
		}, out);
		String partial = net.forbric.kernel.mixin.KernelGuestMixinAdapter.partialSummary(), unmodelled = PassiveSeeder.unmodelledSummary();
		int first = out[0].indexOf(partial), second = out[0].indexOf(unmodelled);
		assertTrue(first >= 0 && second > first, "the partial-mixin census, then the unmodelled-accessor one: " + out[0]);
	}
}
