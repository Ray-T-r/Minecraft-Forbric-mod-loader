package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;

/** The harness must be able to tell a woven class from an unwoven one, or nothing it reports means anything. */
class WeaveHarnessSelfTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/selftest");
	private static final String CONFIG = "selftest.mixins.json";
	private static final List<String> CONFIGS = List.of(CONFIG,
			"selftest-fabric.mixins.json", "selftest-neoforge.mixins.json", "selftest-client.mixins.json");

	@TempDir static Path work;
	private static Path fixture;

	@BeforeAll static void compile() throws Exception {
		List<Path> sources = new java.util.ArrayList<>();
		for (String name : List.of("Greeter", "Ledger", "Panel")) {
			sources.add(SOURCES.resolve("fixture/selftest/" + name + ".java"));
		}
		for (String name : List.of("GreeterMixin", "FabricLedgerMixin", "NeoForgeLedgerMixin", "PanelClientMixin")) {
			sources.add(SOURCES.resolve("fixture/selftest/mixin/" + name + ".java"));
		}
		Map<String, Path> configs = new java.util.LinkedHashMap<>();
		for (String config : CONFIGS) configs.put(config, SOURCES.resolve(config));
		fixture = WeaveHarness.fixture(work, "selftest", sources, configs);
	}

	@Test void realMixinWeavesTheFixtureAndTheWovenCodeRuns() throws Exception {
		WeaveHarness.Result woven = run("woven", Map.of());
		WeaveHarness.Result plain = run("control-no-bootstrap", Map.of("forbric.weaveHarness.bootstrap", "off"));

		// What the woven method DID, not what the mixin declared.
		assertTrue(woven.printed(WeaveHarnessMain.DONE + " woven"), woven.describe());
		assertTrue(woven.printed("Mixin up on the sovereign kernel — 1 config(s) registered"), woven.describe());
		assertTrue(WeaveHarness.hasMergedMethod(woven.defined("fixture/selftest/Greeter")),
				"ForbricClassLoader defined Greeter without Mixin's merged handler — " + woven.describe());
		WeaveHarness.assertWovenAndVerified(woven, "fixture/selftest/Greeter", fixture);

		// The absent anchor is the runtime audit's job: one confirmed, required loss, named for its mod.
		List<WeaveHarness.Finding> losses = woven.findings().stream()
				.filter(f -> f.id().startsWith("mixin-injector:") && f.confirmedRequired()).toList();
		assertEquals(1, losses.size(), "findings: " + woven.findings() + "\n" + woven.describe());
		assertEquals("selftest", losses.get(0).modId());
		assertTrue(losses.get(0).id().contains("missing"), losses.get(0).toString());
		assertFalse(woven.printed("absent site executed"), woven.describe());

		// Control: the same fixture and probe without the bootstrap. Every assertion above must be able to fail.
		assertTrue(plain.printed(WeaveHarnessMain.DONE + " plain"), plain.describe());
		assertFalse(plain.printed("Mixin up on the sovereign kernel"), plain.describe());
		assertFalse(WeaveHarness.hasMergedMethod(plain.defined("fixture/selftest/Greeter")), plain.describe());
		assertTrue(plain.findings().stream().noneMatch(f -> f.id().startsWith("mixin-injector:")), plain.findings().toString());
	}

	/**
	 * Two mods' configs on one target, each published with its own owner, are registered as KernelBoot registers them:
	 * the Fabric one first even when listed second, the Forge family after it. With equal priorities registration order
	 * is the order the mixins apply in, which the woven return value records; two Forge-family configs keep the order
	 * they were given, so the same two mixins apply the other way round.
	 */
	@Test void configsFromTwoEcosystemsApplyInKernelBootsRegistrationOrder() throws Exception {
		WeaveHarness.Result mixed = ledger("kernelboot-order", Ecosystem.NEOFORGE, Ecosystem.FABRIC);
		WeaveHarness.Result forgeFamily = ledger("forge-family-order", Ecosystem.NEOFORGE, Ecosystem.NEOFORGE);

		assertTrue(mixed.printed(WeaveHarnessMain.REGISTERED + "selftest-fabric.mixins.json, selftest-neoforge.mixins.json"),
				mixed.describe());
		assertTrue(mixed.printed("Mixin up on the sovereign kernel — 2 config(s) registered"), mixed.describe());
		assertTrue(mixed.printed(WeaveHarnessMain.DONE + " base|fabric|neoforge"), mixed.describe());
		assertEquals(List.of("selffabric", "selfneo"), decoratedMods(mixed), "each config's handler names its own mod");
		WeaveHarness.assertWovenAndVerified(mixed, "fixture/selftest/Ledger", fixture);

		assertTrue(forgeFamily.printed(WeaveHarnessMain.REGISTERED + "selftest-neoforge.mixins.json, selftest-fabric.mixins.json"),
				forgeFamily.describe());
		assertTrue(forgeFamily.printed(WeaveHarnessMain.DONE + " base|neoforge|fabric"), forgeFamily.describe());
		assertEquals(List.of("selfneo", "selffabric"), decoratedMods(forgeFamily));
	}

	/** A mixin listed only under "client" applies on a CLIENT run and is neither applied nor judged on a SERVER run. */
	@Test void aClientOnlyMixinAppliesOnlyOnAClientRun() throws Exception {
		WeaveHarness.Result client = panel("client", EnvType.CLIENT);
		WeaveHarness.Result server = panel("server", EnvType.SERVER);

		assertTrue(client.printed("Mixin up on the sovereign kernel — 1 config(s) registered, side CLIENT"), client.describe());
		assertTrue(client.printed(WeaveHarnessMain.DONE + " client"), client.describe());
		assertTrue(WeaveHarness.hasMergedMethod(client.defined("fixture/selftest/Panel")), client.describe());
		WeaveHarness.assertWovenAndVerified(client, "fixture/selftest/Panel", fixture);

		assertTrue(server.printed("Mixin up on the sovereign kernel — 1 config(s) registered, side SERVER"), server.describe());
		assertTrue(server.printed(WeaveHarnessMain.DONE + " panel"), server.describe());
		assertFalse(WeaveHarness.hasMergedMethod(server.defined("fixture/selftest/Panel")), server.describe());
		assertTrue(server.findings().stream().noneMatch(f -> f.id().contains("PanelClientMixin")), server.findings().toString());
	}

	/** A fixture's own javac options reach javac: {@code -g} adds the LocalVariableTable javac's default leaves out. */
	@Test void aFixtureCompilesWithItsOwnJavacOptions() throws Exception {
		List<Path> greeter = List.of(SOURCES.resolve("fixture/selftest/Greeter.java"));
		Path debug = WeaveHarness.fixture(work, "javac-g", greeter, Map.of(), List.of("-g"));
		Path plain = WeaveHarness.fixture(work, "javac-default", greeter, Map.of());
		assertEquals(List.of("this"), greetLocals(debug));
		assertEquals(List.of(), greetLocals(plain));
	}

	private static List<String> greetLocals(Path jar) throws Exception {
		try (var zip = new java.util.zip.ZipFile(jar.toFile())) {
			ClassNode node = new ClassNode();
			new ClassReader(zip.getInputStream(zip.getEntry("fixture/selftest/Greeter.class")).readAllBytes()).accept(node, 0);
			var greet = node.methods.stream().filter(m -> m.name.equals("greet")).findFirst().orElseThrow();
			return greet.localVariables == null ? List.of() : greet.localVariables.stream().map(v -> v.name).toList();
		}
	}

	private static WeaveHarness.Result run(String label, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, "selftest", Ecosystem.FABRIC, EnvType.SERVER,
				"fixture.selftest.Greeter", "greet", properties);
	}

	/** The NeoForge-owned config listed first, the other second; {@code second} decides whether KernelBoot reorders. */
	private static WeaveHarness.Result ledger(String label, Ecosystem first, Ecosystem second) throws Exception {
		return WeaveHarness.run(work, label, fixture, List.of(
				new WeaveHarness.Config("selftest-neoforge.mixins.json", "selfneo", first),
				new WeaveHarness.Config("selftest-fabric.mixins.json", "selffabric", second)),
				List.of(), EnvType.SERVER, "fixture.selftest.Ledger", "entries", Map.of());
	}

	private static WeaveHarness.Result panel(String label, EnvType side) throws Exception {
		return WeaveHarness.run(work, label, fixture, "selftest-client.mixins.json", "selfclient", Ecosystem.FABRIC, side,
				"fixture.selftest.Panel", "title", Map.of());
	}

	/** The mod id KernelMixinBootstrap decorated into each merged handler's name, in the order the handlers were merged. */
	private static List<String> decoratedMods(WeaveHarness.Result run) throws Exception {
		return node(run, "fixture/selftest/Ledger").methods.stream().map(m -> m.name)
				.filter(name -> name.endsWith("$fabric") || name.endsWith("$neoforge"))
				.map(name -> name.split("\\$")[2]).toList();
	}

	private static ClassNode node(WeaveHarness.Result run, String internalName) throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(run.defined(internalName)).accept(node, 0);
		return node;
	}
}
