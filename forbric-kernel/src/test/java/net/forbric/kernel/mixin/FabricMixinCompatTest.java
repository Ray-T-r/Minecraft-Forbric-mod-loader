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

package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.FabricUtil;

import net.fabricmc.loader.api.metadata.ModDependency;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.fabric.KernelMetadataSupport.SimpleModDependency;

/**
 * Each Fabric mod gets the Mixin compatibility level Fabric Loader gives it.
 *
 * <p>The cases are the shapes mods actually declare for {@code fabricloader}; the second half runs every one of them
 * through Fabric Loader 0.19.3 itself, from the local Minecraft libraries, and asks for the same answer.
 */
class FabricMixinCompatTest {
	/** {@code {depends..., "breaks:" + predicate...}} and the level Fabric Loader 0.19.3 gives. */
	private static final Object[][] CASES = {
			{List.of(), FabricUtil.COMPATIBILITY_0_9_2},
			{List.of("*"), FabricUtil.COMPATIBILITY_0_9_2},
			{List.of("<0.20"), FabricUtil.COMPATIBILITY_0_9_2},
			{List.of(">=0.11.0"), FabricUtil.COMPATIBILITY_0_9_2},
			{List.of(">=0.12.0"), FabricUtil.COMPATIBILITY_0_10_0},
			{List.of(">=0.14.21"), FabricUtil.COMPATIBILITY_0_10_0},
			{List.of(">=0.15.11"), FabricUtil.COMPATIBILITY_0_10_0},
			{List.of(">=0.16.0"), FabricUtil.COMPATIBILITY_0_14_0},
			{List.of(">=0.16.10"), FabricUtil.COMPATIBILITY_0_14_0},
			{List.of(">=0.17.3"), FabricUtil.COMPATIBILITY_0_16_5},
			{List.of(">=0.18.4"), FabricUtil.COMPATIBILITY_0_17_0},
			{List.of(">0.18.4"), FabricUtil.COMPATIBILITY_0_17_0},
			{List.of("=0.18.4"), FabricUtil.COMPATIBILITY_0_17_0},
			{List.of(">=0.19.0-beta.1"), FabricUtil.COMPATIBILITY_0_17_0},
			{List.of(">=0.19.0"), FabricUtil.COMPATIBILITY_0_17_1},
			{List.of(">=0.19.3"), FabricUtil.COMPATIBILITY_0_17_1},
			{List.of(">=0.19.3+build.7"), FabricUtil.COMPATIBILITY_0_17_1},
			{List.of("~0.16.10"), FabricUtil.COMPATIBILITY_0_14_0},
			{List.of("^0.18.4"), FabricUtil.COMPATIBILITY_0_17_0},
			{List.of("0.17.x"), FabricUtil.COMPATIBILITY_0_14_0},
			{List.of("0.x"), FabricUtil.COMPATIBILITY_0_9_2},
			{List.of(">=0.15 <0.20"), FabricUtil.COMPATIBILITY_0_10_0},
			{List.of(">=0.17.3", ">=0.15"), FabricUtil.COMPATIBILITY_0_10_0},
			{List.of(">=0.14", "&>=0.16.5"), FabricUtil.COMPATIBILITY_0_14_0},
			{List.of("*", "breaks:<0.16"), FabricUtil.COMPATIBILITY_0_14_0},
			{List.of(">=0.12", "breaks:<0.17.3"), FabricUtil.COMPATIBILITY_0_16_5},
			{List.of(">=0.12", "breaks:>=0.20"), FabricUtil.COMPATIBILITY_0_10_0},
			// What is left starts at 0.16-, the lowest pre-release of 0.16.0, which sorts below the 0.16.0 row.
			{List.of(">=0.15", "breaks:0.15.x"), FabricUtil.COMPATIBILITY_0_10_0},
			{List.of(">=0.15", "breaks:=0.15.0"), FabricUtil.COMPATIBILITY_0_10_0},
			// Fabric's own version order at the rows: a numeric pre-release is still below its release, a short core is
			// padded before the pre-release is compared, and "0.12.x" floors at "0.12-". The kernel's dependency
			// comparator decides each of these the other way.
			{List.of(">=0.16.0-0"), FabricUtil.COMPATIBILITY_0_10_0},
			{List.of(">=0.16.0-1"), FabricUtil.COMPATIBILITY_0_10_0},
			{List.of(">=0.16.0-01"), FabricUtil.COMPATIBILITY_0_10_0},
			{List.of(">=0.16-0.1"), FabricUtil.COMPATIBILITY_0_10_0},
			{List.of(">=0.17.3-1"), FabricUtil.COMPATIBILITY_0_14_0},
			{List.of(">=0.18.4-0"), FabricUtil.COMPATIBILITY_0_16_5},
			{List.of(">=0.19.0-0"), FabricUtil.COMPATIBILITY_0_17_0},
			{List.of("*", "breaks:<0.17.3-0"), FabricUtil.COMPATIBILITY_0_14_0},
			{List.of(">=0.12-"), FabricUtil.COMPATIBILITY_0_10_0},
			{List.of(">=0.12-alpha"), FabricUtil.COMPATIBILITY_0_10_0},
			{List.of(">=0.12-rc.1"), FabricUtil.COMPATIBILITY_0_10_0},
			{List.of("^0.12-"), FabricUtil.COMPATIBILITY_0_10_0},
			{List.of(">0.12-"), FabricUtil.COMPATIBILITY_0_10_0},
			{List.of(">=0.15", "0.12.x"), FabricUtil.COMPATIBILITY_0_10_0},
			{List.of(">=0.16.0-beta.1"), FabricUtil.COMPATIBILITY_0_10_0},
			{List.of(">=0.16.0-beta.1 <0.17"), FabricUtil.COMPATIBILITY_0_10_0},
	};

