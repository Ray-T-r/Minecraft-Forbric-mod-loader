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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.ForbricLog;

/**
 * Gives a universal jar's {@code ServiceLoader} only the providers the loader it was arbitrated to could use natively.
 *
 * <p>Multi-platform mods often pick their platform half the {@code java.util.ServiceLoader} way: one
 * {@code META-INF/services} file lists a provider per loader, and the one that cannot link on the running loader drops
 * out on its own — on NeoForge a provider calling {@code FabricLoader} throws {@code NoClassDefFoundError} and the
 * mod's lookup skips it. On Forbric every loader's classes are live, so nothing drops out, and the jar's own choice can
 * disagree with {@code MultiLoaderArbiter}'s. rrls is the case: run as its NeoForge half, its config lookup lists
 * {@code FabricConfigImpl, FallbackConfigImpl, NeoForgeConfigImpl}; with ForgeConfigAPIPort installed the Fabric one
 * reports itself active, ties on priority and wins as the first listed, so the NeoForge constructor registers no
 * config (it only does so for its own impl), the Fabric entrypoint that would have registered one never runs (the
 * Fabric half is suppressed), and the first read in {@code Minecraft.<init>} throws "Cannot get config value before
 * config is loaded".
 *
 * <p>So for a universal jar, a provider whose class — or a supertype in the same jar — names a type its own loader would
 * not have is left out of the file the jar's {@code ServiceLoader} reads, which is the list its own loader would have
 * ended up with. Such a type is one of two things, each read off where the class comes from rather than off a mod's name:
 * <ul>
 *   <li>another loader's own platform: for a NeoForge or MinecraftForge owner, Fabric Loader's packages
 *       ({@code net/fabricmc/loader/}, {@code net/fabricmc/api/}) and the other Forge family's; for a Fabric owner only the
 *       Forge families' loading packages, {@code ModList} and {@code ModContainer}, because a Fabric port
 *       (ForgeConfigAPIPort) legitimately supplies NeoForge's config types on Fabric;</li>
 *   <li>a class outside every platform's packages that this class loader reads from a mod jar of another loader's family:
 *       a Fabric mod's class — fabric-api's included, which is a mod like any other — for a NeoForge owner, a NeoForge
 *       mod's for a Fabric owner. No such class exists on the owner's own loader.</li>
 * </ul>
 *
 * <p>Only disambiguates, never empties: a file is rewritten only when it lists two or more providers and at least one
 * survives; a provider whose bytes cannot be read is kept. Single-loader jars and everything unowned (the game, the
 * carriers, libraries) are served as they are. {@code -Dforbric.universalServices=off} serves every file unfiltered.
 */
public final class UniversalJarServices {
	public static final String PROPERTY = "forbric.universalServices";
	static final String PREFIX = "META-INF/services/";

	private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

	private UniversalJarServices() {
	}

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/**
	 * Internal-name prefixes of another loader's own platform, which a provider must not name to be usable by
	 * {@code owner}'s loader natively. Fabric Loader's packages only: fabric-api's classes are a mod's, judged by the jar
	 * they come from ({@link #usable}).
	 */
	static List<String> foreign(LoaderProbePolicy.Family owner) {
		return switch (owner) {
			case NEOFORGE -> List.of("net/fabricmc/loader/", "net/fabricmc/api/", "net/minecraftforge/");
			case FORGE -> List.of("net/fabricmc/loader/", "net/fabricmc/api/", "net/neoforged/");
			case FABRIC -> List.of("net/neoforged/fml/loading/", "net/minecraftforge/fml/loading/",
					ForeignType.MOD_LIST.internal(Ecosystem.NEOFORGE), ForeignType.MOD_LIST.internal(Ecosystem.FORGE),
					ForeignType.MOD_CONTAINER.internal(Ecosystem.NEOFORGE), ForeignType.MOD_CONTAINER.internal(Ecosystem.FORGE));
		};
	}

	/**
	 * The providers {@code owner} could use, in their listed order; {@code providers} itself when nothing is dropped,
	 * fewer than two are listed, or dropping would leave none.
	 *
	 * @param classInJar the bytes of a class (internal name) from the SAME jar, or null when it has none
	 * @param definingFamily the loader family of the mod jar this class loader reads a class (internal name) from, or null
	 *        when it is no mod's (the game, a carrier, a library, the kernel) or absent
	 */
	static List<String> usable(LoaderProbePolicy.Family owner, List<String> providers, Function<String, byte[]> classInJar,
			Function<String, LoaderProbePolicy.Family> definingFamily) {
		if (owner == null || providers.size() < 2) return providers;
		List<String> prefixes = foreign(owner);
		java.util.function.Predicate<String> foreign = type -> {
			for (String prefix : prefixes) if (type.startsWith(prefix)) return true;
			String binary = type.replace('/', '.');
			// A platform's class is judged by the table above alone; any other class by the mod jar it is read from.
			if (DelegationPolicy.alwaysGame(binary) || DelegationPolicy.alwaysParent(binary) || definingFamily == null) return false;
			LoaderProbePolicy.Family family = definingFamily.apply(type);
			return family != null && family != owner;
		};
		List<String> kept = new ArrayList<>();
		for (String provider : providers) {
			if (!namesAny(provider.replace('.', '/'), foreign, classInJar, new HashSet<>())) kept.add(provider);
		}
		return kept.isEmpty() || kept.size() == providers.size() ? providers : kept;
	}

