/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import net.forbric.api.Ecosystem;
import net.forbric.api.ModCatalog;
import net.forbric.kernel.ui.ModListRows.DeclaredParent;
import net.forbric.kernel.ui.ModListRows.Kind;
import net.forbric.kernel.ui.ModListRows.Platform;
import net.forbric.kernel.ui.ModListRows.Row;

/**
 * Which rows the kernel's Mods screen lists, and where.
 *
 * <p>The rule under test is structural: a mod a jar carries gets a row exactly when it has a config screen, and sits
 * under the parent it names for itself. The ids here are invented ({@code carrier}, {@code lib}, ...) on purpose: the
 * shape of a real pack -- Origins: Legacy carrying Apoli, TRender carried by two mods and naming a parent that is no
 * mod -- with none of its names, so nothing below can pass by recognising one.
 */
class ModListRowsTest {
	private static ModCatalog.Entry installed(String id, String name) {
		return new ModCatalog.Entry(Ecosystem.FABRIC, id, name, "1", "", List.of(), id + ".jar", "", "");
	}

	private static ModCatalog.Entry carried(String id, String name, String by) {
		return new ModCatalog.Entry(Ecosystem.FABRIC, id, name, "1", "", List.of(), id + ".jar", "", by);
	}

	private static List<String> ids(List<Row> rows) {
		List<String> out = new ArrayList<>();
		for (Row r : rows) out.add((r.depth() > 0 ? "  " : "") + r.id());
		return out;
	}

	private static List<Row> plan(List<ModCatalog.Entry> everything, Set<String> withConfig, Map<String, DeclaredParent> parents) {
		return ModListRows.plan(everything, List.of(), e -> withConfig.contains(e.modId()), parents::get);
	}

	@Test
	void aCarriedModWithAConfigScreenGetsARowAndOneWithoutDoesNot() {
		List<ModCatalog.Entry> all = List.of(installed("carrier", "Carrier"), carried("lib", "Lib", "carrier"),
				carried("runtime", "Runtime", "carrier"));
		assertEquals(List.of("carrier", "lib"), ids(plan(all, Set.of("lib"), Map.of())));
		// The negative control: with no config screen, a carried mod is not listed -- it is in the carrier's count.
		assertEquals(List.of("carrier"), ids(plan(all, Set.of(), Map.of())));
	}

	@Test
	void installedModsAreListedWhetherOrNotTheyHaveAConfigScreenAndConfigIsAskedOnlyOfCarriedOnes() {
		List<ModCatalog.Entry> all = List.of(installed("b", "Beta"), installed("a", "Alpha"), carried("c", "Gamma", "a"));
		List<String> asked = new ArrayList<>();
		Predicate<ModCatalog.Entry> has = e -> {
			asked.add(e.modId());
			return false;
		};
		List<Row> rows = ModListRows.plan(all, List.of(), has, id -> null);
		assertEquals(List.of("a", "b"), ids(rows));
		assertEquals(List.of("c"), asked);
	}

	@Test
	void aCarriedModNamingNoParentIsARowOfItsOwnInNameOrder() {
		// Not under the jar that carries it: that is not where Mod Menu puts it, and a library two mods carry has no one
		// carrier to sit under ("?" is the catalogue's "more than one").
		List<ModCatalog.Entry> all = List.of(installed("zeta", "Zeta"), carried("lib", "Lib", "zeta"),
				installed("alpha", "Alpha"), carried("shared", "Shared", "?"));
		List<Row> rows = plan(all, Set.of("lib", "shared"), Map.of());
		assertEquals(List.of("alpha", "lib", "shared", "zeta"), ids(rows));
		for (Row r : rows) assertEquals(0, r.depth(), r.id());
	}

	@Test
	void aParentThatIsAListedModTakesTheCarriedModAsAChild() {
		List<ModCatalog.Entry> all = List.of(installed("carrier", "Carrier"), installed("other", "Other"),
				carried("lib", "Lib", "other"));
		List<Row> rows = plan(all, Set.of("lib"), Map.of("lib", new DeclaredParent("carrier", "", "")));
		assertEquals(List.of("carrier", "  lib", "other"), ids(rows));
		assertEquals(Kind.MOD, rows.get(1).kind());
	}

