/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.zip.ZipFile;

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
