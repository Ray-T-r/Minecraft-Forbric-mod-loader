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

package net.forbric.kernel.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.api.LanguageAdapterException;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;

import net.forbric.api.Ecosystem;
import net.forbric.api.ModCatalog;
import net.forbric.kernel.boot.KernelFabricEcosystem;

/**
 * Language adapters are built when Fabric Loader builds them, and entrypoints resolve the way its
 * {@code DefaultLanguageAdapter} resolves them.
 *
 * <p>Two gaps, both silent. Configured Defaults does its whole job — copying a pack's default config files — in its
 * adapter's constructor and names the adapter from no entrypoint, so an adapter built on first use was never built.
 * And an entrypoint written as an instance method or as {@code ::<init>} was refused here though Fabric runs it.
 */
@ResourceLock("KernelFabricEcosystem")
@ResourceLock("ModCatalog")
@ResourceLock("system-properties")
class KernelLanguageAdaptersTest {
	static final List<String> RAN = Collections.synchronizedList(new ArrayList<>());

	@TempDir
	Path dir;

	private KernelFabricLoader loader;

	@BeforeEach
	void setUp() {
		KernelFabricLoader.resetForTests();
		RAN.clear();
		Constructing.built = 0;
		Throwing.tried = 0;
		Instance.receivers = 0;
		ByConstructor.built = 0;
		loader = KernelFabricLoader.create(EnvType.SERVER, dir, dir.resolve("config"), new String[0], "26.2");
		loader.setGameLoader(getClass().getClassLoader());
	}

	@AfterEach
	void tearDown() {
		KernelFabricLoader.resetForTests();
		KernelLanguageAdapters.reset();
		System.clearProperty(KernelLanguageAdapters.EAGER_PROPERTY);
		RAN.clear();
	}

	// ---- eager construction ----

	/** The Configured Defaults shape: an adapter no entrypoint names, whose constructor is the feature. */
	@Test
	void anAdapterNoEntrypointNamesIsStillConstructed() {
		register("defaults", "{}", "{\"defaults\":\"" + Constructing.class.getName() + "\"}");
		loader.freeze();

		List<KernelLanguageAdapters.Failure> failures = KernelLanguageAdapters.constructAll(List.of("defaults"));

		assertEquals(List.of(), failures);
		assertEquals(1, Constructing.built, "Fabric Loader's freeze() constructs every declared adapter");
	}

	/** Built once, and the instance an entrypoint gets later is the one built up front. */
	@Test
	void theUpFrontInstanceIsTheOneEntrypointsUse() throws Exception {
		register("defaults", "{}", "{\"defaults\":\"" + Constructing.class.getName() + "\"}");
		loader.freeze();

		KernelLanguageAdapters.constructAll(List.of("defaults", "defaults"));
		LanguageAdapter first = KernelLanguageAdapters.get("defaults");

		assertSame(first, KernelLanguageAdapters.get("defaults"));
		assertEquals(1, Constructing.built, "a name listed twice, and asked for again, is constructed once");
	}

	/**
	 * A constructor that threw is not run again — it may already have done part of its work — and every later
	 * request answers with that failure.
	 */
	@Test
	void aFailedAdapterIsReportedOnceAndNeverConstructedAgain() {
		register("broken", "{}", "{\"broken\":\"" + Throwing.class.getName() + "\"}");
		loader.freeze();

		List<KernelLanguageAdapters.Failure> failures = KernelLanguageAdapters.constructAll(List.of("broken"));

		assertEquals(1, failures.size());
		assertEquals("broken", failures.get(0).modId(), "the failure names the mod that declared the adapter");
		assertEquals(Throwing.class.getName(), failures.get(0).className());
		LanguageAdapterException again = assertThrows(LanguageAdapterException.class,
				() -> KernelLanguageAdapters.get("broken"));
		assertTrue(String.valueOf(again.getMessage()).contains("could not be constructed"), again.getMessage());
		assertEquals(List.of(), KernelLanguageAdapters.constructAll(List.of("broken")),
				"an adapter already known to have failed is not tried, nor reported, again");
		assertEquals(1, Throwing.tried);
	}

