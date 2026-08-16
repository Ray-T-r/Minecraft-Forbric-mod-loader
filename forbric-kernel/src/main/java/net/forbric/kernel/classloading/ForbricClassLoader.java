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

package net.forbric.kernel.classloading;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import net.forbric.kernel.util.ForbricLog;

/**
 * The kernel's single sovereign transforming class loader — the one and only loader that defines the game +
 * ecosystem classes, applying the unified transform pipeline as each class is defined.
 *
 * <p>This replaces Fabric's Knot (and Forge's ModLauncher/securemodules) with one flat, JPMS-free loader. It is
 * child-first for the jars it owns (the merged base + the Forge/NeoForge runtime carriers + the kernel's game-side
 * runtime jar + mod jars) and parent-first for everything shared with the boot side (ASM, Mixin, logging, the
 * kernel boot classes) — see {@link DelegationPolicy}.
 *
 * <p>The transform hook is injected by the boot orchestrator: {@code (binaryName, classBytes) -> newBytes}. It
 * runs the {@code TransformChain} (Access, merged-base compat, the kernel redirectors) and, last, Mixin. A
 * {@code null}/identity return means "unchanged".
 */
public final class ForbricClassLoader extends URLClassLoader {
	static {
		ClassLoader.registerAsParallelCapable();
	}

	private final ClassLoader parent;
	private volatile BiFunction<String, byte[], byte[]> transformer = (n, b) -> b;
	private volatile BiFunction<String, byte[], byte[]> mixinTransformer = (n, b) -> b;

	public ForbricClassLoader(URL[] ownedJars, ClassLoader parent) {
		super("forbric", ownedJars, parent);
		this.parent = parent;
	}

	/**
	 * Adds a jar to the set this loader owns, at runtime, after boot.
	 *
	 * <p>{@code URLClassLoader} declares this {@code protected}, and mods that unpack their real payload during
	 * {@code preLaunch} look for it with {@code getDeclaredMethod}, which does not search superclasses — so a
	 * protected inherited method reads to them as absent. Essential's stage-2 loader probes for exactly this
	 * signature and, not finding it, gives up with "Failed to add Essential jar to parent ClassLoader".
	 *
	 * <p>Overriding it public is also the honest contract: a jar added here is OWNED, so its classes go through the
	 * whole pipeline (access tweakers, the compat chain, then Mixin) like any other mod's — which is what a mod
	 * extending the classpath at runtime expects, and what Fabric's own {@code addToClassPath} gives it.
	 */
	@Override
	public void addURL(URL url) {
		super.addURL(url);
	}

	/** Installs the pre-mixin transform chain (Access, compat, the kernel redirectors). Call once, before any load. */
	public void setTransformer(BiFunction<String, byte[], byte[]> transformer) {
		this.transformer = transformer == null ? (n, b) -> b : transformer;
	}

	/**
	 * Jars that were SUPERSEDED by another copy of the same mod, consulted ONLY when a class is in no owned jar.
	 *
	 * <p>Cross-jar arbitration keeps one jar per mod id and drops the other, which is required: two builds of one
	 * mod share most class NAMES but not their bytes (measured: 90 of Jade's 436 shared classes differ, 29 of
	 * lithostitched's 346), so putting both on the classpath would mix two builds under first-URL-wins. What that
	 * costs is the loser's handful of platform-only classes — 40 across the nine superseded jars of the merged pack,
	 * 0 to 17 each.
	 *
	 * <p>Nothing in that pack referenced any of them, but a mod that IS built against the other side's platform
	 * class would hit a bare {@code NoClassDefFoundError} with nothing pointing at the cause. Serving them as a
	 * last resort closes that: because this is reached only after {@link #findResource} misses, it cannot shadow the
	 * winner — the disjointness is structural rather than something to compute and trust.
	 *
	 * <p><b>Classes only, never resources.</b> The superseded jar's {@code *.mixins.json} and {@code assets/} must
	 * stay unreachable — not applying them twice is the whole point of suppressing it.
	 *
	 * <p><b>This fixes linkage, not initialisation.</b> A platform class whose own side never ran its {@code @Mod} /
	 * entrypoint may still fail on state that was never set up. That case needs the mod pinned to the other
	 * ecosystem instead, which is what the rescue log line tells the user to do.
	 */
	public void setRescueJars(List<URL> jars) {
		rescue = (jars == null || jars.isEmpty()) ? null : new URLClassLoader(jars.toArray(new URL[0]), null);
	}

	private volatile URLClassLoader rescue;
	private static final Set<String> RESCUED = ConcurrentHashMap.newKeySet();

	/** A class the owned jars do not have, from a superseded jar. Null when there is no rescue set or no such class. */
	private URL rescueResource(String path) {
		URLClassLoader superseded = rescue;
		return superseded == null ? null : superseded.findResource(path);
	}

