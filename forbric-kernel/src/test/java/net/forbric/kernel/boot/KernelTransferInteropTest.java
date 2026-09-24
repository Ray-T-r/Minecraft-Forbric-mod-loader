package net.forbric.kernel.boot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;
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
		if ("1".equals(System.getenv("FORBRIC_COMPAT_FIXTURES_REQUIRED"))) assertTrue(gameSide, "game-side classes were not compiled");
		org.junit.jupiter.api.Assumptions.assumeTrue(gameSide, "no staged game jars, so no game side was built");
		for (String name : List.of(KernelTransferInterop.BRIDGE, KernelTransferInterop.ISSUES, KernelTransferInterop.TRANSACTIONS,
				KernelTransferInterop.ENERGY)) {
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
		if ("1".equals(System.getenv("FORBRIC_COMPAT_FIXTURES_REQUIRED"))) assertTrue(gameSide, "game-side classes were not compiled");
		org.junit.jupiter.api.Assumptions.assumeTrue(gameSide, "no staged game jars, so no game side was built");
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
