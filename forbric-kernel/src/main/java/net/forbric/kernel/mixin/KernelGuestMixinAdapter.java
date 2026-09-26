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

package net.forbric.kernel.mixin;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.json.JsonFormat;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.ModCatalog;
import net.forbric.api.CompatibilityFinding;
import net.forbric.kernel.boot.ArbitratedAwayClasses;
import net.forbric.kernel.util.ForbricLog;

/**
 * Derives, per mixin config, which of its guest mixins must not apply to the merged base — the general form of
 * {@link MergedBaseMixinCompat#SUPPRESSED_MIXINS}'s hand-written entries.
 *
 * <p>A guest Fabric/Forge mixin is written against VANILLA bytecode. In the merged base, Forge or NeoForge may have
 * won the byte-merge of the class the mixin targets and restructured it — a field the mixin {@code @Shadow}s is
 * never assigned, an {@code @Inject} anchor moved, a param was re-typed. Generic erasure lets many such mixins APPLY
 * with no error and then misbehave at runtime (the archetype: {@code fabric-rendering-v1}'s {@code GuiRendererMixin}
 * reads {@code GuiRenderer.pictureInPictureRenderers}, which the NeoForge-won merge never assigns → NPE). Whether a
 * mixin applies cleanly is therefore not a usable signal.
 *
 * <p><b>Provenance is not a usable signal either.</b> This used to drop every mixin whose target matched a
 * hand-curated owned-class/package table. That was wrong at the root: the merged base IS NeoForge's patched
 * Minecraft ({@code MergedBaseBuilder} takes NeoForge as the base and splices Forge in — {@code forge=195
 * neo=10161 MERGED=611}), so "Forge/NeoForge owns this class" describes ~93% of the jar. Measured over the 163
 * suppressions that rule actually made, 108 targeted a class byte-identical to NeoForge's own jar, and restoring
 * them costs nothing. The table also could not see the failures that matter: fabric-block-api-v1 redirects
 * {@code BlockState.isAir()} inside {@code LevelChunkSection.setBlockState}, which the merged base calls as
 * {@code isEmpty()} — silently dead, on a class the table never listed.
 *
 * <p>So the question is asked directly instead: {@link MixinFit} resolves every anchor the mixin names — each
 * {@code @Shadow} member, each injector's target method, each {@code @At(target=…)} — against the merged target's
 * real bytecode, and reports whether they still exist. See {@link MixinFit.Result#shouldSuppress()} for why a
 * partially-resolving mixin is kept by default rather than dropped.
 *
 * <p>Two exemptions survive. PURE accessor/invoker mixins are always kept: they inject no behaviour, and other code
 * casts the target to the {@code @Accessor} interface they contribute. And whenever a mixin IS dropped, every mixin
 * depending on an interface it contributed is dropped with it — see {@link MixinFit#contributedInterfaces} — because
 * a lone drop converts the mod's {@code (Bar) foo} casts into {@code ClassCastException}s.
 *
 * <p>This runs at the point {@link ForbricMixinService} rewrites a config's JSON, so it needs no separate mod-jar
 * inventory: the config names its mixin package, and each mixin class is a game resource resolvable through the same
 * loader. Hand entries in {@link MergedBaseMixinCompat} stay authoritative for cases this cannot see (a runtime
 * break with no owned target); this removes the need to hand-list the owned-target ones.
 */
public final class KernelGuestMixinAdapter {

