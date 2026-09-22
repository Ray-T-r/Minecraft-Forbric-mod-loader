/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Per-launch evidence ledger shared by boot code, the game UI and release checks. */
public final class CompatibilityFindings {
	private static final Map<String, CompatibilityFinding> FINDINGS = new LinkedHashMap<>();
	private static volatile long revision;

	private CompatibilityFindings() { }

	/** A later suspicion cannot erase a confirmed loss; repeated observations retain all evidence. */
	public static synchronized void record(CompatibilityFinding finding) {
		revision++;
		CompatibilityFinding previous = FINDINGS.get(finding.key());
		if (previous == null) {
			FINDINGS.put(finding.key(), finding);
			return;
		}
		CompatibilityFinding chosen = previous.confidence() != CompatibilityFinding.Confidence.SUSPECTED
				&& finding.confidence() == CompatibilityFinding.Confidence.SUSPECTED ? previous : finding;
		List<String> evidence = new ArrayList<>(previous.evidence());
		for (String item : finding.evidence()) if (!evidence.contains(item)) evidence.add(item);
		FINDINGS.put(finding.key(), new CompatibilityFinding(chosen.id(), chosen.modId(), chosen.feature(),
				chosen.source(), chosen.confidence(), chosen.required(), chosen.detail(), evidence));
	}

	/** A repair or a plugin declining its own mixin can discharge a previously reported contract. */
	public static synchronized void resolve(String id, String modId, String proof) {
		CompatibilityFinding previous = FINDINGS.get(modId + ":" + id);
		if (previous == null) return;
		record(new CompatibilityFinding(previous.id(), previous.modId(), previous.feature(), previous.source(),
				CompatibilityFinding.Confidence.RESOLVED, previous.required(), proof, List.of(proof)));
	}

	public static synchronized List<CompatibilityFinding> all() {
		return FINDINGS.values().stream().sorted(java.util.Comparator.comparing(CompatibilityFinding::key)).toList();
	}

	public static List<CompatibilityFinding> confirmedRequired() {
		return all().stream().filter(CompatibilityFinding::confirmedRequired).toList();
	}

	/** Display is a projection: resolving a finding removes only its own reason, not unrelated failures. */
	static List<ModCatalog.Entry> project(List<ModCatalog.Entry> entries) {
		List<CompatibilityFinding> confirmed = all().stream()
				.filter(f -> f.confidence() == CompatibilityFinding.Confidence.CONFIRMED).toList();
		if (confirmed.isEmpty()) return entries;
		List<ModCatalog.Entry> result = new ArrayList<>(entries.size());
		for (ModCatalog.Entry entry : entries) {
			List<String> reasons = new ArrayList<>();
			if (!entry.statusDetail().isEmpty()) reasons.add(entry.statusDetail());
			for (CompatibilityFinding f : confirmed) {
				if (entry.modId().equals(f.modId()) && !reasons.contains(f.detail())) reasons.add(f.detail());
			}
			boolean affected = confirmed.stream().anyMatch(f -> entry.modId().equals(f.modId()));
			result.add(affected ? entry.withStatus(entry.status() == ModCatalog.Status.FAILED
					? ModCatalog.Status.FAILED : ModCatalog.Status.DEGRADED, String.join("; ", reasons)) : entry);
		}
		return List.copyOf(result);
	}

	/** Called once at the beginning of a new loader session; tests use the same boundary. */
	public static synchronized void reset() {
		FINDINGS.clear();
		revision++;
	}

	/** Cheap notification for the client tick; unchanged evidence does not require another snapshot. */
	public static long revision() { return revision; }

	/** Stable machine report. Player acknowledgement deliberately does not change the release verdict. */
	public static String toJson() {
		List<CompatibilityFinding> findings = all();
		StringBuilder out = new StringBuilder("{\"schemaVersion\":1,\"confirmedRequired\":")
				.append(findings.stream().filter(CompatibilityFinding::confirmedRequired).count()).append(",\"findings\":[");
		for (int i = 0; i < findings.size(); i++) {
			CompatibilityFinding f = findings.get(i);
			if (i != 0) out.append(',');
			out.append("{\"id\":").append(json(f.id())).append(",\"modId\":").append(json(f.modId()))
					.append(",\"feature\":").append(json(f.feature())).append(",\"source\":").append(json(f.source()))
					.append(",\"confidence\":").append(json(f.confidence().name())).append(",\"required\":").append(f.required())
					.append(",\"detail\":").append(json(f.detail())).append(",\"evidence\":[");
			for (int j = 0; j < f.evidence().size(); j++) {
				if (j != 0) out.append(',');
				out.append(json(f.evidence().get(j)));
			}
			out.append("]}");
		}
		out.append("],\"catalogFailures\":[");
		List<ModCatalog.Entry> unclassified = ModCatalog.unclassifiedFailures();
		for (int i = 0; i < unclassified.size(); i++) {
			ModCatalog.Entry entry = unclassified.get(i);
			if (i != 0) out.append(',');
			out.append("{\"modId\":").append(json(entry.modId())).append(",\"name\":").append(json(entry.name()))
					.append(",\"status\":").append(json(entry.status().name())).append(",\"detail\":")
					.append(json(entry.statusDetail())).append(",\"classification\":\"UNCLASSIFIED\"}");
		}
		return out.append("]}\n").toString();
	}

	private static String json(String value) {
		StringBuilder out = new StringBuilder("\"");
		for (char ch : value.toCharArray()) {
			switch (ch) {
				case '\\' -> out.append("\\\\");
				case '"' -> out.append("\\\"");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> { if (ch < 32) out.append(String.format("\\u%04x", (int) ch)); else out.append(ch); }
			}
		}
		return out.append('"').toString();
	}
}
