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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Every transformer in this package has said something about what it must edit.
 *
 * <p>This is the test that keeps the mechanism from rotting. Declaring anchors on the transformers that exist
 * today is a one-off; making transformer number thirty-three declare, months from now, when nobody remembers
 * this exists, is what actually keeps carrier drift visible. A silent {@code return classBytes} is the default
 * behaviour of the SPI, so without a census the next transformer written is silent again.
 *
 * <p>Two answers are accepted, and the difference between them matters: a list of classes it must land on, or a
 * stated reason it has no fixed target. What is rejected is saying nothing, because that is indistinguishable
 * from not having thought about it.
 */
class TransformerAnchorCensusTest {

	/** A transformer that has not been converted. Used only to prove the census below can see one. */
	private static final class Undeclared implements ClassTransformer {
		@Override
		public byte[] transform(String className, byte[] classBytes, TransformContext context) {
			return classBytes;
		}
	}

	/** Names of transformers that declared nothing at all. */
	private static List<String> census(List<ClassTransformer> transformers) {
		List<String> undeclared = new ArrayList<>();
		for (ClassTransformer t : transformers) {
			if (t.anchors().isUndeclared()) undeclared.add(t.getClass().getSimpleName());
		}
		return undeclared;
	}

	/** Names of transformer CLASSES that never override {@code anchors()} at all. */
	private static List<String> neverOverrode(List<Class<?>> types) {
		List<String> missing = new ArrayList<>();
		for (Class<?> type : types) {
			try {
				type.getDeclaredMethod("anchors");
			} catch (NoSuchMethodException notOverridden) {
				missing.add(type.getSimpleName());
			}
		}
		return missing;
	}

	/**
	 * The two transformers that carry many repairs behind one {@code changed} flag declare one claim per repair,
	 * and the compat transformer's claim list is the same list, in the same order, as the repairs its transform
	 * actually runs (pinned through the LDC of each repair's name at its call site).
	 */
	@Test
	void theTwoMultiRepairTransformersDeclareOneClaimPerRepair() throws Exception {
		ForbricMergedBaseCompatTransformer compat = new ForbricMergedBaseCompatTransformer(name -> null);
		List<ClassTransformer.Claim> claims = compat.claims();
		assertEquals(ForbricMergedBaseCompatTransformer.REPAIRS.size(), claims.size());
		List<String> ids = new ArrayList<>();
		for (ClassTransformer.Claim claim : claims) {
			ids.add(claim.id().substring(claim.id().indexOf('#') + 1));
			assertTrue(claim.id().startsWith(compat.name() + "#"), claim.id());
			assertTrue(!claim.anchors().isUndeclared(), claim.id() + " declares nothing");
		}
		assertEquals(ForbricMergedBaseCompatTransformer.REPAIRS, ids, "one claim per repair, in transform order");

		java.nio.file.Path compiled = java.nio.file.Path.of(System.getProperty("user.dir"), "build", "classes", "java", "main",
				"net", "forbric", "kernel", "transform", "ForbricMergedBaseCompatTransformer.class");
		assumeTrue(java.nio.file.Files.isRegularFile(compiled), "transform classes not compiled yet");
		org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
		new org.objectweb.asm.ClassReader(java.nio.file.Files.readAllBytes(compiled)).accept(node, 0);
		List<String> called = new ArrayList<>();
		for (org.objectweb.asm.tree.MethodNode m : node.methods) {
			if (!m.name.equals("transform") || !m.desc.contains("ClaimReporter")) continue;
			for (org.objectweb.asm.tree.AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof org.objectweb.asm.tree.LdcInsnNode ldc && ldc.cst instanceof String name
						&& ForbricMergedBaseCompatTransformer.REPAIRS.contains(name)) called.add(name);
			}
		}
		assertEquals(ForbricMergedBaseCompatTransformer.REPAIRS, called, "the transform runs exactly the claimed repairs, in order");

		assertTrue(new CommonNetworkInteropInjector().claims().size() >= 5, "one claim per network branch");
	}

	@Test
	void everyTransformerInThisPackageOverridesAnchors() throws Exception {
		List<Class<?>> types = allTransformerClasses();
		assumeTrue(!types.isEmpty(), "transform classes not compiled yet");

		// Reflective on the CLASS, not on an instance, so the four transformers whose only constructor takes
		// collaborators are covered too. They were skipped by the instance census below, and a transformer that
		// quietly drops out of the census it is supposed to be in is the exact failure this file is about.
		assertEquals(List.of(), neverOverrode(types),
				"a transformer that never overrides anchors() goes back to failing silently when its anchor "
						+ "moves. Give it AnchorSet.of(...) with what a miss costs, or AnchorSet.scanned(why) if "
						+ "it genuinely has no fixed target");
	}

	@Test
	void theClassCensusCanActuallySeeATransformerThatNeverOverrodeAnchors() {
		assertEquals(List.of("Undeclared"), neverOverrode(List.of(ClientEntrypointHookInjector.class,
				Undeclared.class)));
	}

	@Test
	void noTransformerAnswersWithNothing() throws Exception {
		List<ClassTransformer> found = instantiateAll();
		assumeTrue(!found.isEmpty(), "transform classes not compiled yet");

		assertEquals(List.of(), census(found),
				"overriding anchors() and then returning AnchorSet.undeclared() says nothing either");
	}

	@Test
	void theInstanceCensusCanActuallySeeAnUndeclaredTransformer() {
		// The mutation, as an input rather than as something to remember to try. Without this, the assertion
		// above would go green for a census that always returns an empty list -- which is exactly how an
		// assertion ends up watching nothing at all.
		List<ClassTransformer> withOneBad = new ArrayList<>();
		withOneBad.add(new ClientEntrypointHookInjector());
		withOneBad.add(new Undeclared());

		assertEquals(List.of("Undeclared"), census(withOneBad));
	}

	/** Every concrete {@link ClassTransformer} class compiled into this package. */
	private static List<Class<?>> allTransformerClasses() throws Exception {
		Path compiled = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "main",
				"net", "forbric", "kernel", "transform");
		if (!Files.isDirectory(compiled)) return List.of();

		List<Class<?>> types = new ArrayList<>();
		try (Stream<Path> files = Files.list(compiled)) {
			for (Path file : files.sorted().toList()) {
				String name = file.getFileName().toString();
				if (!name.endsWith(".class") || name.contains("$")) continue;

				Class<?> type = Class.forName("net.forbric.kernel.transform." + name.substring(0, name.length() - 6));
				if (!ClassTransformer.class.isAssignableFrom(type) || type.isInterface()) continue;
				types.add(type);
			}
		}
		return types;
	}

	/** Those of them that can be built with a no-arg constructor, so their ANSWER can be read too. */
	private static List<ClassTransformer> instantiateAll() throws Exception {
		List<ClassTransformer> built = new ArrayList<>();
		for (Class<?> type : allTransformerClasses()) {
			try {
				var ctor = type.getDeclaredConstructor();
				ctor.setAccessible(true);
				built.add((ClassTransformer) ctor.newInstance());
			} catch (NoSuchMethodException needsCollaborators) {
				// Covered by everyTransformerInThisPackageOverridesAnchors, which does not need an instance.
			}
		}
		return built;
	}
}
