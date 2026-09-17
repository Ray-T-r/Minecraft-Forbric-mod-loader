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

package net.forbric.kernel.access;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The runtime carriers' own access transformers, applied to the REAL merged base.
 *
 * <p>Each Forge family ships a file that widens the game for every mod of that family, and the genuine loader
 * applies it before anything else runs. The kernel fed it only mod jars' files, so the carriers' were never
 * applied — and where the merge kept one family's method body it kept that body's access flags too, so the other
 * family's widening was simply gone.
 *
 * <p>{@code MenuScreens.register} is the case that costs the most: traditional MinecraftForge's file declares it
 * public, the merged base has it private, and it is the one line every MinecraftForge mod with a GUI runs during
 * client setup.
 */
class CarrierAccessTransformerTest {
	private static final Path RUN =
			Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run").normalize();
	private static final Path MERGED_BASE = RUN.resolve("merged-base/patched-mc-merged-26.2.jar");
	private static final Path FORGE_CARRIER = RUN.resolve("forge-runtime/forge-runtime.jar");

	private static final String MENU_SCREENS = "net/minecraft/client/gui/screens/MenuScreens.class";

	@Test
	void theCarrierFeedIsBeltAndBracesNowThatTheMergeWidensIt() throws Exception {
		// This asserted the opposite until the merge tool learned to keep the wider of the two ecosystems'
		// access, which it now does — so the base ships this method public and the carrier feed no longer has to
		// rescue it. The feed stays: it costs nothing, and it is what covers a base built before that fix.
		MethodNode register = registerIn(original());

		assertTrue((register.access & Opcodes.ACC_PUBLIC) != 0,
				"the merged base should widen this at build time now; if it does not, the merge tool's access "
						+ "reconciliation has regressed and only the carrier feed is holding this up");
	}

	@Test
	void theCarriersFileMakesItPublic() throws Exception {
		MethodNode register = registerIn(widened());

		assertTrue((register.access & Opcodes.ACC_PUBLIC) != 0,
				"every MinecraftForge mod with a GUI calls this during client setup and gets an illegal-access "
						+ "error while it is private");
		assertFalse((register.access & Opcodes.ACC_PRIVATE) != 0);
		assertTrue((register.access & Opcodes.ACC_STATIC) != 0, "widening must not touch anything else");
	}

	@Test
	void theCarrierDeclaresEnoughToBeWorthReading() throws Exception {
		// A parse that silently produced nothing would make every other assertion here vacuous.
		assertTrue(carrierDirectives().size() > 100,
				"the carrier's access transformer should carry hundreds of directives, not a handful");
	}

	private static MethodNode registerIn(ClassNode node) {
		for (MethodNode m : node.methods) {
			if ("register".equals(m.name)) return m;
		}
		throw new AssertionError("MenuScreens has no register method in this base");
	}

	private static ClassNode widened() throws Exception {
		byte[] out = new AccessTransformer(carrierDirectives()).transform(
				"net.minecraft.client.gui.screens.MenuScreens", bytes(), null);
		ClassNode node = new ClassNode();
		new ClassReader(out).accept(node, 0);
		return node;
	}

	private static ClassNode original() throws Exception {
		ClassNode node = new ClassNode();
		new ClassReader(bytes()).accept(node, 0);
		return node;
	}

	private static byte[] bytes() throws IOException {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		try (ZipFile jar = new ZipFile(MERGED_BASE.toFile())) {
			ZipEntry entry = jar.getEntry(MENU_SCREENS);
			assumeTrue(entry != null, "MenuScreens absent from this base");
			try (InputStream in = jar.getInputStream(entry)) {
				return in.readAllBytes();
			}
		}
	}

	/** Every {@code META-INF/accesstransformer*.cfg} in the traditional MinecraftForge carrier. */
	private static List<AtDirective> carrierDirectives() throws IOException {
		assumeTrue(Files.isRegularFile(FORGE_CARRIER), "staged MinecraftForge carrier absent");

		List<AtDirective> directives = new ArrayList<>();
		try (ZipFile jar = new ZipFile(FORGE_CARRIER.toFile())) {
			for (Enumeration<? extends ZipEntry> e = jar.entries(); e.hasMoreElements();) {
				ZipEntry entry = e.nextElement();
				String name = entry.getName();
				if (!name.startsWith("META-INF/") || !name.endsWith(".cfg")) continue;
				if (!name.substring("META-INF/".length()).startsWith("accesstransformer")) continue;

				try (InputStreamReader r =
						new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8)) {
					directives.addAll(AccessTransformerParser.parse(r));
				}
			}
		}
		assumeTrue(!directives.isEmpty(), "this carrier ships no access transformer");
		return directives;
	}
}
