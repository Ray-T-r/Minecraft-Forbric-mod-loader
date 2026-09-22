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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * R1 over the REAL fabric-transfer-api-v1 mixins and the REAL merged containers — the two beneficiaries — and
 * the one look-alike it must refuse: fabric-content-registries' {@code FuelValuesMixin} captures two
 * {@code @Local}s that only the stub's parameter list carries.
 */
class MixinRetargetStagedTest {
	private static final Path MERGED_BASE = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", System.getProperty("user.dir") + "/../forbric-loader"), "run",
			"merged-base", "patched-mc-merged-26.2.jar").normalize();
	private static final Path CLIENT_MODS = Path.of(System.getProperty("user.dir"), "run", "client-kernel", "mods").normalize();

	private static final String SIMPLE_CONTAINER_MIXIN = "net/fabricmc/fabric/mixin/transfer/SimpleContainerMixin.class";
	private static final String BASE_CONTAINER_MIXIN = "net/fabricmc/fabric/mixin/transfer/BaseContainerBlockEntityMixin.class";
	private static final String FUEL_VALUES_MIXIN = "net/fabricmc/fabric/mixin/content/registry/FuelValuesMixin.class";
	private static final String SET_ITEM_STUB = "setItem(ILnet/minecraft/world/item/ItemStack;)V";
	private static final String SET_ITEM_DELEGATE = "setItem(ILnet/minecraft/world/item/ItemStack;Z)V";

	@AfterEach
	void reset() {
		MixinRetarget.reset();
	}

	@Test
	void fabricTransfersTwoContainerMixinsGoPartialToFit() throws Exception {
		Function<String, byte[]> resolver = mergedResolver();
		for (String entry : new String[] { SIMPLE_CONTAINER_MIXIN, BASE_CONTAINER_MIXIN }) {
			byte[] mixin = nested("fabric-transfer-api-v1", entry);
			MixinFit.Result raw = MixinFit.evaluate(mixin, resolver);
			assertEquals(MixinFit.Verdict.PARTIAL, raw.verdict(), entry + " premise: " + raw.unresolved());
			assertTrue(raw.unresolved().stream().anyMatch(u -> u.contains("setChanged")), raw.unresolved().toString());

			MixinRetarget.Plan plan = MixinRetarget.plan(MixinFit.parse(mixin), resolver);
			assertEquals(1, plan.rewrites().size(), entry + ": " + plan.describe());
			assertEquals(SET_ITEM_STUB, plan.rewrites().get(0).from());
			assertEquals(SET_ITEM_DELEGATE, plan.rewrites().get(0).to());
			MixinFit.Result after = MixinFit.evaluate(MixinRetarget.rewritten(mixin, plan), resolver);
			assertEquals(MixinFit.Verdict.FIT, after.verdict(), entry + " after: " + after.unresolved());
		}
	}

	/** R2 over the real fabric-block-api-v1 mixins: the isAir→isEmpty swap the census pins. */
	@Test
	void fabricBlockApisTwoAirCheckRedirectsGoPartialToFit() throws Exception {
		Function<String, byte[]> resolver = mergedResolver();
		for (String entry : new String[] { "net/fabricmc/fabric/mixin/block/LevelChunkSectionMixin.class",
				"net/fabricmc/fabric/mixin/block/ChunkSectionBlockStateCounterMixin.class" }) {
			byte[] mixin = nested("fabric-block-api-v1", entry);
			MixinFit.Result raw = MixinFit.evaluate(mixin, resolver);
			assertEquals(MixinFit.Verdict.PARTIAL, raw.verdict(), entry + " premise: " + raw.unresolved());
			MixinRetarget.Plan plan = MixinRetarget.plan(MixinFit.parse(mixin), resolver);
			assertEquals(1, plan.rewrites().size(), entry + ": " + plan.describe());
			assertEquals(MixinRetarget.Element.AT_TARGET, plan.rewrites().get(0).element());
			assertEquals("Lnet/minecraft/world/level/block/state/BlockState;isEmpty()Z", plan.rewrites().get(0).to());
			MixinFit.Result after = MixinFit.evaluate(MixinRetarget.rewritten(mixin, plan), resolver);
			assertEquals(MixinFit.Verdict.FIT, after.verdict(), entry + " after: " + after.unresolved());
		}
	}

	/** Honest negative: the @Local sugars name the stub's parameters, which the delegate does not have. */
	@Test
	void fuelValuesMixinIsLeftAloneBecauseItsLocalsLiveOnlyInTheStub() throws Exception {
		Function<String, byte[]> resolver = mergedResolver();
		byte[] mixin = nested("fabric-content-registries-v0", FUEL_VALUES_MIXIN);
		assertEquals(MixinFit.Verdict.PARTIAL, MixinFit.evaluate(mixin, resolver).verdict(), "premise");
		MixinRetarget.Plan plan = MixinRetarget.plan(MixinFit.parse(mixin), resolver);
		assertTrue(plan.isEmpty(), "moving it would make MixinExtras fail the @Local capture at apply time: " + plan.describe());
	}

	// ---------------------------------------------------------------------------------------------------------------

	private static Function<String, byte[]> mergedResolver() {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		return name -> {
			try {
				return readFromJar(MERGED_BASE, name);
			} catch (Exception e) {
				return null;
			}
		};
	}

	private static byte[] nested(String module, String entry) throws Exception {
		Path fabricApi = fabricApiJar();
		assumeTrue(fabricApi != null, "fabric-api jar absent from run/client-kernel/mods");
		byte[] bytes = readFromNestedJar(fabricApi, module, entry);
		assumeTrue(bytes != null, entry + " absent from the nested " + module);
		return bytes;
	}

	private static Path fabricApiJar() throws Exception {
		if (!Files.isDirectory(CLIENT_MODS)) return null;
		try (var files = Files.list(CLIENT_MODS)) {
			return files.filter(p -> p.getFileName().toString().startsWith("fabric-api-")).findFirst().orElse(null);
		}
	}

	private static byte[] readFromJar(Path jar, String entry) throws Exception {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry found = zip.getEntry(entry);
			if (found == null) return null;
			try (InputStream in = zip.getInputStream(found)) {
				return in.readAllBytes();
			}
		}
	}

	private static byte[] readFromNestedJar(Path outer, String modulePrefix, String entry) throws Exception {
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
