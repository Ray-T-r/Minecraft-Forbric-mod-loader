package net.forbric.installer;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ItemEvent;
import java.nio.file.Path;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;

/**
 * Minimal Swing front-end for the installer: explains what Forbric is + its compatibility, lets the user pick
 * the Minecraft directory and base version, and runs {@link Installer#install} on a background thread while
 * streaming progress into a log pane. The GUI is a thin shell over the same {@link Installer} the CLI uses.
 */
final class InstallerGui {

	private static final String INTRO =
			"Forbric is a clean-room unified mod loader: it runs both Fabric mods (fabric.mod.json) and Forge "
			+ "mods (mods.toml) in one Minecraft instance, on the Fabric substrate.\n\n"
			+ "This installer does NOT modify your existing Fabric/Forge install. It writes a new, separate "
			+ "launcher version and stages Forbric's jars into your launcher's libraries/ folder. Pick that "
			+ "version in PCL2 / HMCL to launch.\n\n"
			+ "Modes:\n"
			+ "  • Intermediary v1 (MC 1.21.11) — Fabric mods + simple (registry/event) Forge mods.\n"
			+ "  • Full Forge (MC 26.2) — the genuine Forge lifecycle: drop a raw Forge jar into the profile's "
			+ "mods/ and it loads. The first install builds the Forge runtime + patches Minecraft on your "
			+ "machine (downloads Forge, a few minutes; cached afterwards).\n\n"
			+ "Licensing: Forbric reuses the Apache-2.0 fabric-loader substrate; the Forge side is clean-room. "
			+ "The Forge-patched Minecraft is built locally and never redistributed.";

	private final JFrame frame = new JFrame("Forbric Installer");
	private final JTextField mcDirField = new JTextField();
	private final JComboBox<String> modeBox = new JComboBox<>(new String[]{
			"Intermediary v1 — Fabric + simple Forge (MC 1.21.11)",
			"Full Forge — genuine Forge lifecycle (MC 26.2)"});
	private final JComboBox<String> versionBox = new JComboBox<>();
	private final JCheckBox autoDownloadBox = new JCheckBox("Download the base version if it is missing");
	private final JTextArea log = new JTextArea(10, 60);
	private final JButton installButton = new JButton("Install");

	private final Path initialMcDir;
	private final Path initialManifest;
	private String targetVersion;   // the base version the mode targets (flips with the mode box)
	private String selectedMode;

	InstallerGui(Path mcDir, String mcVersion, String mode, Path manifest, boolean autoDownloadBase) {
		this.initialMcDir = mcDir;
		this.initialManifest = manifest;
		this.targetVersion = mcVersion;
		this.selectedMode = mode;
		this.autoDownloadBox.setSelected(autoDownloadBase);
		if (Installer.MODE_FULL_FORGE_26_2.equals(mode)) modeBox.setSelectedIndex(1);
	}

	void show() {
		frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
		frame.setLayout(new BorderLayout(0, 0));

		frame.add(header(), BorderLayout.NORTH);
		frame.add(form(), BorderLayout.CENTER);
		frame.add(footer(), BorderLayout.SOUTH);

		reloadVersions();

		frame.pack();
		frame.setMinimumSize(new Dimension(640, 560));
		frame.setLocationRelativeTo(null);
		frame.setVisible(true);
	}

	private JPanel header() {
		JPanel p = new JPanel(new BorderLayout());
		p.setBorder(BorderFactory.createEmptyBorder(14, 16, 8, 16));

		JLabel title = new JLabel("Forbric — unified Fabric + Forge loader");
		title.setFont(title.getFont().deriveFont(Font.BOLD, 17f));

		JTextArea intro = new JTextArea(INTRO);
		intro.setEditable(false);
		intro.setLineWrap(true);
		intro.setWrapStyleWord(true);
		intro.setOpaque(false);
		intro.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
		intro.setFont(intro.getFont().deriveFont(12f));

		p.add(title, BorderLayout.NORTH);
		p.add(intro, BorderLayout.CENTER);
		return p;
	}

