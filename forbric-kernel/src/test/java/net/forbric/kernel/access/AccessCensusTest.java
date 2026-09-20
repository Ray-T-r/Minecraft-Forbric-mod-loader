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
				"public com.example.Target secret\n"		  // exists
				+ "public com.example.Target nope\n"		  // missing field: stale
				+ "public com.example.Target gone()V\n"	   // missing method: stale
				+ "public com.example.Target hidden(I)V\n"	// hidden exists as ()V: re-typed
				+ "public com.example.Target *\n"), "x.jar"); // wildcard: always matches
		new AccessTransformer(directives).transform("com.example.Target", sampleClass(), CTX);
		List<AccessCensus.Unmatched> entries = AccessCensus.entries();
		assertEquals(3, entries.size(), entries.toString());
		for (AccessCensus.Unmatched u : entries) {
			assertEquals("AT", u.kind());
			assertEquals("x.jar", u.source());
		}
		assertTrue(entries.get(0).directive().contains("nope") && !entries.get(0).retyped(), entries.toString());
		assertTrue(entries.get(1).directive().contains("gone()V") && !entries.get(1).retyped(), entries.toString());
		assertTrue(entries.get(2).directive().contains("hidden(I)V") && !entries.get(2).retyped() && entries.get(2).namePresent(),
				"an AT method present under another descriptor is reported but not judged: " + entries);
	}

	@Test
	void anAccessWidenerEntryNamingAMissingMemberIsCountedWithItsJar() throws Exception {
		String widener = "accessWidener\tv2\tintermediary\n"
				+ "accessible\tfield\tcom/example/Target\tsecret\tI\n"
				+ "accessible\tfield\tcom/example/Target\tsecret\tJ\n"	  // an int here: vanilla's own drift, not ours
				+ "accessible\tfield\tcom/example/Target\tlazy\tLjava/util/function/Supplier;\n"  // an ecosystem re-typed it
				+ "accessible\tfield\tcom/example/Target\titem\tLnet/minecraft/world/item/Item;\n"  // vanilla did
				+ "accessible\tfield\tcom/example/Target\tmissing\tI\n"
				+ "accessible\tmethod\tcom/example/Target\thidden\t(I)V\n"  // name present: not judged
				+ "accessible\tmethod\tcom/example/Target\tgone\t()V\n";
		ClassTweakerTransformer tweaker = ClassTweakerTransformer.createFrom(
				List.of(new ClassTweakerTransformer.File("y.jar", widener.getBytes(StandardCharsets.UTF_8))), (n, b) -> { });
		tweaker.transform("com.example.Target", sampleClass(), CTX);
		List<AccessCensus.Unmatched> entries = AccessCensus.entries();
		assertEquals(6, entries.size(), entries.toString());
		for (AccessCensus.Unmatched u : entries) {
			assertEquals("AW", u.kind());
			assertEquals("y.jar", u.source());
		}
		assertTrue(entries.stream().anyMatch(u -> u.directive().contains("missing") && !u.retyped()), entries.toString());
		assertTrue(entries.stream().anyMatch(u -> u.directive().contains("gone") && !u.retyped()), entries.toString());
		// The distinction that matters: a field vanilla itself re-typed between versions is a stale line a native
		// loader ignores the same way, while one an ecosystem re-typed is a cost this instance introduced.
		assertTrue(entries.stream().anyMatch(u -> u.directive().contains("secret J") && !u.retyped()
						&& "I".equals(u.presentAs())),
				"a field this Minecraft simply declares differently is not an ecosystem re-typing: " + entries);
		assertTrue(entries.stream().anyMatch(u -> u.directive().contains("lazy Ljava/util/function/Supplier;")
						&& u.retyped()),
				"a field whose descriptor names a carrier type is: " + entries);
		assertTrue(entries.stream().anyMatch(u -> u.directive().contains("item Lnet/minecraft/world/item/Item;")
						&& !u.retyped() && "Lnet/minecraft/core/Holder;".equals(u.presentAs())),
				"one object type for another, both vanilla, is the game's own drift and marks nobody: " + entries);
		assertTrue(entries.stream().anyMatch(u -> u.directive().contains("hidden (I)V") && !u.retyped() && u.namePresent()),
				"a widener METHOD present under another descriptor is not judged: " + entries);
	}

	@Test
	void reportMarksTheOwningJarAndNotACarrier() throws Exception {
		ModCatalog.publish(List.of(
				new ModCatalog.Entry(Ecosystem.NEOFORGE, "xmod", "X", "1", "", List.of(), "x.jar", "", ""),
				new ModCatalog.Entry(Ecosystem.NEOFORGE, "other", "Other", "1", "", List.of(), "other.jar", "", "")));
		AccessCensus.unmatched("AW", "x.jar", "field com/example/Target nope J", true, true);
		AccessCensus.unmatched("AW", "carrier:forge-runtime.jar", "field com/example/Target alsoNope J", true, true);
		// A re-typing a named kernel repair already satisfies marks nobody: the ACCESS phase runs before the
		// COREMOD one, so the widener looks before the repair has happened.
		for (String satisfied : AccessCensus.allSatisfiedElsewhere().keySet()) {
			AccessCensus.unmatched("AW", "satisfied.jar", satisfied, true, true);
		}
		AccessCensus.unmatched("AT", "other.jar", "public com/example/Target stale", false, false);
		AccessCensus.unmatched("AT", "other.jar", "public com/example/Target overload(I)V", false, true);
		AccessCensus.report();
		assertEquals(1, ModCatalog.failures().size(), "the carrier's own directive marks nobody, a stale one marks "
				+ "nobody, and neither does one the kernel already satisfies");
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

	/**
	 * Every "already satisfied" row claims a repair does the directive's whole job. That claim is what makes the
	 * row safe to suppress a report on, and it is the thing that rots: the repair gets renamed, or narrowed, or
	 * deleted, and the row keeps quietly hiding a real loss. So the member it names has to still be named by a
	 * repair in the transformer that is supposed to do it.
	 */
	@Test
	void everySatisfiedRowNamesAMemberSomeRepairStillHandles() throws Exception {
		String transformer = java.nio.file.Files.readString(java.nio.file.Path.of(
				"src/main/java/net/forbric/kernel/transform/ForbricMergedBaseCompatTransformer.java"));
		List<String> orphaned = new java.util.ArrayList<>();
		for (String directive : AccessCensus.allSatisfiedElsewhere().keySet()) {
			String[] parts = directive.split(" ");
			// "field <owner> <name> <desc>" — the member name is what a repair has to still be about.
			if (parts.length < 4 || !transformer.contains(parts[2])) orphaned.add(directive);
		}
		assertTrue(orphaned.isEmpty(), "these rows suppress an access-widener report on the strength of a repair "
				+ "that no longer mentions the member: " + orphaned);
	}

	private static byte[] sampleClass() {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, OWNER, null, "java/lang/Object", null);
		cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "secret", "I", null, null).visitEnd();
		// A field an ECOSYSTEM re-typed: its descriptor names a class stock Minecraft does not ship, so no
		// version of the game ever declared it that way and the miss is a cost this instance introduced.
		cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "lazy",
				"Lnet/minecraftforge/common/util/ClearableLazy;", null, null).visitEnd();
		// VANILLA's own drift, and the shape that made the old rule wrong: ItemStack.item became a Holder<Item>
		// in the game itself, on every base, so a mod carried forward names the old type and a native loader
		// ignores the line in exactly the same way. Both descriptors are object types, so only the carrier-package
		// test tells this apart from the row above.
		cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "item", "Lnet/minecraft/core/Holder;", null, null)
				.visitEnd();
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "hidden", "()V", null, null);
		mv.visitCode();
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 1);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}
}
