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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.fabricmc.api.EnvType;

/**
 * Which of the compat transformer's repairs would stop applying against a DIFFERENT build of the game.
 *
 * <h2>Why this is a question worth asking</h2>
 *
 * <p>Every repair is calibrated against one pair of carrier versions. That has already bitten: 26.2.0.38-beta →
 * .88 gave {@code RegistryDataLoader.load} a fifth parameter, so fabric-api's {@code WorldLoaderMixin} stopped
 * replacing the list, and every datapack registry a Fabric mod declared silently vanished. "Fixed" has a
 * half-life, and nothing in this tree measured it: the staged test proves the repairs land on the base that is
 * here, which says nothing about the base that is coming.
 *
 * <p>So: point this at a candidate merged base and its carriers, and it replays the claim ledger against them.
 * A repair whose anchor moved reports as a MISS — by name, before the build is adopted, instead of as whatever
 * the mod that needed it does three weeks later.
 *
 * <p>It is a report and not an assertion. A candidate build is by definition not the one the tree is calibrated
 * against, so a miss here is information for a decision, not a regression.
 */
public final class RepairDriftCensus {

	/** What a replay found. */
	public record Drift(int declared, int hit, List<String> declined, List<String> absent) {
		public Drift {
			declined = List.copyOf(declined);
			absent = List.copyOf(absent);
		}

		/** The line a script greps: denominator first, as every census here does. */
		public String summary() {
			return "[Forbric/RepairDrift] " + declared + " claim(s) declared, " + hit + " landed, "
					+ declined.size() + " declined, " + absent.size() + " had no target in these jars";
		}
	}

	private RepairDriftCensus() {
	}

	/**
	 * Replays every fixed-target claim against {@code jars}.
	 *
	 * @param jars the candidate merged base first, then its carriers — the same set a launch would stage
	 */
	public static Drift replay(List<Path> jars) throws IOException {
		for (Path jar : jars) {
			if (!Files.isRegularFile(jar)) throw new IOException("not a file: " + jar);
		}
		TransformChain chain = new TransformChain();
		ForbricMergedBaseCompatTransformer compat =
				new ForbricMergedBaseCompatTransformer(name -> bytesOf(jars, name));
		chain.register(TransformPhase.COREMOD, compat);

		Map<String, byte[]> targets = new LinkedHashMap<>();
		for (ClassTransformer.Claim claim : compat.claims()) {
			for (AnchorSet.Anchor anchor : claim.anchors().anchors()) {
				targets.computeIfAbsent(anchor.binaryName(), n -> bytesOf(jars, n));
			}
		}
		TransformContext ctx = new TransformContext(EnvType.CLIENT, false, "intermediary");
		for (Map.Entry<String, byte[]> target : targets.entrySet()) {
			if (target.getValue() == null) continue; // reported as absent by the ledger
			chain.applyBeforeMixin(target.getKey(), target.getValue(), ctx);
		}

		AnchorLedger.Report report = chain.ledger().report();
		List<String> declined = new ArrayList<>();
		report.misses().forEach(m -> declined.add(String.valueOf(m)));
		List<String> absent = new ArrayList<>();
		report.absent().forEach(a -> absent.add(String.valueOf(a)));
		return new Drift(report.declared(), report.hit(), declined, absent);
	}

	static byte[] bytesOf(List<Path> jars, String binaryName) {
		String entryName = binaryName.replace('.', '/') + ".class";
		for (Path jar : jars) {
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				ZipEntry entry = zip.getEntry(entryName);
				if (entry == null) continue;
				try (InputStream in = zip.getInputStream(entry)) {
					return in.readAllBytes();
				}
			} catch (IOException unreadable) {
				return null;
			}
		}
		return null;
	}

	/** {@code RepairDriftCensus <candidate-merged-base.jar> [<carrier.jar> ...]} */
	public static void main(String[] args) throws IOException {
		if (args.length < 1) {
			System.err.println("usage: RepairDriftCensus <candidate-merged-base.jar> [<carrier.jar> ...]");
			System.exit(2);
		}
		List<Path> jars = new ArrayList<>();
		for (String a : args) jars.add(Path.of(a));
		Drift drift = replay(jars);
		System.out.println(drift.summary());
		for (String d : drift.declined()) System.out.println("    DECLINED  " + d);
		for (String a : drift.absent()) System.out.println("    ABSENT    " + a);
		if (drift.declined().isEmpty() && drift.absent().isEmpty()) {
			System.out.println("[Forbric/RepairDrift] every repair still lands — this build does not move any anchor.");
		}
	}
}
