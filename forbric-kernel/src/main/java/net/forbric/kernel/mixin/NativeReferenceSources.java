/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.util.Caught;
import org.objectweb.asm.ClassReader;

/**
 * Where each ecosystem's native game bytes come from, decided once at boot from the files the kernel was launched with
 * and nothing else.
 *
 * <p>{@link NativeGameReferences} answers "how did ecosystem E compile class C" — the evidence dozens of mixin adapters
 * compare against before they touch a guest mixin. That evidence used to have exactly one home: the
 * {@code META-INF/forbric/native-reference/<ECO>/} trees the old loader's merge tool embeds in the merged base, read
 * through the game class loader, with a null answer when absent. A base that is not that merge embeds nothing, and the
 * null answer is silent: measured on the genuine NeoForge-patched jar, every adapter that compares against vanilla stood
 * down, 26 Fabric injectors warned that had not before, and fabric-api alone left 13 vanilla datapack registries empty.
 * So the sources are spelled out here instead of being whatever the classpath happens to hold:
 *
 * <ol>
 *   <li><b>The vanilla-reference sidecar</b> ({@code --nativeReference}, built by the installer from the vanilla jar and
 *       the base): the references of every ecosystem whose index it carries — FABRIC, in what the installer writes. It
 *       is preferred over the base's embedded index for those ecosystems: on the merged base the two are equal row for
 *       row (VanillaReferenceStagedTest), and on any other base only the sidecar exists. Where the base embeds the same
 *       index, that index still answers, class by class, whatever the sidecar cannot verify against it: a sidecar built
 *       against another base never costs the merged base evidence it carries itself.</li>
 *   <li><b>The base's embedded index</b>, for every other ecosystem it carries — FORGE and NEOFORGE on the merged base.
 *       Read through the game class loader exactly as before.</li>
 *   <li><b>The base itself</b>, for the base's own ecosystem when it embeds no index at all: a NeoForge-patched base IS
 *       NeoForge's compile of every class it holds, so its untransformed bytes are the NEOFORGE references, read on
 *       demand from the jar. Nothing is generated, so nothing can go stale.</li>
 * </ol>
 *
 * <p>Which ecosystem a base belongs to is read off the base, never guessed from a file name: its {@code Block} declares
 * NeoForge's block extension interface and no MinecraftForge one (NEOFORGE), MinecraftForge's and no NeoForge one
 * (FORGE), or neither (plain vanilla, which is FABRIC's own compile). A base that declares both is the merge, which
 * embeds its index; without one it has no own ecosystem to answer for, because its bytes are neither side's compile.
 *
 * <p>{@link #describe()} is the one boot line naming those sources; gates assert its wording.
 */
public final class NativeReferenceSources {
	static final String PREFIX = "META-INF/forbric/native-reference/";
	private static final String BLOCK = "net/minecraft/world/level/block/Block.class";
	private static final String NEO_BLOCK = "net/neoforged/neoforge/common/extensions/IBlockExtension";
	private static final String FORGE_PACKAGE = "net/minecraftforge/";

	private final Path base;
	private final Path sidecar;
	private final ZipFile baseJar;
	private final ZipFile sidecarJar;
	private final Map<Ecosystem, Source> sources;
	private final Ecosystem baseEcosystem;

	/** Where one ecosystem's references come from. */
	enum Source {
		/** {@code --nativeReference}: indexed, its copies in the sidecar, hash-only rows answered from the base. */
		SIDECAR,
		/** The base's own {@code META-INF/forbric/native-reference/<ECO>/}, through the game class loader. */
		EMBEDDED,
		/** The base's raw class bytes: the base is that ecosystem's compile. */
		BASE,
		/** No source: every reference for it is absent. */
		NONE
	}

	private NativeReferenceSources(Path base, ZipFile baseJar, Path sidecar, ZipFile sidecarJar) {
		this.base = base;
		this.baseJar = baseJar;
		this.sidecar = sidecar;
		this.sidecarJar = sidecarJar;
		Map<Ecosystem, Source> decided = new EnumMap<>(Ecosystem.class);
		boolean embedsAny = false;
		for (Ecosystem ecosystem : Ecosystem.values()) embedsAny |= has(baseJar, index(ecosystem));
		Ecosystem own = embedsAny ? null : ownEcosystem(baseJar);
		for (Ecosystem ecosystem : Ecosystem.values()) {
			if (has(sidecarJar, index(ecosystem))) decided.put(ecosystem, Source.SIDECAR);
			else if (has(baseJar, index(ecosystem))) decided.put(ecosystem, Source.EMBEDDED);
			else if (ecosystem == own) decided.put(ecosystem, Source.BASE);
			else decided.put(ecosystem, Source.NONE);
		}
		this.sources = decided;
		this.baseEcosystem = own;
	}

	/**
	 * The sources for a launch with {@code base} as its game jar and {@code sidecar} (may be null) as its
	 * {@code --nativeReference}. A file that cannot be opened counts as absent and says so; it never stops the boot,
	 * because without it the kernel is exactly as it was before this source existed.
	 */
	public static NativeReferenceSources open(Path base, Path sidecar) {
		ZipFile baseJar = openJar(base, "game jar");
		ZipFile sidecarJar = sidecar == null ? null : openJar(sidecar, "--nativeReference sidecar");
		return new NativeReferenceSources(base, baseJar, sidecarJar == null ? null : sidecar, sidecarJar);
	}

