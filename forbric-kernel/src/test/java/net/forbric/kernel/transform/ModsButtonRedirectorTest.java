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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
			Path.of(System.getenv().getOrDefault("FORBRIC_OLD", System.getProperty("user.dir") + "/../forbric-loader"), "run", "merged-base",
					"patched-mc-merged-26.2.jar").normalize();

	/**
	 * The Forge-family button does not live in one fixed class, and pinning it to one is the mistake the
	 * transformer's own javadoc is about. It was {@code PauseScreen} on NeoForge 26.2.0.38-beta; on 26.2.0.88 the
	 * NeoForge half moved out to {@code neoforge.client.gui.widget.ModsButton}, which is not in the merged base at
	 * all — it is in the runtime jar. So these tests FIND the carriers the same way the transformer does, by
	 * marker, across both staged jars, and then assert against whatever they turn out to be.
	 */
	private static final Path NEO_RUNTIME =
			Path.of(System.getenv().getOrDefault("FORBRIC_OLD", System.getProperty("user.dir") + "/../forbric-loader"), "run", "neoforge-runtime",
					"neoforge-runtime.jar").normalize();

	private static final String PAUSE = "net/minecraft/client/gui/screens/PauseScreen";
	private static final String NEO = ForeignType.MOD_LIST_SCREEN.internal(Ecosystem.NEOFORGE);
	private static final String FORGE = ForeignType.MOD_LIST_SCREEN.internal(Ecosystem.FORGE);

	@Test
	void theStagedGameStillOpensAFamilysOwnModListSomewhere() throws Exception {
		Map<String, byte[]> carriers = carriers();
		assumeTrue(!carriers.isEmpty(), "staged jars absent");

		Map<String, List<String>> opened = new TreeMap<>();
		for (Map.Entry<String, byte[]> e : carriers.entrySet()) {
			List<String> families = opensAFamilysList(parse(e.getValue()));
			if (!families.isEmpty()) opened.put(e.getKey(), families);
		}
		assertTrue(opened.values().stream().flatMap(List::stream).anyMatch(NEO::equals),
				"something in the staged jars must still open NeoForge's own ModListScreen — if nothing does, the "
						+ "button moved again and this redirect needs re-deriving. Carriers found: " + opened);
		// MinecraftForge's own site is NOT required. On the merged base NeoForge's createPauseMenu is the body
		// that wins, so the pause menu carries NeoForge's button and MinecraftForge's construction site survives
		// only as dead code — and once the builder started putting captured lambdas back on their capturer's
		// side, that dead body stopped existing at all. One live Forge-family button, re-pointed, is the whole
		// claim; requiring two was describing a merge artefact.
	}

	/**
	 * BOTH families' construction sites are re-pointed, not only the one the button happens to be bound to.
	 *
	 * <p>The merged base carries a lambda for each, and which one the button calls is a byte-merge outcome rather
	 * than a decision. Redirecting only the winner would make the repair conditional on a merge detail that has
	 * changed before, and the symptom would be a mods button that silently lists one family again.
	 */
	@Test
	void everyCarrierIsRePointedAtTheUnifiedOne() throws Exception {
		Map<String, byte[]> carriers = carriers();
		assumeTrue(!carriers.isEmpty(), "staged jars absent");

		int repointed = 0;
		for (Map.Entry<String, byte[]> e : carriers.entrySet()) {
			if (opensAFamilysList(parse(e.getValue())).isEmpty()) continue;
			byte[] out = transform(e.getKey(), e.getValue());
			assertTrue(out != e.getValue(), e.getKey() + " opens a family's list and must still need the redirect");
			List<String> left = opensAFamilysList(parse(out));
			assertTrue(left.isEmpty(), "no family's own mod list may still be opened from " + e.getKey()
					+ ", got " + left);
			assertTrue(opensTheKernelsList(parse(out)), e.getKey() + " must open the kernel's screen instead");
			repointed++;
		}
		assertTrue(repointed >= 1, "at least one carrier opens a family's own list and must be re-pointed, got "
				+ repointed);
	}

	/** The constructor call has to move with the NEW, or the class does not link. */
	@Test
	void theConstructorCallMovesWithTheAllocation() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		ClassNode node = parse(transform(PAUSE, readClass(MERGED_BASE, PAUSE + ".class")));
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

	/**
	 * The label moves too, because the redirect alone is invisible.
	 *
	 * <p>Mod Menu inserts its own small "Mods" icon button next to the Forge family's; the two are the same size
	 * and the same word. A working redirect on a button a player cannot pick out reads as "nothing happened" —
	 * which is how it was reported before this existed.
	 */
	@Test
	void theButtonSaysWhoseListItOpens() throws Exception {
		Map.Entry<String, byte[]> carrier = carrierCarrying(ModsButtonRedirector.FML_MODS_KEY);
		assumeTrue(carrier != null, "staged jars absent");

		ClassNode after = parse(transform(carrier.getKey(), carrier.getValue()));
		assertTrue(constants(after).contains(ModsButtonRedirector.FORBRIC_LABEL),
				"the new label must be there in " + carrier.getKey());
		assertTrue(!constants(after).contains(ModsButtonRedirector.FML_MODS_KEY),
				"and the old key must be gone, or both buttons still say the same word");
	}

	/** A literal, not a translation key: the language is loaded long after this class, and a missing key renders raw. */
	@Test
	void theLabelIsBuiltAsALiteralAndNotAKey() throws Exception {
		Map.Entry<String, byte[]> carrier = carrierCarrying(ModsButtonRedirector.FML_MODS_KEY);
		assumeTrue(carrier != null, "staged jars absent");
		ClassNode node = parse(transform(carrier.getKey(), carrier.getValue()));
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

	/**
	 * The icon moves with the label.
	 *
	 * <p>A button wearing NeoForge's logo while opening a list of every ecosystem's mods is a picture that is
	 * wrong about what the button does, and the picture is the first thing a player reads.
	 */
	@Test
	void theButtonWearsTheKernelsOwnIcon() throws Exception {
		Map.Entry<String, byte[]> carrier = carrierCarrying(ModsButtonRedirector.FML_SPRITE_PATH);
		assumeTrue(carrier != null, "staged jars absent");

		List<String> after = constants(parse(transform(carrier.getKey(), carrier.getValue())));
		assertTrue(after.contains(ModsButtonRedirector.FORBRIC_SPRITE_NAMESPACE)
				&& after.contains(ModsButtonRedirector.FORBRIC_SPRITE_PATH), "ours must be there");
		assertTrue(!after.contains(ModsButtonRedirector.FML_SPRITE_PATH),
				"and theirs gone — a half-rewrite names a texture nobody ships, which renders as magenta");
	}

	/**
	 * Both halves of the identifier move, or neither does.
	 *
	 * <p>{@code Identifier.fromNamespaceAndPath} takes two adjacent constants. Swapping one leaves
	 * {@code forbric:icon/neo_logo} or {@code neoforge:icon/forbric_logo}, and a GUI sprite that resolves to
	 * nothing is a magenta square rather than an error — so nothing downstream would report it.
	 */
	@Test
	void theNamespaceAndThePathMoveTogether() throws Exception {
		Map.Entry<String, byte[]> carrier = carrierCarrying(ModsButtonRedirector.FML_SPRITE_PATH);
		assumeTrue(carrier != null, "staged jars absent");
		ClassNode node = parse(transform(carrier.getKey(), carrier.getValue()));
		for (MethodNode method : node.methods) {
			if (method.instructions == null) continue;
			AbstractInsnNode prev = null;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof org.objectweb.asm.tree.LdcInsnNode ldc
						&& ModsButtonRedirector.FORBRIC_SPRITE_PATH.equals(ldc.cst)) {
					assertTrue(prev instanceof org.objectweb.asm.tree.LdcInsnNode ns
									&& ModsButtonRedirector.FORBRIC_SPRITE_NAMESPACE.equals(ns.cst),
							"our sprite path must follow our namespace, not the one it replaced");
				}
				if (insn.getOpcode() >= 0) prev = insn;
			}
		}
	}

	/** The texture the rewritten identifier names has to be a file the kernel actually ships. */
	@Test
	void theIconIsShippedAtThePathTheIdentifierResolvesTo() {
		Path icon = Path.of(System.getProperty("user.dir"), "src", "runtime", "resources", "assets",
				ModsButtonRedirector.FORBRIC_SPRITE_NAMESPACE, "textures", "gui", "sprites",
				ModsButtonRedirector.FORBRIC_SPRITE_PATH + ".png");
		assertTrue(Files.isRegularFile(icon), "a GUI sprite resolves <ns>:<path> to assets/<ns>/textures/gui/"
				+ "sprites/<path>.png, and a missing one renders as magenta rather than failing: " + icon);
	}

	@Test
	void aSecondPassLeavesTheRedirectedClassAlone() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		byte[] once = transform(PAUSE, readClass(MERGED_BASE, PAUSE + ".class"));
		assertSame(once, transform(PAUSE, once), "nothing left to re-point means nothing to rewrite");
	}

	/** Only the two screens that carry a mods button are touched; everything else is handed back unchanged. */
	@Test
	void anyOtherClassIsHandedBackUntouched() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		byte[] unrelated = readClass(MERGED_BASE, "net/minecraft/client/gui/screens/ChatScreen.class");
		assertSame(unrelated, transform("net/minecraft/client/gui/screens/ChatScreen", unrelated));
	}

	@Test
	void theTwoFamiliesAreNamedThroughForeignTypeAndDiffer() {
		assertEquals("net/neoforged/neoforge/client/gui/modlist/ModListScreen", NEO);
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

	/**
	 * Which families' own mod list this class opens, by either shape: {@code new ModListScreen(screen)} or the
	 * static {@code ModListScreen.create(screen)} NeoForge moved to at 26.2.0.88.
	 */
	private static List<String> opensAFamilysList(ClassNode node) {
		List<String> out = new ArrayList<>();
		for (MethodNode method : node.methods) {
			if (method.instructions == null) continue;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
						&& (NEO.equals(type.desc) || FORGE.equals(type.desc))) {
					out.add(type.desc);
				} else if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
						&& (NEO.equals(call.owner) || FORGE.equals(call.owner))
						&& call.desc.startsWith("(Lnet/minecraft/client/gui/screens/Screen;)")) {
					out.add(call.owner);
				}
			}
		}
		return out;
	}

	private static boolean opensTheKernelsList(ClassNode node) {
		for (MethodNode method : node.methods) {
			if (method.instructions == null) continue;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
						&& ModsButtonRedirector.KERNEL_SCREEN.equals(type.desc)) return true;
				if (insn instanceof MethodInsnNode call
						&& ModsButtonRedirector.KERNEL_SCREEN.equals(call.owner)) return true;
			}
		}
		return false;
	}

	/** The first staged carrier whose constants contain {@code marker}, or null when nothing is staged. */
	private static Map.Entry<String, byte[]> carrierCarrying(String marker) throws Exception {
		for (Map.Entry<String, byte[]> e : carriers().entrySet()) {
			if (constants(parse(e.getValue())).contains(marker)) return e;
		}
		return null;
	}

	/**
	 * Every class in the staged jars that mentions a mods-button marker, excluding the screens being replaced.
	 * Scanned once: the merged base alone is tens of thousands of entries.
	 */
	private static Map<String, byte[]> carriers() throws Exception {
		if (CARRIERS != null) return CARRIERS;
		Map<String, byte[]> found = new TreeMap<>();
		for (Path jar : List.of(MERGED_BASE, NEO_RUNTIME)) {
			if (!Files.isRegularFile(jar)) continue;
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				for (ZipEntry entry : zip.stream().toList()) {
					if (!entry.getName().endsWith(".class")) continue;
					String internal = entry.getName().substring(0, entry.getName().length() - 6);
					if (NEO.equals(internal) || FORGE.equals(internal)) continue;
					byte[] bytes;
					try (InputStream in = zip.getInputStream(entry)) {
						bytes = in.readAllBytes();
					}
					String raw = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
					if (raw.contains("ModListScreen") || raw.contains(ModsButtonRedirector.FML_MODS_KEY)
							|| raw.contains(ModsButtonRedirector.FML_SPRITE_PATH)) {
						found.putIfAbsent(internal, bytes);
					}
				}
			}
		}
		CARRIERS = found;
		return found;
	}

	private static Map<String, byte[]> CARRIERS;

	private static byte[] transform(String internal, byte[] bytes) {
		return new ModsButtonRedirector().transform(internal.replace('/', '.'), bytes, null);
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] readClass(Path jar, String entry) throws Exception {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry e = zip.getEntry(entry);
			assertTrue(e != null, entry + " must be in " + jar.getFileName());
			try (InputStream in = zip.getInputStream(e)) {
				return in.readAllBytes();
			}
		}
	}

	/**
	 * The reject path, pinned directly because it is invisible from behaviour: a class with no marker and a class
	 * that was parsed and found to need nothing both come back byte-identical. Only the COST differs, and this is
	 * the only place that difference is assertable.
	 */
	@org.junit.jupiter.api.Test
	void aClassMentioningNoneOfTheMarkersIsRejectedFromItsBytes() {
		org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/Unrelated", null, "java/lang/Object", null);
		cw.visitEnd();

		assertFalse(ModsButtonRedirector.carriesAMarker(cw.toByteArray()));
		assertFalse(ModsButtonRedirector.carriesAMarker(new byte[0]));
		assertFalse(ModsButtonRedirector.carriesAMarker(null));
	}

	/**
	 * A mod built against a NeoForge older than 26.2.0.88 still constructs the name NeoForge has since moved, and
	 * on this instance that class does not exist. titlescreenfixer's mixin puts exactly this constructor inside
	 * {@code TitleScreen}, so the client died in {@code Minecraft.<init>} with NoClassDefFoundError before drawing
	 * anything — a whole dead client for one renamed class. Re-pointing it costs nothing: every construction of it
	 * was going to open a mods list, and the kernel's is the one this instance wants.
	 */
	@org.junit.jupiter.api.Test
	void theNameNeoForgeMovedIsRePointedToo() {
		String moved = "net/neoforged/neoforge/client/gui/ModListScreen";
		org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/OldMod", null, "java/lang/Object", null);
		org.objectweb.asm.MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "open",
				"(Lnet/minecraft/client/gui/screens/Screen;)V", null, null);
		mv.visitCode();
		mv.visitTypeInsn(Opcodes.NEW, moved);
		mv.visitInsn(Opcodes.DUP);
		mv.visitVarInsn(Opcodes.ALOAD, 1);
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, moved, "<init>",
				"(Lnet/minecraft/client/gui/screens/Screen;)V", false);
		mv.visitInsn(Opcodes.POP);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(3, 2);
		mv.visitEnd();
		cw.visitEnd();

		ClassNode out = parse(transform("com/example/OldMod", cw.toByteArray()));
		for (MethodNode method : out.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW) {
					assertEquals(ModsButtonRedirector.KERNEL_SCREEN, type.desc,
							"the moved name must be re-pointed at the kernel's list, not left to NoClassDefFoundError");
				}
				if (insn instanceof MethodInsnNode call && "<init>".equals(call.name)) {
					assertEquals(ModsButtonRedirector.KERNEL_SCREEN, call.owner,
							"and so must its constructor, or NEW and INVOKESPECIAL disagree");
				}
			}
		}
	}

	@org.junit.jupiter.api.Test
	void aClassNamingTheScreenIsLetThrough() {
		// A false negative here silently drops the redirect, which is far worse than the scan it saves.
		org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/Pause", null, "java/lang/Object", null);
		cw.visitField(Opcodes.ACC_STATIC, "screen", "Lnet/minecraftforge/client/gui/ModListScreen;", null, null)
				.visitEnd();
		cw.visitEnd();

		assertTrue(ModsButtonRedirector.carriesAMarker(cw.toByteArray()));
	}

	@Test
	void aClassThatCarriesTheLabelButBuildsNoScreenIsNotToldItOpensTheUnifiedList() {
		// The failure this asserts against is one the tree has already had: TitleScreen carried the label while
		// the real construction had moved into NeoForge's own ModsButton widget, and the redirector printed
		// "now opens the unified list" with "0 construction site(s) re-pointed" in the same sentence. A
		// relabelled button that still opens one family's list is worse than an untouched one, because it now
		// tells the player something untrue.
		byte[] labelOnly = labelOnlyClass();

		String log = capture(() -> assertNotNull(transform("forbric/test/LabelOnly", labelOnly)));

		assertFalse(log.contains("now opens the unified list"),
				"nothing re-pointed, so the button does not open the unified list: " + log);
		assertTrue(log.contains("NO construction site"), "and the report has to say which half is missing: " + log);
	}

	@Test
	void aClassThatBuildsTheScreenAndCarriesTheLabelIsToldBothHalvesLanded() {
		// The other direction, so the assertion above cannot pass by the log simply never saying anything. Uses
		// the real carrier rather than a fixture, because that is where both halves genuinely coexist.
		Map<String, byte[]> carriers = CARRIERS;
		assumeTrue(carriers != null && !carriers.isEmpty(), "staged carriers absent");

		Map.Entry<String, byte[]> both = null;
		for (Map.Entry<String, byte[]> candidate : carriers.entrySet()) {
			ClassNode node = parse(candidate.getValue());
			if (!opensAFamilysList(node).isEmpty() && constants(node).contains(ModsButtonRedirector.FML_MODS_KEY)) {
				both = candidate;
				break;
			}
		}
		assumeTrue(both != null, "no staged class carries both the label and a construction site");

		final Map.Entry<String, byte[]> target = both;
		String log = capture(() -> transform(target.getKey(), target.getValue()));
		assertTrue(log.contains("now opens the unified list"),
				"both halves landed, so the full sentence is the true one: " + log);
	}

	private static byte[] labelOnlyClass() {
		org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "forbric/test/LabelOnly", null, "java/lang/Object", null);
		org.objectweb.asm.MethodVisitor mv =
				cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "label", "()Ljava/lang/Object;", null, null);
		mv.visitCode();
		mv.visitLdcInsn(ModsButtonRedirector.FML_MODS_KEY);
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/network/chat/Component", "translatable",
				"(Ljava/lang/String;)Lnet/minecraft/network/chat/MutableComponent;", false);
		mv.visitInsn(Opcodes.ARETURN);
		mv.visitMaxs(1, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static String capture(Runnable body) {
		java.io.PrintStream originalOut = System.out;
		java.io.PrintStream originalErr = System.err;
		java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
		java.io.PrintStream sink = new java.io.PrintStream(buffer, true, java.nio.charset.StandardCharsets.UTF_8);
		System.setOut(sink);
		System.setErr(sink);
		try {
			body.run();
		} finally {
			System.setOut(originalOut);
			System.setErr(originalErr);
		}
		return buffer.toString(java.nio.charset.StandardCharsets.UTF_8);
	}
}
