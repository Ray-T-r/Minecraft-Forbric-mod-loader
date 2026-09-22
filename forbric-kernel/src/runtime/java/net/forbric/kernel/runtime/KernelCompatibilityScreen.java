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

	KernelCompatibilityScreen(List<CompatibilityFinding> findings, BooleanConsumer answer) {
		super(answer, Component.literal(DialogLang.ofSystem().get("compat.title")), message(findings),
				Component.literal(DialogLang.ofSystem().get("compat.continuePlaying")),
				Component.literal(DialogLang.ofSystem().get("compat.returnTitle")));
		this.answer = answer;
	}

	@Override public void onClose() { answer.accept(false); }
	@Override protected void init() {
		super.init();
		setInitialFocus(noButton);
	}

	private static Component message(List<CompatibilityFinding> findings) {
		StringBuilder text = new StringBuilder(DialogLang.ofSystem().get("compat.intro")).append("\n\n");
		for (CompatibilityFinding finding : findings.stream().limit(4).toList()) {
			String name = ModCatalog.everything().stream().filter(e -> e.modId().equals(finding.modId()))
					.map(ModCatalog.Entry::name).findFirst().orElse(finding.modId());
			text.append(name).append(": ").append(finding.feature()).append('\n');
		}
		text.append('\n').append(DialogLang.ofSystem().get("compat.reportDetails"));
		return Component.literal(text.toString());
	}
}
