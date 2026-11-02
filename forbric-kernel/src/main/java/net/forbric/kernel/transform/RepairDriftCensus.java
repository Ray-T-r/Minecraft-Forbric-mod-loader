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
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.NativeGameReferences;
import net.forbric.kernel.util.Caught;

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
 * <p>So: point this at a candidate game base and its carrier, and it replays the claim ledger against them.
 * A repair whose anchor moved reports as a MISS — by name, before the build is adopted, instead of as whatever
 * the mod that needed it does three weeks later.
 *
 * <p>It is a report and not an assertion. A candidate build is by definition not the one the tree is calibrated
 * against, so a miss here is information for a decision, not a regression.
 *
 * <h2>What of the launch's chain it replays</h2>
 *
 * <p>The compat transformer is not the first edit a class meets: {@code KernelBoot} registers
 * {@link VanillaMemberTwinInjector} before it, and several repairs wire up exactly the vanilla members the twins put
 * back (GuiRenderer's {@code pictureInPictureRenderers}, ParticleResources' int-keyed {@code providers}). Replayed
 * alone, those repairs decline on the very build the tree is calibrated against, for want of a member the launch
 * always has by then. So the replay runs the twins first, in KernelBoot's order, and then the repairs. It does NOT
 * replay the rest of the chain: the ACCESS phase and the other COREMOD transformers KernelBoot registers before the
 * compat transformer ({@code CustomPayloadInteropSeam}, {@code FabricItemContractTransformer}, ...) are left out, and
 * {@code ChainAnchorCensusStagedTest} is the census of every transformer's anchors over the full chain.
 *
 * <p>The twins read vanilla's bodies through a {@link NativeGameReferences} over the jars given here, not through the
 * launch's configured sources ({@link NativeGameReferences#over}): pass the vanilla-reference sidecar among them (the
 * file the installer stages beside the base), unless the candidate base embeds its own index. On the genuine base the
 * two answer alike; a candidate that embeds an index of its own is read from that index here. Without a reference the
 * replay says so, the twins that copy vanilla's bodies stand down, and a repair that stands on such a body declines by
 * name: the picture-in-picture bridge, on GuiRenderer.
 *
 * <h2>Hedges count</h2>
 *
 * <p>The twins' anchors are hedges, and the ledger files a declined hedge apart from its misses. Here a twin that
 * stands down is the case the tool exists for (a stale sidecar after a base bump: the vanilla members Fabric mods call
 * would be missing at launch), so every hedge that did not land is printed by name, and the all-clear is printed only
 * when every declared claim landed.
 */
public final class RepairDriftCensus {

	/** What a replay found. */
	public record Drift(int declared, int hit, List<String> declined, List<String> hedged, List<String> absent,
			boolean vanillaReference) {
		public Drift {
			declined = List.copyOf(declined);
			hedged = List.copyOf(hedged);
			absent = List.copyOf(absent);
		}

		/** The line a script greps: denominator first, as every census here does. */
		public String summary() {
			return "[Forbric/RepairDrift] " + declared + " claim(s) declared, " + hit + " landed, "
					+ declined.size() + " declined, " + absent.size() + " had no target in these jars, "
					+ hedged.size() + " hedge(s) stood down";
		}

		/** True only when every declared claim landed: a hedge that stood down is not a landing. */
		public boolean everyClaimLanded() {
			return hit == declared && declined.isEmpty() && hedged.isEmpty() && absent.isEmpty();
		}

		/** What {@link #main} prints: the summary, every claim that did not land by name, then the verdict. */
		public List<String> lines() {
			List<String> lines = new ArrayList<>();
			lines.add(summary());
			if (!vanillaReference) {
				lines.add("[Forbric/RepairDrift] no vanilla reference among these jars: the vanilla twins that copy"
						+ " vanilla's bodies stand down, and the repairs standing on those bodies read as declined");
			}
			for (String d : declined) lines.add("    DECLINED  " + d);
			for (String h : hedged) lines.add("    HEDGED    " + h);
			for (String a : absent) lines.add("    ABSENT    " + a);
			if (everyClaimLanded()) {
				lines.add("[Forbric/RepairDrift] every repair still lands — this build does not move any anchor.");
			}
			return lines;
		}
	}

