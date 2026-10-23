/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import net.forbric.api.Ecosystem;
import net.forbric.api.ModCatalog;

/**
 * The rows of the kernel's Mods screen and their order. Pure: the screen draws what this returns, and the screen, its
 * Config button and the client smoke read the same list, so "this mod has a row" means one thing to all of them.
 *
 * <h2>Which mods get a row</h2>
 *
 * <p>Every jar a player put in {@code mods/}, as before. Two more kinds, because a row is the only way to a config
 * screen and a config screen nothing leads to is one a player cannot use:
 * <ul>
 *   <li><b>A jar a mod carries inside itself</b> ({@link ModCatalog.Entry#installed()} false) <b>that has a config
 *       screen of its own.</b> Leaving those out was right for the list's purpose — what did I install — and wrong for
 *       the Config button: a library a mod nests (a config framework, a render library) whose own settings are not
 *       reachable from its carrier had no way in at all. Mod Menu lists them, as libraries with a config. One without a
 *       config still has no row: it is in the carrier's "bundles N jar(s)".</li>
 *   <li><b>A platform entry</b>: an id a loader's own registry holds that is no jar's — the game itself, a loader's
 *       own mod — when it has a config screen. The caller decides which ones; for the game the answer is always yes
 *       (its options screen), as under Mod Menu. Whether a loader's own entry (one with a {@link Platform#family()})
 *       is listed at all is one decision, {@link #LOADER_ROWS}.</li>
 * </ul>
 *
 * <p>This plan drops no installed entry for sharing an id with another: each is a row of its own. That does NOT mean
 * the screen shows two jars with one id today. {@link ModCatalog#publish} collapses duplicate ids before anything reads
 * the catalogue (first one wins), and the kernel appends a jar whose metadata could not be read -- catalogued under the
 * id its failure names -- after every mod that loaded, so when that id is a loaded jar's, the FAILED entry is gone
 * before this plan sees it, and the unreadable jar's finding is then shown on the loaded jar's row
 * ({@code CompatibilityFindings.project} matches by id). That is an open defect of the catalogue's identity (mod id,
 * not jar), not something this class can repair.
 *
 * <h2>Where a carried mod sits</h2>
 *
 * <p>Where it says it belongs, as Mod Menu reads it: a mod that names a parent for itself ({@code custom.modmenu.parent},
 * an id, or an object with an id and the name to show) is listed under that parent, indented; one that names none is
 * a row of its own. Not under the jar that carries it: Mod Menu does not group by jar-in-jar, and a library two mods
 * both carry has no single carrier to sit under. A parent that is a listed row takes the mod as a child. One that is a
 * real mod with no row of its own (a carried library without a config) gets a row so its child has somewhere to be. One
 * nothing on the instance is — a family name such as "tr7zw Library Modules" — becomes a {@link Kind#GROUP} row with the
 * name the child gave it. Parents are followed up to the top, as Mod Menu does, so everything is one level deep, and a
 * chain that loops back on itself leaves the mod at the top. Installed mods stay where they are whatever they declare:
 * the list is still, first, the list of what was installed.
 */
public final class ModListRows {
	private ModListRows() {
	}

	/** What a row is. */
	public enum Kind {
		/** A mod in the catalogue: one a player installed, or one a mod carries that has a config screen. */
		MOD,
		/** An id a loader's registry holds that is no jar's: the game, a loader's own mod. */
		PLATFORM,
		/** A parent a carried mod named that is nothing on this instance: a heading, with no config of its own. */
		GROUP
	}

	/**
	 * The parent a mod names for itself in its metadata. {@code name} and {@code description} are what to show for a
	 * parent that is not a mod (the object form); empty for the plain-id form.
	 */
	public record DeclaredParent(String id, String name, String description) {
		public DeclaredParent {
			id = id == null ? "" : id.trim();
			name = name == null ? "" : name.trim();
			description = description == null ? "" : description.trim();
		}
	}

	/**
	 * A platform entry: {@code entry} is what its config is asked through; {@code family} is the loader whose registry
	 * holds it, for the row's tag, or {@code null} for the game itself, which belongs to none of them.
	 */
	public record Platform(ModCatalog.Entry entry, Ecosystem family) {
	}

	/** Which loaders' own entries (a {@link Platform} with a family: NeoForge's own settings, say) become rows. */
	public enum LoaderRows {
		/** Every one with a config screen, on every instance -- NeoForge's own row even with no NeoForge mod installed. */
		ALWAYS,
		/** Only a loader at least one catalogued mod (installed or carried) belongs to. */
		WHEN_ITS_FAMILY_HAS_MODS,
		/** None; the game's row is not a loader's and stays. */
		NEVER
	}

