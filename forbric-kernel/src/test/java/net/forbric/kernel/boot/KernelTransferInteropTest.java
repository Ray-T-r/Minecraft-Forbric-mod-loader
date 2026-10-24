package net.forbric.kernel.boot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.classloading.ForbricClassLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import static org.junit.jupiter.api.Assertions.*;

class KernelTransferInteropTest {
	@TempDir Path directory;
	@AfterEach void reset() throws Exception {
		CompatibilityFindings.reset();
		System.clearProperty("forbric.transferBridge");
		System.clearProperty("forbric.hopperFabricStorage");
		try (var loader = new ForbricClassLoader(new java.net.URL[0], getClass().getClassLoader())) {
			KernelTransferInterop.configure(loader);
		}
	}
	@Test void noTransferModMeansNoNativeTransactionHooksOrOptionalRuntimeRequirements() throws Exception {
		try (var loader = loader(false, false)) {
			assertFalse(KernelTransferInterop.configure(loader));
			assertFalse(KernelRuntimeClasses.compiled().contains(KernelTransferInterop.BRIDGE));
			assertTrue(CompatibilityFindings.confirmedRequired().isEmpty());
		}
	}
	@Test void missingKernelComponentCannotBreakOtherwiseNativeTransactions() throws Exception {
		try (var loader = loader(true, false)) {
			assertFalse(KernelTransferInterop.configure(loader), "do not install calls into absent PairedTransactions");
			assertEquals("transfer-component", CompatibilityFindings.confirmedRequired().getFirst().id());
		}
	}
	@Test void presentComponentMakesItsBootGameSeamMandatoryWithoutInitializingGameClasses() throws Exception {
		try (var loader = loader(true, true)) {
			assertTrue(KernelTransferInterop.configure(loader));
			assertTrue(KernelRuntimeClasses.compiled().containsAll(List.of(KernelTransferInterop.BRIDGE,
					KernelTransferInterop.ISSUES, KernelTransferInterop.TRANSACTIONS)));
			assertTrue(CompatibilityFindings.confirmedRequired().isEmpty());
		}
	}
	@Test void explicitOffDoesNotRewriteNativeTransactionsOrInventARequiredLoss() throws Exception {
		System.setProperty("forbric.transferBridge", "off");
		try (var loader = loader(true, true)) {
			assertFalse(KernelTransferInterop.configure(loader));
			assertTrue(CompatibilityFindings.confirmedRequired().isEmpty());
		}
	}
	/**
	 * The build may not decide by itself that the transfer component is optional. It used to: an absent dev-pack
	 * Fabric API jar excluded the whole package from a runtime jar that was still built and nested, and every
	 * fabric-api player then met the required loss pinned above. The game side is compiled for this suite (test
	 * depends on compileRuntimeJava), so whenever it exists, the three classes the boot seam checks must exist.
	 */
	@Test void aBuiltGameSideAlwaysCarriesTheTransferComponent() {
		Path classes = Path.of("build/classes/java/runtime");
		boolean gameSide = Files.isRegularFile(classes.resolve("net/forbric/kernel/runtime/KernelGameLookupHelper.class"));
		TestFixtures.require(Fixture.GAME_SIDE, gameSide, "no staged game jars, so no game side was built");
		for (String name : List.of(KernelTransferInterop.BRIDGE, KernelTransferInterop.ISSUES, KernelTransferInterop.TRANSACTIONS,
				KernelTransferInterop.ENERGY, KernelTransferInterop.HOPPER)) {
			assertTrue(Files.isRegularFile(classes.resolve(name.replace('.', '/') + ".class")), name + " was left out of the game side");
		}
	}

