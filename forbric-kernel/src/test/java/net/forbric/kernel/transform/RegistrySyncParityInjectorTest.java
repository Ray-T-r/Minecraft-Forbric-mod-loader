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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.fabricmc.api.EnvType;

/**
 * Pins {@link RegistrySyncParityInjector} against the REAL staged carrier bytecode.
 *
 * <p>This is the transformer in the tree that rests on the most fragile literals: an <em>anonymous inner class
 * ordinal</em> ({@code NamespacedWrapper$3}), a javac-synthesised capture field name ({@code val$newBindings}), and
 * exact descriptors for members two other ecosystems declare. None of that is checked by the compiler, and until
 * this test existed none of it was checked by anything else either — its two registry siblings
 * ({@code RegistryHookRedirectorTest}, {@code RegistryAliasParityInjectorTest}) both had real-bytecode tests and
 * this one had none.
 *
 * <p>What that cost: the failure mode is not an exception at transform time. A stale ordinal simply means the
 * class never matches, the contract is never added, and — per the injector's own javadoc — the first thing that
 * asks a wrapped registry for its pending tag contents dies with an {@code AbstractMethodError} in the middle of a
 * world load. The carrier has been re-pinned once already (NeoForge .7-beta to .38-beta), so "the ordinal moved"
 * is a live upgrade hazard, not a hypothetical. It should go red here instead.
 */
class RegistrySyncParityInjectorTest {
	private static final Path FORGE_RUNTIME =
			Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run", "forge-runtime", "forge-runtime.jar")
					.normalize();

	private static final String WRAPPER = "net.minecraftforge.registries.NamespacedWrapper";
	private static final String WRAPPER_PENDING_TAGS = WRAPPER + "$3";
	private static final String PENDING_TAGS_INTERFACE = "net/minecraft/core/Registry$PendingTags";

	private final RegistrySyncParityInjector injector = new RegistrySyncParityInjector();

	/**
	 * The ordinal guard. {@code $3} is only correct for as long as javac numbers that anonymous class third; if a
	 * carrier upgrade reorders them, the injector silently targets the wrong class.
	 */
	@Test
	void theThirdAnonymousClassIsStillTheOneImplementingPendingTags() throws Exception {
		ClassNode node = realClass(WRAPPER_PENDING_TAGS);
		assumeTrue(node != null, "staged forge-runtime absent — skipping real-bytecode check");

		assertTrue(node.interfaces.contains(PENDING_TAGS_INTERFACE),
				WRAPPER_PENDING_TAGS + " no longer implements " + PENDING_TAGS_INTERFACE
						+ " — the anonymous-class ordinal in RegistrySyncParityInjector has gone stale, and the "
						+ "pending-tags contract is being added to the wrong class (or to none)");
	}

	/** And the capture field it reads is a javac artifact, so its name is equally unguarded by the compiler. */
	@Test
	void theCaptureFieldTheContractReadsStillExists() throws Exception {
		ClassNode node = realClass(WRAPPER_PENDING_TAGS);
		assumeTrue(node != null, "staged forge-runtime absent");

		FieldNode bindings = null;
		for (FieldNode f : node.fields) {
			if ("val$newBindings".equals(f.name)) bindings = f;
		}
		assertNotNull(bindings, "val$newBindings is gone — contents() would return the wrong thing or not build");
		assertEquals("Lcom/google/common/collect/ImmutableMap;", bindings.desc,
				"the capture field changed type; contents() reads it with a hardcoded descriptor");
	}

	@Test
	void theRealPendingTagsClassGainsAVerifiableContents() throws Exception {
		byte[] in = realBytes(WRAPPER_PENDING_TAGS);
		assumeTrue(in != null, "staged forge-runtime absent");

		byte[] out = injector.transform(WRAPPER_PENDING_TAGS, in, ctx());
		assertTrue(out != in, "the pending-tags contract was not added");

		MethodNode contents = method(parse(out), "contents", "()Ljava/util/Map;");
		assertNotNull(contents, "contents()Ljava/util/Map; missing — NeoForge's condition context calls exactly this");
		new Analyzer<>(new BasicVerifier()).analyze(parse(out).name, contents);
	}

