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

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/** The window: pick a game version, a loader version and a directory, then press Install. */
final class InstallerGui {
	static final String DEFAULT_VERSION = "26.2";

	private final JFrame frame = new JFrame("Forbric Installer");
	private final JComboBox<String> gameVersion = new JComboBox<>(new String[] {DEFAULT_VERSION});
	private final JComboBox<String> loaderVersion = new JComboBox<>(new String[] {loaderVersion()});
	private final JTextField directory = new JTextField(Util.defaultMinecraftDir().toString());
	private final JTextField artifacts = new JTextField();
	private final JTextArea log = new JTextArea(12, 64);
	private final JButton install = new JButton("Install");

	private InstallerGui() {
	}

	static void open() {
		SwingUtilities.invokeLater(() -> new InstallerGui().show());
	}

	private void show() {
		frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
		frame.setLayout(new BorderLayout(0, 8));

		// The form belongs with the header, not in CENTER. CENTER is the slot BorderLayout gives the leftover
		// space to — and takes it back from first when there is none, which clipped the last row ("Built
		// artifacts" and its Browse button) right off the bottom. The log is the thing that should grow.
		JPanel top = new JPanel(new BorderLayout(0, 8));
		top.add(header(), BorderLayout.NORTH);
		top.add(form(), BorderLayout.CENTER);

		frame.add(top, BorderLayout.NORTH);
		frame.add(logPane(), BorderLayout.CENTER);
		frame.add(buttons(), BorderLayout.SOUTH);
		frame.pack();
		// Wide enough that the header reads in full: it is one line and JLabel ellipsises rather than wraps, so
		// a window narrower than the sentence silently truncates it to "… NeoForge mods i…".
		Dimension packed = frame.getSize();
		frame.setMinimumSize(new Dimension(Math.max(760, packed.width), packed.height));
		frame.setSize(frame.getMinimumSize());
		frame.setLocationRelativeTo(null);
		frame.setVisible(true);
	}

