package net.forbric.kernel.mixin.weave;

import java.io.PrintStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.fabricmc.api.EnvType;
import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;
import net.forbric.api.DiscoveredMod;
import net.forbric.api.Ecosystem;
import net.forbric.api.ModPresence;
import net.forbric.api.UnifiedDependency;
import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.mixin.KernelMixinBootstrap;
import net.forbric.kernel.mixin.MixinConfigOwners;

/**
 * The child half of {@link WeaveHarness}: one JVM, one real Mixin bootstrap.
 *
 * <p>Everything a production boot does to a guest mixin happens here, through the same entry points and in KernelBoot's
 * order: the named pre-Mixin transformers go on the loader as a {@code TransformChain} ({@link KernelBootChain}), the
 * config owners and their mods' manifests are published, and {@link KernelMixinBootstrap#init} registers the configs
 * Fabric first with the Forge family appended. {@code ForbricMixinService} then serves the fixture jar's bytes through
 * that chain and its own adapters, real sponge-mixin weaves, the post-Mixin stages run, and
 * {@code FinalMixinApplications} audits the woven class as {@link ForbricClassLoader} defines it. Then the probe method
 * is CALLED, so what the test asserts is what the woven code did, not what the mixin said it would do.
 *
 * <p>A child JVM because the bootstrap is one-shot per JVM and leaves Mixin's and the kernel's global state behind.
 *
 * <p>Arguments: fixtureJar mixinExtrasJar side probeClass probeMethod outDir chain, then config modId ecosystem for
 * each config; chain is a comma-separated list of transformer classes, empty for none.
 */
public final class WeaveHarnessMain {
	static final String DONE = "[WeaveHarness] probe returned";
	static final String THREW = "[WeaveHarness] probe threw ";
	static final String REGISTERED = "[WeaveHarness] mixin configs in registration order: ";

	private WeaveHarnessMain() {
	}

	public static void main(String[] args) throws Exception {
		Path fixture = Path.of(args[0]);
		String mixinExtras = args[1];
		EnvType side = EnvType.valueOf(args[2]);
		String probeClass = args[3];
		String probeMethod = args[4];
		Path out = Path.of(args[5]);
		List<String> chain = args[6].isEmpty() ? List.of() : List.of(args[6].split(","));
		List<MixinConfigOwners.Owned> declared = new ArrayList<>();
		for (int i = 7; i + 2 < args.length; i += 3) {
			declared.add(new MixinConfigOwners.Owned(args[i], args[i + 1], Ecosystem.valueOf(args[i + 2]), ""));
		}

		List<URL> owned = new ArrayList<>();
		owned.add(fixture.toUri().toURL());
		// Opt-in, so every other run's loader owns exactly what it did: jars beside the fixture that no mod is recorded for.
		for (String library : System.getProperty(LIBRARIES, "").split(java.io.File.pathSeparator)) {
			if (!library.isBlank()) owned.add(Path.of(library).toUri().toURL());
		}
		if (!mixinExtras.isEmpty()) owned.add(Path.of(mixinExtras).toUri().toURL());
		ForbricClassLoader loader = new ForbricClassLoader(owned.toArray(URL[]::new), ClassLoader.getSystemClassLoader());
		// KernelBoot puts the chain on the loader before Mixin exists, so Mixin is only ever shown its output.
		if (!chain.isEmpty()) KernelBootChain.install(loader, side, chain);
		Thread.currentThread().setContextClassLoader(loader);

		// KernelBoot's order: the Fabric configs as they come, the Forge family's appended, every owner published first.
		List<MixinConfigOwners.Owned> ordered = new ArrayList<>();
		for (MixinConfigOwners.Owned one : declared) if (one.ecosystem() == Ecosystem.FABRIC) ordered.add(one);
		for (MixinConfigOwners.Owned one : declared) if (one.ecosystem() != Ecosystem.FABRIC) ordered.add(one);
		List<String> configs = new ArrayList<>();
		for (MixinConfigOwners.Owned one : ordered) if (!configs.contains(one.config())) configs.add(one.config());
		MixinConfigOwners.publish(ordered);
		publishPresence(ordered);
		// Opt-in, so every other run keeps an unattributed fixture: the fixture jar is the configs' owners' own jar, as a
		// boot records it for every selected mod (one ecosystem per run; two claiming it leave it unattributed).
		if ("on".equals(System.getProperty(ORIGINS))) {
			List<DiscoveredMod> owners = new ArrayList<>();
			for (MixinConfigOwners.Owned one : ordered) {
				owners.add(new DiscoveredMod(one.ecosystem(), one.modId(), "1.0", one.modId(), List.of(), List.of(one.config()),
						null, fixture.toString()));
			}
			loader.setModOrigins(owners);
		}
		System.out.println(REGISTERED + String.join(", ", configs));
		if ("off".equals(System.getProperty("forbric.weaveHarness.bootstrap"))) {
			System.out.println("[WeaveHarness] bootstrap skipped (control run)");
		} else {
			KernelMixinBootstrap.init(loader, side, configs);
		}

		try {
			Class<?> target = Class.forName(probeClass, true, loader);
			Object result = target.getMethod(probeMethod).invoke(target.getDeclaredConstructor().newInstance());
			System.out.println(DONE + " " + result);
		} catch (Throwable thrown) {
			Throwable cause = thrown instanceof java.lang.reflect.InvocationTargetException ite ? ite.getCause() : thrown;
			System.out.println(THREW + cause);
			cause.printStackTrace(System.out);
		}
		System.out.flush();

		// As KernelLoadReport.writeTo does before it reads them: a row held back for a config plugin is settled now.
		net.forbric.kernel.mixin.PluginDeclinedMixins.resolve();
		StringBuilder findings = new StringBuilder();
		for (CompatibilityFinding f : CompatibilityFindings.all()) {
			findings.append(String.join("\t", f.id(), f.modId(), f.confidence().name(), String.valueOf(f.required()),
					f.source(), flat(f.detail()))).append('\n');
		}
		Files.writeString(out.resolve("findings.tsv"), findings, StandardCharsets.UTF_8);
		PrintStream done = System.out;
		done.println("[WeaveHarness] findings written: " + CompatibilityFindings.all().size());
	}

