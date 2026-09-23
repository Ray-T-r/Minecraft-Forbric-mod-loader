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

package net.forbric.kernel.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * What the parent tells the dialog child, and how.
 *
 * <p>The two processes share only this file format — deliberately, because the child must not need the kernel's
 * boot state, a game classloader, or any library the parent happens to have. It is tab-separated because every
 * field in it is a mod id, a version or a version range, and none of those can contain a tab; a format that
 * cannot be written wrongly is worth more here than one that is general.
 *
 * <p>Fields, in order: {@code requiredBy}, {@code requiredByName}, {@code ecosystem}, {@code requiredId},
 * {@code requiredRange}, {@code installedVersion} ({@code -} when nothing provides the id at all).
 */
public final class DependencyReport {
	/** Stands in for "no version installed" — a real version can never be a bare hyphen. */
	private static final String ABSENT = "-";

	/**
	 * One unmet requirement, as both sides of the pipe see it.
	 *
	 * @param installedVersion {@code null} when nothing provides {@code requiredId}. That is the difference
	 *                         between telling the player to install a mod and telling them to change its version.
	 */
	public record Row(String requiredBy, String requiredByName, String ecosystem, String requiredId,
			String requiredRange, String installedVersion) {
		public boolean absent() {
			return installedVersion == null;
		}
	}

	/**
	 * A mixin that was written to attach to another mod and did not.
	 *
	 * @param owner   the mod whose mixin config this is
	 * @param mixin   the mixin class
	 * @param anchors the injection points that did not resolve
	 */
	public record MixinRow(String owner, String mixin, String anchors) {
	}

	/**
	 * A compatibility finding as the dialog shows it. In {@link Confirmation#required} it is a proven loss of a
	 * required feature and needs an explicit continue answer; in {@link Confirmation#suspected} it is a note.
	 */
	public record CompatibilityRow(String modId, String modName, String feature, String detail,
			String source, String evidence) { }

	/**
	 * Everything the ONE startup window shows when a required loss needs an answer.
	 *
	 * <p>Only {@code required} asks anything. The unmet dependencies and mixin breaks are the notice the old
	 * dependency dialog used to show in a window of its own -- folded in here, so a player who clicked through that
	 * window is not asked a second time about the same mods -- and {@code suspected} are details, never a question.
	 *
	 * @param coveredDeps unmet requirements that a {@code required} row already asks about. Kept for the suggestions
	 *                    and the details (what to install, where to search), left out of the summary so the same
	 *                    dependency is not listed twice
	 */
	public record Confirmation(List<CompatibilityRow> required, List<CompatibilityRow> suspected, List<Row> deps,
			List<Row> coveredDeps, List<MixinRow> mixins) {
		public Confirmation {
			required = required == null ? List.of() : List.copyOf(required);
			suspected = suspected == null ? List.of() : List.copyOf(suspected);
			deps = deps == null ? List.of() : List.copyOf(deps);
			coveredDeps = coveredDeps == null ? List.of() : List.copyOf(coveredDeps);
			mixins = mixins == null ? List.of() : List.copyOf(mixins);
		}

		/** What the log counts: every row the window lists, notes excluded. */
		public int findings() {
			return required.size() + deps.size() + mixins.size();
		}
	}

	private static final String COMPATIBILITY = "--compatibility-v1--";
	/** The confirmation file's later sections. None can be a row: a row always has tabs. */
	private static final String SUSPECTED = "--suspected--";
	private static final String DEPS = "--deps--";
	private static final String COVERED_DEPS = "--covered-deps--";
	private static final String MIXINS = "--mixins--";

	public static void writeCompatibility(Path file, List<CompatibilityRow> rows) throws IOException {
		writeConfirmation(file, new Confirmation(rows, List.of(), List.of(), List.of(), List.of()));
	}

	public static void writeConfirmation(Path file, Confirmation confirmation) throws IOException {
		StringBuilder out = new StringBuilder(COMPATIBILITY).append('\n');
		for (CompatibilityRow row : confirmation.required()) compatibility(out, row);
		out.append(SUSPECTED).append('\n');
		for (CompatibilityRow row : confirmation.suspected()) compatibility(out, row);
		out.append(DEPS).append('\n');
		for (Row row : confirmation.deps()) dependency(out, row);
		out.append(COVERED_DEPS).append('\n');
		for (Row row : confirmation.coveredDeps()) dependency(out, row);
		out.append(MIXINS).append('\n');
		for (MixinRow row : confirmation.mixins()) mixin(out, row);
		Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
	}

	public static List<CompatibilityRow> readCompatibility(Path file) throws IOException {
		return readConfirmation(file).required();
	}

