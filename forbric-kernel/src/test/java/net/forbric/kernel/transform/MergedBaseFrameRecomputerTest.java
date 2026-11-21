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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.forbric.kernel.ExpectCaught;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * The recomputer's hierarchy walk over the staged game jars, and the cases where it must hand the class back untouched.
 *
 * <p>Until Stage 4 this class also reproduced the crash the recomputer was written for: InventoryProfilesNext's
 * MinecraftForge build, whose frames named {@code net/minecraftforge/common/capabilities/CapabilityProvider}, linked
 * against the merged base. That build is a refused MinecraftForge mod and the genuine base has no such type, so those
 * four cases went with MinecraftForge. The repair itself is generic (no ecosystem, mod or type is named in it): {@link
 * MergedBaseFrameShapeExecutionTest} proves on a synthetic hierarchy that the original bytes fail real linkage, the
 * recomputed ones link and run, and the frames are recomputed rather than copied.
 */
class MergedBaseFrameRecomputerTest {
	private static final Path RUN = TestFixtures.stagedRoot();
	private static final Path MERGED = RUN.resolve(TestFixtures.BASE_JAR);
	private static final Path NEO_RT = RUN.resolve(TestFixtures.NEO_CARRIER_JAR);
	private static final Path IPN = RUN.resolve("mods/InventoryProfilesNext-forge-26.2-2.3.7.jar");
	private static final String VICTIM = "org/anti_ad/mc/ipnext/item/ItemTypeExtensionsKt";
	/** A type no hierarchy here has. */
	private static final String ABSENT = "future/protocol/Gone";

	private final List<ZipFile> open = new ArrayList<>();

	@AfterEach
	void closeJars() throws IOException {
		for (ZipFile jar : open) jar.close();
		open.clear();
	}

	/**
	 * The common superclass a recomputed frame names is read off the staged jars, not hardcoded: NeoForge's Entity and
	 * Level both extend its AttachmentHolder, so a frame joining a player and a level has to say AttachmentHolder.
	 */
	@Test
	void theCommonSuperclassComesFromTheStagedHierarchy() throws Exception {
		assertEquals("net/neoforged/neoforge/attachment/AttachmentHolder",
				recomputer().commonSuperClass("net/minecraft/client/player/LocalPlayer", "net/minecraft/world/level/Level"),
				"the join of the Entity and Level branches on NeoForge's base");
	}

	@Test
	void aClassNamingNoLostAncestorIsReturnedUnchanged() throws Exception {
		byte[] plain = simpleClass();
		assertSame(plain, recomputer().transform("test.Plain", plain, null));
	}

	/** Named in the constant pool but never in a frame: there is nothing to recompute. */
	@Test
	void aClassNamingItOnlyInThePoolIsReturnedUnchanged() throws Exception {
		byte[] poolOnly = classMentioning(ABSENT);
		assertSame(poolOnly, recomputer().transform("test.PoolOnly", poolOnly, null));
	}

	/** Not even the types the frames declare can be read, so nothing is judged: the original bytes come back, and the report stays. */
	// The blind resolver is the point: the recomputer reports that it cannot prove a repair, and abstains.
	@ExpectCaught("cannot prove a frame repair for org.anti_ad.mc.ipnext.item.ItemTypeExtensionsKt")
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

	/**
	 * The game's own text-to-speech library, as a Windows or a Linux launch has it. Its {@code Narrator} interface picks
	 * one of three implementations and returns it as a {@code Narrator}; the macOS one extends a class of an Objective-C
	 * bridge that only the macOS library list carries. Inferring the join of the three has to walk that chain and, on
	 * Windows and Linux, cannot. The frame is declared as the interface and accepts any object, so there is nothing to
	 * repair and nothing to report. A macOS launch has the bridge and never meets the question, so this reads the class
	 * the way the other two do: the library itself, JNA where it is installed, the JDK, and nothing else. The real jar
	 * is the sample; the rule that keeps it quiet names no class ({@link MergedBaseFrameUnreadTypeTest} holds the shapes).
	 */
	@Test
	void theNarratorOfAnotherOperatingSystemIsQuiet() throws Exception {
		Path speech = TestFixtures.newestLibrary("com/mojang/text2speech");
		TestFixtures.require(Fixture.MC_LIBRARIES, speech != null, "Minecraft's text2speech library required");
		List<Path> libraries = new ArrayList<>(List.of(speech));
		for (String optional : List.of("net/java/dev/jna/jna", "net/java/dev/jna/jna-platform")) {
			Path jar = TestFixtures.newestLibrary(optional);
			if (jar != null) libraries.add(jar);
		}
		Function<String, byte[]> resolver = over(libraries);
		assertNull(resolver.apply("ca/weblite/objc/NSObject.class"), "the bridge must not be readable here: that is the other operating systems' class path");
		assertThrows(TypeNotPresentException.class, () -> new MergedBaseFrameRecomputer(resolver)
				.commonSuperClass("com/mojang/text2speech/NarratorMac", "com/mojang/text2speech/NarratorWindows"),
				"the join of two implementations cannot be computed without the bridge");
		byte[] narrator = resolver.apply("com/mojang/text2speech/Narrator.class");
		assertNotNull(narrator, "Narrator in " + speech);
		assertSame(narrator, new MergedBaseFrameRecomputer(resolver).transform("com.mojang.text2speech.Narrator", narrator, null));
	}

	private MergedBaseFrameRecomputer recomputer() throws IOException {
		return new MergedBaseFrameRecomputer(resolver());
	}

	/** Reads class bytes out of the staged jars, exactly as the loader's resource lookup would. */
	private Function<String, byte[]> resolver() throws IOException {
		TestFixtures.requireFiles(Fixture.STAGED,
				"staged base and carriers absent — skipping the real-hierarchy check", MERGED, NEO_RT);
		return over(List.of(MERGED, NEO_RT));
	}

	/** {@code paths}' entries in order, then the JDK's own classes; never the test class path. */
	private Function<String, byte[]> over(List<Path> paths) throws IOException {
		List<ZipFile> jars = new ArrayList<>();
		for (Path p : paths) {
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

	/** Any real class with reference frames serves the abstention case; this one is a jar the staged mods hold. */
	private byte[] victimBytes() {
		TestFixtures.require(Fixture.THIRD_PARTY, Files.isRegularFile(IPN), "InventoryProfilesNext not staged — skipping the real-jar check");
		return TestFixtures.requireEntry(Fixture.THIRD_PARTY, IPN, VICTIM + ".class");
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
