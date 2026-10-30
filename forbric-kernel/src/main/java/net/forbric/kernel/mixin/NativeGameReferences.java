/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

/**
 * Reads how each ecosystem natively compiled a game class: hash-pinned, non-executable original bytes, from the sources
 * {@link NativeReferenceSources} decided at boot — the vanilla-reference sidecar, the base's embedded index, or the
 * base's own bytes. A reader with no sources (tests, and anything before {@link #configure}) reads the embedded index
 * through its resource function alone, which is what every reader did before the sidecar existed.
 */
public final class NativeGameReferences {
	private static volatile NativeGameReferences active;
	/** What KernelBoot read off its launch files; null until then, and in every test that does not set it. */
	private static volatile NativeReferenceSources configured;
	/** Said once per process: a sidecar measured against another base answers nothing for what that base changed. */
	private static final AtomicBoolean staleSidecarReported = new AtomicBoolean();
	private final Function<String, byte[]> resources;
	private final Function<String, byte[]> currentClasses;
	private final NativeReferenceSources sources;
	private Function<String, net.forbric.kernel.classloading.LoaderProbePolicy.Family> resourceFamilies;
	private final Map<Ecosystem, Pins> pins = new EnumMap<>(Ecosystem.class);

	public NativeGameReferences(Function<String, byte[]> resources) {
		this(resources, null, null);
	}
	public NativeGameReferences(Function<String, byte[]> resources, Function<String, byte[]> currentClasses) {
		this(resources, currentClasses, null);
	}
	/**
	 * @param resources      game resources by path: the embedded index, and the classes it pins by hash
	 * @param currentClasses the current pipeline's pre-Mixin bytes, or null
	 * @param sources        where each ecosystem's references come from, or null for the embedded index alone
	 */
	public NativeGameReferences(Function<String, byte[]> resources, Function<String, byte[]> currentClasses,
			NativeReferenceSources sources) {
		this.resources = resources;
		this.currentClasses = currentClasses;
		this.sources = sources;
	}

	/**
	 * Records where this launch's references come from and logs the one line that says so. KernelBoot calls it as soon
	 * as it has read its arguments, before any reader exists; null clears it (tests).
	 */
	public static void configure(NativeReferenceSources sources) {
		configured = sources;
		if (sources != null) ForbricLog.info(sources.describe());
	}

	/** What {@link #configure} recorded, or null. */
	public static NativeReferenceSources configured() {
		return configured;
	}

	/**
	 * A reader over {@code resources} with this launch's sources, for a caller that holds its own view of the game's
	 * resources rather than the bound loader's ({@code LootSourceContracts}): its FABRIC answers come from the sidecar
	 * whenever the launch has one, as every other reader's do.
	 */
	public static NativeGameReferences over(Function<String, byte[]> resources) {
		return new NativeGameReferences(resources, null, configured);
	}

	static void bind(ForbricClassLoader loader) {
		if (loader == null) {
			active = null;
			return;
		}
		NativeGameReferences reader = new NativeGameReferences(path -> {
			try (var stream = loader.getGameResourceAsStream(path)) {
				return stream == null ? null : stream.readAllBytes();
			} catch (IOException unavailable) {
				Caught.warn(unavailable, "[Forbric/Mixin] could not read game resource %s; taken as absent", path);
				return null;
			}
		}, owner -> loader.getPreMixinClassBytes(owner.replace('/', '.')), configured);
		reader.resourceFamilies=owner->loader.familyOfResource(owner.replace('/','.'));
		active=reader;
	}

