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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;

/**
 * Covers the mods-button redirect: the pause menu opened NeoForge's mod list, which is every mod NEOFORGE loaded
 * and, on a real sixteen-jar Forbric pack, three of them.
 */
class ModsButtonRedirectorTest {
	private static final Path MERGED_BASE =
			Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run", "merged-base",
					"patched-mc-merged-26.2.jar").normalize();

	private static final String PAUSE = "net/minecraft/client/gui/screens/PauseScreen";
	private static final String TITLE = "net/minecraft/client/gui/screens/TitleScreen";
	private static final String NEO = ForeignType.MOD_LIST_SCREEN.internal(Ecosystem.NEOFORGE);
	private static final String FORGE = ForeignType.MOD_LIST_SCREEN.internal(Ecosystem.FORGE);

	@Test
	void theStagedPauseScreenStillBuildsAFamilysOwnModList() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		List<String> built = constructed(parse(readClass(PAUSE + ".class")));
		assertTrue(built.contains(NEO), "the base must still construct NeoForge's ModListScreen — if it stopped, "
				+ "the button moved and this redirect needs re-deriving");
	}

	/**
	 * BOTH families' construction sites are re-pointed, not only the one the button happens to be bound to.
	 *
	 * <p>The merged base carries a lambda for each, and which one the button calls is a byte-merge outcome rather
	 * than a decision. Redirecting only the winner would make the repair conditional on a merge detail that has
	 * changed before, and the symptom would be a mods button that silently lists one family again.
	 */
	@Test
	void bothFamiliesModListsAreRePointedAtTheUnifiedOne() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		byte[] in = readClass(PAUSE + ".class");
		byte[] out = transform(PAUSE, in);
		assertTrue(out != in, "the staged base must still need the redirect");

		ClassNode node = parse(out);
		List<String> built = constructed(node);
		assertTrue(built.contains(ModsButtonRedirector.KERNEL_SCREEN), "the kernel's screen must be constructed");
		assertTrue(!built.contains(NEO) && !built.contains(FORGE),
				"no family's own mod list may still be constructed here, got " + built);
	}

	/** The constructor call has to move with the NEW, or the class does not link. */
	@Test
	void theConstructorCallMovesWithTheAllocation() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		ClassNode node = parse(transform(PAUSE, readClass(PAUSE + ".class")));
		for (MethodNode method : node.methods) {
			if (method.instructions == null) continue;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
						&& "<init>".equals(call.name)) {
					assertTrue(!NEO.equals(call.owner) && !FORGE.equals(call.owner),
							"a family's ModListScreen constructor is still called in " + method.name);
				}
			}
			new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
		}
	}

	@Test
	void theTitleScreensButtonIsRedirectedToo() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		byte[] in = readClass(TITLE + ".class");
		byte[] out = transform(TITLE, in);
		assertTrue(out != in, "the title screen carries a mods button of its own");
		List<String> built = constructed(parse(out));
		assertTrue(built.contains(ModsButtonRedirector.KERNEL_SCREEN));
		assertTrue(!built.contains(NEO) && !built.contains(FORGE), built.toString());
	}

	/**
	 * The label moves too, because the redirect alone is invisible.
	 *
	 * <p>Mod Menu inserts its own small "Mods" icon button next to the Forge family's; the two are the same size
	 * and the same word. A working redirect on a button a player cannot pick out reads as "nothing happened" —
	 * which is how it was reported before this existed.
	 */
	@Test
	void theButtonSaysWhoseListItOpens() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		ClassNode before = parse(readClass(PAUSE + ".class"));
		assertTrue(constants(before).contains(ModsButtonRedirector.FML_MODS_KEY),
				"the base must still label the button with the Forge families' key");

		ClassNode after = parse(transform(PAUSE, readClass(PAUSE + ".class")));
		assertTrue(constants(after).contains(ModsButtonRedirector.FORBRIC_LABEL), "the new label must be there");
		assertTrue(!constants(after).contains(ModsButtonRedirector.FML_MODS_KEY),
				"and the old key must be gone, or both buttons still say the same word");
	}

	/** A literal, not a translation key: the language is loaded long after this class, and a missing key renders raw. */
	@Test
	void theLabelIsBuiltAsALiteralAndNotAKey() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		ClassNode node = parse(transform(PAUSE, readClass(PAUSE + ".class")));
		boolean sawLiteral = false;
		for (MethodNode method : node.methods) {
			if (method.instructions == null) continue;
			AbstractInsnNode prev = null;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call && "net/minecraft/network/chat/Component".equals(call.owner)
						&& prev instanceof org.objectweb.asm.tree.LdcInsnNode ldc
						&& ModsButtonRedirector.FORBRIC_LABEL.equals(ldc.cst)) {
					assertEquals("literal", call.name, "a key would render as the key itself");
					sawLiteral = true;
				}
				if (insn.getOpcode() >= 0) prev = insn;
			}
		}
		assertTrue(sawLiteral, "the label must still be built through Component");
	}

	@Test
	void aSecondPassLeavesTheRedirectedClassAlone() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		byte[] once = transform(PAUSE, readClass(PAUSE + ".class"));
		assertSame(once, transform(PAUSE, once), "nothing left to re-point means nothing to rewrite");
	}

	/** Only the two screens that carry a mods button are touched; everything else is handed back unchanged. */
	@Test
	void anyOtherClassIsHandedBackUntouched() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		byte[] unrelated = readClass("net/minecraft/client/gui/screens/ChatScreen.class");
		assertSame(unrelated, transform("net/minecraft/client/gui/screens/ChatScreen", unrelated));
	}

	@Test
	void theTwoFamiliesAreNamedThroughForeignTypeAndDiffer() {
		assertEquals("net/neoforged/neoforge/client/gui/ModListScreen", NEO);
		assertEquals("net/minecraftforge/client/gui/ModListScreen", FORGE);
	}

	// --- helpers ---------------------------------------------------------------------------------------------

	private static List<String> constants(ClassNode node) {
		List<String> out = new ArrayList<>();
		for (MethodNode method : node.methods) {
			if (method.instructions == null) continue;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof org.objectweb.asm.tree.LdcInsnNode ldc && ldc.cst instanceof String s) {
					out.add(s);
				}
			}
		}
		return out;
	}

	private static List<String> constructed(ClassNode node) {
		List<String> out = new ArrayList<>();
		for (MethodNode method : node.methods) {
			if (method.instructions == null) continue;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
						&& type.desc.endsWith("ModListScreen")) {
					out.add(type.desc);
				}
			}
		}
		return out;
	}

	private static byte[] transform(String internal, byte[] bytes) {
		return new ModsButtonRedirector().transform(internal.replace('/', '.'), bytes, null);
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] readClass(String entry) throws Exception {
		try (ZipFile zip = new ZipFile(MERGED_BASE.toFile())) {
			ZipEntry e = zip.getEntry(entry);
			assertTrue(e != null, entry + " must be in the staged merged base");
			try (InputStream in = zip.getInputStream(e)) {
				return in.readAllBytes();
			}
		}
	}
}
