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
		Path merged = TestFixtures.mergedBase();
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

	/**
	 * A sidecar measured against another base — here the genuine NeoForge jar, the likeliest mix-up, since both are
	 * staged side by side — changes no FABRIC answer on the merged base. Its hash-only rows for classes the merged base
	 * changed and the genuine one did not can no longer verify, and alone they would answer nothing (what the kernel did
	 * before); the merged base's own embedded index answers them instead, byte for byte what it answers with no sidecar.
	 */
	@Test
	void aSidecarBuiltAgainstAnotherBaseChangesNoFabricAnswerOnTheMergedBase() throws Exception {
		Path merged = merged();
		Path genuine = TestFixtures.genuineBase();
		TestFixtures.requireFiles(Fixture.STAGED, "the genuine NeoForge-patched base the stale sidecar is built against", genuine);
		Path stale = TestFixtures.vanillaReference(genuine);
		try (ZipFile base = new ZipFile(merged.toFile()); ZipFile sidecar = new ZipFile(stale.toFile())) {
			java.util.function.Function<String, byte[]> resources = path -> entry(base, path);
			// The premise, read off the two files: hash-only rows of the stale sidecar the merged base's bytes do not match.
			List<String> unverifiable = new java.util.ArrayList<>();
			for (String row : new String(entry(sidecar, FABRIC + "index.tsv"), java.nio.charset.StandardCharsets.UTF_8)
					.lines().skip(1).toList()) {
				String owner = row.substring(0, row.indexOf('\t'));
				if (sidecar.getEntry(FABRIC + owner + ".class.bin") != null) continue;
				byte[] bytes = entry(base, owner + ".class");
				if (bytes == null || !row.endsWith("\t" + sha256(bytes))) unverifiable.add(owner);
			}
			assertFalse(unverifiable.isEmpty(), "premise: the genuine-built sidecar leaves some merged class unverifiable");

			NativeGameReferences embeddedOnly = new NativeGameReferences(resources);
			NativeGameReferences withStale = new NativeGameReferences(resources, null, NativeReferenceSources.open(merged, stale));
			List<String> owners = new java.util.ArrayList<>(ADAPTER_CLASSES);
			owners.addAll(unverifiable);
			for (String owner : owners) {
				ClassNode before = embeddedOnly.get(Ecosystem.FABRIC, owner), after = withStale.get(Ecosystem.FABRIC, owner);
				assertNotNull(before, "FABRIC " + owner + " is answered by the embedded index");
				assertNotNull(after, "FABRIC " + owner + " is still answered with the stale sidecar");
				assertArrayEquals(write(before), write(after), "FABRIC " + owner);
			}
			assertTrue(NativeGameReferences.sidecarFallbackReported(), "and the fallback was said");
			System.out.println("[VanillaReferenceStagedTest] " + unverifiable.size()
					+ " merged classes the genuine-built sidecar cannot verify, all answered by the embedded index");
		}
	}

	/**
	 * The other way a sidecar goes wrong: a row that pins other bytes than the base's own index does (a sidecar built from
	 * another vanilla jar). Its copy verifies against its own row, so only the embedded index can tell; it answers.
	 */
	@Test
	void aSidecarRowPinningOtherBytesIsAnsweredByTheEmbeddedIndex(@org.junit.jupiter.api.io.TempDir Path work) throws Exception {
		Path merged = merged();
		String living = ADAPTER_CLASSES.getFirst(), other = ADAPTER_CLASSES.get(1);
		Path forged = work.resolve("vanilla-reference-26.2.jar");
		try (ZipFile current = new ZipFile(TestFixtures.vanillaReference(merged).toFile());
				ZipFile base = new ZipFile(merged.toFile());
				java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(forged))) {
			// LivingEntity's row and copy become another class's vanilla bytes: self-consistent, and wrong.
			byte[] foreign = entry(current, FABRIC + other + ".class.bin");
			if (foreign == null) foreign = entry(base, other + ".class");
			assertNotNull(foreign, other);
			String livingRow = null;
			for (var entries = current.entries(); entries.hasMoreElements(); ) {
				ZipEntry entry = entries.nextElement();
				byte[] bytes = entry(current, entry.getName());
				if (entry.getName().equals(FABRIC + living + ".class.bin")) continue;
				if (entry.getName().equals(FABRIC + "index.tsv")) {
					StringBuilder index = new StringBuilder();
					for (String row : new String(bytes, java.nio.charset.StandardCharsets.UTF_8).lines().toList()) {
						if (row.startsWith(living + "\t")) {
							livingRow = row;
							row = living + "\t" + sha256(foreign);
						}
						index.append(row).append('\n');
					}
					bytes = index.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
				}
				out.putNextEntry(new ZipEntry(entry.getName()));
				out.write(bytes);
				out.closeEntry();
			}
			assertNotNull(livingRow, "premise: the sidecar indexes " + living);
			out.putNextEntry(new ZipEntry(FABRIC + living + ".class.bin"));
			out.write(foreign);
			out.closeEntry();
		}
		try (ZipFile base = new ZipFile(merged.toFile())) {
			java.util.function.Function<String, byte[]> resources = path -> entry(base, path);
			NativeReferenceSources sources = NativeReferenceSources.open(merged, forged);
			assertTrue(sources.describe().contains("(the base's embedded index answers what it cannot verify)"), sources.describe());
			ClassNode answered = new NativeGameReferences(resources, null, sources).get(Ecosystem.FABRIC, living);
			assertNotNull(answered, living);
			assertEquals(living, answered.name, "not the other class the forged row pins");
			assertArrayEquals(write(new NativeGameReferences(resources).get(Ecosystem.FABRIC, living)), write(answered));
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