	/**
	 * Guest mixins kept although only some of their handlers bound, this boot.
	 *
	 * <p>Each one is already logged on its own line, and a 3-mod gate produces ninety of them on the client and
	 * thirty on the server — every boot, under a gate that reports green. Individually they are informational;
	 * as a number they are the thing {@code MixinFit}'s own javadoc calls worse than either extreme, because the
	 * mod keeps the handlers that bound and silently loses the rest. Ninety lines nobody totals is not a
	 * measurement, so this is the total.
	 *
	 * <p>Counted, not asserted on here: what a healthy number is depends on the mod set. The gate asserts.
	 */
	private static final java.util.Set<String> PARTIAL =
			java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());

	/** Records one, keyed so the same mixin evaluated twice counts once. */
	static void notePartial(String configName, String mixin) {
		if (configName == null || mixin == null) return;
		PARTIAL.add(configName + ":" + mixin);
	}

	/** Every guest mixin that applied only partially so far, sorted. */
	public static java.util.List<String> partiallyApplied() {
		synchronized (PARTIAL) {
			return PARTIAL.stream().sorted().toList();
		}
	}

	/** The one line a gate greps: the count, and what it costs. */
	public static String partialSummary() {
		return "[Forbric/Mixin] " + PARTIAL.size() + " guest mixin(s) apply only partially on the merged base — "
				+ "each keeps the handlers that bound and loses the rest, with no error at either end";
	}

	private static final String ACCESSOR_DESC = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
	private static final String INVOKER_DESC = "Lorg/spongepowered/asm/mixin/gen/Invoker;";

	/** {@code -Dforbric.guestMixinAdapter=off} turns the derived scan off (leaving only the hand list). */
	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty("forbric.guestMixinAdapter", "on"));
	}

	private KernelGuestMixinAdapter() {
	}

	/**
	 * The mixin entries in {@code configJson} (as they appear in its {@code mixins}/{@code client}/{@code server}
	 * arrays) that no longer fit the merged base, plus anything transitively broken by dropping them.
	 * {@code resource} resolves a resource path ({@code some/pkg/Name.class}) to its bytes, or null. Best-effort:
	 * any parse/scan failure on one entry skips that entry, never the config.
	 */
	public static List<String> unfitMixins(String configName, byte[] configJson, Function<String, byte[]> resource) {
		return unfitMixins(configName, configJson, resource, null);
	}

	/** {@code -Dforbric.mixinFitSided=off} judges every array on both sides again, as before. */
	static final String SIDED_PROPERTY = "forbric.mixinFitSided";

	/**
	 * The entries Mixin will actually prepare on {@code side}: {@code mixins}, plus {@code client} on the client or
	 * {@code server} on a dedicated server ({@code MixinConfig.prepare} switches on the environment side and never
	 * looks at the other array). {@code null} — no side known — is every array.
	 *
	 * <p>Judging the other side's array was not merely noise. A client-only mod's client mixin, unfit on the merged
	 * game, was recorded as a CONFIRMED required loss on a dedicated server, where Mixin would never have applied
	 * it, and the default STRICT policy stopped the server: fusion (connected textures) in a server's mods folder
	 * was enough. Same rule as {@code CandidateContractScanner}'s.
	 */
	static LinkedHashSet<String> appliedEntries(UnmodifiableConfig config, net.fabricmc.api.EnvType side) {
		LinkedHashSet<String> mixins = new LinkedHashSet<>();
		addMixinEntries(config.get(List.of("mixins")), mixins);
		boolean sided = side != null && !"off".equalsIgnoreCase(System.getProperty(SIDED_PROPERTY, "on"));
		if (!sided || side == net.fabricmc.api.EnvType.CLIENT) addMixinEntries(config.get(List.of("client")), mixins);
		if (!sided || side == net.fabricmc.api.EnvType.SERVER) addMixinEntries(config.get(List.of("server")), mixins);
		return mixins;
	}

	/**
	 * {@link #unfitMixins(String, byte[], Function)} for the entries Mixin prepares on {@code side} only; an entry of
	 * the other side's array is neither judged nor reported — nothing is lost where it never runs.
	 */
	public static List<String> unfitMixins(String configName, byte[] configJson, Function<String, byte[]> resource,
			net.fabricmc.api.EnvType side) {
		if (!enabled()) return List.of();

		UnmodifiableConfig config;
		try (Reader reader = new InputStreamReader(new ByteArrayInputStream(configJson), StandardCharsets.UTF_8)) {
			config = JsonFormat.fancyInstance().createParser().parse(reader);
		} catch (RuntimeException | java.io.IOException notAMixinConfig) {
			return List.of();
		}

		String pkg = asString(config.get(List.of("package")));
		if (pkg == null || pkg.isEmpty()) return List.of();

		LinkedHashSet<String> mixins = appliedEntries(config, side);
		if (mixins.isEmpty()) return List.of();

		String pkgPath = pkg.replace('.', '/');
		// A config that declares a plugin can have switched a mixin off itself, and the plugin is never asked
		// about an entry this method removes. PluginDeclinedMixins holds the attribution back to ask it later.
		String pluginClass = asString(config.get(List.of("plugin")));
		boolean required = Boolean.TRUE.equals(config.get(List.of("required")));
		Map<String, byte[]> loaded = new LinkedHashMap<>();
		List<String> suppress = new ArrayList<>();

		net.forbric.api.Ecosystem ecosystem = MixinConfigOwners.ecosystemOf(configName);
		for (String mixin : mixins) {
			// Which family's mod wrote it decides what shape it was compiled against (MixinStubRebind).
			MixinStubRebind.noteEcosystem(pkgPath + "/" + mixin.replace('.', '/'), ecosystem, configName);
			byte[] classBytes = resource.apply(pkgPath + "/" + mixin.replace('.', '/') + ".class");
			if (classBytes == null) continue;
			loaded.put(mixin, classBytes);
			try {
				if (reportTargetsArbitratedAway(configName, pkg, mixin, pluginClass, classBytes)) continue;
				if (isPureAccessorMixin(classBytes)) {
					// Never suppressed (the cast to its generated interface must keep working), but a member it
					// cannot bind is worth a line here: Mixin's own report is an InvalidAccessorException naming a
					// descriptor and nothing about which mod or why.
					MixinFit.Result accessors = MixinFit.evaluate(classBytes, resource,
							net.forbric.kernel.classloading.DelegationPolicy::alwaysGame);
					if (!accessors.unresolved().isEmpty()) {
						ForbricLog.info("[Forbric/Mixin] guest accessor mixin %s:%s cannot bind — %s (kept; the merge "
								+ "re-typed or removed the member, so the generated accessor will throw when called)",
								MixinConfigOwners.describe(configName), mixin, String.join(", ", accessors.unresolved()));
					}
					continue;
				}
				if (isExplicitlyKept(configName, mixin)) continue;

				// What the mixins Mixin applies first add to the same targets: a @Shadow of one of those members binds,
				// on Fabric and here (moreculling's shadow of the mesh field fabric-renderer-api adds).
				MixinAddedMembers.View added = MixinAddedMembers.before(configName, mixin, resource);
				MixinFit.Result fit = MixinFit.evaluate(classBytes, resource,
						net.forbric.kernel.classloading.DelegationPolicy::alwaysGame, added);
				if (!fit.shouldSuppress()) {
					if (!fit.foreign().isEmpty()) {
						// A DIFFERENT thing from the line below, and the reason the two are separated. An anchor
						// that misses on a merged-base class is routine -- 1226 such anchors across every gate log
						// in this repo, on runs that pass. An anchor that misses on ANOTHER MOD's class is not:
						// across those same 1226 there is not one. It means two mods that were built to fit each
						// other no longer do, and the failure that follows names neither of them. Iris 1.11.2
						// beside Sodium 0.9.2-beta.1 is the worked example: its @Redirect wanted a call to
						// RenderRegion.clearAllCachedBatches inside RenderRegionManager.uploadResults, that Sodium
						// stopped making the call, and the client died a render frame later on "Unsupported
						// stride: 36".
						//
						// Says "did not attach", not "will crash". Whether it crashes is not something this layer
						// can establish -- it knows an anchor did not resolve and nothing more.
						//
						// Three records, because three readers need it. The finding stays SUSPECTED: application
						// has not been observed, and only an observed loss may ask the player to continue or quit.
						// ForeignMixinBreaks is the dependency dialog's non-blocking mixin section -- the details a
						// suspicion belongs in -- and the row is what the Mods screen shows. Without the last two
						// the one pointer from "Unsupported stride" back to the pair of mods is gone.
						ForbricLog.warn("[Forbric/Mixin] %s:%s has unresolved preflight anchors on ANOTHER MOD — %s. "
								+ "Both mods are installed and each is within the version range the other declares, "
								+ "so nothing else will report this; one of them probably needs a different version. "
								+ "This is a suspected mismatch; actual application has not been observed yet.",
								MixinConfigOwners.describe(configName), mixin, String.join(", ", fit.foreign()));
						ForeignMixinBreaks.record(configName, mixin, fit.foreign());
						attribute(configName, "its mixin " + mixin + " targets another mod's class that has changed ("
								+ String.join(", ", fit.foreign()) + ")");
						preflight(configName, pkg, mixin, pluginClass, classBytes, required,
								"preflight could not resolve this mixin's anchors on another mod", fit.foreign());
					} else if (fit.verdict() == MixinFit.Verdict.PARTIAL) {
						// Before reporting a PARTIAL, ask whether it is one the merge MADE: an injector bound by
						// explicit descriptor to a merge-added delegating stub whose body moved. If rebinding it to
						// the delegate makes the mixin fit, remember the plan; Mixin receives the rewritten
						// annotation from the bytecode provider.
						MixinRetarget.Plan plan = MixinRetarget.plan(MixinFit.parse(classBytes), resource);
						MixinFit.Result after = plan.isEmpty() ? null : MixinFit.evaluate(
								MixinRetarget.rewritten(classBytes, plan), resource,
								net.forbric.kernel.classloading.DelegationPolicy::alwaysGame, added);
						if (after != null && after.unresolved().size() < fit.unresolved().size()) {
							MixinRetarget.remember(plan);
							ForbricLog.info("[Forbric/Mixin] retargeted guest mixin %s:%s — %s; verdict %s→%s",
									MixinConfigOwners.describe(configName), mixin, plan.describe(), fit.verdict(),
									after.verdict());
							if (after.verdict() == MixinFit.Verdict.PARTIAL) {
								notePartial(configName, mixin);
								suspectDrift(configName, pkg, mixin, pluginClass, classBytes, required, after);
								preflight(configName, pkg, mixin, pluginClass, classBytes, required, after.reason(), after.unresolved());
								ForbricLog.info("[Forbric/Mixin] guest mixin %s:%s still applies only partially — %s",
										MixinConfigOwners.describe(configName), mixin, after.reason());
							}
							continue;
						}
						String drifted = driftedTarget(fit);
						if (drifted != null) {
							ForbricLog.warn("[Forbric/Mixin] guest mixin %s:%s targets %s, a renumbered anonymous class — on this "
									+ "base that name is a different class (%s); its injections bind to unrelated code",
									MixinConfigOwners.describe(configName), mixin, drifted, MergedBaseAnonymousDrift.describe(drifted));
						}
						suspectDrift(configName, pkg, mixin, pluginClass, classBytes, required, fit);
						notePartial(configName, mixin);
						preflight(configName, pkg, mixin, pluginClass, classBytes, required, fit.reason(), fit.unresolved());
						ForbricLog.info("[Forbric/Mixin] guest mixin %s:%s applies only partially on the merged base "
								+ "— %s (kept; -Dforbric.mixinFit=strict drops these)", MixinConfigOwners.describe(configName), mixin,
								fit.reason());
					}
					continue;
				}
				// UNFIT means "no anchor resolves against the merged base", which the adapter reads as dead weight.
				// It is not dead weight when the anchor belongs to ANOTHER mod: a cross-mod compatibility mixin
				// targets a member that mod's own mixin adds at runtime. See ForeignMixinTargets for the two Physics
				// Mod cases (Sodium's SpriteCoordinateExpander.transform, Iris' VertexFormat.bindAttributesIris) that
				// this rule was silently deleting. Keeping it is cheap — a relaxed injector soft-skips if the member
				// really is absent — while dropping it removes a working feature with no error anywhere.
				if (fit.verdict() == MixinFit.Verdict.UNFIT && ForeignMixinTargets.claimedByAnotherConfig(
						configName, MixinFit.mixinTargets(MixinFit.parse(classBytes)), resource)) {
					ForbricLog.info("[Forbric/Mixin] keeping guest mixin %s:%s — %s, but another loaded mod's mixin "
							+ "targets the same class, so the missing member is that mod's to add (cross-mod "
							+ "compatibility layer, not dead weight)", MixinConfigOwners.describe(configName), mixin, fit.reason());
					continue;
				}
				suppress.add(mixin);
				ForbricLog.info("[Forbric/Mixin] auto-suppressing guest mixin %s:%s — %s on the merged base (%s)",
						MixinConfigOwners.describe(configName), mixin, fit.verdict(), fit.reason());
				report(MixinCompatibility.id(configName, pkg + "." + mixin), configName, pkg, mixin, pluginClass, classBytes,
						"guest mixin " + mixin + " did not fit the merged game and was left out",
						CompatibilityFinding.Confidence.CONFIRMED, required, List.of(fit.reason(), "kernel suppressed this mixin"));
			} catch (RuntimeException perMixin) {
				ForbricLog.debug("[Forbric/Mixin] could not scan guest mixin %s:%s — %s", MixinConfigOwners.describe(configName), mixin,
						String.valueOf(perMixin));
			}
		}

		closeOverPinnedContracts(configName, pkg, pluginClass, loaded, suppress, resource);
		closeOverCastContracts(configName, pkg, pluginClass, loaded, suppress);
		if (!suppress.isEmpty()) {
			ForbricLog.info("[Forbric/Mixin] %s: left out %d of %d mixin(s)", MixinConfigOwners.describe(configName),
					suppress.size(), loaded.size());
		}
		return suppress;
	}

	/** A bytecode preflight cannot know which targets, plugins or preceding transforms will actually run. */
	private static void preflight(String config, String pkg, String mixin, String plugin, byte[] bytes,
			boolean required, String detail, List<String> evidence) {
		report(MixinCompatibility.id(config, pkg + "." + mixin), config, pkg, mixin, plugin, bytes, detail,
				CompatibilityFinding.Confidence.SUSPECTED, required, evidence);
	}

	/**
	 * A renumbered anonymous {@code @Mixin} target, on a row of its own.
	 *
	 * <p>On the whole-mixin row it was discharged by the very evidence it is about: the final class is checked for
	 * references to each merged handler, and on a drifted target every handler binds cleanly — to the unrelated
	 * class that carries vanilla's name here. Attachment proves nothing about WHICH class, so this row stays
	 * SUSPECTED unless the mod's own plugin declines the mixin.
	 */
	private static void suspectDrift(String config, String pkg, String mixin, String plugin, byte[] bytes,
			boolean required, MixinFit.Result fit) {
		String drifted = driftedTarget(fit);
		if (drifted == null) return;
		List<String> evidence = fit.unresolved().stream().filter(r -> r.startsWith("@Mixin target ")).toList();
		report(MixinCompatibility.driftId(config, pkg + "." + mixin), config, pkg, mixin, plugin, bytes,
				"its mixin " + mixin + " targets " + drifted.replace('/', '.') + ", a renumbered anonymous class; on this "
						+ "base that name is a different class, so its injections may bind to unrelated code",
				CompatibilityFinding.Confidence.SUSPECTED, required, evidence);
	}

	/** Holds the row back for the mod's config plugin when it has one, and records it now when it does not. */
	private static void report(String id, String config, String pkg, String mixin, String plugin, byte[] bytes,
			String detail, CompatibilityFinding.Confidence confidence, boolean required, List<String> evidence) {
		report(id, config, pkg, mixin, plugin, dottedTargets(bytes), detail, confidence, required, evidence);
	}

	private static void report(String id, String config, String pkg, String mixin, String plugin, List<String> targets,
			String detail, CompatibilityFinding.Confidence confidence, boolean required, List<String> evidence) {
		if (!PluginDeclinedMixins.defer(id, config, plugin, mixin, pkg + "." + mixin, targets, detail,
				confidence, required, evidence)) {
			MixinCompatibility.recordAs(id, config, pkg + "." + mixin, detail, confidence, required, evidence);
		}
	}

	/**
	 * Puts the mixins the kernel leaves out BY NAME in the finding ledger: {@link MergedBaseMixinCompat}'s measured
	 * hand list, the pruner's whole-mixin fallback and {@code -Dforbric.suppressMixins}. Until now each was one log
	 * line, so the report said nothing about a mixin that never runs.
	 *
	 * <p>CONFIRMED, because the entry is gone from the config before Mixin reads it. Not a necessary loss on the
	 * prompt's terms: every entry is a measured decision the kernel ships, or the player's own switch, and a
	 * continue-or-quit question on every launch could change neither. The mod's own declaration is kept in the
	 * evidence, and a config plugin that would not have applied the mixin still clears it.
	 *
	 * @param sources mixin entry → where the suppression came from, as the report should name it
	 */
	static void reportNamedSuppressions(String configName, byte[] configJson, Map<String, String> sources,
			Function<String, byte[]> resource) {
		reportNamedSuppressions(configName, configJson, sources, resource, null);
	}

	/** As above, for a named entry Mixin prepares on {@code side}: one of the other side's array loses nothing here. */
	static void reportNamedSuppressions(String configName, byte[] configJson, Map<String, String> sources,
			Function<String, byte[]> resource, net.fabricmc.api.EnvType side) {
		if (sources.isEmpty()) return;
		UnmodifiableConfig config;
		try (Reader reader = new InputStreamReader(new ByteArrayInputStream(configJson), StandardCharsets.UTF_8)) {
			config = JsonFormat.fancyInstance().createParser().parse(reader);
		} catch (RuntimeException | java.io.IOException notAMixinConfig) {
			return;
		}
		String pkg = asString(config.get(List.of("package")));
		if (pkg == null || pkg.isEmpty()) return;
		String pluginClass = asString(config.get(List.of("plugin")));
		boolean required = Boolean.TRUE.equals(config.get(List.of("required")));
		java.util.Set<String> applied = appliedEntries(config, side);
		for (Map.Entry<String, String> e : sources.entrySet()) {
			String mixin = e.getKey();
			if (side != null && !applied.contains(mixin)) continue;
			List<String> targets = List.of();
			try {
				byte[] classBytes = resource.apply(pkg.replace('.', '/') + "/" + mixin.replace('.', '/') + ".class");
				if (classBytes != null) targets = dottedTargets(classBytes);
			} catch (RuntimeException unreadable) {
				// No targets means no question for the plugin, which records the row: the removal is certain.
			}
			report(MixinCompatibility.id(configName, pkg + "." + mixin), configName, pkg, mixin, pluginClass, targets,
					"the kernel leaves out its mixin " + mixin + " on the merged game",
					CompatibilityFinding.Confidence.CONFIRMED, false,
					List.of("kernel suppressed this mixin by name", "source=" + e.getValue(), "config required=" + required));
		}
	}

	/** Every {@code @Mixin} target, dotted, in declaration order — the names Mixin asks a config plugin about. */
	private static List<String> dottedTargets(byte[] mixinBytes) {
		return MixinFit.mixinTargets(MixinFit.parse(mixinBytes)).stream().map(t -> t.replace('/', '.')).toList();
	}

	/**
	 * Names a guest mixin whose target class exists only in the build of a duplicated mod the kernel did not load.
	 *
	 * <p>Such a mixin is INERT and silent: Mixin never applies a mixin whose target does not load, and that is not
	 * an error anywhere, so nothing reports it. It is not harmless when the mixin's job is to implant a duck-type
	 * interface — the code that casts to that interface is in another class, it still runs, and it throws
	 * {@code ClassCastException} whenever it is first reached. Iris' {@code MixinFluidRendererImpl} against
	 * sodium's FABRIC {@code FluidRendererImpl} is the worked example: a clean boot, every mod loaded, and a crash
	 * on the first chunk of water the moment shaders went on.
	 *
	 * <p>Only the arbitrated case is reported. A mixin targeting a class from a mod that is simply not installed
	 * is ordinary — that is what a compat mixin is — and {@link ForeignMixinTargets} already covers the case where
	 * another loaded mod contributes the member. What makes this one the kernel's to name is that the class is
	 * missing because the kernel chose between two builds, and the remedy is the kernel's own lever.
	 *
	 * <p>Membership of {@link ArbitratedAwayClasses} is the whole test, and deliberately not "the class does not
	 * resolve as a resource". The losing jar stays readable on the owned classpath — measured: the live boot
	 * still served {@code sodium.fabric.render.FluidRendererImpl}'s bytes while the loaded sodium was the
	 * NeoForge build, so a resource check was silent on the one case this was written for. The registry already
	 * means "only the build that was NOT loaded has this", which is exactly the condition.
	 *
	 * <p>CONFIRMED, since the target never loads, but not a necessary loss by the mod's own contract: native Mixin
	 * only warns about a missing target class, whatever the config's {@code required} says. Whether the build
	 * choice itself was acceptable is the arbitration's finding, not this one.
	 *
	 * @return true when this mixin was reported, so the caller skips the anchor scan that cannot say anything
	 */
	private static boolean reportTargetsArbitratedAway(String configName, String pkg, String mixin, String pluginClass,
			byte[] classBytes) {
		if (!ArbitratedAwayClasses.warningEnabled()) return false;
		for (String target : MixinFit.mixinTargets(MixinFit.parse(classBytes))) {
			ArbitratedAwayClasses.Loss loss = ArbitratedAwayClasses.lost(target.replace('/', '.'));
			if (loss == null) continue;

			String modId = MixinConfigOwners.modIdOf(configName);
			ForbricLog.warn("[Forbric/Mixin] %s:%s cannot apply: %s. Nothing reports a mixin whose target never "
					+ "loads, so this one is silently inert — and if it implants an interface, whatever casts to "
					+ "that interface throws ClassCastException the first time it runs. Pick the other build with "
					+ "`%s = %s` in forbric-mods.txt, or install %s's %s build.",
					MixinConfigOwners.describe(configName), mixin, loss.describe(target.replace('/', '.')),
					loss.modId(), loss.loser().toString().toLowerCase(java.util.Locale.ROOT),
					modId == null ? "that mod" : modId,
					loss.winner() == null ? "matching" : loss.winner().toString());
			report(MixinCompatibility.id(configName, pkg + "." + mixin), configName, pkg, mixin, pluginClass, classBytes,
					"its mixin " + mixin + " targets " + target.replace('/', '.') + ", which only " + loss.modId()
							+ "'s " + loss.loser() + " build has, and this instance loaded the other one",
					CompatibilityFinding.Confidence.CONFIRMED, false,
					List.of("target " + target.replace('/', '.') + " never loads: " + loss.describe(target.replace('/', '.')),
							"arbitrated away with " + loss.modId() + "'s " + loss.loser() + " build"));
			return true;
		}
		return false;
	}

	/**
	 * Puts what happened on the owning mod's row — the Mods screen and load-report.txt both read
	 * {@link ModCatalog} — when the config has exactly one owner. A config nobody or more than one mod claims
	 * marks nobody: a confidently wrong name is worse than none. Only for what is not a finding of its own; a
	 * CONFIRMED finding reaches the row through the catalogue's projection.
	 */
	private static void attribute(String configName, String detail) {
		String modId = MixinConfigOwners.modIdOf(configName);
		if (modId != null) ModCatalog.mark(modId, ModCatalog.Status.DEGRADED, detail);
	}

	/** {@code -Dforbric.pinnedContracts=off} keeps a mixin that relies on a pinned mixin's interface; its calls then throw. */
	static final String PINNED_CONTRACTS_PROPERTY = "forbric.pinnedContracts";

	/**
	 * Leaves out every mixin of this config that relies on an interface only a {@link MergedBaseMixinCompat} pin
	 * implements, while that pin is in force and the target does not stand behind the interface by itself. See
	 * {@link MergedBaseMixinCompat.PinnedContract}: the pin is another mod's, dropped by name before this config is
	 * read, so {@link #closeOverCastContracts} never sees it.
	 *
	 * <p>The worked case is owo-lib's {@code MixinCreativeModeInventoryScreenMixin} with the creative pager bridge
	 * switched off: kept, it called the interface default at the tail of every {@code selectTab} and the creative
	 * inventory threw {@code AssertionError} on opening. CONFIRMED, since the kernel removes it; not necessary on its
	 * own account — the pin's row carries that.
	 */
	private static void closeOverPinnedContracts(String configName, String pkg, String pluginClass,
			Map<String, byte[]> loaded, List<String> suppress, Function<String, byte[]> resource) {
		if ("off".equalsIgnoreCase(System.getProperty(PINNED_CONTRACTS_PROPERTY, "on"))) return;
		for (MergedBaseMixinCompat.PinnedContract row : MergedBaseMixinCompat.PINNED_CONTRACTS) {
			List<String> unsupplied = null; // judged at the first mixin that relies on it: almost no config has one
			for (Map.Entry<String, byte[]> e : loaded.entrySet()) {
				String mixin = e.getKey();
				if (suppress.contains(mixin) || row.pin().equals(configName + ":" + mixin)) continue;
				try {
					if (!MixinFit.referencesAny(MixinFit.parse(e.getValue()), Set.of(row.contract()))) continue;
				} catch (RuntimeException unreadable) {
					continue;
				}
				if (unsupplied == null) unsupplied = pinInForce(row.pin()) ? unsuppliedOnTarget(row, resource) : List.of();
				if (unsupplied.isEmpty()) break;
				suppress.add(mixin);
				String contract = row.contract().substring(row.contract().lastIndexOf('/') + 1);
				ForbricLog.info("[Forbric/Mixin] auto-suppressing guest mixin %s:%s — it relies on %s, which only the pinned "
						+ "%s implements, and %s does not implement %s itself: kept, its calls would throw AssertionError",
						MixinConfigOwners.describe(configName), mixin, contract, row.pin(),
						row.target().substring(row.target().lastIndexOf('/') + 1), String.join(", ", unsupplied));
				report(MixinCompatibility.id(configName, pkg + "." + mixin), configName, pkg, mixin, pluginClass, e.getValue(),
						"guest mixin " + mixin + " was left out: it relies on " + contract + ", which nothing implements "
								+ "on the merged game",
						CompatibilityFinding.Confidence.CONFIRMED, false,
						List.of("it implements, casts to or calls through " + row.contract(),
								"only " + row.pin() + " implements it, and the kernel leaves that mixin out",
								row.target().replace('/', '.') + " lacks " + String.join(", ", unsupplied)));
			}
		}
	}

	/** Whether the named pin is being left out on this boot — not switched off, not kept by {@code -Dforbric.keepMixins}. */
	private static boolean pinInForce(String pin) {
		int colon = pin.indexOf(':');
		return ForbricMixinService.suppressedMixinsFor(pin.substring(0, colon)).contains(pin.substring(colon + 1));
	}

	/** The target's missing interface methods, as Mixin will see the target; nothing to judge when either is absent. */
	private static List<String> unsuppliedOnTarget(MergedBaseMixinCompat.PinnedContract row, Function<String, byte[]> resource) {
		byte[] target = resource.apply(row.target() + ".class");
		byte[] contract = resource.apply(row.contract() + ".class");
		if (target == null || contract == null) return List.of();
		return MixinFit.unsupplied(MixinFit.parse(contract), MixinFit.parse(target));
	}

	/**
	 * Drops every mixin that depends on a duck-type interface a dropped mixin was contributing, until nothing new is
	 * dropped.
	 *
	 * <p>Without this, suppressing one half of a cast contract is worse than suppressing neither. Verified live:
	 * {@code fabric-rendering-v1}'s {@code GuiRendererMixin implements GuiRendererExtensions} and is dropped as a
	 * HAZARD (it {@code @Shadow}s the orphaned {@code pictureInPictureRenderers}); its sibling
	 * {@code GameRendererMixin} does {@code checkcast GuiRendererExtensions} and resolves cleanly, so it would be
	 * kept — and would then throw {@code ClassCastException} on a path that works today.
	 *
	 * <p>Each one is CONFIRMED — the kernel removed it — but not necessary on its own account: it goes only because
	 * the kernel dropped a sibling, and kept alone it would throw. The sibling's own row carries the necessity.
	 */
	private static void closeOverCastContracts(String configName, String pkg, String pluginClass, Map<String, byte[]> loaded,
			List<String> suppress) {
		for (int round = 0; round < 8; round++) {
			Set<String> contracts = new LinkedHashSet<>();
			for (String dropped : suppress) {
				byte[] bytes = loaded.get(dropped);
				if (bytes != null) contracts.addAll(MixinFit.contributedInterfaces(MixinFit.parse(bytes)));
			}
			if (contracts.isEmpty()) return;

			List<String> added = new ArrayList<>();
			for (Map.Entry<String, byte[]> e : loaded.entrySet()) {
				if (suppress.contains(e.getKey())) continue;
				try {
					if (!MixinFit.referencesAny(MixinFit.parse(e.getValue()), contracts)) continue;
				} catch (RuntimeException unreadable) {
					continue;
				}
				added.add(e.getKey());
				ForbricLog.info("[Forbric/Mixin] auto-suppressing guest mixin %s:%s — it casts the target to an "
						+ "interface a suppressed sibling contributes, which would ClassCastException",
						configName, e.getKey());
				report(MixinCompatibility.id(configName, pkg + "." + e.getKey()), configName, pkg, e.getKey(), pluginClass,
						e.getValue(), "guest mixin " + e.getKey() + " was left out with the sibling whose interface it casts to",
						CompatibilityFinding.Confidence.CONFIRMED, false,
						List.of("kernel suppressed this mixin with " + String.join(", ", suppress),
								"it casts to an interface one of them contributes: " + String.join(", ", contracts)));
			}
			if (added.isEmpty()) return;
			suppress.addAll(added);
		}
	}

	/** The drifted anonymous target a PARTIAL verdict names, or null. */
	private static String driftedTarget(MixinFit.Result fit) {
		for (String reason : fit.unresolved()) {
			if (!reason.startsWith("@Mixin target ")) continue;
			for (String name : MergedBaseAnonymousDrift.RELOCATED.keySet()) if (reason.contains(name.substring(name.lastIndexOf('/') + 1) + " is not")) return name;
			for (String name : MergedBaseAnonymousDrift.RESHAPED) if (reason.contains(name.substring(name.lastIndexOf('/') + 1) + " is not")) return name;
		}
		return null;
	}

	/**
	 * A mixin whose only members are {@code @Accessor}/{@code @Invoker} methods and which declares no fields. Such a
	 * mixin injects no behaviour; keeping it registered lets code that casts the target to its generated interface
	 * keep working, so it is never suppressed.
	 */
	private static boolean isPureAccessorMixin(byte[] classBytes) {
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		if (node.fields != null && !node.fields.isEmpty()) return false;

		boolean sawAccessor = false;
		if (node.methods != null) {
			for (MethodNode m : node.methods) {
				if (m.name.startsWith("<")) continue;
				if ((m.access & Opcodes.ACC_SYNTHETIC) != 0) continue;
				if (!hasAnnotation(m.visibleAnnotations, ACCESSOR_DESC) && !hasAnnotation(m.invisibleAnnotations, ACCESSOR_DESC)
						&& !hasAnnotation(m.visibleAnnotations, INVOKER_DESC)
						&& !hasAnnotation(m.invisibleAnnotations, INVOKER_DESC)) {
					return false;
				}
				sawAccessor = true;
			}
		}
		return sawAccessor;
	}

	/**
	 * Whether {@code configName:mixin} is on the never-auto-suppress list — {@link MergedBaseMixinCompat#KEPT_MIXINS}
	 * plus anything named by {@code -Dforbric.keepMixins} (csv of {@code <config>:<MixinEntry>}).
	 *
	 * <p>These are mixins that target an owned class but ALSO contribute a duck-type interface the mod casts the
	 * target to, so suppressing them converts a dropped feature into a {@code ClassCastException}. See
	 * {@link MergedBaseMixinCompat#KEPT_MIXINS} for why this is a measured hand list and not the obvious
	 * "keep everything that implements an interface" rule.
	 */
	private static boolean isExplicitlyKept(String configName, String mixin) {
		String entry = configName + ":" + mixin;
		if (MergedBaseMixinCompat.enabled() && MergedBaseMixinCompat.KEPT_MIXINS.contains(entry)) return true;

		String csv = System.getProperty("forbric.keepMixins");
		if (csv == null || csv.isEmpty()) return false;

		for (String raw : csv.split(",")) {
			if (entry.equals(raw.trim())) return true;
		}
		return false;
	}

	private static boolean hasAnnotation(List<AnnotationNode> annotations, String desc) {
		if (annotations == null) return false;
		for (AnnotationNode a : annotations) {
			if (desc.equals(a.desc)) return true;
		}
		return false;
	}

	private static void addMixinEntries(Object value, Set<String> out) {
		if (!(value instanceof List<?> list)) return;
		for (Object element : list) {
			if (element instanceof String s && !s.isBlank()) out.add(s.trim());
		}
	}

	private static String asString(Object value) {
		return value == null ? null : value.toString();
	}
}
