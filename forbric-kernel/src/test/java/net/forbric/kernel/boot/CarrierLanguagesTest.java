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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * {@link CarrierLanguages} — the carriers' own translation tables.
 *
 * <p>Two halves, because either alone passes while the feature is dead. The stand-in half drives the real
 * reflection with classes this test owns, so the loader-found / table-read / probe-checked path is exercised
 * without a staged carrier. The staged half checks the four names per carrier against the carrier jar itself:
 * every one of them is reached by {@code Class.forName} / {@code getDeclaredMethod} / {@code getDeclaredField}
 * inside a {@code catch}, so a rename does not throw — it leaves the menu rendering {@code fml.menu.branding}
 * with nothing in any log to say why.
 */
class CarrierLanguagesTest {
	/** A stand-in for one carrier: a loader method that fills a table, in the shape both real ones have. */
	public static final class StandIn {
		public static final Map<String, String> table = new HashMap<>();
		static ClassLoader loaderDuringCall;
		static boolean throwOnLoad;
		static boolean fillProbe = true;

		public static void loadBuiltinLanguages() {
			loaderDuringCall = Thread.currentThread().getContextClassLoader();
			if (throwOnLoad) throw new IllegalStateException("assets/neoforge/lang/en_us.json is not on this loader");
			table.clear();
			table.put("fml.menu.mods", "Mods");
			if (fillProbe) table.put("fml.menu.branding", "%s (%s mods)");
		}

		static void reset() {
			table.clear();
			loaderDuringCall = null;
			throwOnLoad = false;
			fillProbe = true;
		}
	}

	private static final String STAND_IN = StandIn.class.getName();

	private static CarrierLanguages.Carrier standIn() {
		return new CarrierLanguages.Carrier("stand-in", STAND_IN, "loadBuiltinLanguages", STAND_IN, "table",
				"fml.menu.branding");
	}

	@Test
	void aLoadedTableIsReportedWithItsSizeAndItsProbe() {
		StandIn.reset();
		CarrierLanguages.Loaded loaded = CarrierLanguages.load(getClass().getClassLoader(), standIn());

		assertEquals(2, loaded.size(), "the table the carrier filled is the one that must be measured");
		assertTrue(loaded.probeResolved(), "the probe key is in the table the loader just filled");
	}

	/**
	 * A table that loaded but does not hold the probe key is a FAILURE, not a success with a smaller number. This is
	 * the case that used to render as a raw key on screen while every log line looked healthy.
	 */
	@Test
	void aTableWithoutTheProbeKeyIsNotReportedAsResolved() {
		StandIn.reset();
		StandIn.fillProbe = false;
		CarrierLanguages.Loaded loaded = CarrierLanguages.load(getClass().getClassLoader(), standIn());

		assertEquals(1, loaded.size());
		assertFalse(loaded.probeResolved(), "the probe key is absent, so the carrier's own screens still break");
	}

	/**
	 * Both real loaders read their assets off the CONTEXT class loader, and the thread that reaches the kernel's
	 * mod-loading window is not guaranteed to be carrying one that can see the carrier jars. Drop the swap and this
	 * is the failure: the loader runs, finds nothing, and fills an empty table.
	 */
	@Test
	void theGameLoaderIsTheContextLoaderForTheCallAndIsPutBackAfterwards() {
		StandIn.reset();
		ClassLoader game = new java.net.URLClassLoader(new java.net.URL[0], getClass().getClassLoader());
		Thread thread = Thread.currentThread();
		ClassLoader before = thread.getContextClassLoader();

		CarrierLanguages.load(game, standIn());

		assertSame(game, StandIn.loaderDuringCall, "the loader's own assets are found through the context loader");
		assertSame(before, thread.getContextClassLoader(), "and the thread is handed back the loader it came with");
	}

	@Test
	void aCarrierThatIsNotOnTheClasspathCostsOnlyItsOwnText() {
		assertNull(CarrierLanguages.load(getClass().getClassLoader(),
				new CarrierLanguages.Carrier("absent", "net.forbric.NoSuchCarrier", "load", "net.forbric.NoSuchStore",
						"table", "fml.menu.mods")));
	}

	@Test
	void aLoaderThatThrowsDoesNotTakeTheBootWithIt() {
		StandIn.reset();
		StandIn.throwOnLoad = true;

		assertNull(CarrierLanguages.load(getClass().getClassLoader(), standIn()));
		assertSame(getClass().getClassLoader(), Thread.currentThread().getContextClassLoader(),
				"the context loader is restored even when the carrier's loader throws");
	}