	/**
	 * Offers a class synthesized by a transformer (class-tweaker enum extension) for definition on demand. The
	 * bytes are used verbatim; the class is defined the first time something loads it.
	 *
	 * @param internalName the ASM internal name ({@code a/b/C})
	 */
	public void putGeneratedClass(String internalName, byte[] bytes) {
		generatedClasses.put(internalName.replace('/', '.'), bytes);
	}

	/**
	 * Installs the Mixin weaver, which runs strictly AFTER {@link #setTransformer the chain} — the last stage of
	 * the pipeline, as Mixin requires.
	 *
	 * <p>It is invoked with {@code null} bytes for a class not present in any owned jar: that is Mixin's
	 * class-GENERATION path (e.g. {@code org.spongepowered.asm.synthetic.*} argument classes), which must return
	 * bytes or {@code null}. It must not be given already-woven bytes, or it would weave its own output.
	 */
	public void setMixinTransformer(BiFunction<String, byte[], byte[]> mixinTransformer) {
		this.mixinTransformer = mixinTransformer == null ? (n, b) -> b : mixinTransformer;
	}

	/**
	 * The bytes Mixin's bytecode provider must see for {@code name}: read from the owned jars and put through the
	 * pre-mixin chain, but NOT woven. Falls back to the parent's resources for library classes Mixin inspects
	 * (superclasses, interfaces), which are never transformed. {@code null} if the class has no bytes anywhere.
	 */
	public byte[] getPreMixinClassBytes(String name) {
		String path = name.replace('.', '/') + ".class";
		URL resource = findResource(path);
		// Same last-resort as tryDefineGameClass, or Mixin would inspect different bytes than the ones defined.
		if (resource == null) resource = rescueResource(path);

		if (resource != null) {
			byte[] raw = read(resource);
			if (raw == null) return null;

			byte[] transformed = transformer.apply(name, raw);
			return transformed == null ? raw : transformed;
		}

		try (InputStream in = parent.getResourceAsStream(path)) {
			return in == null ? null : in.readAllBytes();
		} catch (IOException e) {
			return null;
		}
	}

	/** Whether this loader has already defined {@code name} (Mixin's {@code IClassTracker}). */
	public boolean isClassLoadedByName(String name) {
		synchronized (getClassLoadingLock(name)) {
			return findLoadedClass(name) != null;
		}
	}

	/** Resource lookup for Mixin config JSONs: this loader's own jars first, then the parent. */
	public InputStream getGameResourceAsStream(String name) {
		URL url = findResource(name);

		if (url != null) {
			try {
				return url.openStream();
			} catch (IOException e) {
				return null;
			}
		}

		return parent.getResourceAsStream(name);
	}

	private static byte[] read(URL resource) {
		try (InputStream in = resource.openStream()) {
			return in.readAllBytes();
		} catch (IOException e) {
			return null;
		}
	}

	/**
	 * Defines a kernel-generated class in THIS loader, so generated glue (e.g. the container-factory's
	 * {@code ModContainer} subclass) shares the game's class identity and can extend game/ecosystem types.
	 * The bytes are used verbatim (no transform). Returns the already-defined class if present.
	 */
	public Class<?> defineRuntimeClass(String binaryName, byte[] bytes) {
		synchronized (getClassLoadingLock(binaryName)) {
			Class<?> existing = findLoadedClass(binaryName);
			if (existing != null) return existing;
			definePackageIfNeeded(binaryName, null); // generated class, no owning jar
			return defineClass(binaryName, bytes, 0, bytes.length);
		}
	}

