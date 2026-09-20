package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * Two guest jars carrying the same Fabric mod at different versions must be ordered newest-first, because
 * first-URL-wins is what decides which build every caller links against.
 *
 * <p>Distant Horizons nests an ENTIRE fabric-api 0.149.0 — 45 modules — and jar-file order put those ahead of the
 * fabric-api 0.161.0 the player installed. Architectury called {@code ScreenKeyboardEvents.allowCharType}, which
 * 0.149.0 does not have, and the client died in {@code Minecraft.<init>} with a NoSuchMethodError naming a
 * fabric-api class: a report that reads as a broken mod rather than as a shadowed library.
 *
 * <p>Ordering, never withdrawal: the nested arbiter deliberately leaves same-family duplicates on the classpath
 * (taking one off cost Sodium its ServiceLoader lookup once), so the loser must stay reachable.
 */
@ResourceLock("system-properties")
class KernelOwnedClasspathVersionOrderTest {
	@Test
	void theHigherVersionTakesTheEarlierSlotAndTheLoserStaysOnTheClasspath(@TempDir Path dir) throws Exception {
		Path old = fabricJar(dir, "old.jar", "fabric-screen-api-v1", "5.0.2+086d547a25");
		Path other = fabricJar(dir, "other.jar", "sodium", "0.9.2");
		Path recent = fabricJar(dir, "new.jar", "fabric-screen-api-v1", "5.2.1+5087f8249e");

		List<Path> ordered = KernelOwnedClasspath.newestFirstWithinEachModId(List.of(old, other, recent));

		assertEquals(List.of(recent, other, old), ordered,
				"the two contested jars swap slots; the uninvolved jar does not move, and neither is dropped");
	}

	@Test
	void anAlreadyCorrectOrderAndAnUncontestedIdAreLeftAlone(@TempDir Path dir) throws Exception {
		Path recent = fabricJar(dir, "new.jar", "fabric-api-base", "2.0.4");
		Path old = fabricJar(dir, "old.jar", "fabric-api-base", "2.0.3");
		Path lone = fabricJar(dir, "lone.jar", "iris", "1.11.4");

		assertEquals(List.of(recent, old, lone),
				KernelOwnedClasspath.newestFirstWithinEachModId(List.of(recent, old, lone)));
	}

	@Test
	void equalVersionsAndJarsWithNoFabricManifestKeepDiscoveryOrder(@TempDir Path dir) throws Exception {
		Path first = fabricJar(dir, "first.jar", "twin", "1.0.0");
		Path second = fabricJar(dir, "second.jar", "twin", "1.0.0");
		Path plain = plainJar(dir, "library.jar");

		// Nothing here says which copy is better, so the pass must not invent an answer.
		assertEquals(List.of(first, plain, second),
				KernelOwnedClasspath.newestFirstWithinEachModId(List.of(first, plain, second)));
	}

	@Test
	void theSwitchPutsItBackToWhicheverWasDiscoveredFirst(@TempDir Path dir) throws Exception {
		Path old = fabricJar(dir, "old.jar", "shared", "1.0.0");
		Path recent = fabricJar(dir, "new.jar", "shared", "2.0.0");

		System.setProperty(KernelOwnedClasspath.VERSION_ORDER_SWITCH, "off");
		try {
			assertEquals(List.of(old, recent), KernelOwnedClasspath.newestFirstWithinEachModId(List.of(old, recent)));
		} finally {
			System.clearProperty(KernelOwnedClasspath.VERSION_ORDER_SWITCH);
		}
	}

	private static Path fabricJar(Path dir, String name, String id, String version) throws Exception {
		Path jar = dir.resolve(name);
		try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
			zip.putNextEntry(new ZipEntry("fabric.mod.json"));
			zip.write(("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"" + version + "\"}")
					.getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		return jar;
	}

	private static Path plainJar(Path dir, String name) throws Exception {
		Path jar = dir.resolve(name);
		try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
			zip.putNextEntry(new ZipEntry("org/example/Thing.class"));
			zip.write(new byte[] {1, 2, 3});
			zip.closeEntry();
		}
		return jar;
	}
}