	@Test
	void eachDeclaredShapeGetsFabricLoadersLevel() {
		for (Object[] row : CASES) {
			@SuppressWarnings("unchecked")
			List<String> spec = (List<String>) row[0];
			assertEquals((int) row[1], FabricMixinCompat.levelFor(dependencies("fabricloader", spec)), String.valueOf(spec));
		}
	}

	@Test
	void theOldIdIsReadToo() {
		assertEquals(FabricUtil.COMPATIBILITY_0_17_0, FabricMixinCompat.levelFor(
				dependencies("fabric-loader", List.of(">=0.18.4"))));
	}

	@Test
	void otherDependenciesAreNotRead() {
		Collection<ModDependency> deps = new ArrayList<>(dependencies("fabricloader", List.of(">=0.16.0")));
		deps.add(new SimpleModDependency(ModDependency.Kind.DEPENDS, "minecraft", List.of(">=1.0.0")));
		deps.add(new SimpleModDependency(ModDependency.Kind.RECOMMENDS, "fabricloader", List.of(">=0.19.0")));
		assertEquals(FabricUtil.COMPATIBILITY_0_14_0, FabricMixinCompat.levelFor(deps));
	}

	/**
	 * The table and the bundled Mixin move together: a row whose level the bundled Mixin does not know is read as its
	 * latest, so a newer Fabric Loader's table without the Mixin it ships with would decide nothing.
	 */
	@Test
	void theNewestRowIsTheBundledMixinsLatestLevel() {
		assertEquals(FabricUtil.COMPATIBILITY_LATEST, FabricMixinCompat.newestLevel());
	}

