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

import java.io.InputStream;
import java.lang.module.ModuleDescriptor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * A {@code cpw.mods.jarhandling.SecureJar} for a seeded MinecraftForge {@code ModFile}, built without ModLauncher.
 *
 * <h2>Why the real factory cannot be called</h2>
 *
 * <p>MinecraftForge's own {@code SecureJar.from(Path...)} is the obvious answer and it does not work here.
 * {@code from} lands in {@code cpw.mods.jarhandling.impl.Jar}, whose {@code <clinit>} looks up ModLauncher's
 * {@code UnionFileSystemProvider} through the JDK's installed file-system providers and throws
 * {@code IllegalStateException: Couldn't find UnionFileSystemProvider} when it is absent. The kernel replaces
 * ModLauncher rather than running under it, so that provider is never installed, and the class stays permanently
 * un-initialisable: the second call gets {@code NoClassDefFoundError: Could not initialize class …impl.Jar}.
 * Verified on a real boot before this class existed.
 *
 * <p>So the interface is implemented instead of the implementation being borrowed. {@code javap} on
 * {@code ModFile} and {@code ModFileInfo} says which methods actually get called: {@code getPrimaryPath} (behind
 * {@code getFilePath} and therefore {@code getFileName} and {@code toString}), {@code getPath} (behind
 * {@code findResource}), {@code name}, {@code getManifestSigners} and {@code moduleDataProvider}. Those are
 * answered for real from the jar; the rest are answered with the honest empty rather than left to NPE.
 *
 * <h2>The zip is opened as a zip</h2>
 *
 * <p>{@code getPath}/{@code getRootPath} hand back paths inside a plain JDK zip file system. That is the whole
 * point of not using {@code SecureJar.from}: a mod calling {@code findResource("META-INF", "mods.toml")} gets a
 * path it can ask {@code Files.exists} about and read, with no union mounting involved. The file system is
 * opened on first use and re-opened if something else in the kernel closed it, because the kernel does open and
 * close zip file systems over these same jars elsewhere.
 *
 * <p>Every method is dispatched by NAME, which is what makes this testable: the unit test declares its own
 * interface with the same method names and gets the same answers, so the seam is exercised without either
 * carrier jar on the test classpath.
 */
public final class ForgeSecureJarStandIn {

	private ForgeSecureJarStandIn() {
	}

	/**
	 * A stand-in implementing {@code secureJarIface}, backed by {@code jar}.
	 *
	 * @param secureJarIface the carrier's {@code SecureJar} interface, loaded from the game class loader
	 * @param jar            the jar file on disk
	 */
	public static Object create(Class<?> secureJarIface, Path jar) {
		Handler handler = new Handler(secureJarIface, jar);
		return Proxy.newProxyInstance(secureJarIface.getClassLoader(), new Class<?>[] {secureJarIface}, handler);
	}

	/** Answers a {@code SecureJar}-shaped interface from a jar path, by method name. */
	static final class Handler implements InvocationHandler {
		private final Class<?> iface;
		private final Path jar;
		private final String name;

		private volatile FileSystem zip;
		private volatile Manifest manifest;
		private volatile Object moduleData;

		Handler(Class<?> iface, Path jar) {
			this.iface = iface;
			this.jar = jar;
			this.name = moduleNameOf(jar);
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) {
			switch (method.getName()) {
				case "getPrimaryPath":
					return jar;
				case "getRootPath":
					return root();
				case "getPath":
					return path(args);
				case "name":
					return name;
				case "hasSecurityData":
					return Boolean.FALSE;
				// null is what an unsigned jar answers on both of these, and every seeded jar is unsigned as far
				// as the kernel is concerned: it does not verify signatures and must not claim to have.
				case "getManifestSigners":
				case "verifyAndGetSigners":
				case "getTrustedManifestEntries":
					return null;
				case "verifyPath":
				case "getFileStatus":
					return status();
				case "getPackages":
					return Set.of();
				case "getProviders":
					return List.of();
				case "moduleDataProvider":
					return moduleDataProvider();
				case "getManifest":
					return manifest();
				case "uri":
					return jar.toUri();
				case "descriptor":
					return ModuleDescriptor.newAutomaticModule(name).build();
				case "findFile":
					return findFile(args);
				case "open":
					return open(args);
				case "toString":
					return "ForbricSecureJar[" + jar + "]";
				case "hashCode":
					return System.identityHashCode(proxy);
				case "equals":
					return proxy == (args == null ? null : args[0]);
				default:
					return emptyFor(method.getReturnType());
			}
		}

		/**
		 * The zip file system over the jar, opened on demand.
		 *
		 * <p>Re-opened when found closed rather than cached once and trusted: {@code KernelDataPacks} opens and
		 * closes zip file systems over these same jars per world load, and a stand-in that went permanently dead
		 * after the first world would be worse than one that was never built.
		 */
		private FileSystem fileSystem() throws Exception {
			FileSystem current = zip;
			if (current != null && current.isOpen()) return current;
			synchronized (this) {
				if (zip != null && zip.isOpen()) return zip;
				zip = openZip(jar);
				return zip;
			}
		}

		private Path root() {
			try {
				return fileSystem().getRootDirectories().iterator().next();
			} catch (Throwable t) {
				// The jar itself, not null: a caller resolving against this gets a path that does not exist rather
				// than an NPE three frames away from anything that names the jar.
				return jar;
			}
		}

		private Path path(Object[] args) {
			String first = args == null || args.length == 0 ? "" : String.valueOf(args[0]);
			String[] more = args != null && args.length > 1 && args[1] instanceof String[] rest
					? rest : new String[0];
			try {
				return fileSystem().getPath(first, more);
			} catch (Throwable t) {
				return jar.resolve(first);
			}
		}

		private Manifest manifest() {
			Manifest current = manifest;
			if (current != null) return current;
			synchronized (this) {
				if (manifest != null) return manifest;
				Manifest read = new Manifest();
				try (JarFile file = new JarFile(jar.toFile())) {
					Manifest real = file.getManifest();
					if (real != null) read = real;
				} catch (Throwable ignored) {
					// An empty manifest, not a failure: every caller here reads attributes out of it and an absent
					// manifest legitimately means "no attributes".
				}
				manifest = read;
				return read;
			}
		}

		private Optional<URI> findFile(Object[] args) {
			try {
				Path in = path(args);
				return Files.exists(in) ? Optional.of(in.toUri()) : Optional.empty();
			} catch (Throwable t) {
				return Optional.empty();
			}
		}

		private Optional<InputStream> open(Object[] args) {
			try {
				Path in = path(args);
				return Files.exists(in) ? Optional.of(Files.newInputStream(in)) : Optional.empty();
			} catch (Throwable t) {
				return Optional.empty();
			}
		}

		/** {@code SecureJar$ModuleDataProvider}, answered by the same handler. Null when there is no such type. */
		private Object moduleDataProvider() {
			Object current = moduleData;
			if (current != null) return current;
			Class<?> nested = nested("ModuleDataProvider");
			if (nested == null) return null;
			synchronized (this) {
				if (moduleData == null) {
					moduleData = Proxy.newProxyInstance(nested.getClassLoader(), new Class<?>[] {nested}, this);
				}
				return moduleData;
			}
		}

		/** {@code Status.UNVERIFIED}: the kernel did not verify this jar and must not answer VERIFIED. */
		private Object status() {
			Class<?> nested = nested("Status");
			if (nested == null) return null;
			try {
				return Enum.valueOf(nested.asSubclass(Enum.class), "UNVERIFIED");
			} catch (Throwable t) {
				return null;
			}
		}

		private Class<?> nested(String simpleName) {
			try {
				return Class.forName(iface.getName() + "$" + simpleName, false, iface.getClassLoader());
			} catch (Throwable t) {
				return null;
			}
		}
	}

	static FileSystem openZip(Path jar) throws Exception {
		try {
			return FileSystems.newFileSystem(jar);
		} catch (FileSystemAlreadyExistsException already) {
			// Someone else in this process mounted the same jar. Sharing theirs is right: two zip file systems over
			// one file is exactly what the JDK refuses, and the paths are interchangeable.
			return FileSystems.getFileSystem(URI.create("jar:" + jar.toUri()));
		}
	}

	/**
	 * A module-ish name for the jar.
	 *
	 * <p>Only ever read by logging and by {@code ModuleDescriptor.newAutomaticModule}, which rejects a name with a
	 * character that cannot appear in an identifier — so everything but letters, digits and dots becomes a dot,
	 * and a leading digit gets a prefix.
	 */
	static String moduleNameOf(Path jar) {
		String file = jar.getFileName() == null ? "mod" : jar.getFileName().toString();
		int dot = file.lastIndexOf('.');
		if (dot > 0) file = file.substring(0, dot);
		StringBuilder out = new StringBuilder(file.length());
		for (int i = 0; i < file.length(); i++) {
			char c = file.charAt(i);
			out.append(Character.isLetterOrDigit(c) ? Character.toLowerCase(c) : '.');
		}
		String name = out.toString().replaceAll("\\.+", ".").replaceAll("^\\.|\\.$", "");
		if (name.isEmpty()) name = "mod";
		if (Character.isDigit(name.charAt(0))) name = "mod." + name;
		return name.toLowerCase(Locale.ROOT);
	}

	private static Object emptyFor(Class<?> returnType) {
		if (returnType == boolean.class) return Boolean.FALSE;
		if (returnType == int.class) return 0;
		if (returnType == long.class) return 0L;
		if (returnType == Optional.class) return Optional.empty();
		if (returnType == List.class) return List.of();
		if (returnType == Set.class) return Set.of();
		return null;
	}
}
