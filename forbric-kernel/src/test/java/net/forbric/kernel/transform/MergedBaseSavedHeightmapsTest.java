/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Covers making {@code ChunkStatus} report vanilla's saved-heightmap set.
 *
 * <p>Both fields must still exist afterwards. The repair is a re-aim, not a removal: NeoForge's
 * {@code chunkSaveHeightmaps} and the constructor that fills it stay, so a mod that asks NeoForge's own accessor
 * still gets an answer — only the reader {@code SerializableChunkData} goes through is moved back.
 *
 * <p>What it is protecting: {@code WORLD_SURFACE_WG} and {@code OCEAN_FLOOR_WG} stop being maintained once a
 * chunk passes CARVERS, so persisting them means reloading them stale, and
 * {@code PlacementUtils.HEIGHTMAP_WORLD_SURFACE} is what decides the Y a decoration is placed at. Measured on
 * one seed with no mods, five vanilla worlds against five Forbric ones: this was the last difference left, seven
 * dead bushes out of 5,079, always the same seven. With the repair, two fresh Forbric worlds differ from vanilla
 * by one bush each, in different places — the same noise vanilla has against itself.
 */
class MergedBaseSavedHeightmapsTest {
	private static final Path MERGED_BASE =
			Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run", "merged-base",
					"patched-mc-merged-26.2.jar").normalize();
	private static final Path VANILLA = Path.of(System.getProperty("user.home"),
			"Library", "Application Support", "minecraft", "versions", "26.2", "26.2.jar");
	private static final String CHUNK_STATUS = "net/minecraft/world/level/chunk/status/ChunkStatus";
	private static final String WIDENED = "chunkSaveHeightmaps";
	private static final String VANILLA_SHAPED = "heightmapsAfter";

	@Test
	void vanillaHasNoSecondHeightmapSetToBeginWith() throws Exception {
		assumeTrue(Files.isRegularFile(VANILLA), "no vanilla 26.2 jar at " + VANILLA);
		List<String> fields = new ArrayList<>();
		for (FieldNode field : read(entry(VANILLA)).fields) fields.add(field.name);
		assertTrue(fields.contains(VANILLA_SHAPED), "vanilla must carry the field this repair aims at");
		assertTrue(!fields.contains(WIDENED), "vanilla carries no widened saved-heightmap set; if it does, the "
				+ "premise of this repair is wrong and it should go");
	}

	@Test
	void theGetterReadsTheVanillaShapedFieldAfterTheRepair() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "merged base not staged: " + MERGED_BASE);
		byte[] before = entry(MERGED_BASE);
		assertEquals(List.of(WIDENED), reads(read(before)), "the merged getter no longer reads NeoForge's widened "
				+ "set — if the base changed, delete the repair rather than leaving a claim on a shape that is gone");

		ClassNode after = read(new ForbricMergedBaseCompatTransformer()
				.transform(CHUNK_STATUS.replace('/', '.'), before, null));
		assertEquals(List.of(VANILLA_SHAPED), reads(after), "the getter must read the vanilla-shaped field");

		List<String> fields = new ArrayList<>();
		for (FieldNode field : after.fields) fields.add(field.name);
		assertTrue(fields.contains(WIDENED), "the repair re-aims the reader; it must not remove NeoForge's field, "
				+ "which its constructor still writes and its own accessor may still be asked for");
		assertTrue(fields.contains(VANILLA_SHAPED));
	}

	@Test
	void theSwitchStandsTheRepairDown() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "merged base not staged: " + MERGED_BASE);
		String property = ForbricMergedBaseCompatTransformer.SAVED_HEIGHTMAPS_PROPERTY;
		String previous = System.getProperty(property);
		System.setProperty(property, "off");
		try {
			byte[] before = entry(MERGED_BASE);
			ClassNode after = read(new ForbricMergedBaseCompatTransformer()
					.transform(CHUNK_STATUS.replace('/', '.'), before, null));
			assertEquals(List.of(WIDENED), reads(after),
					"-D" + property + "=off must leave NeoForge's reader alone, so the world it produces can be "
							+ "compared against the repaired one");
		} finally {
			if (previous == null) System.clearProperty(property);
			else System.setProperty(property, previous);
		}
	}

	@Test
	void itLeavesAnotherClassAlone() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "merged base not staged: " + MERGED_BASE);
		byte[] other = entry(MERGED_BASE, "net/minecraft/world/level/chunk/status/ChunkType.class");
		assumeTrue(other != null, "ChunkType absent from this base");
		List<String> hits = new ArrayList<>();
		new ForbricMergedBaseCompatTransformer()
				.transform("net.minecraft.world.level.chunk.status.ChunkType", other, null, hits::add);
		assertTrue(!hits.contains("forbric-merged-base-compat#saveTheHeightmapsVanillaSaves"),
				"this repair is pinned to ChunkStatus; it claimed ChunkType instead. Claims seen: " + hits);
	}

	/** Which field {@code getChunkSaveHeightmaps()} reads, in order. */
	private static List<String> reads(ClassNode node) {
		List<String> out = new ArrayList<>();
		for (MethodNode method : node.methods) {
			if (!"getChunkSaveHeightmaps".equals(method.name)) continue;
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof FieldInsnNode read && read.getOpcode() == Opcodes.GETFIELD
						&& CHUNK_STATUS.equals(read.owner)) {
					out.add(read.name);
				}
			}
		}
		return out;
	}

	private static ClassNode read(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] entry(Path jar) throws Exception {
		return entry(jar, CHUNK_STATUS + ".class");
	}

	private static byte[] entry(Path jarPath, String path) throws Exception {
		try (ZipFile jar = new ZipFile(jarPath.toFile())) {
			ZipEntry found = jar.getEntry(path);
			if (found == null) return null;
			try (InputStream in = jar.getInputStream(found)) {
				return in.readAllBytes();
			}
		}
	}
}
