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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;

import net.forbric.kernel.mixin.MixinFit;

/** The funnel over the REAL merged {@code RenderPipeline$Builder} and {@code $Snippet}, and its payoff for fabric-rendering-v1. */
class MergedBaseSnippetFunnelTest {
	private static final Path MERGED_BASE = Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run",
			"merged-base", "patched-mc-merged-26.2.jar").normalize();
	private static final Path CLIENT_MODS = Path.of(System.getProperty("user.dir"), "run", "client-kernel", "mods").normalize();
	private static final String RENDER_PIPELINE_BUILDER_MIXIN = "net/fabricmc/fabric/mixin/client/rendering/RenderPipelineBuilderMixin.class";

	@AfterEach
	void reset() {
		System.clearProperty(SnippetConstructorFunnel.PROPERTY);
	}

	@Test
	void theBuilderConstructsThroughTheElevenArgConstructorWithTheStencilScoped() throws Exception {
		SnippetConstructorFunnel funnel = new SnippetConstructorFunnel(rawResolver());
		byte[] original = bytesOf(SnippetConstructorFunnel.BUILDER_INTERNAL);
		byte[] routed = funnel.transform(SnippetConstructorFunnel.BUILDER, original, null);
		assertNotSame(original, routed);
		assertEquals(1, funnel.routedSites());

		MethodNode build = find(parse(routed), SnippetConstructorFunnel.BUILD_SNIPPET);
		List<MethodInsnNode> ctors = new ArrayList<>();
		TypeInsnNode construction = null;
		MethodInsnNode scope = null, clear = null;
		for (AbstractInsnNode insn = build.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof TypeInsnNode t && t.getOpcode() == Opcodes.NEW) construction = t;
			if (insn instanceof MethodInsnNode c && "<init>".equals(c.name) && SnippetConstructorFunnel.SNIPPET_INTERNAL.equals(c.owner)) ctors.add(c);
			if (insn instanceof MethodInsnNode c && SnippetConstructorFunnel.RUNTIME.equals(c.owner) && "scope".equals(c.name)) scope = c;
			if (insn instanceof MethodInsnNode c && SnippetConstructorFunnel.RUNTIME.equals(c.owner) && "clear".equals(c.name)) clear = c;
		}
		assertEquals(1, ctors.size(), "exactly one Snippet construction");
		assertEquals(11, Type.getArgumentTypes(ctors.get(0).desc).length, "…through the vanilla-shaped constructor");
		assertNotNull(scope, "the stencil test is scoped");
		assertTrue(previousReal(scope) instanceof FieldInsnNode f && SnippetConstructorFunnel.STENCIL_FIELD.equals(f.name), "…from the Builder's own field");
		assertTrue(index(build, scope) < index(build, construction), "…before `new Snippet`");
		assertNotNull(clear, "and cleared afterwards");
		assertEquals(index(build, ctors.get(0)) + 1, index(build, clear), "…right after the constructor call");
		// The twelfth argument's two feeding instructions are gone: nothing reads stencilTest right before the call.
		assertTrue(!(previousReal(ctors.get(0)) instanceof FieldInsnNode f2 && SnippetConstructorFunnel.STENCIL_FIELD.equals(f2.name)));
		for (MethodNode m : parse(routed).methods) new Analyzer<>(new BasicVerifier()).analyze(SnippetConstructorFunnel.BUILDER_INTERNAL, m);
	}

	@Test
	void theSnippetStubTakesFromTheScopeAndTheCanonicalConstructorIsUntouched() throws Exception {
		SnippetConstructorFunnel funnel = new SnippetConstructorFunnel(rawResolver());
		byte[] original = bytesOf(SnippetConstructorFunnel.SNIPPET_INTERNAL);
		byte[] routed = funnel.transform(SnippetConstructorFunnel.SNIPPET, original, null);
		assertNotSame(original, routed);

		ClassNode before = parse(original), after = parse(routed);
		SnippetConstructorFunnel.Stub stub = SnippetConstructorFunnel.stubOf(after);
		assertNotNull(stub);
		assertTrue(stub.alreadyRouted(), "the 11-arg constructor now calls KernelSnippets.take");
		assertEquals(11, Type.getArgumentTypes(stub.ctor().desc).length);
		assertEquals(0, count(stub.ctor(), "java/util/Optional", "empty"), "…and no longer Optional.empty");
		MethodNode canonicalBefore = ctorWithArity(before, 12), canonicalAfter = ctorWithArity(after, 12);
		assertEquals(listing(canonicalBefore), listing(canonicalAfter), "the 12-arg constructor is byte-for-byte what it was");
		for (MethodNode m : after.methods) new Analyzer<>(new BasicVerifier()).analyze(SnippetConstructorFunnel.SNIPPET_INTERNAL, m);
	}

	/** The payoff: fabric-rendering-v1's wrap reads PARTIAL on the raw base and FIT on the funnelled one. */
	@Test
	void fabricRenderingsSnippetWrapFitsAfterTheFunnel() throws Exception {
		byte[] mixin = nested("fabric-rendering-v1", RENDER_PIPELINE_BUILDER_MIXIN);
		Function<String, byte[]> raw = rawResolver();
		assertEquals(MixinFit.Verdict.PARTIAL, MixinFit.evaluate(mixin, raw).verdict(), "premise (MixinFitStagedTest pins the wording)");

		SnippetConstructorFunnel funnel = new SnippetConstructorFunnel(raw);
		Map<String, byte[]> funnelled = Map.of(
				SnippetConstructorFunnel.BUILDER_INTERNAL + ".class", funnel.transform(SnippetConstructorFunnel.BUILDER, bytesOf(SnippetConstructorFunnel.BUILDER_INTERNAL), null),
				SnippetConstructorFunnel.SNIPPET_INTERNAL + ".class", funnel.transform(SnippetConstructorFunnel.SNIPPET, bytesOf(SnippetConstructorFunnel.SNIPPET_INTERNAL), null));
		Function<String, byte[]> after = name -> funnelled.containsKey(name) ? funnelled.get(name) : raw.apply(name);
		MixinFit.Result r = MixinFit.evaluate(mixin, after);
		assertEquals(MixinFit.Verdict.FIT, r.verdict(), r.unresolved().toString());
	}

	@Test
	void aSecondPassChangesNothingFurther() throws Exception {
		SnippetConstructorFunnel funnel = new SnippetConstructorFunnel(rawResolver());
		byte[] builder = funnel.transform(SnippetConstructorFunnel.BUILDER, bytesOf(SnippetConstructorFunnel.BUILDER_INTERNAL), null);
		assertSame(builder, funnel.transform(SnippetConstructorFunnel.BUILDER, builder, null));
		byte[] snippet = funnel.transform(SnippetConstructorFunnel.SNIPPET, bytesOf(SnippetConstructorFunnel.SNIPPET_INTERNAL), null);
		assertSame(snippet, funnel.transform(SnippetConstructorFunnel.SNIPPET, snippet, null));
	}

	/** Both-or-nothing: a Snippet the resolver serves WITHOUT the recognised stub leaves the Builder untouched. */
	@Test
	void theBuilderStandsDownWhenTheSnippetStubIsNotRecognised() throws Exception {
		Function<String, byte[]> raw = rawResolver();
		byte[] noStub = bytesOf("com/mojang/blaze3d/pipeline/RenderPipeline");    // a real class, no such constructor
		Function<String, byte[]> drifted = name -> (SnippetConstructorFunnel.SNIPPET_INTERNAL + ".class").equals(name) ? noStub : raw.apply(name);
		byte[] original = bytesOf(SnippetConstructorFunnel.BUILDER_INTERNAL);
		assertSame(original, new SnippetConstructorFunnel(drifted).transform(SnippetConstructorFunnel.BUILDER, original, null));
	}

	@Test
	void switchedOffBothHalvesStandDown() throws Exception {
		System.setProperty(SnippetConstructorFunnel.PROPERTY, "off");
		SnippetConstructorFunnel funnel = new SnippetConstructorFunnel(rawResolver());
		byte[] builder = bytesOf(SnippetConstructorFunnel.BUILDER_INTERNAL);
		byte[] snippet = bytesOf(SnippetConstructorFunnel.SNIPPET_INTERNAL);
		assertSame(builder, funnel.transform(SnippetConstructorFunnel.BUILDER, builder, null));
		assertSame(snippet, funnel.transform(SnippetConstructorFunnel.SNIPPET, snippet, null));
		assertTrue(funnel.anchors().anchors().isEmpty());
	}

	// ---------------------------------------------------------------------------------------------------------------

	private static MethodNode find(ClassNode node, String name) {
		for (MethodNode m : node.methods) if (m.name.equals(name)) return m;
		throw new AssertionError(name);
	}

	private static MethodNode ctorWithArity(ClassNode node, int arity) {
		for (MethodNode m : node.methods) if ("<init>".equals(m.name) && Type.getArgumentTypes(m.desc).length == arity) return m;
		throw new AssertionError("no " + arity + "-arg constructor");
	}

	private static int count(MethodNode m, String owner, String name) {
		int n = 0;
		for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof MethodInsnNode c && owner.equals(c.owner) && name.equals(c.name)) n++;
		}
		return n;
	}

	private static int index(MethodNode m, AbstractInsnNode target) {
		int i = 0;
		for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() < 0) continue;
			if (insn == target) return i;
			i++;
		}
		return -1;
	}

	private static String listing(MethodNode m) {
		Textifier text = new Textifier();
		m.accept(new TraceMethodVisitor(text));
		StringBuilder sb = new StringBuilder();
		for (Object line : text.getText()) sb.append(line);
		return sb.toString();
	}

	private static AbstractInsnNode previousReal(AbstractInsnNode cursor) {
		AbstractInsnNode prev = cursor == null ? null : cursor.getPrevious();
		while (prev != null && prev.getOpcode() < 0) prev = prev.getPrevious();
		return prev;
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static Function<String, byte[]> rawResolver() {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		return name -> {
			try {
				return readFromJar(MERGED_BASE, name);
			} catch (Exception e) {
				return null;
			}
		};
	}

	private static byte[] bytesOf(String internal) throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		byte[] bytes = readFromJar(MERGED_BASE, internal + ".class");
		assertNotNull(bytes, internal);
		return bytes;
	}

	private static byte[] nested(String module, String entry) throws Exception {
		Path fabricApi = null;
		if (Files.isDirectory(CLIENT_MODS)) {
			try (var files = Files.list(CLIENT_MODS)) {
				fabricApi = files.filter(p -> p.getFileName().toString().startsWith("fabric-api-")).findFirst().orElse(null);
			}
		}
		assumeTrue(fabricApi != null, "fabric-api jar absent from run/client-kernel/mods");
		byte[] bytes = readFromNestedJar(fabricApi, module, entry);
		assumeTrue(bytes != null, entry + " absent from the nested " + module);
		return bytes;
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