	@Override
	protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
		synchronized (getClassLoadingLock(name)) {
			Class<?> c = findLoadedClass(name);
			if (c == null) {
				if (DelegationPolicy.alwaysParent(name)) {
					c = parent.loadClass(name);
				} else if (DelegationPolicy.alwaysGame(name)) {
					c = defineGameClass(name); // must be here; if bytes missing this throws (a real error)
				} else {
					// Child-first for owned jars, else parent. Catches game/mod classes without a package list,
					// while MC libraries (DataFixerUpper, Brigadier, netty, guava, joptsimple) fall to the parent.
					c = tryDefineGameClass(name);
					if (c == null) c = parent.loadClass(name);
				}
			}
			if (resolve) resolveClass(c);
			return c;
		}
	}

	private Class<?> defineGameClass(String name) throws ClassNotFoundException {
		Class<?> c = tryDefineGameClass(name);
		if (c == null) {
			throw new ClassNotFoundException(name + " (game-side, but not found in any kernel-owned jar)");
		}
		return c;
	}

	/**
	 * Reads {@code name} from this loader's own jars, runs the pipeline (chain, then Mixin), defines it.
	 *
	 * <p>When the class is in no owned jar the pre-mixin bytes are {@code null} and Mixin is still consulted: that
	 * is how a mixin-GENERATED class ({@code org.spongepowered.asm.synthetic.*}) comes into being. If Mixin does
	 * not generate it either, this returns {@code null} and the caller falls back to the parent.
	 */
	private Class<?> tryDefineGameClass(String name) {
		String path = name.replace('.', '/') + ".class";
		URL resource = findResource(path); // this loader's own URLs only
		byte[] bytes = null;

		if (resource != null) {
			bytes = read(resource);
			if (bytes == null) return null;

			// Before the chain runs: LoaderProbeRewriter needs to know which loader family owns this class in
			// order to bake the right answer into its Class.forName call sites.
			if (!jarFamilies.isEmpty()) rememberOrigin(name, resource);

			byte[] transformed = transformer.apply(name, bytes);
			if (transformed != null) bytes = transformed;
		} else {
			// A transformer-synthesized class (class-tweaker enum extension) has no jar to come from.
			byte[] generated = generatedClasses.get(name);

			if (generated != null) {
				definePackageIfNeeded(name, null);
				return define(name, generated);
			}

			// Last resort: a jar that cross-jar arbitration superseded. Reached only because no owned jar has this
			// class, so it cannot shadow the winner — see setRescueJars.
			resource = rescueResource(path);
			if (resource != null) {
				bytes = read(resource);
				if (bytes == null) return null;
				if (RESCUED.add(name)) {
					ForbricLog.info("[Forbric/DupeId] served %s from a superseded jar — no loaded mod provides it. "
							+ "If this mod then fails on uninitialised state, pin it to that ecosystem instead "
							+ "(forbric-mods.txt, or -Dforbric.modOwner=<id>=<loader>)", name);
				}
				byte[] transformed = transformer.apply(name, bytes);
				if (transformed != null) bytes = transformed;
			}
		}

		byte[] woven = mixinTransformer.apply(name, bytes);
		if (woven != null) bytes = woven;
		if (bytes == null) return null;

		definePackageIfNeeded(name, resource);
		return define(name, bytes);
	}

	/**
	 * {@code defineClass}, recovering from a RE-ENTRANT definition of the same class on this thread.
	 *
	 * <p>{@link #loadClass} takes the per-name lock and checks {@code findLoadedClass} first, so two threads cannot
	 * race here. One thread can still get in twice: this method's own pipeline runs guest code before the class is
	 * defined. {@code mixinTransformer.apply} on the FIRST game class triggers Mixin's one-shot {@code select()},
	 * which constructs every guest config plugin, and a plugin's constructor or {@code <clinit>} may load anything.
	 * If that graph reaches the class currently being defined, the inner {@code loadClass} re-enters the same
	 * (reentrant) lock, still sees {@code findLoadedClass == null}, and defines it — then this outer call fails with
	 * {@code LinkageError: attempted duplicate class definition}.
	 *
	 * <p>Observed on a 64-mod NeoForge pack: {@code net.neoforged.fml.ModList} is the first class the kernel loads
	 * after Mixin bootstrap ({@code PassiveSeeder.seedNeoForgeModList}), so it is the one that pays. Seeding then
	 * failed, {@code ModList.get()} stayed null, and the client died in {@code Options.<init>} at
	 * {@code ClientHooks.onRegisterKeyMappings} — three steps away, with nothing connecting it back. The trigger is
	 * NOT a plugin that names ModList; none does. It is transitive, which is why it appears only at pack scale.
	 *
	 * <p>The error means the class IS defined by this loader, and the inner definition went through this same
	 * pipeline, so it is the same bytes. Returning it is loss-free and strictly better than failing the caller. The
	 * recovery is logged once per class: it is not an error, but it does mean guest code ran mid-definition, and
	 * that is worth being able to see.
	 */
	private Class<?> define(String name, byte[] bytes) {
		try {
			return defineClass(name, bytes, 0, bytes.length);
		} catch (LinkageError duplicate) {
			Class<?> already = findLoadedClass(name);
			if (already == null) throw duplicate; // a genuine linkage problem, not re-entrancy
			if (REENTRANT.add(name)) {
				ForbricLog.debug("[Forbric/Loader] %s was defined re-entrantly (guest code loaded it from inside its "
						+ "own transform, most likely a mixin config plugin's construction) — using the definition "
						+ "that already completed", name);
			}
			return already;
		}
	}

	/**
	 * Declares which owned jars belong to exactly one loader family, so {@link LoaderProbePolicy} can answer a
	 * guest's platform probe for the loader that guest was actually loaded as. Jars absent from the map — the
	 * merged base, the Forge/NeoForge runtime carriers, MC libraries, and any universal jar carrying more than
	 * one manifest — are unowned and see every probe answer yes, as before. Call once, before any class loads.
	 */
	public void setJarFamilies(java.util.Map<java.nio.file.Path, LoaderProbePolicy.Family> byJar) {
		jarFamilies.clear();
		byJar.forEach((jar, family) -> {
			try {
				jarFamilies.put("jar:" + jar.toUri().toURL(), family);   // same spelling findResource will produce
			} catch (java.net.MalformedURLException impossible) {
				// a jar already on this loader's URL list cannot fail to spell itself
			}
		});
	}

	/**
	 * The loader family of the jar {@code binaryName} is being defined from, or {@code null} if it is unowned —
	 * the merged base, a runtime carrier, an MC library, a universal jar, or a kernel class.
	 */
	public LoaderProbePolicy.Family familyOfClass(String binaryName) {
		return classFamilies.get(binaryName);
	}

	/**
	 * Records the family of the jar a freshly defined class came from. The URL is {@code jar:file:/…/x.jar!/a/B.class};
	 * only single-family jars are in the map, so an unowned origin simply records nothing.
	 */
	private void rememberOrigin(String name, URL resource) {
		String url = resource.toString();
		int bang = url.indexOf("!/");
		if (bang < 0) return;

		LoaderProbePolicy.Family family = jarFamilies.get(url.substring(0, bang));
		if (family != null) classFamilies.put(name, family);
	}

	// Owned single-family jars, keyed by "jar:file:…!"-prefix; and the per-class answer derived from them.
	private final ConcurrentHashMap<String, LoaderProbePolicy.Family> jarFamilies = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, LoaderProbePolicy.Family> classFamilies = new ConcurrentHashMap<>();

	// Classes synthesized by a transformer rather than read from a jar, keyed by binary name.
	private final ConcurrentHashMap<String, byte[]> generatedClasses = new ConcurrentHashMap<>();

	// Cache of jar path -> Manifest so the package version/vendor attributes are read once per jar.
	private final ConcurrentHashMap<String, Manifest> manifestCache = new ConcurrentHashMap<>();
	/** Classes recovered from a re-entrant definition; reported once each. See {@link #define}. */
	private static final java.util.Set<String> REENTRANT = ConcurrentHashMap.newKeySet();

	private static final Manifest NO_MANIFEST = new Manifest();

	/**
	 * Defines the class's package with the owning jar's manifest attributes (spec/impl title, version, vendor),
	 * so e.g. {@code Package.getImplementationVersion()} answers — genuine FML reads it (ForgeVersion's
	 * {@code <clinit>} throws "invalid environment" on a null version). The kernel's equivalent of the old
	 * substrate's package-manifest patch.
	 */
	private void definePackageIfNeeded(String className, URL classResource) {
		int dot = className.lastIndexOf('.');
		if (dot < 0) return;
		String pkg = className.substring(0, dot);
		if (getDefinedPackage(pkg) != null) return;

		Manifest man = manifestFor(classResource);
		Attributes main = man == NO_MANIFEST ? null : man.getMainAttributes();
		Attributes perPkg = man == NO_MANIFEST ? null : man.getAttributes(pkg.replace('.', '/') + "/");
		try {
			definePackage(pkg,
					attr(perPkg, main, Attributes.Name.SPECIFICATION_TITLE),
					attr(perPkg, main, Attributes.Name.SPECIFICATION_VERSION),
					attr(perPkg, main, Attributes.Name.SPECIFICATION_VENDOR),
					attr(perPkg, main, Attributes.Name.IMPLEMENTATION_TITLE),
					attr(perPkg, main, Attributes.Name.IMPLEMENTATION_VERSION),
					attr(perPkg, main, Attributes.Name.IMPLEMENTATION_VENDOR),
					null);
		} catch (IllegalArgumentException alreadyDefined) {
			// race: another thread defined it — fine.
		}
	}

	private static String attr(Attributes perPkg, Attributes main, Attributes.Name name) {
		String v = perPkg == null ? null : perPkg.getValue(name);
		if (v == null && main != null) v = main.getValue(name);
		return v;
	}

	/** Manifest of the jar containing {@code jar:file:...!/...} resource; cached per jar. NO_MANIFEST if none. */
	private Manifest manifestFor(URL classResource) {
		if (classResource == null || !"jar".equals(classResource.getProtocol())) return NO_MANIFEST;
		String spec = classResource.getFile();
		int bang = spec.indexOf("!/");
		if (bang < 0) return NO_MANIFEST;
		String jarSpec = spec.substring(0, bang); // file:/path/to.jar
		return manifestCache.computeIfAbsent(jarSpec, js -> {
			try {
				String filePath = js.startsWith("file:") ? new java.io.File(java.net.URI.create(js)).getPath() : js;
				try (JarFile jf = new JarFile(filePath)) {
					Manifest m = jf.getManifest();
					return m == null ? NO_MANIFEST : m;
				}
			} catch (Exception e) {
				return NO_MANIFEST;
			}
		});
	}
}
