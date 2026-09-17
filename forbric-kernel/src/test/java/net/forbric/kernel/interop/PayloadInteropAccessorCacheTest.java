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

package net.forbric.kernel.interop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

/**
 * The per-packet check at the head of the outbound-packet path.
 *
 * <p>{@code isForgePayloadPacket} is reached for EVERY packet the game sends, and it resolved the accessor
 * reflectively each time — walking the class's whole superclass chain and then its interfaces, constructing and
 * discarding a {@code NoSuchMethodException} at each step. Most packets have no such accessor, so the common
 * case was the most expensive one.
 */
class PayloadInteropAccessorCacheTest {

	/** A packet-shaped class: it has the accessor. */
	public static class WithPayload {
		public Object payload() {
			return "not-a-forge-payload";
		}
	}

	/** The common case: an ordinary packet, no accessor anywhere on it. */
	public static class WithoutPayload {
	}

	/** Inherits the accessor — the lookup has to walk up, which is what made a miss expensive. */
	public static class InheritsPayload extends WithPayload {
	}

	@Test
	void theAccessorIsResolvedOncePerClass() {
		// Identity is the evidence: the reflective lookup hands back a FRESH Method object on every call, so two
		// calls returning the same instance can only mean the second one did not look it up.
		Method first = PayloadInterop.payloadAccessor(WithPayload.class);
		Method again = PayloadInterop.payloadAccessor(WithPayload.class);

		assertNotNull(first, "the accessor is there and must be found");
		assertSame(first, again, "the second call resolved it again instead of remembering it");
	}

	@Test
	void aClassWithNoAccessorRemembersThatToo() {
		// The case that matters most for cost: it is the majority of packets, and caching a miss is what stops
		// the full walk from happening per packet.
		assertNull(PayloadInterop.payloadAccessor(WithoutPayload.class));
		assertNull(PayloadInterop.payloadAccessor(WithoutPayload.class));
		assertFalse(PayloadInterop.isForgePayloadPacket(new WithoutPayload()));
	}

	@Test
	void anInheritedAccessorIsFound() {
		assertNotNull(PayloadInterop.payloadAccessor(InheritsPayload.class),
				"the accessor is declared on the superclass; a lookup that stops at the class itself misses it");
	}

	@Test
	void aPayloadFromAnotherLoaderIsNotAForgeOne() {
		// The answer itself, not the caching: a packet carrying some other payload must come back false, or the
		// outbound check exempts packets it should be checking.
		assertFalse(PayloadInterop.isForgePayloadPacket(new WithPayload()));
	}

	@Test
	void aNullPacketIsNotAForgeOne() {
		assertFalse(PayloadInterop.isForgePayloadPacket(null));
	}
}
