/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;
import net.forbric.api.DiscoveredMod;
import net.forbric.api.Ecosystem;
import net.forbric.api.ModPresence;
import net.forbric.api.UnifiedDependency;

/**
 * An injector whose target the owning mod's own platform lacks too is dropped the way native Mixin drops it — the
 * injector, not the mixin — and is no loss of the merge's; one whose target that platform HAS stays the loss it was.
 *
 * <p>Not Enough Crashes' {@code MixinTileEntity} in miniature: a field of its own, and one {@code @Inject} into
 * {@code BlockEntity.populateCrashReport}, a name 26.2 does not have (it has {@code fillCrashReportCategory}), in a
 * Fabric mod's {@code required: true} config without {@code injectors.defaultRequire}. Natively that mixin applies,
 * its field and all, and the injector is skipped without a word; here it was left out as a CONFIRMED required loss
 * and the STRICT policy stopped the server. Classes are synthesized with ASM, and the tables are the shipped one or a
 * test's own, so this runs anywhere; NativeOnlyMethodsCensusTest asks the same questions of the real jars.
 *
 * <p>Each case that keeps the mixin has its RED control beside it: the same inputs with
 * {@code -Dforbric.mixinFit.nativeAbsent=off}, through the adapter's entry without the raw view, without an owner,
 * without the owner's manifest, for a mod whose range excludes the game the table describes, with a selector Mixin
 * does not read as a plain name, or on a base the table was not derived from, reproduce the old verdict — the mixin
 * left out, and a CONFIRMED required finding.
 */
