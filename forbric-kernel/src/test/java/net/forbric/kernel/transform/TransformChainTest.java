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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.fabricmc.api.EnvType;

class TransformChainTest {
	private static final TransformContext CTX = new TransformContext(EnvType.CLIENT, false, "intermediary");

	/** A transformer that records its name into a shared log and tags the bytes. */
	private static ClassTransformer recording(String name, List<String> log) {
		return new ClassTransformer() {
			@Override
			public byte[] transform(String className, byte[] classBytes, TransformContext context) {
				log.add(name);
				return classBytes;
			}

			@Override
			public String name() {
				return name;
			}
		};
	}

	@Test
	void phasesRunInDeclarationOrder() {
		List<String> log = new ArrayList<>();
		TransformChain chain = new TransformChain();

		// Register out of phase order; the chain must still run them in TransformPhase ordinal order.
		chain.register(TransformPhase.FABRIC_BUILTIN, recording("fabric", log));
		chain.register(TransformPhase.RAW_PATCH, recording("raw", log));
		chain.register(TransformPhase.ACCESS, recording("access", log));
		chain.register(TransformPhase.DEOBF_REMAP, recording("deobf", log));

		chain.applyBeforeMixin("a.B", new byte[] {1}, CTX);

		assertEquals(List.of("raw", "deobf", "access", "fabric"), log);
	}

	@Test
	void sortIndexBreaksTiesWithinAPhase() {
		List<String> log = new ArrayList<>();
		TransformChain chain = new TransformChain();

		chain.register(TransformPhase.COREMOD, recording("c", log), 30);
		chain.register(TransformPhase.COREMOD, recording("a", log), 10);
		chain.register(TransformPhase.COREMOD, recording("b", log), 20);

		chain.apply("a.B", new byte[] {1}, CTX, TransformPhase.COREMOD, TransformPhase.COREMOD);

		assertEquals(List.of("a", "b", "c"), log);
	}

	@Test
	void predependsOverrideSortIndex() {
		List<String> log = new ArrayList<>();
		TransformChain chain = new TransformChain();

		// "early" has the lower sort index, but "late" must run before it via a predepend edge.
		chain.register(TransformPhase.COREMOD, recording("early", log), 1, "late");
		chain.register(TransformPhase.COREMOD, recording("late", log), 99);

		chain.apply("a.B", new byte[] {1}, CTX, TransformPhase.COREMOD, TransformPhase.COREMOD);

		assertEquals(List.of("late", "early"), log);
	}

	@Test
	void bytesAreThreadedThroughTransformers() {
		TransformChain chain = new TransformChain();

		chain.register(TransformPhase.RAW_PATCH, (name, bytes, ctx) -> {
			byte[] out = bytes.clone();
			out[0] += 1;
			return out;
		});
		chain.register(TransformPhase.FABRIC_BUILTIN, (name, bytes, ctx) -> {
			byte[] out = bytes.clone();
			out[0] += 10;
			return out;
		});

		byte[] result = chain.applyBeforeMixin("a.B", new byte[] {0}, CTX);

		assertArrayEquals(new byte[] {11}, result);
	}

	@Test
	void nullReturnMeansUnchanged() {
		TransformChain chain = new TransformChain();
		chain.register(TransformPhase.RAW_PATCH, (name, bytes, ctx) -> null);

		byte[] input = {7};
		byte[] result = chain.applyBeforeMixin("a.B", input, CTX);

		assertArrayEquals(new byte[] {7}, result);
	}

	@Test
	void cyclicPredependsAreRejected() {
		TransformChain chain = new TransformChain();
		List<String> log = new ArrayList<>();

		chain.register(TransformPhase.COREMOD, recording("x", log), 0, "y");
		chain.register(TransformPhase.COREMOD, recording("y", log), 0, "x");

		assertThrows(IllegalStateException.class,
				() -> chain.apply("a.B", new byte[] {1}, CTX, TransformPhase.COREMOD, TransformPhase.COREMOD));
	}

	@Test
	void mixinCannotBeRegistered() {
		TransformChain chain = new TransformChain();
		assertThrows(IllegalArgumentException.class,
				() -> chain.register(TransformPhase.MIXIN, (name, bytes, ctx) -> bytes));
	}

	@Test
	void applyRejectsRunningMixin() {
		TransformChain chain = new TransformChain();
		assertThrows(IllegalArgumentException.class,
				() -> chain.apply("a.B", new byte[] {1}, CTX, TransformPhase.RAW_PATCH, TransformPhase.MIXIN));
	}
}
