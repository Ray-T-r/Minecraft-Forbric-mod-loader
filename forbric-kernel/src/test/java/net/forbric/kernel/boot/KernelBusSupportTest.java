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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Pins {@link KernelBusSupport}, and above all the option whose absence is swallowed.
 *
 * <p>{@code makeModBus} builds the NeoForge mod bus reflectively, and wraps two of the four calls in
 * {@code catch (Throwable ignored)} as "best effort". One of those two is not optional in practice:
 * {@code allowPerPhasePost()} is what lets phase-specific mod-bus events be dispatched through
 * {@code post(EventPriority, Event)}, and a bus built without it rejects them with "This bus does not allow
 * calling phase-specific post" — caught empirically in {@code Options.<init>} via
 * {@code ClientHooks.onRegisterKeyMappings}, i.e. nowhere near here.
 *
 * <p>So if a carrier drops that method, the swallow turns a missing API into a client that dies later and
 * elsewhere, with nothing in the log connecting the two. This test reads the staged carrier and says so at build
 * time instead.
 */
class KernelBusSupportTest {
	private static final Path NEOFORGE = Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run",
			"neoforge-runtime", "neoforge-runtime.jar").normalize();

	/** The four calls makeModBus makes, two of which fail silently if they are gone. */
	@Test
	void theCarrierStillOffersEveryBusBuilderCallMakeModBusMakes() throws Exception {
		ClassNode builder = carrier("net/neoforged/bus/api/BusBuilder");
		assumeTrue(builder != null, "staged NeoForge carrier absent");

		assertNotNull(method(builder, "builder"), "BusBuilder.builder() is gone — makeModBus cannot start");
		assertNotNull(method(builder, "build"), "BusBuilder.build() is gone");
		assertNotNull(method(builder, "markerType"), "BusBuilder.markerType is gone (swallowed as best-effort)");
		assertNotNull(method(builder, "allowPerPhasePost"),
				"BusBuilder.allowPerPhasePost() is gone, and makeModBus SWALLOWS that. The bus would be built "
						+ "without it and the client would die much later in Options.<init> on "
						+ "\"This bus does not allow calling phase-specific post\", with nothing linking the two");
	}

	/** The marker type it tags the bus with, also swallowed. */
	@Test
	void theModBusMarkerInterfaceStillExists() throws Exception {
		assumeTrue(Files.isRegularFile(NEOFORGE), "staged NeoForge carrier absent");
		assertNotNull(carrier("net/neoforged/fml/event/IModBusEvent"),
				"IModBusEvent is gone — the mod bus would lose its marker type, silently");
	}

	/** Found by SHAPE on purpose: a bus's post() erases to its own event bound, which moves. */
	@Test
	void singleArgMethodMatchesOnArityNotSignature() throws Exception {
		Method m = KernelBusSupport.singleArgMethod(Sample.class, "post");

		assertEquals(1, m.getParameterCount());
		assertEquals("post", m.getName());
	}

	@Test
	void singleArgMethodIgnoresTheSameNameAtOtherArities() throws Exception {
		assertEquals(1, KernelBusSupport.singleArgMethod(Sample.class, "overloaded").getParameterCount(),
				"the two-argument overload must not be picked");
	}

	@Test
	void singleArgMethodSaysSoWhenThereIsNone() {
		assertThrows(NoSuchMethodException.class, () -> KernelBusSupport.singleArgMethod(Sample.class, "absent"));
	}

	@Test
	void unwrapPeelsAnInvocationTargetExceptionAndNothingElse() {
		RuntimeException real = new IllegalStateException("the real one");

		assertSame(real, KernelBusSupport.unwrap(new InvocationTargetException(real)));
		assertSame(real, KernelBusSupport.unwrap(real), "a plain throwable is its own cause here");

		InvocationTargetException causeless = new InvocationTargetException(null);
		assertSame(causeless, KernelBusSupport.unwrap(causeless), "an ITE with no cause must not become null");
	}

	// --- helpers ------------------------------------------------------------------------------------------------

	private static ClassNode carrier(String internalName) throws Exception {
		if (!Files.isRegularFile(NEOFORGE)) return null;
		try (ZipFile zip = new ZipFile(NEOFORGE.toFile())) {
			ZipEntry e = zip.getEntry(internalName + ".class");
			if (e == null) return null;
			try (InputStream in = zip.getInputStream(e)) {
				ClassNode node = new ClassNode();
				new ClassReader(in.readAllBytes()).accept(node, 0);
				return node;
			}
		}
	}

	private static MethodNode method(ClassNode node, String name) {
		for (MethodNode m : node.methods) {
			if (m.name.equals(name)) return m;
		}
		return null;
	}

	@SuppressWarnings("unused")
	private static final class Sample {
		public void post(Object event) { }

		public void overloaded(Object one) { }

		public void overloaded(Object one, Object two) { }
	}
}