	/**
	 * THE decision whether a loader's own entry is a row, and the one line to change to decide it differently. It is the
	 * owner's call, not settled: {@code ALWAYS} puts a NeoForge row, which opens NeoForge's own settings screen, into
	 * every instance's list, pure-Fabric packs included, because the kernel always runs NeoForge. The row is named by
	 * the container's own display name, and the kernel's NeoForge baseline container answers its id, so today it reads
	 * "neoforge" (genuine NeoForge's manifest says "NeoForge"). Nothing else reads a loader's own row, and gate-m56
	 * deliberately asserts it neither way.
	 */
	public static final LoaderRows LOADER_ROWS = LoaderRows.ALWAYS;

	/** Whether {@code policy} lists a loader's own entry of {@code family}, given the families catalogued mods are of. */
	static boolean listsLoaderRow(LoaderRows policy, Ecosystem family, Set<Ecosystem> catalogued) {
		return switch (policy) {
			case ALWAYS -> true;
			case WHEN_ITS_FAMILY_HAS_MODS -> catalogued.contains(family);
			case NEVER -> false;
		};
	}

	/**
	 * One row. {@code entry} is null only for a {@link Kind#GROUP}; {@code family} is the tag the row shows (null for
	 * the game and for a group); {@code depth} is 0 at the top and 1 under a parent.
	 */
	public record Row(Kind kind, ModCatalog.Entry entry, String id, String name, String description, Ecosystem family,
			int depth) {
		/** Whether the row is the game itself: a platform entry no loader owns. */
		public boolean game() {
			return kind == Kind.PLATFORM && family == null;
		}
	}

	private static final Comparator<Block> BY_NAME = Comparator.comparing((Block b) -> b.head.name().toLowerCase(Locale.ROOT))
			.thenComparing(b -> b.head.id());

	private static final class Block {
		final Row head;
		final List<Row> children = new ArrayList<>();

		Block(Row head) {
			this.head = head;
		}
	}

	/**
	 * The rows, top-level ones in name order each followed by its children in name order, with loaders' own entries as
	 * {@link #LOADER_ROWS} decides.
	 *
	 * @param everything     every mod in the catalogue, carried ones included ({@code ModCatalog.everything()})
	 * @param platform       platform entries that have a config screen; an id the catalogue also has is ignored
	 * @param hasConfig      whether a mod has a config screen — asked once for each carried mod and for nothing else
	 * @param declaredParent the parent a mod id names for itself, or null
	 */
	public static List<Row> plan(List<ModCatalog.Entry> everything, List<Platform> platform,
			Predicate<ModCatalog.Entry> hasConfig, Function<String, DeclaredParent> declaredParent) {
		return plan(everything, platform, hasConfig, declaredParent, LOADER_ROWS);
	}

	/** As above, with the loader-row decision given rather than {@link #LOADER_ROWS}: for tests of each answer. */
	static List<Row> plan(List<ModCatalog.Entry> everything, List<Platform> platform,
			Predicate<ModCatalog.Entry> hasConfig, Function<String, DeclaredParent> declaredParent, LoaderRows loaderRows) {
		Map<String, ModCatalog.Entry> byId = new HashMap<>();
		Set<Ecosystem> families = new HashSet<>();
		for (ModCatalog.Entry e : everything) {
			byId.putIfAbsent(e.modId(), e);
			families.add(e.ecosystem());
		}
		// Every installed entry is a block of its own, two with one id included -- though the catalogue collapses
		// duplicate ids before this runs (see the class comment). tops is only how a parent is found by id: the first
		// block with that id.
		List<Block> blocks = new ArrayList<>();
		Map<String, Block> tops = new LinkedHashMap<>();
		for (ModCatalog.Entry e : everything) {
			if (e.installed()) add(blocks, tops, new Block(mod(e, 0)));
		}
		for (Platform p : platform) {
			if (p == null || p.entry() == null || byId.containsKey(p.entry().modId()) || tops.containsKey(p.entry().modId())) continue;
			if (p.family() != null && !listsLoaderRow(loaderRows, p.family(), families)) continue;
			ModCatalog.Entry e = p.entry();
			add(blocks, tops, new Block(new Row(Kind.PLATFORM, e, e.modId(), e.name(), e.description(), p.family(), 0)));
		}

		// Carried mods with a config: those naming no parent are rows of their own first, so a carried mod can be the
		// parent another one names, whichever of the two comes first.
		List<ModCatalog.Entry> placed = new ArrayList<>();
		Map<String, DeclaredParent> parents = new HashMap<>();
		for (ModCatalog.Entry e : everything) {
			if (e.installed() || tops.containsKey(e.modId()) || parents.containsKey(e.modId()) || !hasConfig.test(e)) continue;
			DeclaredParent parent = parent(declaredParent, e.modId());
			if (parent == null) add(blocks, tops, new Block(mod(e, 0)));
			else {
				parents.put(e.modId(), parent);
				placed.add(e);
			}
		}
		Set<String> children = new HashSet<>();
		for (ModCatalog.Entry e : placed) children.add(e.modId());
		for (ModCatalog.Entry e : placed) {
			Block top = topFor(e.modId(), parents.get(e.modId()), blocks, tops, byId, children, parents, declaredParent);
			if (top == null) add(blocks, tops, new Block(mod(e, 0)));
			else top.children.add(mod(e, 1));
		}

		List<Block> sorted = new ArrayList<>(blocks);
		sorted.sort(BY_NAME);
		List<Row> rows = new ArrayList<>();
		for (Block block : sorted) {
			rows.add(block.head);
			block.children.sort(Comparator.comparing((Row r) -> r.name().toLowerCase(Locale.ROOT)).thenComparing(Row::id));
			rows.addAll(block.children);
		}
		return List.copyOf(rows);
	}