	/**
	 * KernelBoot publishes every loaded mod's manifest before Mixin parses a config; here each config's owner is such a
	 * mod, declaring what {@value #REQUIRES} says ({@code id=constraint,...}, mandatory) and nothing else.
	 */
	private static void publishPresence(List<MixinConfigOwners.Owned> owners) {
		List<UnifiedDependency> requires = new ArrayList<>();
		for (String one : System.getProperty(REQUIRES, "").split(",")) {
			int eq = one.indexOf('=');
			if (eq > 0) requires.add(new UnifiedDependency(one.substring(0, eq).strip(), one.substring(eq + 1).strip(), true));
		}
		List<DiscoveredMod> fabric = new ArrayList<>();
		List<DiscoveredMod> forgeFamily = new ArrayList<>();
		for (MixinConfigOwners.Owned one : owners) {
			DiscoveredMod mod = new DiscoveredMod(one.ecosystem(), one.modId(), "1.0", one.modId(), requires,
					List.of(one.config()), null, "fixture");
			(one.ecosystem() == Ecosystem.FABRIC ? fabric : forgeFamily).add(mod);
		}
		ModPresence.publishFabric(fabric);
		ModPresence.publishForgeFamily(forgeFamily);
	}

	/** What every fixture mod's manifest requires: {@code id=constraint,...}. */
	static final String REQUIRES = "forbric.weaveHarness.requires";

	/** {@code on}: the loader attributes the fixture jar to the configs' owners, as KernelBoot's setModOrigins does. */
	static final String ORIGINS = "forbric.weaveHarness.origins";

	/**
	 * Jars, {@code File.pathSeparator}-separated, the loader owns beside the fixture and attributes to no mod, as a boot
	 * leaves a library bundled without a loader manifest.
	 */
	static final String LIBRARIES = "forbric.weaveHarness.libraries";

	private static String flat(String text) {
		return text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
	}
}
