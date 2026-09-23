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
	/**
	 * How many findings one prompt names. The rest wait for the next prompt rather than riding along unseen: a
	 * Continue acknowledges exactly what its screen listed, never a loss the player was not shown.
	 */
	static final int PAGE = 4;
	private static long observedRevision = -1;
	/** Refused for the world they were refused in. Joining another world asks again rather than bouncing. */
	private static final Set<String> DECLINED = new LinkedHashSet<>();
	private static Screen prompt;
	private static Screen previous;
	private static List<CompatibilityFinding> active = List.of();
	private static boolean stopping;

	private KernelCompatibilityPrompts() { }

	public static void tick(Minecraft minecraft) {
		if (stopping || !minecraft.isRunning() || minecraft.gui == null || minecraft.gui.overlay() != null) return;
		if (prompt != null) {
			if (minecraft.gui.screen() == prompt) return;
			// Another screen replacing the prompt -- a death screen, a kick, a mod's own menu -- is neither the
			// player's consent nor their refusal. The question is asked again, now, over whatever replaced it, and
			// the answer returns there.
			displaced();
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
		// A refusal left the world it was given in. A world joined later is a new question, and silently throwing
		// the player back to the title for the rest of the launch explained nothing and offered no way back.
		if (minecraft.level != null) askAgain();
		List<CompatibilityFinding> pending = CompatibilityDecision.drain().stream()
				.filter(f -> !DECLINED.contains(f.key())).toList();
		if (pending.isEmpty()) return;
		if (policy == CompatibilityDecision.Policy.STRICT) {
			stopNormally(minecraft, "strict policy rejected " + pending.size() + " confirmed required feature loss(es)");
			return;
		}
		List<CompatibilityFinding> shown = List.copyOf(pending.subList(0, Math.min(PAGE, pending.size())));
		// The rest stay queued for the prompt after this one; drain() emptied the queue.
		if (shown.size() < pending.size()) CompatibilityDecision.queue();
		previous = minecraft.gui.screen();
		active = shown;
		try {
			prompt = new KernelCompatibilityScreen(shown, pending.size() - shown.size(), continued -> answer(minecraft, continued));
			minecraft.gui.setScreen(prompt);
		} catch (RuntimeException | LinkageError unavailable) {
			prompt = null;
			stopNormally(minecraft, "confirmation could not be displayed; continuation was not approved");
		}
	}

	private static void askAgain() {
		boolean again = false;
		for (CompatibilityFinding f : CompatibilityFindings.confirmedRequired()) again |= DECLINED.remove(f.key());
		if (again) CompatibilityDecision.queue();
	}

	private static void displaced() {
		prompt = null;
		active = List.of();
		previous = null;
		CompatibilityDecision.queue();
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
			return;
		}
		// A refusal answers for what is still waiting as well: continuing needs every loss accepted, and none was.
		Set<String> refused = new LinkedHashSet<>();
		for (CompatibilityFinding f : answered) refused.add(f.key());
		for (CompatibilityFinding f : CompatibilityDecision.drain()) refused.add(f.key());
		DECLINED.addAll(refused);
		ForbricLog.warn("[Forbric/Compatibility] continuation was declined for %d required feature loss(es); %s",
				refused.size(), minecraft.level != null ? "saving and leaving this world" : "nothing to leave");
		if (minecraft.level != null) {
			minecraft.disconnectWithSavingScreen();
			minecraft.gui.setScreen(new TitleScreen());
		} else {
			// Not in a world: nothing to save, and whatever was on screen -- the title, or a disconnect screen still
			// carrying the server's reason -- stays.
			minecraft.gui.setScreen(restore == null ? new TitleScreen() : restore);
		}
	}

	private static void stopNormally(Minecraft minecraft, String reason) {
		stopping = true;
		// Recorded before the stop, so the launcher's boundary reports this policy stop as 78 once Main returns.
		CompatibilityDecision.recordPolicyStop();
		ForbricLog.error("[Forbric/Compatibility] FATAL: %s; saving and stopping normally", reason);
		if (minecraft.level != null) minecraft.disconnectWithSavingScreen();
		minecraft.stop();
	}
}