	/** In the order the mods come in, which is Fabric Loader's order; a name nothing declares is skipped. */
	@Test
	void adaptersAreConstructedInTheOrderGiven() {
		register("a", "{}", "{\"second\":\"" + Second.class.getName() + "\"}");
		register("b", "{}", "{\"defaults\":\"" + Constructing.class.getName() + "\"}");
		loader.freeze();

		KernelLanguageAdapters.constructAll(List.of("second", "nobody", "defaults"));

		assertEquals(List.of("second", "constructing"), RAN);
	}

	/** The boot step: every Fabric mod's adapters, and a failure marks the declaring mod's row. */
	@Test
	void theBootStepConstructsEveryDeclaredAdapterAndFailsTheModWhoseAdapterThrew() throws Exception {
		register("defaults", "{}", "{\"defaults\":\"" + Constructing.class.getName() + "\"}");
		register("broken", "{}", "{\"broken\":\"" + Throwing.class.getName() + "\"}");
		loader.freeze();
		List<ModCatalog.Entry> previous = ModCatalog.everything();
		ModCatalog.publish(List.of(row("defaults"), row("broken")));
		try {
			withEcosystemLoader(KernelFabricEcosystem::constructLanguageAdapters);

			assertEquals(1, Constructing.built);
			assertEquals(1, Throwing.tried);
			assertEquals(ModCatalog.Status.FAILED, status("broken"));
			assertEquals(ModCatalog.Status.OK, status("defaults"));
		} finally {
			ModCatalog.publish(previous);
		}
	}

	/** The escape hatch restores construction on first use. */
	@Test
	void offLeavesConstructionToTheFirstEntrypoint() throws Exception {
		System.setProperty(KernelLanguageAdapters.EAGER_PROPERTY, "off");
		register("defaults", "{}", "{\"defaults\":\"" + Constructing.class.getName() + "\"}");
		loader.freeze();

		withEcosystemLoader(KernelFabricEcosystem::constructLanguageAdapters);

		assertFalse(KernelLanguageAdapters.eager());
		assertEquals(0, Constructing.built);
	}

	// ---- entrypoint values, as Fabric's DefaultLanguageAdapter reads them ----

	/** {@code Cls::method} naming an instance method: bound to a fresh instance, as Fabric does. */
	@Test
	void anInstanceMethodEntrypointRunsOnANewInstance() {
		register("inst", "{\"main\":[\"" + Instance.class.getName() + "::init\"]}", "{}");
		loader.freeze();

		runMain();

		assertEquals(List.of("instance.init"), RAN);
		assertEquals(1, Instance.receivers, "one receiver, made through the no-arg constructor");
	}

	/** {@code Cls::<init>}: the constructor itself is the entrypoint. */
	@Test
	void aConstructorEntrypointRunsTheConstructor() {
		register("ctor", "{\"main\":[\"" + ByConstructor.class.getName() + "::<init>\"]}", "{}");
		loader.freeze();

		List<EntrypointContainer<ModInitializer>> main = loader.getEntrypointContainers("main", ModInitializer.class);
		assertEquals(1, main.size(), "provides() must not drop it: getDeclaredMethods never lists <init>");
		main.get(0).getEntrypoint().onInitialize();

		assertEquals(List.of("ByConstructor.<init>"), RAN);
		assertEquals(1, ByConstructor.built);
	}

	/** What already worked keeps working: a static method, a static field, and a plain class. */
	@Test
	void staticMembersAndPlainClassesResolveAsBefore() {
		register("statics", "{\"main\":[\"" + Statics.class.getName() + "::init\",\"" + Statics.class.getName()
				+ "::FIELD\",\"" + Plain.class.getName() + "\"]}", "{}");
		loader.freeze();

		runMain();

		assertEquals(List.of("Statics.init", "Statics.FIELD", "Plain"), RAN);
	}

	/**
	 * Of several members of one name Fabric refuses the value; here the first static one is taken, as it always
	 * was — no mod can depend on that, because none runs on Fabric.
	 */
	@Test
	void ofSeveralMethodsOfOneNameTheStaticOneIsStillTaken() {
		register("overloads", "{\"main\":[\"" + Overloads.class.getName() + "::go\"]}", "{}");
		loader.freeze();

		runMain();

		assertEquals(List.of("Overloads.go static"), RAN);
	}

