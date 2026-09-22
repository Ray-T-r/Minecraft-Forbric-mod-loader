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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Covers the recomputer against the REAL artifacts, including the crash it was written for.
 *
 * <p>The subject is a hierarchy that lives in a 35MB jar built by another repository, so a synthetic fixture
 * could only test the transformer's opinion of itself. {@link #theRealModLinksAgainstTheRealMergedBase} defines
 * InventoryProfilesNext's class for real, both ways, and forces linkage — because {@code defineClass} does NOT
 * verify, so a test that merely defines the broken class passes.
 */
class MergedBaseFrameRecomputerTest {
	private static final Path RUN = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", System.getProperty("user.dir") + "/../forbric-loader"), "run")
			.normalize();
	private static final Path MERGED = RUN.resolve("merged-base/patched-mc-merged-26.2.jar");
	private static final Path FORGE_RT = RUN.resolve("forge-runtime/forge-runtime.jar");
	private static final Path NEO_RT = RUN.resolve("neoforge-runtime/neoforge-runtime.jar");
	private static final Path IPN = RUN.resolve("mods/InventoryProfilesNext-forge-26.2-2.3.7.jar");
	private static final String VICTIM = "org/anti_ad/mc/ipnext/item/ItemTypeExtensionsKt";
	private static final String LOST = "net/minecraftforge/common/capabilities/CapabilityProvider";

	private final List<ZipFile> open = new ArrayList<>();

	@AfterEach
	void closeJars() throws IOException {
		for (ZipFile jar : open) jar.close();
		open.clear();
	}

	/**
	 * The crash, reproduced and fixed, with no game launched.
	 *
	 * <p>Linkage is forced with {@code getDeclaredMethods()} on purpose: the broken bytes DEFINE without
	 * complaint and only fail when something first links the class. A test that stopped at defineClass would
	 * pass on the bug.
	 */
	@Test
	void theRealModLinksAgainstTheRealMergedBase() throws Exception {
		byte[] original = victimBytes();
		byte[] recomputed = recomputer().transform(VICTIM.replace('/', '.'), original, null);
		assertFalse(java.util.Arrays.equals(original, recomputed), "the class must actually have been rewritten");

		Class<?> before = gameLoader().put(VICTIM.replace('/', '.'), original);
		Error verify = assertThrows(VerifyError.class, before::getDeclaredMethods,
				"the ORIGINAL bytes must still fail, or this test is not reproducing the defect");
		assertTrue(String.valueOf(verify.getMessage()).contains("branch target"), verify.getMessage());
		assertTrue(String.valueOf(verify.getMessage()).contains("CapabilityProvider"), verify.getMessage());

		Class<?> after = gameLoader().put(VICTIM.replace('/', '.'), recomputed);
		assertTrue(after.getDeclaredMethods().length > 0,
				"the recomputed class must link against the merged hierarchy it will actually run on");
	}

	/** The recomputed frame must say what the STAGED hierarchy says, not what this test hardcodes. */
	@Test
	void theRecomputedFrameNamesWhatTheRealHierarchySays() throws Exception {
		byte[] recomputed = recomputer().transform(VICTIM.replace('/', '.'), victimBytes(), null);

		ClassNode node = new ClassNode();
		new ClassReader(recomputed).accept(node, ClassReader.EXPAND_FRAMES);
		for (MethodNode m : node.methods) {
			if (m.instructions == null) continue;
			for (var insn : m.instructions) {
				if (!(insn instanceof FrameNode frame)) continue;
				for (Object entry : allEntries(frame)) {
					assertFalse(LOST.equals(entry),
							m.name + " still names a superclass the merged base does not have");
				}
			}
		}
		// And the type it now names is the one the STAGED hierarchy gives, derived here rather than hardcoded.
		assertEquals(walkCommonSuper("net/minecraft/client/player/LocalPlayer", "net/minecraft/world/level/Level"),
				"net/neoforged/neoforge/attachment/AttachmentHolder",
				"Entity and Level both took NeoForge's AttachmentHolder — that is what the merge did, and what "
						+ "a recomputed frame over those two branches has to say");
	}

