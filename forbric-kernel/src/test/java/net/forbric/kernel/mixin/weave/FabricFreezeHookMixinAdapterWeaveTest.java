package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.transform.FabricFreezePointInjector;

/**
 * {@code FabricFreezeHookMixinAdapter} through the real weave: a Fabric mod's HEAD and TAIL injectors on
 * {@code BuiltInRegistries.freeze()} run where a Fabric game with fabric-registry-sync freezes — after the Fabric main
 * — while the freeze itself stays in the native bootstrap (issue #52).
 *
 * <p>The guest is Create Fly's shape: at HEAD it creates its registries unless fabric-api is loaded (then its main
 * does), and at TAIL it reads them, whose holder registers into the root the first time it is touched. Beside it,
 * LiquidBounce's shape: an injector in {@code bootStrap()} at its call of {@code freeze()}, whose creative tabs need the
 * client instance, which exists only after the bootstrap (the trace says whether it was there). The probe boots
 * the way the kernel does — bootstrap, open the root, the Fabric main, the HEAD hook, freeze, the TAIL hook — and every
 * step writes to one trace. {@link FabricFreezePointInjector} is on the run's pre-Mixin chain as KernelBoot registers
 * it. Three runs:
 * <ul>
 *   <li>adapted — fabric-registry-sync's config registered, fabric-api loaded: both injectors move to the hooks, the
 *       TAIL reads registries the main created, and the boot finishes;</li>
 *   <li>off — {@code -Dforbric.fabricFreezePoint=off}: no hooks, nothing moved, and the TAIL is the first touch of the
 *       holder, inside the bootstrap, against the frozen root. That is #52, and the probe dies with its message;</li>
 *   <li>without registry sync — the same switch on, but no fabric-registry-sync config: Fabric itself would freeze in
 *       the bootstrap, so nothing moves and, fabric-api absent, the HEAD branch creates the registries there.</li>
 * </ul>
 */
class FabricFreezeHookMixinAdapterWeaveTest {
	private static final Path SOURCES = Path.of("src/test/resources/weave/fabricfreezehook");
	private static final String CONFIG = "fabricfreezehook.mixins.json";
	private static final String MOD = "fabricfreezehook";
	private static final String SYNC_CONFIG = "fabric-registry-sync-v0.mixins.json";
	private static final String SYNC_MOD = "fabric-registry-sync-v0";
	private static final String BUILT_IN_REGISTRIES = "net/minecraft/core/registries/BuiltInRegistries";
	private static final String FABRIC_API = "fixture.fabricfreezehook.fabricApi";
	private static final String MOVED = "bootstrap:start,createContents,freeze,bootstrap:end,registrySync,client:new,"
			+ "kernel:open,main,create:register,minecraft:root+create:arm_interaction_point_type,hook:head,create:head,"
			+ "tabs:client,kernel:freeze,hook:tail,create:tail,create:arm_interaction_point_type+create:depot";
	private static final String KEPT = "bootstrap:start,createContents,tabs:no-client,create:head,create:register,"
			+ "minecraft:root+create:arm_interaction_point_type,freeze,create:tail,"
			+ "create:arm_interaction_point_type+create:depot,bootstrap:end,client:new,kernel:open,main,hook:head,"
			+ "kernel:freeze,hook:tail";
	private static final String FROZEN = "java.lang.IllegalStateException: Registry is already frozen (trying to add key "
			+ "ResourceKey[minecraft:root / create:arm_interaction_point_type])";
	private static final String ADAPTED = "[Forbric/RegistrySync] fixture/fabricfreezehook/mixin/BuiltInRegistriesMixin: "
			+ "injector(s) on BuiltInRegistries.freeze() or at bootStrap()'s call of it wait for Fabric's registry freeze "
			+ "point after the Fabric entrypoints, where fabric-registry-sync puts the freeze: onInitialize (HEAD), "
			+ "afterFreeze (TAIL)";
	private static final String ADAPTED_TABS = "[Forbric/RegistrySync] fixture/fabricfreezehook/mixin/CreativeTabsMixin: "
			+ "injector(s) on BuiltInRegistries.freeze() or at bootStrap()'s call of it wait for Fabric's registry freeze "
			+ "point after the Fabric entrypoints, where fabric-registry-sync puts the freeze: initializeTabs (HEAD)";
	private static final String INSTALLED = "[WeaveHarness] pre-Mixin chain installed: COREMOD FabricFreezePointInjector "
			+ "(behind its enabled())";
	private static final String NOT_REGISTERED = "[WeaveHarness] pre-Mixin chain: FabricFreezePointInjector.enabled() is "
			+ "false, so KernelBoot would not register it";