	/** The other half of the same class pair: the wrapper itself has to answer to BOTH ecosystems' remap contracts. */
	@Test
	void theRealWrapperGainsBothEcosystemsRemapContracts() throws Exception {
		byte[] in = realBytes(WRAPPER);
		assumeTrue(in != null, "staged forge-runtime absent");

		ClassNode out = parse(injector.transform(WRAPPER, in, ctx()));

		assertNotNull(method(out, "clear", "(Z)V"), "NeoForge calls clear(Z) on every registry it resyncs");
		assertNotNull(method(out, "registerIdMapping", "(Lnet/minecraft/resources/ResourceKey;I)V"),
				"this is the one that NPE'd on the wrapper's empty inherited byKey and dropped the client");
		assertNotNull(method(out, "remap",
				"(Lit/unimi/dsi/fastutil/objects/Object2IntMap;"
						+ "Lnet/fabricmc/fabric/impl/registry/sync/RemappableRegistry$RemapMode;)V"),
				"fabric-api's half — without it a pure-Fabric server's ids are accepted and silently not applied");

		for (String name : new String[] {"clear", "registerIdMapping", "remap"}) {
			new Analyzer<>(new BasicVerifier()).analyze(out.name, method(out, name, null));
		}
	}

	/** Fail-soft, not fail-hard: a carrier that already declares the member keeps its own. */
	@Test
	void aCarrierThatAlreadyHasContentsIsLeftAlone() {
		byte[] in = pendingTagsWithContents();
		assertSame(in, injector.transform(WRAPPER_PENDING_TAGS, in, ctx()));
	}

	/** And one whose capture field is gone is handed back untouched rather than mis-built. */
	@Test
	void aPendingTagsClassWithoutTheCaptureFieldIsNotRewritten() {
		byte[] in = pendingTagsWithoutBindings();
		assertSame(in, injector.transform(WRAPPER_PENDING_TAGS, in, ctx()),
				"with no val$newBindings there is nothing correct to return — warn and leave it");
	}

	@Test
	void everyOtherClassIsHandedBackUntouched() {
		byte[] in = pendingTagsWithoutBindings();
		assertSame(in, injector.transform("net.example.Unrelated", in, ctx()));
	}

	// --- helpers ------------------------------------------------------------------------------------------------

	private static TransformContext ctx() {
		return new TransformContext(EnvType.CLIENT, false, "intermediary");
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		for (MethodNode m : node.methods) {
			if (m.name.equals(name) && (desc == null || m.desc.equals(desc))) return m;
		}
		return null;
	}

	private static ClassNode realClass(String binaryName) throws Exception {
		byte[] b = realBytes(binaryName);
		return b == null ? null : parse(b);
	}

	private static byte[] realBytes(String binaryName) throws Exception {
		if (!Files.isRegularFile(FORGE_RUNTIME)) return null;
		try (ZipFile zip = new ZipFile(FORGE_RUNTIME.toFile())) {
			ZipEntry e = zip.getEntry(binaryName.replace('.', '/') + ".class");
			if (e == null) return null;
			try (InputStream in = zip.getInputStream(e)) {
				return in.readAllBytes();
			}
		}
	}

	/** A stand-in that already declares {@code contents()}, like a future carrier might. */
	private static byte[] pendingTagsWithContents() {
		ClassWriter cw = base();
		MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC, "contents", "()Ljava/util/Map;", null, null);
		m.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
		m.instructions.add(new InsnNode(Opcodes.ARETURN));
		m.maxStack = 1;
		m.maxLocals = 1;
		m.accept(cw);
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** A stand-in with neither {@code contents()} nor the capture field. */
	private static byte[] pendingTagsWithoutBindings() {
		ClassWriter cw = base();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static ClassWriter base() {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, WRAPPER_PENDING_TAGS.replace('.', '/'), null,
				"java/lang/Object", null);
		return cw;
	}
}
