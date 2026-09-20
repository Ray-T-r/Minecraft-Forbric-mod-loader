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

package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;

/** The three anchor kinds over REAL fabric-api mixins and the RAW merged base — the verdicts before any repair. */
class MixinFitStagedTest {
	static final Path MERGED_BASE = Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run",
			"merged-base", "patched-mc-merged-26.2.jar").normalize();
	static final Path CLIENT_MODS = Path.of(System.getProperty("user.dir"), "run", "client-kernel", "mods").normalize();

	static final String RENDER_PIPELINE_BUILDER_MIXIN = "net/fabricmc/fabric/mixin/client/rendering/RenderPipelineBuilderMixin.class";
	static final String ATTRIBUTE_BUILDER_ACCESSOR = "net/fabricmc/fabric/mixin/object/builder/AttributeSupplierBuilderAccessor.class";
	static final String SIMPLE_CONTAINER_MIXIN = "net/fabricmc/fabric/mixin/transfer/SimpleContainerMixin.class";

	/** Today's false FIT: the merged Snippet constructor takes 12 arguments, fabric's wrap handles 11. */
	@Test
	void theSnippetWrapReadsPartialOnTheRawBaseNamingTheArity() throws Exception {
		Function<String, byte[]> resolver = rawResolver();
		byte[] mixin = nested("fabric-rendering-v1", RENDER_PIPELINE_BUILDER_MIXIN);
		MixinFit.Result r = MixinFit.evaluate(mixin, resolver);
		assertEquals(MixinFit.Verdict.PARTIAL, r.verdict(), r.unresolved().toString());
		assertEquals(1, r.unresolved().size(), r.unresolved().toString());
		assertTrue(r.unresolved().get(0).contains("@At(NEW)") && r.unresolved().get(0).contains("Snippet")
				&& r.unresolved().get(0).contains("handler wraps a 11-arg constructor, the call site constructs with 12"),
				r.unresolved().toString());
	}

	/** Mixin's InvalidAccessorException on every fabric-api boot, now named as an anchor. */
	@Test
	void theAttributeBuilderAccessorCannotBindOnTheRawBase() throws Exception {
		Function<String, byte[]> resolver = rawResolver();
		byte[] mixin = nested("fabric-object-builder-api-v1", ATTRIBUTE_BUILDER_ACCESSOR);
		MixinFit.Result r = MixinFit.evaluate(mixin, resolver);
		assertEquals(1, r.unresolved().size(), r.unresolved().toString());
		assertTrue(r.unresolved().get(0).contains("@Accessor field")
				&& r.unresolved().get(0).contains("builder:Lcom/google/common/collect/ImmutableMap$Builder;"), r.unresolved().toString());
	}

	/** An explicit-descriptor selector is not touched by overload resolution — the R1 case stays R1's. */
	@Test
	void anExplicitDescriptorSelectorIsStillPartialWithoutRetargeting() throws Exception {
		Function<String, byte[]> resolver = rawResolver();
		byte[] mixin = nested("fabric-transfer-api-v1", SIMPLE_CONTAINER_MIXIN);
		assertEquals(MixinFit.Verdict.PARTIAL, MixinFit.evaluate(mixin, resolver).verdict());
	}

	// ---------------------------------------------------------------------------------------------------------------

	static Function<String, byte[]> rawResolver() {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		return name -> {
			try {
				return readFromJar(MERGED_BASE, name);
			} catch (Exception e) {
				return null;
			}
		};
	}

	static byte[] nested(String module, String entry) throws Exception {
		Path fabricApi = fabricApiJar();
		assumeTrue(fabricApi != null, "fabric-api jar absent from run/client-kernel/mods");
		byte[] bytes = readFromNestedJar(fabricApi, module, entry);
		assumeTrue(bytes != null, entry + " absent from the nested " + module);
		return bytes;
	}

	static Path fabricApiJar() throws Exception {
		if (!Files.isDirectory(CLIENT_MODS)) return null;
		try (var files = Files.list(CLIENT_MODS)) {
			return files.filter(p -> p.getFileName().toString().startsWith("fabric-api-")).findFirst().orElse(null);
		}
	}

	static byte[] readFromJar(Path jar, String entry) throws Exception {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry found = zip.getEntry(entry);
			if (found == null) return null;
			try (InputStream in = zip.getInputStream(found)) {
				return in.readAllBytes();
			}
		}
	}

	static byte[] readFromNestedJar(Path outer, String modulePrefix, String entry) throws Exception {
		try (ZipFile zip = new ZipFile(outer.toFile())) {
			for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
				ZipEntry nested = e.nextElement();
				if (!nested.getName().startsWith("META-INF/jars/" + modulePrefix)) continue;
				Path tmp = Files.createTempFile("forbric-nested", ".jar");
				try (InputStream in = zip.getInputStream(nested)) {
					Files.write(tmp, in.readAllBytes());
				}
				try {
					byte[] bytes = readFromJar(tmp, entry);
					if (bytes != null) return bytes;
				} finally {
					Files.deleteIfExists(tmp);
				}
			}
		}
		return null;
	}
}
