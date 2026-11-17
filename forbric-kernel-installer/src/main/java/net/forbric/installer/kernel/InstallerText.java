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
 * user-facing sentence it changes exists in both languages. The clean-up's lines ({@link OldInstallCleanup}) came later
 * under the same rule.
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

	// --- the clean-up of an older install's files (OldInstallCleanup) ---------------------------------------------
	// Every line starts "[cleanup] " in both languages, the way the build steps' lines carry their tag, so a log can be
	// searched for them whatever language wrote it.

	/** One file removed. */
	static String cleanupRemoved(Path file) {
		return chinese() ? "[cleanup] 已删除 " + file + "（旧版 Forbric 留下的文件，已没有任何版本使用）"
				: "[cleanup] removed " + file + " (an older Forbric's file that no installed version uses)";
	}

	/** A link the clean-up met and left alone: a symbolic link or a junction, or a directory reached through one. */
	static String cleanupLeftLink(Path link) {
		return chinese() ? "[cleanup] 保留 " + link + "：它是链接（符号链接或目录联接），或经由链接到达；清理从不跟随或删除链接"
				: "[cleanup] left " + link + " in place: it is a link (a symbolic link or a junction), or is reached through"
						+ " one, which the clean-up never follows or removes";
	}

	/** A file that could not be deleted (on Windows, typically one a running game holds open). */
	static String cleanupFailed(Path file, String reason) {
		return chinese() ? "[cleanup] 无法删除 " + file + "（" + reason + "），已保留；安装照常完成"
				: "[cleanup] could not remove " + file + " (" + reason + "); it is left in place and the install carries on";
	}

	/** A version profile that could not be read, so the old libraries are all kept. */
	static String cleanupProfileUnreadable(Path json, String reason) {
		return chinese() ? "[cleanup] 无法读取 " + json + "（" + reason + "），不知道它用到哪些库，所以 libraries/net/forbric 下的旧文件全部保留"
				: "[cleanup] could not read " + json + " (" + reason + "), so what it uses is unknown and every old file in"
						+ " libraries/net/forbric is kept";
	}

	/** A JSON file beside a profile that does not parse: the game's or a mod's, not a profile, so it decides nothing. */
	static String cleanupSkippedJson(Path json, String reason) {
		return chinese() ? "[cleanup] 跳过 " + json + "（" + reason + "）：它不是版本配置文件（只有 versions/<id>/<id>.json 是），不影响保留哪些库"
				: "[cleanup] skipped " + json + " (" + reason + "): it is not a version profile (only versions/<id>/<id>.json"
						+ " is), so it does not decide which libraries are kept";
	}

	/** Something unexpected stopped the clean-up part-way: what it had not reached is left, and the install stands. */
	static String cleanupStopped(String reason) {
		return chinese() ? "[cleanup] 清理中途停止（" + reason + "）：还没处理到的旧文件全部保留；安装照常完成"
				: "[cleanup] the clean-up stopped part-way (" + reason + "): every old file it had not reached yet is left in"
						+ " place, and the install carries on";
	}

	/** The switch is off. */
	static String cleanupOff() {
		return chinese() ? "[cleanup] 已关闭（-D" + OldInstallCleanup.SWITCH + "=off）：旧版 Forbric 的文件全部保留"
				: "[cleanup] off (-D" + OldInstallCleanup.SWITCH + "=off): an older Forbric's files are left in place";
	}

	/** The last clean-up line: how much went, and how much could not. */
	static String cleanupSummary(int removed, long bytes, int failed) {
		String megabytes = String.format(Locale.ROOT, "%.1f", bytes / (1024.0 * 1024.0));
		if (chinese()) {
			return "[cleanup] 已删除旧版 Forbric 留下的 " + removed + " 个文件（" + megabytes + " MB）"
					+ (failed > 0 ? "；" + failed + " 个无法删除，已保留" : "");
		}
		return "[cleanup] removed " + removed + " file(s) an older Forbric left behind (" + megabytes + " MB)"
				+ (failed > 0 ? "; " + failed + " could not be removed and were left in place" : "");
	}
}
