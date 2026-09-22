package net.forbric.kernel.boot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
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
	private ForbricClassLoader loader(boolean apis, boolean runtime) throws Exception {
		Path jar = directory.resolve("fixture.jar");
		try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
			if (apis) for (String name : List.of("net/fabricmc/fabric/api/transfer/v1/storage/Storage",
					"net/neoforged/neoforge/transfer/ResourceHandler")) add(out, name);
			if (runtime) for (String name : List.of(KernelTransferInterop.BRIDGE, KernelTransferInterop.ISSUES,
					KernelTransferInterop.TRANSACTIONS)) add(out, name.replace('.', '/'));
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
