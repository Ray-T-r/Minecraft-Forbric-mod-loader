/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.List;

import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;

/** One identity from preflight through application; prose is evidence, never the identity. */
public final class MixinCompatibility {
	private static final java.util.Map<String, Boolean> ORIGINAL_REQUIRED = new java.util.concurrent.ConcurrentHashMap<>();
	private MixinCompatibility() { }

	/** Every new loader session re-reads its own original declarations. */
	public static void reset() { ORIGINAL_REQUIRED.clear(); FinalMixinApplications.reset(); SupersededMixins.reset(); }

	/** Keep the mod's declaration before Forbric relaxes required=true in the bytes handed to Mixin. */
	static void rememberOriginalConfig(String config, byte[] bytes) {
		try {
			var parsed = com.electronwill.nightconfig.json.JsonFormat.fancyInstance().createParser().parse(
					new java.io.StringReader(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)));
			ORIGINAL_REQUIRED.put(config, Boolean.TRUE.equals(parsed.get(java.util.List.of("required"))));
			FinalMixinApplications.config(config, parsed);
		} catch (RuntimeException invalid) {
			// Mixin reports an unreadable config; diagnostics must not prevent that report.
		}
	}

	static boolean required(String config, boolean current) {
		return config == null ? current : ORIGINAL_REQUIRED.getOrDefault(config, current);
	}

	static String id(String config, String mixin) {
		return "mixin:" + config + ":" + mixin;
	}

	static void record(String config, String mixin, String detail, CompatibilityFinding.Confidence confidence,
			boolean required, List<String> evidence) {
		String modId = config == null ? null : MixinConfigOwners.modIdOf(config);
		CompatibilityFindings.record(new CompatibilityFinding(id(config, mixin),
				modId == null ? "config:" + config : modId, "Mixin " + mixin, "mixin:" + config,
				confidence, required, detail, evidence));
	}

	static void resolve(String config, String mixin, String reason) {
		String modId = config == null ? null : MixinConfigOwners.modIdOf(config);
		CompatibilityFindings.resolve(id(config, mixin), modId == null ? "config:" + config : modId, reason);
	}
}
