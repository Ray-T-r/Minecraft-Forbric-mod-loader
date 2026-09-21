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

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Window;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * The dialog, as a separate process's {@code main}.
 *
 * <p><b>Why a separate process.</b> On macOS the game JVM is started with {@code -XstartOnFirstThread} (see
 * {@code run/launch-kernel-client.sh}), which GLFW requires and which AWT cannot coexist with: both want thread
 * one.
 *
 * <p>Fabric Loader forks there too, and it is worth being exact about what it keys off, because the obvious
 * reading is wrong. With Minecraft's game provider registered it never inspects the environment at all:
 * {@code MinecraftGameProvider.hasAwtSupport()} is {@code !LoaderUtil.hasMacOs()} and nothing more
 * ({@code invokestatic hasMacOs / ifne 10 / iconst_1 ... iconst_0 / ireturn}), so the decision is
 * {@code os.name} alone. Its scan for an environment key beginning {@code JAVA_STARTED_ON_FIRST_THREAD_} lives
 * in {@code LoaderUtil.hasAwtSupport()}, which {@code FabricGuiEntry.open} reaches only while no provider has
 * been established yet. That environment variable is real — measured here, a JVM started with the flag has
 * {@code JAVA_STARTED_ON_FIRST_THREAD_<pid>} and one started without it does not — it is simply not the trigger
 * on the path that matters. The technique is Fabric's either way, read out of the bytecode rather than copied.
 *
 * <p>Forbric forks ALWAYS, on every platform, rather than only where it must. A dialog costs a JVM start only on
 * the boots that have something to report, which are rare, and in exchange there is ONE code path instead of two.
 * The alternative — in-process where it is safe, forked where it is not — is the shape that has to be right on
 * three operating systems and is only ever exercised on whichever one the author had.
 *
 * <p>The one AWT class the game's own process touches is {@code GraphicsEnvironment.isHeadless()}, in
 * {@link DependencyDialog}'s guard. Measured on a JVM started with {@code -XstartOnFirstThread}: it returns in
 * about 12ms and starts no {@code AWT-} thread, so the guard cannot be the thing that breaks the window it
 * guards. That is a measurement of that one call, not a claim that AWT is never loaded.
 *
 * <p>Exit code IS the answer: {@code 0} continue, {@code 1} quit. Anything else the parent reads as continue,
 * because a dialog that fails must not be able to stop a launch that would otherwise have worked.
 *
 * <h2>What the player is shown</h2>
 *
 * <p>Three layers, in the order a player needs them:
 *
 * <ol>
 *   <li><b>What is wrong</b>, in their own language and in their own words — which mod, and what it wanted.
 *       {@link DialogLang} holds the words.</li>
 *   <li><b>What might fix it.</b> A warning that does not say what to do next leaves the reader with nothing but
 *       the feeling that something is broken. Every suggestion here is derived from what the kernel actually
 *       measured — the required id, the declared range, the version that is installed, the ecosystem the
 *       dependent belongs to — and is worded as a possibility, because that is all any of it is.</li>
 *   <li><b>The technical detail</b>, behind {@code Show details} and hidden by default. Mixin class names,
 *       unresolved anchors and version ranges are what a mod author or a bug report needs, and they are the
 *       reason the previous version of this dialog opened as a wall of text that a player would close without
 *       reading.</li>
 * </ol>
 *
 * <h2>Headless</h2>
 *
 * <p>EVERY line that touches a window — building the components, sizing against the screen, creating the dialog,
 * showing it — is inside one try that answers {@link #CONTINUE}. Nothing about this is decoration: an uncaught
 * {@code HeadlessException} leaves the JVM with exit code 1, the parent reads 1 as {@link #QUIT}, and a client
 * with no display would quit the game on the player's behalf — an inversion of the exact policy this whole
 * feature exists to uphold.
 */
public final class DependencyDialogMain {
	/** The player chose to launch anyway. */
	public static final int CONTINUE = 0;
	/** The player chose to quit and go install something. */
	public static final int QUIT = 1;

	/**
	 * How many findings the summary names before it hands the rest to the details.
	 *
	 * <p>A pack with forty unmet requirements would otherwise produce a summary that is itself the wall of text
	 * the details button exists to put away.
	 */
	static final int SUMMARY_BULLETS = 6;

	/** The width the wrapped player-facing text is laid out at, in pixels before HiDPI scaling. */
	private static final int TEXT_WIDTH = 640;

	/**
	 * What marks a line as a list item.
	 *
	 * <p>A marker inside the text, read back by {@link #block}, rather than a structure the builders return. The
	 * text is what the tests assert on and what a bug report can be pasted from; the layout is one reading of it.
	 */
	private static final String BULLET = "  • ";

	/** The continuation of a capped list: indented like a bullet, but not one. */
	private static final String MORE = "    ";

	/** The width of a list item's marker column, so its wrapped lines start under its first line. */
	private static final int MARKER_WIDTH = 26;

	private DependencyDialogMain() {
	}

	/**
	 * Takes Java2D off the Direct3D pipeline on Windows before anything is drawn.
	 *
	 * <p>Measured, not guessed: on the machine that reported this, the Windows look and feel hands Swing an
	 * ordinary palette — panel 240/240/240, text area white, text black — and this dialog sets no colour of its
	 * own, yet it painted itself yellow with blue and red text. Nothing computed those colours; they were painted
	 * wrong, which is a rendering-pipeline fault rather than a theming one.
	 *
	 * <p>The installer already carries this exact workaround, for the same symptom in the other window, so this is
	 * a known hazard on this platform rather than a hunch. Only when the caller has no opinion, and only on
	 * Windows, so it never overrides a deliberate {@code -Dsun.java2d.d3d}.
	 *
	 * <p>The cost is software rendering for one modal warning, which nothing animates.
	 */
	private static void avoidOverlayRenderingCorruption() {
		if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) return;
		if (System.getProperty("sun.java2d.d3d") != null) return;
		System.setProperty("sun.java2d.d3d", "false");
	}

	public static void main(String[] args) {
		avoidOverlayRenderingCorruption();
		if (args.length < 1) System.exit(CONTINUE);
		List<DependencyReport.Row> rows;
		List<DependencyReport.MixinRow> mixins;
		try {
			rows = DependencyReport.read(Path.of(args[0]));
			mixins = DependencyReport.readMixins(Path.of(args[0]));
		} catch (Throwable unreadable) {
			System.exit(CONTINUE);
			return;
		}
		if (rows.isEmpty() && mixins.isEmpty()) System.exit(CONTINUE);

		DialogLang lang = DialogLang.ofSystem();
		try {
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
		} catch (Throwable ignored) {
			// The cross-platform look and feel is not worth failing a warning over.
		}

		int answer;
		try {
			answer = askOnEventThread(lang, rows, mixins);
		} catch (Throwable noDisplay) {
			// The single net. See the class note: anything other than CONTINUE here would be the kernel quitting
			// the game for a player who was never asked.
			System.exit(CONTINUE);
			return;
		}
		System.exit(answer);
	}

	/**
	 * Builds and shows the dialog on the event thread, and returns the exit code it earned.
	 *
	 * <p>On the EDT rather than on {@code main} because the details toggle mutates the component hierarchy after
	 * it is realised, and a listener firing on the EDT while {@code main} is still reading those same components
	 * is the classic Swing race. The old dialog got away with building off-thread only because nothing ever
	 * changed after it was shown.
	 */
	private static int askOnEventThread(DialogLang lang, List<DependencyReport.Row> rows,
			List<DependencyReport.MixinRow> mixins) throws Exception {
		int[] answer = { CONTINUE };
		SwingUtilities.invokeAndWait(() -> answer[0] = show(lang, rows, mixins));
		return answer[0];
	}

	private static int show(DialogLang lang, List<DependencyReport.Row> rows,
			List<DependencyReport.MixinRow> mixins) {
		String summary = summary(lang, rows, mixins);
		String fixes = fixes(lang, rows, mixins);
		String notes = notes(lang, rows, mixins);
		String detail = details(lang, rows, mixins);

		Font prose = legible(summary + fixes + notes, 13, false);
		JPanel content = new JPanel(new BorderLayout(0, 12));
		content.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

		// What is wrong, then what to do about it, then the caveat. A player who reads the first two blocks has
		// read the useful part; the previous version opened with the paragraph about what happens if they ignore
		// it, which is the part that matters least and took the most room.
		Box head = Box.createVerticalBox();
		head.add(leftAligned(block(summary, prose)));
		head.add(Box.createVerticalStrut(14));
		head.add(leftAligned(block(fixes, prose)));
		head.add(Box.createVerticalStrut(14));
		head.add(leftAligned(block(notes, prose)));
		head.add(Box.createVerticalStrut(14));

		JButton toggle = new JButton(lang.get("button.details.show"));
		head.add(leftAligned(hugging(toggle)));
		content.add(head, BorderLayout.NORTH);

		// Added now and hidden. BorderLayout skips an invisible child entirely when it measures, so the collapsed
		// dialog is exactly the size it would be if the details did not exist — which is the whole point of
		// building it up front rather than swapping a component in later.
		JScrollPane details = detailsPane(detail);
		details.setVisible(false);
		content.add(details, BorderLayout.CENTER);

		Object[] options = options(lang);
		JOptionPane pane = new JOptionPane(content, JOptionPane.WARNING_MESSAGE, JOptionPane.DEFAULT_OPTION,
				null, options, initialOption(lang));
		JDialog dialog = pane.createDialog(null, title(lang, rows, mixins));
		// createDialog fixes the size; forty findings want a window the player can drag bigger.
		dialog.setResizable(true);

		toggle.addActionListener(event -> {
			boolean showing = !details.isVisible();
			details.setVisible(showing);
			toggle.setText(lang.get(showing ? "button.details.hide" : "button.details.show"));
			Rectangle was = dialog.getBounds();
			content.revalidate();
			// pack(), not validate(): validate re-lays-out the children inside the size the window already has,
			// which is the size of a dialog that had no details in it.
			dialog.pack();
			reseat(dialog, was);
		});

		// pack() keeps the TOP-LEFT corner fixed. A dialog centred at y≈380 that grows by 420px puts its answer
		// buttons below the bottom of the screen, where on Windows there is no way back without the keyboard.
		// Done once before the first show as well, for a collapsed dialog that is already too tall — a long
		// summary in a CJK language at 300% scaling.
		reseat(dialog, dialog.getBounds());
		dialog.setVisible(true);
		dialog.dispose();

		// Compared against the OPTION OBJECT, never an index. The options are translated, so the only comparison
		// that cannot drift with the language is the one against the value that was put in the array. Everything
		// that is not exactly the quit option — a closed window, Escape, an uninitialised value — means continue,
		// which is the same fail-open the exit code has always had.
		return options[1].equals(pane.getValue()) ? QUIT : CONTINUE;
	}

	/**
	 * The two answers, in the order they are offered.
	 *
	 * <p>Index 0 CONTINUES and index 1 QUITS, and {@link #initialOption} returns element 0 by construction rather
	 * than by a second literal that could drift from it. The keyboard default must never be the one that quits:
	 * the first screenshot of this dialog had Quit highlighted, so a player holding Enter would have lost the
	 * launch — the opposite of the policy the whole feature is built on. That invariant now has to hold in ten
	 * languages, which is why it is a method a test can call rather than a line a test can grep.
	 */
	static Object[] options(DialogLang lang) {
		return new Object[] { lang.get("button.continue"), lang.get("button.quit") };
	}

	/** @see #options */
	static Object initialOption(DialogLang lang) {
		return options(lang)[0];
	}

	/**
	 * The window title, which has to match what is actually in the dialog.
	 *
	 * <p>A fixed "a mod is missing something it requires" is a lie on a run where the only finding is a mixin
	 * that did not attach: both mods are installed and neither is missing anything. A player who reads the title
	 * and stops there would go looking for a download that does not exist.
	 */
	static String title(DialogLang lang, List<DependencyReport.Row> rows,
			List<DependencyReport.MixinRow> mixins) {
		if (rows.isEmpty()) return lang.get("title.mixins");
		if (mixins.isEmpty()) return lang.get("title.deps");
		return lang.get("title.both");
	}

	/**
	 * What is wrong, in one screen.
	 *
	 * <p>Names the mod the player recognises and the id it wanted, and nothing else — no ecosystem, no version
	 * range, no mixin class. Those are all true and all in the details; none of them is what a player needs in
	 * the first five seconds, and every one of them made the old dialog look like a stack trace.
	 */
	static String summary(DialogLang lang, List<DependencyReport.Row> rows,
			List<DependencyReport.MixinRow> mixins) {
		StringBuilder text = new StringBuilder();
		if (!rows.isEmpty()) {
			text.append(rows.size() == 1 ? lang.get("summary.deps.one")
					: lang.get("summary.deps.many", rows.size())).append("\n\n");
			int shown = Math.min(rows.size(), SUMMARY_BULLETS);
			for (int i = 0; i < shown; i++) {
				DependencyReport.Row row = rows.get(i);
				text.append(BULLET).append(row.absent()
						? lang.get("bullet.absent", row.requiredByName(), row.requiredId())
						: lang.get("bullet.version", row.requiredByName(), row.requiredId(), row.requiredRange(),
								row.installedVersion())).append('\n');
			}
			if (rows.size() > shown) {
				text.append(MORE).append(lang.get("summary.more", rows.size() - shown)).append('\n');
			}
		}
		if (!mixins.isEmpty()) {
			if (text.length() > 0) text.append('\n');
			text.append(mixins.size() == 1 ? lang.get("summary.mixins.one")
					: lang.get("summary.mixins.many", mixins.size())).append("\n\n");
			int shown = Math.min(mixins.size(), SUMMARY_BULLETS);
			for (int i = 0; i < shown; i++) {
				text.append(BULLET).append(lang.get("bullet.mixin", mixins.get(i).owner())).append('\n');
			}
			if (mixins.size() > shown) {
				text.append(MORE).append(lang.get("summary.more", mixins.size() - shown)).append('\n');
			}
		}
		return text.toString();
	}

	/**
	 * What happens if they ignore it, and — for a mixin break — why nothing else reported it.
	 *
	 * <p>Last, under the suggestions. It is the part a player who reads two lines and acts does not need, and it
	 * is what the previous dialog opened with.
	 */
	static String notes(DialogLang lang, List<DependencyReport.Row> rows,
			List<DependencyReport.MixinRow> mixins) {
		StringBuilder text = new StringBuilder();
		if (!rows.isEmpty()) text.append(lang.get("note.deps")).append('\n');
		if (!mixins.isEmpty()) {
			if (text.length() > 0) text.append('\n');
			text.append(lang.get("note.mixins")).append('\n');
		}
		return text.toString();
	}

	/**
	 * What the player can actually do about it.
	 *
	 * <p>Every line is derived from something the kernel measured, and every line is worded as a possibility. The
	 * kernel knows a mod id is not installed; it does not know that installing it fixes the pack, and a dialog
	 * that promised so would be wrong the first time a player's problem was something else.
	 *
	 * <p>The ecosystem in the install suggestion is the ecosystem of the mod that DECLARED the requirement, not
	 * of the mod being suggested — nobody knows the latter, because it is not installed. So it is offered as the
	 * safest guess and immediately qualified: on a merged instance a Fabric build really can satisfy a Forge
	 * mod's requirement, which is the one fact about this that no single-ecosystem loader could tell them.
	 *
	 * <p>The last line — take it out of the mods folder — is the only suggestion here that is certain to work,
	 * and it is last because it costs the player the mod.
	 */
	static String fixes(DialogLang lang, List<DependencyReport.Row> rows,
			List<DependencyReport.MixinRow> mixins) {
		List<String> lines = new ArrayList<>();
		for (DependencyReport.Row row : rows) {
			if (lines.size() >= SUMMARY_BULLETS) break;
			lines.add(row.absent()
					? lang.get("fix.install", row.requiredId(), row.requiredByName(), row.ecosystem())
					: lang.get("fix.version", row.requiredId(), row.requiredRange(), row.installedVersion()));
		}
		for (DependencyReport.MixinRow row : mixins) {
			if (lines.size() >= SUMMARY_BULLETS) break;
			lines.add(lang.get("fix.mixin", row.owner()));
		}
		lines.add(lang.get("fix.remove", affected(rows, mixins)));

		StringBuilder text = new StringBuilder(lang.get("fix.header")).append("\n\n");
		for (String line : lines) text.append(BULLET).append(line).append('\n');
		return text.toString();
	}

	/**
	 * The mods a player would be taking out, named.
	 *
	 * <p>Three at most, with an ellipsis when there are more. Ellipsis rather than a translated "and N others"
	 * because the list is already a hint and the full one is two clicks away in the details; a count that has to
	 * agree with the list in ten grammars is a translation bug waiting to happen.
	 */
	private static String affected(List<DependencyReport.Row> rows, List<DependencyReport.MixinRow> mixins) {
		LinkedHashSet<String> names = new LinkedHashSet<>();
		for (DependencyReport.Row row : rows) names.add(row.requiredByName());
		for (DependencyReport.MixinRow row : mixins) names.add(row.owner());
		List<String> named = new ArrayList<>(names);
		if (named.size() <= 3) return String.join(", ", named);
		return String.join(", ", named.subList(0, 3)) + " …";
	}

	/**
	 * Everything the summary left out: ids, ecosystems, exact ranges, mixin classes and the anchors that did not
	 * resolve, plus where to search for the download and where the same findings are in the log.
	 *
	 * <p>Hidden behind a button, not deleted. A player does not need it; the person they paste it to does.
	 */
	static String details(DialogLang lang, List<DependencyReport.Row> rows,
			List<DependencyReport.MixinRow> mixins) {
		StringBuilder text = new StringBuilder();
		if (!rows.isEmpty()) {
			text.append(lang.get("details.deps.header")).append("\n\n");
			for (DependencyReport.Row row : rows) {
				text.append("  ").append(lang.get("details.by", row.requiredByName(), row.requiredBy(),
						row.ecosystem())).append('\n');
				text.append("      ").append(row.absent()
						? lang.get("details.needs.absent", row.requiredId(), row.requiredRange())
						: lang.get("details.needs.version", row.requiredId(), row.requiredRange(),
								row.installedVersion())).append('\n');
				for (String url : searchUrls(row.requiredId())) {
					text.append("      ").append(lang.get("details.search", url)).append('\n');
				}
				text.append('\n');
			}
		}
		if (!mixins.isEmpty()) {
			text.append(lang.get("details.mixins.header")).append("\n\n");
			for (DependencyReport.MixinRow row : mixins) {
				text.append("  ").append(lang.get("details.mixin", row.owner(), row.mixin())).append('\n');
				text.append("      ").append(lang.get("details.anchors", row.anchors())).append('\n');
				text.append('\n');
			}
		}
		text.append(lang.get("details.log")).append('\n');
		return text.toString();
	}

	/**
	 * Where to go looking for a mod id.
	 *
	 * <p>Search pages, not mod pages: the kernel knows an id, and an id is not a slug on either site. A search
	 * URL is a thing that is true — it takes them to the search — where a guessed mod page would be a link that
	 * is wrong more often than not. Both sites, because a great many Forge mods have never been on Modrinth.
	 */
	static List<String> searchUrls(String modId) {
		String query = URLEncoder.encode(modId == null ? "" : modId, StandardCharsets.UTF_8).replace("+", "%20");
		return List.of("https://modrinth.com/mods?q=" + query,
				"https://www.curseforge.com/minecraft/search?search=" + query);
	}

	// -------------------------------------------------------------------------------------------------------
	// Swing plumbing.
	// -------------------------------------------------------------------------------------------------------

	/**
	 * One block of the dialog, laid out so that a list looks like a list.
	 *
	 * <p>Reads {@link #BULLET} back out of the text and gives those lines a marker column, so a suggestion that
	 * wraps onto a second line is indented under the first instead of starting again at the margin. A
	 * {@code JTextArea} cannot hang-indent, and a suggestion is usually two lines long — which is exactly the
	 * case that looked broken.
	 */
	private static Component block(String text, Font font) {
		Box box = Box.createVerticalBox();
		StringBuilder paragraph = new StringBuilder();
		for (String line : text.split("\n", -1)) {
			if (line.startsWith(BULLET)) {
				flush(box, paragraph, font);
				box.add(leftAligned(item("\u2022", line.substring(BULLET.length()), font)));
			} else if (line.startsWith(MORE) && !line.isBlank()) {
				flush(box, paragraph, font);
				box.add(leftAligned(item(" ", line.strip(), font)));
			} else {
				paragraph.append(line).append('\n');
			}
		}
		flush(box, paragraph, font);
		return box;
	}

	/** Emits whatever prose has accumulated, or a gap when all that accumulated was blank lines. */
	private static void flush(Box box, StringBuilder paragraph, Font font) {
		String raw = paragraph.toString();
		paragraph.setLength(0);
		if (raw.isEmpty()) return;
		String text = raw.strip();
		// A blank line that opened this run is a paragraph break the stripped text can no longer show. Stripping
		// is not optional — a trailing newline inside a JTextArea is a visible empty row — so the break has to be
		// carried out here, as a gap, or a section heading ends up glued to the list above it.
		if (box.getComponentCount() > 0 && (text.isEmpty() || raw.startsWith("\n"))) {
			box.add(Box.createVerticalStrut(10));
		}
		if (!text.isEmpty()) box.add(leftAligned(wrapped(text, TEXT_WIDTH, font)));
	}

	/** A marker in its own column, and the text wrapped beside it. */
	private static JPanel item(String marker, String text, Font font) {
		JPanel row = new JPanel(new BorderLayout(0, 0));
		row.setOpaque(false);
		javax.swing.JLabel mark = new javax.swing.JLabel(marker);
		mark.setFont(font);
		mark.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 6));
		mark.setVerticalAlignment(javax.swing.SwingConstants.TOP);
		row.add(mark, BorderLayout.WEST);
		JTextArea body = wrapped(text, TEXT_WIDTH - MARKER_WIDTH, font);
		row.add(body, BorderLayout.CENTER);
		row.setPreferredSize(new Dimension(TEXT_WIDTH, body.getPreferredSize().height));
		row.setMaximumSize(row.getPreferredSize());
		return row;
	}

	/**
	 * A read-only, wrapping block of text that reads as a label rather than as a field.
	 *
	 * <p>{@code JTextArea} and never an HTML {@code JLabel}. Mod display names come out of a third party's
	 * {@code fabric.mod.json} or {@code mods.toml}, and {@link DependencyReport}'s sanitiser removes only tabs
	 * and newlines — {@code <} and {@code &} pass through. Swing's {@code HTMLEditorKit} would read a name
	 * containing {@code <} as a tag and swallow the rest of the sentence, and it honours {@code <img src>}, which
	 * would let a mod name make this dialog fetch a URL on the event thread. The mods this dialog is reporting on
	 * are precisely the ones least worth trusting with that.
	 *
	 * <p>The width is forced before the height is read. With wrapping on, a {@code JTextArea}'s preferred height
	 * is computed against its current width, which is zero before layout — so {@code pack()} on an unsized one
	 * produces either a single line or a window wider than the screen.
	 */
	private static JTextArea wrapped(String text, int width, Font font) {
		JTextArea area = new JTextArea(text);
		area.setEditable(false);
		area.setFocusable(false);
		area.setLineWrap(true);
		area.setWrapStyleWord(true);
		area.setOpaque(false);
		area.setBorder(null);
		area.setFont(font);
		area.setSize(new Dimension(width, Short.MAX_VALUE));
		area.setPreferredSize(new Dimension(width, area.getPreferredSize().height));
		area.setMaximumSize(area.getPreferredSize());
		return area;
	}

	/** The details, scrollable and never taller than a bit over half the usable screen. */
	private static JScrollPane detailsPane(String text) {
		JTextArea area = new JTextArea(text);
		area.setEditable(false);
		// Not wrapped: these rows are aligned columns, and re-flowing them destroys the alignment that is the
		// only reason they are laid out this way. It scrolls sideways instead.
		area.setLineWrap(false);
		area.setFont(legible(text, 12, true));
		area.setCaretPosition(0);

		JScrollPane scroll = new JScrollPane(area);
		Rectangle usable = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
		int cap = Math.max(200, (int) (usable.height * 0.5));
		Dimension want = area.getPreferredSize();
		scroll.setPreferredSize(new Dimension(TEXT_WIDTH + 40, Math.min(want.height + 8, cap)));
		// The default is one pixel per wheel notch, which on a forty-finding report is unusable.
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		return scroll;
	}

	/**
	 * A font that can actually draw {@code sample}.
	 *
	 * <p>Asked rather than assumed. The logical fonts are composites whose fallback list comes from the JDK's
	 * font configuration for the HOST locale, so {@code Monospaced} on a Windows box with an English system
	 * locale can resolve to a font with no CJK component — and a Japanese dialog then renders as boxes. Which
	 * platform has which fallback is not worth reasoning about when the font will answer definitively in
	 * microseconds.
	 *
	 * <p>The sample is the whole text, mod names included: one Cyrillic mod title in an otherwise English run is
	 * enough to produce boxes, and that is exactly the case a guess based on the chosen language would miss.
	 */
	private static Font legible(String sample, int size, boolean preferMono) {
		if (preferMono) {
			Font mono = new Font(Font.MONOSPACED, Font.PLAIN, size);
			if (mono.canDisplayUpTo(sample) < 0) return mono;
		}
		Font ui = UIManager.getFont("Label.font");
		// Derived rather than constructed, so the size the system look and feel picked for this display's
		// scaling survives — a hard 12pt is about four millimetres tall at 250% Windows scaling.
		if (ui != null && ui.canDisplayUpTo(sample) < 0) return ui.deriveFont(Font.PLAIN, ui.getSize2D());
		Font dialog = new Font(Font.DIALOG, Font.PLAIN, size);
		if (dialog.canDisplayUpTo(sample) < 0) return dialog;
		return ui != null ? ui.deriveFont(Font.PLAIN, ui.getSize2D()) : dialog;
	}

	private static Component leftAligned(Component component) {
		if (component instanceof javax.swing.JComponent swing) swing.setAlignmentX(Component.LEFT_ALIGNMENT);
		return component;
	}

	/** A button in a box layout stretches to the full width unless something holds it to its own size. */
	private static JPanel hugging(Component component) {
		JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		panel.setOpaque(false);
		panel.add(component);
		panel.setMaximumSize(new Dimension(Short.MAX_VALUE, panel.getPreferredSize().height));
		return panel;
	}

	/**
	 * Puts a window that has just been packed back somewhere the player can reach all of it.
	 *
	 * <p>Keeps the horizontal centre and the top edge, pushes up only when the bottom would leave the screen, and
	 * clamps to {@code getMaximumWindowBounds} — which already subtracts the Windows taskbar and the macOS menu
	 * bar and Dock, where a raw screen size would not.
	 */
	private static void reseat(Window window, Rectangle was) {
		Rectangle usable = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
		int width = Math.min(window.getWidth(), usable.width);
		int height = Math.min(window.getHeight(), usable.height);
		if (width != window.getWidth() || height != window.getHeight()) window.setSize(width, height);

		int x = was.x + was.width / 2 - width / 2;
		int y = was.y;
		if (y + height > usable.y + usable.height) y = usable.y + usable.height - height;
		x = Math.max(usable.x, Math.min(x, usable.x + usable.width - width));
		y = Math.max(usable.y, Math.min(y, usable.y + usable.height - height));
		window.setLocation(x, y);
		window.validate();
	}
}