	private JPanel header() {
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 0, 12));
		JLabel title = new JLabel(InstallerText.title());
		title.setFont(title.getFont().deriveFont(title.getFont().getSize2D() + 3f));
		// BoxLayout positions a child by its own alignmentX, and these two did not agree: the title floated
		// while the paragraph below it sat left, which is why the heading appeared shoved to one side.
		title.setAlignmentX(Component.LEFT_ALIGNMENT);
		panel.add(title);
		panel.add(Box.createVerticalStrut(6));
		// This used to say the game base and runtimes "are built on this machine ... so point it at the directory
		// holding them", from before the installer could build them itself. A player read that as "supply three
		// files", filled Built artifacts with unrelated jars, and got a game that died on its first NeoForge class
		// (#13). The one thing a player has to choose is the game directory, so that is all this asks for.
		JTextArea intro = new JTextArea("Installs a version your usual launcher can start. Choose your game "
				+ "directory and press Install: everything else is downloaded and built on this computer while it "
				+ "installs. Leave \"Built artifacts\" empty; it is only for developers.");
		intro.setEditable(false);
		intro.setLineWrap(true);
		intro.setWrapStyleWord(true);
		intro.setOpaque(false);
		intro.setBorder(null);
		intro.setFocusable(false);
		intro.setAlignmentX(Component.LEFT_ALIGNMENT);
		intro.setFont(title.getFont().deriveFont(title.getFont().getSize2D() - 3f));
		panel.add(intro);
		return panel;
	}

	private JPanel form() {
		JPanel panel = new JPanel(new GridBagLayout());
		panel.setBorder(BorderFactory.createEmptyBorder(8, 12, 0, 12));
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(4, 0, 4, 8);
		c.anchor = GridBagConstraints.LINE_START;

		int row = 0;
		addRow(panel, c, row++, "Game version", gameVersion, null);
		addRow(panel, c, row++, "Loader version", loaderVersion, null);
		addRow(panel, c, row++, "Game directory", directory, this::chooseDirectory);
		// Said on the label itself, not only in a tooltip: a tooltip is what nobody hovers over before typing.
		addRow(panel, c, row, "Built artifacts (leave empty)", artifacts, this::chooseArtifacts,
				"<html>Leave this empty: the installer downloads and builds the game files it needs.<br>"
						+ "Only for developers who already built Forbric's merged game base and both Forge<br>"
						+ "runtimes from source, to skip that build.</html>");
		return panel;
	}

	private void addRow(JPanel panel, GridBagConstraints c, int row, String label,
			javax.swing.JComponent field, Runnable browse) {
		addRow(panel, c, row, label, field, browse, null);
	}

	private void addRow(JPanel panel, GridBagConstraints c, int row, String label,
			javax.swing.JComponent field, Runnable browse, String tooltip) {
		c.gridx = 0;
		c.gridy = row;
		c.weightx = 0;
		c.fill = GridBagConstraints.NONE;
		JLabel name = new JLabel(label);
		if (tooltip != null) {
			name.setToolTipText(tooltip);
			field.setToolTipText(tooltip);
		}
		panel.add(name, c);

		c.gridx = 1;
		c.weightx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		panel.add(field, c);

		c.gridx = 2;
		c.weightx = 0;
		c.fill = GridBagConstraints.NONE;
		if (browse != null) {
			JButton button = new JButton("Browse…");
			button.addActionListener(e -> browse.run());
			panel.add(button, c);
		} else {
			panel.add(Box.createHorizontalStrut(0), c);
		}
	}

	/** The log, which is the one thing here that should take the leftover space. */
	private JScrollPane logPane() {
		log.setEditable(false);
		JScrollPane pane = new JScrollPane(log);
		pane.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
		pane.setPreferredSize(new Dimension(0, 240));
		return pane;
	}

	private JPanel buttons() {
		JPanel panel = new JPanel(new BorderLayout());
		panel.setBorder(BorderFactory.createEmptyBorder(8, 12, 12, 12));
		install.addActionListener(e -> runInstall());
		panel.add(install, BorderLayout.LINE_END);
		return panel;
	}

	private void chooseDirectory() {
		choose(directory, "Choose the Minecraft directory");
	}

	private void chooseArtifacts() {
		choose(artifacts, "Developers only: the directory holding a merged game base and both runtimes you built");
	}

	private void choose(JTextField field, String title) {
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle(title);
		chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		String current = Util.unquote(field.getText().trim());
		if (!current.isEmpty()) chooser.setCurrentDirectory(new File(current));
		if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
			field.setText(chooser.getSelectedFile().getAbsolutePath());
		}
	}

	private void runInstall() {
		install.setEnabled(false);
		log.setText("");
		transientStart = -1;   // the cleared document has no live line in it any more
		// Only the text is read here. Turning it into paths can fail, and that has to happen on the install thread,
		// where a failure reaches the log (see installFromFields).
		String mcVersion = String.valueOf(gameVersion.getSelectedItem());
		String dirText = directory.getText();
		String artifactText = artifacts.getText();

		new Thread(() -> {
			try {
				installFromFields(mcVersion, dirText, artifactText, this::append);
			} finally {
				SwingUtilities.invokeLater(() -> install.setEnabled(true));
			}
		}, "forbric-install").start();
	}

	/**
	 * What Install does, given the text of the window's fields: every way it can fail ends as a line in
	 * {@code log}.
	 *
	 * <p>The two path fields used to be read on the event thread, before the install thread started and outside
	 * its try. {@link java.nio.file.Paths#get} refuses some text outright — on Windows, any path pasted from
	 * Explorer's "Copy as path", which wraps it in quotes — and that exception went to the event thread's
	 * handler, which prints to stderr, which the .bat's javaw throws away. The log stayed empty and the button
	 * stayed grey: Install looked like it did nothing at all.
	 */
	static void installFromFields(String mcVersion, String dirText, String artifactText, Consumer<String> log) {
		try {
			Path dir = fieldPath("Game directory", dirText);
			if (dir == null) {
				throw new IOException("Game directory is empty. Choose the folder your launcher keeps the game in"
						+ " (usually called .minecraft), then press Install again.");
			}
			Path artifactDir = fieldPath("Built artifacts", artifactText);
			// The window needs the release for exactly the reason the CLI does: a slim installer carries no
			// jars, and without a source for them the only thing the button can do is fail.
			RemoteSource remote = RemoteSource.create(new Http(log), log, null, null, false);
			new Installer(log).install(dir, mcVersion, artifactDir, null, remote);
		} catch (Exception e) {
			log.accept("");
			log.accept("Install failed: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
		} catch (Error e) {
			// Not left to the thread's default handler either: that is stderr too.
			log.accept("");
			log.accept("Install failed: " + e);
		}
	}

	/**
	 * The path in one of the window's path fields, or null when the field is empty.
	 *
	 * <p>Taken the way it was pasted: blanks around it and the quotes "Copy as path" adds are dropped. A relative
	 * path is resolved here, so the log says where the install actually went rather than echoing it back. Text
	 * that still is not a path this computer accepts is refused with a sentence naming the field.
	 */
	static Path fieldPath(String field, String text) throws IOException {
		String raw = text.trim();
		if (Util.unquote(raw).isBlank()) return null;
		try {
			return Util.path(raw).toAbsolutePath();
		} catch (InvalidPathException e) {
			throw new IOException(field + ": \"" + raw + "\" is not a folder path this computer accepts ("
					+ e.getReason() + "). Choose the folder with Browse… instead of typing it.");
		}
	}

	/**
	 * Document offset where the live status line starts, or -1 when the log ends in a settled line.
	 *
	 * <p>A running download refreshes ONE line in place instead of scrolling hundreds past. Without this the
	 * hardened {@link Http}'s byte-progress arrives as ordinary lines and a 250 MB build phase writes thousands
	 * of near-identical rows into the document — which is both unreadable and a steady leak of Swing document
	 * memory over a multi-minute install.
	 */
	private int transientStart = -1;

	private void append(String raw) {
		SwingUtilities.invokeLater(() -> {
			boolean progress = raw.startsWith(Http.PROGRESS);
			String line = progress ? raw.substring(Http.PROGRESS.length()) : raw;
			if (transientStart >= 0) {
				log.replaceRange("", transientStart, log.getDocument().getLength());
			}
			int start = log.getDocument().getLength();
			log.append(progress ? line : line + System.lineSeparator());
			// A settled line ends the live one, so the finished log carries no progress noise at all.
			transientStart = progress ? start : -1;
			log.setCaretPosition(log.getDocument().getLength());
		});
	}

	/**
	 * The version this installer was built at, read from its own jar manifest.
	 *
	 * <p>The fallback is {@code "dev"} and must stay a non-version: it is reached when there is no manifest to
	 * read — running from a class directory, or from a jar whose build forgot {@code Implementation-Version}.
	 * It used to be the literal {@code "0.1.0"}, which is a claim rather than an admission, and it was wrong
	 * from the moment the version moved: the jar never carried the attribute, so EVERY build took the fallback
	 * and an installer built at 0.2.0 showed a user "0.1.0". A version that cannot be determined has to say so.
	 */
	private static String loaderVersion() {
		String version = InstallerGui.class.getPackage().getImplementationVersion();
		return version == null ? "dev" : version;
	}
}
