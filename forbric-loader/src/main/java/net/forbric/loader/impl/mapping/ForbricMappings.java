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

package net.forbric.loader.impl.mapping;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.adapter.MappingNsRenamer;
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch;
import net.fabricmc.mappingio.format.MappingFormat;
import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

/**
 * The Forbric mapping spine: it exposes the game in the Fabric (intermediary) and Forge (Mojang
 * "named") namespaces at once, so a Forge mod's bytecode can be remapped onto the canonical runtime
 * namespace (intermediary) that the Fabric substrate runs in.
 *
 * <p>It is built by joining two permitted, non-MCP sources on their shared obfuscated column:
 * <ul>
 *   <li>Fabric <b>intermediary</b> ({@code official → intermediary}), and</li>
 *   <li>Mojang official "<b>Mojmap</b>" mappings (ProGuard {@code named → official}).</li>
 * </ul>
 * The result is a tree keyed by the <b>named</b> (Mojmap) namespace whose values include intermediary,
 * letting {@link #mapClass}/{@link #mapField}/{@link #mapMethod} translate the names a modern Forge mod
 * references into the runtime names. (SRG can be synthesized from the same join when older Forge mods
 * need it; not bundled here.) No MCP data is ever read — see {@code MAPPINGS.md}.
 */
public final class ForbricMappings {
	/** The namespace a modern Forge/NeoForge mod references the game in. */
	public static final String NAMED = "named";
	/** The Fabric runtime namespace = Forbric's canonical namespace on 1.21.x. */
	public static final String INTERMEDIARY = "intermediary";
	/** The obfuscated namespace shared by both mapping sources (the join key). */
	public static final String OFFICIAL = "official";

	private final MemoryMappingTree namedKeyed;
	private final int intermediaryNs;

	private ForbricMappings(MemoryMappingTree namedKeyed) {
		this.namedKeyed = namedKeyed;
		this.intermediaryNs = namedKeyed.getNamespaceId(INTERMEDIARY);

		if (intermediaryNs < 0) {
			throw new IllegalStateException("merged mappings are missing the intermediary namespace");
		}
	}

	/**
	 * Builds the spine from a Fabric intermediary mapping file ({@code official → intermediary}, Tiny v1/v2)
	 * and a Mojang ProGuard mapping file ({@code named → official}).
	 */
	public static ForbricMappings load(Path intermediaryMappings, Path mojmapProguard) throws IOException {
		// Start keyed by 'official' (obf): intermediary already is.
		MemoryMappingTree officialKeyed = new MemoryMappingTree();
		MappingReader.read(intermediaryMappings, officialKeyed);

		// ProGuard exposes (source=named, target=obf); rename target→official + source→named, then re-root on official.
		MappingReader.read(mojmapProguard, MappingFormat.PROGUARD_FILE,
				new MappingNsRenamer(
						new MappingSourceNsSwitch(officialKeyed, OFFICIAL),
						Map.of("source", NAMED, "target", OFFICIAL)));

		// Re-root the merged tree on 'named' so Forge mods (which speak Mojmap) can be looked up directly.
		MemoryMappingTree namedKeyed = new MemoryMappingTree();
		officialKeyed.accept(new MappingSourceNsSwitch(namedKeyed, NAMED));

		return new ForbricMappings(namedKeyed);
	}

	/** Maps a Mojmap class internal name (e.g. {@code net/minecraft/world/phys/Vec3}) to intermediary, or returns the input if unmapped. */
	public String mapClass(String namedInternalName) {
		MappingTree.ClassMapping cls = namedKeyed.getClass(namedInternalName);
		return cls == null ? namedInternalName : orSelf(cls.getName(intermediaryNs), namedInternalName);
	}

	/** Maps a Mojmap field to its intermediary name. */
	public String mapField(String namedOwner, String namedFieldName, String namedDesc) {
		MappingTree.ClassMapping cls = namedKeyed.getClass(namedOwner);
		if (cls == null) return namedFieldName;

		MappingTree.FieldMapping field = cls.getField(namedFieldName, namedDesc);
		return field == null ? namedFieldName : orSelf(field.getName(intermediaryNs), namedFieldName);
	}

	/** Maps a Mojmap method to its intermediary name. {@code namedDesc} may be {@code null} to match by name only. */
	public String mapMethod(String namedOwner, String namedMethodName, String namedDesc) {
		MappingTree.ClassMapping cls = namedKeyed.getClass(namedOwner);
		if (cls == null) return namedMethodName;

		MappingTree.MethodMapping method = cls.getMethod(namedMethodName, namedDesc);
		return method == null ? namedMethodName : orSelf(method.getName(intermediaryNs), namedMethodName);
	}

	/** The merged tree (named-keyed), for driving a bytecode remapper (e.g. tiny-remapper) in the DEOBF_REMAP phase. */
	public MappingTree tree() {
		return namedKeyed;
	}

	public int classCount() {
		return namedKeyed.getClasses().size();
	}

	private static String orSelf(String mapped, String fallback) {
		return mapped == null ? fallback : mapped;
	}
}
