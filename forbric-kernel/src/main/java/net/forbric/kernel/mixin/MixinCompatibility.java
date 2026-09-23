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
	public static void reset() { ORIGINAL_REQUIRED.clear(); FinalMixinApplications.reset(); }

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

	/**
	 * A mixin whose {@code @Mixin} target is a renumbered anonymous class. A separate identity from {@link #id}
	 * because final attachment answers the whole-mixin suspicion and cannot answer this one: the handlers bind to
	 * whatever class carries that name here, so every one of them attaching is exactly what the drift looks like.
	 */
	static String driftId(String config, String mixin) {
		return "mixin-target-drift:" + config + ":" + mixin;
	}

	static void record(String config, String mixin, String detail, CompatibilityFinding.Confidence confidence,
			boolean required, List<String> evidence) {
		recordAs(id(config, mixin), config, mixin, detail, confidence, required, evidence);
	}

	static void recordAs(String id, String config, String mixin, String detail, CompatibilityFinding.Confidence confidence,
			boolean required, List<String> evidence) {
		CompatibilityFindings.record(new CompatibilityFinding(id, owner(config), "Mixin " + mixin, "mixin:" + config,
				confidence, required, detail, evidence));
	}

	static void resolve(String config, String mixin, String reason) {
		resolveAs(id(config, mixin), config, reason);
	}

	static void resolveAs(String id, String config, String reason) {
		CompatibilityFindings.resolve(id, owner(config), reason);
	}

	/**
	 * One injector method the kernel removed from a guest mixin before Mixin read it. CONFIRMED — it never runs —
	 * and not necessary on the prompt's terms, like every other measured kernel removal; the residual loss belongs
	 * in {@code detail}.
	 */
	public static void recordRemovedInjector(String config, String mixin, String name, String desc, String detail,
			List<String> evidence) {
		CompatibilityFindings.record(new CompatibilityFinding("mixin-injector:" + config + ":" + mixin + "#" + name + desc,
				owner(config), "Mixin injection " + name, "mixin:" + config, CompatibilityFinding.Confidence.CONFIRMED,
				false, detail, evidence));
	}

	private static String owner(String config) {
		String modId = config == null ? null : MixinConfigOwners.modIdOf(config);
		return modId == null ? "config:" + config : modId;
	}
}
