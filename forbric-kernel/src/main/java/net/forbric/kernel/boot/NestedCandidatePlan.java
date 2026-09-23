/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import java.nio.file.Path;
import java.util.*;
import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;

/** One selection, consumed by both actual discoveries; verification never chooses another winner. */
public final class NestedCandidatePlan {
	private final NestedCandidateInventory inventory;
	private final JointCandidateSelector.Result selection;

	NestedCandidatePlan(NestedCandidateInventory inventory, JointCandidateSelector.Result selection) {
		this.inventory = inventory; this.selection = selection;
	}
	public NestedCandidateInventory inventory() { return inventory; }
	public Set<Path> selected() { return selection.selected(); }
	public JointCandidateSelector.Result selection() { return selection; }
	/** Uses selected parent edges, never the digest directory's name, as display provenance. */
	Optional<String> bundledBy(Path file) {
		Path path = file.toAbsolutePath().normalize();
		var node = inventory.nodes().get(path);
		if (node == null || !selected().contains(path)) return Optional.empty();
		if (node.root()) return Optional.of("");
		Set<String> owners = new LinkedHashSet<>(); Set<Path> visited = new HashSet<>();
		Deque<Path> pending = new ArrayDeque<>(); pending.add(path);
		while (!pending.isEmpty()) {
			Path child = pending.removeFirst(); if (!visited.add(child)) continue;
			for (var edge : inventory.edges()) if (edge.child().equals(child) && selected().contains(edge.parent())) {
				var parent = inventory.nodes().get(edge.parent());
				if (parent.claim() != null && !parent.claim().modIds().isEmpty()) owners.addAll(parent.claim().modIds());
				else pending.add(edge.parent());
			}
		}
		return Optional.of(owners.size() == 1 ? owners.iterator().next() : KernelModCatalog.UNKNOWN_PARENT);
	}
	public List<Path> nestedFiles() {
		return inventory.nodes().values().stream().filter(n -> !n.root() && selected().contains(n.path()))
				.map(NestedCandidateInventory.Node::path).toList();
	}

	/** All selected files are checked again, and every selected nested digest must reach actual discovery. */
	public boolean verify(List<Path> actualNested) {
		// Each discrepancy is filed under the mod whose file it concerns, never under the first jar in mods/.
		Map<String, List<String>> discrepancies = new LinkedHashMap<>();
		Set<String> expected = new HashSet<>(), observed = new HashSet<>();
		Set<String> allSelected = new HashSet<>();
		for (Path path : selected()) {
			var node = inventory.nodes().get(path); allSelected.add(node.digest());
			if (!node.root()) expected.add(node.digest());
			try { if (!node.digest().equals(NestedCandidateInventory.digest(path))) note(discrepancies, path, "selected jar changed: " + path); }
			catch (Exception unavailable) { note(discrepancies, path, "selected jar unavailable: " + path); }
		}
		for (Path path : actualNested) {
			try {
				String digest = NestedCandidateInventory.digest(path); observed.add(digest);
				if (!allSelected.contains(digest)) note(discrepancies, path, "unselected jar reached discovery: " + path);
			} catch (Exception unavailable) { note(discrepancies, path, "discovered jar unreadable: " + path); }
		}
		for (String digest : expected) if (!observed.contains(digest)) {
			Path missing = selected().stream().filter(p -> inventory.nodes().get(p).digest().equals(digest)).findFirst().orElse(null);
			note(discrepancies, missing, "selected nested digest missing: " + digest);
		}
		if (discrepancies.isEmpty()) return true;
		for (var owner : discrepancies.entrySet()) {
			CompatibilityFindings.record(new CompatibilityFinding("arbitration:materialization", owner.getKey(),
					"Selected mod files", "arbitration:materialization", CompatibilityFinding.Confidence.CONFIRMED, true,
					"The discovered files differ from the jointly selected candidates", owner.getValue()));
		}
		return false;
	}

	private void note(Map<String, List<String>> discrepancies, Path path, String detail) {
		discrepancies.computeIfAbsent(ownerOf(path), ignored -> new ArrayList<>()).add(detail);
	}

	/** The mod a physical file belongs to: its own claim, else the nearest claimed bundling parent, else "forbric". */
	String ownerOf(Path file) {
		if (file == null) return "forbric";
		Deque<Path> pending = new ArrayDeque<>(List.of(file.toAbsolutePath().normalize())); Set<Path> visited = new HashSet<>();
		while (!pending.isEmpty()) {
			Path path = pending.removeFirst(); if (!visited.add(path)) continue;
			var node = inventory.nodes().get(path); if (node == null) continue;
			if (node.claim() != null && !node.claim().modIds().isEmpty()) return node.claim().modIds().getFirst();
			for (var edge : inventory.edges()) if (edge.child().equals(path)) pending.add(edge.parent());
		}
		return "forbric";
	}
}
