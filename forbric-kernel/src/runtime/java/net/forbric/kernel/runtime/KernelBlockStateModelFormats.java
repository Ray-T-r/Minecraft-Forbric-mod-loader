/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.util.Optional;
import java.util.stream.Stream;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;

import net.forbric.kernel.mixin.FabricBlockStateCodecMixinAdapter;
import net.forbric.kernel.util.ForbricLog;

/**
 * Keeps NeoForge's custom block-state models readable when fabric-model-loading is installed.
 *
 * <h2>Two dialects, one codec field</h2>
 *
 * <p>A blockstate variant can name a custom model in both ecosystems: NeoForge reads {@code "type"} (registered with
 * {@code RegisterBlockStateModels}), Fabric reads {@code "fabric:type"} (registered with
 * {@code CustomUnbakedBlockStateModel.register}). NeoForge's patch builds {@code BlockStateModel.Unbaked.CODEC} and
 * {@code HARDCODED_WEIGHTED_CODEC} around its key. fabric-model-loading's {@code BlockStateModelUnbakedMixin}
 * redirects the two {@code flatComapMap} calls that build those fields and returns its own codecs, which read
 * {@code "fabric:type"} and otherwise a plain variant — so without this NeoForge's key was never read.
 *
 * <p>What that cost: Sophisticated Backpacks' placed backpack ({@code "type": "sophisticatedbackpacks:backpack_model_loader"})
 * parsed as a plain variant of a model with no elements, and rendered as its selection outline only — whenever
 * fabric-api was installed, with or without Sodium (issue #16). Every NeoForge mod with a custom block-state model
 * type loses its block the same way.
 *
 * <h2>The rule</h2>
 *
 * <p>{@code FabricBlockStateCodecMixinAdapter} lets each redirect make the call it replaced, and hands both answers
 * here. Decoding gives Fabric's codec what names Fabric's key — a variant object with {@code "fabric:type"}, or a
 * variant list with such an entry — and NeoForge's codec everything else, which for a variant without either key is
 * the same plain variant both would have read. A variant naming both keys goes to Fabric first, as
 * {@code KernelModelFormats} does for model files; when Fabric cannot read it (no deserializer under that id) it
 * goes to NeoForge, the build of the mod that is installed. Encoding asks NeoForge first and Fabric for what NeoForge
 * rejects (a Fabric custom model).
 *
 * <p>{@code -Dforbric.blockStateModelFormats=off} returns Fabric's codec alone, as before.
 */
public final class KernelBlockStateModelFormats {
	static final String FABRIC_KEY = "fabric:type";
	static final String NEOFORGE_KEY = "type";

	private static volatile boolean announced;

	private KernelBlockStateModelFormats() {
	}

	/**
	 * The codec the redirected {@code flatComapMap} returns: NeoForge's answer and Fabric's, combined by the rule in
	 * the class javadoc. Used for both fields — the variant list and the model — whose inputs differ only in shape.
	 *
	 * @param neoForge what the redirected call itself returns, i.e. the base's own codec
	 * @param fabric   what fabric-model-loading's handler returns
	 */
	public static <A> Codec<A> either(Codec<A> neoForge, Codec<A> fabric) {
		if (!enabled() || neoForge == null) return fabric;
		if (fabric == null) return neoForge;
		if (!announced) {
			announced = true;
			ForbricLog.info("[Forbric/ModelFormats] blockstate variants are read by NeoForge's codec unless they name \"%s\" "
					+ "— fabric-model-loading had replaced it outright, so NeoForge's \"%s\" models (Sophisticated "
					+ "Backpacks' backpack) parsed as empty plain variants", FABRIC_KEY, NEOFORGE_KEY);
		}
		return new Codec<>() {
			@Override
			public <T> DataResult<Pair<A, T>> decode(DynamicOps<T> ops, T input) {
				if (!names(ops, input, FABRIC_KEY)) return neoForge.decode(ops, input);
				DataResult<Pair<A, T>> read = fabric.decode(ops, input);
				return read.isError() && names(ops, input, NEOFORGE_KEY) ? neoForge.decode(ops, input) : read;
			}

			@Override
			public <T> DataResult<T> encode(A input, DynamicOps<T> ops, T prefix) {
				DataResult<T> written = neoForge.encode(input, ops, prefix);
				return written.isError() ? fabric.encode(input, ops, prefix) : written;
			}

			@Override
			public String toString() {
				return "Forbric[" + FABRIC_KEY + " → " + fabric + ", otherwise " + neoForge + "]";
			}
		};
	}

	/** Whether {@code input} — a variant object, or a list of them — has {@code key} on it or on any entry. */
	static <T> boolean names(DynamicOps<T> ops, T input, String key) {
		if (has(ops, input, key)) return true;
		Optional<Stream<T>> entries = ops.getStream(input).result();
		return entries.isPresent() && entries.get().anyMatch(entry -> has(ops, entry, key));
	}

	private static <T> boolean has(DynamicOps<T> ops, T input, String key) {
		return ops.getMap(input).result().map(map -> map.get(key) != null).orElse(false);
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(FabricBlockStateCodecMixinAdapter.PROPERTY, "on"));
	}
}
