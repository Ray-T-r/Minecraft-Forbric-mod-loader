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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.api.ModCatalog;
import net.forbric.kernel.transform.TransformContext;

/** Directives that meet no member: counted per kind with the jar they came from, named, and marked by jar. */
@org.junit.jupiter.api.parallel.ResourceLock("ModCatalog")
class AccessCensusTest {
	private static final String OWNER = "com/example/Target";
	private static final TransformContext CTX = new TransformContext(EnvType.CLIENT, false, "intermediary");
	private List<ModCatalog.Entry> previous;

	@BeforeEach
	void fresh() {
		previous = ModCatalog.everything();
		AccessCensus.reset();
	}

	@AfterEach
	void restore() {
		AccessCensus.reset();
		ModCatalog.publish(previous);
	}

	@Test
	void anAtLineNamingAMissingMemberIsCountedWithItsJar() throws Exception {
		List<AtDirective> directives = AccessTransformerParser.parse(new StringReader(
				"public com.example.Target secret\n"          // exists
				+ "public com.example.Target nope\n"          // missing field
				+ "public com.example.Target gone()V\n"       // missing method
				+ "public com.example.Target *\n"), "x.jar"); // wildcard: always matches
		new AccessTransformer(directives).transform("com.example.Target", sampleClass(), CTX);
		List<AccessCensus.Unmatched> entries = AccessCensus.entries();
		assertEquals(2, entries.size(), entries.toString());
		for (AccessCensus.Unmatched u : entries) {
			assertEquals("AT", u.kind());
			assertEquals("x.jar", u.source());
		}
		assertTrue(entries.get(0).directive().contains("nope") && entries.get(1).directive().contains("gone()V"), entries.toString());
	}

	@Test
	void anAccessWidenerEntryNamingAMissingMemberIsCountedWithItsJar() throws Exception {
		String widener = "accessWidener\tv2\tintermediary\n"
				+ "accessible\tfield\tcom/example/Target\tsecret\tI\n"
				+ "accessible\tfield\tcom/example/Target\tmissing\tI\n"
				+ "accessible\tmethod\tcom/example/Target\tgone\t()V\n";
		ClassTweakerTransformer tweaker = ClassTweakerTransformer.createFrom(
				List.of(new ClassTweakerTransformer.File("y.jar", widener.getBytes(StandardCharsets.UTF_8))), (n, b) -> { });
		tweaker.transform("com.example.Target", sampleClass(), CTX);
		List<AccessCensus.Unmatched> entries = AccessCensus.entries();
		assertEquals(2, entries.size(), entries.toString());
		for (AccessCensus.Unmatched u : entries) {
			assertEquals("AW", u.kind());
			assertEquals("y.jar", u.source());
		}
		assertTrue(entries.stream().anyMatch(u -> u.directive().contains("missing")) && entries.stream().anyMatch(u -> u.directive().contains("gone")),
				entries.toString());
	}

	@Test
	void reportMarksTheOwningJarAndNotACarrier() throws Exception {
		ModCatalog.publish(List.of(
				new ModCatalog.Entry(Ecosystem.NEOFORGE, "xmod", "X", "1", "", List.of(), "x.jar", "", ""),
				new ModCatalog.Entry(Ecosystem.NEOFORGE, "other", "Other", "1", "", List.of(), "other.jar", "", "")));
		AccessCensus.unmatched("AT", "x.jar", "public com/example/Target nope");
		AccessCensus.unmatched("AT", "carrier:forge-runtime.jar", "public com/example/Target alsoNope");
		AccessCensus.report();
		assertEquals(1, ModCatalog.failures().size(), "the carrier's own directive marks nobody");
		ModCatalog.Entry xmod = ModCatalog.failures().get(0);
		assertEquals("xmod", xmod.modId());
		assertEquals(ModCatalog.Status.DEGRADED, xmod.status());
		assertTrue(xmod.statusDetail().contains("nope"), xmod.statusDetail());
	}

	@Test
	void aClassEveryDirectiveMatchesCountsNothing() throws Exception {
		List<AtDirective> directives = AccessTransformerParser.parse(new StringReader("public com.example.Target secret\n"), "x.jar");
		new AccessTransformer(directives).transform("com.example.Target", sampleClass(), CTX);
		assertTrue(AccessCensus.entries().isEmpty());
	}

	private static byte[] sampleClass() {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, OWNER, null, "java/lang/Object", null);
		cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "secret", "I", null, null).visitEnd();
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "hidden", "()V", null, null);
		mv.visitCode();
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 1);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}
}