	/** Every case through Fabric Loader 0.19.3's own predicate parser, interval algebra and table. */
	@Test
	void fabricLoaderItselfGivesTheSameLevels() throws Exception {
		Path jar = fabricLoaderJar();
		TestFixtures.requireFiles(TestFixtures.Fixture.THIRD_PARTY, "Fabric Loader 0.19.3 in a local launcher's libraries",
				jar);

		try (URLClassLoader real = new URLClassLoader(new URL[] {jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
			RealFabric fabric = new RealFabric(real);
			for (Object[] row : CASES) {
				@SuppressWarnings("unchecked")
				List<String> spec = (List<String>) row[0];
				assertEquals(fabric.level(spec), FabricMixinCompat.levelFor(dependencies("fabricloader", spec)),
						"Fabric Loader 0.19.3 and the kernel disagree on " + spec);
			}
		}
	}

	/** Version pairs at the edges of Fabric's order: pre-releases, padding, numeric identifiers, build metadata. */
	private static final String[] VERSIONS = {"0.12", "0.12.0", "0.12.0-", "0.12-", "0.12-alpha", "0.12.0-alpha",
			"0.12.0-alpha.1", "0.12.0-alpha.beta", "0.12.0-1", "0.12.0-01", "0.12.0-10", "0.12.0-2", "0.12.0-rc.1",
			"0.12.0+build.3", "0.12.0-rc.1+build", "0.12.1", "0.16.0-0", "0.16.0", "0.16", "0.16-0.1", "1.0.0-A", "1.0.0-a",
			"0.19.3", "0.19.10", "0.2.0", "1", "1.0.0.0", "1.0.0.0.1"};

	/** The comparator the level is read with orders versions exactly as Fabric Loader 0.19.3's SemanticVersionImpl. */
	@Test
	void versionsAreOrderedAsFabricLoaderOrdersThem() throws Exception {
		Path jar = fabricLoaderJar();
		TestFixtures.requireFiles(TestFixtures.Fixture.THIRD_PARTY, "Fabric Loader 0.19.3 in a local launcher's libraries",
				jar);

		try (URLClassLoader real = new URLClassLoader(new URL[] {jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
			Method parse = Class.forName("net.fabricmc.loader.api.Version", true, real).getMethod("parse", String.class);
			for (String a : VERSIONS) {
				for (String b : VERSIONS) {
					@SuppressWarnings("unchecked")
					Comparable<Object> left = (Comparable<Object>) parse.invoke(null, a);
					int fabric = Integer.signum(left.compareTo(parse.invoke(null, b)));
					assertEquals(fabric, Integer.signum(FabricMixinCompat.compare(a, b)), a + " vs " + b);
				}
			}
		}
	}

	/**
	 * Fabric Loader 0.19.3 — the version the kernel reports, whose table it carries — where a launcher that installed a
	 * Fabric profile keeps it: {@code -Pforbric.fabricLoaderJar} (Gradle hands the test JVM
	 * {@code forbric.fabricLoaderJar}), the fixture Minecraft directory, or the launcher's
	 * own directory. Vanilla's library list has no Fabric Loader, so this is a third-party jar, not part of the
	 * Minecraft-libraries fixture.
	 */
	private static Path fabricLoaderJar() {
		String explicit = System.getProperty("forbric.fabricLoaderJar");
		if (explicit != null && !explicit.isBlank()) return Path.of(explicit);

		String relative = "libraries/net/fabricmc/fabric-loader/0.19.3/fabric-loader-0.19.3.jar";
		String home = System.getProperty("user.home");
		List<Path> candidates = new ArrayList<>(List.of(TestFixtures.minecraftDir().resolve(relative),
				Path.of(home, "Library/Application Support/minecraft").resolve(relative), Path.of(home, ".minecraft").resolve(relative)));
		String appData = System.getenv("APPDATA");
		if (appData != null) candidates.add(Path.of(appData, ".minecraft").resolve(relative));
		for (Path candidate : candidates) {
			if (java.nio.file.Files.isRegularFile(candidate)) return candidate;
		}
		return candidates.get(0);
	}

	/**
	 * {@code spec}: each plain entry is one {@code depends} predicate of ONE dependency (an array, OR-joined); an
	 * entry starting {@code &} is a second {@code depends} entry, and {@code breaks:} a {@code breaks} one.
	 */
	private static List<ModDependency> dependencies(String id, List<String> spec) {
		List<ModDependency> deps = new ArrayList<>();
		List<String> depends = new ArrayList<>();
		for (String entry : spec) {
			if (entry.startsWith("breaks:")) {
				deps.add(new SimpleModDependency(ModDependency.Kind.BREAKS, id, List.of(entry.substring(7))));
			} else if (entry.startsWith("&")) {
				deps.add(new SimpleModDependency(ModDependency.Kind.DEPENDS, id, List.of(entry.substring(1))));
			} else {
				depends.add(entry);
			}
		}
		if (!depends.isEmpty()) deps.add(0, new SimpleModDependency(ModDependency.Kind.DEPENDS, id, depends));
		return deps;
	}

	/** Fabric Loader's {@code getMixinCompat}, line for line, on Fabric Loader's own classes. */
	private static final class RealFabric {
		private final Method parse;
		private final Method getInterval;
		private final Method or;
		private final Method and;
		private final Method not;
		private final Method getMin;
		private final List<?> table;
		private final Field loaderVersion;
		private final Field mixinVersion;

		RealFabric(ClassLoader loader) throws Exception {
			Class<?> predicate = Class.forName("net.fabricmc.loader.api.metadata.version.VersionPredicate", true, loader);
			Class<?> interval = Class.forName("net.fabricmc.loader.api.metadata.version.VersionInterval", true, loader);
			parse = predicate.getMethod("parse", String.class);
			getInterval = predicate.getMethod("getInterval");
			or = interval.getMethod("or", Collection.class, interval);
			and = interval.getMethod("and", Collection.class, Collection.class);
			not = interval.getMethod("not", Collection.class);
			getMin = interval.getMethod("getMin");

			Class<?> versions = Class.forName("net.fabricmc.loader.impl.launch.FabricMixinVersions", true, loader);
			Method getVersions = versions.getDeclaredMethod("getVersions");
			getVersions.setAccessible(true);
			table = (List<?>) getVersions.invoke(null);
			Class<?> entry = Class.forName("net.fabricmc.loader.impl.launch.FabricMixinVersions$LoaderMixinVersionEntry",
					true, loader);
			loaderVersion = entry.getDeclaredField("loaderVersion");
			mixinVersion = entry.getDeclaredField("mixinVersion");
			loaderVersion.setAccessible(true);
			mixinVersion.setAccessible(true);
		}

		@SuppressWarnings({"unchecked", "rawtypes"})
		int level(List<String> spec) throws Exception {
			List req = List.of(infinite());
			for (ModDependency dep : dependencies("fabricloader", spec)) {
				List<Object> intervals = List.of();
				for (String constraint : ((SimpleModDependency) dep).getConstraints()) {
					intervals = (List<Object>) or.invoke(null, intervals, getInterval.invoke(parse.invoke(null, constraint)));
				}
				req = (List) (dep.getKind() == ModDependency.Kind.DEPENDS
						? and.invoke(null, req, intervals)
						: and.invoke(null, req, not.invoke(null, intervals)));
			}
			if (req.isEmpty()) return FabricUtil.COMPATIBILITY_0_9_2;
			Comparable<Object> min = (Comparable<Object>) getMin.invoke(req.get(0));
			if (min != null) {
				for (Object row : table) {
					if (min.compareTo(loaderVersion.get(row)) >= 0) return (int) mixinVersion.get(row);
				}
			}
			return FabricUtil.COMPATIBILITY_0_9_2;
		}

		private Object infinite() throws Exception {
			Class<?> interval = getMin.getDeclaringClass();
			return interval.getField("INFINITE").get(null);
		}
	}
}
