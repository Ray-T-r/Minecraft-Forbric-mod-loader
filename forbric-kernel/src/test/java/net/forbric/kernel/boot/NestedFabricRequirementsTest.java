package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.*;
import net.fabricmc.api.EnvType;
import net.forbric.api.CompatibilityFindings;
import net.forbric.api.Side;
import net.forbric.kernel.discovery.ForbricModDiscoverer;
import net.forbric.kernel.fabric.FabricModDiscovery;
import net.forbric.kernel.fabric.NestedFabricRequirements;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * Nested Fabric mods are resolved as fabric-loader 0.19.5's ModSolver resolves them: a nested build whose
 * {@code minecraft}/{@code java} requirement excludes this game is not loaded, and neither is what needs only it.
 * The shape is ViaFabric 0.4.22's: one parent, one platform layer per Minecraft line under two different ids.
 */
@ResourceLock("ModCatalog") @ResourceLock("system-properties")
class NestedFabricRequirementsTest {
	private static final String GAME = "26.2";
	private static final String JAVA = String.valueOf(Runtime.version().feature());
	private static final String KEPT_BACK = "[Forbric/JiJ] nested host-mc26-1 1 in host.jar loaded although Fabric Loader would "
			+ "leave it out (minecraft >=26.1 <=26.1.2 does not include 26.2): host requires host-mc26-1 >=1, and nothing else "
			+ "installed meets that";
	@TempDir Path root;

	@BeforeEach @AfterEach void reset() {
		DuplicateModArbiter.reset(); MultiLoaderArbiter.reset(); CompatibilityFindings.reset();
		System.clearProperty(NestedFabricRequirements.SWITCH); System.clearProperty("forbric.crossJarArbitration");
	}