	/** A third-party adapter delegating to the default one meets the same resolution. */
	@Test
	void theDefaultAdapterResolvesInstanceMethodsAndConstructorsToo() throws Exception {
		register("inst", "{}", "{}");
		loader.freeze();
		ModContainer mod = loader.getModContainer("inst").orElseThrow();

		ModInitializer viaMethod = LanguageAdapter.getDefault().create(mod, Instance.class.getName() + "::init",
				ModInitializer.class);
		ModInitializer viaConstructor = LanguageAdapter.getDefault().create(mod,
				ByConstructor.class.getName() + "::<init>", ModInitializer.class);
		viaMethod.onInitialize();
		viaConstructor.onInitialize();
		viaConstructor.onInitialize();

		assertEquals(List.of("instance.init", "ByConstructor.<init>", "ByConstructor.<init>"), RAN);
		assertNotSame(viaMethod, viaConstructor);
	}

	@Test
	void aMemberThatDoesNotExistIsStillAnError() {
		register("missing", "{}", "{}");
		loader.freeze();
		ModContainer mod = loader.getModContainer("missing").orElseThrow();

		assertThrows(LanguageAdapterException.class, () -> LanguageAdapter.getDefault().create(mod,
				Statics.class.getName() + "::absent", ModInitializer.class));
	}

	// ---- helpers ----

	private void runMain() {
		for (EntrypointContainer<ModInitializer> c : loader.getEntrypointContainers("main", ModInitializer.class)) {
			c.getEntrypoint().onInitialize();
		}
	}

	private void register(String id, String entrypoints, String languageAdapters) {
		String json = "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1\",\"entrypoints\":" + entrypoints
				+ ",\"languageAdapters\":" + languageAdapters + "}";
		loader.register(new KernelModContainer(FabricModMetadataParser.read(new StringReader(json)), null, null));
	}

	private void withEcosystemLoader(Runnable action) throws Exception {
		Field active = KernelFabricEcosystem.class.getDeclaredField("loader");
		active.setAccessible(true);
		Object previous = active.get(null);
		try {
			active.set(null, loader);
			action.run();
		} finally {
			active.set(null, previous);
		}
	}

	private static ModCatalog.Entry row(String id) {
		return new ModCatalog.Entry(Ecosystem.FABRIC, id, id, "1", "", List.of(), id + ".jar", "", "");
	}

	private static ModCatalog.Status status(String id) {
		for (ModCatalog.Entry e : ModCatalog.everything()) {
			if (e.modId().equals(id)) return e.status();
		}
		throw new AssertionError("no row for " + id);
	}

	// ---- fixtures ----

	public static final class Constructing implements LanguageAdapter {
		static int built;

		public Constructing() {
			built++;
			RAN.add("constructing");
		}

		@Override
		public <T> T create(ModContainer mod, String value, Class<T> type) throws LanguageAdapterException {
			return LanguageAdapter.getDefault().create(mod, value, type);
		}
	}

	public static final class Second implements LanguageAdapter {
		public Second() {
			RAN.add("second");
		}

		@Override
		public <T> T create(ModContainer mod, String value, Class<T> type) throws LanguageAdapterException {
			return LanguageAdapter.getDefault().create(mod, value, type);
		}
	}

	public static final class Throwing implements LanguageAdapter {
		static int tried;

		public Throwing() {
			tried++;
			throw new IllegalStateException("this adapter cannot be built");
		}

		@Override
		public <T> T create(ModContainer mod, String value, Class<T> type) throws LanguageAdapterException {
			throw new LanguageAdapterException("unreachable");
		}
	}

	public static final class Instance {
		static int receivers;

		public Instance() {
			receivers++;
		}

		public void init() {
			RAN.add("instance.init");
		}
	}

	public static final class ByConstructor {
		static int built;

		public ByConstructor() {
			built++;
			RAN.add("ByConstructor.<init>");
		}
	}

	public static final class Statics {
		public static final ModInitializer FIELD = () -> RAN.add("Statics.FIELD");

		public static void init() {
			RAN.add("Statics.init");
		}
	}

	public static final class Plain implements ModInitializer {
		@Override
		public void onInitialize() {
			RAN.add("Plain");
		}
	}

	public static final class Overloads {
		public static void go() {
			RAN.add("Overloads.go static");
		}

		public void go(int ignored) {
			RAN.add("Overloads.go instance");
		}
	}
}
