/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

/**
 * On today's merged base, the vanilla-reference sidecar the installer builds IS the FABRIC index the merged base embeds:
 * the same rows in the same order and the same vanilla copies, so preferring it changes no adapter's evidence. That is
 * what lets every launch path pass the sidecar now, while the base still embeds its own, and lets the genuine base later
 * rely on the sidecar alone. Built by the installer's own writer (TestFixtures.vanillaReference), from the vanilla jar.
 */
class VanillaReferenceStagedTest {
	private static final String FABRIC = NativeReferenceSources.PREFIX + "FABRIC/";
	/** Classes the measured adapters and repairs read FABRIC references of. */
	private static final List<String> ADAPTER_CLASSES = List.of("net/minecraft/world/entity/LivingEntity",
			"net/minecraft/world/level/block/entity/FuelValues", "net/minecraft/client/renderer/block/FluidRenderer",
			"net/minecraft/server/ReloadableServerRegistries", "net/minecraft/server/WorldLoader",
			"net/minecraft/resources/RegistryDataLoader", "net/minecraft/world/item/ItemStack",
			"net/minecraft/world/level/block/state/BlockBehaviour");

	private static Path merged() {
		Path merged = TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar");
		TestFixtures.requireFiles(Fixture.STAGED, "the staged merged base", merged);
		try (ZipFile zip = new ZipFile(merged.toFile())) {
			TestFixtures.require(Fixture.STAGED, zip.getEntry(FABRIC + "index.tsv") != null,
					"the staged base embeds no FABRIC index, so it is not the merged base this proof compares against: "
							+ merged);
		} catch (IOException unreadable) {
			throw new AssertionError(merged + " is not a readable jar", unreadable);
		}
		return merged;
	}

	@Test
	void theSidecarIsTheEmbeddedFabricIndexRowForRowAndCopyForCopy() throws Exception {
		Path merged = merged();
		Map<String, String> embedded = fabricTree(merged), sidecar = fabricTree(TestFixtures.vanillaReference(merged));
		assertEquals(embedded.get(FABRIC + "index.tsv"), sidecar.get(FABRIC + "index.tsv"),
				"the index: every vanilla class, in name order, with its vanilla hash");
		String index = read(merged, FABRIC + "index.tsv");
		assertTrue(index.lines().count() > 10_000, "the whole vanilla game is indexed: " + index.lines().count() + " rows");
		// Then every copy, by its digest: the same classes keep their vanilla bytes, and they are the same bytes.
		assertEquals(embedded.keySet(), sidecar.keySet(), "the same classes carry their vanilla bytes");
		assertEquals(embedded, sidecar, "and those bytes are the same");
	}

	@Test
	void preferringTheSidecarChangesNoFabricAnswerOnTheMergedBase() throws Exception {
		Path merged = merged();
		NativeReferenceSources sources = NativeReferenceSources.open(merged, TestFixtures.vanillaReference(merged));
		String line = sources.describe();
		assertTrue(line.contains("native references: FABRIC from the vanilla-reference sidecar"), line);
		assertTrue(line.contains("FORGE from the base's embedded index") && line.contains("NEOFORGE from the base's embedded index"),
				"the merged base keeps answering for the two halves only it carries: " + line);
		assertTrue(NativeReferenceSources.open(merged, null).describe()
				.contains("native references: FABRIC from the base's embedded index"), "and for FABRIC, with no sidecar");
		try (ZipFile zip = new ZipFile(merged.toFile())) {
			java.util.function.Function<String, byte[]> resources = path -> {
				try {
					ZipEntry entry = zip.getEntry(path);
					return entry == null ? null : zip.getInputStream(entry).readAllBytes();
				} catch (IOException unreadable) {
					throw new AssertionError(unreadable);
				}
			};
			NativeGameReferences embeddedOnly = new NativeGameReferences(resources);
			NativeGameReferences withSidecar = new NativeGameReferences(resources, null, sources);
			for (String owner : ADAPTER_CLASSES) {
				for (Ecosystem ecosystem : Ecosystem.values()) {
					ClassNode before = embeddedOnly.get(ecosystem, owner), after = withSidecar.get(ecosystem, owner);
					assertNotNull(before, ecosystem + " " + owner + " is answered by the embedded index");
					assertArrayEquals(write(before), write(after), ecosystem + " " + owner);
				}
			}
		}
	}

	/** The FABRIC tree of {@code jar}: each entry's SHA-256, by name. */
	private static Map<String, String> fabricTree(Path jar) throws Exception {
		Map<String, String> tree = new TreeMap<>();
		MessageDigest sha = MessageDigest.getInstance("SHA-256");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (var entries = zip.entries(); entries.hasMoreElements(); ) {
				ZipEntry entry = entries.nextElement();
				if (!entry.getName().startsWith(FABRIC)) continue;
				try (InputStream in = zip.getInputStream(entry)) {
					tree.put(entry.getName(), HexFormat.of().formatHex(sha.digest(in.readAllBytes())));
				}
			}
		}
		return tree;
	}

	private static String read(Path jar, String entry) throws IOException {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			return new String(zip.getInputStream(zip.getEntry(entry)).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		}
	}

	private static byte[] write(ClassNode node) {
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}
}
