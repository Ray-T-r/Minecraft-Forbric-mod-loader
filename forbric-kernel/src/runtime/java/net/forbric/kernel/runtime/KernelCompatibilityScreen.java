/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.util.List;
import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.forbric.api.CompatibilityFinding;
import net.forbric.api.ModCatalog;
import net.forbric.kernel.ui.DialogLang;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;

/** Native Minecraft confirmation so late findings never fork Swing or exit the JVM. */
final class KernelCompatibilityScreen extends ConfirmScreen {
	private final BooleanConsumer answer;

	/** @param more how many further findings the next prompt will show; this screen's answer covers none of them */
	KernelCompatibilityScreen(List<CompatibilityFinding> findings, int more, BooleanConsumer answer) {
		super(answer, Component.literal(DialogLang.ofSystem().get("compat.title")), message(findings, more),
				Component.literal(DialogLang.ofSystem().get("compat.continuePlaying")),
				Component.literal(DialogLang.ofSystem().get("compat.returnTitle")));
		this.answer = answer;
	}

	@Override public void onClose() { answer.accept(false); }
	@Override protected void init() {
		super.init();
		setInitialFocus(noButton);
	}

	/** Every finding the answer covers is named; the caller pages anything beyond what fits. */
	static String text(List<CompatibilityFinding> findings, int more) {
		DialogLang lang = DialogLang.ofSystem();
		StringBuilder text = new StringBuilder(lang.get("compat.intro")).append("\n\n");
		for (CompatibilityFinding finding : findings) {
			String name = ModCatalog.everything().stream().filter(e -> e.modId().equals(finding.modId()))
					.map(ModCatalog.Entry::name).findFirst().orElse(finding.modId());
			text.append(name).append(": ").append(finding.feature()).append('\n');
		}
		if (more > 0) text.append(lang.get("compat.more", more)).append('\n');
		text.append('\n').append(lang.get("compat.reportDetails"));
		return text.toString();
	}

	private static Component message(List<CompatibilityFinding> findings, int more) {
		return Component.literal(text(findings, more));
	}
}
