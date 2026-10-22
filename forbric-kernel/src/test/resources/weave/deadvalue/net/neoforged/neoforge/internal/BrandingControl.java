package net.neoforged.neoforge.internal;

import java.util.List;
import java.util.function.BiConsumer;

import net.minecraft.SharedConstants;

/** Fixture stand-in for the carrier's branding: its own "Minecraft " + version line, then its own line, drawn bottom-up. */
public final class BrandingControl {
	private BrandingControl() {
	}

	public static void forEachLine(boolean includeMC, boolean reverse, BiConsumer<Integer, String> consumer) {
		List<String> lines = List.of("Minecraft " + SharedConstants.versionName(), "Carrier 1.0 (3 mods)");
		if (reverse) lines = lines.reversed();
		for (int i = 0; i < lines.size(); i++) consumer.accept(i, lines.get(i));
	}

	public static void forEachAboveCopyrightLine(BiConsumer<Integer, String> consumer) {
		consumer.accept(0, "Minecraft 26.2");
	}
}
