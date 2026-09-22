/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;
import net.forbric.kernel.ui.CompatibilityDecision;
import net.forbric.kernel.util.ForbricLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

/** The only place late compatibility findings change client state: a normal render-thread tick. */
public final class KernelCompatibilityPrompts {
	private static long observedRevision = -1;
	private static final Set<String> DECLINED = new LinkedHashSet<>();
	private static Screen prompt;
	private static Screen previous;
	private static List<CompatibilityFinding> active = List.of();
	private static boolean stopping;

	private KernelCompatibilityPrompts() { }

	public static void tick(Minecraft minecraft) {
		if (stopping || !minecraft.isRunning() || minecraft.gui == null || minecraft.gui.overlay() != null) return;
		if (prompt != null) {
			// A different mod replacing the screen is not the player's consent.
			if (minecraft.gui.screen() != prompt) answer(minecraft, false);
			return;
		}
		long revision = CompatibilityFindings.revision();
		if (revision != observedRevision) {
			observedRevision = revision;
			CompatibilityDecision.queue();
		}
		CompatibilityDecision.Policy policy = CompatibilityDecision.policy();
		if (policy == CompatibilityDecision.Policy.CONTINUE) {
			CompatibilityDecision.acknowledge(CompatibilityDecision.drain());
			DECLINED.clear();
			return;
		}
		if (minecraft.level != null && CompatibilityFindings.confirmedRequired().stream()
				.anyMatch(f -> DECLINED.contains(f.key()))) {
			returnToTitle(minecraft);
			return;
		}
		List<CompatibilityFinding> pending = CompatibilityDecision.drain().stream()
				.filter(f -> !DECLINED.contains(f.key())).toList();
		if (pending.isEmpty()) return;
		if (policy == CompatibilityDecision.Policy.STRICT) {
			stopNormally(minecraft, "strict policy rejected " + pending.size() + " confirmed required feature loss(es)");
			return;
		}
		previous = minecraft.gui.screen();
		active = pending;
		try {
			prompt = new KernelCompatibilityScreen(pending, continued -> answer(minecraft, continued));
			minecraft.gui.setScreen(prompt);
		} catch (RuntimeException | LinkageError unavailable) {
			stopNormally(minecraft, "confirmation could not be displayed; continuation was not approved");
		}
	}

	private static void answer(Minecraft minecraft, boolean continued) {
		if (prompt == null) return;
		List<CompatibilityFinding> answered = active;
		Screen restore = previous;
		prompt = null;
		active = List.of();
		previous = null;
		if (continued) {
			CompatibilityDecision.acknowledge(answered);
			minecraft.gui.setScreen(restore);
		} else {
			for (CompatibilityFinding f : answered) DECLINED.add(f.key());
			returnToTitle(minecraft);
		}
	}

	private static void returnToTitle(Minecraft minecraft) {
		if (minecraft.level != null) minecraft.disconnectWithSavingScreen();
		minecraft.gui.setScreen(new TitleScreen());
	}

	private static void stopNormally(Minecraft minecraft, String reason) {
		stopping = true;
		ForbricLog.error("[Forbric/Compatibility] FATAL: %s; saving and stopping normally", reason);
		if (minecraft.level != null) minecraft.disconnectWithSavingScreen();
		minecraft.stop();
	}
}
