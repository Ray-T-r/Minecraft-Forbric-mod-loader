/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

/**
 * The vanilla-reference sidecar on the staged base (NeoForge's patched game), which embeds no native-reference index of
 * its own: the sidecar is then the only place a FABRIC reference can come from, so it must be measured against exactly
 * this base, index every vanilla class, pin each one's vanilla bytes, and make every FABRIC answer an adapter reads
 * vanilla's. Built by the installer's own writer (TestFixtures.vanillaReference), from the vanilla jar.
 */
class VanillaReferenceStagedTest {
	private static final String FABRIC = NativeReferenceSources.PREFIX + "FABRIC/";
	private static final String PROVENANCE = "META-INF/forbric/vanilla-reference.properties";
	/** Classes the measured adapters and repairs read FABRIC references of. */
	private static final List<String> ADAPTER_CLASSES = List.of("net/minecraft/world/entity/LivingEntity",
			"net/minecraft/world/level/block/entity/FuelValues", "net/minecraft/client/renderer/block/FluidRenderer",
			"net/minecraft/server/ReloadableServerRegistries", "net/minecraft/server/WorldLoader",
			"net/minecraft/resources/RegistryDataLoader", "net/minecraft/world/item/ItemStack",
			"net/minecraft/world/level/block/state/BlockBehaviour");

	private static Path base() {
		Path base = TestFixtures.stagedBase();
		TestFixtures.requireFiles(Fixture.STAGED, "the staged base", base);
		return base;
	}

	private static Path vanilla() {
		Path vanilla = TestFixtures.vanillaJar();
		TestFixtures.requireFiles(Fixture.MC_LIBRARIES, "the vanilla jar the sidecar is built from", vanilla);
		return vanilla;
	}

	@Test
	void theStagedBaseEmbedsNoIndexSoTheSidecarIsTheOnlyFabricSource() throws Exception {
		Path base = base();
		try (ZipFile zip = new ZipFile(base.toFile())) {
			for (Ecosystem ecosystem : Ecosystem.values()) {
				assertNull(zip.getEntry(NativeReferenceSources.PREFIX + ecosystem.name() + "/index.tsv"),
						"the base embeds a " + ecosystem + " index: it is not NeoForge's own patched game");
			}
		}
		String alone = NativeReferenceSources.open(base, null).describe();
		assertTrue(alone.contains("FABRIC none"), alone);
		assertTrue(alone.contains("NEOFORGE from the base's own bytes"), alone);
		String launched = NativeReferenceSources.open(base, TestFixtures.vanillaReference(base)).describe();
		assertTrue(launched.contains("native references: FABRIC from the vanilla-reference sidecar"), launched);
		assertFalse(launched.contains("embedded index"), "nothing of the base's own stands behind the sidecar: " + launched);
		assertTrue(launched.contains("NEOFORGE from the base's own bytes"), launched);
	}

