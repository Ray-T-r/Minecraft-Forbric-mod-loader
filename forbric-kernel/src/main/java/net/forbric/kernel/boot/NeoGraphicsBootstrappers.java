/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

import net.forbric.api.ModCatalog;
import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.ForbricLog;

/**
 * NeoForge's {@code GraphicsBootstrapper} services, run where genuine FML runs them: before the game creates its window.
 *
 * <p>A NeoForge jar may declare {@code META-INF/services/net.neoforged.neoforgespi.earlywindow.GraphicsBootstrapper}.
 * Genuine FML finds such jars among the top-level files of {@code mods/} ({@code EarlyServiceDiscovery}, a walk of depth
 * one: a JarJar child is never one), and {@code FMLLoader.create} hands them to {@code ImmediateWindowHandler.load},
 * whose first statement runs every provider — {@code name()}, then {@code bootstrap(programArguments)} — before it so
 * much as reads whether an early window is wanted, headless or not. Nothing in the kernel did either, so a provider never
 * ran under Forbric: Sodium's NeoForge build keeps its pre-launch checks, graphics adapter probe and driver workarounds
 * there ({@code SodiumWorkarounds}), and on Forbric the probe never searched, {@code Workarounds.init} never decided
 * anything, and every later {@code isWorkaroundEnabled} the mod asks — from its window mixin, its context-creation
 * checks — answered from a list nobody filled.
 *
 * <p>Here they run from the kernel's pre-launch point: after Mixin is installed and the FML identities are seeded,
 * before any game class (the window is built inside {@code Minecraft.<init>}, much later), on the boot thread with the
 * game loader as context loader. Which ones is the SPI's own rule, never a mod's name: a provider listed by a top-level
 * jar the kernel loaded as NeoForge (a jar arbitration superseded contributes nothing, as it contributes no classes); in
 * {@code mods/} order, then file order; a class listed twice runs once, as one {@code ServiceLoader} lists it once.
 *
 * <p>Genuine FML lets a provider's exception end the launch. Here it costs that provider's jar: reported at ERROR with
 * the jar named, and every mod of that jar marked FAILED on the Mods screen, the same weight a Fabric {@code preLaunch}
 * that throws gets. {@code -Dforbric.graphicsBootstrappers=off} leaves them unrun, as before.
 *
 * <p>MinecraftForge has no counterpart: its FML for this game version defines no graphics bootstrap SPI, its
 * {@code ImmediateWindowProvider} is chosen by name from {@code fml.toml} (the kernel gives it FML's own no-window
 * provider, {@code PassiveSeeder.seedForgeImmediateWindow}), and its {@code BootstrapClasspathModifier} /
 * {@code BootstrapEntryPoint} are read from the launcher's class path, never from a mod.
 */
public final class NeoGraphicsBootstrappers {
	public static final String PROPERTY = "forbric.graphicsBootstrappers";
	/** The SPI's binary name, as NeoForge's FML declares it. */
	static final String SPI = "net.neoforged.neoforgespi.earlywindow.GraphicsBootstrapper";
	static final String SERVICES = "META-INF/services/" + SPI;

	/** One provider a jar lists. */
	record Declared(Path jar, String provider) {
	}

	/** What one provider did. */
	record Outcome(Declared declared, String name, Throwable failure) {
		boolean ran() {
			return failure == null;
		}
	}

