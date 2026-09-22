/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.api;

import java.util.List;
import java.util.Objects;

/** Evidence about one compatibility contract, independent of whether the player chose to continue. */
public record CompatibilityFinding(String id, String modId, String feature, String source,
		Confidence confidence, boolean required, String detail, List<String> evidence) {
	/** RESOLVED is retained as evidence, but never marks a mod or blocks a release. */
	public enum Confidence { SUSPECTED, CONFIRMED, RESOLVED }

	public CompatibilityFinding {
		id = nonBlank(id, "id");
		modId = nonBlank(modId, "modId");
		feature = nonBlank(feature, "feature");
		source = nonBlank(source, "source");
		confidence = Objects.requireNonNull(confidence, "confidence");
		detail = nonBlank(detail, "detail");
		evidence = evidence == null ? List.of() : evidence.stream().filter(Objects::nonNull).distinct().toList();
	}

	public boolean confirmedRequired() {
		return confidence == Confidence.CONFIRMED && required;
	}

	/** Identity is stable across repeated checks and does not depend on translated prose. */
	public String key() {
		return modId + ":" + id;
	}

	private static String nonBlank(String value, String name) {
		if (value == null || value.isBlank()) throw new IllegalArgumentException(name);
		return value;
	}
}
