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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Covers the repair of {@code ParticleResources}' split {@code providers} field, against the REAL staged base.
 *
 * <p>A synthetic fixture would be the wrong subject twice over: what can drift is what the other repository's
 * merge emits, and the exact instruction the repair has to land BEFORE is in that emitted {@code <init>}.
 */
class MergedBaseParticleProvidersTest {
	private static final Path MERGED_BASE =
			Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run", "merged-base",
					"patched-mc-merged-26.2.jar").normalize();
	private static final String ENTRY = "net/minecraft/client/particle/ParticleResources.class";
	private static final String ID_KEYED = "Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;";
	private static final String NAME_KEYED = "Ljava/util/Map;";

	@Test
	void theVanillaTypedMapIsGivenAViewBeforeRegisterProvidersRuns() throws Exception {
		ClassNode node = repaired();
		MethodNode init = method(node, "<init>", "()V");

		int put = -1;
		int register = -1;
		AbstractInsnNode[] body = init.instructions.toArray();
		for (int i = 0; i < body.length; i++) {
			if (body[i] instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD
					&& "providers".equals(f.name) && ID_KEYED.equals(f.desc) && put < 0) {
				put = i;
			} else if (body[i] instanceof MethodInsnNode m && "registerProviders".equals(m.name) && register < 0) {
				register = i;
			}
		}

		assertTrue(put >= 0, "<init> must write the vanilla-typed providers field — fabric-api reads it directly, "
				+ "and the merged <init> writes only the NeoForge-typed one");
		assertTrue(register >= 0, "<init> no longer calls registerProviders — the anchor this repair depends on");
		assertTrue(put < register,
				"the write must land BEFORE registerProviders. fabric-api's ParticleResourcesMixin injects at that "
						+ "method's RETURN, so a repair placed at the end of <init> — where the merge tool puts its "
						+ "own synthetic field inits — is still too late and reproduces the crash exactly");
	}

	/**
	 * The one assertion that rejects "just give it an empty map". An empty {@code Int2ObjectOpenHashMap} satisfies
	 * every other check here and in the game: nothing NPEs, the mod registers happily, and the particle never
	 * renders because the registration went into a map nothing reads.
	 */
	@Test
	void theStoredValueIsAViewOfTheLiveMapNotAFreshOne() throws Exception {
		MethodNode init = method(repaired(), "<init>", "()V");

		boolean viewed = false;
		AbstractInsnNode[] body = init.instructions.toArray();
		for (int i = 0; i < body.length; i++) {
			if (!(body[i] instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC
					|| !"net/forbric/kernel/runtime/KernelParticleProviders".equals(call.owner)
					|| !"intKeyedView".equals(call.name)) {
				continue;
			}
			assertTrue(i >= 1 && body[i - 1] instanceof FieldInsnNode src
							&& src.getOpcode() == Opcodes.GETFIELD && "providers".equals(src.name)
							&& NAME_KEYED.equals(src.desc),
					"the view must be built over the LIVE map — reading the field that <init> just wrote");
			assertTrue(body[i + 1] instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST,
					"and cast to the field's own type, since the seam is declared in JDK types");
			viewed = true;
		}
		assertTrue(viewed, "the stored value must come from KernelParticleProviders.intKeyedView");
	}

	/**
	 * MinecraftForge's {@code providersByName} lost its only producer in the merge, and the synthetic replacement
	 * init runs AFTER {@code registerProviders} — so it is empty during the exact window that matters, and stays
	 * empty afterwards.
	 */
	@Test
	void getProviderReadsTheMapThatHasSomethingInIt() throws Exception {
		MethodNode getProvider = method(repaired(), "getProvider",
				"(Lnet/minecraft/core/particles/ParticleType;)Lnet/minecraft/client/particle/ParticleProvider;");
		for (AbstractInsnNode insn : getProvider.instructions) {
			if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETFIELD) {
				assertTrue(!"providersByName".equals(f.name),
						"getProvider still reads providersByName, which nothing ever writes");
			}
		}
	}

	@Test
	void aSecondPassLeavesTheRepairedClassAlone() throws Exception {
		byte[] once = new ForbricMergedBaseCompatTransformer().transform(
				"net.minecraft.client.particle.ParticleResources", original(), null);
		byte[] twice = new ForbricMergedBaseCompatTransformer().transform(
				"net.minecraft.client.particle.ParticleResources", once, null);
		assertSame(once, twice, "the repair must stand down once the field is written — otherwise every reload of "
				+ "the class stacks another view on top of the last");
	}

	@Test
	void anotherClassIsUntouched() throws Exception {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		byte[] other = readClass("net/minecraft/client/particle/ParticleProvider.class");
		assumeTrue(other != null, "ParticleProvider absent from this base");
		assertSame(other, new ForbricMergedBaseCompatTransformer()
				.transform("net.minecraft.client.particle.ParticleProvider", other, null));
	}

	private static ClassNode repaired() throws Exception {
		byte[] out = new ForbricMergedBaseCompatTransformer().transform(
				"net.minecraft.client.particle.ParticleResources", original(), null);
		ClassNode node = new ClassNode();
		new ClassReader(out).accept(node, 0);
		return node;
	}

	private static byte[] original() throws IOException {
		assumeTrue(Files.isRegularFile(MERGED_BASE), "staged merged base absent — skipping real-bytecode check");
		byte[] in = readClass(ENTRY);
		assumeTrue(in != null, "ParticleResources absent from this base");
		ClassNode node = new ClassNode();
		new ClassReader(in).accept(node, 0);
		boolean split = node.fields.stream().anyMatch(f -> "providers".equals(f.name) && ID_KEYED.equals(f.desc))
				&& node.fields.stream().anyMatch(f -> "providers".equals(f.name) && NAME_KEYED.equals(f.desc));
		assumeTrue(split, "this base no longer splits ParticleResources.providers — nothing to repair");
		return in;
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		for (MethodNode m : node.methods) {
			if (m.name.equals(name) && m.desc.equals(desc)) return m;
		}
		assertNotNull(null, "missing " + name + desc);
		throw new AssertionError();
	}

	private static byte[] readClass(String entry) throws IOException {
		try (ZipFile jar = new ZipFile(MERGED_BASE.toFile())) {
			var e = jar.getEntry(entry);
			if (e == null) return null;
			try (InputStream in = jar.getInputStream(e)) {
				return in.readAllBytes();
			}
		}
	}
}