	/** Whether {@code internal} or a supertype of it found in the same jar names a type {@code foreign} says the owner lacks. */
	private static boolean namesAny(String internal, java.util.function.Predicate<String> foreign, Function<String, byte[]> classInJar, Set<String> seen) {
		if (!seen.add(internal) || seen.size() > 32) return false;
		byte[] bytes = classInJar.apply(internal);
		if (bytes == null) return false;
		Set<String> named = new HashSet<>();
		List<String> supertypes = new ArrayList<>();
		try {
			new ClassReader(bytes).accept(new Collector(named, supertypes), ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		} catch (RuntimeException unreadable) {
			Caught.warn(unreadable, "[Forbric/Services] could not read service provider class %s; kept as usable", internal);
			return false;
		}
		for (String type : named) if (!type.equals(internal) && foreign.test(type)) return true;
		for (String supertype : supertypes) if (namesAny(supertype, foreign, classInJar, seen)) return true;
		return false;
	}

	/**
	 * The services file at {@code resource} as {@code owner}'s loader would read it: {@code resource} itself, or an
	 * in-memory URL serving the filtered list.
	 */
	static URL serve(URL resource, String serviceFile, LoaderProbePolicy.Family owner, Function<String, byte[]> classInJar,
			Function<String, LoaderProbePolicy.Family> definingFamily) {
		String text;
		try (InputStream in = resource.openStream()) {
			text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException unreadable) {
			Caught.warn(unreadable, "[Forbric/Services] could not read %s; serving it unfiltered", resource);
			return resource;
		}
		List<String> providers = providers(text);
		List<String> kept = usable(owner, providers, classInJar, definingFamily);
		if (kept == providers) return resource;

		String jar = ForbricClassLoader.jarUrlOf(resource);
		if (REPORTED.add(jar + "!" + serviceFile)) {
			List<String> dropped = new ArrayList<>(providers);
			dropped.removeAll(kept);
			ForbricLog.info("[Forbric/Services] %s runs as its %s half, so its %s lists only %s — %s name%s another loader and "
							+ "could not link on %s's own (-D%s=off to serve the file unfiltered)", jar.substring(jar.lastIndexOf('/') + 1),
					owner, serviceFile.substring(PREFIX.length()), kept, dropped, dropped.size() == 1 ? "s" : "", owner, PROPERTY);
		}
		byte[] body = (String.join("\n", kept) + "\n").getBytes(StandardCharsets.UTF_8);
		try {
			return new URL(null, "forbric-services:" + resource, new URLStreamHandler() {
				@Override
				protected URLConnection openConnection(URL url) {
					return new URLConnection(url) {
						@Override
						public void connect() {
						}

						@Override
						public InputStream getInputStream() {
							return new ByteArrayInputStream(body);
						}
					};
				}
			});
		} catch (java.net.MalformedURLException impossible) {
			Caught.warn(impossible, "[Forbric/Services] could not build the filtered URL for %s; serving it unfiltered",
					resource);
			return resource;
		}
	}

	/** The provider names in a services file: one per line, {@code #} comments and blanks dropped, duplicates once. */
	static List<String> providers(String text) {
		List<String> out = new ArrayList<>();
		for (String line : text.split("\n")) {
			int hash = line.indexOf('#');
			String name = (hash >= 0 ? line.substring(0, hash) : line).strip();
			if (!name.isEmpty() && !out.contains(name)) out.add(name);
		}
		return out;
	}

	/** Every type a class names anywhere it can, plus its direct supertypes. */
	private static final class Collector extends ClassVisitor {
		private final Set<String> named;
		private final List<String> supertypes;

		Collector(Set<String> named, List<String> supertypes) {
			super(Opcodes.ASM9);
			this.named = named;
			this.supertypes = supertypes;
		}

		private void type(Type type) {
			if (type == null) return;
			switch (type.getSort()) {
				case Type.ARRAY -> type(type.getElementType());
				case Type.OBJECT -> named.add(type.getInternalName());
				case Type.METHOD -> {
					type(type.getReturnType());
					for (Type argument : type.getArgumentTypes()) type(argument);
				}
				default -> { }
			}
		}

		private void descriptor(String desc) {
			if (desc != null) type(desc.startsWith("(") ? Type.getMethodType(desc) : Type.getType(desc));
		}

		private void constant(Object value) {
			if (value instanceof Type type) type(type);
			else if (value instanceof Handle handle) {
				named.add(handle.getOwner());
				descriptor(handle.getDesc());
			}
		}

		@Override
		public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
			if (superName != null) {
				named.add(superName);
				supertypes.add(superName);
			}
			if (interfaces != null) {
				for (String i : interfaces) {
					named.add(i);
					supertypes.add(i);
				}
			}
		}

		@Override
		public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
			descriptor(desc);
			return null;
		}

		@Override
		public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
			descriptor(desc);
			if (exceptions != null) for (String e : exceptions) named.add(e);
			return new MethodVisitor(Opcodes.ASM9) {
				@Override
				public void visitTypeInsn(int opcode, String type) {
					if (type.startsWith("[")) descriptor(type); else named.add(type);
				}

				@Override
				public void visitFieldInsn(int opcode, String owner, String name, String desc) {
					named.add(owner);
					descriptor(desc);
				}

				@Override
				public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
					if (owner.startsWith("[")) descriptor(owner); else named.add(owner);
					descriptor(desc);
				}

				@Override
				public void visitInvokeDynamicInsn(String name, String desc, Handle bootstrap, Object... arguments) {
					descriptor(desc);
					for (Object argument : arguments) constant(argument);
				}

				@Override
				public void visitLdcInsn(Object value) {
					constant(value);
				}

				@Override
				public void visitMultiANewArrayInsn(String desc, int dims) {
					descriptor(desc);
				}

				@Override
				public void visitTryCatchBlock(org.objectweb.asm.Label start, org.objectweb.asm.Label end,
						org.objectweb.asm.Label handler, String type) {
					if (type != null) named.add(type);
				}
			};
		}
	}

	/** Test seam: forget which files were already reported. */
	static void forget() {
		REPORTED.clear();
	}
}