	@TempDir static Path work;
	private static Path fixture;
	private static WeaveHarness.Result adapted;
	private static WeaveHarness.Result off;
	private static WeaveHarness.Result withoutSync;

	@BeforeAll static void weaveAll() throws Exception {
		fixture = WeaveHarness.fixture(work, MOD, sources(),
				Map.of(CONFIG, SOURCES.resolve(CONFIG), SYNC_CONFIG, SOURCES.resolve(SYNC_CONFIG)));
		adapted = run("adapted", true, "on", "present");
		off = run("off", true, "off", "present");
		withoutSync = run("without-sync", false, "on", "absent");
	}

	@Test void withRegistrySyncTheFreezeInjectorsRunAfterTheFabricMain() throws Exception {
		assertTrue(adaptedHolds(adapted), adapted.describe() + "\nfindings: " + adapted.findings());
		assertTrue(adapted.printed(INSTALLED), adapted.describe());
		assertTrue(adapted.printed(WeaveHarnessMain.REGISTERED + SYNC_CONFIG + ", " + CONFIG), adapted.describe());
		assertEquals(List.of(), handlerCalls(adapted, "freeze"), "freeze() still calls a moved handler — "
				+ adapted.describe());
		assertEquals(List.of(), handlerCalls(adapted, "bootStrap"), "bootStrap() still calls a moved handler — "
				+ adapted.describe());
		assertEquals(List.of("onInitialize", "initializeTabs"), handlerCalls(adapted, FabricFreezePointInjector.HEAD_HOOK),
				adapted.describe());
		assertEquals(List.of("afterFreeze"), handlerCalls(adapted, FabricFreezePointInjector.TAIL_HOOK), adapted.describe());
		assertTrue(WeaveHarness.hasMergedMethod(adapted.defined(BUILT_IN_REGISTRIES)), adapted.describe());
		WeaveHarness.assertWovenAndVerified(adapted, BUILT_IN_REGISTRIES, fixture);
	}

	@Test void withTheSwitchOffTheTailCreatesTheRegistriesInTheFrozenRootAndBootstrapDies() throws Exception {
		assertTrue(offHolds(off), off.describe() + "\nfindings: " + off.findings());
		assertTrue(off.printed(NOT_REGISTERED), off.describe());
		assertFalse(off.printed("[Forbric/RegistrySync]"), off.describe());
		assertTrue(off.printed("net.minecraft.server.Bootstrap.bootStrap(Bootstrap.java"),
				"#52 dies in the bootstrap — " + off.describe());
		assertEquals(List.of("onInitialize", "afterFreeze"), handlerCalls(off, "freeze"), off.describe());
		assertEquals(List.of("initializeTabs"), handlerCalls(off, "bootStrap"), off.describe());
		WeaveHarness.assertWovenAndVerified(off, BUILT_IN_REGISTRIES, fixture);
	}

	@Test void withoutRegistrySyncTheInjectorsStayInTheBootstrapAsOnFabric() throws Exception {
		assertTrue(withoutSyncHolds(withoutSync), withoutSync.describe() + "\nfindings: " + withoutSync.findings());
		assertTrue(withoutSync.printed(INSTALLED), "the hooks exist; only fabric-registry-sync is missing — "
				+ withoutSync.describe());
		assertTrue(withoutSync.printed(WeaveHarnessMain.REGISTERED + CONFIG), withoutSync.describe());
		assertFalse(withoutSync.printed(WeaveHarnessMain.REGISTERED + SYNC_CONFIG), withoutSync.describe());
		assertFalse(withoutSync.printed("[Forbric/RegistrySync]"), withoutSync.describe());
		assertEquals(List.of("onInitialize", "afterFreeze"), handlerCalls(withoutSync, "freeze"), withoutSync.describe());
		assertEquals(List.of("initializeTabs"), handlerCalls(withoutSync, "bootStrap"), withoutSync.describe());
		assertEquals(List.of(), handlerCalls(withoutSync, FabricFreezePointInjector.HEAD_HOOK), withoutSync.describe());
		assertEquals(List.of(), handlerCalls(withoutSync, FabricFreezePointInjector.TAIL_HOOK), withoutSync.describe());
		WeaveHarness.assertWovenAndVerified(withoutSync, BUILT_IN_REGISTRIES, fixture);
	}

