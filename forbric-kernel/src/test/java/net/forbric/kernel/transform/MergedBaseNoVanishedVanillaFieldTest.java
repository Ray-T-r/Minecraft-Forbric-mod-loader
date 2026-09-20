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

package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

/**
 * A whole-artifact detector for a vanilla field whose vanilla DESCRIPTOR the merged base no longer offers.
 *
 * <h2>Why this is not {@link MergedBaseNoUnwrittenDuplicateFieldTest}</h2>
 *
 * <p>That test covers the case where the merge keeps BOTH declarations and only one is written — the field is
 * there, and it is null. This is the neighbouring case and the more damaging one: when one ecosystem RE-TYPES a
 * vanilla field and the merge keeps only its declaration, vanilla's {@code (name, descriptor)} pair stops
 * existing. Nothing is null, because there is nothing. A mod compiled against vanilla gets
 * {@code NoSuchFieldError} at its {@code GETFIELD}/{@code PUTFIELD}, or — if it reaches the field through Mixin —
 * an {@code @Accessor} that cannot bind, which by default is a warning and a feature silently gone. The duplicate
 * test is blind to it by construction: the name is not duplicated.
 *
 * <p>It has cost a boot. {@code ChunkGenerator.featuresPerStep} became MinecraftForge's {@code ClearableLazy},
 * and fabric-api's biome API writes that field directly with vanilla's descriptor — so a dedicated server with
 * any Fabric biome modification installed did not start. That one is repaired now, which is why it is not in the
 * list below; the list is what is still true.
 *
 * <h2>Why a pinned list rather than zero</h2>
 *
 * <p>Because the answer today is not zero and pretending otherwise would mean deleting the test the first time it
 * went red. Each entry below is a real, still-unrepaired drift with the consequence stated, so the set is a
 * ledger rather than a suppression: a NEW drift fails this test, and repairing one of these fails it too, which
 * is the right time to delete its line.
 *
 * <p>The comparison is made AFTER the kernel's compat transformer has run, because that is what the game sees.
 */
class MergedBaseNoVanishedVanillaFieldTest {
	private static final Path MERGED_BASE =
			Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run", "merged-base",
					"patched-mc-merged-26.2.jar").normalize();

	/**
	 * Stock Minecraft, from the same local install the staged artifacts were derived from — the same lookup the
	 * other artifact-scanning tests use, so one {@code MC_DIR} covers them all.
	 */
	private static final Path VANILLA = vanillaJar();

	private static Path vanillaJar() {
		String env = System.getenv("MC_DIR");
		return Path.of(env != null && !env.isBlank()
				? env
				: System.getProperty("user.home") + "/Library/Application Support/minecraft")
				.resolve("versions/26.2/26.2.jar");
	}

	/** The ledger lives in main now, so FieldDriftAudit can name the readers at boot; keyed {@code owner#name:vanillaDescriptor}. */
	private static final Map<String, String> KNOWN = new LinkedHashMap<>();

	static {
		for (MergedBaseFieldDrift.Drift drift : MergedBaseFieldDrift.KNOWN) KNOWN.put(drift.key(), drift.cost());
	}

	@Test
	void noVanillaFieldDescriptorHasVanishedBeyondTheKnownSet() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent — skipping whole-artifact scan");
		assumeTrue(Files.isRegularFile(VANILLA),
				"stock 26.2 absent at " + VANILLA + " — set MC_DIR to a Minecraft install to run this scan");

		List<String> vanished = new ArrayList<>();
		int scanned = 0;
		try (ZipFile vanilla = new ZipFile(VANILLA.toFile());
				ZipFile merged = new ZipFile(MERGED_BASE.toFile())) {
			var entries = vanilla.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				String name = entry.getName();
				if (!name.endsWith(".class") || !name.startsWith("net/minecraft/")) continue;
				ZipEntry mergedEntry = merged.getEntry(name);
				if (mergedEntry == null) continue;          // a whole missing class is a different question
				scanned++;

				ClassNode before = read(vanilla, entry);
				ClassNode after = repaired(read(merged, mergedEntry), name);
				Set<String> present = new LinkedHashSet<>();
				for (FieldNode field : after.fields) {
					present.add(field.name + ":" + field.desc);
				}
				Set<String> names = new LinkedHashSet<>();
				for (FieldNode field : after.fields) {
					names.add(field.name);
				}
				for (FieldNode field : before.fields) {
					// A field DELETED outright is a different failure with a different repair; this test is about
					// the one that is still there under another type, because that one reads as present.
					if (!names.contains(field.name)) continue;
					if (present.contains(field.name + ":" + field.desc)) continue;
					vanished.add(before.name + "#" + field.name + ":" + field.desc);
				}
			}
		}

		assertTrue(scanned > 5000, "only " + scanned + " vanilla classes compared — the scan did not really run");
		List<String> unexpected = new ArrayList<>(vanished);
		unexpected.removeAll(KNOWN.keySet());
		assertEquals(List.of(), unexpected,
				"a vanilla field's own descriptor no longer exists in the merged base. A mod compiled against "
						+ "vanilla gets NoSuchFieldError there, or an @Accessor that cannot bind — which is a "
						+ "warning and a feature silently gone. Repair it the way ChunkGenerator.featuresPerStep "
						+ "was, or add it to KNOWN with what it costs");

		List<String> repaired = new ArrayList<>(KNOWN.keySet());
		repaired.removeAll(vanished);
		assertEquals(List.of(), repaired,
				"these are listed as unrepaired drifts and the merged base no longer has them. That is good news: "
						+ "delete their lines from KNOWN so the list keeps meaning what it says");
	}

	private static ClassNode read(ZipFile zip, ZipEntry entry) throws IOException {
		byte[] bytes;
		try (InputStream in = zip.getInputStream(entry)) {
			bytes = in.readAllBytes();
		}
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	/** What the game actually sees: the merged bytes after the kernel's own compat pass. */
	private static ClassNode repaired(ClassNode merged, String entryName) {
		org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
		merged.accept(writer);
		String binary = entryName.substring(0, entryName.length() - ".class".length()).replace('/', '.');
		byte[] out = new ForbricMergedBaseCompatTransformer().transform(binary, writer.toByteArray(), null);
		// …and the twin injector, which answers three of the drifts this census used to list as KNOWN.
		out = new WidenedFieldTwinInjector().transform(binary, out, null);
		ClassNode node = new ClassNode();
		new ClassReader(out).accept(node, 0);
		return node;
	}
}