	@Test
	void aParentThatIsNothingHereBecomesAGroupWithTheNameTheChildGaveIt() {
		// The shape of TRender: carried by two mods, naming a parent object that is no mod at all.
		List<ModCatalog.Entry> all = List.of(installed("culling", "Culling"), installed("layers", "Layers"),
				carried("render", "Render", "?"), carried("api", "Api", "?"));
		DeclaredParent family = new DeclaredParent("family-parent", "Family Modules", "Shared modules.");
		List<Row> rows = plan(all, Set.of("render", "api"), Map.of("render", family, "api", family));
		assertEquals(List.of("culling", "family-parent", "  api", "  render", "layers"), ids(rows));
		Row group = rows.get(1);
		assertEquals(Kind.GROUP, group.kind());
		assertNull(group.entry());
		assertEquals("Family Modules", group.name());
		assertEquals("Shared modules.", group.description());
	}

	@Test
	void aGroupNamedOnlyByIdIsShownUnderThatId() {
		List<ModCatalog.Entry> all = List.of(installed("a", "A"), carried("lib", "Lib", "a"));
		List<Row> rows = plan(all, Set.of("lib"), Map.of("lib", new DeclaredParent("umbrella", "", "")));
		assertEquals(List.of("a", "umbrella", "  lib"), ids(rows));
		assertEquals("umbrella", rows.get(1).name());
	}

	@Test
	void aParentThatIsARealModWithNoRowGetsOneSoTheChildHasSomewhereToBe() {
		List<ModCatalog.Entry> all = List.of(installed("a", "A"), carried("core", "Core", "a"), carried("addon", "Addon", "a"));
		List<Row> rows = plan(all, Set.of("addon"), Map.of("addon", new DeclaredParent("core", "", "")));
		assertEquals(List.of("a", "core", "  addon"), ids(rows));
		assertEquals(Kind.MOD, rows.get(1).kind());
	}

	@Test
	void parentsAreFollowedToTheTopWhicheverComesFirst() {
		// mid names top, low names mid: low sits under top, as Mod Menu puts every mod under its topmost parent. Both
		// orders of the catalogue give the same rows.
		ModCatalog.Entry top = installed("top", "Top");
		ModCatalog.Entry mid = carried("mid", "Mid", "top");
		ModCatalog.Entry low = carried("low", "Low", "top");
		Map<String, DeclaredParent> parents = Map.of("mid", new DeclaredParent("top", "", ""), "low",
				new DeclaredParent("mid", "", ""));
		assertEquals(List.of("top", "  low", "  mid"), ids(plan(List.of(top, mid, low), Set.of("mid", "low"), parents)));
		assertEquals(List.of("top", "  low", "  mid"), ids(plan(List.of(top, low, mid), Set.of("mid", "low"), parents)));
	}

	@Test
	void aParentChainThatLoopsLeavesTheModAtTheTop() {
		List<ModCatalog.Entry> all = List.of(installed("a", "A"), carried("x", "X", "a"), carried("y", "Y", "a"));
		Map<String, DeclaredParent> parents = Map.of("x", new DeclaredParent("y", "", ""), "y", new DeclaredParent("x", "", ""));
		List<Row> rows = plan(all, Set.of("x", "y"), parents);
		assertTrue(ids(rows).contains("x") || ids(rows).contains("y"), ids(rows).toString());
		assertEquals(3, rows.size(), ids(rows).toString());
	}

	@Test
	void aModNamingItselfAsParentIsAtTheTop() {
		List<ModCatalog.Entry> all = List.of(installed("a", "A"), carried("x", "X", "a"));
		assertEquals(List.of("a", "x"), ids(plan(all, Set.of("x"), Map.of("x", new DeclaredParent("x", "", "")))));
	}

