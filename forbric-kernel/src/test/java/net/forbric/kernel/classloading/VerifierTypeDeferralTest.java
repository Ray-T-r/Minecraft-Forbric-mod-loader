/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.classloading;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Array;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.transform.InjectorExecution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;

/**
 * A class defined while a Mixin weave is open is verified without defining game classes it does not run.
 *
 * <p>The fixtures are written for this test, not lifted from a mod: a platform interface found through
 * {@link java.util.ServiceLoader} from a holder class, a provider that hands one "game" interface to a parameter
 * declared as its super-interface in nine different shapes (an argument, a deeper argument with a {@code long} above
 * it, a merge of two branches into one local, a return, a field store, a chained store that javac writes with
 * {@code dup_x1}, a protected range, an array return and an array loop), and a startup class that reads the platform
 * in its static initializer. None of their names or method names resembles the mod this was found in. The last test
 * runs the same mechanism over that mod's real provider.
 */
class VerifierTypeDeferralTest {
	private static final String PROVIDER = "fixture/platform/WorkingDirectories";

	private static Map<String, String> sources() {
		Map<String, String> sources = new HashMap<>();
		sources.put("fixture.world.Viewable", "package fixture.world; public interface Viewable { }");
		sources.put("fixture.world.Shaded", "package fixture.world; public interface Shaded extends Viewable { }");
		sources.put("fixture.world.Sample",
				"package fixture.world; public final class Sample implements Shaded { public String toString() { return \"sample\"; } }");
		sources.put("fixture.world.Surfaces", """
				package fixture.world;
				public final class Surfaces {
					public static String look(Viewable seen) { return "seen:" + seen; }
					public static String pair(Viewable seen, String tag) { return tag + ":" + seen; }
					public static String weigh(Viewable seen, long weight, String unit) { return seen + "=" + weight + unit; }
				}""");
		sources.put("fixture.world.Base", """
				package fixture.world;
				public class Base { public static String take(Base base) { return "base:" + base.getClass().getSimpleName(); } }""");
		sources.put("fixture.platform.Locations", """
				package fixture.platform;
				public interface Locations {
					String root();
					static Locations get() { return Holder.FOUND; }
				}
				final class Holder {
					static final Locations FOUND = java.util.ServiceLoader.load(Locations.class).findFirst().orElseThrow();
				}""");
		sources.put("fixture.platform.WorkingDirectories", """
				package fixture.platform;
				import fixture.world.*;
				public class WorkingDirectories implements Locations {
					private Viewable last;
					public WorkingDirectories() { }
					public String root() { return "root"; }
					public String direct(Shaded view) { return Surfaces.look(view); }
					public String deeper(Shaded view) { return Surfaces.pair(view, "tag"); }
					public String wide(Shaded view) { return Surfaces.weigh(view, 7L, "kg"); }
					public String merged(Shaded view, Viewable other, boolean pick) {
						Viewable chosen;
						if (pick) chosen = view; else chosen = other;
						return Surfaces.look(chosen);
					}
					public Viewable widen(Shaded view) { return view; }
					public String keep(Shaded view) { last = view; return String.valueOf(last); }
					public String chained(Shaded view) { return String.valueOf(last = view); }
					public String guarded(Shaded view) {
						try { return Surfaces.look(view); } catch (RuntimeException failed) { return "failed"; }
					}
					public Viewable[] all(Shaded[] views) { return views; }
					public int count(Shaded[] views) { int found = 0; for (Shaded one : views) if (one != null) found++; return found; }
				}""");
		// Same capability, only exact and JDK types: nothing for the verifier to load from the game.
		sources.put("fixture.platform.ExactTypes", """
				package fixture.platform;
				import fixture.world.*;
				public class ExactTypes {
					public String exact(Viewable seen) { return Surfaces.look(seen); }
					public java.util.Collection<String> jdk() { java.util.ArrayList<String> list = new java.util.ArrayList<>(); return list; }
				}""");
		// The same shape -- this class handed to a parameter of a game type -- where the game type is its own superclass,
		// which the JVM defines before it verifies anything. Not the problem.
		sources.put("fixture.platform.OwnAncestry", """
				package fixture.platform;
				public class OwnAncestry extends fixture.world.Base { public String self() { return fixture.world.Base.take(this); } }""");
		sources.put("fixture.boot.StartupPlugin", """
				package fixture.boot;
				public class StartupPlugin { public static final String ROOT = fixture.platform.Locations.get().root(); }""");
		// The platform reached from a method Mixin calls while preparing, instead of from a static initializer.
		sources.put("fixture.boot.LazyPlugin", """
				package fixture.boot;
				public class LazyPlugin { public boolean shouldApply(String target) { return fixture.platform.Locations.get().root().equals(target); } }""");
		// Code that RUNS a game type inside the weave: that class must exist then, and no rewrite pretends otherwise.
		sources.put("fixture.boot.EagerPlugin", """
				package fixture.boot;
				public class EagerPlugin { public static final Object MADE = new fixture.world.Sample(); }""");
		sources.put("fixture.boot.Trigger", "package fixture.boot; public class Trigger { }");
		return sources;
	}