	@Test
	void theSidecarRecordsTheBaseAndTheVanillaJarItWasMeasuredAgainst() throws Exception {
		Path base = base(), vanilla = vanilla();
		Map<String, String> recorded = new HashMap<>();
		try (ZipFile sidecar = new ZipFile(TestFixtures.vanillaReference(base).toFile())) {
			for (String line : new String(entry(sidecar, PROVENANCE), java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
				int eq = line.indexOf('=');
				if (eq > 0) recorded.put(line.substring(0, eq), line.substring(eq + 1).strip());
			}
		}
		assertEquals("1", recorded.get("format"), recorded.toString());
		assertEquals(sha1(base), recorded.get("base-sha1"), "the sidecar names the staged base by its SHA-1");
		assertEquals(sha1(vanilla), recorded.get("vanilla-sha1"), "and the vanilla jar it copied from");
	}

	/**
	 * Every vanilla class is indexed with its vanilla hash, and every row is verifiable on this base: the sidecar carries
	 * the vanilla bytes where the base changed the class, and where it did not, the base's own bytes are vanilla's.
	 */
	@Test
	void everyVanillaClassIsIndexedAndEveryRowVerifiesOnThisBase() throws Exception {
		Path base = base(), vanilla = vanilla();
		try (ZipFile sidecar = new ZipFile(TestFixtures.vanillaReference(base).toFile());
				ZipFile vanillaJar = new ZipFile(vanilla.toFile()); ZipFile baseJar = new ZipFile(base.toFile())) {
			TreeSet<String> vanillaClasses = new TreeSet<>();
			for (var entries = vanillaJar.entries(); entries.hasMoreElements(); ) {
				String name = entries.nextElement().getName();
				if (name.endsWith(".class")) vanillaClasses.add(name.substring(0, name.length() - ".class".length()));
			}
			List<String> rows = new String(entry(sidecar, FABRIC + "index.tsv"), java.nio.charset.StandardCharsets.UTF_8)
					.lines().toList();
			assertEquals("# forbric-native-reference-v1", rows.getFirst());
			List<String> indexed = new ArrayList<>(), mismatched = new ArrayList<>();
			int copies = 0;
			for (String row : rows.subList(1, rows.size())) {
				int tab = row.indexOf('\t');
				String owner = row.substring(0, tab), hash = row.substring(tab + 1);
				indexed.add(owner);
				assertEquals(sha256(entry(vanillaJar, owner + ".class")), hash, "the row pins vanilla's bytes: " + owner);
				byte[] copy = entry(sidecar, FABRIC + owner + ".class.bin");
				if (copy != null) {
					copies++;
					if (!hash.equals(sha256(copy))) mismatched.add(owner + " (copy)");
				} else {
					byte[] own = entry(baseJar, owner + ".class");
					if (own == null || !hash.equals(sha256(own))) mismatched.add(owner + " (no copy, base differs)");
				}
			}
			assertEquals(new ArrayList<>(vanillaClasses), indexed, "every vanilla class, in name order");
			assertTrue(indexed.size() > 10_000, "the whole vanilla game is indexed: " + indexed.size() + " rows");
			assertTrue(copies > 0 && copies < indexed.size(), "copies only of the classes the base changed: " + copies);
			assertEquals(List.of(), mismatched, "a row that cannot verify on this base answers nothing");
		}
	}

	/** With the sidecar the FABRIC answer for every class an adapter reads is vanilla's own class; without it, none. */
	@Test
	void withTheSidecarTheFabricAnswersAreVanillasAndWithoutItThereAreNone() throws Exception {
		Path base = base(), vanilla = vanilla();
		try (ZipFile baseJar = new ZipFile(base.toFile()); ZipFile vanillaJar = new ZipFile(vanilla.toFile())) {
			java.util.function.Function<String, byte[]> resources = path -> entry(baseJar, path);
			NativeGameReferences launched = new NativeGameReferences(resources, null,
					NativeReferenceSources.open(base, TestFixtures.vanillaReference(base)));
			NativeGameReferences alone = new NativeGameReferences(resources, null, NativeReferenceSources.open(base, null));
			for (String owner : ADAPTER_CLASSES) {
				ClassNode answered = launched.get(Ecosystem.FABRIC, owner);
				assertNotNull(answered, "FABRIC " + owner + " is answered from the sidecar");
				ClassNode expected = new ClassNode();
				new ClassReader(entry(vanillaJar, owner + ".class")).accept(expected, ClassReader.SKIP_FRAMES); // as get() reads
				assertArrayEquals(write(expected), write(answered), "FABRIC " + owner + " is vanilla's class");
				assertNull(alone.get(Ecosystem.FABRIC, owner), "and with no sidecar nothing answers FABRIC " + owner);
			}
		}
	}

	/**
	 * A sidecar measured against another base (here vanilla itself, which leaves every row hash-only) cannot verify the
	 * rows of the classes this base changed, so those answer nothing instead of answering this base's own bytes; a class
	 * the base left as vanilla wrote it still verifies.
	 */
	@Test
	void aSidecarMeasuredAgainstAnotherBaseAnswersNothingItCannotVerify(@org.junit.jupiter.api.io.TempDir Path work) throws Exception {
		Path base = base(), vanilla = vanilla();
		Path stale = net.forbric.installer.kernel.VanillaReference.ensure(vanilla, vanilla,
				work.resolve(net.forbric.installer.kernel.VanillaReference.fileName("26.2"))).file();
		try (ZipFile baseJar = new ZipFile(base.toFile()); ZipFile vanillaJar = new ZipFile(vanilla.toFile());
				ZipFile staleJar = new ZipFile(stale.toFile())) {
			assertNull(entry(staleJar, FABRIC + ADAPTER_CLASSES.getFirst() + ".class.bin"), "premise: hash-only rows");
			java.util.function.Function<String, byte[]> resources = path -> entry(baseJar, path);
			NativeGameReferences withStale = new NativeGameReferences(resources, null, NativeReferenceSources.open(base, stale));
			int changed = 0;
			for (String owner : ADAPTER_CLASSES) {
				byte[] own = entry(baseJar, owner + ".class");
				assertNotNull(own, owner);
				boolean leftVanilla = sha256(own).equals(sha256(entry(vanillaJar, owner + ".class")));
				ClassNode answered = withStale.get(Ecosystem.FABRIC, owner);
				if (leftVanilla) {
					assertNotNull(answered, owner + " is vanilla's bytes on the base, so its hash-only row verifies");
				} else {
					changed++;
					assertNull(answered, owner + " was changed by the base, so its hash-only row cannot verify");
				}
			}
			assertTrue(changed > 0, "premise: the base changed some of the adapter classes");
		}
	}

	private static byte[] entry(ZipFile zip, String path) {
		ZipEntry entry = zip.getEntry(path);
		if (entry == null) return null;
		try (InputStream in = zip.getInputStream(entry)) {
			return in.readAllBytes();
		} catch (IOException unreadable) {
			throw new AssertionError(unreadable);
		}
	}

	private static String sha256(byte[] bytes) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
	}

	private static String sha1(Path file) throws Exception {
		MessageDigest sha = MessageDigest.getInstance("SHA-1");
		try (InputStream in = Files.newInputStream(file)) {
			byte[] buffer = new byte[1 << 16];
			for (int read; (read = in.read(buffer)) > 0; ) sha.update(buffer, 0, read);
		}
		return HexFormat.of().formatHex(sha.digest());
	}

	private static byte[] write(ClassNode node) {
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}
}
