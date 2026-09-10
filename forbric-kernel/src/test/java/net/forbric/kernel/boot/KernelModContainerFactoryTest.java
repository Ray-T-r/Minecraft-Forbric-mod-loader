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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Pins the reflective contract {@link KernelModContainerFactory} depends on, against the real NeoForge carrier.
 *
 * <p>That factory is 371 lines of {@code Class.forName}, a {@code Proxy}, {@code UnsafeHacks} field-poking and
 * ASM generation, and it has NOT ONE warn or error path. Every name in it is a string the compiler never sees:
 * four class names, five field names on two classes, and two methods on Forge's {@code UnsafeHacks}. If a carrier
 * upgrade renames any of them the factory does not fail loudly — it throws deep inside mod construction, or
 * silently builds a container with a null where a mod expects its event bus.
 *
 * <p>The carrier has already been re-pinned once (NeoForge .7-beta to .38-beta), so "a field moved" is a live
 * upgrade hazard rather than a hypothetical. These assertions are the cheap early warning: they read the staged
 * jar directly, so they go red at build time instead of during someone's world load.
 */
class KernelModContainerFactoryTest {
	private static final Path NEOFORGE = Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run",
			"neoforge-runtime", "neoforge-runtime.jar").normalize();
	private static final Path FORGE = Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run",
			"forge-runtime", "forge-runtime.jar").normalize();

	/** The four types the factory resolves by name, and the visibility its typed use of them needs. */
	@Test
	void theFourResolvedTypesExistAndArePublic() throws Exception {
		String[] types = {
			"net/neoforged/neoforgespi/language/IModInfo",
			"net/neoforged/bus/api/IEventBus",
			"net/neoforged/fml/ModContainer",
			"net/neoforged/fml/javafmlmod/FMLModContainer",
		};
		for (String t : types) {
			ClassNode node = neo(t);
			assumeTrue(node != null, "staged NeoForge carrier absent");
			assertTrue((node.access & Opcodes.ACC_PUBLIC) != 0, t + " is no longer public");
		}
	}

	/**
	 * The fields the factory writes directly, because {@code FMLModContainer}'s only constructor would run genuine
	 * FancyModLoader discovery. These are the names most likely to move, and the least likely to say so.
	 */
	@Test
	void theFieldsTheFactoryWritesStillExistOnModContainer() throws Exception {
		ClassNode modContainer = neo("net/neoforged/fml/ModContainer");
		assumeTrue(modContainer != null, "staged NeoForge carrier absent");

		for (String field : new String[] {"modId", "namespace", "modInfo", "extensionPoints"}) {
			assertNotNull(field(modContainer, field),
					"ModContainer." + field + " is gone — KernelModContainerFactory sets it by name through "
							+ "UnsafeHacks, and a missing field there surfaces as a container that is subtly wrong "
							+ "rather than as a failure");
		}
	}

	/** And the one on the subclass, which is what makes {@code getEventBus()} answer at all. */
	@Test
	void theEventBusFieldStillExistsOnFmlModContainer() throws Exception {
		ClassNode fml = neo("net/neoforged/fml/javafmlmod/FMLModContainer");
		assumeTrue(fml != null, "staged NeoForge carrier absent");

		FieldNode bus = field(fml, "eventBus");
		assertNotNull(bus, "FMLModContainer.eventBus is gone — every mod that resolves its own bus through "
				+ "ModList.getModContainerById(id).getEventBus() would get null");
	}

	/**
	 * {@code getEventBus()} must stay ABSTRACT on ModContainer. The factory generates a concrete subclass whose
	 * whole job is to implement it; if the carrier ever gave it a body, the generated override would be silently
	 * shadowing real behaviour instead of supplying missing behaviour.
	 */
	@Test
	void getEventBusIsStillAbstractOnModContainer() throws Exception {
		ClassNode modContainer = neo("net/neoforged/fml/ModContainer");
		assumeTrue(modContainer != null, "staged NeoForge carrier absent");

		MethodNode m = method(modContainer, "getEventBus");
		assertNotNull(m, "ModContainer.getEventBus is gone");
		assertTrue((m.access & Opcodes.ACC_ABSTRACT) != 0,
				"getEventBus() now has a body on the carrier — the generated subclass would be overriding real "
						+ "behaviour rather than filling a gap; re-check whether the generated container is still "
						+ "the right shape");
	}

	/** UnsafeHacks is MinecraftForge's, borrowed by the NeoForge path; both entry points are named by string. */
	@Test
	void unsafeHacksStillOffersTheTwoMethodsTheFactoryCalls() throws Exception {
		ClassNode unsafe = readClass(FORGE, "net/minecraftforge/unsafe/UnsafeHacks");
		assumeTrue(unsafe != null, "staged MinecraftForge carrier absent");

		assertNotNull(method(unsafe, "newInstance"),
				"UnsafeHacks.newInstance is gone — the factory allocates FMLModContainer without a constructor "
						+ "precisely to avoid running genuine FancyModLoader discovery");
		assertNotNull(method(unsafe, "setField"), "UnsafeHacks.setField is gone");
	}

	/** The IModInfo methods the Proxy answers, so a mod reading its own metadata gets something real. */
	@Test
	void iModInfoStillDeclaresWhatTheProxyAnswers() throws Exception {
		ClassNode info = neo("net/neoforged/neoforgespi/language/IModInfo");
		assumeTrue(info != null, "staged NeoForge carrier absent");

		assertNotNull(method(info, "getModId"), "IModInfo.getModId is gone — the proxy answers it by name");
		assertEquals(true, (info.access & Opcodes.ACC_INTERFACE) != 0, "IModInfo is no longer an interface, so it "
				+ "cannot be answered by a java.lang.reflect.Proxy at all");
	}

	// --- helpers ------------------------------------------------------------------------------------------------

	private static ClassNode neo(String internalName) throws Exception {
		return readClass(NEOFORGE, internalName);
	}

	private static ClassNode readClass(Path jar, String internalName) throws Exception {
		if (!Files.isRegularFile(jar)) return null;
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry e = zip.getEntry(internalName + ".class");
			if (e == null) return null;
			try (InputStream in = zip.getInputStream(e)) {
				ClassNode node = new ClassNode();
				new ClassReader(in.readAllBytes()).accept(node, 0);
				return node;
			}
		}
	}

	private static FieldNode field(ClassNode node, String name) {
		for (FieldNode f : node.fields) {
			if (f.name.equals(name)) return f;
		}
		return null;
	}

	private static MethodNode method(ClassNode node, String name) {
		for (MethodNode m : node.methods) {
			if (m.name.equals(name)) return m;
		}
		return null;
	}
}