	/**
	 * {@code new ClassWriter(reader, COMPUTE_FRAMES)} is a SILENT no-op — ASM copies each method's bytes
	 * verbatim, original StackMapTable included. It is faster, it looks right, and the class still fails to
	 * verify. Without this test that mutation is invisible.
	 */
	@Test
	void theConstantPoolSharingWriterIsNotUsed() throws Exception {
		byte[] original = victimBytes();
		byte[] recomputed = recomputer().transform(VICTIM.replace('/', '.'), original, null);
		assertFalse(java.util.Arrays.equals(original, recomputed),
				"byte-identical output means the frames were copied, not recomputed");
	}

	@Test
	void aClassNamingNoLostAncestorIsReturnedUnchanged() throws Exception {
		byte[] plain = simpleClass();
		assertSame(plain, recomputer().transform("test.Plain", plain, null));
	}

	/**
	 * ItemStack KEPT {@code CapabilityProvider$ItemStacks} in the merged base, so a frame naming it is valid.
	 * The cheap gate matches it by prefix; the precise check must not.
	 */
	@Test
	void theTypeItemStackKeptIsNotTreatedAsLost() {
		assertFalse(MergedBaseFrameRecomputer.LOST_ANCESTORS
						.contains("net/minecraftforge/common/capabilities/CapabilityProvider$ItemStacks"),
				"ItemStack still extends it — rewriting a class for naming it would be work for nothing, and "
						+ "would widen a frame that was already right");
	}

	/** Named in the constant pool but never in a frame: the gate fires, the precise check must not. */
	@Test
	void aClassNamingItOnlyInThePoolIsReturnedUnchanged() throws Exception {
		byte[] poolOnly = classMentioning(LOST);
		assertSame(poolOnly, recomputer().transform("test.PoolOnly", poolOnly, null));
	}

	/** An unwalkable hierarchy abstains: the original bytes come back, and nothing is guessed. */
	@Test
	void anUnresolvableHierarchyLeavesTheClassAlone() throws Exception {
		byte[] original = victimBytes();
		MergedBaseFrameRecomputer blind = new MergedBaseFrameRecomputer(path -> null);
		assertSame(original, blind.transform(VICTIM.replace('/', '.'), original, null));
	}

	/** Interfaces widen to Object: the verifier accepts any object reference for an interface-typed frame. */
	@Test
	void anInterfaceWidensToObject() throws Exception {
		MergedBaseFrameRecomputer r = recomputer();
		assertEquals("java/lang/Object", r.commonSuperClass("java/util/List", "java/util/ArrayList"));
	}

	@Test
	void aSharedSuperclassIsFound() throws Exception {
		assertEquals("java/lang/Number", recomputer().commonSuperClass("java/lang/Integer", "java/lang/Long"));
	}

	private String walkCommonSuper(String a, String b) throws Exception {
		return recomputer().commonSuperClass(a, b);
	}

	private static List<Object> allEntries(FrameNode frame) {
		List<Object> out = new ArrayList<>();
		if (frame.local != null) out.addAll(frame.local);
		if (frame.stack != null) out.addAll(frame.stack);
		return out;
	}

	private MergedBaseFrameRecomputer recomputer() throws IOException {
		return new MergedBaseFrameRecomputer(resolver());
	}

	/** Reads class bytes out of the staged jars, exactly as the loader's resource lookup would. */
	private Function<String, byte[]> resolver() throws IOException {
		assumeTrue(Files.isRegularFile(MERGED) && Files.isRegularFile(FORGE_RT) && Files.isRegularFile(NEO_RT),
				"staged merged base and carriers absent — skipping the real-hierarchy check");
		List<ZipFile> jars = new ArrayList<>();
		for (Path p : List.of(MERGED, FORGE_RT, NEO_RT)) {
			ZipFile jar = new ZipFile(p.toFile());
			jars.add(jar);
			open.add(jar);
		}
		return path -> {
			for (ZipFile jar : jars) {
				ZipEntry entry = jar.getEntry(path);
				if (entry == null) continue;
				try (InputStream in = jar.getInputStream(entry)) {
					return in.readAllBytes();
				} catch (IOException unreadable) {
					return null;
				}
			}
			// The JDK's own types are not in the staged jars; the verifier needs them and so does the walk.
			try (InputStream in = ClassLoader.getPlatformClassLoader().getResourceAsStream(path)) {
				return in == null ? null : in.readAllBytes();
			} catch (IOException unreadable) {
				return null;
			}
		};
	}

