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

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import net.fabricmc.api.EnvType;

import net.forbric.kernel.transform.AnchorSet.Anchor;
import net.forbric.kernel.transform.AnchorSet.Severity;

/**
 * That the chain actually keeps the books, and does so without putting a lookup per transformer on every class.
 *
 * <p>The hit signal is array identity, which is the contract {@link ClassTransformer#transform} already states
 * and which {@code ModsButtonRedirectorTest} already asserts with {@code assertSame}. Reusing it is what keeps
 * the audit from re-implementing -- and therefore disagreeing with -- the match the transformer makes.
 */
class TransformChainAnchorAccountingTest {
	private static final TransformContext CTX = new TransformContext(EnvType.CLIENT, false, "intermediary");
	private static final String TARGET = "a.Target";

	private static ClassTransformer declaring(String name, boolean edits, AtomicInteger calls) {
		return new ClassTransformer() {
			@Override
			public String name() {
				return name;
			}

			@Override
			public AnchorSet anchors() {
				return AnchorSet.of(new Anchor(TARGET, Severity.REQUIRED, "the example feature stops working"));
			}

			@Override
			public byte[] transform(String className, byte[] classBytes, TransformContext context) {
				calls.incrementAndGet();
				if (!TARGET.equals(className) || !edits) return classBytes;
				return classBytes.clone(); // a different array is the "I edited it" answer
			}
		};
	}

	@Test
	void aTransformerThatReturnsItsInputIsScoredAMiss() {
		TransformChain chain = new TransformChain();
		chain.register(TransformPhase.COREMOD, declaring("declines", false, new AtomicInteger()));

		chain.applyBeforeMixin(TARGET, new byte[] {1, 2, 3}, CTX);

		AnchorLedger.Report r = chain.ledger().report();
		assertFalse(r.clean(), "the same array back is the contract's own way of saying no edit was made");
		assertEquals(1, r.misses().size());
		assertEquals(TARGET, r.misses().get(0).className());
	}

	@Test
	void aTransformerThatReturnsNewBytesIsScoredAHit() {
		TransformChain chain = new TransformChain();
		chain.register(TransformPhase.COREMOD, declaring("edits", true, new AtomicInteger()));

		chain.applyBeforeMixin(TARGET, new byte[] {1, 2, 3}, CTX);

		AnchorLedger.Report r = chain.ledger().report();
		assertTrue(r.clean());
		assertEquals(1, r.hit());
	}

	@Test
	void aClassNobodyDeclaredIsNotAccountedForAtAll() {
		// This is the performance claim, and it is worth an assertion because the obvious implementation -- ask
		// each transformer whether it cares -- costs a map lookup per transformer on all ~30 000 classes the game
		// loads, rather than one failed lookup for the whole chain.
		TransformChain chain = new TransformChain();
		chain.register(TransformPhase.COREMOD, declaring("declines", false, new AtomicInteger()));

		chain.applyBeforeMixin("z.Unrelated", new byte[] {1, 2, 3}, CTX);

		AnchorLedger.Report r = chain.ledger().report();
		assertEquals(1, r.declared());
		assertEquals(1, r.absent().size(), "the declared anchor is still merely absent");
		assertEquals(0, r.misses().size(), "and an unrelated class must not create a finding");
	}

	@Test
	void aTransformerRegisteredAfterTheFirstApplyIsStillWatched() {
		// The watch set is cached, so a late registration has to drop it. Without that, a transformer added after
		// the first class went through would keep books that arealways empty and look perfectly healthy.
		TransformChain chain = new TransformChain();
		chain.register(TransformPhase.COREMOD, declaring("first", true, new AtomicInteger()));
		chain.applyBeforeMixin("z.Unrelated", new byte[] {1}, CTX);

		chain.register(TransformPhase.COREMOD, declaring("late", false, new AtomicInteger()));
		chain.applyBeforeMixin(TARGET, new byte[] {1}, CTX);

		AnchorLedger.Report r = chain.ledger().report();
		assertEquals(1, r.misses().size(), "the late transformer's declined anchor must still be seen");
		assertEquals("late", r.misses().get(0).transformer());
	}

	@Test
	void aPartialPhaseRangeIsNotScoredAtAll() {
		// A caller that runs only some phases legitimately never reaches a transformer in a later one. Scoring
		// that as "declined" would invent a defect out of the caller's choice of range.
		TransformChain chain = new TransformChain();
		chain.register(TransformPhase.COREMOD, declaring("declines", false, new AtomicInteger()));

		chain.apply(TARGET, new byte[] {1, 2, 3}, CTX, TransformPhase.RAW_PATCH, TransformPhase.COREMOD);

		assertTrue(chain.ledger().report().clean(),
				"only the full pipeline can say a transformer had its chance and declined");
	}
}