	private static ZipFile openJar(Path jar, String role) {
		if (jar == null) return null;
		if (!Files.isRegularFile(jar)) {
			net.forbric.kernel.util.ForbricLog.warn("[Forbric/Mixin] the %s %s does not exist; taken as absent", role, jar);
			return null;
		}
		try {
			return new ZipFile(jar.toFile());
		} catch (IOException unreadable) {
			Caught.warn(unreadable, "[Forbric/Mixin] could not open the %s %s; taken as absent", role, jar);
			return null;
		}
	}

	/** Where {@code ecosystem}'s references come from. */
	Source source(Ecosystem ecosystem) {
		return ecosystem == null ? Source.NONE : sources.get(ecosystem);
	}

	/** The base's own ecosystem when it is answered from its raw bytes, else null. */
	Ecosystem baseEcosystem() {
		return baseEcosystem;
	}

	/** A sidecar entry, e.g. {@code META-INF/forbric/native-reference/FABRIC/index.tsv}, or null. */
	byte[] sidecarResource(String path) {
		return read(sidecarJar, path, "the vanilla-reference sidecar");
	}

	/** {@code owner}'s class file exactly as the base jar holds it, before any transformer, or null. */
	byte[] baseClass(String owner) {
		return read(baseJar, owner + ".class", "the game jar");
	}

	/** Whether the base jar holds {@code owner}. */
	boolean baseHas(String owner) {
		return has(baseJar, owner + ".class");
	}

	/** An entry of the base jar itself, e.g. its own {@code META-INF/forbric/native-reference/FABRIC/index.tsv}, or null. */
	byte[] baseResource(String path) {
		return read(baseJar, path, "the game jar");
	}

	/**
	 * Whether the base jar embeds {@code ecosystem}'s index itself — the merged base does, for all three. Where the
	 * sidecar is that ecosystem's source, the embedded index answers every class the sidecar cannot verify.
	 */
	boolean baseEmbeds(Ecosystem ecosystem) {
		return ecosystem != null && has(baseJar, index(ecosystem));
	}

	/**
	 * The boot line naming every ecosystem's source. Gates assert {@code native references: FABRIC from the
	 * vanilla-reference sidecar}; keep that wording.
	 */
	public String describe() {
		List<String> parts = new ArrayList<>();
		for (Ecosystem ecosystem : Ecosystem.values()) {
			String from = switch (source(ecosystem)) {
				case SIDECAR -> "from the vanilla-reference sidecar " + sidecar.getFileName()
						+ (baseEmbeds(ecosystem) ? " (the base's embedded index answers what it cannot verify)" : "");
				case EMBEDDED -> "from the base's embedded index";
				case BASE -> "from the base's own bytes (" + base.getFileName() + " is " + ecosystem.displayName()
						+ "'s compile)";
				case NONE -> "none";
			};
			parts.add(ecosystem.name() + " " + from);
		}
		String line = "[Forbric/Mixin] native references: " + String.join(", ", parts);
		if (source(Ecosystem.FABRIC) == Source.NONE) {
			line += " — no vanilla reference, so every adapter that compares a Fabric mixin against vanilla stands down"
					+ " (pass --nativeReference; the installer writes one)";
		}
		return line;
	}

	private static String index(Ecosystem ecosystem) {
		return PREFIX + ecosystem.name() + "/index.tsv";
	}

	private static boolean has(ZipFile jar, String path) {
		return jar != null && jar.getEntry(path) != null;
	}

	private static byte[] read(ZipFile jar, String path, String what) {
		if (jar == null) return null;
		ZipEntry entry = jar.getEntry(path);
		if (entry == null) return null;
		try (InputStream in = jar.getInputStream(entry)) {
			return in.readAllBytes();
		} catch (IOException unreadable) {
			Caught.warn(unreadable, "[Forbric/Mixin] could not read %s from %s; taken as absent", path, what);
			return null;
		}
	}

	/**
	 * The ecosystem whose compile {@code base} is, read off its {@code Block}: see the class comment. Null when the base
	 * has no {@code Block}, or declares both families' interfaces.
	 */
	private static Ecosystem ownEcosystem(ZipFile base) {
		byte[] block = read(base, BLOCK, "the game jar");
		if (block == null) return null;
		String[] interfaces;
		try {
			interfaces = new ClassReader(block).getInterfaces();
		} catch (RuntimeException malformed) {
			Caught.warn(malformed, "[Forbric/Mixin] the game jar's Block does not parse; its own ecosystem is taken as unknown");
			return null;
		}
		boolean neo = false, forge = false;
		for (String type : interfaces) {
			neo |= type.equals(NEO_BLOCK);
			forge |= type.startsWith(FORGE_PACKAGE);
		}
		if (neo && forge) return null;
		return neo ? Ecosystem.NEOFORGE : forge ? Ecosystem.FORGE : Ecosystem.FABRIC;
	}
}
