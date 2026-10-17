/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * The one place a {@code -Dforbric.*} switch is looked up under a name it used to have.
 *
 * <p>A switch is part of the launch arguments a player or a pack already carries — copied from an issue reply, kept
 * in a launcher profile. When a switch is renamed, a reader that asks {@link System#getProperty} for the new name
 * alone ignores the old flag without a word: the repair the player turned off comes back on, and nothing in the log
 * says why. A switch whose mechanism is gone is the same trap seen from the other side: the flag still looks like it
 * does something.
 *
 * <p>So every renamed switch keeps its old name in {@link #RENAMED}, and the readers of the new name ask {@link #get}
 * instead of {@link System#getProperty}. When only the old name is set its value is used and a warning names the
 * switch to use instead; when both are set the current name wins and the warning says the old one was ignored. A
 * switch in {@link #RETIRED} has no reader at all, so {@link #announce} — called once as the kernel boots — is what
 * warns that it has no effect (and warns about any renamed one on the command line, whether or not its reader runs
 * in this session). Each old name warns once per run, whichever of the two reaches it first.
 */
public final class ForbricSwitches {
	/** Old name → the switch that now controls the same mechanism. */
	static final Map<String, String> RENAMED = Map.ofEntries(
			Map.entry("forbric.barrelRollCamera", "forbric.cameraRollCallbacks"),
			Map.entry("forbric.blockInfoCaches", "forbric.registryElementCallbacks"),
			Map.entry("forbric.c2meBlockUpdates", "forbric.chunkStatusRetarget"),
			Map.entry("forbric.carpetMixins", "forbric.playerWorldCallbacks"),
			Map.entry("forbric.compatPluginPlatforms", "forbric.mixinPluginPlatforms"),
			Map.entry("forbric.continuitySpriteSources", "forbric.spriteLoaderCallbacks"),
			Map.entry("forbric.corpseNameTag", "forbric.zeroNameTagMigration"),
			Map.entry("forbric.createContextualBlocks", "forbric.blockQueryAdapters"),
			Map.entry("forbric.createEntitySounds", "forbric.entitySoundCallbacks"),
			Map.entry("forbric.createHudMixin", "forbric.hudContextCallbacks"),
			Map.entry("forbric.createInjectionAdapters", "forbric.carrierCallbackAdapters"),
			Map.entry("forbric.createInteractionMixins", "forbric.blockInteractionAdapters"),
			Map.entry("forbric.createKeyboardMixin", "forbric.keyActionCallbacks"),
			Map.entry("forbric.createStructureMixin", "forbric.structurePlacementCallbacks"),
			// Both kept a config plugin's platform provider from defining game classes during Mixin's preparation; the
			// general mechanism that replaced them is the loader's.
			Map.entry("forbric.earlyGameDirectory", "forbric.deferLinkTimeTypes"),
			Map.entry("forbric.irisEarlyGamePath", "forbric.deferLinkTimeTypes"),
			Map.entry("forbric.portingLayerAbi", "forbric.configApiAbi"),
			Map.entry("forbric.sodiumConfigUsers", "forbric.crossEcosystemDeclarations"));

	/** Retired name → why setting it changes nothing. */
	static final Map<String, String> RETIRED = Map.ofEntries(
			Map.entry("forbric.dragonParts", "the multipart entity hierarchy is now proved by the merged base when it is built, "
					+ "so there is no runtime rewrite left to switch off"),
			Map.entry("forbric.fluidInteractionCallbacks", "a fluid callback on vanilla's spread decision is no longer moved onto the carriers' "
					+ "registry calls; the decision method is asked again where vanilla asks it (-Dforbric.nativeEntryRelay)"),
			Map.entry("forbric.createFluidMixins", "a fluid callback on vanilla's spread decision is no longer moved onto the carriers' "
					+ "registry calls; the decision method is asked again where vanilla asks it (-Dforbric.nativeEntryRelay)"),
			Map.entry("forbric.hudBridge", "fabric-api's HUD elements are no longer redrawn from NeoForge's layers: fabric-rendering-v1's "
					+ "own HudMixin dispatches every root, each wrap landing in the body the kernel gives the method reference"),
			Map.entry("forbric.fabricPlayChannels", "the kernel no longer records a client's Fabric PLAY channels itself: Fabric's own "
					+ "c:register receiver runs on the payload as Fabric reads it and records them (-Dforbric.commonNetworkInterop)"),
			Map.entry("forbric.fabricFluidFlow", "a fluid callback on vanilla's spread decision is no longer moved onto the carriers' "
					+ "registry calls; the decision method is asked again where vanilla asks it (-Dforbric.nativeEntryRelay)"),
			Map.entry("forbric.breathingCallbacks", "a mod's wrap of vanilla's breathing checks is no longer served by a kernel callback scope: "
					+ "the native checks are asked again ahead of the carrier's breathing hook (-Dforbric.nativeDecisionIsland)"),
			Map.entry("forbric.createBreathingMixin", "a mod's wrap of vanilla's breathing checks is no longer served by a kernel callback scope: "
					+ "the native checks are asked again ahead of the carrier's breathing hook (-Dforbric.nativeDecisionIsland)"),
			Map.entry("forbric.balmEffectVeto", "a clear-all veto is no longer re-hosted on the carrier's per-effect question: the native clear is put back on a view of "
					+ "what the carrier lets go and the mod's own wrap runs on it (-Dforbric.scopedClearRelay)"),
			Map.entry("forbric.effectClearVeto", "a clear-all veto is no longer re-hosted on the carrier's per-effect question: the native clear is put back on a view of "
					+ "what the carrier lets go and the mod's own wrap runs on it (-Dforbric.scopedClearRelay)"),
			Map.entry("forbric.fabricElytraTickAnchor", "a mod's cancellable injector behind a guard the carrier added is moved ahead of that guard by structure, not by name "
					+ "(-Dforbric.carrierGuardedPoints)"),
			Map.entry("forbric.fabricSoundContracts", "fabric-api's sound stream redirect is no longer neutralised and replaced by a rewrite of NeoForge's "
					+ "default: it moves onto the carrier's SoundInstance.getStream and runs that default with fabric-api's own handler wherever a "
					+ "sound inherits it (-Dforbric.carrierDefaultChains)"),
			Map.entry("forbric.fabricServerLanguage", "a mod's injector on a vanilla decision the carrier replaced is moved onto the carrier's decision by structure, not "
					+ "by name (-Dforbric.carrierDecisions)"),
			Map.entry("forbric.fabricBlockBreak", "fabric-api's AFTER break handler is no longer wrapped by name: an injector at a vanilla call the carrier makes "
					+ "in a helper that answers whether it made it runs on that answer, by structure (-Dforbric.carrierHelperResults)"),
			Map.entry("forbric.fabricEntityAnchors", "fabric-api's bed occupancy redirect is no longer replaced by a rewrite of its handler: it moves onto the state's "
					+ "setBedOccupied and runs NeoForge's inherited default with fabric-api's own handler wherever a bed inherits it "
					+ "(-Dforbric.carrierDefaultChains); a NeoForge bed that overrides the setter keeps its own and no longer asks Fabric's event"));

	private static final ForbricSwitches SYSTEM =
			new ForbricSwitches(RENAMED, RETIRED, System::getProperty, ForbricLog::warn);

	/** Current name → its old names, in a fixed order so two set old names always resolve the same way. */
	private final Map<String, List<String>> formerNames;
	private final Map<String, String> renamed;
	private final Map<String, String> retired;
	private final UnaryOperator<String> properties;
	private final Consumer<String> warn;
	private final Set<String> warned = ConcurrentHashMap.newKeySet();

	ForbricSwitches(Map<String, String> renamed, Map<String, String> retired, UnaryOperator<String> properties,
			Consumer<String> warn) {
		Map<String, List<String>> former = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : new TreeMap<>(renamed).entrySet()) {
			String old = entry.getKey(), current = entry.getValue();
			// A chain would point the warning at a name that is itself deprecated; a retired name cannot also live on.
			if (old.equals(current) || renamed.containsKey(current) || retired.containsKey(current) || retired.containsKey(old)) {
				throw new IllegalArgumentException("switch alias " + old + " -> " + current + " does not end at a live switch");
			}
			former.computeIfAbsent(current, k -> new ArrayList<>()).add(old);
		}
		former.replaceAll((current, olds) -> List.copyOf(olds));
		this.formerNames = Collections.unmodifiableMap(former);
		this.renamed = Map.copyOf(renamed);
		this.retired = Map.copyOf(retired);
		this.properties = properties;
		this.warn = warn;
	}

	/** The value of switch {@code name}, read under its current name or else under a name it used to have. */
	public static String get(String name, String fallback) {
		return SYSTEM.lookup(name, fallback);
	}

	/** {@link #get(String, String)} with no fallback: null when the switch is set under none of its names. */
	public static String get(String name) {
		return SYSTEM.lookup(name, null);
	}

	/** Warns about every renamed or retired switch on the command line. Called once, as the kernel boots. */
	public static void announce() {
		SYSTEM.announceSet();
	}

	/** Which spelling of a switch applies, and its value. */
	private record Setting(String name, String value) { }

	/** The setting that applies to {@code current}: the current name when set, else the first set old name. */
	private Setting applied(String current) {
		String value = properties.apply(current);
		if (value != null) return new Setting(current, value);
		for (String old : formerNames.getOrDefault(current, List.of())) {
			value = properties.apply(old);
			if (value != null) return new Setting(old, value);
		}
		return null;
	}

	String lookup(String name, String fallback) {
		List<String> olds = formerNames.get(name);
		if (olds == null) {
			String value = properties.apply(name);
			return value != null ? value : fallback;
		}
		Setting applied = applied(name);
		for (String old : olds) {
			String value = properties.apply(old);
			if (value != null) deprecated(old, name, value, applied);
		}
		return applied != null ? applied.value() : fallback;
	}

	void announceSet() {
		for (Map.Entry<String, String> entry : new TreeMap<>(renamed).entrySet()) {
			String value = properties.apply(entry.getKey());
			if (value != null) deprecated(entry.getKey(), entry.getValue(), value, applied(entry.getValue()));
		}
		for (Map.Entry<String, String> entry : new TreeMap<>(retired).entrySet()) {
			String value = properties.apply(entry.getKey());
			if (value != null && warned.add(entry.getKey())) {
				warn.accept(String.format("[Forbric/Switches] -D%s=%s has no effect: %s. Remove it from the launch "
						+ "arguments.", entry.getKey(), value, entry.getValue()));
			}
		}
	}

	private void deprecated(String old, String current, String value, Setting applied) {
		if (!warned.add(old)) return;
		if (applied.name().equals(old)) {
			warn.accept(String.format("[Forbric/Switches] -D%s is a renamed switch; its value (%s) is applied as -D%s. "
					+ "Use -D%s=%s in the launch arguments instead.", old, value, current, current, value));
		} else if (applied.name().equals(current)) {
			warn.accept(String.format("[Forbric/Switches] -D%s=%s is a renamed switch and is ignored: -D%s=%s is also "
					+ "set and takes precedence. Remove -D%s from the launch arguments.", old, value, current,
					applied.value(), old));
		} else {
			warn.accept(String.format("[Forbric/Switches] -D%s=%s is a renamed switch and is ignored: -D%s=%s, another "
					+ "old name of -D%s, is applied instead. Use -D%s alone in the launch arguments.", old, value,
					applied.name(), applied.value(), current, current));
		}
	}
}
