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

package net.forbric.kernel.ui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The words of {@link DependencyDialogMain}, in the player's own language.
 *
 * <h2>Why the strings are Java and not a resource bundle</h2>
 *
 * <p>Because of how the dialog child is launched. {@link DependencyDialog} runs it with
 * {@code -cp <the code source of DependencyDialog>} — the ONE entry the child needs. In a shipped build that code
 * source is {@code forbric-kernel.jar}, which carries classes and resources together, and a bundle would be
 * found. Under Gradle it is {@code build/classes/java/main}, and processed resources live in the sibling
 * {@code build/resources/main} — a directory that is NOT on the child's classpath. A {@code .properties} table
 * would therefore load in production and silently fall back to English in the one test that drives the real fork,
 * which is the test that exists to prove the child works. Compiled constants cannot have that gap: if the class
 * loads at all, its strings are there.
 *
 * <h2>Fallback</h2>
 *
 * <p>English is the base and every other table is checked against it — {@link #missingKeys()} is what a test
 * asserts on, so a key added here in English and forgotten in Japanese is a failing build rather than an English
 * sentence in the middle of a Japanese dialog. At runtime a missing key still falls back to English, because a
 * dialog that throws is a dialog the player never sees.
 *
 * <h2>Substitution</h2>
 *
 * <p>{@code {0}}…{@code {9}}, substituted by {@link #get}. Deliberately NOT {@code MessageFormat}: its patterns
 * treat {@code '} as an escape, so "Biomes O' Plenty" in a French or English pattern would quietly eat the rest
 * of the sentence. Nothing here needs plural or number formatting that would justify that hazard.
 */
public final class DialogLang {
	/** {@code -Dforbric.dialogLanguage=ja} forces one, for a developer or a gate. Unset: the system language. */
	public static final String SWITCH = "forbric.dialogLanguage";

	private final String tag;
	private final Map<String, String> strings;

	private DialogLang(String tag, Map<String, String> strings) {
		this.tag = tag;
		this.strings = strings;
	}

	/** The language tag this table is filed under, e.g. {@code zh_cn}. */
	public String tag() {
		return tag;
	}

	/**
	 * The string for {@code key}, with {@code {0}}… replaced by {@code args}.
	 *
	 * <p>Falls back to English, then to the key itself. A dialog whose job is to explain a problem must not be
	 * able to become a second problem.
	 */
	public String get(String key, Object... args) {
		String raw = strings.get(key);
		if (raw == null) raw = EN.strings.get(key);
		if (raw == null) return key;
		return substitute(raw, args);
	}

	/**
	 * One pass over the pattern, never over what an argument put there.
	 *
	 * <p>The obvious loop — {@code replace("{0}", a).replace("{1}", b)} — rescans the string it has already
	 * rewritten, so an argument whose own text contains a later placeholder has that placeholder filled in.
	 * Mod display names come out of a third party's manifest and are not ours to trust: a mod calling itself
	 * "Cool {3} Mod" would be shown to the player under a name no jar in their folder carries, which is the one
	 * identifier this dialog exists to hand them. Scanning the pattern once and emitting arguments as literals
	 * makes that unreachable rather than unlikely.
	 */
	/**
	 * One pass over the pattern, never over what an argument put there.
	 *
	 * <p>The obvious loop — {@code replace("{0}", a).replace("{1}", b)} — rescans the string it has already
	 * rewritten, so an argument whose own text contains a later placeholder has that placeholder filled in. Mod
	 * display names come out of a third party's manifest and are not ours to trust: a mod calling itself
	 * "Cool {3} Mod" would be shown to the player under a name no jar in their folder carries, which is the one
	 * identifier this dialog exists to hand them. Scanning the pattern once and emitting arguments as literals
	 * makes that unreachable rather than unlikely.
	 */
	static String substitute(String raw, Object... args) {
		if (args == null || args.length == 0 || raw.indexOf('{') < 0) return raw;
		StringBuilder out = new StringBuilder(raw.length() + 32);
		for (int i = 0; i < raw.length(); i++) {
			char c = raw.charAt(i);
			if (c == '{' && i + 2 < raw.length() && raw.charAt(i + 2) == '}'
					&& Character.isDigit(raw.charAt(i + 1))) {
				int index = raw.charAt(i + 1) - '0';
				if (index < args.length) {
					out.append(args[index] == null ? "?" : args[index].toString());
					i += 2;
					continue;
				}
			}
			out.append(c);
		}
		return out.toString();
	}

	/** Every table, English first. A test walks this. */
	public static List<DialogLang> all() {
		return List.of(EN, ZH_CN, ZH_TW, JA, KO, RU, DE, FR, ES, PT_BR);
	}

	/** The keys English has and this table does not. Empty is the only acceptable answer; see the class note. */
	public List<String> missingKeys() {
		List<String> missing = new java.util.ArrayList<>();
		for (String key : EN.strings.keySet()) {
			if (!strings.containsKey(key)) missing.add(key);
		}
		return missing;
	}

	/** The keys this table has that English does not — a typo in a key is otherwise invisible. */
	public List<String> strayKeys() {
		List<String> stray = new java.util.ArrayList<>();
		for (String key : strings.keySet()) {
			if (!EN.strings.containsKey(key)) stray.add(key);
		}
		return stray;
	}

	/** The raw value, with no English fallback — so a test can tell "translated" from "fell through". */
	String raw(String key) {
		return strings.get(key);
	}

	/** Every key this table declares, in declaration order. English's is the canonical set. */
	List<String> keys() {
		return List.copyOf(strings.keySet());
	}

	/**
	 * The table for this run: {@link #SWITCH} if it names one, otherwise the system language, otherwise English.
	 *
	 * <p>Read in the CHILD process. {@link DependencyDialog} forwards the property when the parent has one set,
	 * because a child JVM does not inherit its parent's {@code -D} flags — only the OS locale, which is the
	 * common case and needs no forwarding at all.
	 */
	public static DialogLang ofSystem() {
		String forced = System.getProperty(SWITCH);
		if (forced != null && !forced.isBlank()) {
			DialogLang named = byTag(forced.trim().toLowerCase(Locale.ROOT).replace('-', '_'));
			if (named != null) return named;
		}
		return of(Locale.getDefault());
	}

	/**
	 * The closest table to {@code locale}.
	 *
	 * <p>Region matters for exactly one language here — a Traditional-Chinese reader handed Simplified text is
	 * being handed the wrong language, not a dialect of their own — so {@code zh} splits on region and everything
	 * else resolves on the language alone. Portuguese resolves to the Brazilian table from any region, because
	 * one Portuguese is better than English for a Portuguese reader and pt-BR is the one that exists.
	 */
	public static DialogLang of(Locale locale) {
		if (locale == null) return EN;
		String language = locale.getLanguage().toLowerCase(Locale.ROOT);
		if ("zh".equals(language)) {
			String region = locale.getCountry() == null ? "" : locale.getCountry().toUpperCase(Locale.ROOT);
			String script = locale.getScript() == null ? "" : locale.getScript();
			if ("Hant".equalsIgnoreCase(script)) return ZH_TW;
			return switch (region) {
				case "TW", "HK", "MO" -> ZH_TW;
				default -> ZH_CN;
			};
		}
		DialogLang byLanguage = byTag(language);
		if (byLanguage != null) return byLanguage;
		return switch (language) {
			case "pt" -> PT_BR;
			default -> EN;
		};
	}

	private static DialogLang byTag(String tag) {
		for (DialogLang lang : all()) {
			if (lang.tag.equals(tag)) return lang;
		}
		// "pt" for pt_br, "zh" handled above. A bare language that prefixes exactly one table resolves to it.
		DialogLang only = null;
		for (DialogLang lang : all()) {
			if (lang.tag.startsWith(tag + "_")) {
				if (only != null) return null;
				only = lang;
			}
		}
		return only;
	}

	private static Map<String, String> table(String... pairs) {
		Map<String, String> map = new LinkedHashMap<>();
		for (int i = 0; i + 1 < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
		return map;
	}

	// ---------------------------------------------------------------------------------------------------------
	// English. The base: every other table is diffed against this one, and a key missing anywhere else falls
	// back to it rather than to nothing.
	// ---------------------------------------------------------------------------------------------------------
	public static final DialogLang EN = new DialogLang("en", table(
		"compat.continuePlaying", "Continue playing",
		"compat.returnTitle", "Return to title",
		"compat.reportDetails", "Details are available on the Mods screen and in the compatibility report.",
		"compat.title", "Required mod features are unavailable",
		"compat.intro", "Forbric confirmed that these required features cannot work in this instance:",
		"compat.note", "You can continue for this launch, or quit to change the mod set. Closing this window does not approve continuing.",
		"details.required.header", "Required features that cannot work",
		"details.suspected.header", "Possible problems, not confirmed (no decision needed)",
		"compat.more", "{0} more will be shown after this.",
			"title.deps", "Forbric — a mod is missing something it requires",
			"title.mixins", "Forbric — two mods do not fit each other",
			"title.both", "Forbric — some mods are missing requirements, and some do not fit each other",
			"title.deps.many", "Forbric — some mods are missing things they require",
			"title.mixins.many", "Forbric — some mods do not fit each other",
			"button.continue", "Launch anyway",
			"button.quit", "Quit",
			"button.details.show", "Show details",
			"button.details.hide", "Hide details",
			"summary.deps.one", "One mod is missing something it requires:",
			"summary.deps.many", "{0} mods are missing something they require:",
			"summary.mixins.one", "One mod could not attach to another mod it was built for:",
			"summary.mixins.many", "{0} mods could not attach to other mods they were built for:",
			"summary.more", "…and {0} more. The full list is in the details.",
			"bullet.absent", "{0} needs {1}, which is not installed",
			"bullet.version", "{0} needs {1} {2}, and you have {3}",
			"bullet.mixin", "{0} could not attach to the mod it was built for",
			"fix.header", "What might fix it:",
			"fix.install", "Install {0}. {1} is a {2} mod, so the {2} build is the safest one to get — on Forbric "
					+ "a build for another loader can satisfy it too.",
			"fix.version", "Change {0} to a version that matches {1}. You have {2}.",
			"fix.mixin", "Both mods are installed and neither is missing anything — only their builds do not "
					+ "match. A version of {0} released around the same time as the mod it attaches to may fix it.",
			"fix.remove", "Or take {0} out of your mods folder. Forbric keeps loading everything else, so the "
					+ "rest of your mods still work.",
			"note.deps", "Forbric will launch anyway if you ask it to. A mod whose requirement is unmet usually "
					+ "fails much later, in an error that names neither mod — an empty world, a missing block, or "
					+ "a crash while creating a world — so it is worth fixing before you play.",
			"note.mixins", "Nothing reports this as a missing dependency, because it is not one: both mods are "
					+ "installed and each is inside the version range the other asks for. The two builds simply "
					+ "do not fit.",
			"details.deps.header", "Unmet requirements",
			"details.mixins.header", "Mods that could not attach",
			"details.by", "{0}  ({1}, {2})",
			"details.needs.absent", "needs {0} {1}  —  NOT INSTALLED",
			"details.needs.version", "needs {0} {1}  —  installed: {2}",
			"details.search", "search: {0}",
			"details.mixin", "{0}  —  {1}",
			"details.anchors", "could not find {0}",
			"isolation.title", "Forbric — the game crashed last time",
			"isolation.intro", "The game crashed the last time it ran, and the crash points at these mods:",
			"isolation.intro.clash", "The game crashed the last time it ran because these mods clash with {0}. Forbric "
					+ "can keep {0} and start without:",
			"isolation.bullet", "{0}  ({1})",
			"isolation.without", "Starting without them writes their file names into forbric-disabled.txt, next to "
					+ "your mods folder, and Forbric does not load them. The files stay where they are. To turn one back on, "
					+ "delete its line from forbric-disabled.txt.",
			"isolation.note", "This is a guess: the crash points at these mods, which does not prove they are at "
					+ "fault. Closing this window starts the game with every mod, as before.",
			"isolation.details.report", "The crash report is crash-reports/{0}; the analysis is "
					+ ".forbric-kernel/crash-analysis.txt.",
			"button.isolation.without", "Start without {0}",
			"button.isolation.everything", "Start with everything",
			// A MinecraftForge jar Forbric refuses ({0} the mod's name, {1} its file). The detail is the line the
			// confirmation window, the Mods screen and the in-game prompt show; the report lines are the load report's.
			"unsupported.forge.feature", "MinecraftForge mod",
			"unsupported.forge.detail", "{0} ({1}) is a MinecraftForge mod. Forbric no longer supports MinecraftForge, "
					+ "so it is not loaded. Get the NeoForge or Fabric build of {0} instead.",
			"unsupported.forge.bundled", "{0} ({1}) is a MinecraftForge build that another mod bundles. Forbric no "
					+ "longer supports MinecraftForge, so it is kept only as a library.",
			"report.unsupported.what", "not loaded: Forbric no longer supports MinecraftForge",
			"report.unsupported.fix", "Get the NeoForge or Fabric build of {0} and put it in the mods folder in place "
					+ "of {1}.",
			"report.unsupported.advice", "Forbric now loads Fabric and NeoForge mods only. Most mods publish a NeoForge "
					+ "or Fabric build for the same Minecraft version; that build is the one to use.",
			"details.log", "The same findings are in logs/latest.log, under [Forbric/Deps]."));

	// ---------------------------------------------------------------------------------------------------------
	// Simplified Chinese.
	// ---------------------------------------------------------------------------------------------------------
	public static final DialogLang ZH_CN = new DialogLang("zh_cn", table(
		"compat.continuePlaying", "继续游戏",
		"compat.returnTitle", "返回标题界面",
		"compat.reportDetails", "详细原因见 Mods 界面和兼容性报告。",
		"compat.title", "部分 mod 的必要功能无法运行",
		"compat.intro", "Forbric 已确认以下必要功能在当前实例中无法运行：",
		"compat.note", "你可以选择本次继续启动，或退出后调整 mod。关闭此窗口不会视为同意继续。",
		"details.required.header", "无法运行的必要功能",
		"details.suspected.header", "可能的问题（未确认，不需要你做选择）",
		"compat.more", "之后还会显示另外 {0} 项。",
			"title.deps", "Forbric —— 有 mod 缺少它需要的前置",
			"title.mixins", "Forbric —— 有两个 mod 互相不配套",
			"title.both", "Forbric —— 有 mod 缺前置，还有 mod 互相不配套",
			"title.deps.many", "Forbric —— 有些 mod 缺少它们需要的前置",
			"title.mixins.many", "Forbric —— 有些 mod 互相不配套",
			"button.continue", "继续启动",
			"button.quit", "退出",
			"button.details.show", "显示详细信息",
			"button.details.hide", "隐藏详细信息",
			"summary.deps.one", "有 1 个 mod 缺少它需要的前置：",
			"summary.deps.many", "有 {0} 个 mod 缺少它需要的前置：",
			"summary.mixins.one", "有 1 个 mod 没能接上它本来要配合的那个 mod：",
			"summary.mixins.many", "有 {0} 个 mod 没能接上它们本来要配合的那些 mod：",
			"summary.more", "……还有 {0} 个，完整列表在详细信息里。",
			"bullet.absent", "{0} 需要 {1}，但它没装",
			"bullet.version", "{0} 需要 {1} {2}，而你装的是 {3}",
			"bullet.mixin", "{0} 没能接上它本来要配合的那个 mod",
			"fix.header", "可以试试这些：",
			"fix.install", "装上 {0}。{1} 是 {2} 的 mod，所以下 {2} 版最稳妥 —— 在 Forbric 上，别的加载器的版本也可能顶用。",
			"fix.version", "把 {0} 换成符合 {1} 的版本。你现在装的是 {2}。",
			"fix.mixin", "两个 mod 都装了，也都不缺东西，只是这两个版本不配套。把 {0} 换成和它要配合的那个 mod "
					+ "同期发布的版本，可能就好了。",
			"fix.remove", "或者把 {0} 从 mods 文件夹里拿出来。Forbric 会照常加载其余的 mod，别的照样能玩。",
			"note.deps", "你让它启动，Forbric 就会照常启动。但前置没装齐的 mod 通常会在很久以后才出问题，而那个报错里"
					+ "往往不会提到上面任何一个 mod —— 可能是世界空空如也、某个方块不见了，或者创建世界时直接崩溃。"
					+ "所以最好先修好再玩。",
			"note.mixins", "这不会被任何地方报成“缺前置”，因为它确实不是：两个 mod 都装了，而且各自都在对方要求的"
					+ "版本范围里。只是这两个版本不配套。",
			"details.deps.header", "没满足的前置要求",
			"details.mixins.header", "没能接上的 mod",
			"details.by", "{0}（{1}，{2}）",
			"details.needs.absent", "需要 {0} {1}  —  没装",
			"details.needs.version", "需要 {0} {1}  —  已装：{2}",
			"details.search", "搜索：{0}",
			"details.mixin", "{0}  —  {1}",
			"details.anchors", "没找到 {0}",
			"isolation.title", "Forbric —— 上次游戏崩溃了",
			"isolation.intro", "上次运行时游戏崩溃了，崩溃指向这些 mod：",
			"isolation.intro.clash", "上次运行时游戏崩溃了，因为这些 mod 和 {0} 互相冲突。Forbric 可以保留 {0}，"
					+ "不加载下面这些再启动：",
			"isolation.bullet", "{0}（{1}）",
			"isolation.without", "选择不加载它们启动，Forbric 会把它们的文件名写进 mods 文件夹旁边的 "
					+ "forbric-disabled.txt，之后不再加载它们。文件本身不会被移动。想重新启用哪个，就把它那一行从 "
					+ "forbric-disabled.txt 里删掉。",
			"isolation.note", "这只是个猜测：崩溃指向这些 mod，并不能证明是它们的问题。关掉这个窗口，"
					+ "游戏会像以前一样加载全部 mod 启动。",
			"isolation.details.report", "崩溃报告在 crash-reports/{0}，分析在 .forbric-kernel/crash-analysis.txt。",
			"button.isolation.without", "不加载 {0} 启动",
			"button.isolation.everything", "全部照常启动",
			"unsupported.forge.feature", "MinecraftForge mod",
			"unsupported.forge.detail", "{0}（{1}）是 MinecraftForge mod。Forbric 已不再支持 MinecraftForge，所以它不会被加载。"
					+ "请改用 {0} 的 NeoForge 或 Fabric 版本。",
			"unsupported.forge.bundled", "{0}（{1}）是另一个 mod 内嵌的 MinecraftForge 版本。Forbric 已不再支持 "
					+ "MinecraftForge，它只作为库保留。",
			"report.unsupported.what", "没有加载：Forbric 已不再支持 MinecraftForge",
			"report.unsupported.fix", "请下载 {0} 的 NeoForge 或 Fabric 版本，放进 mods 文件夹替换 {1}。",
			"report.unsupported.advice", "Forbric 现在只加载 Fabric 和 NeoForge mod。大多数 mod 在同一个 Minecraft "
					+ "版本上都发布了 NeoForge 或 Fabric 版本，换成那个就行。",
			"details.log", "同样的内容也在 logs/latest.log 里，搜 [Forbric/Deps] 就能找到。"));

	// -------------------------------------------------------------------------------------------------------
	// Traditional Chinese.
	// -------------------------------------------------------------------------------------------------------
	public static final DialogLang ZH_TW = new DialogLang("zh_tw", table(
		"compat.continuePlaying", "繼續遊戲",
		"compat.returnTitle", "返回標題畫面",
		"compat.reportDetails", "詳細原因請見 Mods 畫面與相容性報告。",
		"compat.title", "部分 mod 的必要功能無法執行",
		"compat.intro", "Forbric 已確認以下必要功能在目前實例中無法執行：",
		"compat.note", "你可以選擇本次繼續啟動，或結束後調整 mod。關閉此視窗不代表同意繼續。",
		"details.required.header", "無法執行的必要功能",
		"details.suspected.header", "可能的問題（未確認，不需要你做選擇）",
		"compat.more", "之後還會顯示另外 {0} 項。",
			"title.deps", "Forbric —— 有模組缺少必要的前置模組",
			"title.mixins", "Forbric —— 有兩個模組彼此搭不起來",
			"title.both", "Forbric —— 有模組缺少前置模組，也有模組彼此搭不起來",
			"title.deps.many", "Forbric —— 有些模組缺少必要的前置模組",
			"title.mixins.many", "Forbric —— 有些模組彼此搭不起來",
			"button.continue", "仍要啟動",
			"button.quit", "結束",
			"button.details.show", "顯示詳細資訊",
			"button.details.hide", "隱藏詳細資訊",
			"summary.deps.one", "有一個模組缺少它需要的東西：",
			"summary.deps.many", "有 {0} 個模組缺少它們需要的東西：",
			"summary.mixins.one", "有一個模組無法接上它原本要搭配的模組：",
			"summary.mixins.many", "有 {0} 個模組無法接上它們原本要搭配的模組：",
			"summary.more", "……還有其他 {0} 個，完整清單請看詳細資訊。",
			"bullet.absent", "{0} 需要 {1}，但你沒有安裝",
			"bullet.version", "{0} 需要 {1} {2}，但你安裝的是 {3}",
			"bullet.mixin", "{0} 無法接上它原本要搭配的模組",
			"fix.header", "可能的解決方法：",
			"fix.install", "安裝 {0}。{1} 是 {2} 模組，所以裝 {2} 版本最保險——在 Forbric "
					+ "上，做給其他載入器的版本通常也能滿足它。",
			"fix.version", "把 {0} 換成符合 {1} 的版本。你現在裝的是 {2}。",
			"fix.mixin", "兩個模組都有安裝，也都不缺前置模組——只是這兩個版本搭不起來。換一個和它要搭配的模組差不多時"
					+ "期發布的 {0}，也許就能解決。",
			"fix.remove", "或者把 {0} 從 mods 資料夾移走。Forbric 會照常載入其餘模組，你其他的模組還是能用。",
			"note.deps", "你要它啟動，Forbric 還是會啟動。不過缺前置模組的問題通常要到很久以後才發作，而且到時候的錯"
					+ "誤訊息不會提到這兩個模組——可能是空蕩蕩的世界、少了某個方塊，或是建立世界時直接當掉——所以最好"
					+ "先處理好再玩。",
			"note.mixins", "不會有任何地方把這件事回報成缺少前置模組，因為它不是：兩個模組都有安裝，版本也都落在對方"
					+ "要求的範圍內。單純就是這兩個版本搭不起來。",
			"details.deps.header", "未滿足的需求",
			"details.mixins.header", "無法接上的模組",
			"details.by", "{0}  ({1}, {2})",
			"details.needs.absent", "需要 {0} {1}  —  未安裝",
			"details.needs.version", "需要 {0} {1}  —  已安裝：{2}",
			"details.search", "搜尋：{0}",
			"details.mixin", "{0}  —  {1}",
			"details.anchors", "找不到 {0}",
			"isolation.title", "Forbric —— 上次遊戲崩潰了",
			"isolation.intro", "上次執行時遊戲崩潰了，崩潰指向這些模組：",
			"isolation.intro.clash", "上次執行時遊戲崩潰了，因為這些模組和 {0} 互相衝突。Forbric 可以保留 {0}，"
					+ "不載入下面這些再啟動：",
			"isolation.bullet", "{0}（{1}）",
			"isolation.without", "選擇不載入它們啟動，Forbric 會把它們的檔名寫進 mods 資料夾旁邊的 "
					+ "forbric-disabled.txt，之後不再載入它們。檔案本身不會被移動。想重新啟用哪個，就把它那一行從 "
					+ "forbric-disabled.txt 裡刪掉。",
			"isolation.note", "這只是猜測：崩潰指向這些模組，並不能證明是它們的問題。關掉這個視窗，"
					+ "遊戲會像以前一樣載入全部模組啟動。",
			"isolation.details.report", "崩潰報告在 crash-reports/{0}，分析在 .forbric-kernel/crash-analysis.txt。",
			"button.isolation.without", "不載入 {0} 啟動",
			"button.isolation.everything", "全部照常啟動",
			"unsupported.forge.feature", "MinecraftForge mod",
			"unsupported.forge.detail", "{0}（{1}）是 MinecraftForge mod。Forbric 已不再支援 MinecraftForge，所以不會載入它。"
					+ "請改用 {0} 的 NeoForge 或 Fabric 版本。",
			"unsupported.forge.bundled", "{0}（{1}）是另一個 mod 內嵌的 MinecraftForge 版本。Forbric 已不再支援 "
					+ "MinecraftForge，它只作為函式庫保留。",
			"report.unsupported.what", "沒有載入：Forbric 已不再支援 MinecraftForge",
			"report.unsupported.fix", "請下載 {0} 的 NeoForge 或 Fabric 版本，放進 mods 資料夾取代 {1}。",
			"report.unsupported.advice", "Forbric 現在只載入 Fabric 和 NeoForge mod。大多數 mod 在同一個 Minecraft "
					+ "版本上都有 NeoForge 或 Fabric 版本，改用那個即可。",
			"details.log", "同樣的內容也記在 logs/latest.log 的 [Forbric/Deps] 底下。"));

	// -------------------------------------------------------------------------------------------------------
	// Japanese.
	// -------------------------------------------------------------------------------------------------------
	public static final DialogLang JA = new DialogLang("ja", table(
		"compat.continuePlaying", "プレイを続ける",
		"compat.returnTitle", "タイトルに戻る",
		"compat.reportDetails", "詳しい理由は Mods 画面と互換性レポートで確認できます。",
		"compat.title", "必要な mod 機能を利用できません",
		"compat.intro", "この環境では次の必要な機能が動作しないことを Forbric が確認しました：",
		"compat.note", "今回だけ起動を続けるか、終了して mod を変更できます。ウィンドウを閉じても続行には同意したことになりません。",
		"details.required.header", "動作しない必要な機能",
		"details.suspected.header", "考えられる問題（未確認・選択は不要）",
		"compat.more", "このあと、ほかの {0} 件も表示します。",
			"title.deps", "Forbric ——必要な前提MODが見つからないMODがあります",
			"title.mixins", "Forbric ——2つのMODがかみ合っていません",
			"title.both", "Forbric ——前提MODが足りないMODと、かみ合っていないMODがあります",
			"title.deps.many", "Forbric ——必要な前提MODが見つからないMODが複数あります",
			"title.mixins.many", "Forbric ——かみ合っていないMODが複数あります",
			"button.continue", "このまま起動",
			"button.quit", "終了",
			"button.details.show", "詳細を表示",
			"button.details.hide", "詳細を隠す",
			"summary.deps.one", "1つのMODに、必要な前提MODが足りません：",
			"summary.deps.many", "{0}個のMODに、必要な前提MODが足りません：",
			"summary.mixins.one", "1つのMODが、組み合わせて動くはずのMODに組み込めませんでした：",
			"summary.mixins.many", "{0}個のMODが、組み合わせて動くはずのMODに組み込めませんでした：",
			"summary.more", "……ほか {0} 件。すべての一覧は「詳細」で確認できます。",
			"bullet.absent", "{0} には {1} が必要ですが、インストールされていません",
			"bullet.version", "{0} には {1} {2} が必要ですが、お使いのバージョンは {3} です",
			"bullet.mixin", "{0} は、組み合わせて動くはずのMODに組み込めませんでした",
			"fix.header", "解決できるかもしれない方法：",
			"fix.install", "{0} をインストールしてください。{1} は {2} 向けのMODなので、{2} "
					+ "版を入れるのがいちばん確実です——Forbric では別のローダー向けの版でも条件を満たせることがあり"
					+ "ます。",
			"fix.version", "{0} を {1} に一致するバージョンに変えてください。現在は {2} です。",
			"fix.mixin", "どちらのMODもインストールされていて、足りないものもありません——ただ、ビルドどうしがかみ合"
					+ "っていないだけです。組み込み先のMODと近い時期に公開された {0} のバージョンにすると、直ること"
					+ "があります。",
			"fix.remove", "または、{0} を mods フォルダーから取り出してください。Forbric "
					+ "は残りをそのまま読み込み続けるので、ほかのMODは変わらず動きます。",
			"note.deps", "ご希望であれば、Forbric はこのまま起動できます。ただし前提MODが足りないMODは、ずっとあとに"
					+ "なってから——どちらのMOD名も出てこないエラー、何も生成されないワールド、消えたブロック、ワー"
					+ "ルド作成中のクラッシュといった形で——問題が出ることがほとんどです。遊ぶ前に直しておくことをお"
					+ "すすめします。",
			"note.mixins", "これは前提MODの不足としては報告されません。実際そうではないからです：どちらのMODもインス"
					+ "トールされていて、それぞれが相手の求めるバージョン範囲に収まっています。ただ、2つのビルドが"
					+ "かみ合っていないだけです。",
			"details.deps.header", "満たされていない前提条件",
			"details.mixins.header", "組み込めなかったMOD",
			"details.by", "{0}  （{1}、{2}）",
			"details.needs.absent", "必要：{0} {1}  ——  未インストール",
			"details.needs.version", "必要：{0} {1}  ——  インストール済み：{2}",
			"details.search", "検索：{0}",
			"details.mixin", "{0}  ——  {1}",
			"details.anchors", "{0} が見つかりませんでした",
			"isolation.title", "Forbric — 前回ゲームがクラッシュしました",
			"isolation.intro", "前回の起動でゲームがクラッシュしました。クラッシュはこれらのMODを指しています:",
			"isolation.intro.clash", "前回の起動で、これらのMODが {0} と競合したためゲームがクラッシュしました。"
					+ "Forbric は {0} を残し、次のMODなしで起動できます:",
			"isolation.bullet", "{0}（{1}）",
			"isolation.without", "これらなしで起動すると、ファイル名が mods フォルダーの隣にある forbric-disabled.txt "
					+ "に書き込まれ、Forbric はそれらを読み込みません。ファイル自体はそのまま残ります。元に戻すには、"
					+ "forbric-disabled.txt からその行を削除してください。",
			"isolation.note", "これは推測です。クラッシュがこれらのMODを指していても、"
					+ "原因だと証明されたわけではありません。このウィンドウを閉じると、"
					+ "これまでどおりすべてのMODで起動します。",
			"isolation.details.report", "クラッシュレポートは crash-reports/{0}、分析は "
					+ ".forbric-kernel/crash-analysis.txt にあります。",
			"button.isolation.without", "{0} なしで起動",
			"button.isolation.everything", "すべて読み込んで起動",
			"unsupported.forge.feature", "MinecraftForge mod",
			"unsupported.forge.detail", "{0}（{1}）は MinecraftForge 用の mod です。Forbric は MinecraftForge のサポートを"
					+ "終了したため、読み込まれません。{0} の NeoForge 版か Fabric 版を使ってください。",
			"unsupported.forge.bundled", "{0}（{1}）は別の mod に同梱された MinecraftForge 版です。Forbric は "
					+ "MinecraftForge のサポートを終了したため、ライブラリとしてのみ残します。",
			"report.unsupported.what", "読み込まれませんでした：Forbric は MinecraftForge のサポートを終了しました",
			"report.unsupported.fix", "{0} の NeoForge 版か Fabric 版をダウンロードし、mods フォルダの {1} と置き換えてください。",
			"report.unsupported.advice", "Forbric が読み込むのは Fabric と NeoForge の mod だけになりました。ほとんどの mod "
					+ "は同じ Minecraft バージョン向けに NeoForge 版か Fabric 版も公開しているので、そちらを使ってください。",
			"details.log", "同じ内容は logs/latest.log の [Forbric/Deps] にも記録されています。"));

	// -------------------------------------------------------------------------------------------------------
	// Korean.
	// -------------------------------------------------------------------------------------------------------
	public static final DialogLang KO = new DialogLang("ko", table(
		"compat.continuePlaying", "계속 플레이",
		"compat.returnTitle", "타이틀로 돌아가기",
		"compat.reportDetails", "자세한 내용은 Mods 화면과 호환성 보고서에서 확인할 수 있습니다.",
		"compat.title", "필수 모드 기능을 사용할 수 없습니다",
		"compat.intro", "Forbric이 현재 환경에서 다음 필수 기능이 작동하지 않는 것을 확인했습니다:",
		"compat.note", "이번 실행을 계속하거나 종료 후 모드를 변경할 수 있습니다. 창을 닫는 것은 계속 실행에 동의하는 것이 아닙니다.",
		"details.required.header", "작동하지 않는 필수 기능",
		"details.suspected.header", "가능한 문제 (확인되지 않음, 선택할 필요 없음)",
		"compat.more", "이후에 나머지 {0}개도 표시됩니다.",
			"title.deps", "Forbric - 어떤 모드에 필요한 것이 빠져 있습니다",
			"title.mixins", "Forbric - 두 모드가 서로 맞지 않습니다",
			"title.both", "Forbric - 일부 모드는 필요한 것이 빠져 있고, 일부 모드는 서로 맞지 않습니다",
			"title.deps.many", "Forbric - 일부 모드에 필요한 것이 빠져 있습니다",
			"title.mixins.many", "Forbric - 일부 모드가 서로 맞지 않습니다",
			"button.continue", "그래도 실행",
			"button.quit", "종료",
			"button.details.show", "자세한 정보 보기",
			"button.details.hide", "자세한 정보 숨기기",
			"summary.deps.one", "모드 하나에 필요한 것이 빠져 있습니다:",
			"summary.deps.many", "모드 {0}개에 필요한 것이 빠져 있습니다:",
			"summary.mixins.one", "모드 하나가 함께 쓰이도록 만들어진 다른 모드에 연결되지 못했습니다:",
			"summary.mixins.many", "모드 {0}개가 함께 쓰이도록 만들어진 다른 모드에 연결되지 못했습니다:",
			"summary.more", "……그 외 {0}개가 더 있습니다. 전체 목록은 자세히 보기에서 확인할 수 있습니다.",
			"bullet.absent", "{0}에는 {1}이(가) 필요하지만 설치되어 있지 않습니다",
			"bullet.version", "{0}에는 {1} {2}이(가) 필요하지만, 설치된 버전은 {3}입니다",
			"bullet.mixin", "{0}이(가) 함께 쓰이도록 만들어진 모드에 연결되지 못했습니다",
			"fix.header", "이렇게 하면 해결될 수 있습니다:",
			"fix.install", "{0}을(를) 설치해 보세요. {1}은(는) {2} 모드이므로 {2} 버전을 받는 것이 가장 안전합니다 "
					+ "- Forbric에서는 다른 로더용 버전으로도 해결될 수 있습니다.",
			"fix.version", "{0}을(를) {1}에 맞는 버전으로 바꿔 보세요. 지금 설치된 버전은 {2}입니다.",
			"fix.mixin", "두 모드 모두 설치되어 있고 빠진 것도 없습니다. 단지 두 빌드가 서로 맞지 않을 뿐입니다. "
					+ "연결 대상 모드와 비슷한 시기에 나온 {0} 버전을 쓰면 해결될 수 있습니다.",
			"fix.remove", "또는 {0}을(를) mods 폴더에서 빼도 됩니다. Forbric은 나머지를 계속 불러오므로 다른 모드는 "
					+ "그대로 작동합니다.",
			"note.deps", "원하신다면 Forbric은 이대로도 실행합니다. 다만 필요한 것이 빠진 모드는 보통 한참 뒤에야 "
					+ "문제를 일으키고, 그때 나오는 오류에는 두 모드의 이름이 모두 나오지 않습니다 - 텅 빈 세계, "
					+ "사라진 블록, 세계를 만들다 나는 크래시 같은 식입니다. 그래서 플레이 전에 해결해 두는 편이 "
					+ "좋습니다.",
			"note.mixins", "이것을 전제 모드 누락으로 알려 주는 곳은 없습니다. 실제로 누락이 아니기 때문입니다. 두 "
					+ "모드 모두 설치되어 있고, 서로가 요구하는 버전 범위도 만족합니다. 단지 두 빌드가 서로 맞지 "
					+ "않을 뿐입니다.",
			"details.deps.header", "충족되지 않은 요구 사항",
			"details.mixins.header", "연결하지 못한 모드",
			"details.by", "{0}  ({1}, {2})",
			"details.needs.absent", "{0} {1} 필요  —  설치되지 않음",
			"details.needs.version", "{0} {1} 필요  —  설치됨: {2}",
			"details.search", "검색: {0}",
			"details.mixin", "{0}  —  {1}",
			"details.anchors", "{0}을(를) 찾지 못했습니다",
			"isolation.title", "Forbric — 지난번에 게임이 크래시되었습니다",
			"isolation.intro", "지난번 실행에서 게임이 크래시되었고, 크래시는 다음 모드를 가리킵니다:",
			"isolation.intro.clash", "지난번 실행에서 다음 모드가 {0}와(과) 서로 충돌해 게임이 크래시되었습니다. "
					+ "Forbric은 {0}을(를) 남기고 다음 모드 없이 실행할 수 있습니다:",
			"isolation.bullet", "{0}  ({1})",
			"isolation.without", "이 모드 없이 실행하면 파일 이름이 mods 폴더 옆의 forbric-disabled.txt에 기록되고, "
					+ "Forbric은 해당 모드를 불러오지 않습니다. 파일은 그대로 남습니다. 다시 켜려면 forbric-disabled.txt에서 "
					+ "해당 줄을 지우세요.",
			"isolation.note", "이것은 추측입니다. 크래시가 이 모드들을 가리킨다고 해서 원인이라는 증거는 아닙니다. 이 "
					+ "창을 닫으면 이전처럼 모든 모드로 실행합니다.",
			"isolation.details.report", "크래시 보고서는 crash-reports/{0}, 분석은 "
					+ ".forbric-kernel/crash-analysis.txt에 있습니다.",
			"button.isolation.without", "{0} 없이 실행",
			"button.isolation.everything", "모두 불러와 실행",
			"unsupported.forge.feature", "MinecraftForge 모드",
			"unsupported.forge.detail", "{0}({1})은(는) MinecraftForge 모드입니다. Forbric은 더 이상 MinecraftForge를 "
					+ "지원하지 않으므로 이 모드를 불러오지 않습니다. {0}의 NeoForge 또는 Fabric 버전을 사용하세요.",
			"unsupported.forge.bundled", "{0}({1})은(는) 다른 모드에 포함된 MinecraftForge 버전입니다. Forbric은 더 이상 "
					+ "MinecraftForge를 지원하지 않으므로 라이브러리로만 남겨 둡니다.",
			"report.unsupported.what", "불러오지 않음: Forbric은 더 이상 MinecraftForge를 지원하지 않습니다",
			"report.unsupported.fix", "{0}의 NeoForge 또는 Fabric 버전을 받아 mods 폴더의 {1} 대신 넣으세요.",
			"report.unsupported.advice", "이제 Forbric은 Fabric과 NeoForge 모드만 불러옵니다. 대부분의 모드는 같은 "
					+ "Minecraft 버전용 NeoForge 또는 Fabric 버전도 배포하므로 그 버전을 사용하세요.",
			"details.log", "같은 내용이 logs/latest.log의 [Forbric/Deps] 항목에도 기록되어 있습니다."));

	// -------------------------------------------------------------------------------------------------------
	// Russian.
	// -------------------------------------------------------------------------------------------------------
	public static final DialogLang RU = new DialogLang("ru", table(
		"compat.continuePlaying", "Продолжить игру",
		"compat.returnTitle", "Вернуться в меню",
		"compat.reportDetails", "Подробности доступны на экране Mods и в отчёте о совместимости.",
		"compat.title", "Необходимые функции модов недоступны",
		"compat.intro", "Forbric подтвердил, что в этой сборке не работают следующие необходимые функции:",
		"compat.note", "Можно продолжить этот запуск или выйти и изменить набор модов. Закрытие окна не означает согласие продолжить.",
		"details.required.header", "Необходимые функции, которые не работают",
		"details.suspected.header", "Возможные проблемы (не подтверждены, решение не требуется)",
		"compat.more", "После этого будут показаны ещё {0}.",
			"title.deps", "Forbric — моду не хватает того, что ему нужно",
			"title.mixins", "Forbric — два мода не подходят друг другу",
			"title.both", "Forbric — одним модам не хватает нужного, другие не подходят друг другу",
			"title.deps.many", "Forbric — некоторым модам не хватает нужного",
			"title.mixins.many", "Forbric — некоторые моды не подходят друг другу",
			"button.continue", "Всё равно запустить",
			"button.quit", "Выход",
			"button.details.show", "Показать подробности",
			"button.details.hide", "Скрыть подробности",
			"summary.deps.one", "Одному моду не хватает того, что ему нужно:",
			"summary.deps.many", "Модам не хватает того, что им нужно (всего: {0}):",
			"summary.mixins.one", "Один мод не смог подключиться к другому моду, для которого он сделан:",
			"summary.mixins.many", "Моды не смогли подключиться к другим модам, для которых они сделаны (всего: "
					+ "{0}):",
			"summary.more", "…и ещё {0}. Полный список — в подробностях.",
			"bullet.absent", "{0}: нужен мод {1}, а он не установлен",
			"bullet.version", "{0}: нужен {1} версии {2}, а установлена {3}",
			"bullet.mixin", "{0} не смог подключиться к моду, для которого он сделан",
			"fix.header", "Что может помочь:",
			"fix.install", "Установите {0}. {1} — это мод для {2}, поэтому надёжнее всего взять сборку для {2}, но "
					+ "в Forbric подойдёт и сборка для другого загрузчика.",
			"fix.version", "Смените {0} на версию, подходящую под {1}. Сейчас установлена {2}.",
			"fix.mixin", "Оба мода установлены, и ни одному из них ничего не хватает — просто их сборки не "
					+ "совпадают. Может помочь версия {0}, вышедшая примерно тогда же, что и мод, к которому она "
					+ "подключается.",
			"fix.remove", "Или уберите {0} из папки mods. Forbric продолжит загружать всё остальное, так что другие "
					+ "ваши моды будут работать.",
			"note.deps", "Если попросите, Forbric запустится и так. Но мод, которому не хватает нужного, обычно "
					+ "ломается гораздо позже — и в ошибке не будет названия ни одного из модов: пустой мир, "
					+ "пропавший блок или вылет при создании мира. Лучше починить это до начала игры.",
			"note.mixins", "Никто не сообщает об этом как о пропущенной зависимости, потому что это не она: оба "
					+ "мода установлены, и версия каждого попадает в тот диапазон, который просит другой. Просто "
					+ "две сборки не подходят друг другу.",
			"details.deps.header", "Невыполненные требования",
			"details.mixins.header", "Моды, которые не смогли подключиться",
			"details.by", "{0}  ({1}, {2})",
			"details.needs.absent", "нужен {0} {1}  —  НЕ УСТАНОВЛЕН",
			"details.needs.version", "нужен {0} {1}  —  установлено: {2}",
			"details.search", "поиск: {0}",
			"details.mixin", "{0}  —  {1}",
			"details.anchors", "не удалось найти {0}",
			"isolation.title", "Forbric — в прошлый раз игра вылетела",
			"isolation.intro", "В прошлый раз игра вылетела, и вылет указывает на эти моды:",
			"isolation.intro.clash", "В прошлый раз игра вылетела, потому что эти моды конфликтуют с {0}. Forbric "
					+ "может оставить {0} и запустить игру без:",
			"isolation.bullet", "{0}  ({1})",
			"isolation.without", "Если запустить без них, Forbric запишет имена их файлов в forbric-disabled.txt рядом "
					+ "с папкой mods и не будет их загружать. Сами файлы останутся на месте. Чтобы снова включить мод, "
					+ "удалите его строку из forbric-disabled.txt.",
			"isolation.note", "Это догадка: вылет указывает на эти моды, но это не доказывает, что виноваты они. Если "
					+ "закрыть это окно, игра запустится со всеми модами, как раньше.",
			"isolation.details.report", "Отчёт о вылете: crash-reports/{0}; разбор: .forbric-kernel/crash-analysis.txt.",
			"button.isolation.without", "Запустить без {0}",
			"button.isolation.everything", "Запустить со всеми модами",
			"unsupported.forge.feature", "Мод MinecraftForge",
			"unsupported.forge.detail", "{0} ({1}) — мод для MinecraftForge. Forbric больше не поддерживает MinecraftForge, "
					+ "поэтому он не загружается. Возьмите версию {0} для NeoForge или Fabric.",
			"unsupported.forge.bundled", "{0} ({1}) — сборка для MinecraftForge, встроенная в другой мод. Forbric больше "
					+ "не поддерживает MinecraftForge, поэтому она оставлена только как библиотека.",
			"report.unsupported.what", "не загружен: Forbric больше не поддерживает MinecraftForge",
			"report.unsupported.fix", "Скачайте версию {0} для NeoForge или Fabric и положите её в папку mods вместо {1}.",
			"report.unsupported.advice", "Теперь Forbric загружает только моды для Fabric и NeoForge. У большинства модов "
					+ "есть версия для NeoForge или Fabric под ту же версию Minecraft — используйте её.",
			"details.log", "Те же сведения есть в logs/latest.log, в разделе [Forbric/Deps]."));

	// -------------------------------------------------------------------------------------------------------
	// German.
	// -------------------------------------------------------------------------------------------------------
	public static final DialogLang DE = new DialogLang("de", table(
		"compat.continuePlaying", "Weiterspielen",
		"compat.returnTitle", "Zum Titelbildschirm",
		"compat.reportDetails", "Details stehen im Mods-Menü und im Kompatibilitätsbericht.",
		"compat.title", "Erforderliche Mod-Funktionen sind nicht verfügbar",
		"compat.intro", "Forbric hat bestätigt, dass diese erforderlichen Funktionen in dieser Instanz nicht funktionieren:",
		"compat.note", "Du kannst diesen Start fortsetzen oder beenden und die Mods ändern. Das Schließen dieses Fensters erlaubt keine Fortsetzung.",
		"details.required.header", "Erforderliche Funktionen, die nicht funktionieren",
		"details.suspected.header", "Mögliche Probleme (nicht bestätigt, keine Entscheidung nötig)",
		"compat.more", "Danach werden {0} weitere angezeigt.",
			"title.deps", "Forbric - einem Mod fehlt etwas, das er braucht",
			"title.mixins", "Forbric - zwei Mods passen nicht zueinander",
			"title.both", "Forbric - einigen Mods fehlt etwas, und andere passen nicht zueinander",
			"title.deps.many", "Forbric - einigen Mods fehlt etwas, das sie brauchen",
			"title.mixins.many", "Forbric - einige Mods passen nicht zueinander",
			"button.continue", "Trotzdem starten",
			"button.quit", "Beenden",
			"button.details.show", "Details anzeigen",
			"button.details.hide", "Details ausblenden",
			"summary.deps.one", "Einem Mod fehlt etwas, das er benötigt:",
			"summary.deps.many", "{0} Mods fehlt etwas, das sie benötigen:",
			"summary.mixins.one", "Ein Mod konnte sich nicht mit dem Mod verbinden, für den er gebaut wurde:",
			"summary.mixins.many", "{0} Mods konnten sich nicht mit den Mods verbinden, für die sie gebaut wurden:",
			"summary.more", "… und {0} weitere. Die vollständige Liste findest du in den Details.",
			"bullet.absent", "{0} benötigt {1}, das nicht installiert ist",
			"bullet.version", "{0} benötigt {1} {2}, installiert ist aber {3}",
			"bullet.mixin", "{0} konnte sich nicht mit dem Mod verbinden, für den er gebaut wurde",
			"fix.header", "Was helfen könnte:",
			"fix.install", "Installiere {0}. {1} ist ein {2}-Mod, daher ist die {2}-Fassung am sichersten - unter "
					+ "Forbric kann auch eine Fassung für einen anderen Mod-Loader genügen.",
			"fix.version", "Wechsle bei {0} auf eine Version, die zu {1} passt. Installiert ist {2}.",
			"fix.mixin", "Beide Mods sind installiert, und keinem fehlt etwas - nur ihre Fassungen passen nicht "
					+ "zueinander. Eine Version von {0}, die etwa zur gleichen Zeit erschienen ist wie der Mod, "
					+ "mit dem sie sich verbinden soll, kann das beheben.",
			"fix.remove", "Oder nimm {0} aus deinem mods-Ordner heraus. Forbric lädt alles andere weiter, deine "
					+ "übrigen Mods funktionieren also nach wie vor.",
			"note.deps", "Forbric startet trotzdem, wenn du es möchtest. Ein Mod, dessen Voraussetzung fehlt, fällt "
					+ "meist erst viel später aus - mit einem Fehler, der keinen der beiden Mods nennt: eine leere "
					+ "Welt, ein fehlender Block oder ein Absturz beim Erstellen einer Welt. Es lohnt sich also, "
					+ "das vor dem Spielen zu beheben.",
			"note.mixins", "Nirgends wird das als fehlende Voraussetzung gemeldet, denn das ist es nicht: Beide "
					+ "Mods sind installiert, und jeder liegt in dem Versionsbereich, den der andere verlangt. Die "
					+ "beiden Fassungen passen schlicht nicht zueinander.",
			"details.deps.header", "Nicht erfüllte Voraussetzungen",
			"details.mixins.header", "Mods, die sich nicht verbinden konnten",
			"details.by", "{0}  ({1}, {2})",
			"details.needs.absent", "benötigt {0} {1}  —  NICHT INSTALLIERT",
			"details.needs.version", "benötigt {0} {1}  —  installiert: {2}",
			"details.search", "Suche: {0}",
			"details.mixin", "{0}  —  {1}",
			"details.anchors", "{0} nicht gefunden",
			"isolation.title", "Forbric — das Spiel ist beim letzten Mal abgestürzt",
			"isolation.intro", "Das Spiel ist beim letzten Start abgestürzt, und der Absturz deutet auf diese Mods:",
			"isolation.intro.clash", "Das Spiel ist beim letzten Start abgestürzt, weil diese Mods mit {0} "
					+ "kollidieren. Forbric kann {0} behalten und ohne diese starten:",
			"isolation.bullet", "{0}  ({1})",
			"isolation.without", "Startest du ohne sie, schreibt Forbric ihre Dateinamen in forbric-disabled.txt neben "
					+ "deinem mods-Ordner und lädt sie nicht. Die Dateien bleiben, wo sie sind. Um einen Mod wieder "
					+ "einzuschalten, lösche seine Zeile aus forbric-disabled.txt.",
			"isolation.note", "Das ist eine Vermutung: Der Absturz deutet auf diese Mods, beweist aber nicht, dass sie "
					+ "schuld sind. Schließt du dieses Fenster, startet das Spiel wie bisher mit allen Mods.",
			"isolation.details.report", "Der Absturzbericht ist crash-reports/{0}, die Analyse "
					+ ".forbric-kernel/crash-analysis.txt.",
			"button.isolation.without", "Ohne {0} starten",
			"button.isolation.everything", "Mit allen Mods starten",
			"unsupported.forge.feature", "MinecraftForge-Mod",
			"unsupported.forge.detail", "{0} ({1}) ist eine MinecraftForge-Mod. Forbric unterstützt MinecraftForge nicht "
					+ "mehr, daher wird sie nicht geladen. Nimm stattdessen die NeoForge- oder Fabric-Version von {0}.",
			"unsupported.forge.bundled", "{0} ({1}) ist eine MinecraftForge-Version, die eine andere Mod mitbringt. "
					+ "Forbric unterstützt MinecraftForge nicht mehr, daher bleibt sie nur als Bibliothek erhalten.",
			"report.unsupported.what", "nicht geladen: Forbric unterstützt MinecraftForge nicht mehr",
			"report.unsupported.fix", "Lade die NeoForge- oder Fabric-Version von {0} herunter und lege sie anstelle von "
					+ "{1} in den mods-Ordner.",
			"report.unsupported.advice", "Forbric lädt jetzt nur noch Fabric- und NeoForge-Mods. Die meisten Mods "
					+ "veröffentlichen für dieselbe Minecraft-Version auch eine NeoForge- oder Fabric-Version; nimm diese.",
			"details.log", "Dieselben Ergebnisse stehen in logs/latest.log unter [Forbric/Deps]."));

	// -------------------------------------------------------------------------------------------------------
	// French.
	// -------------------------------------------------------------------------------------------------------
	public static final DialogLang FR = new DialogLang("fr", table(
		"compat.continuePlaying", "Continuer à jouer",
		"compat.returnTitle", "Retour à l’accueil",
		"compat.reportDetails", "Les détails sont disponibles dans le menu Mods et le rapport de compatibilité.",
		"compat.title", "Des fonctions nécessaires des mods sont indisponibles",
		"compat.intro", "Forbric a confirmé que ces fonctions nécessaires ne peuvent pas fonctionner dans cette instance :",
		"compat.note", "Vous pouvez poursuivre ce lancement, ou quitter pour changer les mods. Fermer cette fenêtre ne vaut pas accord pour continuer.",
		"details.required.header", "Fonctions nécessaires qui ne peuvent pas fonctionner",
		"details.suspected.header", "Problèmes possibles (non confirmés, aucune décision requise)",
		"compat.more", "{0} autre(s) seront affichée(s) ensuite.",
			"title.deps", "Forbric - un mod ne trouve pas quelque chose dont il a besoin",
			"title.mixins", "Forbric - deux mods ne s'accordent pas",
			"title.both", "Forbric - certains mods ne trouvent pas ce dont ils ont besoin, et d'autres ne "
					+ "s'accordent pas entre eux",
			"title.deps.many", "Forbric - il manque quelque chose à certains mods",
			"title.mixins.many", "Forbric - certains mods ne sont pas compatibles entre eux",
			"button.continue", "Lancer quand même",
			"button.quit", "Quitter",
			"button.details.show", "Afficher les détails",
			"button.details.hide", "Masquer les détails",
			"summary.deps.one", "Un mod ne trouve pas quelque chose dont il a besoin :",
			"summary.deps.many", "{0} mods ne trouvent pas quelque chose dont ils ont besoin :",
			"summary.mixins.one", "Un mod n'a pas pu se greffer sur l'autre mod pour lequel il a été conçu :",
			"summary.mixins.many", "{0} mods n'ont pas pu se greffer sur les autres mods pour lesquels ils ont été "
					+ "conçus :",
			"summary.more", "… et {0} autres. La liste complète se trouve dans les détails.",
			"bullet.absent", "{0} a besoin de {1}, qui n'est pas installé",
			"bullet.version", "{0} a besoin de {1} {2}, et vous avez {3}",
			"bullet.mixin", "{0} n'a pas pu se greffer sur le mod pour lequel il a été conçu",
			"fix.header", "Ce qui peut régler le problème :",
			"fix.install", "Installez {0}. {1} est un mod {2}, donc la version {2} est la plus sûre à récupérer - "
					+ "sur Forbric, une version prévue pour un autre chargeur de mods peut aussi faire l'affaire.",
			"fix.version", "Remplacez {0} par une version qui correspond à {1}. Vous avez la {2}.",
			"fix.mixin", "Les deux mods sont installés et il ne manque rien à aucun des deux - ce sont seulement "
					+ "leurs versions qui ne correspondent pas. Une version de {0} sortie à peu près en même temps "
					+ "que le mod auquel il se greffe peut régler le problème.",
			"fix.remove", "Ou alors, retirez {0} de votre dossier mods. Forbric continue de charger tout le reste, "
					+ "donc vos autres mods fonctionnent toujours.",
			"note.deps", "Forbric se lancera quand même si vous le lui demandez. Un mod dont un besoin n'est pas "
					+ "satisfait échoue en général bien plus tard, dans une erreur qui ne cite ni l'un ni l'autre "
					+ "des deux mods - un monde vide, un bloc manquant, ou un plantage au moment de créer un monde "
					+ "- donc mieux vaut régler cela avant de jouer.",
			"note.mixins", "Rien ne signale cela comme une dépendance manquante, car ce n'en est pas une : les deux "
					+ "mods sont installés, et chacun se trouve dans la plage de versions que l'autre demande. Ce "
					+ "sont simplement leurs deux versions qui ne s'accordent pas.",
			"details.deps.header", "Besoins non satisfaits",
			"details.mixins.header", "Mods qui n'ont pas pu se greffer",
			"details.by", "{0}  ({1}, {2})",
			"details.needs.absent", "a besoin de {0} {1}  —  NON INSTALLÉ",
			"details.needs.version", "a besoin de {0} {1}  —  installé : {2}",
			"details.search", "recherche : {0}",
			"details.mixin", "{0}  —  {1}",
			"details.anchors", "impossible de trouver {0}",
			"isolation.title", "Forbric — le jeu a planté la dernière fois",
			"isolation.intro", "Le jeu a planté lors du dernier lancement, et le plantage désigne ces mods :",
			"isolation.intro.clash", "Le jeu a planté lors du dernier lancement parce que ces mods entrent en conflit "
					+ "avec {0}. Forbric peut garder {0} et démarrer sans :",
			"isolation.bullet", "{0}  ({1})",
			"isolation.without", "Démarrer sans eux inscrit leurs noms de fichiers dans forbric-disabled.txt, à côté "
					+ "de votre dossier mods, et Forbric ne les charge plus. Les fichiers restent où ils sont. Pour en "
					+ "réactiver un, supprimez sa ligne de forbric-disabled.txt.",
			"isolation.note", "Ce n'est qu'une supposition : le plantage désigne ces mods, ce qui ne prouve pas qu'ils "
					+ "sont en cause. Fermer cette fenêtre lance le jeu avec tous les mods, comme avant.",
			"isolation.details.report", "Le rapport de plantage est crash-reports/{0} ; l'analyse est "
					+ ".forbric-kernel/crash-analysis.txt.",
			"button.isolation.without", "Démarrer sans {0}",
			"button.isolation.everything", "Démarrer avec tout",
			"unsupported.forge.feature", "Mod MinecraftForge",
			"unsupported.forge.detail", "{0} ({1}) est un mod MinecraftForge. Forbric ne prend plus en charge "
					+ "MinecraftForge, il n’est donc pas chargé. Prenez plutôt la version NeoForge ou Fabric de {0}.",
			"unsupported.forge.bundled", "{0} ({1}) est une version MinecraftForge embarquée par un autre mod. Forbric ne "
					+ "prend plus en charge MinecraftForge, elle est donc conservée seulement comme bibliothèque.",
			"report.unsupported.what", "non chargé : Forbric ne prend plus en charge MinecraftForge",
			"report.unsupported.fix", "Téléchargez la version NeoForge ou Fabric de {0} et mettez-la dans le dossier mods "
					+ "à la place de {1}.",
			"report.unsupported.advice", "Forbric ne charge plus que les mods Fabric et NeoForge. La plupart des mods "
					+ "publient une version NeoForge ou Fabric pour la même version de Minecraft ; c’est celle-là qu’il faut.",
			"details.log", "Les mêmes informations se trouvent dans logs/latest.log, sous [Forbric/Deps]."));

	// -------------------------------------------------------------------------------------------------------
	// Spanish.
	// -------------------------------------------------------------------------------------------------------
	public static final DialogLang ES = new DialogLang("es", table(
		"compat.continuePlaying", "Seguir jugando",
		"compat.returnTitle", "Volver al título",
		"compat.reportDetails", "Los detalles están en la pantalla de Mods y en el informe de compatibilidad.",
		"compat.title", "Faltan funciones necesarias de los mods",
		"compat.intro", "Forbric ha confirmado que estas funciones necesarias no funcionan en esta instancia:",
		"compat.note", "Puedes continuar este inicio o salir para cambiar los mods. Cerrar esta ventana no autoriza continuar.",
		"details.required.header", "Funciones necesarias que no pueden funcionar",
		"details.suspected.header", "Posibles problemas (sin confirmar, no requieren decisión)",
		"compat.more", "Después se mostrarán {0} más.",
			"title.deps", "Forbric - a un mod le falta algo que necesita",
			"title.mixins", "Forbric - dos mods no encajan entre sí",
			"title.both", "Forbric - a algunos mods les faltan requisitos y otros no encajan entre sí",
			"title.deps.many", "Forbric - a algunos mods les falta algo que necesitan",
			"title.mixins.many", "Forbric - algunos mods no encajan entre sí",
			"button.continue", "Iniciar de todos modos",
			"button.quit", "Salir",
			"button.details.show", "Mostrar detalles",
			"button.details.hide", "Ocultar detalles",
			"summary.deps.one", "A un mod le falta algo que necesita:",
			"summary.deps.many", "A {0} mods les falta algo que necesitan:",
			"summary.mixins.one", "Un mod no pudo acoplarse a otro mod para el que fue creado:",
			"summary.mixins.many", "{0} mods no pudieron acoplarse a otros mods para los que fueron creados:",
			"summary.more", "…y {0} más. La lista completa está en los detalles.",
			"bullet.absent", "{0} necesita {1}, que no está instalado",
			"bullet.version", "{0} necesita {1} {2}, y tienes {3}",
			"bullet.mixin", "{0} no pudo acoplarse al mod para el que fue creado",
			"fix.header", "Lo que podría solucionarlo:",
			"fix.install", "Instala {0}. {1} es un mod de {2}, así que la versión para {2} es la más segura de "
					+ "conseguir; en Forbric una versión hecha para otro cargador también puede servir.",
			"fix.version", "Cambia {0} a una versión que coincida con {1}. Tienes {2}.",
			"fix.mixin", "Ambos mods están instalados y a ninguno le falta nada; lo único que no coincide son sus "
					+ "versiones. Una versión de {0} publicada más o menos en la misma época que el mod al que se "
					+ "acopla podría solucionarlo.",
			"fix.remove", "O saca {0} de tu carpeta mods. Forbric sigue cargando todo lo demás, así que el resto de "
					+ "tus mods siguen funcionando.",
			"note.deps", "Forbric se iniciará de todos modos si se lo pides. Un mod al que le falta algo que "
					+ "necesita suele fallar mucho después, con un error que no menciona a ninguno de los dos "
					+ "mods: un mundo vacío, un bloque que no aparece o un cierre inesperado al crear un mundo. "
					+ "Por eso conviene arreglarlo antes de jugar.",
			"note.mixins", "Nada lo reporta como una dependencia faltante, porque no lo es: ambos mods están "
					+ "instalados y cada uno está dentro del rango de versiones que pide el otro. Simplemente sus "
					+ "versiones no encajan.",
			"details.deps.header", "Requisitos no cubiertos",
			"details.mixins.header", "Mods que no pudieron acoplarse",
			"details.by", "{0}  ({1}, {2})",
			"details.needs.absent", "necesita {0} {1}  —  NO INSTALADO",
			"details.needs.version", "necesita {0} {1}  —  instalado: {2}",
			"details.search", "búsqueda: {0}",
			"details.mixin", "{0}  —  {1}",
			"details.anchors", "no se encontró {0}",
			"isolation.title", "Forbric — el juego se cerró por un error la última vez",
			"isolation.intro", "El juego se cerró por un error la última vez que se ejecutó, y el error apunta a estos mods:",
			"isolation.intro.clash", "El juego se cerró por un error la última vez porque estos mods chocan con {0}. "
					+ "Forbric puede conservar {0} e iniciar sin:",
			"isolation.bullet", "{0}  ({1})",
			"isolation.without", "Si inicias sin ellos, Forbric escribe sus nombres de archivo en "
					+ "forbric-disabled.txt, junto a tu carpeta mods, y no los carga. Los archivos se quedan donde están. "
					+ "Para volver a activar uno, borra su línea de forbric-disabled.txt.",
			"isolation.note", "Es una suposición: el error apunta a estos mods, pero eso no demuestra que sean los "
					+ "culpables. Si cierras esta ventana, el juego se inicia con todos los mods, como antes.",
			"isolation.details.report", "El informe del error está en crash-reports/{0}; el análisis, en "
					+ ".forbric-kernel/crash-analysis.txt.",
			"button.isolation.without", "Iniciar sin {0}",
			"button.isolation.everything", "Iniciar con todo",
			"unsupported.forge.feature", "Mod de MinecraftForge",
			"unsupported.forge.detail", "{0} ({1}) es un mod de MinecraftForge. Forbric ya no es compatible con "
					+ "MinecraftForge, así que no se carga. Usa en su lugar la versión de {0} para NeoForge o Fabric.",
			"unsupported.forge.bundled", "{0} ({1}) es una versión de MinecraftForge incluida en otro mod. Forbric ya no "
					+ "es compatible con MinecraftForge, así que se conserva solo como biblioteca.",
			"report.unsupported.what", "no se cargó: Forbric ya no es compatible con MinecraftForge",
			"report.unsupported.fix", "Descarga la versión de {0} para NeoForge o Fabric y ponla en la carpeta mods en "
					+ "lugar de {1}.",
			"report.unsupported.advice", "Forbric ahora solo carga mods de Fabric y NeoForge. La mayoría de los mods "
					+ "publican una versión para NeoForge o Fabric de la misma versión de Minecraft; usa esa.",
			"details.log", "Los mismos resultados están en logs/latest.log, bajo [Forbric/Deps]."));

	// -------------------------------------------------------------------------------------------------------
	// Brazilian Portuguese. Reached from any Portuguese region; see of(Locale).
	// -------------------------------------------------------------------------------------------------------
	public static final DialogLang PT_BR = new DialogLang("pt_br", table(
		"compat.continuePlaying", "Continuar jogando",
		"compat.returnTitle", "Voltar ao título",
		"compat.reportDetails", "Os detalhes estão na tela de Mods e no relatório de compatibilidade.",
		"compat.title", "Funções necessárias dos mods estão indisponíveis",
		"compat.intro", "O Forbric confirmou que estas funções necessárias não funcionam nesta instância:",
		"compat.note", "Você pode continuar esta inicialização ou sair para alterar os mods. Fechar esta janela não autoriza continuar.",
		"details.required.header", "Funções necessárias que não podem funcionar",
		"details.suspected.header", "Possíveis problemas (não confirmados, nenhuma decisão necessária)",
		"compat.more", "Depois serão mostradas mais {0}.",
			"title.deps", "Forbric - um mod está sem algo de que precisa",
			"title.mixins", "Forbric - dois mods não se encaixam",
			"title.both", "Forbric - alguns mods estão sem o que precisam, e outros não se encaixam",
			"title.deps.many", "Forbric - alguns mods estão sem algo de que precisam",
			"title.mixins.many", "Forbric - alguns mods não se encaixam",
			"button.continue", "Iniciar mesmo assim",
			"button.quit", "Sair",
			"button.details.show", "Mostrar detalhes",
			"button.details.hide", "Ocultar detalhes",
			"summary.deps.one", "Um mod está sem algo de que precisa:",
			"summary.deps.many", "{0} mods estão sem algo de que precisam:",
			"summary.mixins.one", "Um mod não conseguiu se encaixar em outro mod para o qual foi feito:",
			"summary.mixins.many", "{0} mods não conseguiram se encaixar nos outros mods para os quais foram feitos:",
			"summary.more", "…e mais {0}. A lista completa está nos detalhes.",
			"bullet.absent", "{0} precisa de {1}, que não está instalado",
			"bullet.version", "{0} precisa de {1} {2}, e você tem a {3}",
			"bullet.mixin", "{0} não conseguiu se encaixar no mod para o qual foi feito",
			"fix.header", "O que pode resolver:",
			"fix.install", "Instale {0}. {1} é um mod {2}, então a versão para {2} é a mais segura de pegar - no "
					+ "Forbric, uma versão feita para outro loader também pode servir.",
			"fix.version", "Troque {0} por uma versão que corresponda a {1}. Você tem a {2}.",
			"fix.mixin", "Os dois mods estão instalados e não falta nada para nenhum deles - só as versões é que "
					+ "não combinam. Uma versão de {0} lançada mais ou menos na mesma época do mod ao qual ele se "
					+ "encaixa pode resolver.",
			"fix.remove", "Ou tire {0} da sua pasta mods. O Forbric continua carregando todo o resto, então os seus "
					+ "outros mods seguem funcionando.",
			"note.deps", "O Forbric inicia mesmo assim, se você pedir. Um mod cujo requisito não foi atendido "
					+ "costuma falhar bem mais tarde, em um erro que não cita nenhum dos dois mods - um mundo "
					+ "vazio, um bloco que sumiu ou um travamento ao criar um mundo - então vale a pena resolver "
					+ "antes de jogar.",
			"note.mixins", "Nada aponta isso como uma dependência faltando, porque não é: os dois mods estão "
					+ "instalados e cada um está dentro da faixa de versões que o outro pede. As duas versões "
					+ "simplesmente não se encaixam.",
			"details.deps.header", "Requisitos não atendidos",
			"details.mixins.header", "Mods que não conseguiram se encaixar",
			"details.by", "{0}  ({1}, {2})",
			"details.needs.absent", "precisa de {0} {1}  —  NÃO INSTALADO",
			"details.needs.version", "precisa de {0} {1}  —  instalado: {2}",
			"details.search", "buscar: {0}",
			"details.mixin", "{0}  —  {1}",
			"details.anchors", "não foi possível encontrar {0}",
			"isolation.title", "Forbric — o jogo travou da última vez",
			"isolation.intro", "O jogo travou da última vez que foi aberto, e o travamento aponta para estes mods:",
			"isolation.intro.clash", "O jogo travou da última vez porque estes mods entram em conflito com {0}. O "
					+ "Forbric pode manter {0} e iniciar sem:",
			"isolation.bullet", "{0}  ({1})",
			"isolation.without", "Iniciar sem eles grava os nomes dos arquivos em forbric-disabled.txt, ao lado da sua "
					+ "pasta mods, e o Forbric não os carrega. Os arquivos ficam onde estão. Para reativar um, apague a "
					+ "linha dele em forbric-disabled.txt.",
			"isolation.note", "Isto é um palpite: o travamento aponta para estes mods, o que não prova que a culpa é "
					+ "deles. Fechar esta janela inicia o jogo com todos os mods, como antes.",
			"isolation.details.report", "O relatório do travamento está em crash-reports/{0}; a análise, em "
					+ ".forbric-kernel/crash-analysis.txt.",
			"button.isolation.without", "Iniciar sem {0}",
			"button.isolation.everything", "Iniciar com tudo",
			"unsupported.forge.feature", "Mod de MinecraftForge",
			"unsupported.forge.detail", "{0} ({1}) é um mod de MinecraftForge. O Forbric não oferece mais suporte ao "
					+ "MinecraftForge, então ele não é carregado. Use a versão de {0} para NeoForge ou Fabric.",
			"unsupported.forge.bundled", "{0} ({1}) é uma versão de MinecraftForge incluída em outro mod. O Forbric não "
					+ "oferece mais suporte ao MinecraftForge, então ela fica apenas como biblioteca.",
			"report.unsupported.what", "não carregado: o Forbric não oferece mais suporte ao MinecraftForge",
			"report.unsupported.fix", "Baixe a versão de {0} para NeoForge ou Fabric e coloque-a na pasta mods no lugar "
					+ "de {1}.",
			"report.unsupported.advice", "Agora o Forbric carrega apenas mods de Fabric e NeoForge. A maioria dos mods "
					+ "publica uma versão para NeoForge ou Fabric da mesma versão do Minecraft; use essa.",
			"details.log", "As mesmas informações estão em logs/latest.log, sob [Forbric/Deps]."));
}