	/**
	 * Strict, unlike the legacy reader: this file decides whether a launch may continue, so a row with the wrong
	 * shape or an unknown section is a report that cannot be trusted, and the child answers quit rather than
	 * approving whatever part of it happened to parse.
	 */
	public static Confirmation readConfirmation(Path file) throws IOException {
		List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
		if (lines.isEmpty() || !COMPATIBILITY.equals(lines.get(0))) throw new IOException("missing compatibility header");
		List<CompatibilityRow> required = new ArrayList<>();
		List<CompatibilityRow> suspected = new ArrayList<>();
		List<Row> deps = new ArrayList<>();
		List<Row> covered = new ArrayList<>();
		List<MixinRow> mixins = new ArrayList<>();
		String section = COMPATIBILITY;
		for (String line : lines.subList(1, lines.size())) {
			if (line.isBlank()) continue;
			if (!line.contains("\t")) {
				if (!List.of(SUSPECTED, DEPS, COVERED_DEPS, MIXINS).contains(line)) throw new IOException("invalid compatibility row");
				section = line;
				continue;
			}
			String[] fields = line.split("\t", -1);
			switch (section) {
				case COMPATIBILITY, SUSPECTED -> {
					if (fields.length != 6) throw new IOException("invalid compatibility row");
					(section.equals(COMPATIBILITY) ? required : suspected).add(new CompatibilityRow(fields[0], fields[1],
							fields[2], fields[3], fields[4], fields[5]));
				}
				case DEPS, COVERED_DEPS -> {
					if (fields.length != 6) throw new IOException("invalid dependency row");
					(section.equals(DEPS) ? deps : covered).add(new Row(fields[0], fields[1], fields[2], fields[3],
							fields[4], ABSENT.equals(fields[5]) ? null : fields[5]));
				}
				default -> {
					if (fields.length != 3) throw new IOException("invalid mixin row");
					mixins.add(new MixinRow(fields[0], fields[1], fields[2]));
				}
			}
		}
		return new Confirmation(required, suspected, deps, covered, mixins);
	}

	/** Separates the two sections. A mod id can never be a bare double hyphen. */
	private static final String SECTION = "--";

	private DependencyReport() {
	}

	public static void write(Path file, List<Row> rows) throws IOException {
		write(file, rows, List.of());
	}

	public static void write(Path file, List<Row> rows, List<MixinRow> mixins) throws IOException {
		write(file, rows, mixins, List.of());
	}

	/** @param suspected notes for the details pane; a third section, after the mixins, that never asks anything */
	public static void write(Path file, List<Row> rows, List<MixinRow> mixins, List<CompatibilityRow> suspected)
			throws IOException {
		StringBuilder out = new StringBuilder();
		for (Row row : rows) dependency(out, row);
		if (!mixins.isEmpty() || !suspected.isEmpty()) {
			out.append(SECTION).append('\n');
			for (MixinRow row : mixins) mixin(out, row);
		}
		if (!suspected.isEmpty()) {
			out.append(SUSPECTED).append('\n');
			for (CompatibilityRow row : suspected) compatibility(out, row);
		}
		Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
	}

	private static void dependency(StringBuilder out, Row row) {
		out.append(field(row.requiredBy())).append('\t')
				.append(field(row.requiredByName())).append('\t')
				.append(field(row.ecosystem())).append('\t')
				.append(field(row.requiredId())).append('\t')
				.append(field(row.requiredRange())).append('\t')
				.append(row.installedVersion() == null ? ABSENT : field(row.installedVersion()))
				.append('\n');
	}

	private static void mixin(StringBuilder out, MixinRow row) {
		out.append(field(row.owner())).append('\t')
				.append(field(row.mixin())).append('\t')
				.append(field(row.anchors())).append('\n');
	}

	private static void compatibility(StringBuilder out, CompatibilityRow row) {
		out.append(field(row.modId())).append('\t').append(field(row.modName())).append('\t')
				.append(field(row.feature())).append('\t').append(field(row.detail())).append('\t')
				.append(field(row.source())).append('\t').append(field(row.evidence())).append('\n');
	}

	public static List<Row> read(Path file) throws IOException {
		List<Row> rows = new ArrayList<>();
		for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
			if (line.isBlank()) continue;
			if (SECTION.equals(line)) break;
			// -1: keep trailing empties, so a row whose last field is blank still has six columns and is
			// rejected below rather than silently becoming a five-column row with everything shifted.
			String[] parts = line.split("\t", -1);
			if (parts.length != 6) continue;
			rows.add(new Row(parts[0], parts[1], parts[2], parts[3], parts[4],
					ABSENT.equals(parts[5]) ? null : parts[5]));
		}
		return rows;
	}

	public static List<MixinRow> readMixins(Path file) throws IOException {
		List<MixinRow> rows = new ArrayList<>();
		boolean inSection = false;
		for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
			if (SECTION.equals(line)) { inSection = true; continue; }
			if (SUSPECTED.equals(line)) break;
			if (!inSection || line.isBlank()) continue;
			String[] parts = line.split("\t", -1);
			if (parts.length != 3) continue;
			rows.add(new MixinRow(parts[0], parts[1], parts[2]));
		}
		return rows;
	}

	/** The legacy notice's third section. Lenient like the rest of that format: a malformed note is dropped. */
	public static List<CompatibilityRow> readSuspected(Path file) throws IOException {
		List<CompatibilityRow> rows = new ArrayList<>();
		boolean inSection = false;
		for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
			if (SUSPECTED.equals(line)) { inSection = true; continue; }
			if (!inSection || line.isBlank()) continue;
			String[] parts = line.split("\t", -1);
			if (parts.length != 6) continue;
			rows.add(new CompatibilityRow(parts[0], parts[1], parts[2], parts[3], parts[4], parts[5]));
		}
		return rows;
	}

	/** Never null, never empty, never contains the separator — so a malformed row cannot be produced at all. */
	private static String field(String raw) {
		if (raw == null || raw.isBlank()) return "?";
		return raw.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
	}
}