@ResourceLock("ModCatalog")
@ResourceLock("ModPresence")
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class NativeAbsentTargetsTest {
	private static final String PKG = "net/example/necmixin";
	private static final String BLOCK_ENTITY = "net/minecraft/world/level/block/entity/BlockEntity";
	private static final String CONFIG = "nec.mixins.json";
	private static final String LEFT_OUT = "mixin:" + CONFIG + ":net.example.necmixin.TileEntityMixin";
	private static final String CATEGORY = "(Lnet/minecraft/CrashReportCategory;)V";

	@BeforeEach @AfterEach void clean() {
		CompatibilityFindings.reset();
		MixinConfigOwners.reset();
		System.clearProperty(NativeAbsentTargets.PROPERTY);
		System.clearProperty(NativeAbsentTargets.BASE_PROPERTY);
		System.clearProperty("mixin.debug.countInjections");
		System.clearProperty("mixin.debug");
		ModPresence.publishFabric(List.of());
		ModPresence.publishForgeFamily(List.of());
	}

	// --- the adapter: kept like native, or left out as before ------------------------------------------------------

	@Test void aTargetVanillaLacksTooKeepsAFabricModsMixinAndRecordsNothing() {
		owned(Ecosystem.FABRIC);
		Map<String, byte[]> classes = necShape(null, false);
		assertEquals(List.of(), judge(json(""), classes, classes), "Mixin gets the whole mixin, as native does");
		assertEquals(List.of(), CompatibilityFindings.all(), "nothing the merge lost, so nothing to report");
	}

	/** RED control: the switch restores the verdict that stopped the server. */
	@Test void switchedOffTheSameMixinIsLeftOutAsARequiredLoss() {
		owned(Ecosystem.FABRIC);
		System.setProperty(NativeAbsentTargets.PROPERTY, "off");
		Map<String, byte[]> classes = necShape(null, false);
		assertEquals(List.of("TileEntityMixin"), judge(json(""), classes, classes));
		assertTrue(confirmedRequired(), "the old CONFIRMED required finding: " + CompatibilityFindings.all());
	}

	/** RED control: the entry without the raw view (every caller before this) asks nothing, as before. */
	@Test void withoutTheRawViewNothingIsAskedOfTheNativeGame() {
		owned(Ecosystem.FABRIC);
		Map<String, byte[]> classes = necShape(null, false);
		assertEquals(List.of("TileEntityMixin"), KernelGuestMixinAdapter.unfitMixins(CONFIG, json(""), classes::get, null));
		assertTrue(confirmedRequired(), CompatibilityFindings.all().toString());
	}

	/** A config no single mod claims has no native game to ask: every miss is the merge's, as before. */
	@Test void aConfigNoModOwnsIsNotAsked() {
		Map<String, byte[]> classes = necShape(null, false);
		assertEquals(List.of("TileEntityMixin"), judge(json(""), classes, classes));
		assertTrue(confirmedRequired(), CompatibilityFindings.all().toString());
	}

	/**
	 * The platform is the owning mod's. With the shipped table, MinecraftForge's and NeoForge's games lack
	 * populateCrashReport as vanilla does, so their mods' injector is dropped natively too; what differs between them is
	 * what each game declares, which aRowOrTheRawClassMakesAMethodThePlatforms asks of a table here and
	 * NativeOnlyMethodsCensusTest.aMethodTheModsOwnPlatformHasAndTheMergeLostStaysAConfirmedLoss of the real one.
	 */
	@Test void everyPlatformIsAskedAboutItsOwnGame() {
		for (Ecosystem platform : Ecosystem.values()) {
			MixinConfigOwners.reset();
			CompatibilityFindings.reset();
			owned(platform);
			Map<String, byte[]> classes = necShape(null, false);
			assertEquals(List.of(), judge(json(""), classes, classes), platform.displayName());
			assertEquals(List.of(), CompatibilityFindings.all(), platform.displayName());
		}
	}

	/** Natively an injector that must inject throws "Critical injection failure"; it is a loss on both, as before. */
	@Test void aRequiredInjectionIsStillALossWhenVanillaLacksTheTargetToo() {
		owned(Ecosystem.FABRIC);
		Map<String, byte[]> classes = necShape(null, false);
		assertEquals(List.of("TileEntityMixin"), judge(json(",\"injectors\":{\"defaultRequire\":1}"), classes, classes),
				"defaultRequire 1 makes the injector mandatory natively too");
		CompatibilityFindings.reset();
		classes = necShape(1, false);
		assertEquals(List.of("TileEntityMixin"), judge(json(""), classes, classes), "so does the injector's own require=1");
		CompatibilityFindings.reset();
		classes = necShape(0, false);
		assertEquals(List.of(), judge(json(",\"injectors\":{\"defaultRequire\":1}"), classes, classes),
				"…and require=0 on the injector wins over the config's default, as InjectionInfo reads it");
	}

	/** A @Group needs one injection between its members, so a grouped injector is never silently empty. */
	@Test void aGroupedInjectorIsNeverDroppedSilently() {
		owned(Ecosystem.FABRIC);
		Map<String, byte[]> classes = necShape(null, true);
		assertEquals(List.of("TileEntityMixin"), judge(json(""), classes, classes));
	}

	/** Under mixin.debug.countInjections native Mixin fails on an empty injector whatever it requires. */
	@Test void countingInjectionsMakesTheEmptyInjectorALossAgain() {
		owned(Ecosystem.FABRIC);
		Map<String, byte[]> classes = necShape(null, false);
		System.setProperty("mixin.debug", "true");
		assertEquals(List.of("TileEntityMixin"), judge(json(""), classes, classes));
	}

	/**
	 * The raw class still declares it, only the chain took it away: whether vanilla had it is unknowable here, so it
	 * stays the merge's miss.
	 */
	@Test void aMethodOnlyTheTransformChainRemovedStaysAMiss() {
		owned(Ecosystem.FABRIC);
		Map<String, byte[]> chained = necShape(null, false);
		Map<String, byte[]> raw = new HashMap<>(chained);
		raw.put(BLOCK_ENTITY + ".class", blockEntity("fillCrashReportCategory", "populateCrashReport"));
		assertEquals(List.of("TileEntityMixin"), judge(json(""), chained, raw));
	}

	// --- whose game: the version the mod asks for ----------------------------------------------------------------------

	/** The game each platform's rows describe, as the shipped table records it; the cases below stand on it. */
	@Test void theShippedTableNamesTheGameEachPlatformsRowsDescribe() {
		NativeAbsentTargets.Table table = NativeAbsentTargets.shipped();
		assertEquals(Map.of("minecraft", "26.2"), table.of(Ecosystem.FABRIC).versions());
		assertEquals(Map.of("minecraft", "26.2", "forge", "65.0.1"), table.of(Ecosystem.FORGE).versions());
		assertEquals(Map.of("minecraft", "26.2", "neoforge", "26.2.0.88"), table.of(Ecosystem.NEOFORGE).versions());
	}

	/**
	 * A mod whose mandatory range excludes the game the rows describe would not run on it natively — its own loader
	 * refuses it — and the newer game it was built for may declare the method: not answered, the miss is the merge's.
	 * A range that admits the game, an optional requirement, and a requirement on something other than the game change
	 * nothing. A range nobody can read is not shown to admit the game, so it is not answered either.
	 */
	@Test void aModItsOwnLoaderWouldNotRunOnTheTablesGameIsNotAnswered() {
		record Case(Ecosystem platform, UnifiedDependency requires, boolean kept) {
		}
		List<Case> cases = List.of(
				new Case(Ecosystem.FABRIC, required("minecraft", "26.2"), true),
				new Case(Ecosystem.FABRIC, required("minecraft", "~26.2"), true),
				new Case(Ecosystem.FABRIC, required("minecraft", ">=26.2-alpha.1 <26.3"), true),
				new Case(Ecosystem.FABRIC, required("fabricloader", ">=0.99"), true),
				new Case(Ecosystem.FABRIC, new UnifiedDependency("minecraft", ">=26.3", false), true),
				new Case(Ecosystem.FABRIC, required("minecraft", ">=26.3"), false),
				new Case(Ecosystem.FABRIC, required("minecraft", "26.1.x"), false),
				new Case(Ecosystem.FABRIC, required("minecraft", "?!"), false),
				new Case(Ecosystem.FORGE, required("forge", ">=65"), true),
				new Case(Ecosystem.FORGE, required("minecraft", ">=26.2 <26.3"), true),
				new Case(Ecosystem.FORGE, required("neoforge", ">=99"), true),
				new Case(Ecosystem.FORGE, required("forge", ">=65.1"), false),
				new Case(Ecosystem.FORGE, required("minecraft", "=26.3"), false),
				new Case(Ecosystem.NEOFORGE, required("neoforge", ">=26.2.0.80"), true),
				new Case(Ecosystem.NEOFORGE, required("NeoForge", ">=26.2.0.90"), false),
				new Case(Ecosystem.NEOFORGE, required("minecraft", ">=26.3"), false));
		List<String> wrong = new java.util.ArrayList<>();
		for (Case one : cases) {
			clean();
			owned(one.platform(), one.requires());
			Map<String, byte[]> classes = necShape(null, false);
			List<String> left = judge(json(""), classes, classes);
			boolean kept = left.isEmpty() && CompatibilityFindings.all().isEmpty();
			boolean leftOut = left.equals(List.of("TileEntityMixin")) && confirmedRequired();
			if (one.kept() ? !kept : !leftOut) wrong.add(one + " left " + left + " " + CompatibilityFindings.all());
		}
		assertEquals(List.of(), wrong, "cases judged against the wrong game");
	}

	/**
	 * RED control: without the owning mod's manifest — none published, one of the other platform under the same id, or
	 * a mod that only answers to the id as an alias — nothing shows the mod is native to the table's game.
	 */
	@Test void aModWhoseManifestTheKernelCannotSeeIsNotAnswered() {
		MixinConfigOwners.publish(List.of(new MixinConfigOwners.Owned(CONFIG, "nec", Ecosystem.FABRIC)));
		Map<String, byte[]> classes = necShape(null, false);
		assertEquals(List.of("TileEntityMixin"), judge(json(""), classes, classes), "no manifest published");
		assertTrue(confirmedRequired(), CompatibilityFindings.all().toString());

		CompatibilityFindings.reset();
		ModPresence.publishForgeFamily(List.of(mod(Ecosystem.NEOFORGE, "nec")));
		assertEquals(List.of("TileEntityMixin"), judge(json(""), classes, classes), "the NeoForge mod of that id is not it");
		assertTrue(confirmedRequired(), CompatibilityFindings.all().toString());

		CompatibilityFindings.reset();
		ModPresence.publishForgeFamily(List.of());
		ModPresence.publishFabric(List.of(mod(Ecosystem.FABRIC, "nec-fabric").withAliases(List.of("nec"))));
		assertEquals(List.of("TileEntityMixin"), judge(json(""), classes, classes), "a mod answering to the id as an alias");
		assertTrue(confirmedRequired(), CompatibilityFindings.all().toString());

		CompatibilityFindings.reset();
		ModPresence.publishFabric(List.of(mod(Ecosystem.FABRIC, "nec")));
		assertEquals(List.of(), judge(json(""), classes, classes), "the mod itself, published as discovery publishes it");
	}

	// --- Minecraft's own libraries -------------------------------------------------------------------------------------

	/**
	 * A class served by one of the library jars the table lists is answered from its own bytes; one served by any other
	 * jar is not, and the warning calls that jar neither a base nor a library — it may be either.
	 */
	@Test void aMinecraftLibraryTheTableListsIsAnsweredForAsItIs() {
		owned(Ecosystem.FABRIC);
		String dispatcher = "com/mojang/brigadier/CommandDispatcher";
		Map<String, byte[]> classes = necShape(null, false, "forbricNoSuchMethod", dispatcher);
		classes.put(dispatcher + ".class", targetClass(dispatcher, false, "execute"));
		String listed = NativeAbsentTargets.shipped().libraries().iterator().next();
		assertEquals(List.of(), KernelGuestMixinAdapter.unfitMixins(CONFIG, json(""), classes::get, null, classes::get,
				owner -> listed), "a listed library jar is what every platform loads");
		assertEquals(List.of(), CompatibilityFindings.all());

		assertEquals(List.of("TileEntityMixin"), KernelGuestMixinAdapter.unfitMixins(CONFIG, json(""), classes::get, null,
				classes::get, owner -> "an older brigadier"), "a library jar the table does not list");
		assertTrue(confirmedRequired(), CompatibilityFindings.all().toString());

		String warning = NativeAbsentTargets.mismatch(dispatcher, "an older brigadier", "e49c");
		assertTrue(warning.contains(dispatcher + " is served by a jar with members an older brigadier, which is neither the "
				+ "merged base"), warning);
		assertTrue(warning.contains("nor one of the Minecraft library jars it lists"), warning);
		assertFalse(warning.contains("a base with members"), warning);
		assertTrue(NativeAbsentTargets.mismatch(dispatcher, null, null).contains("served by no jar the kernel can read"));
	}

	@Test void theTableRecordsEachPlatformsGameAndTheLibraryJars() {
		NativeAbsentTargets.Table table = NativeAbsentTargets.Table.parse(List.of("base b", "library l1 com.mojang:brigadier:1.3.10",
				"library l2 com.mojang:datafixerupper:10.0.21", "platform fabric minecraft=26.2",
				"platform neoforge minecraft=26.2 neoforge=26.2.0.88 malformed =x y=", "platform nobody minecraft=1"));
		assertEquals(java.util.Set.of("l1", "l2"), table.libraries());
		assertEquals(Map.of("minecraft", "26.2"), table.of(Ecosystem.FABRIC).versions());
		assertEquals(Map.of("minecraft", "26.2", "neoforge", "26.2.0.88"), table.of(Ecosystem.NEOFORGE).versions());
		assertNull(table.of(Ecosystem.FORGE), "a platform without its line is not spoken for");

		NativeAbsentTargets.Rows rows = table.of(Ecosystem.NEOFORGE);
		assertNull(NativeAbsentTargets.unmetRequirement(rows, mod(Ecosystem.NEOFORGE, "m", required("neoforge", ">=26.2.0.88"))));
		assertEquals("neoforge >=26.2.0.89",
				NativeAbsentTargets.unmetRequirement(rows, mod(Ecosystem.NEOFORGE, "m", required("neoforge", ">=26.2.0.89"))));
		assertNull(NativeAbsentTargets.unmetRequirement(rows, mod(Ecosystem.NEOFORGE, "m", required("forge", ">=99"))),
				"a requirement on something the line does not name is not the game's");
	}

	// --- which selectors are a plain name lookup -----------------------------------------------------------------------

	/**
	 * Only what Mixin reads as a plain name — optionally a descriptor, optionally the target as owner — finds nothing
	 * silently. A dynamic selector resolves somewhere else (MixinSquared's {@code @MixinSquared:Handler} to another
	 * mixin's merged handler), a quantifier's minimum throws whatever {@code require} says, and a dotted name is an
	 * owner and a name: none of those is answered, so a real loss written that way stays one.
	 */
	@Test void onlyAPlainNameIsAnsweredFor() {
		assertArrayEquals(new String[] {"populateCrashReport", null}, NativeAbsentTargets.plainSelector("populateCrashReport", BLOCK_ENTITY));
		assertArrayEquals(new String[] {"populateCrashReport", CATEGORY},
				NativeAbsentTargets.plainSelector("populateCrashReport" + CATEGORY, BLOCK_ENTITY));
		assertArrayEquals(new String[] {"populateCrashReport", null},
				NativeAbsentTargets.plainSelector("L" + BLOCK_ENTITY + ";populateCrashReport", BLOCK_ENTITY), "its own owner");
		assertArrayEquals(new String[] {"<init>", "()V"}, NativeAbsentTargets.plainSelector("<init>()V", BLOCK_ENTITY));
		assertArrayEquals(new String[] {"lambda$tick$0", null}, NativeAbsentTargets.plainSelector("lambda$tick$0", BLOCK_ENTITY));

		for (String other : List.of("@MixinSquared:Handler", "@Desc(foo)", "populateCrashReport+", "populateCrashReport{1,}",
				"populateCrashReport{2}", "populateCrashReport*", "/populate.*/", "net.minecraft.world.level.block.entity.BlockEntity.populateCrashReport",
				"a.b.C.populateCrashReport", "Lnet/example/Other;populateCrashReport", "populateCrashReport:" + CATEGORY,
				"populateCrashReport(I)", "populateCrashReport(Lfoo)V", "populateCrashReport(Ljava.lang.String;)V",
				"populate-crash", "populateCrashReport ()V", "foo->bar", "net/minecraft/Foo", "")) {
			assertNull(NativeAbsentTargets.plainSelector(other, BLOCK_ENTITY), other);
		}
	}

	/** RED shape: each of those, as the NEC injector's selector, leaves the mixin out as a required loss, as before. */
	@Test void aSelectorThatIsNotAPlainNameStaysALoss() {
		owned(Ecosystem.FABRIC);
		List<String> kept = new java.util.ArrayList<>();
		for (String selector : List.of("@MixinSquared:Handler", "populateCrashReport+", "populateCrashReport{1,}",
				"net.minecraft.world.level.block.entity.BlockEntity.populateCrashReport", "Lnet/example/Other;populateCrashReport")) {
			CompatibilityFindings.reset();
			Map<String, byte[]> classes = necShape(null, false, selector);
			if (!judge(json(""), classes, classes).equals(List.of("TileEntityMixin")) || !confirmedRequired()) kept.add(selector);
		}
		assertEquals(List.of(), kept, "selectors whose miss was not left out as a CONFIRMED required loss");
		for (String selector : List.of("L" + BLOCK_ENTITY + ";populateCrashReport", "populateCrashReport" + CATEGORY)) {
			CompatibilityFindings.reset();
			Map<String, byte[]> classes = necShape(null, false, selector);
			assertEquals(List.of(), judge(json(""), classes, classes), selector + " is the plain name, and still kept");
		}
	}

	// --- the base the table speaks for ---------------------------------------------------------------------------------

	/**
	 * RED control: a class served by a base whose members digest is not the table's — or by no jar at all — is not
	 * answered for; the miss is the merge's, as before. A base that lost a method the staged one kept would otherwise
	 * have it called absent, with no row to say otherwise.
	 */
	@Test void aBaseTheTableWasNotDerivedFromIsNotAnsweredFor() {
		owned(Ecosystem.FABRIC);
		Map<String, byte[]> classes = necShape(null, false);
		assertEquals(List.of("TileEntityMixin"), KernelGuestMixinAdapter.unfitMixins(CONFIG, json(""), classes::get, null,
				classes::get, owner -> "another base"));
		assertTrue(confirmedRequired(), CompatibilityFindings.all().toString());
		CompatibilityFindings.reset();
		assertEquals(List.of("TileEntityMixin"), KernelGuestMixinAdapter.unfitMixins(CONFIG, json(""), classes::get, null,
				classes::get, owner -> null), "served by no jar the kernel can read");
		assertTrue(confirmedRequired(), CompatibilityFindings.all().toString());
		CompatibilityFindings.reset();
		assertEquals(List.of("TileEntityMixin"), KernelGuestMixinAdapter.unfitMixins(CONFIG, json(""), classes::get, null,
				classes::get, null), "no base view at all");
	}

	/** The override names the base to trust instead: what a fixture game's weave test needs. */
	@Test void theBasePropertyNamesTheBaseToTrustInstead() {
		owned(Ecosystem.FABRIC);
		Map<String, byte[]> classes = necShape(null, false);
		System.setProperty(NativeAbsentTargets.BASE_PROPERTY, "fixture base");
		assertEquals(List.of(), KernelGuestMixinAdapter.unfitMixins(CONFIG, json(""), classes::get, null, classes::get,
				owner -> "fixture base"));
		assertEquals(List.of(), CompatibilityFindings.all());
	}

	/** Members, not bytes: a rebuild that moves only code keeps the digest, a method gained or lost changes it. */
	@Test void theMembersDigestSeesMethodsAndNothingElse() {
		Map<String, byte[]> base = Map.of(BLOCK_ENTITY + ".class", blockEntity("fillCrashReportCategory"),
				"net/example/Elsewhere.class", blockEntity("x"));
		String digest = NativeAbsentTargets.membersDigest(base);
		assertEquals(digest, NativeAbsentTargets.membersDigest(Map.of(BLOCK_ENTITY + ".class", blockEntity("fillCrashReportCategory"))),
				"a class outside vanilla's packages is not counted");
		assertEquals(digest, NativeAbsentTargets.membersDigest(Map.of(BLOCK_ENTITY + ".class", blockEntityWithBody("fillCrashReportCategory"))),
				"a body is not counted");
		assertNotEquals(digest, NativeAbsentTargets.membersDigest(Map.of(BLOCK_ENTITY + ".class", blockEntity())),
				"a method lost is");
		assertNotEquals(digest, NativeAbsentTargets.membersDigest(Map.of(BLOCK_ENTITY + ".class",
				blockEntity("fillCrashReportCategory", "populateCrashReport"))), "a method gained is");
	}

	// --- the predicate -----------------------------------------------------------------------------------------------

	@Test void aRowOrTheRawClassMakesAMethodThePlatforms() {
		Function<String, byte[]> raw = Map.of(BLOCK_ENTITY + ".class", blockEntity("fillCrashReportCategory"))::get;
		NativeAbsentTargets.Table rows = NativeAbsentTargets.Table.parse(List.of("# comment", "base b",
				"platform fabric", "platform forge",
				"fabric " + BLOCK_ENTITY + "#lost(I)V", "fabric merged-only net/minecraft/OnlyMerged",
				"forge " + BLOCK_ENTITY + "#forgeOnly()V", "forge merged-only net/minecraft/ForgeLacks",
				"neoforge " + BLOCK_ENTITY + "#notDeclared()V"));
		Ecosystem fabric = Ecosystem.FABRIC;

		assertEquals("b", rows.base());
		assertTrue(NativeAbsentTargets.nativeLacks(fabric, BLOCK_ENTITY, "populateCrashReport", null, raw, rows));
		assertFalse(NativeAbsentTargets.nativeLacks(fabric, BLOCK_ENTITY, "fillCrashReportCategory", null, raw, rows), "raw has it");
		assertFalse(NativeAbsentTargets.nativeLacks(fabric, BLOCK_ENTITY, "lost", "(I)V", raw, rows), "a row: vanilla has it");
		assertFalse(NativeAbsentTargets.nativeLacks(fabric, BLOCK_ENTITY, "lost", null, raw, rows), "by name, too");
		assertTrue(NativeAbsentTargets.nativeLacks(fabric, BLOCK_ENTITY, "lost", "(J)V", raw, rows), "another descriptor is not the row");
		assertFalse(NativeAbsentTargets.nativeLacks(fabric, "net/minecraft/OnlyMerged", "x", null, name -> blockEntity(), rows),
				"a class vanilla does not have is not answered for");
		assertFalse(NativeAbsentTargets.nativeLacks(fabric, "org/example/ModClass", "x", null, name -> blockEntity(), rows),
				"nor a class outside vanilla's packages");
		assertFalse(NativeAbsentTargets.nativeLacks(fabric, "net/minecraft/Unseen", "x", null, name -> null, rows),
				"nor one the raw view cannot serve");

		// Each platform reads its own rows only.
		assertTrue(NativeAbsentTargets.nativeLacks(fabric, BLOCK_ENTITY, "forgeOnly", null, raw, rows), "vanilla lacks it");
		assertFalse(NativeAbsentTargets.nativeLacks(Ecosystem.FORGE, BLOCK_ENTITY, "forgeOnly", null, raw, rows),
				"MinecraftForge's game has it");
		assertTrue(NativeAbsentTargets.nativeLacks(Ecosystem.FORGE, BLOCK_ENTITY, "lost", null, raw, rows),
				"vanilla's row says nothing of MinecraftForge's game");
		assertTrue(NativeAbsentTargets.nativeLacks(fabric, "net/minecraft/ForgeLacks", "x", null, name -> blockEntity(), rows));
		assertFalse(NativeAbsentTargets.nativeLacks(Ecosystem.FORGE, "net/minecraft/ForgeLacks", "x", null, name -> blockEntity(), rows));
		assertFalse(NativeAbsentTargets.nativeLacks(Ecosystem.NEOFORGE, BLOCK_ENTITY, "populateCrashReport", null, raw, rows),
				"a platform the table does not declare is never answered for, rows or not");
		assertFalse(NativeAbsentTargets.nativeLacks(null, BLOCK_ENTITY, "populateCrashReport", null, raw, rows), "nor no platform");
	}

	/**
	 * The count native Mixin requires: what the mod wrote, where anything below 0 requires nothing (the {@code -1} mods
	 * write is no unknown), and unknown only where a parent may supply it ({@code mergeFrom} replaces only a 0).
	 */
	@Test void theConfigsDefaultIsReadAsMixinCountsIt() {
		int unknown = NativeAbsentTargets.UNKNOWN_DEFAULT_REQUIRE;
		assertEquals(0, KernelGuestMixinAdapter.declaredDefaultRequire(parse("{}")));
		assertEquals(1, KernelGuestMixinAdapter.declaredDefaultRequire(parse("{\"injectors\":{\"defaultRequire\":1}}")));
		assertEquals(0, KernelGuestMixinAdapter.declaredDefaultRequire(parse("{\"injectors\":{\"defaultRequire\":-1}}")),
				"-1 requires nothing: no Mixin check fails on a count of 0 or less");
		assertEquals(0, KernelGuestMixinAdapter.declaredDefaultRequire(parse("{\"injectors\":{\"defaultRequire\":-5}}")));
		assertEquals(unknown, KernelGuestMixinAdapter.declaredDefaultRequire(parse("{\"parent\":\"p.mixins.json\"}")),
				"a parent may supply it: unknown, never none");
		assertEquals(unknown, KernelGuestMixinAdapter.declaredDefaultRequire(
				parse("{\"parent\":\"p\",\"injectors\":{\"defaultRequire\":0}}")), "a written 0 is replaced by the parent's too");
		assertEquals(unknown, KernelGuestMixinAdapter.declaredDefaultRequire(parse("{\"injectors\":{\"defaultRequire\":\"x\"}}")));
		assertEquals(2, KernelGuestMixinAdapter.declaredDefaultRequire(parse("{\"parent\":\"p\",\"injectors\":{\"defaultRequire\":2}}")));
		assertEquals(0, KernelGuestMixinAdapter.declaredDefaultRequire(parse("{\"parent\":\"p\",\"injectors\":{\"defaultRequire\":-1}}")),
				"a child's own -1 is not replaced by the parent's");
	}

	// ---------------------------------------------------------------------------------------------------------------

	/** The config's owner, and its manifest as discovery publishes it, declaring {@code requires}. */
	private static void owned(Ecosystem platform, UnifiedDependency... requires) {
		MixinConfigOwners.publish(List.of(new MixinConfigOwners.Owned(CONFIG, "nec", platform)));
		DiscoveredMod mod = mod(platform, "nec", requires);
		if (platform == Ecosystem.FABRIC) ModPresence.publishFabric(List.of(mod));
		else ModPresence.publishForgeFamily(List.of(mod));
	}

	private static DiscoveredMod mod(Ecosystem platform, String id, UnifiedDependency... requires) {
		return new DiscoveredMod(platform, id, "1.0", id, List.of(requires), List.of(CONFIG), null, id + ".jar");
	}

	private static UnifiedDependency required(String id, String constraint) {
		return new UnifiedDependency(id, constraint, true);
	}

	/** The adapter as the boot calls it, on a base the shipped table speaks for. */
	private static List<String> judge(byte[] config, Map<String, byte[]> chained, Map<String, byte[]> raw) {
		String staged = NativeAbsentTargets.shipped().base();
		return KernelGuestMixinAdapter.unfitMixins(CONFIG, config, chained::get, null, raw::get, owner -> staged);
	}

	private static boolean confirmedRequired() {
		return CompatibilityFindings.all().stream().anyMatch(f -> f.id().equals(LEFT_OUT)
				&& f.confidence() == CompatibilityFinding.Confidence.CONFIRMED && f.required());
	}

	private static byte[] json(String extra) {
		return ("{\"required\":true,\"package\":\"" + PKG.replace('/', '.') + "\",\"mixins\":[\"TileEntityMixin\"]" + extra + "}")
				.getBytes(StandardCharsets.UTF_8);
	}

	private static com.electronwill.nightconfig.core.UnmodifiableConfig parse(String json) {
		return com.electronwill.nightconfig.json.JsonFormat.fancyInstance().createParser().parse(json);
	}

	/** The merged BlockEntity (no populateCrashReport) and the mod's mixin, by resource path. */
	private static Map<String, byte[]> necShape(Integer require, boolean grouped) {
		return necShape(require, grouped, "populateCrashReport");
	}

	private static Map<String, byte[]> necShape(Integer require, boolean grouped, String selector) {
		return necShape(require, grouped, selector, BLOCK_ENTITY);
	}

	private static Map<String, byte[]> necShape(Integer require, boolean grouped, String selector, String target) {
		Map<String, byte[]> classes = new HashMap<>();
		classes.put(BLOCK_ENTITY + ".class", blockEntity("fillCrashReportCategory"));
		classes.put(PKG + "/TileEntityMixin.class", tileEntityMixin(require, grouped, selector, target));
		return classes;
	}

	private static byte[] blockEntity(String... methods) {
		return blockEntity(false, methods);
	}

	private static byte[] blockEntityWithBody(String... methods) {
		return blockEntity(true, methods);
	}

	private static byte[] blockEntity(boolean body, String... methods) {
		return targetClass(BLOCK_ENTITY, body, methods);
	}

	private static byte[] targetClass(String owner, boolean body, String... methods) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
		for (String name : methods) {
			MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, name, CATEGORY, null, null);
			mv.visitCode();
			if (body) {
				mv.visitInsn(Opcodes.ICONST_0);
				mv.visitInsn(Opcodes.POP);
			}
			mv.visitInsn(Opcodes.RETURN);
			mv.visitMaxs(1, 2);
			mv.visitEnd();
		}
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** A field of its own (no @Shadow) and one @Inject naming {@code selector}, optionally with require / a @Group. */
	private static byte[] tileEntityMixin(Integer require, boolean grouped, String selector, String target) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, PKG + "/TileEntityMixin", null, "java/lang/Object", null);
		AnnotationVisitor mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
		AnnotationVisitor targets = mixin.visitArray("value");
		targets.visit(null, Type.getObjectType(target));
		targets.visitEnd();
		mixin.visitEnd();
		cw.visitField(Opcodes.ACC_PRIVATE, "noNBT", "Z", null, null).visitEnd();
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "onPopulateCrashReport",
				"(Lnet/minecraft/CrashReportCategory;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", null, null);
		AnnotationVisitor inject = mv.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", true);
		AnnotationVisitor methods = inject.visitArray("method");
		methods.visit(null, selector);
		methods.visitEnd();
		if (require != null) inject.visit("require", require);
		inject.visitEnd();
		if (grouped) {
			AnnotationVisitor group = mv.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Group;", false);
			group.visit("name", "crash");
			group.visitEnd();
		}
		mv.visitCode();
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 3);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}
}
