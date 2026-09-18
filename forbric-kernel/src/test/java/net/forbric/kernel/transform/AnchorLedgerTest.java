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

package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.forbric.kernel.transform.AnchorSet.Anchor;
import net.forbric.kernel.transform.AnchorSet.Severity;

/**
 * The books themselves, as a pure function of what was declared and what was recorded.
 *
 * <p>Every test here asserts BOTH answers for the same input shape. An assertion that has only ever seen one
 * verdict is the kind this project has shipped toothless four times: it goes green whether or not the mechanism
 * under it is alive. So the mutation is not something a person has to remember to try -- it is the second half
 * of each test, and it runs on every build.
 */
class AnchorLedgerTest {

	private static final String T = "forbric-example";

	private static Anchor required(String name) {
		return new Anchor(name, Severity.REQUIRED, "the example feature stops working");
	}

	@Test
	void aDeclaredClassThatWasLoadedAndNotEditedIsAMiss() {
		AnchorLedger declined = new AnchorLedger();
		declined.declare(T, required("a.B"));
		declined.record(T, "a.B", false);

		AnchorLedger.Report r = declined.report();
		assertEquals(1, r.misses().size(), "loaded and declined is a defect");
		assertEquals("a.B", r.misses().get(0).className());
		assertEquals(0, r.hit());
		assertFalse(r.clean());

		// The mutation, as an input: the same anchor, edited. If the miss survives this, the report is not
		// reading `edited` at all.
		AnchorLedger edited = new AnchorLedger();
		edited.declare(T, required("a.B"));
		edited.record(T, "a.B", true);

		AnchorLedger.Report ok = edited.report();
		assertEquals(0, ok.misses().size(), "an edited anchor must not be reported as a miss");
		assertEquals(1, ok.hit());
		assertTrue(ok.clean());
	}

	@Test
	void aDeclaredClassThatWasNeverLoadedIsAbsentAndNotAMiss() {
		AnchorLedger never = new AnchorLedger();
		never.declare(T, required("a.NeverLoaded"));

		AnchorLedger.Report r = never.report();
		assertEquals(0, r.misses().size(),
				"never loaded means nothing on its own -- a client-only target on a server is exactly this");
		assertEquals(1, r.absent().size());
		assertTrue(r.clean(), "absent must not make a report dirty");

		// The other direction: the same anchor, now loaded and declined, must move out of `absent` into `misses`.
		AnchorLedger loaded = new AnchorLedger();
		loaded.declare(T, required("a.NeverLoaded"));
		loaded.record(T, "a.NeverLoaded", false);

		AnchorLedger.Report bad = loaded.report();
		assertEquals(0, bad.absent().size(), "once the class was seen it is no longer merely absent");
		assertEquals(1, bad.misses().size());
	}

	@Test
	void oneEditAnywhereSettlesTheAnchorEvenIfALaterPassDeclines() {
		// The chain runs over each class twice -- once for Mixin's pre-weave view and once when it is defined --
		// and a transformer that edited it the first time has done its job. Recording the second pass must not
		// undo that.
		AnchorLedger ledger = new AnchorLedger();
		ledger.declare(T, required("a.B"));
		ledger.record(T, "a.B", true);
		ledger.record(T, "a.B", false);

		assertTrue(ledger.report().clean(), "a second pass must not retract a hit");
		assertEquals(1, ledger.report().hit());
	}

	@Test
	void aHedgeThatNeverMatchesIsNotADefect() {
		// ClientPackHookInjector and LifecycleHookInjector both carry a target they expect never to match, so
		// that they start working by themselves if a carrier grows the method. Reporting those every boot is how
		// a reader learns to skip the lines that matter.
		AnchorLedger ledger = new AnchorLedger();
		ledger.declare(T, new Anchor("a.Hedge", Severity.HEDGE, "nothing today; it would wake up on its own"));
		ledger.record(T, "a.Hedge", false);

		AnchorLedger.Report r = ledger.report();
		assertEquals(1, r.misses().size(), "it is still recorded");
		assertEquals(Severity.HEDGE, r.misses().get(0).severity(), "but it carries the severity that says so");
	}

	@Test
	void recordingAClassNobodyDeclaredChangesNothing() {
		AnchorLedger ledger = new AnchorLedger();
		ledger.declare(T, required("a.B"));
		ledger.record(T, "a.Unrelated", false);
		ledger.record("some-other-transformer", "a.B", false);

		AnchorLedger.Report r = ledger.report();
		assertEquals(1, r.declared(), "the books must not grow rows from traffic");
		assertEquals(0, r.misses().size(), "another transformer declining a.B says nothing about this one");
	}

	@Test
	void theWatchSetIsExactlyTheDeclaredClasses() {
		AnchorLedger ledger = new AnchorLedger();
		ledger.declare(T, required("a.B"));
		ledger.declare("other", required("a.B"));
		ledger.declare("other", required("c.D"));

		assertEquals(java.util.Set.of("a.B", "c.D"), ledger.watchedClasses(),
				"this set is what puts ONE failed hash lookup, not thirty, on every class the game loads");
	}
}
