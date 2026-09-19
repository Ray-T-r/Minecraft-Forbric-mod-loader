package net.forbric.kernel.compat;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

final class CompatProbeJars {
	private CompatProbeJars() { }

	static byte[] type(String name, String... references) {
		ClassWriter writer = writer(name);
		for (String reference : references) writer.newClass(reference);
		writer.visitEnd();
		return writer.toByteArray();
	}

	static ClassWriter writer(String name) {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
		return writer;
	}

	static Path write(Path path, Map<String, byte[]> entries) throws Exception {
		return Files.write(path, bytes(entries));
	}

	static byte[] bytes(Map<String, byte[]> entries) throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (JarOutputStream jar = new JarOutputStream(bytes)) {
			for (var entry : entries.entrySet()) {
				jar.putNextEntry(new JarEntry(entry.getKey()));
				jar.write(entry.getValue());
				jar.closeEntry();
			}
		}
		return bytes.toByteArray();
	}
}