	/** Where a vanilla-reference sidecar, or a base that embeds its own, keeps the FABRIC index the twins read. */
	static final String FABRIC_INDEX = "META-INF/forbric/native-reference/FABRIC/index.tsv";

	private RepairDriftCensus() {
	}

	/**
	 * Replays every fixed-target claim against {@code jars}.
	 *
	 * @param jars the candidate game base first, then its carrier and the vanilla-reference sidecar — the same set a
	 *             launch would stage
	 */
	public static Drift replay(List<Path> jars) throws IOException {
		for (Path jar : jars) {
			if (!Files.isRegularFile(jar)) throw new IOException("not a file: " + jar);
		}
		TransformChain chain = new TransformChain();
		// As KernelBoot registers them: the vanilla twins first, then the repairs that find the members they add.
		NativeGameReferences vanilla = new NativeGameReferences(path -> resourceOf(jars, path));
		chain.register(TransformPhase.COREMOD,
				new VanillaMemberTwinInjector(owner -> vanilla.getWithFrames(Ecosystem.FABRIC, owner)));
		chain.register(TransformPhase.COREMOD, new ForbricMergedBaseCompatTransformer());

		Map<String, byte[]> targets = new LinkedHashMap<>();
		for (String className : chain.ledger().watchedClasses()) {
			targets.computeIfAbsent(className, n -> bytesOf(jars, n));
		}
		TransformContext ctx = new TransformContext(EnvType.CLIENT, false, "intermediary");
		for (Map.Entry<String, byte[]> target : targets.entrySet()) {
			if (target.getValue() == null) continue; // reported as absent by the ledger
			chain.applyBeforeMixin(target.getKey(), target.getValue(), ctx);
		}

		AnchorLedger.Report report = chain.ledger().report();
		List<String> declined = new ArrayList<>();
		report.misses().forEach(m -> declined.add(String.valueOf(m)));
		List<String> hedged = new ArrayList<>();
		report.hedged().forEach(h -> hedged.add(String.valueOf(h)));
		List<String> absent = new ArrayList<>();
		report.absent().forEach(a -> absent.add(String.valueOf(a)));
		return new Drift(report.declared(), report.hit(), declined, hedged, absent,
				resourceOf(jars, FABRIC_INDEX) != null);
	}

	static byte[] bytesOf(List<Path> jars, String binaryName) {
		return resourceOf(jars, binaryName.replace('.', '/') + ".class");
	}

	/** The entry {@code entryName} from the first of {@code jars} that has it, as the game loader resolves it. */
	static byte[] resourceOf(List<Path> jars, String entryName) {
		for (Path jar : jars) {
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				ZipEntry entry = zip.getEntry(entryName);
				if (entry == null) continue;
				try (InputStream in = zip.getInputStream(entry)) {
					return in.readAllBytes();
				}
			} catch (IOException unreadable) {
				Caught.warn(unreadable, "[Forbric/RepairDrift] could not read %s looking for %s; what needs it counts as"
						+ " absent", jar, entryName);
				return null;
			}
		}
		return null;
	}

	/** {@code RepairDriftCensus <candidate-base.jar> [<carrier.jar> ...] [<vanilla-reference.jar>]} */
	public static void main(String[] args) throws IOException {
		if (args.length < 1) {
			System.err.println("usage: RepairDriftCensus <candidate-base.jar> [<carrier.jar> ...] [<vanilla-reference.jar>]");
			System.exit(2);
		}
		List<Path> jars = new ArrayList<>();
		for (String a : args) jars.add(Path.of(a));
		for (String line : replay(jars).lines()) System.out.println(line);
	}
}