	private JPanel form() {
		JPanel p = new JPanel(new GridBagLayout());
		p.setBorder(BorderFactory.createEmptyBorder(4, 16, 8, 16));
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(4, 4, 4, 4);
		c.fill = GridBagConstraints.HORIZONTAL;

		mcDirField.setText(initialMcDir.toString());

		// Flip the target base version (and refresh the dropdown) when the mode changes.
		modeBox.addItemListener(e -> {
			if (e.getStateChange() != ItemEvent.SELECTED) return;
			boolean full = modeBox.getSelectedIndex() == 1;
			selectedMode = full ? Installer.MODE_FULL_FORGE_26_2 : Installer.MODE_INTERMEDIARY_V1;
			targetVersion = full ? "26.2" : "1.21.11";
			reloadVersions();
		});

		int row = 0;
		addRow(p, c, row++, "Minecraft folder:", mcDirField, "Reload", e -> reloadVersions());
		addRow(p, c, row++, "Mode:", modeBox, null, null);
		addRow(p, c, row++, "Base version:", versionBox, null, null);
		addRow(p, c, row++, "", autoDownloadBox, null, null);

		log.setEditable(false);
		log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
		JScrollPane scroll = new JScrollPane(log);
		scroll.setBorder(BorderFactory.createTitledBorder("Log"));

		c.gridx = 0;
		c.gridy = row;
		c.gridwidth = 3;
		c.weightx = 1;
		c.weighty = 1;
		c.fill = GridBagConstraints.BOTH;
		p.add(scroll, c);
		return p;
	}

	private JPanel footer() {
		JPanel p = new JPanel(new BorderLayout());
		p.setBorder(BorderFactory.createEmptyBorder(0, 16, 14, 16));
		installButton.addActionListener(e -> doInstall());
		p.add(Box.createHorizontalGlue(), BorderLayout.CENTER);
		p.add(installButton, BorderLayout.EAST);
		return p;
	}

	private void addRow(JPanel p, GridBagConstraints c, int row, String label, java.awt.Component field,
	                    String buttonText, java.awt.event.ActionListener onButton) {
		c.gridy = row;
		c.gridx = 0;
		c.weightx = 0;
		c.gridwidth = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		p.add(new JLabel(label), c);

		c.gridx = 1;
		c.weightx = 1;
		p.add(field, c);

		c.gridx = 2;
		c.weightx = 0;
		if (buttonText != null) {
			JButton b = new JButton(buttonText);
			b.addActionListener(onButton);
			p.add(b, c);
		} else {
			p.add(Box.createHorizontalStrut(1), c);
		}
	}

	private void reloadVersions() {
		Path mcDir = Util.path(mcDirField.getText().trim());
		List<String> versions = Installer.discoverBaseVersions(mcDir);
		DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
		for (String v : versions) model.addElement(v);
		// Always offer the mode's target version even when it isn't installed yet: with auto-download on, Install
		// can still proceed and fetch it, so the dropdown is never empty/dead.
		if (!versions.contains(targetVersion)) model.addElement(targetVersion);
		versionBox.setModel(model);
		versionBox.setSelectedItem(targetVersion);
		if (!versions.contains(targetVersion)) {
			appendLog("Base '" + targetVersion + "' is not installed yet — it will be downloaded on Install "
					+ "(uncheck the box to opt out).");
		}
	}

	private void doInstall() {
		final Path mcDir = Util.path(mcDirField.getText().trim());
		final Object sel = versionBox.getSelectedItem();
		if (sel == null) {
			appendLog("Pick a base version first.");
			return;
		}
		final String mcVersion = sel.toString();
		final boolean autoDownloadBase = autoDownloadBox.isSelected();
		final String mode = selectedMode;

		installButton.setEnabled(false);
		log.setText("");
		appendLog("Installing Forbric (" + mode + ") for " + mcVersion + " into " + mcDir + " ...\n");

		new SwingWorker<Void, String>() {
			@Override
			protected Void doInBackground() {
				try {
					// Dev override (--manifest) still works; otherwise use the manifest + jars bundled in this jar.
					Installer installer = (initialManifest != null)
							? Installer.fromManifest(initialManifest)
							: Installer.fromBundledManifest();
					installer.install(mcDir, mcVersion, mode, autoDownloadBase, this::publish);
				} catch (Exception ex) {
					publish("ERROR: " + ex.getMessage());
				}
				return null;
			}

			@Override
			protected void process(List<String> chunks) {
				for (String s : chunks) appendLog(s);
			}

			@Override
			protected void done() {
				installButton.setEnabled(true);
				reloadVersions();   // the base version may now exist on disk — refresh the dropdown
			}
		}.execute();
	}

	private void appendLog(String line) {
		log.append(line + "\n");
		log.setCaretPosition(log.getDocument().getLength());
	}
}
