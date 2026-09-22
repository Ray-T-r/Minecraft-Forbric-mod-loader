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

package net.forbric.kernel.mapping;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import net.fabricmc.mappingio.tree.MappingTree;
import net.fabricmc.tinyremapper.IMappingProvider;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;

import net.forbric.api.Ecosystem;

/**
 * Remaps a Forge/NeoForge mod jar from the Mojang "named" (Mojmap) namespace it was compiled against to
 * Forbric's canonical runtime namespace (intermediary), using {@link ForbricMappings} to drive tiny-remapper.
 *
 * <p>This is the engine behind the {@code DEOBF_REMAP} transform phase: after this, a Forge mod's bytecode
 * references the game by the same intermediary names the Fabric substrate runs in, so its classes link and
 * load in the same instance. tiny-remapper resolves inheritance from the game/library jars passed as the
 * remap classpath (in the named namespace), so inherited members map correctly too.
 */
public final class ForgeModRemapper {
	private ForgeModRemapper() {
	}

	/**
	 * Builds a tiny-remapper mapping provider that renames a Forge mod from the named (Mojmap) namespace to
	 * intermediary, derived from the merged mapping tree.
	 */
	public static IMappingProvider mappingProvider(ForbricMappings mappings) {
		return provider(mappings, ForbricMappings.NAMED, ForbricMappings.INTERMEDIARY);
	}

	/**
	 * Builds a tiny-remapper provider for an arbitrary namespace pair held in the merged tree
	 * ({@code named}, {@code official}, {@code intermediary}). For example {@code provider(m, "official", "named")}
	 * produces the mapping that deobfuscates the vanilla game jar to Mojmap (used to build the remap classpath).
	 */
	public static IMappingProvider provider(ForbricMappings mappings, String fromNs, String toNs) {
		MappingTree tree = mappings.tree();
		String srcNs = tree.getSrcNamespace();

		boolean fromIsSrc = fromNs.equals(srcNs);
		boolean toIsSrc = toNs.equals(srcNs);
		int fromId = fromIsSrc ? -1 : tree.getNamespaceId(fromNs);
		int toId = toIsSrc ? -1 : tree.getNamespaceId(toNs);

		if (!fromIsSrc && fromId < 0) throw new IllegalArgumentException("unknown source namespace: " + fromNs);
		if (!toIsSrc && toId < 0) throw new IllegalArgumentException("unknown target namespace: " + toNs);

		return acceptor -> {
			for (MappingTree.ClassMapping cls : tree.getClasses()) {
				String src = fromIsSrc ? cls.getSrcName() : cls.getName(fromId);
				String dst = toIsSrc ? cls.getSrcName() : cls.getName(toId);
				if (src == null || dst == null) continue;

				acceptor.acceptClass(src, dst);

				for (MappingTree.FieldMapping field : cls.getFields()) {
					String fieldDst = toIsSrc ? field.getSrcName() : field.getName(toId);
					String fieldName = fromIsSrc ? field.getSrcName() : field.getName(fromId);
					String fieldDesc = fromIsSrc ? field.getSrcDesc() : field.getDesc(fromId);
					if (fieldDst == null || fieldName == null) continue;

					acceptor.acceptField(new IMappingProvider.Member(src, fieldName, fieldDesc), fieldDst);
				}

				for (MappingTree.MethodMapping method : cls.getMethods()) {
					String methodDst = toIsSrc ? method.getSrcName() : method.getName(toId);
					String methodName = fromIsSrc ? method.getSrcName() : method.getName(fromId);
					String methodDesc = fromIsSrc ? method.getSrcDesc() : method.getDesc(fromId);
					if (methodDst == null || methodName == null) continue;

					acceptor.acceptMethod(new IMappingProvider.Member(src, methodName, methodDesc), methodDst);
				}
			}
		};
	}

	/**
	 * Remaps {@code input} to {@code output}, mapping Mojmap → intermediary. Non-class files are copied through.
	 *
	 * @param remapClasspath the game + library jars (in the named namespace) used for inheritance resolution;
	 *                       may be empty for simple mods that only reference declared members directly.
	 */
	public static void remapJar(Path input, Path output, ForbricMappings mappings, List<Path> remapClasspath) throws IOException {
		remapJar(input, output, mappingProvider(mappings), remapClasspath);
	}

	/** Remaps {@code input} to {@code output} using an explicit provider (e.g. {@code provider(m, "official", "named")}). */
	public static void remapJar(Path input, Path output, IMappingProvider provider, List<Path> remapClasspath) throws IOException {
		Files.deleteIfExists(output);

		TinyRemapper remapper = TinyRemapper.newRemapper()
				.withMappings(provider)
				.renameInvalidLocals(false)
				.threads(1)
				.build();

		try (OutputConsumerPath out = new OutputConsumerPath.Builder(output).build()) {
			out.addNonClassFiles(input);

			if (remapClasspath != null) {
				for (Path cp : remapClasspath) {
					remapper.readClassPath(cp);
				}
			}

			remapper.readInputs(input);
			remapper.apply(out);
		} finally {
			remapper.finish();
		}
	}

	/** @see #automaticModuleName(String, Ecosystem) */
	public static String automaticModuleName(String modId) {
		return automaticModuleName(modId, Ecosystem.FORGE);
	}

	/**
	 * Deterministic, always-valid module name for a wrapped Forge-family mod. The two runtimes resolve a mod's
	 * module differently, so the name must match what each looks up:
	 * <ul>
	 *   <li><b>MinecraftForge</b> — {@code FMLModContainer} resolves by the ModFile's {@code moduleName()}
	 *       (the securejar name); Forbric namespaces it {@code forbricmod.<id>} to avoid collisions.</li>
	 *   <li><b>NeoForge</b> — {@code FMLModContainer} resolves by {@code IModFile.getId()}, which is the mod's
	 *       toml {@code modId}; the layer module must therefore be named EXACTLY that id (NeoForge modIds match
	 *       {@code [a-z][a-z0-9_]*}, already valid Java module names — no prefix, no sanitize needed).</li>
	 * </ul>
	 */
	public static String automaticModuleName(String modId, Ecosystem ecosystem) {
		String seg = modId == null || modId.isEmpty() ? "mod" : modId.replaceAll("[^A-Za-z0-9_]", "_");
		if (Character.isDigit(seg.charAt(0))) seg = "_" + seg;
		if (ecosystem == Ecosystem.NEOFORGE) return seg;
		return "forbricmod." + seg;
	}
}