	@Test void aNestedBuildForAnotherMinecraftLineIsLeftOutAndItsSiblingLoads() throws Exception {
		installViaFabricShape();
		var decision = new DuplicateModArbiter.Decision[1];
		String log = capture(() -> decision[0] = DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME));
		// One line for each left-out mod, the one it bundles included.
		assertEquals(List.of("[Forbric/JiJ] nested host-mc26-1 1 in host.jar left out: minecraft >=26.1 <=26.1.2 does not "
				+ "include 26.2 — Fabric Loader does not load it either", "[Forbric/JiJ] nested inner 1 in host-mc26-1.jar left out: only mods left out bundle it — Fabric Loader does not "
				+ "load it either"), jijLines(log));
		var plan = DuplicateModArbiter.currentPlan();
		assertEquals(Set.of("host-mc26-2"), nestedIds(plan));
		Path old = only(plan, "host-mc26-1");
		assertTrue(plan.inventory().nodes().get(old).excluded());
		assertTrue(decision[0].suppressed(old), "the left-out build must not reach the classpath");
		assertFalse(decision[0].rescueJars().contains(old), "nor be lent a class as a last resort");
		assertEquals(Map.of(old, "minecraft >=26.1 <=26.1.2 does not include 26.2", only(plan, "inner"),
				"only mods left out bundle it"), plan.inventory().leftOut());
		assertTrue(plan.inventory().keptBack().isEmpty());
		FabricModDiscovery discovery = new FabricModDiscovery(EnvType.SERVER, root.resolve("legacy-cache"));
		discovery.discover(mods());
		assertEquals(Set.of("host", "host-mc26-2"), ids(discovery));
		assertTrue(plan.verify(discovery.getClasspathJars()));
	}

	@Test void theOffSwitchLoadsEveryNestedBuildAgain() throws Exception {
		installViaFabricShape();
		System.setProperty(NestedFabricRequirements.SWITCH, "off");
		DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME);
		assertEquals(Set.of("host-mc26-1", "inner", "host-mc26-2"), nestedIds(DuplicateModArbiter.currentPlan()));
		assertTrue(DuplicateModArbiter.currentPlan().inventory().leftOut().isEmpty());
	}

	@Test void discoveryWithoutAPlanAppliesTheSameRule() throws Exception {
		installViaFabricShape();
		System.setProperty("forbric.crossJarArbitration", "off");
		DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME);
		assertNull(DuplicateModArbiter.planned(mods(), EnvType.SERVER));
		FabricModDiscovery discovery = new FabricModDiscovery(EnvType.SERVER, root.resolve("jij"));
		discovery.setPlatform(NestedFabricRequirements.Platform.running(GAME));
		String log = capture(() -> discovery.discover(mods()));
		assertEquals(Set.of("host", "host-mc26-2"), ids(discovery));
		assertEquals(2, discovery.getClasspathJars().size());
		assertEquals(List.of("[Forbric/JiJ] nested host-mc26-1 1 in host.jar left out: minecraft >=26.1 <=26.1.2 does not "
				+ "include 26.2 — Fabric Loader does not load it either", "[Forbric/JiJ] nested inner 1 in host-mc26-1.jar left out: only mods left out bundle it — Fabric Loader does not "
				+ "load it either"), jijLines(log));
	}

	@Test void whatOnlyALeftOutModProvidesOrBundlesIsLeftOutWithIt() throws Exception {
		byte[] addon = fabric("addon", "1", Map.of("META-INF/jars/addonlib.jar", fabric("addonlib", "1", Map.of(), "")),
				",\"depends\":{\"host-mc26-1\":\"*\"}");
		byte[] compat = fabric("compat", "1", Map.of(), ",\"depends\":{\"nobody-installed-this\":\"*\"}");
		install("host.jar", fabric("host", "1", Map.of("META-INF/jars/mc26-1.jar", mc261(), "META-INF/jars/addon.jar", addon,
				"META-INF/jars/compat.jar", compat), ""));
		DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME);
		var plan = DuplicateModArbiter.currentPlan();
		// A dependency nothing installed meets is DependencyAudit's to report, as it was: only what Fabric Loader
		// provably could not load here is left out.
		assertEquals(Set.of("compat"), nestedIds(plan));
		assertEquals("it requires host-mc26-1 *, and every installed build of that is left out",
				plan.inventory().leftOut().get(only(plan, "addon")));
		assertEquals("only mods left out bundle it", plan.inventory().leftOut().get(only(plan, "addonlib")));
		assertEquals("only mods left out bundle it", plan.inventory().leftOut().get(only(plan, "inner")));
		FabricModDiscovery legacy = new FabricModDiscovery(EnvType.SERVER, root.resolve("jij"));
		legacy.setPlatform(NestedFabricRequirements.Platform.running(GAME));
		DuplicateModArbiter.reset();
		legacy.discover(mods());
		assertEquals(Set.of("host", "compat"), ids(legacy));
	}

	@Test void aNewerNestedCopyThisGameCannotRunDoesNotDisplaceAnOlderOneItCan() throws Exception {
		install("a.jar", fabric("a", "1", Map.of("META-INF/jars/lib.jar", fabric("lib", "1", Map.of(), "")), ""));
		install("b.jar", fabric("b", "1", Map.of("META-INF/jars/lib.jar", fabric("lib", "2", Map.of(),
				",\"depends\":{\"minecraft\":\"~26.1\"}")), ""));
		DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME);
		var plan = DuplicateModArbiter.currentPlan();
		assertEquals(List.of("1"), plan.nestedFiles().stream().map(p -> plan.inventory().nodes().get(p).claim().versionOf("lib")).toList());
	}

	@Test void theNewestNestedCopyStillWinsWhenEveryCopyCanRun() throws Exception {
		install("a.jar", fabric("a", "1", Map.of("META-INF/jars/lib.jar", fabric("lib", "1", Map.of(), "")), ""));
		install("b.jar", fabric("b", "1", Map.of("META-INF/jars/lib.jar", fabric("lib", "2", Map.of(),
				",\"depends\":{\"minecraft\":\"~26.2\"}")), ""));
		DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME);
		var plan = DuplicateModArbiter.currentPlan();
		assertEquals(List.of("2"), plan.nestedFiles().stream().map(p -> plan.inventory().nodes().get(p).claim().versionOf("lib")).toList());
		assertTrue(plan.inventory().leftOut().isEmpty());
	}

	@Test void aJarInModsIsNeverLeftOut() throws Exception {
		Path chosen = install("old.jar", mc261());
		var decision = DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME);
		assertFalse(decision.suppressed(chosen));
		assertTrue(DuplicateModArbiter.currentPlan().selected().contains(chosen.toAbsolutePath()));
		assertTrue(DuplicateModArbiter.currentPlan().inventory().leftOut().isEmpty());
	}

	@Test void javaAndBreaksAreJudgedButAnUnknownGameAnUnreadableRangeAndTheLoaderLevelAreNot() throws Exception {
		install("host.jar", fabric("host", "1", Map.of(
				"META-INF/jars/java.jar", fabric("needs-java-99", "1", Map.of(), ",\"depends\":{\"java\":\">=99\"}"),
				"META-INF/jars/breaks.jar", fabric("breaks-this-game", "1", Map.of(), ",\"breaks\":{\"minecraft\":\"26.2\"}"),
				"META-INF/jars/garbled.jar", fabric("garbled-range", "1", Map.of(), ",\"depends\":{\"minecraft\":\"<<26.1!\"}"),
				"META-INF/jars/loader.jar", fabric("newer-loader", "1", Map.of(), ",\"depends\":{\"fabricloader\":\">=9.0.0\"}"),
				"META-INF/jars/mc26-1.jar", mc261()), ""));
		DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME);
		var plan = DuplicateModArbiter.currentPlan();
		assertEquals(Set.of("garbled-range", "newer-loader"), nestedIds(plan));
		assertEquals("java >=99 does not include " + JAVA, plan.inventory().leftOut().get(only(plan, "needs-java-99")));
		assertEquals("it breaks minecraft 26.2, which includes 26.2", plan.inventory().leftOut().get(only(plan, "breaks-this-game")));

		DuplicateModArbiter.reset();
		DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER);
		assertTrue(nestedIds(DuplicateModArbiter.currentPlan()).contains("host-mc26-1"), "no game version, no minecraft verdict");
	}

	/**
	 * A NeoForge mod's nested jar is not Fabric Loader's to judge, even when all it carries is a fabric.mod.json:
	 * Fabric Loader never opens a jar without one. FML loads the child its metadata.json declares as the parent's
	 * library; the one that only sits in META-INF/jars no native loader loads, and the kernel keeps it as it always
	 * has. Neither is left out, whichever way the walk reached it.
	 */
	@Test void aForgeFamilyParentsJarJarChildIsNotFabricLoadersToJudge() throws Exception {
		byte[] somelib = fabric("somelib", "1.0.0", Map.of(), ",\"depends\":{\"minecraft\":\"~26.1\"}");
		byte[] otherlib = fabric("otherlib", "1.0.0", Map.of(), ",\"depends\":{\"minecraft\":\"~26.1\"}");
		install("neoparent.jar", neo("neoparent", Map.of("META-INF/jarjar/somelib.jar", somelib, "META-INF/jars/otherlib.jar", otherlib),
				List.of("META-INF/jarjar/somelib.jar")));
		install("host.jar", fabric("host", "1", Map.of("META-INF/jars/mc26-1.jar", mc261()), ""));
		String log = capture(() -> DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME));
		var plan = DuplicateModArbiter.currentPlan();
		assertEquals(Set.of("somelib", "otherlib"), nestedIds(plan));
		assertEquals(Set.of(only(plan, "host-mc26-1"), only(plan, "inner")), plan.inventory().leftOut().keySet());
		assertFalse(plan.inventory().nodes().get(only(plan, "somelib")).excluded());
		assertEquals(List.of("[Forbric/JiJ] nested host-mc26-1 1 in host.jar left out: minecraft >=26.1 <=26.1.2 does not "
				+ "include 26.2 — Fabric Loader does not load it either", "[Forbric/JiJ] nested inner 1 in mc26-1.jar left out: "
				+ "only mods left out bundle it — Fabric Loader does not load it either"), jijLines(log));
	}

	/** One jar, two parents: a Fabric mod's jars and a NeoForge mod's JarJar. It is kept whichever is walked first. */
	@Test void aJarAForgeFamilyParentAlsoBundlesIsKeptWhicheverRouteIsWalkedFirst() throws Exception {
		for (boolean fabricFirst : List.of(true, false)) {
			reset();
			try (var files = Files.list(mods())) { for (Path file : files.toList()) Files.delete(file); }
			install((fabricFirst ? "a" : "b") + "-host.jar", fabric("host", "1", Map.of("META-INF/jars/mc26-1.jar", mc261()), ""));
			install((fabricFirst ? "b" : "a") + "-neoparent.jar", neo("neoparent", Map.of("META-INF/jarjar/mc26-1.jar", mc261()),
					List.of("META-INF/jarjar/mc26-1.jar")));
			String log = capture(() -> DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME));
			var plan = DuplicateModArbiter.currentPlan();
			assertEquals(Set.of("host-mc26-1", "inner"), nestedIds(plan), "fabric first: " + fabricFirst);
			assertTrue(plan.inventory().leftOut().isEmpty(), "fabric first: " + fabricFirst);
			assertEquals(List.of(), jijLines(log), "fabric first: " + fabricFirst);
		}
	}

	/**
	 * A Fabric mod the kernel loads as one, even inside a NeoForge mod's JarJar, has its own jars resolved by Fabric's
	 * rule: that declaration is a Fabric mod's, and Fabric Loader is what reads it.
	 */
	@Test void aFabricModInsideAForgeFamilyModStillHasItsOwnJarsJudged() throws Exception {
		byte[] host = fabric("host", "1", Map.of("META-INF/jars/mc26-1.jar", mc261()), "");
		install("neoparent.jar", neo("neoparent", Map.of("META-INF/jarjar/host.jar", host), List.of("META-INF/jarjar/host.jar")));
		DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME);
		var plan = DuplicateModArbiter.currentPlan();
		assertEquals(Set.of("host"), nestedIds(plan));
		assertEquals(Map.of(only(plan, "host-mc26-1"), "minecraft >=26.1 <=26.1.2 does not include 26.2", only(plan, "inner"),
				"only mods left out bundle it"), plan.inventory().leftOut());
	}

	/**
	 * Native refuses to start when a jar in mods/ hard-depends on a nested mod it left out. The kernel loads that jar
	 * anyway, so leaving its dependency out would only break it: the nested mod is kept, with what it bundles, and a
	 * WARN says so. Both with the plan and without one.
	 */
	@Test void aModInModsThatRequiresALeftOutNestedModKeepsIt() throws Exception {
		Path host = install("host.jar", fabric("host", "1", Map.of("META-INF/jars/mc26-1.jar", mc261()),
				",\"depends\":{\"host-mc26-1\":\">=1\"}"));
		String log = capture(() -> DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME));
		var plan = DuplicateModArbiter.currentPlan();
		assertEquals(Set.of("host-mc26-1", "inner"), nestedIds(plan));
		assertTrue(plan.inventory().leftOut().isEmpty());
		assertEquals(Map.of(only(plan, "host-mc26-1"), new NestedFabricRequirements.KeptBack(
				"minecraft >=26.1 <=26.1.2 does not include 26.2", "host requires host-mc26-1 >=1, and nothing else installed "
				+ "meets that")), plan.inventory().keptBack());
		assertEquals(List.of(KEPT_BACK), jijLines(log));
		assertFalse(plan.inventory().nodes().get(only(plan, "host-mc26-1")).excluded());

		var present = new ForbricModDiscoverer().discoverJar(host);
		String audit = capture(() -> DependencyAudit.report(present, plan.nestedFiles(), Side.DEDICATED_SERVER));
		assertTrue(audit.contains("every hard dependency of 1 mod(s) is present and in range"), audit);

		System.setProperty("forbric.crossJarArbitration", "off");
		DuplicateModArbiter.reset();
		FabricModDiscovery discovery = new FabricModDiscovery(EnvType.SERVER, root.resolve("jij"));
		discovery.setPlatform(NestedFabricRequirements.Platform.running(GAME));
		String legacy = capture(() -> discovery.discover(mods()));
		assertEquals(Set.of("host", "host-mc26-1", "inner"), ids(discovery));
		assertEquals(List.of(KEPT_BACK), jijLines(legacy));
	}

	/** The same from another ecosystem: a NeoForge mod in mods/, and a Fabric jar a NeoForge mod's JarJar carries. */
	@Test void aForgeFamilyModOrItsJarJarChildThatRequiresALeftOutNestedModKeepsIt() throws Exception {
		for (boolean viaJarJarChild : List.of(false, true)) {
			reset();
			try (var files = Files.list(mods())) { for (Path file : files.toList()) Files.delete(file); }
			install("host.jar", fabric("host", "1", Map.of("META-INF/jars/mc26-1.jar", mc261()), ""));
			Path neoparent = install("neoparent.jar", viaJarJarChild
					? neo("neoparent", Map.of("META-INF/jarjar/needs-old.jar", fabric("needs-old", "1", Map.of(),
							",\"depends\":{\"host-mc26-1\":\"*\"}")), List.of("META-INF/jarjar/needs-old.jar"), "")
					: neo("neoparent", Map.of(), List.of(), "[[dependencies.neoparent]]\nmodId=\"host-mc26-1\"\ntype=\"required\"\n"
							+ "versionRange=\"[1,)\"\n"));
			DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME);
			var plan = DuplicateModArbiter.currentPlan();
			assertTrue(nestedIds(plan).contains("host-mc26-1"), "via JarJar child: " + viaJarJarChild);
			assertEquals((viaJarJarChild ? "needs-old requires host-mc26-1 *" : "neoparent requires host-mc26-1 >=1")
					+ ", and nothing else installed meets that", plan.inventory().keptBack().get(only(plan, "host-mc26-1")).because());

			// Without a plan, through the boot's own seam: Fabric discovery reads mods/ and Fabric jars only, so the
			// NeoForge mod's requirement, and the Fabric jar its JarJar carries, must be handed over to it.
			System.setProperty("forbric.crossJarArbitration", "off");
			DuplicateModArbiter.reset();
			var decision = DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME);
			assertNull(DuplicateModArbiter.currentPlan());
			List<Path> forgeFamily = KernelBoot.discoverForgeFamilyModJars(mods(), decision).jars();
			assertEquals(List.of(neoparent), forgeFamily);
			List<Path> jarJar = KernelBoot.extractForgeFamilyJarJar(forgeFamily, root);
			assertEquals(viaJarJarChild ? 1 : 0, jarJar.size());
			FabricModDiscovery discovery = KernelBoot.scanFabricMods(KernelBoot.Side.SERVER, root, decision, GAME, null,
					forgeFamily, jarJar);
			assertEquals(Set.of("host", "host-mc26-1", "inner"), ids(discovery), "via JarJar child: " + viaJarJarChild);
			System.clearProperty("forbric.crossJarArbitration");
		}
	}

	/**
	 * When no build installed meets what a loaded mod asks for and nothing that loads provides the id at all, the
	 * left-out copy is kept, as the kernel loaded it before the rule, and the WARN does not claim it meets the range.
	 * When a left-out copy does meet it, only that one is kept.
	 */
	@Test void aKeptCopyOutsideTheRequiredRangeIsSaidToBeOne() throws Exception {
		byte[] x2 = fabric("x", "2", Map.of(), ",\"depends\":{\"minecraft\":\"~26.1\"}");
		install("r.jar", fabric("r", "1", Map.of("META-INF/jars/x.jar", x2), ",\"depends\":{\"x\":\">=3\"}"));
		String outOfRange = "[Forbric/JiJ] nested x 2 in r.jar loaded although Fabric Loader would leave it out (minecraft "
				+ "~26.1 does not include 26.2): r requires x >=3; no installed build meets that, and no other x would load";
		String log = capture(() -> DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME));
		var plan = DuplicateModArbiter.currentPlan();
		assertEquals(Set.of("x"), nestedIds(plan));
		assertTrue(plan.inventory().leftOut().isEmpty());
		assertEquals(List.of(outOfRange), jijLines(log));

		System.setProperty("forbric.crossJarArbitration", "off");
		DuplicateModArbiter.reset();
		FabricModDiscovery discovery = new FabricModDiscovery(EnvType.SERVER, root.resolve("jij"));
		discovery.setPlatform(NestedFabricRequirements.Platform.running(GAME));
		String legacy = capture(() -> discovery.discover(mods()));
		assertEquals(Set.of("r", "x"), ids(discovery));
		assertEquals(List.of(outOfRange), jijLines(legacy));
		System.clearProperty("forbric.crossJarArbitration");

		// A second left-out copy that does meet the range: it alone comes back, and x 2 stays out.
		DuplicateModArbiter.reset();
		install("s.jar", fabric("s", "1", Map.of("META-INF/jars/x.jar", fabric("x", "3", Map.of(),
				",\"depends\":{\"minecraft\":\"~26.1\"}")), ""));
		capture(() -> DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME));
		var both = DuplicateModArbiter.currentPlan();
		Path three = both.inventory().nodes().values().stream().filter(n -> n.claim() != null && "3".equals(n.claim().versionOf("x")))
				.map(NestedCandidateInventory.Node::path).findFirst().orElseThrow();
		Path two = both.inventory().nodes().values().stream().filter(n -> n.claim() != null && "2".equals(n.claim().versionOf("x")))
				.map(NestedCandidateInventory.Node::path).findFirst().orElseThrow();
		assertEquals(Set.of(three), both.inventory().keptBack().keySet());
		assertEquals("r requires x >=3, and nothing else installed meets that", both.inventory().keptBack().get(three).because());
		assertEquals(Map.of(two, "minecraft ~26.1 does not include 26.2"), both.inventory().leftOut());
		assertEquals(List.of("3"), both.nestedFiles().stream().map(p -> both.inventory().nodes().get(p).claim().versionOf("x")).toList());
	}

	/** What a left-out mod bundles is kept, and the left-out mod with it, when a mod that loads needs it. */
	@Test void aModThatNeedsWhatALeftOutModBundlesKeepsTheChain() throws Exception {
		installViaFabricShape();
		install("user.jar", fabric("user", "1", Map.of(), ",\"depends\":{\"inner\":\"*\"}"));
		DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME);
		var plan = DuplicateModArbiter.currentPlan();
		assertEquals(Set.of("host-mc26-1", "inner", "host-mc26-2"), nestedIds(plan));
		assertEquals(Map.of(only(plan, "inner"), new NestedFabricRequirements.KeptBack("only mods left out bundle it",
				"user requires inner *, and nothing else installed meets that"), only(plan, "host-mc26-1"),
				new NestedFabricRequirements.KeptBack("minecraft >=26.1 <=26.1.2 does not include 26.2",
						"it bundles inner (user requires inner *, and nothing else installed meets that)")), plan.inventory().keptBack());
		assertTrue(plan.inventory().leftOut().isEmpty());

		System.setProperty("forbric.crossJarArbitration", "off");
		DuplicateModArbiter.reset();
		FabricModDiscovery discovery = new FabricModDiscovery(EnvType.SERVER, root.resolve("jij"));
		discovery.setPlatform(NestedFabricRequirements.Platform.running(GAME));
		discovery.discover(mods());
		assertEquals(Set.of("host", "host-mc26-1", "inner", "host-mc26-2", "user"), ids(discovery));
	}

	/**
	 * A left-out copy is kept when only it meets what a loaded mod asks for: here lib 2, beside a lib 1 that can run.
	 * Arbitration then picks between them as it did before the rule, and the newest wins.
	 */
	@Test void aLeftOutCopyIsKeptWhenOnlyItMeetsALoadedModsRange() throws Exception {
		install("a.jar", fabric("a", "1", Map.of("META-INF/jars/lib.jar", fabric("lib", "1", Map.of(), "")), ""));
		install("b.jar", fabric("b", "1", Map.of("META-INF/jars/lib.jar", fabric("lib", "2", Map.of(),
				",\"depends\":{\"minecraft\":\"~26.1\"}")), ""));
		install("c.jar", fabric("c", "1", Map.of(), ",\"depends\":{\"lib\":\">=2\"}"));
		DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME);
		var plan = DuplicateModArbiter.currentPlan();
		assertEquals(List.of("2"), plan.nestedFiles().stream().map(p -> plan.inventory().nodes().get(p).claim().versionOf("lib")).toList());
		assertEquals("c requires lib >=2, and nothing else installed meets that",
				plan.inventory().keptBack().values().iterator().next().because());
	}

	/** {@code off:<ids>} keeps exactly those ids out of the rule, for the player who needs one nested build anyway. */
	@Test void aPinnedIdIsKeptAndEveryOtherStillJudged() throws Exception {
		install("host.jar", fabric("host", "1", Map.of("META-INF/jars/mc26-1.jar", mc261(),
				"META-INF/jars/java.jar", fabric("needs-java-99", "1", Map.of(), ",\"depends\":{\"java\":\">=99\"}")), ""));
		System.setProperty(NestedFabricRequirements.SWITCH, "off: host-mc26-1 ,unrelated");
		DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME);
		var plan = DuplicateModArbiter.currentPlan();
		assertEquals(Set.of("host-mc26-1", "inner"), nestedIds(plan));
		assertEquals(Set.of(only(plan, "needs-java-99")), plan.inventory().leftOut().keySet());

		System.setProperty("forbric.crossJarArbitration", "off");
		DuplicateModArbiter.reset();
		FabricModDiscovery discovery = new FabricModDiscovery(EnvType.SERVER, root.resolve("jij"));
		discovery.setPlatform(NestedFabricRequirements.Platform.running(GAME));
		discovery.discover(mods());
		assertEquals(Set.of("host", "host-mc26-1", "inner"), ids(discovery));
	}

	/**
	 * owo-lib's shape: a mod that needs owo-lib nests owo-sentinel, which {@code provides} owo-lib's ids so it can ask
	 * for owo-lib when it is missing. Fabric Loader indexes provided ids with real ones and loads one mod per id, a jar
	 * in mods/ first, so next to owo-lib the sentinel is not loaded. Both with the plan and without one.
	 */
	@Test void aNestedModThatProvidesTheIdOfAJarInModsIsLeftOut() throws Exception {
		installOwoShape();
		String reason = "it answers to 'owo', as owo 0.13.1 in mods/ does, and Fabric Loader loads one mod per id, provides included";
		var decision = new DuplicateModArbiter.Decision[1];
		String log = capture(() -> decision[0] = DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME));
		var plan = DuplicateModArbiter.currentPlan();
		Path sentinel = only(plan, "owo-sentinel");
		assertEquals(Set.of(), nestedIds(plan));
		assertEquals(Map.of(sentinel, reason), plan.oneModPerId().leftOut());
		assertTrue(decision[0].suppressed(sentinel), "the left-out stand-in must not reach the classpath");
		assertFalse(decision[0].rescueJars().contains(sentinel));
		assertTrue(plan.inventory().leftOut().isEmpty(), "this is not the platform rule's");
		assertEquals(List.of("[Forbric/JiJ] nested owo-sentinel 0.13.1 in fishing.jar left out: " + reason
				+ " — Fabric Loader does not load it either"), jijLines(log));
		FabricModDiscovery planned = new FabricModDiscovery(EnvType.SERVER, root.resolve("planned"));
		planned.discover(mods());
		assertEquals(Set.of("fishing", "owo"), ids(planned));
		assertTrue(plan.verify(planned.getClasspathJars()));

		System.setProperty("forbric.crossJarArbitration", "off");
		DuplicateModArbiter.reset();
		FabricModDiscovery discovery = new FabricModDiscovery(EnvType.SERVER, root.resolve("jij"));
		discovery.setPlatform(NestedFabricRequirements.Platform.running(GAME));
		String legacy = capture(() -> discovery.discover(mods()));
		assertEquals(Set.of("fishing", "owo"), ids(discovery));
		assertEquals(List.of("[Forbric/JiJ] nested owo-sentinel 0.13.1 in fishing.jar left out: " + reason
				+ " — Fabric Loader does not load it either"), jijLines(legacy));
	}

	/** What the sentinel is for: with no owo-lib installed it is the only mod answering to owo, and it loads. */
	@Test void withNothingElseAnsweringToItsIdsTheStandInLoads() throws Exception {
		installOwoShape();
		Files.delete(mods().resolve("owo.jar"));
		DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME);
		var plan = DuplicateModArbiter.currentPlan();
		assertEquals(Set.of("owo-sentinel"), nestedIds(plan));
		assertTrue(plan.oneModPerId().leftOut().isEmpty());
	}

	/** Of two nested mods that answer to one id, Fabric Loader's order keeps the higher version, alias or not. */
	@Test void ofTwoNestedModsSharingAnIdTheHigherVersionLoads() throws Exception {
		install("a.jar", fabric("a", "1", Map.of("META-INF/jars/x.jar", fabric("x", "1", Map.of(), "")), ""));
		install("b.jar", fabric("b", "1", Map.of("META-INF/jars/y.jar", fabric("y", "2", Map.of(), ",\"provides\":[\"x\"]")), ""));
		DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME);
		var plan = DuplicateModArbiter.currentPlan();
		assertEquals(Set.of("y"), nestedIds(plan));
		assertEquals(Map.of(only(plan, "x"), "it answers to 'x', as y 2 does, and Fabric Loader loads one mod per id, provides included"),
				plan.oneModPerId().leftOut());

		// The same version: by id, here x first.
		reset();
		Files.write(mods().resolve("b.jar"), fabric("b", "1", Map.of("META-INF/jars/y.jar", fabric("y", "1", Map.of(),
				",\"provides\":[\"x\"]")), ""));
		DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME);
		assertEquals(Set.of("x"), nestedIds(DuplicateModArbiter.currentPlan()));
	}

	/**
	 * Fabric Loader's choice is one optimisation over its whole order, not a version contest per id: here lib 1 bundles
	 * aaa-helper, which Fabric orders before both rivals, so lib 1 loads with it and the newer lib-fork, which provides
	 * lib, does not. The same when a mod Fabric orders first depends on a version only the older one meets.
	 */
	@Test void aRivalThatAModOrderedFirstNeedsWinsOverAHigherVersion() throws Exception {
		install("a.jar", fabric("a", "1", Map.of("META-INF/jars/lib.jar", fabric("lib", "1.0.0",
				Map.of("META-INF/jars/aaa-helper.jar", fabric("aaa-helper", "1", Map.of(), "")), "")), ""));
		install("b.jar", fabric("b", "1", Map.of("META-INF/jars/lib-fork.jar", fabric("lib-fork", "2.0.0", Map.of(),
				",\"provides\":[\"lib\"]")), ""));
		DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME);
		var plan = DuplicateModArbiter.currentPlan();
		assertEquals(Set.of("lib", "aaa-helper"), nestedIds(plan));
		assertEquals(Map.of(only(plan, "lib-fork"), "it answers to 'lib', as lib 1.0.0 does, and Fabric Loader loads one mod per id, "
				+ "provides included"), plan.oneModPerId().leftOut());

		reset();
		try (var files = Files.list(mods())) { for (Path file : files.toList()) Files.delete(file); }
		install("a.jar", fabric("a", "1", Map.of("META-INF/jars/lib.jar", fabric("lib", "1.0.0", Map.of(), ""),
				"META-INF/jars/a-core.jar", fabric("a-core", "1", Map.of(), ",\"depends\":{\"lib\":\">=1 <2\"}")), ""));
		install("b.jar", fabric("b", "1", Map.of("META-INF/jars/lib-fork.jar", fabric("lib-fork", "2.0.0", Map.of(),
				",\"provides\":[\"lib\"]")), ""));
		DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME);
		assertEquals(Set.of("lib", "a-core"), nestedIds(DuplicateModArbiter.currentPlan()));
	}

	/**
	 * Native switches off a nested mod with a hard dependency nothing installed meets, and the id goes to its rival.
	 * The kernel never leaves a mod out for that alone, so it only yields: it loses each id it shares, and still loads
	 * where nothing contests it.
	 */
	@Test void aModWhoseDependencyNothingMeetsYieldsTheIdButLoadsUncontested() throws Exception {
		install("b.jar", fabric("b", "1", Map.of("META-INF/jars/lib-fork.jar", fabric("lib-fork", "2.0.0", Map.of(),
				",\"provides\":[\"lib\"],\"depends\":{\"nobody-installed-this\":\"*\"}")), ""));
		DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME);
		assertEquals(Set.of("lib-fork"), nestedIds(DuplicateModArbiter.currentPlan()));

		reset();
		install("a.jar", fabric("a", "1", Map.of("META-INF/jars/lib.jar", fabric("lib", "1.0.0", Map.of(), "")), ""));
		DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME);
		var plan = DuplicateModArbiter.currentPlan();
		assertEquals(Set.of("lib"), nestedIds(plan));
		assertEquals(Set.of(only(plan, "lib-fork")), plan.oneModPerId().leftOut().keySet());
	}

	/**
	 * The answer does not depend on jar names. m2 shares x with m1 and y with m3; Fabric orders m3, m2, m1 (higher
	 * versions move ahead where ids overlap), so m3 takes y, m2 cannot load, and m1 keeps x. Settling one id at a
	 * time in scan order used to leave m1 out as well.
	 */
	@Test void everyIdIsSettledInFabricLoadersOrderWhateverTheJarsAreCalled() throws Exception {
		for (List<String> names : List.of(List.of("a.jar", "b.jar", "c.jar"), List.of("c.jar", "b.jar", "a.jar"))) {
			reset();
			try (var files = Files.list(mods())) { for (Path file : files.toList()) Files.delete(file); }
			install(names.get(0), fabric("p1", "1", Map.of("META-INF/jars/m1.jar", fabric("m1", "1.0.0", Map.of(), ",\"provides\":[\"x\"]")), ""));
			install(names.get(1), fabric("p2", "1", Map.of("META-INF/jars/m2.jar", fabric("m2", "3.0.0", Map.of(), ",\"provides\":[\"x\",\"y\"]")), ""));
			install(names.get(2), fabric("p3", "1", Map.of("META-INF/jars/m3.jar", fabric("m3", "5.0.0", Map.of(), ",\"provides\":[\"y\"]")), ""));
			DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME);
			assertEquals(Set.of("m1", "m3"), nestedIds(DuplicateModArbiter.currentPlan()), String.valueOf(names));
		}
	}

	/**
	 * Decided on what arbitration selected, so a build arbitration drops cannot push its rival out. Here the nested b 3
	 * provides x, but the b in mods/ wins id b, so x-impl answers to x and loads. Before, x-impl was left out against
	 * a b 3 that never loaded, and nothing answered to x.
	 */
	@Test void aBuildArbitrationDropsNeverTakesAnIdFromItsRival() throws Exception {
		install("b.jar", fabric("b", "5.0.0", Map.of(), ""));
		install("c.jar", fabric("c", "1", Map.of("META-INF/jars/b.jar", fabric("b", "3.0.0", Map.of(), ",\"provides\":[\"x\"]")), ""));
		install("d.jar", fabric("d", "1", Map.of("META-INF/jars/x-impl.jar", fabric("x-impl", "1.0.0", Map.of(), ",\"provides\":[\"x\"]")), ""));
		DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME);
		var plan = DuplicateModArbiter.currentPlan();
		assertEquals(Set.of("x-impl"), nestedIds(plan));
		assertTrue(plan.oneModPerId().leftOut().isEmpty());

		// A winner only a losing copy of a library bundles: the selector keeps l 2, so w is never selected and x loads.
		reset();
		try (var files = Files.list(mods())) { for (Path file : files.toList()) Files.delete(file); }
		install("a.jar", fabric("a", "1", Map.of("META-INF/jars/l.jar", fabric("l", "1", Map.of("META-INF/jars/w.jar",
				fabric("w", "5", Map.of(), ",\"provides\":[\"x\"]")), "")), ""));
		install("b.jar", fabric("b", "1", Map.of("META-INF/jars/l.jar", fabric("l", "2", Map.of(), "")), ""));
		install("q.jar", fabric("q", "1", Map.of("META-INF/jars/x.jar", fabric("x", "1", Map.of(), "")), ""));
		DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME);
		assertEquals(Set.of("l", "x"), nestedIds(DuplicateModArbiter.currentPlan()));
	}

	/**
	 * A dependency on the shared id no longer steers arbitration. q needs x, which both its own nested x and w (nested
	 * in m's Fabric build) answer to. The NeoForge build of m is preferred and loads; w goes with the Fabric build, so x
	 * loads. Excluding x before arbitration used to leave w as the dependency's only provider, so the Fabric build of m
	 * loaded instead, and a player's pin on the NeoForge build made the selection unsatisfiable.
	 */
	@Test void aDependencyOnTheSharedIdLeavesTheEcosystemPreferenceAlone() throws Exception {
		Path fabricBuild = install("m-fabric.jar", fabric("m", "1", Map.of("META-INF/jars/w.jar", fabric("w", "2", Map.of(),
				",\"provides\":[\"x\"]")), ""));
		Path neoBuild = install("m-neo.jar", neo("m", Map.of(), List.of()));
		install("q.jar", fabric("q", "1", Map.of("META-INF/jars/x.jar", fabric("x", "1", Map.of(), "")), ",\"depends\":{\"x\":\"*\"}"));
		var decision = DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME);
		var plan = DuplicateModArbiter.currentPlan();
		assertTrue(decision.suppressed(fabricBuild));
		assertFalse(decision.suppressed(neoBuild));
		assertEquals(Set.of("x"), nestedIds(plan));
		assertEquals(JointCandidateSelector.Status.SOLVED, plan.selection().status());
	}

	/**
	 * Builds of one mod are cross-jar arbitration's, also when they share an alias rather than their id (every
	 * mixinextras-fabric copy provides com_github_llamalad7_mixinextras): the rule leaves none of them out, and only a
	 * different mod that answers to the alias loses to the build Fabric Loader prefers.
	 */
	@Test void buildsOfOneModAreLeftToArbitrationAndOnlyAnotherModLoses() throws Exception {
		install("a.jar", fabric("a", "1", Map.of("META-INF/jars/lib.jar", fabric("lib", "1", Map.of(), ",\"provides\":[\"alias\"]")), ""));
		install("b.jar", fabric("b", "1", Map.of("META-INF/jars/lib.jar", fabric("lib", "2", Map.of(), ",\"provides\":[\"alias\"]")), ""));
		DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME);
		var plan = DuplicateModArbiter.currentPlan();
		assertTrue(plan.oneModPerId().leftOut().isEmpty());
		assertEquals(List.of("2"), plan.nestedFiles().stream().map(p -> plan.inventory().nodes().get(p).claim().versionOf("lib")).toList());

		reset();
		install("c.jar", fabric("c", "1", Map.of("META-INF/jars/alias.jar", fabric("alias", "1", Map.of(), "")), ""));
		DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME);
		var three = DuplicateModArbiter.currentPlan();
		assertEquals(Map.of(only(three, "alias"), "it answers to 'alias', as lib 2 does, and Fabric Loader loads one mod per id, "
				+ "provides included"), three.oneModPerId().leftOut());
		assertEquals(Set.of("lib"), nestedIds(three));
	}

	/**
	 * A loser's own jars go with it. A mod in mods/ that needs the loser, by its own id or by one it provides that
	 * nothing else that loads meets, keeps it, with a WARN. Native would drop the owo jar in mods/ here instead (no id of
	 * its own forces it), and refuses to start where one does.
	 */
	@Test void whatTheLoserBundlesGoesWithItUnlessALoadedModNeedsIt() throws Exception {
		byte[] sentinel = fabric("owo-sentinel", "0.13.1", Map.of("META-INF/jars/inner.jar", fabric("inner", "1", Map.of(), "")),
				",\"provides\":[\"owo\",\"owo-lib\"]");
		install("owo.jar", fabric("owo", "0.13.1", Map.of(), ",\"provides\":[\"owo-lib\"]"));
		install("fishing.jar", fabric("fishing", "1", Map.of("META-INF/jars/owo-sentinel.jar", sentinel), ""));
		DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME);
		var plan = DuplicateModArbiter.currentPlan();
		assertEquals(Set.of(), nestedIds(plan));
		assertEquals("only mods left out bundle it", plan.oneModPerId().leftOut().get(only(plan, "inner")));

		for (String need : List.of("\"owo-sentinel\":\"*\"", "\"owo\":\">=0.14\"")) {
			reset();
			Files.write(mods().resolve("fishing.jar"), fabric("fishing", "1", Map.of("META-INF/jars/owo-sentinel.jar",
					fabric("owo-sentinel", "0.14.0", Map.of("META-INF/jars/inner.jar", fabric("inner", "1", Map.of(), "")),
							",\"provides\":[\"owo\",\"owo-lib\"]")), ""));
			install("user.jar", fabric("user", "1", Map.of(), ",\"depends\":{" + need + "}"));
			String log = capture(() -> DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME));
			var kept = DuplicateModArbiter.currentPlan();
			assertEquals(Set.of("owo-sentinel", "inner"), nestedIds(kept), need);
			String because = need.startsWith("\"owo-sentinel\"") ? "user requires owo-sentinel *" : "user requires owo >=0.14";
			assertEquals(because + ", and nothing else that loads meets that",
					kept.oneModPerId().keptBack().get(only(kept, "owo-sentinel")).because());
			assertTrue(jijLines(log).contains("[Forbric/JiJ] nested owo-sentinel 0.14.0 in fishing.jar loaded although Fabric "
					+ "Loader would leave it out (it answers to 'owo', as owo 0.13.1 in mods/ does, and Fabric Loader loads one "
					+ "mod per id, provides included): " + because + ", and nothing else that loads meets that"), log);
		}
	}

	/**
	 * Jars in mods/ are the player's: two that share an id through an alias both stay (native drops Indium here, whose
	 * own id sodium also provides). Only Fabric mods are in Fabric Loader's index: a NeoForge mod with the id leaves a
	 * Fabric stand-in alone. And the rule's off switch turns this half off with the rest.
	 */
	@Test void jarsInModsAnotherEcosystemsModAndTheOffSwitchLeaveEveryCandidateIn() throws Exception {
		install("sodium.jar", fabric("sodium", "1", Map.of(), ",\"provides\":[\"indium\"]"));
		install("indium.jar", fabric("indium", "1", Map.of(), ""));
		var decision = DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME);
		assertFalse(decision.suppressed(mods().resolve("indium.jar")));
		assertFalse(decision.suppressed(mods().resolve("sodium.jar")));
		assertTrue(DuplicateModArbiter.currentPlan().oneModPerId().leftOut().isEmpty());

		reset();
		try (var files = Files.list(mods())) { for (Path file : files.toList()) Files.delete(file); }
		installOwoShape();
		Files.delete(mods().resolve("owo.jar"));
		install("owo-neo.jar", neo("owo", Map.of(), List.of()));
		DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME);
		assertEquals(Set.of("owo-sentinel"), nestedIds(DuplicateModArbiter.currentPlan()));

		reset();
		Files.delete(mods().resolve("owo-neo.jar"));
		installOwoShape();
		System.setProperty(NestedFabricRequirements.SWITCH, "off");
		DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME);
		assertEquals(Set.of("owo-sentinel"), nestedIds(DuplicateModArbiter.currentPlan()));
	}

	/**
	 * What only a loser bundles goes with it even when it is no Fabric mod: a library without fabric.mod.json, a
	 * NeoForge jar in its jars. Logging those used to throw (no Fabric manifest), and arbitration with it.
	 */
	@Test void aLoserTakesItsLibrariesAndOtherEcosystemsJarsWithIt() throws Exception {
		byte[] plain = zip(Map.of("lib/Plain.class", new byte[] {(byte) 0xca, (byte) 0xfe}));
		install("owo.jar", fabric("owo", "0.13.1", Map.of(), ",\"provides\":[\"owo-lib\"]"));
		install("fishing.jar", fabric("fishing", "1", Map.of("META-INF/jars/owo-sentinel.jar", fabric("owo-sentinel", "0.13.1",
				Map.of("META-INF/jars/plain.jar", plain, "META-INF/jars/neolib.jar", neo("neolib", Map.of(), List.of())),
				",\"provides\":[\"owo\",\"owo-lib\"]")), ""));
		String log = capture(() -> DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME));
		var plan = DuplicateModArbiter.currentPlan();
		assertEquals(Set.of(), nestedIds(plan));
		Path library = plan.inventory().nodes().values().stream().filter(n -> n.claim() == null && !n.root())
				.map(NestedCandidateInventory.Node::path).findFirst().orElseThrow();
		assertEquals("only mods left out bundle it", plan.oneModPerId().leftOut().get(library));
		assertEquals("only mods left out bundle it", plan.oneModPerId().leftOut().get(only(plan, "neolib")));
		assertFalse(plan.selected().contains(library));
		assertTrue(log.contains("[Forbric/JiJ] nested " + library.getFileName() + " in "), log);
	}

	/**
	 * A jar outside Fabric Loader's index never decides who wins. x 2 sorts before y 1 (which provides x), so x loads;
	 * a library inside y used to be weighed first and pull y in through its parent, leaving x out.
	 */
	@Test void aJarOutsideFabricsIndexDoesNotDecideARivalry() throws Exception {
		byte[] plain = zip(Map.of("lib/Plain.class", new byte[] {(byte) 0xca, (byte) 0xfe}));
		install("a.jar", fabric("a", "1", Map.of("META-INF/jars/x.jar", fabric("x", "2", Map.of(), "")), ""));
		install("b.jar", fabric("b", "1", Map.of("META-INF/jars/y.jar", fabric("y", "1", Map.of("META-INF/jars/plain.jar", plain),
				",\"provides\":[\"x\"]")), ""));
		DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME);
		var plan = DuplicateModArbiter.currentPlan();
		assertEquals(Set.of("x"), nestedIds(plan));
		assertTrue(plan.oneModPerId().leftOut().containsKey(only(plan, "y")));
	}

	/**
	 * Native switches off a mod whose dependency nothing meets, and with it what only it satisfies and what it
	 * bundles, so its rival loads. Here aaa-addon (needs cfg-fork) and aaa-child (in cfg-fork) sort before both rivals;
	 * they used to pull cfg-fork in and push the healthy cfg out.
	 */
	@Test void whatGoesWithAModWhoseDependencyNothingMeetsCannotPullItIn() throws Exception {
		for (boolean bundled : List.of(false, true)) {
			reset();
			try (var files = Files.list(mods())) { for (Path file : files.toList()) Files.delete(file); }
			byte[] fork = fabric("cfg-fork", "3.0.0", bundled ? Map.of("META-INF/jars/aaa-child.jar", fabric("aaa-child", "1", Map.of(), ""))
					: Map.of(), ",\"provides\":[\"cfg\"],\"depends\":{\"nobody-installed-this\":\">=9\"}");
			Map<String, byte[]> first = new LinkedHashMap<>(Map.of("META-INF/jars/cfg-fork.jar", fork));
			if (!bundled) first.put("META-INF/jars/aaa-addon.jar", fabric("aaa-addon", "1", Map.of(), ",\"depends\":{\"cfg-fork\":\"*\"}"));
			install("p1.jar", fabric("p1", "1", first, ""));
			install("p2.jar", fabric("p2", "1", Map.of("META-INF/jars/cfg.jar", fabric("cfg", "2.0.0", Map.of(), "")), ""));
			DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME);
			assertEquals(Set.of("cfg"), nestedIds(DuplicateModArbiter.currentPlan()), "bundled: " + bundled);
		}
	}

	/**
	 * Outside the rule means outside the rivalry, both ways. A Fabric jar a NeoForge mod's JarJar carries is never
	 * Fabric Loader's, so the owo it is and owo-sentinel both load; and off:x exempts x without pushing y (which
	 * provides x) out in its place.
	 */
	@Test void modsOutsideTheRuleAreNeverRivals() throws Exception {
		install("neohost.jar", neo("neohost", Map.of("META-INF/jarjar/owo.jar", fabric("owo", "0.13.1", Map.of(),
				",\"provides\":[\"owo-lib\"]")), List.of("META-INF/jarjar/owo.jar")));
		install("fishing.jar", fabric("fishing", "1", Map.of("META-INF/jars/owo-sentinel.jar", fabric("owo-sentinel", "0.13.1",
				Map.of(), ",\"provides\":[\"owo\",\"owo-lib\"]")), ""));
		DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME);
		var plan = DuplicateModArbiter.currentPlan();
		assertEquals(Set.of("owo", "owo-sentinel"), nestedIds(plan));
		assertTrue(plan.oneModPerId().leftOut().isEmpty());

		reset();
		try (var files = Files.list(mods())) { for (Path file : files.toList()) Files.delete(file); }
		install("a.jar", fabric("a", "1", Map.of("META-INF/jars/x.jar", fabric("x", "1", Map.of(), "")), ""));
		install("b.jar", fabric("b", "1", Map.of("META-INF/jars/y.jar", fabric("y", "2", Map.of(), ",\"provides\":[\"x\"]")), ""));
		System.setProperty(NestedFabricRequirements.SWITCH, "off:x");
		DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME);
		assertEquals(Set.of("x", "y"), nestedIds(DuplicateModArbiter.currentPlan()));
	}

	/**
	 * A loser that was arbitration's build of its own id gives that id back to arbitration: lib 3 provides x, which
	 * the jar a in mods/ is, so it goes, and arbitration runs again and loads lib 2 instead of leaving lib to nobody.
	 */
	@Test void anotherBuildOfALosersOwnIdTakesItsPlace() throws Exception {
		install("a.jar", fabric("a", "1", Map.of(), ",\"provides\":[\"x\"]"));
		install("p.jar", fabric("p", "1", Map.of("META-INF/jars/lib.jar", fabric("lib", "3", Map.of(), ",\"provides\":[\"x\"]")), ""));
		install("r.jar", fabric("r", "1", Map.of("META-INF/jars/lib.jar", fabric("lib", "2", Map.of(), "")), ""));
		DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME);
		var plan = DuplicateModArbiter.currentPlan();
		assertEquals(List.of("2"), plan.nestedFiles().stream().map(p -> plan.inventory().nodes().get(p).claim().versionOf("lib")).toList());
		assertEquals(1, plan.oneModPerId().leftOut().size());
		assertTrue(plan.oneModPerId().leftOut().values().iterator().next().startsWith("it answers to 'x', as a 1 in mods/ does"));
	}

	/**
	 * Without a plan nothing arbitrates: of two copies of b, KernelFabricLoader keeps the first found, b 1, so only it
	 * takes part, and x keeps its id rather than losing it to a b 2 that is dropped later.
	 */
	@Test void withoutAPlanOnlyTheCopyThatRegistersTakesPart() throws Exception {
		install("p1.jar", fabric("p1", "1", Map.of("META-INF/jars/b.jar", fabric("b", "1", Map.of(), "")), ""));
		install("p2.jar", fabric("p2", "1", Map.of("META-INF/jars/b.jar", fabric("b", "2", Map.of(), ",\"provides\":[\"x\"]")), ""));
		install("p3.jar", fabric("p3", "1", Map.of("META-INF/jars/x.jar", fabric("x", "1", Map.of(), "")), ""));
		System.setProperty("forbric.crossJarArbitration", "off");
		FabricModDiscovery discovery = new FabricModDiscovery(EnvType.SERVER, root.resolve("jij"));
		discovery.setPlatform(NestedFabricRequirements.Platform.running(GAME));
		discovery.discover(mods());
		assertTrue(ids(discovery).contains("x"), String.valueOf(ids(discovery)));
	}
	/**
	 * Only a mod that lost a rivalry stays excluded between arbitration rounds. lib 3 loses x to a and goes, and lib 2
	 * takes its place; d (which needs lib) and inner (in both builds of lib) went with lib 3 in the first round and come
	 * back with lib 2, as on Fabric.
	 */
	@Test void whatWentWithALoserComesBackWithTheBuildThatReplacesIt() throws Exception {
		byte[] inner = fabric("inner", "1", Map.of(), "");
		install("a.jar", fabric("a", "1", Map.of(), ",\"provides\":[\"x\"]"));
		install("p.jar", fabric("p", "1", Map.of("META-INF/jars/lib.jar", fabric("lib", "3", Map.of("META-INF/jars/inner.jar", inner),
				",\"provides\":[\"x\"]")), ""));
		install("r.jar", fabric("r", "1", Map.of("META-INF/jars/lib.jar", fabric("lib", "2", Map.of("META-INF/jars/inner.jar", inner), "")), ""));
		install("q.jar", fabric("q", "1", Map.of("META-INF/jars/d.jar", fabric("d", "1", Map.of(), ",\"depends\":{\"lib\":\"*\"}")), ""));
		String log = capture(() -> DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME));
		var plan = DuplicateModArbiter.currentPlan();
		assertEquals(Set.of("lib", "inner", "d"), nestedIds(plan));
		assertEquals(List.of("2"), plan.nestedFiles().stream().filter(p -> plan.inventory().nodes().get(p).claim().modIds().contains("lib"))
				.map(p -> plan.inventory().nodes().get(p).claim().versionOf("lib")).toList());
		assertEquals(List.of("[Forbric/JiJ] nested lib 3 in p.jar left out: it answers to 'x', as a 1 in mods/ does, and Fabric Loader "
				+ "loads one mod per id, provides included — Fabric Loader does not load it either"), jijLines(log));
	}

	/**
	 * Every build of owo-sentinel loses to the owo jar in mods/, so all go in one arbitration round, and each mod that
	 * was selected gets one line, however many mods nest their own sentinel.
	 */
	@Test void everyBuildThatLosesTheSameWayGoesAtOnce() throws Exception {
		installOwoShape();
		install("other.jar", fabric("other", "1", Map.of("META-INF/jars/owo-sentinel.jar", fabric("owo-sentinel", "0.13.0",
				Map.of(), ",\"provides\":[\"owo\",\"owo-lib\"]")), ""));
		install("third.jar", fabric("third", "1", Map.of("META-INF/jars/owo-sentinel.jar", fabric("owo-sentinel", "0.12.0",
				Map.of(), ",\"provides\":[\"owo\",\"owo-lib\"]")), ""));
		String log = capture(() -> DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME));
		var plan = DuplicateModArbiter.currentPlan();
		assertEquals(Set.of(), nestedIds(plan));
		assertEquals(1, jijLines(log).size(), log);
		assertTrue(log.contains("one mod per id: decided in 1 arbitration round(s)"), log);
	}

	/**
	 * A need from outside Fabric's index does not change Fabric's choice; it keeps the loser back. aaa 2 and bbb 1 both
	 * provide x, and Fabric loads aaa; a NeoForge mod that requires bbb (in mods/, or nested in a judged Fabric mod) keeps
	 * bbb back with a WARN, and aaa still loads.
	 */
	@Test void aNeedFabricNeverSeesKeepsTheLoserBackAndLeavesTheWinner() throws Exception {
		String requiresBbb = "[[dependencies.neoreq]]\nmodId=\"bbb\"\ntype=\"required\"\nversionRange=\"[1,)\"\n";
		for (boolean nestedRequirer : List.of(false, true)) {
			reset();
			try (var files = Files.list(mods())) { for (Path file : files.toList()) Files.delete(file); }
			Map<String, byte[]> host = new LinkedHashMap<>(Map.of(
					"META-INF/jars/aaa.jar", fabric("aaa", "2.0.0", Map.of(), ",\"provides\":[\"x\"]"),
					"META-INF/jars/bbb.jar", fabric("bbb", "1.0.0", Map.of(), ",\"provides\":[\"x\"]")));
			if (nestedRequirer) {
				host.put("META-INF/jars/pmod.jar", fabric("pmod", "1", Map.of("META-INF/jars/neoreq.jar",
						neo("neoreq", Map.of(), List.of(), requiresBbb)), ""));
			} else {
				install("neoreq.jar", neo("neoreq", Map.of(), List.of(), requiresBbb));
			}
			install("host.jar", fabric("host", "1", host, ""));
			String log = capture(() -> DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME));
			var plan = DuplicateModArbiter.currentPlan();
			assertTrue(nestedIds(plan).containsAll(Set.of("aaa", "bbb")), nestedRequirer + " " + nestedIds(plan));
			assertTrue(plan.oneModPerId().leftOut().isEmpty(), String.valueOf(plan.oneModPerId().leftOut()));
			assertTrue(plan.oneModPerId().keptBack().get(only(plan, "bbb")).because().startsWith("neoreq requires bbb"),
					String.valueOf(plan.oneModPerId().keptBack()));
			assertTrue(jijLines(log).stream().anyMatch(line -> line.startsWith("[Forbric/JiJ] nested bbb 1.0.0 in host.jar loaded although")), log);
		}
	}

	/** Without a plan, a copy that loses takes the later copies of its id that would lose the same way. */
	@Test void withoutAPlanEveryCopyOfTheLoserGoes() throws Exception {
		installOwoShape();
		install("other.jar", fabric("other", "1", Map.of("META-INF/jars/owo-sentinel.jar", fabric("owo-sentinel", "0.13.0",
				Map.of(), ",\"provides\":[\"owo\",\"owo-lib\"]")), ""));
		System.setProperty("forbric.crossJarArbitration", "off");
		FabricModDiscovery discovery = new FabricModDiscovery(EnvType.SERVER, root.resolve("jij"));
		discovery.setPlatform(NestedFabricRequirements.Platform.running(GAME));
		discovery.discover(mods());
		assertEquals(Set.of("owo", "fishing", "other"), ids(discovery));
	}
	/**
	 * A mod kept back for a need Fabric never sees comes with what it brings: bbb's own nested library q (which bbb
	 * does not list in depends, as Loom's include does not) and r, which needs bbb, load with it. And a need for a mod
	 * nested in a loser keeps back the loser that bundles it, with that said in the WARN.
	 */
	@Test void whatAKeptBackModBringsComesWithIt() throws Exception {
		install("neoreq.jar", neo("neoreq", Map.of(), List.of(), "[[dependencies.neoreq]]\nmodId=\"bbb\"\ntype=\"required\"\nversionRange=\"[1,)\"\n"));
		install("host.jar", fabric("host", "1", Map.of(
				"META-INF/jars/aaa.jar", fabric("aaa", "2.0.0", Map.of(), ",\"provides\":[\"x\"]"),
				"META-INF/jars/bbb.jar", fabric("bbb", "1.0.0", Map.of("META-INF/jars/q.jar", fabric("q", "1", Map.of(), "")), ",\"provides\":[\"x\"]"),
				"META-INF/jars/r.jar", fabric("r", "1", Map.of(), ",\"depends\":{\"bbb\":\"*\"}")), ""));
		DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME);
		assertEquals(Set.of("aaa", "bbb", "q", "r"), nestedIds(DuplicateModArbiter.currentPlan()));

		reset();
		try (var files = Files.list(mods())) { for (Path file : files.toList()) Files.delete(file); }
		install("owo.jar", fabric("owo", "0.13.1", Map.of(), ",\"provides\":[\"owo-lib\"]"));
		install("fishing.jar", fabric("fishing", "1", Map.of("META-INF/jars/owo-sentinel.jar", fabric("owo-sentinel", "0.13.1",
				Map.of("META-INF/jars/zlib.jar", fabric("zlib", "1", Map.of(), "")), ",\"provides\":[\"owo\",\"owo-lib\"]")), ""));
		install("neoreq.jar", neo("neoreq", Map.of(), List.of(), "[[dependencies.neoreq]]\nmodId=\"zlib\"\ntype=\"required\"\nversionRange=\"[1,)\"\n"));
		String log = capture(() -> DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME));
		var plan = DuplicateModArbiter.currentPlan();
		assertEquals(Set.of("owo-sentinel", "zlib"), nestedIds(plan));
		assertTrue(plan.oneModPerId().keptBack().get(only(plan, "owo-sentinel")).because().startsWith("it bundles zlib (neoreq requires zlib"),
				String.valueOf(plan.oneModPerId().keptBack()));
		assertTrue(log.contains("nested owo-sentinel 0.13.1 in fishing.jar loaded although Fabric Loader would leave it out"), log);
	}

	/**
	 * What goes with a loser is not a loser. c is nested in p 2, which loses x to a; c goes with it, but it did not
	 * lose y to d, so when p 1 (which bundles the same c) replaces p 2, c comes back and, ordered before d, takes y.
	 */
	@Test void aModThatOnlyWentWithALoserIsNotOutvotedForGood() throws Exception {
		byte[] c = fabric("c", "1", Map.of(), ",\"provides\":[\"y\"]");
		install("a.jar", fabric("a", "1", Map.of(), ",\"provides\":[\"x\"]"));
		install("host1.jar", fabric("host1", "1", Map.of("META-INF/jars/p.jar", fabric("p", "2", Map.of("META-INF/jars/c.jar", c),
				",\"provides\":[\"x\"]")), ""));
		install("host2.jar", fabric("host2", "1", Map.of("META-INF/jars/p.jar", fabric("p", "1", Map.of("META-INF/jars/c.jar", c), "")), ""));
		install("host3.jar", fabric("host3", "1", Map.of("META-INF/jars/d.jar", fabric("d", "1", Map.of(), ",\"provides\":[\"y\"]")), ""));
		DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME);
		assertEquals(Set.of("p", "c"), nestedIds(DuplicateModArbiter.currentPlan()));
	}

	/**
	 * Arbitration runs again for what went with a loser, too: lib 2 is nested only in l, which loses x to a, and
	 * another build, lib 1, is there to take its place, so lib loads.
	 */
	@Test void anotherBuildOfWhatWentWithALoserTakesItsPlace() throws Exception {
		install("a.jar", fabric("a", "1", Map.of(), ",\"provides\":[\"x\"]"));
		install("p.jar", fabric("p", "1", Map.of("META-INF/jars/l.jar", fabric("l", "1", Map.of("META-INF/jars/lib.jar",
				fabric("lib", "2", Map.of(), "")), ",\"provides\":[\"x\"]")), ""));
		install("q.jar", fabric("q", "1", Map.of("META-INF/jars/lib.jar", fabric("lib", "1", Map.of(), "")), ""));
		DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME);
		var plan = DuplicateModArbiter.currentPlan();
		assertEquals(List.of("1"), plan.nestedFiles().stream().filter(p -> plan.inventory().nodes().get(p).claim().modIds().contains("lib"))
				.map(p -> plan.inventory().nodes().get(p).claim().versionOf("lib")).toList());
	}

	/**
	 * A mod that needs a loser stays out in a later round too, when the loser is no longer weighed: d needs l, which
	 * loses x to a, and a second round runs because k 2 loses y to b and k 1 can replace it.
	 */
	@Test void aModThatNeedsAnOutvotedLoserStaysOutInLaterRounds() throws Exception {
		install("a.jar", fabric("a", "1", Map.of(), ",\"provides\":[\"x\"]"));
		install("b.jar", fabric("b", "1", Map.of(), ",\"provides\":[\"y\"]"));
		install("p.jar", fabric("p", "1", Map.of("META-INF/jars/l.jar", fabric("l", "1", Map.of(), ",\"provides\":[\"x\"]")), ""));
		install("q.jar", fabric("q", "1", Map.of("META-INF/jars/d.jar", fabric("d", "1", Map.of(), ",\"depends\":{\"l\":\"*\"}")), ""));
		install("host1.jar", fabric("host1", "1", Map.of("META-INF/jars/k.jar", fabric("k", "2", Map.of(), ",\"provides\":[\"y\"]")), ""));
		install("host2.jar", fabric("host2", "1", Map.of("META-INF/jars/k.jar", fabric("k", "1", Map.of(), "")), ""));
		String log = capture(() -> DuplicateModArbiter.arbitrate(mods(), EnvType.SERVER, GAME));
		var plan = DuplicateModArbiter.currentPlan();
		assertEquals(Set.of("k"), nestedIds(plan));
		assertEquals("it requires l *, and every installed build of that is left out", plan.oneModPerId().leftOut().get(only(plan, "d")));
		assertTrue(log.contains("one mod per id: decided in 2 arbitration round(s)"), log);
	}

	/**
	 * Without a plan the copies are weighed one at a time, the first found first: a copy that loses goes and the next
	 * is weighed in its place. zzz 1 loses x to x 2, zzz 3 then beats x 2, as Fabric orders them; and a copy in mods/
	 * always loads.
	 */
	@Test void withoutAPlanEachCopyIsWeighedInTurn() throws Exception {
		install("p1.jar", fabric("p1", "1", Map.of("META-INF/jars/zzz.jar", fabric("zzz", "1", Map.of(), ",\"provides\":[\"x\"]")), ""));
		install("p2.jar", fabric("p2", "1", Map.of("META-INF/jars/zzz.jar", fabric("zzz", "3", Map.of(), ",\"provides\":[\"x\"]")), ""));
		install("p3.jar", fabric("p3", "1", Map.of("META-INF/jars/x.jar", fabric("x", "2", Map.of(), "")), ""));
		System.setProperty("forbric.crossJarArbitration", "off");
		FabricModDiscovery discovery = new FabricModDiscovery(EnvType.SERVER, root.resolve("jij"));
		discovery.setPlatform(NestedFabricRequirements.Platform.running(GAME));
		discovery.discover(mods());
		assertEquals(List.of("3"), discovery.getContainers().stream().filter(c -> c.getMetadata().getId().equals("zzz"))
				.map(c -> c.getMetadata().getVersion().getFriendlyString()).toList());
		assertFalse(ids(discovery).contains("x"));

		try (var files = Files.list(mods())) { for (Path file : files.toList()) Files.delete(file); }
		installOwoShape();
		install("zzz-owo-sentinel.jar", fabric("owo-sentinel", "0.13.0", Map.of(), ",\"provides\":[\"owo\",\"owo-lib\"]"));
		FabricModDiscovery second = new FabricModDiscovery(EnvType.SERVER, root.resolve("jij2"));
		second.setPlatform(NestedFabricRequirements.Platform.running(GAME));
		second.discover(mods());
		assertEquals(List.of("0.13.0"), second.getContainers().stream().filter(c -> c.getMetadata().getId().equals("owo-sentinel"))
				.map(c -> c.getMetadata().getVersion().getFriendlyString()).toList());
	}
	private void installOwoShape() throws Exception {
		install("owo.jar", fabric("owo", "0.13.1", Map.of(), ",\"provides\":[\"owo-impl\",\"owo-lib\"]"));
		install("fishing.jar", fabric("fishing", "1", Map.of("META-INF/jars/owo-sentinel.jar", fabric("owo-sentinel", "0.13.1",
				Map.of(), ",\"provides\":[\"owo\",\"owo-lib\"]")), ",\"depends\":{\"owo-lib\":\"~0.13.1\"}"));
	}

	private void installViaFabricShape() throws Exception {
		byte[] current = fabric("host-mc26-2", "1", Map.of(), ",\"depends\":{\"minecraft\":[\"26.2\",\"26.3\"],\"host\":\"*\"}");
		install("host.jar", fabric("host", "1", Map.of("META-INF/jars/host-mc26-2.jar", current, "META-INF/jars/host-mc26-1.jar", mc261()),
				",\"depends\":{\"minecraft\":[\"26.1\",\"26.1.1\",\"26.1.2\",\"26.2\",\"26.3\"]}"));
	}
	/** Built for the previous Minecraft line, with a nested jar of its own. */
	private static byte[] mc261() throws Exception {
		return fabric("host-mc26-1", "1", Map.of("META-INF/jars/inner.jar", fabric("inner", "1", Map.of(), "")),
				",\"depends\":{\"minecraft\":\">=26.1 <=26.1.2\"}");
	}
	private Path mods() throws Exception { Path mods = root.resolve("mods"); Files.createDirectories(mods); return mods; }
	private Path install(String name, byte[] bytes) throws Exception { Path path = mods().resolve(name); Files.write(path, bytes); return path; }
	private static Set<String> nestedIds(NestedCandidatePlan plan) {
		return plan.nestedFiles().stream().flatMap(p -> plan.inventory().nodes().get(p).claim().modIds().stream()).collect(Collectors.toSet());
	}
	private static Path only(NestedCandidatePlan plan, String id) {
		List<Path> matches = plan.inventory().nodes().values().stream().filter(n -> n.claim() != null && n.claim().modIds().contains(id))
				.map(NestedCandidateInventory.Node::path).toList();
		assertEquals(1, matches.size(), id);
		return matches.getFirst();
	}
	private static List<String> jijLines(String log) {
		return log.lines().filter(line -> line.contains("[Forbric/JiJ] nested")).map(line -> line.substring(line.indexOf("[Forbric/JiJ]"))).toList();
	}
	private interface Body { void run() throws Exception; }
	private static String capture(Body body) throws Exception {
		PrintStream out = System.out, err = System.err;
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		PrintStream sink = new PrintStream(buffer, true, StandardCharsets.UTF_8);
		System.setOut(sink); System.setErr(sink);
		try { body.run(); } finally { System.setOut(out); System.setErr(err); }
		return buffer.toString(StandardCharsets.UTF_8);
	}
	/** A NeoForge mod whose JarJar metadata declares {@code declared}; the other children are only in its directories. */
	private static byte[] neo(String id, Map<String, byte[]> children, List<String> declared) throws Exception {
		return neo(id, children, declared, "");
	}
	/** As above; {@code toml} is appended to its neoforge.mods.toml (its dependencies). */
	private static byte[] neo(String id, Map<String, byte[]> children, List<String> declared, String toml) throws Exception {
		Map<String, byte[]> all = new LinkedHashMap<>(children);
		all.put("META-INF/neoforge.mods.toml", ("modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\""
				+ id + "\"\nversion=\"1\"\n" + toml).getBytes(StandardCharsets.UTF_8));
		List<String> entries = new ArrayList<>();
		for (String path : declared) {
			String artifact = path.substring(path.lastIndexOf('/') + 1).replace(".jar", "");
			entries.add("{\"path\":\"" + path + "\",\"identifier\":{\"group\":\"example\",\"artifact\":\"" + artifact
					+ "\"},\"version\":{\"range\":\"[1,)\",\"artifactVersion\":\"1\"}}");
		}
		all.put("META-INF/jarjar/metadata.json", ("{\"jars\":[" + String.join(",", entries) + "]}").getBytes(StandardCharsets.UTF_8));
		return zip(all);
	}
	private static Set<String> ids(FabricModDiscovery discovery) {
		return discovery.getContainers().stream().map(c -> c.getMetadata().getId()).collect(Collectors.toSet());
	}
	private static byte[] fabric(String id, String version, Map<String, byte[]> children, String extra) throws Exception {
		Map<String, byte[]> all = new LinkedHashMap<>(children);
		String jars = String.join(",", children.keySet().stream().map(name -> "{\"file\":\"" + name + "\"}").toList());
		all.put("fabric.mod.json", ("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"" + version + "\",\"jars\":[" + jars + "]" + extra + "}")
				.getBytes(StandardCharsets.UTF_8));
		return zip(all);
	}
	private static byte[] zip(Map<String, byte[]> all) throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
			for (var entry : new TreeMap<>(all).entrySet()) {
				ZipEntry part = new ZipEntry(entry.getKey()); part.setTime(0); zip.putNextEntry(part); zip.write(entry.getValue()); zip.closeEntry();
			}
		}
		return bytes.toByteArray();
	}
}