	public static ClassNode reference(Ecosystem ecosystem, String owner) {
		NativeGameReferences reader = active;
		return reader == null ? null : reader.get(ecosystem, owner);
	}
	/** {@code owner} as every ecosystem other than {@code ecosystem} compiled it natively, where a reference exists. */
	public static java.util.List<ClassNode> peers(Ecosystem ecosystem, String owner) {
		java.util.List<ClassNode> peers = new java.util.ArrayList<>();
		for (Ecosystem other : Ecosystem.values()) {
			if (other == ecosystem) continue;
			ClassNode peer = reference(other, owner);
			if (peer != null) peers.add(peer);
		}
		return peers;
	}
	/** The authoritative current pipeline bytes, without defining or initializing a dependency class. */
	public static ClassNode current(String owner) {
		NativeGameReferences reader=active;if(reader==null||reader.currentClasses==null||owner==null)return null;
		byte[]bytes=reader.currentClasses.apply(owner);if(bytes==null)return null;ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);return owner.equals(node.name)?node:null;
	}
	/** Raw external platform helper, with resource-family provenance. It is separate from the indexed game view. */
	public static ClassNode runtime(Ecosystem ecosystem,String owner){
		NativeGameReferences reader=active;if(reader==null||reader.resourceFamilies==null||ecosystem==null||owner==null)return null;
		var expected=net.forbric.kernel.classloading.LoaderProbePolicy.familyOf(ecosystem);
		if(reader.resourceFamilies.apply(owner)!=expected)return null;byte[]bytes=reader.resources.apply(owner+".class");if(bytes==null)return null;ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);return owner.equals(node.name)?node:null;
	}

	public synchronized ClassNode get(Ecosystem ecosystem, String owner) {
		return get(ecosystem, owner, ClassReader.SKIP_FRAMES);
	}

	/** Whether the index pins a native body of {@code owner} for {@code ecosystem}, without reading it. */
	public static boolean has(Ecosystem ecosystem, String owner) {
		NativeGameReferences reader = active;
		if (reader == null || ecosystem == null || owner == null) return false;
		synchronized (reader) {
			return reader.pins(ecosystem).has(owner);
		}
	}

	/**
	 * {@code owner} as {@code ecosystem} compiled it natively, read with its frames expanded as Mixin reads a target —
	 * for running Mixin's own injection-point code over the native body, which reads locals off those frames.
	 */
	public static ClassNode referenceWithFrames(Ecosystem ecosystem, String owner) {
		NativeGameReferences reader = active;
		return reader == null ? null : reader.get(ecosystem, owner, ClassReader.EXPAND_FRAMES);
	}

	private synchronized ClassNode get(Ecosystem ecosystem, String owner, int flags) {
		if (ecosystem == null || owner == null) return null;
		byte[] bytes = pins(ecosystem).bytes(owner);
		if (bytes == null) return null;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, flags);
		return owner.equals(node.name) ? node : null;
	}

	/** One ecosystem's references: which owners they pin, and each one's native bytes. */
	private interface Pins {
		boolean has(String owner);

		/** {@code owner}'s native bytes, or null when they are not pinned or do not verify. */
		byte[] bytes(String owner);
	}

	private static final Pins NO_PINS = new Pins() {
		@Override public boolean has(String owner) { return false; }
		@Override public byte[] bytes(String owner) { return null; }
	};

	private Pins pins(Ecosystem ecosystem) {
		return pins.computeIfAbsent(ecosystem, this::open);
	}

	private Pins open(Ecosystem ecosystem) {
		NativeReferenceSources.Source source = sources == null ? NativeReferenceSources.Source.EMBEDDED
				: sources.source(ecosystem);
		return switch (source) {
			// The sidecar's hash-only rows are classes the base holds unchanged: answered from the base jar itself, the
			// file the sidecar was measured against, and accepted only while they still hash to the row.
			case SIDECAR -> indexed(ecosystem, sources::sidecarResource, owner -> {
				byte[] base = sources.baseClass(owner);
				return base != null ? base : resources.apply(owner + ".class");
			}, true);
			case EMBEDDED -> indexed(ecosystem, resources, owner -> resources.apply(owner + ".class"), false);
			// The base is this ecosystem's compile: every class it holds is a reference, exactly as it is on disk. There
			// is no index to verify against and none is needed -- the bytes come from the one file they are about.
			case BASE -> new Pins() {
				@Override public boolean has(String owner) { return sources.baseHas(owner); }
				@Override public byte[] bytes(String owner) { return sources.baseClass(owner); }
			};
			case NONE -> NO_PINS;
		};
	}

	/**
	 * Pins read from a {@code META-INF/forbric/native-reference/<ECO>/} tree: the index's hash decides, whether the bytes
	 * come from the class's own {@code .class.bin} copy or, for a class the base did not change, from {@code unchanged}.
	 */
	private static Pins indexed(Ecosystem ecosystem, Function<String, byte[]> entries, Function<String, byte[]> unchanged,
			boolean sidecar) {
		Map<String, String> index = index(entries.apply(prefix(ecosystem) + "index.tsv"));
		return new Pins() {
			@Override public boolean has(String owner) {
				return index.containsKey(owner);
			}

			@Override public byte[] bytes(String owner) {
				String expected = index.get(owner);
				if (expected == null) return null;
				byte[] bytes = entries.apply(prefix(ecosystem) + owner + ".class.bin");
				boolean copied = bytes != null;
				if (!copied) bytes = unchanged.apply(owner);
				if (bytes == null) return null;
				if (expected.equals(hash(bytes))) return bytes;
				// With a sidecar there is one way here: it was measured against another base, which changed this class
				// without the sidecar keeping a copy. Every such class goes unanswered; say once why.
				if (sidecar && !copied && staleSidecarReported.compareAndSet(false, true)) {
					ForbricLog.warn("[Forbric/Mixin] the vanilla-reference sidecar was built against another base: %s "
							+ "differs from it without a vanilla copy, so it and every class like it has no FABRIC reference. "
							+ "Rebuild the sidecar (reinstall, or tools/dev.py prepare)", owner);
				}
				return null;
			}
		};
	}

	private static Map<String, String> index(byte[] bytes) {
		if (bytes == null) return Map.of();
		String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\\R");
		if (lines.length == 0 || !lines[0].equals("# forbric-native-reference-v1")) return Map.of();
		Map<String, String> result = new HashMap<>();
		for (int i = 1; i < lines.length; i++) {
			if (lines[i].isBlank()) continue;
			String[] parts = lines[i].split("\\t", -1);
			if (parts.length != 2 || !parts[1].matches("[a-f0-9]{64}")
					|| result.putIfAbsent(parts[0], parts[1]) != null) return Map.of();
		}
		return Map.copyOf(result);
	}

	private static String prefix(Ecosystem ecosystem) {
		return NativeReferenceSources.PREFIX + ecosystem.name() + "/";
	}

	private static String hash(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException impossible) {
			throw new AssertionError(impossible);
		}
	}
}
