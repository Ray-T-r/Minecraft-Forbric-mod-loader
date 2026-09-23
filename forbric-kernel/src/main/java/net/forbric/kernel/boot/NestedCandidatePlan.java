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
		List<String> discrepancies = new ArrayList<>(); Set<String> expected = new HashSet<>(), observed = new HashSet<>();
		Set<String> allSelected = new HashSet<>();
		for (Path path : selected()) {
			var node = inventory.nodes().get(path); allSelected.add(node.digest());
			if (!node.root()) expected.add(node.digest());
			try { if (!node.digest().equals(NestedCandidateInventory.digest(path))) discrepancies.add("selected jar changed: " + path); }
			catch (Exception unavailable) { discrepancies.add("selected jar unavailable: " + path); }
		}
		for (Path path : actualNested) {
			try {
				String digest = NestedCandidateInventory.digest(path); observed.add(digest);
				if (!allSelected.contains(digest)) discrepancies.add("unselected jar reached discovery: " + path);
			} catch (Exception unavailable) { discrepancies.add("discovered jar unreadable: " + path); }
		}
		for (String digest : expected) if (!observed.contains(digest)) discrepancies.add("selected nested digest missing: " + digest);
		if (discrepancies.isEmpty()) return true;
		String owner = inventory.nodes().values().stream().filter(n -> n.root() && n.claim() != null)
				.flatMap(n -> n.claim().modIds().stream()).findFirst().orElse("forbric");
		CompatibilityFindings.record(new CompatibilityFinding("arbitration:materialization", owner,
				"Selected mod files", "arbitration:materialization", CompatibilityFinding.Confidence.CONFIRMED, true,
				"The discovered files differ from the jointly selected candidates", discrepancies));
		return false;
	}
}