	/** Adds a top-level block; the first one with an id is the one a child naming that id sits under. */
	private static void add(List<Block> blocks, Map<String, Block> tops, Block block) {
		blocks.add(block);
		tops.putIfAbsent(block.head.id(), block);
	}

	/**
	 * The top-level row {@code modId}'s declared parent leads to, made if it is not there yet; null when the chain loops
	 * back on itself, in which case the mod is left at the top.
	 */
	private static Block topFor(String modId, DeclaredParent first, List<Block> blocks, Map<String, Block> tops,
			Map<String, ModCatalog.Entry> byId, Set<String> children, Map<String, DeclaredParent> parents,
			Function<String, DeclaredParent> declaredParent) {
		Set<String> seen = new HashSet<>();
		seen.add(modId);
		DeclaredParent at = first;
		while (true) {
			String id = at.id();
			if (!seen.add(id)) return null;
			Block top = tops.get(id);
			if (top != null) return top;
			ModCatalog.Entry real = byId.get(id);
			if (real == null) {
				// Nothing here is that id: a heading named the way the child named it, as Mod Menu's dummy parent.
				String name = at.name().isEmpty() ? id : at.name();
				Block group = new Block(new Row(Kind.GROUP, null, id, name, at.description(), null, 0));
				add(blocks, tops, group);
				return group;
			}
			// A real mod. A carried one placed under a parent of its own passes the child up to that parent; any other
			// becomes the row the child sits under.
			DeclaredParent up = children.contains(id) ? parents.get(id) : parent(declaredParent, id);
			if (up == null) {
				Block made = new Block(mod(real, 0));
				add(blocks, tops, made);
				return made;
			}
			at = up;
		}
	}

	/** The declared parent, or null when there is none or the mod names itself. */
	private static DeclaredParent parent(Function<String, DeclaredParent> declaredParent, String modId) {
		DeclaredParent parent;
		try {
			parent = declaredParent.apply(modId);
		} catch (RuntimeException unreadable) {
			net.forbric.kernel.util.Caught.warn(unreadable, "[Forbric/ModList] could not read the parent %s names for itself; "
					+ "it is listed at the top", modId);
			return null;
		}
		return parent == null || parent.id().isEmpty() || parent.id().equals(modId) ? null : parent;
	}

	private static Row mod(ModCatalog.Entry e, int depth) {
		return new Row(Kind.MOD, e, e.modId(), e.name(), e.description(), e.ecosystem(), depth);
	}

	/**
	 * The rows a search keeps: a top-level row that matches keeps all its children; a child that matches keeps its
	 * top-level row, so it is still shown under what it belongs to; anything else goes.
	 */
	public static List<Row> filter(List<Row> rows, Predicate<Row> matches) {
		List<Row> out = new ArrayList<>();
		int i = 0;
		while (i < rows.size()) {
			Row head = rows.get(i++);
			List<Row> kids = new ArrayList<>();
			while (i < rows.size() && rows.get(i).depth() > 0) kids.add(rows.get(i++));
			boolean headHit = matches.test(head);
			List<Row> kept = headHit ? kids : kids.stream().filter(matches).toList();
			if (headHit || !kept.isEmpty()) {
				out.add(head);
				out.addAll(kept);
			}
		}
		return out;
	}
}
