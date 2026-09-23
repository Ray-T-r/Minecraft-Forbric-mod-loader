package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import net.forbric.api.DiscoveredMod;
import net.forbric.api.Ecosystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class FrapiRendererEvidenceTest {
	@TempDir Path temp;

	private static byte[] caller(String name, String owner, String method) {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
		MethodVisitor code = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "(Ljava/lang/Object;)V", null, null);
		code.visitCode();
		code.visitVarInsn(Opcodes.ALOAD, 0);
		code.visitMethodInsn(Opcodes.INVOKESTATIC, owner, method, "(Ljava/lang/Object;)V", owner.endsWith("/Renderer"));
		code.visitInsn(Opcodes.RETURN);
		code.visitMaxs(1, 1);
		code.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}
	private static byte[] jar(Map<String, byte[]> entries) throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
			for (var entry : entries.entrySet()) { zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue()); zip.closeEntry(); }
		}
		return bytes.toByteArray();
	}
	private Path write(String name, byte[] content) throws Exception { Path path = temp.resolve(name); Files.write(path, content); return path; }

	@Test void aDirectRegistrationInsideANestedJarKeepsThePromise() throws Exception {
		byte[] inner = jar(Map.of("mod/Registrar.class", caller("mod/Registrar", "net/fabricmc/fabric/api/client/renderer/v1/Renderer", "register")));
		assertTrue(FrapiRendererEvidence.registersRenderer(write("outer.jar", jar(Map.of("META-INF/jarjar/inner.jar", inner)))));
		assertTrue(FrapiRendererEvidence.registersRenderer(write("impl.jar", jar(Map.of("mod/Impl.class",
				caller("mod/Impl", "net/fabricmc/fabric/impl/client/renderer/RendererManager", "registerRenderer"))))));
	}

	@Test void onlyReadingTheRendererIsNotRegisteringOne() throws Exception {
		assertFalse(FrapiRendererEvidence.registersRenderer(write("reader.jar", jar(Map.of("mod/Reader.class",
				caller("mod/Reader", "net/fabricmc/fabric/api/client/renderer/v1/Renderer", "get"))))));
		assertFalse(FrapiRendererEvidence.registersRenderer(write("empty.jar", jar(Map.of("a.txt", new byte[1])))));
	}

	@Test void aBuildThatCannotRegisterDoesNotForwardTheDeclarationButAnUnreadableOneStillDoes() throws Exception {
		Path none = write("none.jar", jar(Map.of("mod/Reader.class", caller("mod/Reader", "net/fabricmc/fabric/api/client/renderer/v1/Renderer", "get"))));
		assertFalse(KernelFabricEcosystem.keepsRendererPromise(mod(none.toString())));
		Path broken = write("broken.jar", "not a zip".getBytes());
		assertTrue(KernelFabricEcosystem.keepsRendererPromise(mod(broken.toString())), "unknown stays as declared");
		assertTrue(KernelFabricEcosystem.keepsRendererPromise(mod(temp.resolve("missing.jar").toString())));
	}

	/** The measured pair: Sodium 0.9.1's NeoForge build declares a renderer it does not ship; its Fabric build ships it. */
	@Test void theRealSodiumBuildsAreToldApart() throws Exception {
		Path mods = Path.of(System.getProperty("user.dir"), "run", "client-merged-pack", "mods").normalize();
		Path neo = mods.resolve("sodium-neoforge-0.9.1+mc26.2.jar"), fabric = mods.resolve("[钠] sodium-fabric-0.9.1+mc26.2.jar");
		boolean present = Files.isRegularFile(neo) && Files.isRegularFile(fabric);
		if ("1".equals(System.getenv("FORBRIC_COMPAT_FIXTURES_REQUIRED"))) assertTrue(present, "sodium fixtures absent: " + mods);
		if (!present) return;
		assertFalse(FrapiRendererEvidence.registersRenderer(neo));
		assertTrue(FrapiRendererEvidence.registersRenderer(fabric));
	}

	private static DiscoveredMod mod(String source) {
		return new DiscoveredMod(Ecosystem.NEOFORGE, "sodium", "0.9.1", "Sodium", List.of(), List.of(), null, source);
	}
}
