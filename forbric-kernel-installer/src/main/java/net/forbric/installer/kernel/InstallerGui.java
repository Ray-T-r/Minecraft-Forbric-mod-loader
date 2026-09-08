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
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.nio.file.Path;

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
		frame.add(header(), BorderLayout.NORTH);
		frame.add(form(), BorderLayout.CENTER);
		frame.add(footer(), BorderLayout.SOUTH);
		frame.pack();
		frame.setMinimumSize(new Dimension(640, frame.getHeight()));
		frame.setLocationRelativeTo(null);
		frame.setVisible(true);
	}

	private JPanel header() {
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 0, 12));
		JLabel title = new JLabel("Forbric — Fabric, MinecraftForge and NeoForge mods in one game");
		title.setFont(title.getFont().deriveFont(title.getFont().getSize2D() + 3f));
		panel.add(title);
		panel.add(Box.createVerticalStrut(6));
		JTextArea intro = new JTextArea("Installs a version your usual launcher can start. The game base and the "
				+ "two Forge-family runtimes are built on this machine and never shipped with the installer, so "
				+ "point it at the directory holding them if it cannot find them itself.");
		intro.setEditable(false);
		intro.setLineWrap(true);
		intro.setWrapStyleWord(true);
		intro.setOpaque(false);
		intro.setBorder(null);
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
		addRow(panel, c, row, "Built artifacts", artifacts, this::chooseArtifacts);
		return panel;
	}

	private void addRow(JPanel panel, GridBagConstraints c, int row, String label,
			javax.swing.JComponent field, Runnable browse) {
		c.gridx = 0;
		c.gridy = row;
		c.weightx = 0;
		c.fill = GridBagConstraints.NONE;
		panel.add(new JLabel(label), c);

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

	private JPanel footer() {
		JPanel panel = new JPanel(new BorderLayout(0, 8));
		panel.setBorder(BorderFactory.createEmptyBorder(8, 12, 12, 12));
		log.setEditable(false);
		panel.add(new JScrollPane(log), BorderLayout.CENTER);
		JPanel buttons = new JPanel(new BorderLayout());
		install.addActionListener(e -> runInstall());
		buttons.add(install, BorderLayout.LINE_END);
		panel.add(buttons, BorderLayout.SOUTH);
		return panel;
	}

	private void chooseDirectory() {
		choose(directory, "Choose the Minecraft directory");
	}

	private void chooseArtifacts() {
		choose(artifacts, "Choose the directory holding the built game artifacts");
	}

	private void choose(JTextField field, String title) {
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle(title);
		chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		String current = field.getText().trim();
		if (!current.isEmpty()) chooser.setCurrentDirectory(new File(current));
		if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
			field.setText(chooser.getSelectedFile().getAbsolutePath());
		}
	}

	private void runInstall() {
		install.setEnabled(false);
		log.setText("");
		String mcVersion = String.valueOf(gameVersion.getSelectedItem());
		Path dir = Util.path(directory.getText().trim());
		String artifactText = artifacts.getText().trim();
		Path artifactDir = artifactText.isEmpty() ? null : Util.path(artifactText);

		new Thread(() -> {
			try {
				new Installer(this::append).install(dir, mcVersion, artifactDir);
			} catch (Exception e) {
				append("");
				append("Install failed: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
			} finally {
				SwingUtilities.invokeLater(() -> install.setEnabled(true));
			}
		}, "forbric-install").start();
	}

	private void append(String line) {
		SwingUtilities.invokeLater(() -> {
			log.append(line + System.lineSeparator());
			log.setCaretPosition(log.getDocument().getLength());
		});
	}

	/** The version this installer was built at, from its own manifest; "dev" when run from a class directory. */
	private static String loaderVersion() {
		String version = InstallerGui.class.getPackage().getImplementationVersion();
		return version == null ? "0.1.0" : version;
	}
}