	/**
	 * Energy rides on the transfer component. Team Reborn Energy is an ordinary mod: without it nothing about the boot
	 * changes and its half of the bridge is not even required to exist; with it, that half is required. Presence is
	 * read as a resource, so the fixture's Reborn class (whose initialiser throws) is never loaded either way.
	 */
	@Test void withoutRebornTheEnergyHalfIsNeitherRequiredNorLoaded() throws Exception {
		try (var loader = loader(true, true, false, true)) {
			assertTrue(KernelTransferInterop.configure(loader));
			assertFalse(KernelTransferInterop.energyActive());
			assertFalse(KernelRuntimeClasses.compiled().contains(KernelTransferInterop.ENERGY));
			assertTrue(KernelRuntimeClasses.compiled().contains(KernelTransferInterop.BRIDGE));
			assertTrue(CompatibilityFindings.all().isEmpty(), CompatibilityFindings.all()::toString);
		}
	}
	@Test void withRebornTheEnergyHalfIsRequiredAndRebornIsStillNotLoadedAtBoot() throws Exception {
		try (var loader = loader(true, true, true, true)) {
			assertTrue(KernelTransferInterop.configure(loader));
			assertTrue(KernelTransferInterop.energyActive());
			assertTrue(KernelRuntimeClasses.compiled().contains(KernelTransferInterop.ENERGY));
			assertFalse(loader.isClassLoadedByName("team.reborn.energy.api.EnergyStorage"));
			assertTrue(CompatibilityFindings.all().isEmpty(), CompatibilityFindings.all()::toString);
		}
	}
	/** A kernel without the Reborn half is recorded, but it is not a necessary loss: the pack runs as it did before. */
	@Test void rebornWithoutTheKernelsEnergyHalfIsRecordedButNotNecessary() throws Exception {
		try (var loader = loader(true, true, true, false)) {
			assertTrue(KernelTransferInterop.configure(loader), "items, fluids and Forge/NeoForge energy stay connected");
			assertFalse(KernelTransferInterop.energyActive());
			assertFalse(KernelRuntimeClasses.compiled().contains(KernelTransferInterop.ENERGY));
			assertTrue(CompatibilityFindings.confirmedRequired().isEmpty());
			assertTrue(CompatibilityFindings.all().stream().anyMatch(finding -> finding.id().equals("transfer-energy-component")
					&& !finding.required() && finding.confidence() == CompatibilityFinding.Confidence.CONFIRMED), CompatibilityFindings.all()::toString);
		}
	}
	/** The one switch that turns the bridge off turns energy off with it, and Reborn without the transfer APIs does nothing. */
	@Test void energyNeverOutlivesTheTransferBridge() throws Exception {
		System.setProperty("forbric.transferBridge", "off");
		try (var loader = loader(true, true, true, true)) {
			assertFalse(KernelTransferInterop.configure(loader));
			assertFalse(KernelTransferInterop.energyActive());
			assertFalse(KernelRuntimeClasses.compiled().contains(KernelTransferInterop.ENERGY));
		}
		System.clearProperty("forbric.transferBridge");
		try (var loader = loader(false, true, true, true)) {
			assertFalse(KernelTransferInterop.configure(loader));
			assertFalse(KernelTransferInterop.energyActive());
			assertTrue(CompatibilityFindings.all().isEmpty(), CompatibilityFindings.all()::toString);
		}
	}
	/**
	 * The game side names Team Reborn Energy in exactly two classes, which only the boot seam loads and only when Reborn
	 * is installed. Any other class naming it (BlockTransferBridge above all, which every NeoForge and Forge energy query
	 * reaches) would make a pack without Reborn fail to link that class.
	 */
	@Test void onlyTheRebornHalfOfTheGameSideNamesTeamRebornEnergy() throws Exception {
		Path classes = Path.of("build/classes/java/runtime");
		boolean gameSide = Files.isRegularFile(classes.resolve(KernelTransferInterop.BRIDGE.replace('.', '/') + ".class"));
		TestFixtures.require(Fixture.GAME_SIDE, gameSide, "no staged game jars, so no game side was built");
		List<String> naming = new java.util.ArrayList<>();
		try (var walk = Files.walk(classes)) {
			for (Path file : walk.filter(path -> path.toString().endsWith(".class")).toList()) {
				String text = new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.ISO_8859_1);
				if (text.contains("team/reborn/")) naming.add(classes.relativize(file).toString().replace('\\', '/'));
			}
		}
		assertFalse(naming.isEmpty(), "the energy bridge was compiled without its Reborn half");
		for (String name : naming) assertTrue(name.startsWith("net/forbric/kernel/runtime/transfer/RebornEnergyAdapters")
				|| name.startsWith("net/forbric/kernel/runtime/transfer/RebornEnergyBridge"), name + " names Team Reborn Energy");
	}
	/** The bridge is told what boot decided about the Forge fallback, and looks for it only when boot registered it. */
	@Test void installHandsTheBridgeBootsForgeFallbackDecision() throws Exception {
		try {
			for (boolean registered : new boolean[] { false, true }) {
				try (var loader = loader(true, true)) {
					assertTrue(KernelTransferInterop.configure(loader));
					KernelTransferInterop.forgeFallback(registered);
					ClassLoader game = new GameSide();
					KernelTransferInterop.install(game);
					Class<?> bridge = Class.forName(KernelTransferInterop.BRIDGE, false, game);
					assertEquals(1, bridge.getField("calls").getInt(null));
					assertEquals(registered, bridge.getField("forgeFallback").getBoolean(null));
				}
			}
		} finally {
			KernelTransferInterop.forgeFallback(false);
		}
	}
	/** A game side with only what install calls: TransferIssues.setReporter and a bridge that records its argument. */
	private static final class GameSide extends ClassLoader {
		GameSide() { super(KernelTransferInteropTest.class.getClassLoader()); }
		@Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			if (!name.equals(KernelTransferInterop.BRIDGE) && !name.equals(KernelTransferInterop.ISSUES)) return super.loadClass(name, resolve);
			synchronized (getClassLoadingLock(name)) {
				Class<?> done = findLoadedClass(name);
				if (done != null) return done;
				byte[] bytes = name.equals(KernelTransferInterop.BRIDGE) ? recordingBridge() : silentIssues();
				return defineClass(name, bytes, 0, bytes.length);
			}
		}
		private static byte[] recordingBridge() {
			String self = KernelTransferInterop.BRIDGE.replace('.', '/');
			ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, self, null, "java/lang/Object", null);
			writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "calls", "I", null, null).visitEnd();
			writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "forgeFallback", "Z", null, null).visitEnd();
			var install = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "install", "(Z)V", null, null);
			install.visitCode();
			install.visitFieldInsn(Opcodes.GETSTATIC, self, "calls", "I");
			install.visitInsn(Opcodes.ICONST_1);
			install.visitInsn(Opcodes.IADD);
			install.visitFieldInsn(Opcodes.PUTSTATIC, self, "calls", "I");
			install.visitVarInsn(Opcodes.ILOAD, 0);
			install.visitFieldInsn(Opcodes.PUTSTATIC, self, "forgeFallback", "Z");
			install.visitInsn(Opcodes.RETURN);
			install.visitMaxs(0, 0);
			install.visitEnd();
			writer.visitEnd();
			return writer.toByteArray();
		}
		private static byte[] silentIssues() {
			ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, KernelTransferInterop.ISSUES.replace('.', '/'), null,
					"java/lang/Object", null);
			var reporter = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "setReporter", "(Ljava/util/function/Consumer;)V", null, null);
			reporter.visitCode();
			reporter.visitInsn(Opcodes.RETURN);
			reporter.visitMaxs(0, 0);
			reporter.visitEnd();
			writer.visitEnd();
			return writer.toByteArray();
		}
	}
	/** TransferIssues.Issue's shape, which recordIssue reads by its accessors across the game loader boundary. */
	record Issue(String code, String providerClass, String detail, Class<?> source) {
		Issue(String code, String providerClass, String detail) { this(code, providerClass, detail, null); }
	}
	@Test void aFindingWithNoProviderIsTheKernelsOwnAndLooksUpNoClass() {
		// A finding with no provider object (the Forge fallback hook missing) has no class to attribute. It used to be
		// looked up as a class named "unknown", which never exists, so every such finding was also a failure.
		KernelTransferInterop.recordIssue(getClass().getClassLoader(), new Issue("FORGE_QUERY_HOOK_MISSING", null, "detail"));
		CompatibilityFinding finding = CompatibilityFindings.all().stream()
				.filter(f -> f.id().equals("transfer:FORGE_QUERY_HOOK_MISSING:unknown")).findFirst().orElseThrow();
		assertEquals("forbric", finding.modId());
		assertEquals(List.of("FORGE_QUERY_HOOK_MISSING", "unknown"), finding.evidence());
	}
	/**
	 * gate-m39's proxy item handler, as the boot side saw it before the runtime carried the handler across: the name
	 * of a jdk.proxy class, which resolves but was defined from no jar. Looking up its code source threw an NPE on
	 * every run, reported as a kernel failure and attributed to forbric. A class that came from no jar names no mod;
	 * that is the answer, and no failure is reported (CaughtErrorsExtension fails this test if one is).
	 */
	@Test void aProxyReportedByNameIsNoModsAndNoFailure() {
		Object proxy = java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { Runnable.class }, (p, m, a) -> null);
		String name = proxy.getClass().getName();
		KernelTransferInterop.recordIssue(getClass().getClassLoader(), new Issue("FORGE_HANDLER_NOT_ROLLBACK_SAFE", name, "detail"));
		assertEquals("forbric", attributed("transfer:FORGE_HANDLER_NOT_ROLLBACK_SAFE:" + name));
		assertEquals("forbric", KernelTransferInterop.owner(getClass().getClassLoader(), "java.lang.String", null), "a JDK class names no mod either");
		assertEquals("forbric", KernelTransferInterop.owner(getClass().getClassLoader(), name, String.class));
	}
	/** The class the runtime says answers for a provider -- a proxy's handler, a lambda -- is attributed to its jar's mod. */
	@Test void theProvidersSourceIsAttributedToTheModWhoseJarHoldsIt() throws Exception {
		Path jar = directory.resolve("handler mod.jar");
		try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
			add(out, "p/Handler");
			out.putNextEntry(new JarEntry("p/Anchor.class"));
			out.write(lambdaFactory("p/Anchor"));
			out.closeEntry();
		}
		net.forbric.api.ModCatalog.publish(List.of(new net.forbric.api.ModCatalog.Entry(net.forbric.api.Ecosystem.FABRIC,
				"handlermod", "Handler Mod", "1", "", List.of(), "handler mod.jar", "", "")));
		try (var loader = new java.net.URLClassLoader(new java.net.URL[] { jar.toUri().toURL() }, null)) {
			Class<?> handler = loader.loadClass("p.Handler");
			KernelTransferInterop.recordIssue(getClass().getClassLoader(),
					new Issue("FORGE_HANDLER_NOT_ROLLBACK_SAFE", "jdk.proxy9.$Proxy1", "detail", handler));
			assertEquals("handlermod", attributed("transfer:FORGE_HANDLER_NOT_ROLLBACK_SAFE:jdk.proxy9.$Proxy1"),
					"the source is used as it is: the name is never looked up");
			Class<?> lambda = loader.loadClass("p.Anchor").getMethod("make").invoke(null).getClass();
			assertTrue(lambda.isHidden(), "the shape this case is about");
			assertEquals("handlermod", KernelTransferInterop.owner(getClass().getClassLoader(), lambda.getName(), lambda),
					"a lambda (hidden class) is its definer's");
		} finally {
			net.forbric.api.ModCatalog.publish(List.of());
		}
	}
	/** Only a name that resolves to no class at all is a failure to attribute, and it still says so. */
	@Test @net.forbric.kernel.ExpectCaught("could not tell which mod ships no.such.Provider")
	void aNameThatResolvesToNothingIsStillReported() {
		assertEquals("forbric", KernelTransferInterop.owner(getClass().getClassLoader(), "no.such.Provider", null));
	}
	/** {@code public class <name> { public static Runnable make() { return () -> {}; } }}: a jar class that makes a real lambda. */
	private static byte[] lambdaFactory(String internalName) {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, internalName, null, "java/lang/Object", null);
		var make = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "make", "()Ljava/lang/Runnable;", null, null);
		make.visitCode();
		make.visitInvokeDynamicInsn("run", "()Ljava/lang/Runnable;",
				new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
						"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
								+ "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false),
				org.objectweb.asm.Type.getType("()V"),
				new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, internalName, "lambda$make$0", "()V", false),
				org.objectweb.asm.Type.getType("()V"));
		make.visitInsn(Opcodes.ARETURN);
		make.visitMaxs(0, 0);
		make.visitEnd();
		var body = writer.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, "lambda$make$0", "()V", null, null);
		body.visitCode();
		body.visitInsn(Opcodes.RETURN);
		body.visitMaxs(0, 0);
		body.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}
	private static String attributed(String id) {
		return CompatibilityFindings.all().stream().filter(f -> f.id().equals(id)).findFirst().orElseThrow().modId();
	}
	/**
	 * Hoppers ask Fabric's lookup whenever fabric-transfer-api and NeoForge's hopper are both present — with or without
	 * the bridge, as Fabric's own mixin would — and the component is required only then.
	 */
	@Test void theHopperComponentFollowsFabricAndNeoForgesHopperNotTheBridge() throws Exception {
		try (var loader = hopperLoader(true, true)) {
			KernelTransferInterop.configure(loader);
			assertTrue(KernelTransferInterop.hopperActive());
			assertTrue(KernelRuntimeClasses.compiled().contains(KernelTransferInterop.HOPPER));
			System.setProperty("forbric.transferBridge", "off");
			KernelTransferInterop.configure(loader);
			assertTrue(KernelTransferInterop.hopperActive(), "the bridge switch does not take the hopper with it");
			System.setProperty("forbric.hopperFabricStorage", "off");
			KernelTransferInterop.configure(loader);
			assertFalse(KernelTransferInterop.hopperActive());
			assertFalse(KernelRuntimeClasses.compiled().contains(KernelTransferInterop.HOPPER));
		}
		System.clearProperty("forbric.transferBridge");
		System.clearProperty("forbric.hopperFabricStorage");
		try (var loader = hopperLoader(false, true)) {
			KernelTransferInterop.configure(loader);
			assertFalse(KernelTransferInterop.hopperActive(), "without fabric-transfer-api there is nothing to ask");
			assertTrue(CompatibilityFindings.all().isEmpty(), CompatibilityFindings.all()::toString);
		}
		try (var loader = hopperLoader(true, false)) {
			KernelTransferInterop.configure(loader);
			assertFalse(KernelTransferInterop.hopperActive());
			assertTrue(CompatibilityFindings.all().stream().anyMatch(f -> f.id().equals("transfer-hopper-component") && !f.required()),
					CompatibilityFindings.all()::toString);
		}
	}
	private ForbricClassLoader hopperLoader(boolean fabric, boolean runtime) throws Exception {
		Path jar = directory.resolve("hopper.jar");
		try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
			add(out, "net/neoforged/neoforge/transfer/item/ContainerOrHandler");
			if (fabric) add(out, "net/fabricmc/fabric/api/transfer/v1/item/ItemStorage");
			if (runtime) add(out, KernelTransferInterop.HOPPER.replace('.', '/'));
		}
		return new ForbricClassLoader(new java.net.URL[] {jar.toUri().toURL()}, getClass().getClassLoader());
	}
	private ForbricClassLoader loader(boolean apis, boolean runtime) throws Exception { return loader(apis, runtime, false, false); }
	private ForbricClassLoader loader(boolean apis, boolean runtime, boolean reborn, boolean energyRuntime) throws Exception {
		Path jar = directory.resolve("fixture.jar");
		try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
			if (apis) for (String name : List.of("net/fabricmc/fabric/api/transfer/v1/storage/Storage",
					"net/neoforged/neoforge/transfer/ResourceHandler")) add(out, name);
			if (runtime) for (String name : List.of(KernelTransferInterop.BRIDGE, KernelTransferInterop.ISSUES,
					KernelTransferInterop.TRANSACTIONS)) add(out, name.replace('.', '/'));
			if (reborn) add(out, KernelTransferInterop.REBORN_API.substring(0, KernelTransferInterop.REBORN_API.length() - ".class".length()));
			if (energyRuntime) add(out, KernelTransferInterop.ENERGY.replace('.', '/'));
		}
		return new ForbricClassLoader(new java.net.URL[] {jar.toUri().toURL()}, getClass().getClassLoader());
	}
	private static void add(JarOutputStream out, String name) throws Exception {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
		var method = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
		method.visitCode(); method.visitTypeInsn(Opcodes.NEW, "java/lang/AssertionError"); method.visitInsn(Opcodes.DUP);
		method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/AssertionError", "<init>", "()V", false);
		method.visitInsn(Opcodes.ATHROW); method.visitMaxs(2, 0); method.visitEnd(); writer.visitEnd();
		out.putNextEntry(new JarEntry(name + ".class")); out.write(writer.toByteArray()); out.closeEntry();
	}
}