	private static String[] supertypes(Map<String, byte[]> classes, String name) {
		byte[] bytes = classes.get(name);
		if (bytes == null) return null;
		ClassReader reader = new ClassReader(bytes);
		List<String> all = new ArrayList<>();
		all.add(reader.getSuperName());
		all.addAll(List.of(reader.getInterfaces()));
		return all.toArray(String[]::new);
	}

	/** Defines from the map and remembers the order, exactly as the JVM asks. */
	private static final class Recording extends ClassLoader {
		private final Map<String, byte[]> classes;
		final List<String> defined = new ArrayList<>();

		Recording(Map<String, byte[]> classes) {
			super(Recording.class.getClassLoader());
			this.classes = classes;
		}

		@Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			synchronized (getClassLoadingLock(name)) {
				Class<?> found = findLoadedClass(name);
				if (found != null) return found;
				byte[] bytes = classes.get(name.replace('.', '/'));
				if (bytes == null) return super.loadClass(name, resolve);
				defined.add(name);
				return defineClass(name, bytes, 0, bytes.length);
			}
		}
	}

	private static List<String> world(List<String> names) {
		return names.stream().filter(name -> name.startsWith("fixture.world.")).toList();
	}

	@Test void aProviderVerifiedInsideTheWeaveDefinesNoneOfTheTypesItOnlyMentions(@TempDir Path work) throws Exception {
		Map<String, byte[]> classes = InjectorExecution.compile(work, sources());
		byte[] original = classes.get(PROVIDER);
		VerifierTypeDeferral.Result result = VerifierTypeDeferral.rewrite(original,
				name -> name.startsWith("fixture/world/"), name -> supertypes(classes, name));
		assertTrue(result.changed());
		assertTrue(result.deferred().contains("fixture/world/Viewable"), result.deferred()::toString);
		assertEquals(9, result.methods(), "every method whose verification needs a game type, and not count(), whose loop keeps one type");

		Recording before = new Recording(classes);
		Class.forName(PROVIDER.replace('/', '.'), false, before).getDeclaredConstructors();
		assertTrue(world(before.defined).contains("fixture.world.Viewable"),
				"the control must show the verifier defining the super-interface: " + before.defined);

		Map<String, byte[]> rewritten = new HashMap<>(classes);
		rewritten.put(PROVIDER, result.bytes());
		Recording after = new Recording(rewritten);
		Class<?> provider = Class.forName(PROVIDER.replace('/', '.'), false, after);
		provider.getDeclaredConstructors();
		assertEquals(List.of(), world(after.defined), "verifying the rewritten provider defined game types");

		// Running it is unchanged, and THAT is when the game types are defined.
		assertEquals(behaviour(before), behaviour(after));
		assertTrue(world(after.defined).contains("fixture.world.Viewable"));
	}

	private static List<Object> behaviour(Recording loader) throws Exception {
		Class<?> provider = Class.forName(PROVIDER.replace('/', '.'), true, loader);
		Class<?> shaded = Class.forName("fixture.world.Shaded", false, loader);
		Class<?> viewable = Class.forName("fixture.world.Viewable", false, loader);
		Object instance = provider.getConstructor().newInstance();
		Object sample = Class.forName("fixture.world.Sample", true, loader).getConstructor().newInstance();
		Object views = Array.newInstance(shaded, 3);
		Array.set(views, 0, sample);
		Array.set(views, 2, sample);
		List<Object> out = new ArrayList<>();
		out.add(provider.getMethod("direct", shaded).invoke(instance, sample));
		out.add(provider.getMethod("deeper", shaded).invoke(instance, sample));
		out.add(provider.getMethod("wide", shaded).invoke(instance, sample));
		out.add(provider.getMethod("merged", shaded, viewable, boolean.class).invoke(instance, sample, null, true));
		out.add(provider.getMethod("merged", shaded, viewable, boolean.class).invoke(instance, null, sample, false));
		out.add(provider.getMethod("widen", shaded).invoke(instance, sample) == sample);
		out.add(provider.getMethod("keep", shaded).invoke(instance, sample));
		out.add(provider.getMethod("chained", shaded).invoke(instance, sample));
		out.add(provider.getMethod("guarded", shaded).invoke(instance, sample));
		out.add(provider.getMethod("all", views.getClass()).invoke(instance, views) == views);
		out.add(provider.getMethod("count", views.getClass()).invoke(instance, views));
		return out;
	}

	@Test void aClassWhoseVerificationNeedsNoUndefinedTypeIsLeftAsWritten(@TempDir Path work) throws Exception {
		Map<String, byte[]> classes = InjectorExecution.compile(work, sources());
		byte[] exact = classes.get("fixture/platform/ExactTypes");
		assertSame(exact, VerifierTypeDeferral.rewrite(exact, name -> name.startsWith("fixture/world/"),
				name -> supertypes(classes, name)).bytes());
		// Its own ancestry is defined before verification starts, so naming it is not deferrable.
		byte[] ancestry = classes.get("fixture/platform/OwnAncestry");
		assertSame(ancestry, VerifierTypeDeferral.rewrite(ancestry, name -> name.startsWith("fixture/world/"),
				name -> supertypes(classes, name)).bytes());
		// And the provider, once the game types it mentions are already defined, has nothing to defer.
		byte[] provider = classes.get(PROVIDER);
		assertSame(provider, VerifierTypeDeferral.rewrite(provider, name -> false, name -> supertypes(classes, name)).bytes());
	}

	// ---- the loader: inside a weave, and only there ----

	private static ForbricClassLoader loader(Path work) throws Exception {
		Map<String, byte[]> classes = InjectorExecution.compile(work, sources());
		Path root = Files.createDirectories(work.resolve("owned"));
		for (Map.Entry<String, byte[]> one : classes.entrySet()) {
			Path file = root.resolve(one.getKey() + ".class");
			Files.createDirectories(file.getParent());
			Files.write(file, one.getValue());
		}
		Path services = Files.createDirectories(root.resolve("META-INF/services"));
		Files.writeString(services.resolve("fixture.platform.Locations"), "fixture.platform.WorkingDirectories\n", StandardCharsets.UTF_8);
		return new ForbricClassLoader(new URL[] {root.toUri().toURL()}, VerifierTypeDeferralTest.class.getClassLoader());
	}

	/**
	 * Loads {@code outer} through {@code loader} and, while Mixin would be weaving it, runs {@code during} -- what Mixin
	 * does there when it builds a config plugin. Returns every class that reached the weaver while that was open.
	 */
	private static List<String> weave(ForbricClassLoader loader, String outer, Callable<?> during) throws Exception {
		List<String> inside = new ArrayList<>();
		weave(loader, outer, during, inside);
		return inside;
	}

	/** {@link #weave(ForbricClassLoader, String, Callable)} into {@code inside}, which keeps what it saw if this throws. */
	private static void weave(ForbricClassLoader loader, String outer, Callable<?> during, List<String> inside) throws Exception {
		boolean[] open = {false};
		loader.setMixinTransformer((name, bytes) -> {
			if (open[0]) inside.add(name);
			if (name.equals(outer) && !open[0]) {
				open[0] = true;
				try {
					during.call();
				} catch (Exception failed) {
					throw new IllegalStateException(failed);
				} finally {
					open[0] = false;
				}
			}
			return bytes;
		});
		Thread thread = Thread.currentThread();
		ClassLoader context = thread.getContextClassLoader();
		thread.setContextClassLoader(loader);
		try {
			loader.loadClass(outer);
		} finally {
			thread.setContextClassLoader(context);
		}
	}

	@Test void aPluginBuiltDuringTheWeaveNoLongerDefinesGameTypesThroughItsProvider(@TempDir Path work) throws Exception {
		try (ForbricClassLoader loader = loader(work)) {
			List<String> inside = weave(loader, "fixture.boot.Trigger", () -> Class.forName("fixture.boot.StartupPlugin", true, loader));
			assertTrue(inside.containsAll(List.of("fixture.boot.StartupPlugin", "fixture.platform.Locations",
					"fixture.platform.WorkingDirectories")), inside::toString);
			assertEquals(List.of(), world(inside), "game types were defined while the weave was open");

			assertEquals("root", Class.forName("fixture.boot.StartupPlugin", false, loader).getField("ROOT").get(null));
			Object provider = Class.forName("fixture.platform.Locations", false, loader).getMethod("get").invoke(null);
			Class<?> shaded = Class.forName("fixture.world.Shaded", false, loader);
			Object sample = Class.forName("fixture.world.Sample", true, loader).getConstructor().newInstance();
			assertEquals("seen:sample", provider.getClass().getMethod("direct", shaded).invoke(provider, sample));
		}
	}

	@Test void aPluginAskedWhileConfigsArePreparedIsCoveredTheSameWay(@TempDir Path work) throws Exception {
		try (ForbricClassLoader loader = loader(work)) {
			List<String> inside = weave(loader, "fixture.boot.Trigger", () -> {
				Class<?> plugin = Class.forName("fixture.boot.LazyPlugin", true, loader);
				return plugin.getMethod("shouldApply", String.class).invoke(plugin.getConstructor().newInstance(), "root");
			});
			assertTrue(inside.contains("fixture.platform.WorkingDirectories"), inside::toString);
			assertEquals(List.of(), world(inside), "game types were defined while the weave was open");
		}
	}

	@Test void codeThatRunsAGameTypeInsideTheWeaveStillGetsIt(@TempDir Path work) throws Exception {
		try (ForbricClassLoader loader = loader(work)) {
			List<String> inside = weave(loader, "fixture.boot.Trigger", () -> Class.forName("fixture.boot.EagerPlugin", true, loader));
			assertTrue(world(inside).contains("fixture.world.Sample"), inside::toString);
			assertEquals("sample", String.valueOf(Class.forName("fixture.boot.EagerPlugin", false, loader).getField("MADE").get(null)));
		}
	}

	@Test void switchedOffTheVerifierDefinesThemInsideTheWeaveAgain(@TempDir Path work) throws Exception {
		String saved = System.getProperty(ForbricClassLoader.DEFER_LINK_TIME_TYPES);
		System.setProperty(ForbricClassLoader.DEFER_LINK_TIME_TYPES, "off");
		try (ForbricClassLoader loader = loader(work)) {
			List<String> inside = weave(loader, "fixture.boot.Trigger", () -> Class.forName("fixture.boot.StartupPlugin", true, loader));
			assertTrue(world(inside).contains("fixture.world.Viewable"), inside::toString);
		} finally {
			if (saved == null) System.clearProperty(ForbricClassLoader.DEFER_LINK_TIME_TYPES);
			else System.setProperty(ForbricClassLoader.DEFER_LINK_TIME_TYPES, saved);
		}
	}

	@Test void outsideAnyWeaveTheProviderIsDefinedAsWritten(@TempDir Path work) throws Exception {
		try (ForbricClassLoader loader = loader(work)) {
			List<String> seen = new ArrayList<>();
			loader.setMixinTransformer((name, bytes) -> {
				seen.add(name);
				return bytes;
			});
			Class.forName("fixture.platform.WorkingDirectories", false, loader).getDeclaredConstructors();
			assertTrue(world(seen).contains("fixture.world.Viewable"),
					"no weave was open, so verification must behave exactly as the JVM's: " + seen);
		}
	}

	// ---- the mod this was found in ----

	@Test void theRealProviderBehindAModsTwoConfigPluginsDefinesNoGameTypeInsideTheWeave(@TempDir Path work) throws Exception {
		Path mod = Path.of("build/compat-inputs/player-loading/mods/iris-neoforge-1.11.4+mc26.2.jar");
		TestFixtures.requireFiles(Fixture.THIRD_PARTY, "a mod whose config plugins reach its platform provider", mod);
		Path staged = TestFixtures.stagedRoot();
		Path merged = staged.resolve("merged-base/patched-mc-merged-26.2.jar");
		Path neoforge = staged.resolve("neoforge-runtime/neoforge-runtime.jar");
		Path forge = staged.resolve("forge-runtime/forge-runtime.jar");
		TestFixtures.requireFiles(Fixture.STAGED, "the merged game the provider names", merged, neoforge, forge);
		URL[] owned = {mod.toUri().toURL(), merged.toUri().toURL(), neoforge.toUri().toURL(), forge.toUri().toURL()};
		// What ServiceLoader does to a provider before it constructs one: look up its constructor, which links it.
		String provider = "net.irisshaders.iris.platform.IrisForgeHelpers";
		String outer = "net.irisshaders.iris.compat.dh.DHMixinConfigPlugin";
		String gameType = "net.minecraft.world.level.BlockAndLightGetter";

		try (ForbricClassLoader loader = new ForbricClassLoader(owned, getClass().getClassLoader())) {
			List<String> inside = weave(loader, outer, () -> Class.forName(provider, false, loader).getDeclaredConstructors());
			assertTrue(inside.contains(provider), inside::toString);
			assertEquals(List.of(), inside.stream().filter(name -> name.startsWith("net.minecraft.")).toList(),
					"the provider's verification defined game types while the weave was open");
		}

		String saved = System.getProperty(ForbricClassLoader.DEFER_LINK_TIME_TYPES);
		System.setProperty(ForbricClassLoader.DEFER_LINK_TIME_TYPES, "off");
		try (ForbricClassLoader loader = new ForbricClassLoader(owned, getClass().getClassLoader())) {
			List<String> inside = new ArrayList<>();
			try {
				weave(loader, outer, () -> Class.forName(provider, false, loader).getDeclaredConstructors(), inside);
			} catch (IllegalStateException | LinkageError incomplete) {
				// The control needs only the request; the rest of the game's hierarchy need not link on this path.
			}
			assertTrue(inside.contains(gameType),
					"the control must show the provider's verification defining " + gameType + ": " + inside);
		} finally {
			if (saved == null) System.clearProperty(ForbricClassLoader.DEFER_LINK_TIME_TYPES);
			else System.setProperty(ForbricClassLoader.DEFER_LINK_TIME_TYPES, saved);
		}
	}
}
