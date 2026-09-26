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

package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * fabric-registry-sync's "alias target doesn't exist" warning asks the registry's own key set — so a MinecraftForge
 * wrapper's registered entry counts — and stays quiet for NeoForge's alias-first order; its collision check is untouched.
 */
@ResourceLock("system-properties")
class PostMixinFixupsAliasTargetTest {
	private static final String REGISTRY = "net/minecraft/core/MappedRegistry";
	private static final String WRAPPER = "net/minecraftforge/registries/NamespacedWrapper";
	private static final String DEFERRED = "net/neoforged/neoforge/registries/DeferredRegister";
	private static final String WARNING = "Adding {} as an alias for {}, but the latter doesn't exist in registry {}";
	/** KernelRegistryAliases' switch for NeoForge's alias-first order. */
	private static final String DEFERRED_REGISTER_ORDER = "forbric.aliasPresenceParity.deferredRegister";

	@AfterEach
	void reset() {
		System.clearProperty(PostMixinFixups.ALIAS_PRESENCE_PROPERTY);
		System.clearProperty(DEFERRED_REGISTER_ORDER);
	}

	/** More Nemo's Woodcutter Variants' shape: register on a Forge wrapper, then alias the old id to it. */
	@Test
	void anAliasToAnEntryAForgeWrapperRegisteredDoesNotWarn() throws Exception {
		Fixture before = new Fixture(registryClass());
		before.register(before.wrapper, "new");
		before.addAlias(before.wrapper, "old", "new");
		assertEquals(1, before.warnings(), "the unrepaired check reads byLocation, which the wrapper never fills");

		Fixture after = new Fixture(repaired());
		after.register(after.wrapper, "new");
		after.addAlias(after.wrapper, "old", "new");
		assertEquals(0, after.warnings(), "the wrapper's own names answer, and it registered the target");
		after.addAlias(after.wrapper, "older", "missing");
		assertEquals(1, after.warnings(), "a target that really is missing still warns");
	}

	@Test
	void aPlainRegistryAnswersExactlyAsItsMapDid() throws Exception {
		Fixture f = new Fixture(repaired());
		f.register(f.plain, "new");
		f.addAlias(f.plain, "old", "new");
		assertEquals(0, f.warnings());
		f.addAlias(f.plain, "older", "missing");
		assertEquals(1, f.warnings());
	}

	/** sophisticatedcore's shape: NeoForge's DeferredRegister applies its aliases before it registers the entries. */
	@Test
	void neoForgesAliasFirstOrderDoesNotWarnUnlessSwitchedOff() throws Exception {
		Fixture f = new Fixture(repaired());
		f.addEntries(f.plain, "render_info_tag", "render_data");
		assertEquals(0, f.warnings(), "NeoForge registers the target in the same call and never warns about it");
		f.addAlias(f.plain, "elsewhere", "not_registered");
		assertEquals(1, f.warnings(), "any other caller still hears about a missing target");

		System.setProperty(DEFERRED_REGISTER_ORDER, "off");
		f.addEntries(f.plain, "second_alias", "second_target");
		assertEquals(2, f.warnings());
	}

	/** Making the collision check see the wrapper's names would turn an alias the game accepts today into a crash. */
	@Test
	void theCollisionCheckIsLeftAsMixinWoveIt() throws Exception {
		Fixture f = new Fixture(repaired());
		f.register(f.wrapper, "taken");
		f.addAlias(f.wrapper, "taken", "other");
		f.register(f.plain, "taken");
		InvocationTargetException thrown = assertThrows(InvocationTargetException.class, () -> f.addAlias(f.plain, "taken", "other"));
		assertTrue(thrown.getCause() instanceof IllegalArgumentException);

		ClassNode node = node(repaired());
		MethodNode addAlias = node.methods.stream().filter(m -> m.name.equals("addAlias")).findFirst().orElseThrow();
		List<String> calls = new ArrayList<>();
		for (AbstractInsnNode insn : addAlias.instructions) if (insn instanceof MethodInsnNode call) calls.add(call.name);
		assertEquals(List.of("containsKey", "<init>", "keySet", "aliasTargetPresent", "put"), calls);
	}