	@Test
	void theSwitchIsOnByDefaultAndOffTurnsTheLoadIntoANoOp() {
		String previous = System.getProperty(CarrierLanguages.PROPERTY);
		try {
			System.clearProperty(CarrierLanguages.PROPERTY);
			assertTrue(CarrierLanguages.enabled(), "the carriers' text is loaded unless someone asks otherwise");

			System.setProperty(CarrierLanguages.PROPERTY, "off");
			assertFalse(CarrierLanguages.enabled());

			StandIn.reset();
			CarrierLanguages.loadBuiltins(getClass().getClassLoader());
			assertTrue(StandIn.table.isEmpty(), "with the switch off nothing is loaded at all");
		} finally {
			if (previous == null) System.clearProperty(CarrierLanguages.PROPERTY);
			else System.setProperty(CarrierLanguages.PROPERTY, previous);
		}
	}

	// ---- the staged half: the names against the carriers themselves ----

	@Test
	void everyCarriersLoaderAndTableAreWhereTheTableSaysTheyAre() throws IOException {
		Path neoforge = staged("neoforge-runtime", "neoforge-runtime.jar");
		Path forge = staged("forge-runtime", "forge-runtime.jar");
		assumeTrue(Files.isRegularFile(neoforge) && Files.isRegularFile(forge),
				"staged carriers absent: " + neoforge + " / " + forge);

		List<String> wrong = new ArrayList<>();
		for (CarrierLanguages.Carrier carrier : CarrierLanguages.carriers()) {
			Path jar = "forge".equals(carrier.label()) ? forge : neoforge;
			if (!hasMethod(jar, carrier.hookClass(), carrier.loadMethod(), "()V")) {
				wrong.add(carrier.hookClass() + "#" + carrier.loadMethod() + "() is not in " + jar.getFileName());
			}
			if (!hasField(jar, carrier.storeClass(), carrier.storeField())) {
				wrong.add(carrier.storeClass() + "." + carrier.storeField() + " is not in " + jar.getFileName());
			}
			if (!declaresTranslation(jar, carrier.probeKey())) {
				wrong.add(carrier.probeKey() + " is not in " + jar.getFileName() + "'s own lang file, so the probe "
						+ "can never resolve and the check would warn on a healthy load");
			}
		}

		assertTrue(wrong.isEmpty(), "CarrierLanguages reaches all of these inside a catch, so each one silently "
				+ "leaves the carrier's own screens rendering raw keys: " + wrong);
	}

	private static Path staged(String dir, String jar) {
		return Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run", dir, jar).normalize();
	}

	private static boolean hasMethod(Path jar, String binary, String name, String descriptor) throws IOException {
		boolean[] found = {false};
		visit(jar, binary, new ClassVisitor(Opcodes.ASM9) {
			@Override
			public MethodVisitor visitMethod(int access, String n, String d, String s, String[] e) {
				if (n.equals(name) && d.equals(descriptor) && (access & Opcodes.ACC_STATIC) != 0) found[0] = true;
				return null;
			}
		});
		return found[0];
	}

	private static boolean hasField(Path jar, String binary, String name) throws IOException {
		boolean[] found = {false};
		visit(jar, binary, new ClassVisitor(Opcodes.ASM9) {
			@Override
			public FieldVisitor visitField(int access, String n, String d, String s, Object v) {
				if (n.equals(name) && (access & Opcodes.ACC_STATIC) != 0) found[0] = true;
				return null;
			}
		});
		return found[0];
	}

	private static void visit(Path jar, String binary, ClassVisitor visitor) throws IOException {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry(binary.replace('.', '/') + ".class");
			if (entry == null) return;
			try (var in = zip.getInputStream(entry)) {
				new ClassReader(in).accept(visitor, ClassReader.SKIP_CODE);
			}
		}
	}

	/** Whether the carrier jar's own {@code assets/<ns>/lang/en_us.json} declares the key. */
	private static boolean declaresTranslation(Path jar, String key) throws IOException {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (ZipEntry entry : zip.stream().toList()) {
				if (!entry.getName().startsWith("assets/") || !entry.getName().endsWith("/lang/en_us.json")) continue;
				try (var in = zip.getInputStream(entry)) {
					if (new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
							.contains('"' + key + '"')) {
						return true;
					}
				}
			}
		}
		return false;
	}
}