	@Test
	void installedModsStayWhereTheyAreWhateverParentTheyName() {
		List<ModCatalog.Entry> all = List.of(installed("base", "Base"), installed("addon", "Addon"));
		List<Row> rows = plan(all, Set.of("addon"), Map.of("addon", new DeclaredParent("base", "", "")));
		assertEquals(List.of("addon", "base"), ids(rows));
	}

	@Test
	void platformEntriesAreRowsInNameOrderAndNeverDuplicateACataloguedId() {
		ModCatalog.Entry game = new ModCatalog.Entry(Ecosystem.FABRIC, "game", "Minecraft", "26.2", "", List.of(), "", "", "");
		ModCatalog.Entry loader = new ModCatalog.Entry(Ecosystem.NEOFORGE, "loader", "NeoForge", "1", "", List.of(), "", "", "");
		ModCatalog.Entry shadow = new ModCatalog.Entry(Ecosystem.NEOFORGE, "mod", "Shadow", "1", "", List.of(), "", "", "");
		List<ModCatalog.Entry> all = List.of(installed("mod", "Lemon"), installed("zz", "Zz"));
		List<Row> rows = ModListRows.plan(all, List.of(new Platform(game, null), new Platform(loader, Ecosystem.NEOFORGE),
				new Platform(shadow, Ecosystem.NEOFORGE)), e -> true, id -> null);
		assertEquals(List.of("mod", "game", "loader", "zz"), ids(rows));
		assertTrue(rows.get(1).game());
		assertEquals(Kind.PLATFORM, rows.get(2).kind());
		assertEquals(Ecosystem.NEOFORGE, rows.get(2).family());
		assertEquals("Lemon", rows.get(0).name(), "the catalogue's own row wins over a platform entry of the same id");
	}

	@Test
	void aCarriedModCanBeTheParentOfAnotherCarriedModWhicheverIsReadFirst() {
		ModCatalog.Entry a = installed("a", "A");
		ModCatalog.Entry lib = carried("lib", "Lib", "a");
		ModCatalog.Entry ext = carried("ext", "Ext", "a");
		Map<String, DeclaredParent> parents = Map.of("ext", new DeclaredParent("lib", "", ""));
		assertEquals(List.of("a", "lib", "  ext"), ids(plan(List.of(a, ext, lib), Set.of("lib", "ext"), parents)));
		assertEquals(List.of("a", "lib", "  ext"), ids(plan(List.of(a, lib, ext), Set.of("lib", "ext"), parents)));
	}

	@Test
	void aSearchKeepsAMatchingChildUnderItsParentAndAMatchingParentWithAllItsChildren() {
		List<ModCatalog.Entry> all = List.of(installed("a", "Alpha"), installed("b", "Beta"), carried("x", "Xenon", "a"),
				carried("y", "Yttrium", "a"));
		Map<String, DeclaredParent> parents = Map.of("x", new DeclaredParent("a", "", ""), "y", new DeclaredParent("a", "", ""));
		List<Row> rows = plan(all, Set.of("x", "y"), parents);
		Function<String, List<String>> search = needle -> ids(ModListRows.filter(rows,
				r -> r.name().toLowerCase(java.util.Locale.ROOT).contains(needle)));
		assertEquals(List.of("a", "  y"), search.apply("ytt"));
		assertEquals(List.of("a", "  x", "  y"), search.apply("alp"));
		assertEquals(List.of("b"), search.apply("bet"));
		assertEquals(List.of(), search.apply("nothing"));
	}

	@Test
	void theConfigQuestionIsNotAskedTwiceForOneCarriedMod() {
		Map<String, Integer> asked = new HashMap<>();
		List<ModCatalog.Entry> all = List.of(installed("a", "A"), carried("x", "X", "a"));
		ModListRows.plan(all, List.of(), e -> {
			asked.merge(e.modId(), 1, Integer::sum);
			return true;
		}, id -> new DeclaredParent("a", "", ""));
		assertEquals(Map.of("x", 1), asked);
	}
}