	private NeoGraphicsBootstrappers() {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/**
	 * Every provider the given jars list, in jar order then file order, each class once. Comments ({@code #} to the end of
	 * a line) and blank lines are skipped, as {@code ServiceLoader} skips them. A jar that cannot be read costs only itself.
	 */
	static List<Declared> declared(List<Path> jars) {
		List<Declared> out = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		for (Path jar : jars) {
			if (jar == null || !Files.isRegularFile(jar)) continue;
			try (JarFile zip = new JarFile(jar.toFile(), false)) {
				ZipEntry entry = zip.getEntry(SERVICES);
				if (entry == null) continue;
				try (InputStream in = zip.getInputStream(entry);
						BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
					for (String line; (line = reader.readLine()) != null;) {
						int hash = line.indexOf('#');
						String provider = (hash >= 0 ? line.substring(0, hash) : line).trim();
						if (!provider.isEmpty() && seen.add(provider)) out.add(new Declared(jar, provider));
					}
				}
			} catch (IOException | RuntimeException unreadable) {
				Caught.warn(unreadable, "[Forbric/EarlyWindow] could not read %s from %s — a GraphicsBootstrapper it declares "
						+ "does not run", SERVICES, jar.getFileName());
			}
		}
		return out;
	}

	/**
	 * Runs the providers the NeoForge-owned jars among {@code topLevelJars} list.
	 *
	 * @param topLevelJars the Forge-family jars of {@code mods/} the kernel loads, in that directory's order — no nested jar
	 * @param neoForge     whether a jar was arbitrated to NeoForge (a MinecraftForge jar has no such SPI to declare)
	 * @param programArgs  the game's arguments, which genuine FML hands {@code bootstrap}
	 * @return what each provider did, in the order they ran
	 */
	public static List<Outcome> run(ClassLoader gameLoader, List<Path> topLevelJars, Predicate<Path> neoForge,
			String[] programArgs) {
		if (!enabled()) {
			ForbricLog.warn("[Forbric/EarlyWindow] -D%s=off — NeoForge GraphicsBootstrapper services are not run", PROPERTY);
			return List.of();
		}
		List<Path> owned = new ArrayList<>();
		for (Path jar : topLevelJars) if (neoForge.test(jar)) owned.add(jar);
		List<Declared> declared = declared(owned);
		if (declared.isEmpty()) return List.of();

		Class<?> spi;
		Method name, bootstrap;
		try {
			spi = Class.forName(SPI, false, gameLoader);
			name = byShape(spi, String.class);
			bootstrap = byShape(spi, void.class, String[].class);
		} catch (ReflectiveOperationException | LinkageError absent) {
			Caught.error(absent, "[Forbric/EarlyWindow] %d GraphicsBootstrapper service(s) are declared (%s), but the NeoForge "
					+ "carrier has no %s — none of them runs", declared.size(), declared.get(0).jar().getFileName(), SPI);
			for (Declared one : declared) markFailed(one, absent);
			return List.of();
		}

		List<Outcome> outcomes = new ArrayList<>();
		for (Declared one : declared) {
			String shown = one.provider();
			try {
				Class<?> provider = Class.forName(one.provider(), false, gameLoader);
				if (!spi.isAssignableFrom(provider)) {
					throw new ClassCastException(one.provider() + " is listed as a " + SPI + " but does not implement it");
				}
				Object instance = provider.getDeclaredConstructor().newInstance();
				shown = String.valueOf(name.invoke(instance));
				ForbricLog.info("[Forbric/EarlyWindow] Running graphics bootstrap plugin %s (%s, from %s) — NeoForge's "
						+ "GraphicsBootstrapper, before the game creates its window, where genuine FML runs it", shown,
						one.provider(), one.jar().getFileName());
				bootstrap.invoke(instance, (Object) (programArgs == null ? new String[0] : programArgs.clone()));
				outcomes.add(new Outcome(one, shown, null));
			} catch (InvocationTargetException thrown) {
				Throwable cause = thrown.getCause() == null ? thrown : thrown.getCause();
				Caught.error(cause, FAILED, one.provider(), one.jar().getFileName());
				outcomes.add(new Outcome(one, shown, cause));
				markFailed(one, cause);
			} catch (ReflectiveOperationException | RuntimeException | LinkageError unusable) {
				Caught.error(unusable, FAILED, one.provider(), one.jar().getFileName());
				outcomes.add(new Outcome(one, shown, unusable));
				markFailed(one, unusable);
			}
		}
		return List.copyOf(outcomes);
	}

	/**
	 * The SPI's one abstract method of this shape — {@code name()} answers a String with no argument, {@code bootstrap}
	 * takes the program arguments and answers nothing. Read by shape, so no method name of the game's is spelled here
	 * (the uncalled-method census reads every name the kernel holds as one it may call).
	 */
	static Method byShape(Class<?> spi, Class<?> returns, Class<?>... parameters) throws NoSuchMethodException {
		Method found = null;
		for (Method method : spi.getMethods()) {
			if (!java.lang.reflect.Modifier.isAbstract(method.getModifiers()) || method.getReturnType() != returns
					|| !java.util.Arrays.equals(method.getParameterTypes(), parameters)) continue;
			if (found != null) throw new NoSuchMethodException(spi.getName() + " has more than one abstract method of that shape");
			found = method;
		}
		if (found == null) throw new NoSuchMethodException(spi.getName() + " has no abstract method of that shape");
		return found;
	}

	private static final String FAILED = "[Forbric/EarlyWindow] GraphicsBootstrapper %s from %s failed — on genuine NeoForge "
			+ "this ends the launch; here its jar's mods are marked failed and the game goes on without what it sets up";

	/** The jar's mods, failed on the Mods screen: the provider is the jar's, whichever of its mods it serves. */
	private static void markFailed(Declared one, Throwable why) {
		ModCatalog.markByJar(one.jar().getFileName().toString(), ModCatalog.Status.FAILED,
				"its NeoForge GraphicsBootstrapper " + one.provider() + " threw " + why);
	}
}
