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

package net.forbric.installer.kernel;

import java.nio.file.Path;
import java.util.Locale;

/**
 * The installer's sentences about WHICH mods Forbric loads, in English and Chinese.
 *
 * <p>Since Stage 3 of dropping MinecraftForge the kernel refuses a MinecraftForge-only mod (by name, in the player's
 * language, through the kernel's {@code DialogLang}), so the installer must stop telling the player that "Fabric,
 * MinecraftForge and NeoForge mods all go in" the mods folder: a player who believed it would put a MinecraftForge build
 * there and meet that refusal on the first launch. These are the two places the installer says it -- the window's title
 * and the last line of an install -- and both now name the two ecosystems the kernel loads.
 *
 * <p>Why not {@code DialogLang}: this module does not compile against the kernel (it builds the kernel jar as a
 * subprocess), so it cannot reach it. It follows the same rule for choosing the language -- Chinese for any {@code zh}
 * locale, English otherwise -- and {@code -Dforbric.dialogLanguage} overrides it the way it overrides the kernel's, so a
 * test or a support session can ask for either. The rest of the installer's window is English only, as it was; these
 * two sentences are bilingual because they are the ones this change rewrote, and the kernel's rule is that every
 * user-facing sentence it changes exists in both languages.
 */
final class InstallerText {
	private InstallerText() {
	}

	/** True for a Chinese locale, or when {@code -Dforbric.dialogLanguage} names one. */
	static boolean chinese() {
		String forced = System.getProperty("forbric.dialogLanguage", "").trim().toLowerCase(Locale.ROOT);
		if (!forced.isEmpty()) return forced.startsWith("zh");
		return "zh".equals(Locale.getDefault().getLanguage());
	}

	/** The installer window's one-line title. */
	static String title() {
		return chinese() ? "Forbric —— 在同一个游戏里运行 Fabric 和 NeoForge mod"
				: "Forbric — Fabric and NeoForge mods in one game";
	}

	/** The last line of an install: where the mods go, and which ones. */
	static String modsFolder(Path mods, String versionId) {
		return chinese()
				? "Fabric 和 NeoForge 的 mod 都放进 " + mods + "（启动器开启版本隔离时用 versions/" + versionId
						+ "/mods）。Forbric 不再加载 MinecraftForge 版的 mod，请改用它们的 NeoForge 或 Fabric 版本。"
				: "Fabric and NeoForge mods go in " + mods + " (a launcher with per-version isolation uses versions/"
						+ versionId + "/mods instead). Forbric no longer loads MinecraftForge mods: use their NeoForge or "
						+ "Fabric builds.";
	}
}
