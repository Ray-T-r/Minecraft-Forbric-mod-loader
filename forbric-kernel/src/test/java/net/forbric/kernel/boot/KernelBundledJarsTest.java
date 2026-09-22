package net.forbric.kernel.boot;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class KernelBundledJarsTest {
	@TempDir Path directory;
	@Test void aNewBuildDoesNotOverwriteTheOldInstancesOpenArchive() throws Exception {
		byte[] first = jar("old"), second = jar("new");
		Path old = KernelBundledJars.materialize(directory, "runtime.jar", first);
		try (var stillOpen = new java.util.zip.ZipFile(old.toFile())) {
			Path next = KernelBundledJars.materialize(directory, "runtime.jar", second);
			assertNotEquals(old, next);
			assertArrayEquals(first, Files.readAllBytes(old));
			assertArrayEquals(second, Files.readAllBytes(next));
			assertEquals("old", new String(stillOpen.getInputStream(stillOpen.getEntry("version")).readAllBytes()));
		}
	}
	@Test void identicalBytesAreReusedWithoutRewriting() throws Exception {
		byte[] bytes = jar("same");
		Path file = KernelBundledJars.materialize(directory, "runtime.jar", bytes);
		Files.setLastModifiedTime(file, FileTime.fromMillis(1000));
		assertEquals(file, KernelBundledJars.materialize(directory, "runtime.jar", bytes));
		assertEquals(FileTime.fromMillis(1000), Files.getLastModifiedTime(file));
	}
	@Test void aReadableButWrongArchiveIsNeverAcceptedAsTheRequestedBuild() throws Exception {
		byte[] expected = jar("expected");
		Path file = KernelBundledJars.materialize(directory, "runtime.jar", expected);
		Files.write(file, jar("wrong but valid zip"));
		assertEquals(file, KernelBundledJars.materialize(directory, "runtime.jar", expected));
		assertArrayEquals(expected, Files.readAllBytes(file));
	}
	@Test void invalidBundledBytesFailBeforeBecomingAClasspathEntry() {
		assertThrows(java.io.IOException.class, () -> KernelBundledJars.materialize(directory, "runtime.jar", new byte[] {1, 2, 3}));
	}
	private static byte[] jar(String version) throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (JarOutputStream jar = new JarOutputStream(bytes)) {
			jar.putNextEntry(new JarEntry("version")); jar.write(version.getBytes(java.nio.charset.StandardCharsets.UTF_8)); jar.closeEntry();
		}
		return bytes.toByteArray();
	}
}