	@Test
	void theSwitchLeavesTheMethodAsMixinWoveIt() {
		System.setProperty(PostMixinFixups.ALIAS_PRESENCE_PROPERTY, "off");
		byte[] original = registryClass();
		assertSame(original, PostMixinFixups.apply(REGISTRY.replace('/', '.'), original));
	}

	/**
	 * Every fabric-registry-sync in the compatibility packs has exactly one check the repair touches — the warning's —
	 * so a fabric-api that reshaped the method is noticed here rather than as a silent no-op in a player's log.
	 */
	@Test
	void everyShippedFabricRegistrySyncHasExactlyTheWarningsCheckRepaired() throws Exception {
		List<Path> roots = List.of(Path.of("build/compat-inputs/sweep90/mods"), Path.of("run/client-merged-pack/mods"),
				Path.of("run/client-popular/mods"));
		Map<String, byte[]> mixins = new HashMap<>();
		for (Path root : roots) {
			if (!Files.isDirectory(root)) continue;
			try (var jars = Files.list(root)) {
				for (Path jar : jars.filter(p -> p.toString().endsWith(".jar")).toList()) {
					try (InputStream in = Files.newInputStream(jar)) {
						collect(jar.getFileName().toString(), in, mixins);
					}
				}
			}
		}
		assumeTrue(!mixins.isEmpty(), "no fabric-registry-sync in the compatibility packs on this machine");
		for (Map.Entry<String, byte[]> mixin : mixins.entrySet()) {
			byte[] bytes = mixin.getValue();
			byte[] out = PostMixinFixups.askTheRegistryWhetherAnAliasTargetExists(bytes);
			assertNotSame(bytes, out, mixin.getKey() + ": the warning's check was not found");
			MethodNode addAlias = node(out).methods.stream().filter(m -> m.name.equals("addAlias")).findFirst().orElseThrow();
			int present = 0, containsKey = 0;
			for (AbstractInsnNode insn : addAlias.instructions) {
				if (!(insn instanceof MethodInsnNode call)) continue;
				if (call.name.equals("aliasTargetPresent")) present++;
				if (call.name.equals("containsKey")) containsKey++;
			}
			assertEquals(1, present, mixin.getKey());
			assertEquals(2, containsKey, mixin.getKey() + ": the alias-map and collision checks stay");
		}
	}