	private byte[] victimBytes() throws IOException {
		assumeTrue(Files.isRegularFile(IPN), "InventoryProfilesNext not staged — skipping the real-jar check");
		try (ZipFile jar = new ZipFile(IPN.toFile())) {
			ZipEntry entry = jar.getEntry(VICTIM + ".class");
			assumeTrue(entry != null, "the class moved in this build of the mod");
			try (InputStream in = jar.getInputStream(entry)) {
				return in.readAllBytes();
			}
		}
	}

	/** A loader over the staged game jars that can also be handed bytes directly. */
	private static final class GameLoader extends java.net.URLClassLoader {
		GameLoader(java.net.URL[] urls) {
			super(urls, ClassLoader.getPlatformClassLoader());
		}

		Class<?> put(String name, byte[] bytes) {
			return defineClass(name, bytes, 0, bytes.length);
		}
	}

	/**
	 * A loader over the staged game jars, so the verifier resolves the same hierarchy the game will.
	 *
	 * <p>Forcing linkage reads every method signature, so the libraries the game supplies at runtime —
	 * DataFixerUpper, fastutil — have to be here too, along with the mod's own jar, or the class fails on a
	 * missing type before the verifier has anything to say. The libraries come from the same Minecraft library
	 * tree the build resolves brigadier from.
	 */
	private GameLoader gameLoader() throws IOException {
		assumeTrue(Files.isRegularFile(MERGED) && Files.isRegularFile(FORGE_RT) && Files.isRegularFile(NEO_RT),
				"staged jars absent");
		List<Path> paths = new ArrayList<>(List.of(MERGED, FORGE_RT, NEO_RT));
		// The mod's own jar and its library: linkage reads the signatures of all 66 methods, most of which name
		// the mod's own types. The victim class itself is defined from bytes, so it wins over the jar's copy.
		paths.add(IPN);
		Path libipn = RUN.resolve("mods/libIPN-forge-26.2-6.8.3.jar");
		if (Files.isRegularFile(libipn)) paths.add(libipn);
		for (String artifact : List.of("com/mojang/datafixerupper", "it/unimi/dsi/fastutil")) {
			Path lib = newestUnder(artifact);
			assumeTrue(lib != null, artifact + " absent from the Minecraft library tree — skipping");
			paths.add(lib);
		}
		java.net.URL[] urls = new java.net.URL[paths.size()];
		for (int i = 0; i < paths.size(); i++) urls[i] = paths.get(i).toUri().toURL();
		return new GameLoader(urls);
	}

	/** The same library tree {@code build.gradle} resolves brigadier and fastutil from. */
	private static Path newestUnder(String artifact) throws IOException {
		String configured = System.getProperty("forbric.mcLibraries");
		String env = System.getenv("MC_DIR");
		Path root = Path.of(configured != null ? configured
				: env != null ? env + "/libraries"
				: System.getProperty("user.home") + "/Library/Application Support/minecraft/libraries")
				.resolve(artifact);
		if (!Files.isDirectory(root)) return null;
		try (var found = Files.walk(root)) {
			return found.filter(f -> f.getFileName().toString().endsWith(".jar")).sorted()
					.reduce((a, b) -> b).orElse(null);
		}
	}


	private static byte[] simpleClass() {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "test/Plain", null, "java/lang/Object", null);
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** A class whose constant pool names {@code type} but whose frames do not. */
	private static byte[] classMentioning(String type) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "test/PoolOnly", null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "m", "()V", null, null);
		mv.visitCode();
		mv.visitLdcInsn(org.objectweb.asm.Type.getObjectType(type));
		mv.visitInsn(Opcodes.POP);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(1, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

}
