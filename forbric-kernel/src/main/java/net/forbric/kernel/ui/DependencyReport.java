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

	/** A proven loss of a required feature. This section requires an explicit continue answer. */
	public record CompatibilityRow(String modId, String modName, String feature, String detail,
			String source, String evidence) { }

	private static final String COMPATIBILITY = "--compatibility-v1--";

	public static void writeCompatibility(Path file, List<CompatibilityRow> rows) throws IOException {
		StringBuilder out = new StringBuilder(COMPATIBILITY).append('\n');
		for (CompatibilityRow row : rows) {
			out.append(field(row.modId())).append('\t').append(field(row.modName())).append('\t')
					.append(field(row.feature())).append('\t').append(field(row.detail())).append('\t')
					.append(field(row.source())).append('\t').append(field(row.evidence())).append('\n');
		}
		Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
	}

	public static List<CompatibilityRow> readCompatibility(Path file) throws IOException {
		List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
		if (lines.isEmpty() || !COMPATIBILITY.equals(lines.get(0))) throw new IOException("missing compatibility header");
		List<CompatibilityRow> rows = new ArrayList<>();
		for (String line : lines.subList(1, lines.size())) {
			if (line.isBlank()) continue;
			String[] fields = line.split("\t", -1);
			if (fields.length != 6) throw new IOException("invalid compatibility row");
			rows.add(new CompatibilityRow(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5]));
		}
		return List.copyOf(rows);
	}

	/** Separates the two sections. A mod id can never be a bare double hyphen. */
	private static final String SECTION = "--";

	private DependencyReport() {
	}

	public static void write(Path file, List<Row> rows) throws IOException {
		write(file, rows, List.of());
	}

	public static void write(Path file, List<Row> rows, List<MixinRow> mixins) throws IOException {
		StringBuilder out = new StringBuilder();
		for (Row row : rows) {
			out.append(field(row.requiredBy())).append('\t')
					.append(field(row.requiredByName())).append('\t')
					.append(field(row.ecosystem())).append('\t')
					.append(field(row.requiredId())).append('\t')
					.append(field(row.requiredRange())).append('\t')
					.append(row.installedVersion() == null ? ABSENT : field(row.installedVersion()))
					.append('\n');
		}
		if (!mixins.isEmpty()) {
			out.append(SECTION).append('\n');
			for (MixinRow row : mixins) {
				out.append(field(row.owner())).append('\t')
						.append(field(row.mixin())).append('\t')
						.append(field(row.anchors())).append('\n');
			}
		}
		Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
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
			if (!inSection || line.isBlank()) continue;
			String[] parts = line.split("\t", -1);
			if (parts.length != 3) continue;
			rows.add(new MixinRow(parts[0], parts[1], parts[2]));
		}
		return rows;
	}

	/** Never null, never empty, never contains the separator — so a malformed row cannot be produced at all. */
	private static String field(String raw) {
		if (raw == null || raw.isBlank()) return "?";
		return raw.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
	}
}