	private static void collect(String label, InputStream in, Map<String, byte[]> out) throws IOException {
		ZipInputStream zip = new ZipInputStream(in);
		for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
			String name = entry.getName();
			if (name.endsWith(".jar")) {
				collect(label + "!" + name, new ByteArrayInputStream(zip.readAllBytes()), out);
			} else if (name.equals("net/fabricmc/fabric/mixin/registry/sync/MappedRegistryMixin.class")) {
				out.put(label, zip.readAllBytes());
			}
		}
	}

	private static byte[] repaired() {
		byte[] original = registryClass();
		byte[] out = PostMixinFixups.apply(REGISTRY.replace('/', '.'), original);
		assertNotSame(original, out);
		return out;
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	/** The three classes in a loader of their own, with the registry class under test. */
	private static final class Fixture {
		final ClassLoader loader;
		final Object plain, wrapper;
		final Class<?> registry;

		Fixture(byte[] registryBytes) throws Exception {
			Map<String, byte[]> classes = Map.of(REGISTRY, registryBytes, WRAPPER, wrapperClass(), DEFERRED, deferredClass());
			loader = new ClassLoader(PostMixinFixupsAliasTargetTest.class.getClassLoader()) {
				@Override
				protected Class<?> findClass(String name) throws ClassNotFoundException {
					byte[] bytes = classes.get(name.replace('.', '/'));
					if (bytes == null) throw new ClassNotFoundException(name);
					return defineClass(name, bytes, 0, bytes.length);
				}
			};
			registry = loader.loadClass(REGISTRY.replace('/', '.'));
			plain = registry.getConstructor().newInstance();
			wrapper = loader.loadClass(WRAPPER.replace('/', '.')).getConstructor().newInstance();
		}

		void register(Object on, String id) throws Exception {
			registry.getMethod("register", Object.class).invoke(on, id);
		}

		void addAlias(Object on, String alias, String target) throws Exception {
			registry.getMethod("addAlias", Object.class, Object.class).invoke(on, alias, target);
		}

		void addEntries(Object on, String alias, String target) throws Exception {
			loader.loadClass(DEFERRED.replace('/', '.')).getMethod("addEntries", registry, Object.class, Object.class)
					.invoke(null, on, alias, target);
		}

		int warnings() throws Exception {
			return registry.getField("warnings").getInt(null);
		}
	}

	/** fabric-registry-sync's addAlias as Mixin weaves it into MappedRegistry, reduced to its two byLocation checks. */
	private static byte[] registryClass() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, REGISTRY, null, "java/lang/Object", null);
		cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "byLocation", "Ljava/util/Map;", null, null).visitEnd();
		cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "aliases", "Ljava/util/Map;", null, null).visitEnd();
		cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "warnings", "I", null, null).visitEnd();

		MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		for (String field : new String[] { "byLocation", "aliases" }) {
			init.visitVarInsn(Opcodes.ALOAD, 0);
			init.visitTypeInsn(Opcodes.NEW, "java/util/HashMap");
			init.visitInsn(Opcodes.DUP);
			init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false);
			init.visitFieldInsn(Opcodes.PUTFIELD, REGISTRY, field, "Ljava/util/Map;");
		}
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(0, 0);
		init.visitEnd();

		MethodVisitor keySet = cw.visitMethod(Opcodes.ACC_PUBLIC, "keySet", "()Ljava/util/Set;", null, null);
		keySet.visitCode();
		keySet.visitVarInsn(Opcodes.ALOAD, 0);
		keySet.visitFieldInsn(Opcodes.GETFIELD, REGISTRY, "byLocation", "Ljava/util/Map;");
		keySet.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "keySet", "()Ljava/util/Set;", true);
		keySet.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Collections", "unmodifiableSet", "(Ljava/util/Set;)Ljava/util/Set;", false);
		keySet.visitInsn(Opcodes.ARETURN);
		keySet.visitMaxs(0, 0);
		keySet.visitEnd();

		MethodVisitor register = cw.visitMethod(Opcodes.ACC_PUBLIC, "register", "(Ljava/lang/Object;)V", null, null);
		register.visitCode();
		register.visitVarInsn(Opcodes.ALOAD, 0);
		register.visitFieldInsn(Opcodes.GETFIELD, REGISTRY, "byLocation", "Ljava/util/Map;");
		register.visitVarInsn(Opcodes.ALOAD, 1);
		register.visitVarInsn(Opcodes.ALOAD, 1);
		register.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
		register.visitInsn(Opcodes.POP);
		register.visitInsn(Opcodes.RETURN);
		register.visitMaxs(0, 0);
		register.visitEnd();

		MethodVisitor alias = cw.visitMethod(Opcodes.ACC_PUBLIC, "addAlias", "(Ljava/lang/Object;Ljava/lang/Object;)V", null, null);
		alias.visitCode();
		Label notPresent = new Label(), exists = new Label();
		// "Tried adding %s as an alias, but it is already present in registry %s" — reads the ALIAS, slot 1.
		alias.visitVarInsn(Opcodes.ALOAD, 0);
		alias.visitFieldInsn(Opcodes.GETFIELD, REGISTRY, "byLocation", "Ljava/util/Map;");
		alias.visitVarInsn(Opcodes.ALOAD, 1);
		alias.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "containsKey", "(Ljava/lang/Object;)Z", true);
		alias.visitJumpInsn(Opcodes.IFEQ, notPresent);
		alias.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalArgumentException");
		alias.visitInsn(Opcodes.DUP);
		alias.visitLdcInsn("Tried adding %s as an alias, but it is already present in registry %s");
		alias.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalArgumentException", "<init>", "(Ljava/lang/String;)V", false);
		alias.visitInsn(Opcodes.ATHROW);
		alias.visitLabel(notPresent);
		// The warning's check — reads the TARGET, slot 2, and falls into the warning.
		alias.visitVarInsn(Opcodes.ALOAD, 0);
		alias.visitFieldInsn(Opcodes.GETFIELD, REGISTRY, "byLocation", "Ljava/util/Map;");
		alias.visitVarInsn(Opcodes.ALOAD, 2);
		alias.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "containsKey", "(Ljava/lang/Object;)Z", true);
		alias.visitJumpInsn(Opcodes.IFNE, exists);
		alias.visitLdcInsn(WARNING);
		alias.visitInsn(Opcodes.POP);
		alias.visitFieldInsn(Opcodes.GETSTATIC, REGISTRY, "warnings", "I");
		alias.visitInsn(Opcodes.ICONST_1);
		alias.visitInsn(Opcodes.IADD);
		alias.visitFieldInsn(Opcodes.PUTSTATIC, REGISTRY, "warnings", "I");
		alias.visitLabel(exists);
		alias.visitVarInsn(Opcodes.ALOAD, 0);
		alias.visitFieldInsn(Opcodes.GETFIELD, REGISTRY, "aliases", "Ljava/util/Map;");
		alias.visitVarInsn(Opcodes.ALOAD, 1);
		alias.visitVarInsn(Opcodes.ALOAD, 2);
		alias.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
		alias.visitInsn(Opcodes.POP);
		alias.visitInsn(Opcodes.RETURN);
		alias.visitMaxs(0, 0);
		alias.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** A MinecraftForge wrapper in miniature: registers into its own names and answers keySet from them. */
	private static byte[] wrapperClass() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, WRAPPER, null, REGISTRY, null);
		cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "names", "Ljava/util/Set;", null, null).visitEnd();
		MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, REGISTRY, "<init>", "()V", false);
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitTypeInsn(Opcodes.NEW, "java/util/HashSet");
		init.visitInsn(Opcodes.DUP);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/HashSet", "<init>", "()V", false);
		init.visitFieldInsn(Opcodes.PUTFIELD, WRAPPER, "names", "Ljava/util/Set;");
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(0, 0);
		init.visitEnd();
		MethodVisitor keySet = cw.visitMethod(Opcodes.ACC_PUBLIC, "keySet", "()Ljava/util/Set;", null, null);
		keySet.visitCode();
		keySet.visitVarInsn(Opcodes.ALOAD, 0);
		keySet.visitFieldInsn(Opcodes.GETFIELD, WRAPPER, "names", "Ljava/util/Set;");
		keySet.visitInsn(Opcodes.ARETURN);
		keySet.visitMaxs(0, 0);
		keySet.visitEnd();
		MethodVisitor register = cw.visitMethod(Opcodes.ACC_PUBLIC, "register", "(Ljava/lang/Object;)V", null, null);
		register.visitCode();
		register.visitVarInsn(Opcodes.ALOAD, 0);
		register.visitFieldInsn(Opcodes.GETFIELD, WRAPPER, "names", "Ljava/util/Set;");
		register.visitVarInsn(Opcodes.ALOAD, 1);
		register.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Set", "add", "(Ljava/lang/Object;)Z", true);
		register.visitInsn(Opcodes.POP);
		register.visitInsn(Opcodes.RETURN);
		register.visitMaxs(0, 0);
		register.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** NeoForge's order: the alias first, then the entry it points at. */
	private static byte[] deferredClass() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, DEFERRED, null, "java/lang/Object", null);
		MethodVisitor add = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "addEntries",
				"(L" + REGISTRY + ";Ljava/lang/Object;Ljava/lang/Object;)V", null, null);
		add.visitCode();
		add.visitVarInsn(Opcodes.ALOAD, 0);
		add.visitVarInsn(Opcodes.ALOAD, 1);
		add.visitVarInsn(Opcodes.ALOAD, 2);
		add.visitMethodInsn(Opcodes.INVOKEVIRTUAL, REGISTRY, "addAlias", "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
		add.visitVarInsn(Opcodes.ALOAD, 0);
		add.visitVarInsn(Opcodes.ALOAD, 2);
		add.visitMethodInsn(Opcodes.INVOKEVIRTUAL, REGISTRY, "register", "(Ljava/lang/Object;)V", false);
		add.visitInsn(Opcodes.RETURN);
		add.visitMaxs(0, 0);
		add.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}
}
