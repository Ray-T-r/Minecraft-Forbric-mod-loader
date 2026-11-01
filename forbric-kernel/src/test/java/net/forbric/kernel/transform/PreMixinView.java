/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * A staged game class as the Mixin adapters see it in the game, not as the jar holds it. The adapters read their target
 * through {@code ForbricMixinService.mergedBaseNode}, which asks the loader for its pre-Mixin bytes: the output of the
 * kernel's transformer chain. On the genuine NeoForge base that chain matters to them, because
 * {@link VanillaMemberTwinInjector} adds back the vanilla members NeoForge removed (Hud's
 * {@code extractHotbarAndDecorations}, vanilla's {@code CustomPacketPayload.codec}, the PiP map…) and
 * {@link ForbricMergedBaseCompatTransformer} then wires them to NeoForge's live ones. A test that hands an adapter the raw
 * jar class measures a target the game never shows it.
 *
 * <p>This applies those two, in KernelBoot's order (both COREMOD, the twins registered first), and nothing else of the
 * chain. The twins' vanilla bodies are read from the vanilla client jar with expanded frames: the bytes the launch's
 * vanilla-reference sidecar answers for FABRIC ({@code VanillaReferenceStagedTest} proves every class it indexes is
 * vanilla's own). No process-wide state is touched, so no resource lock is needed.
 */
public final class PreMixinView {
	private static final Map<String, byte[]> VIEWED = new ConcurrentHashMap<>();
	private static final Map<String, byte[]> RAW = new ConcurrentHashMap<>();
	private static final byte[] NONE = new byte[0];
	private static Function<String, ClassNode> vanilla;

	private PreMixinView() {
	}

	/**
	 * {@code raw} (the class {@code internalName} as the staged jars hold it) after the twins and the compat repairs, or
	 * {@code raw} itself when neither changes it.
	 */
	public static byte[] of(String internalName, byte[] raw) {
		if (raw == null) return null;
		byte[] known = VIEWED.get(internalName);
		if (known != null) return known == NONE ? raw : known;
		String dotted = internalName.replace('/', '.');
		byte[] twinned = new VanillaMemberTwinInjector(vanilla()).transform(dotted, raw, null);
		byte[] repaired = new ForbricMergedBaseCompatTransformer(PreMixinView::raw).transform(dotted, twinned, null);
		VIEWED.put(internalName, repaired == raw ? NONE : repaired);
		return repaired;
	}

	/**
	 * {@code raw} after the vanilla twins alone: what the chain hands {@link ForbricMergedBaseCompatTransformer}, for a test
	 * of one of its repairs that wires a twin to NeoForge's live member (the PiP bridge, the particle-map view).
	 */
	public static byte[] twinned(String internalName, byte[] raw) {
		if (raw == null) return null;
		return new VanillaMemberTwinInjector(vanilla()).transform(internalName.replace('/', '.'), raw, null);
	}

	private static Path twinnedBase;

	/**
	 * {@link TestFixtures#stagedBase()} as one jar with every class passed through {@link VanillaMemberTwinInjector}, the
	 * first COREMOD transformer KernelBoot registers, and nothing else of the chain: the base a census generator reads when
	 * its table's runtime consumer asks Mixin's view of a class (post-chain), not the raw jar. Written once per base, vanilla
	 * jar and injector ({@link #cacheKey}) under {@code build/twinned-base/}, which {@code cleanTest} keeps, and kept as a jar
	 * so every generator's {@code ZipFile} code reads it
	 * unchanged. Asserts that every one of the injector's rows landed: without a vanilla reference the VANILLA_BODY rows
	 * stand down without a word, and a census would then write a table that lacks the twins.
	 */
	public static synchronized Path twinnedBase() {
		if (twinnedBase != null) return twinnedBase;
		Path base = TestFixtures.stagedBase();
		TestFixtures.requireFiles(Fixture.STAGED, "the base the twins are added to", base);
		Path vanillaJar = TestFixtures.vanillaJar();
		TestFixtures.requireFiles(Fixture.MC_LIBRARIES, "the vanilla client jar the twins copy from", vanillaJar);
		try {
			String key = cacheKey(base, vanillaJar);
			Path out = Path.of(System.getProperty("user.dir"), "build", "twinned-base", key, "base.jar");
			Set<String> owners = new HashSet<>();
			for (VanillaMemberTwinInjector.Row row : VanillaMemberTwinInjector.ROWS) owners.add(row.owner());
			if (!Files.isRegularFile(out)) {
				Files.createDirectories(out.getParent());
				Path partial = out.resolveSibling("base.jar.partial");
				try (ZipFile in = new ZipFile(base.toFile()); ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(partial))) {
					for (ZipEntry entry : Collections.list(in.entries())) {
						byte[] bytes;
						try (InputStream stream = in.getInputStream(entry)) { bytes = stream.readAllBytes(); }
						String name = entry.getName();
						String internal = name.endsWith(".class") ? name.substring(0, name.length() - ".class".length()) : null;
						if (internal != null && owners.contains(internal)) bytes = twinned(internal, bytes);
						zip.putNextEntry(new ZipEntry(name));
						zip.write(bytes);
						zip.closeEntry();
					}
				}
				Files.move(partial, out, StandardCopyOption.REPLACE_EXISTING);
			}
			try (ZipFile twinned = new ZipFile(out.toFile())) {
				for (VanillaMemberTwinInjector.Row row : VanillaMemberTwinInjector.ROWS) {
					ZipEntry entry = twinned.getEntry(row.owner() + ".class");
					if (entry == null) throw new AssertionError("the twinned base lacks " + row.owner());
					ClassNode node = new ClassNode();
					try (InputStream stream = twinned.getInputStream(entry)) {
						new ClassReader(stream.readAllBytes()).accept(node, ClassReader.SKIP_CODE);
					}
					boolean landed = row.kind() == VanillaMemberTwinInjector.Kind.FIELD
							? node.fields.stream().anyMatch(f -> f.name.equals(row.name()) && f.desc.equals(row.desc()))
							: node.methods.stream().anyMatch(m -> m.name.equals(row.name()) && m.desc.equals(row.desc()));
					if (!landed) throw new AssertionError("a vanilla twin did not land on the census base: " + row);
				}
			}
			twinnedBase = out;
			return out;
		} catch (IOException unwritable) {
			throw new AssertionError("could not write the twinned base for " + base, unwritable);
		}
	}

	/**
	 * The directory name of a twinned base: the base's and the vanilla jar's path, size and time, and what the injector would
	 * do — its rows as written and the bytes of every class of its nest. A cache keyed on the jars alone kept a twin the
	 * injector no longer adds (or adds another way) after a change to it, and the landing check below sees added rows only,
	 * so a census would have read a removed twin's members as still there.
	 */
	static String cacheKey(Path base, Path vanillaJar) throws IOException {
		java.security.MessageDigest digest;
		try {
			digest = java.security.MessageDigest.getInstance("SHA-256");
		} catch (java.security.NoSuchAlgorithmException impossible) {
			throw new AssertionError(impossible);
		}
		digest.update((base.toAbsolutePath().normalize() + "|" + Files.size(base) + "|" + Files.getLastModifiedTime(base).toMillis()
				+ "|" + vanillaJar.toAbsolutePath().normalize() + "|" + Files.size(vanillaJar) + "|"
				+ Files.getLastModifiedTime(vanillaJar).toMillis() + "|" + VanillaMemberTwinInjector.ROWS)
				.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		java.util.List<Class<?>> nest = new java.util.ArrayList<>(java.util.List.of(VanillaMemberTwinInjector.class.getNestMembers()));
		nest.sort(java.util.Comparator.comparing(Class::getName));
		for (Class<?> member : nest) {
			try (InputStream in = member.getResourceAsStream("/" + member.getName().replace('.', '/') + ".class")) {
				if (in == null) throw new AssertionError("cannot read the class file of " + member.getName());
				digest.update(member.getName().getBytes(java.nio.charset.StandardCharsets.UTF_8));
				digest.update(in.readAllBytes());
			}
		}
		return java.util.HexFormat.of().formatHex(digest.digest(), 0, 8);
	}

	/** The same view parsed with {@link ClassReader} {@code flags}. */
	public static ClassNode node(String internalName, byte[] raw, int flags) {
		byte[] bytes = of(internalName, raw);
		if (bytes == null) return null;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, flags);
		return node;
	}

	/** A raw game class by internal name or resource path: the base, then the NeoForge carrier, then the JDK. */
	static byte[] raw(String name) {
		String key = name.endsWith(".class") ? name.substring(0, name.length() - ".class".length())
				: name.contains("/") ? name : name.replace('.', '/');
		byte[] found = RAW.get(key);
		if (found != null) return found == NONE ? null : found;
		byte[] bytes = StagedGameClasses.resource(StagedGameClasses.jars(), key + ".class");
		RAW.put(key, bytes == null ? NONE : bytes);
		return bytes;
	}

	private static synchronized Function<String, ClassNode> vanilla() {
		if (vanilla != null) return vanilla;
		Path jar = TestFixtures.vanillaJar();
		TestFixtures.requireFiles(Fixture.MC_LIBRARIES, "the vanilla client jar the twins copy from", jar);
		Map<String, byte[]> cache = new ConcurrentHashMap<>();
		vanilla = owner -> {
			byte[] bytes = cache.computeIfAbsent(owner, o -> {
				try (ZipFile zip = new ZipFile(jar.toFile())) {
					var entry = zip.getEntry(o + ".class");
					if (entry == null) return NONE;
					try (InputStream in = zip.getInputStream(entry)) { return in.readAllBytes(); }
				} catch (IOException unreadable) {
					throw new AssertionError("could not read " + o + " from " + jar, unreadable);
				}
			});
			if (bytes == NONE) return null;
			ClassNode node = new ClassNode();
			new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
			return node;
		};
		return vanilla;
	}
}