	/** Each run must be told apart from both others by the very predicates the test uses on them. */
	@Test void theControlFlipsEveryAssertion() {
		assertTrue(adaptedHolds(adapted) && !adaptedHolds(off) && !adaptedHolds(withoutSync),
				"adapted predicate does not separate the runs");
		assertTrue(offHolds(off) && !offHolds(adapted) && !offHolds(withoutSync), "control predicate does not separate the runs");
		assertTrue(withoutSyncHolds(withoutSync) && !withoutSyncHolds(adapted) && !withoutSyncHolds(off),
				"without-registry-sync predicate does not separate the runs");
	}

	/**
	 * Every mixin binds in every run, so none may report a loss: what differs is where the handlers run, and whether
	 * the boot survives it.
	 */
	private static boolean adaptedHolds(WeaveHarness.Result run) {
		return run.printed(WeaveHarnessMain.DONE + " " + MOVED) && run.printed(ADAPTED) && run.printed(ADAPTED_TABS)
				&& losses(run).isEmpty();
	}

	private static boolean offHolds(WeaveHarness.Result run) {
		return run.printed(WeaveHarnessMain.THREW + "java.lang.ExceptionInInitializerError") && run.printed(FROZEN)
				&& !run.printed(ADAPTED) && !run.printed(ADAPTED_TABS) && losses(run).isEmpty();
	}

	private static boolean withoutSyncHolds(WeaveHarness.Result run) {
		return run.printed(WeaveHarnessMain.DONE + " " + KEPT) && !run.printed(ADAPTED) && !run.printed(ADAPTED_TABS)
				&& losses(run).isEmpty();
	}

	private static List<WeaveHarness.Finding> losses(WeaveHarness.Result run) {
		return run.findings().stream().filter(f -> (f.modId().equals(MOD) || f.modId().equals(SYNC_MOD))
				&& f.confirmedRequired()).toList();
	}

	/** The guest handlers {@code method} of the woven BuiltInRegistries calls, in order, by their source names. */
	private static List<String> handlerCalls(WeaveHarness.Result run, String method) throws Exception {
		ClassNode woven = new ClassNode();
		new ClassReader(run.defined(BUILT_IN_REGISTRIES)).accept(woven, 0);
		MethodNode body = woven.methods.stream().filter(m -> m.name.equals(method) && m.desc.equals("()V")).findFirst()
				.orElseThrow(() -> new AssertionError(method + "()V is not in the woven class — " + run.describe()));
		return Stream.of(body.instructions.toArray())
				.filter(insn -> insn instanceof MethodInsnNode call && call.owner.equals(BUILT_IN_REGISTRIES))
				.map(insn -> ((MethodInsnNode) insn).name)
				.filter(name -> name.endsWith("$onInitialize") || name.endsWith("$afterFreeze")
						|| name.endsWith("$initializeTabs"))
				.map(name -> name.substring(name.lastIndexOf('$') + 1)).toList();
	}

	private static WeaveHarness.Result run(String label, boolean registrySync, String freezePoint, String fabricApi)
			throws Exception {
		List<WeaveHarness.Config> configs = registrySync
				? List.of(new WeaveHarness.Config(SYNC_CONFIG, SYNC_MOD, Ecosystem.FABRIC),
						new WeaveHarness.Config(CONFIG, MOD, Ecosystem.FABRIC))
				: List.of(new WeaveHarness.Config(CONFIG, MOD, Ecosystem.FABRIC));
		return WeaveHarness.run(work, label, fixture, configs, List.of(FabricFreezePointInjector.class), EnvType.SERVER,
				"fixture.fabricfreezehook.Probe", "run",
				Map.of(FabricFreezePointInjector.PROPERTY, freezePoint, FABRIC_API, fabricApi));
	}

	private static List<Path> sources() throws Exception {
		try (Stream<Path> walk = Files.walk(SOURCES)) {
			return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
		}
	}
}
