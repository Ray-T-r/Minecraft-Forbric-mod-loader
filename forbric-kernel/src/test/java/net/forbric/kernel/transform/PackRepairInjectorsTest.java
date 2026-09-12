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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * The fabric-api-vs-NeoForge overlay collision, reproduced end to end.
 *
 * <p>These tests build the merged base's {@code readPackMetadata} shape, apply fabric-api's mixin the way Mixin
 * applies it — AFTER the kernel's chain, because that is the real order — and then RUN the result. The control
 * case is the point: without the repair the reproduction must actually throw
 * {@code UnsupportedOperationException}, or the tests are asserting nothing.
 *
 * <p>The invariant test is the other half. The repair deliberately adds no store to the local, because a new
 * {@code ASTORE} inside the mixin's variable scope would give its ordinal-less {@code @At("STORE")} selector an
 * extra injection site and duplicate every conditional overlay — wrong instead of crashed. That property is
 * asserted directly rather than left to the doc comment.
 */
class PackRepairInjectorsTest {
	private static final String PACK = "net/minecraft/server/packs/repository/Pack";
	private static final String REPOSITORY = "net/minecraft/server/packs/repository/PackRepository";
	private static final int SLOT = 2;

	// ---------------------------------------------------------------- overlay mutability

	@Test
	void withoutTheRepairTheMixinMakesNeoForgesAddAllThrow() throws Exception {
		// The reproduction itself. If this ever stops throwing, every other assertion here is vacuous.
		Method merge = define(applyFabricMixin(mergedBaseShape())).getMethod(
				"readPackMetadata", List.class, List.class);

		InvocationTargetException boom = assertThrows(InvocationTargetException.class,
				() -> merge.invoke(null, List.of("a"), List.of("b")));
		assertEquals(UnsupportedOperationException.class, boom.getCause().getClass());
	}

	@Test
	void withTheRepairBothPatchesCompose() throws Exception {
		byte[] repaired = new PackOverlayMutabilityInjector()
				.transform(PACK.replace('/', '.'), mergedBaseShape(), null);
		Method merge = define(applyFabricMixin(repaired)).getMethod("readPackMetadata", List.class, List.class);

		// vanilla overlays first, then NeoForge's — the order the patch produces.
		assertEquals(List.of("a", "b", "c"), merge.invoke(null, List.of("a"), List.of("b", "c")));
	}

	@Test
	void theRepairIsSemanticsPreservingWithNoMixinPresent() throws Exception {
		byte[] repaired = new PackOverlayMutabilityInjector()
				.transform(PACK.replace('/', '.'), mergedBaseShape(), null);

		Method before = define(mergedBaseShape()).getMethod("readPackMetadata", List.class, List.class);
		Method after = define(repaired).getMethod("readPackMetadata", List.class, List.class);

		assertEquals(before.invoke(null, List.of("a"), List.of("b")),
				after.invoke(null, List.of("a"), List.of("b")));
		// The neoOverlays == null branch never enters the merge at all; it must stay untouched.
		assertEquals(before.invoke(null, List.of("a"), null), after.invoke(null, List.of("a"), null));
	}

	@Test
	void theRepairAddsNoStoreToTheLocalTheMixinWatches() {
		byte[] original = mergedBaseShape();
		byte[] repaired = new PackOverlayMutabilityInjector().transform(PACK.replace('/', '.'), original, null);

		assertEquals(storesTo(original, SLOT), storesTo(repaired, SLOT),
				"a new ASTORE inside the mixin's variable scope silently duplicates every conditional overlay");
	}

	@Test
	void aMethodWithoutNeoForgesDefensiveCopyIsLeftAlone() {
		// addAll straight onto the parameter: vanilla's own shape, no NeoForge patch, nothing to repair.
		byte[] plain = shape(false);
		assertArrayEquals(plain, new PackOverlayMutabilityInjector().transform(PACK.replace('/', '.'), plain, null));
	}

	@Test
	void anotherClassIsNeverTouched() {
		byte[] bytes = mergedBaseShape();
		assertArrayEquals(bytes, new PackOverlayMutabilityInjector()
				.transform("net.minecraft.server.packs.repository.PackSource", bytes, null));
	}

	@Test
	void theSwitchRestoresTheUnrepairedBehaviour() {
		byte[] bytes = mergedBaseShape();
		System.setProperty("forbric.packRepair", "off");
		try {
			assertArrayEquals(bytes, new PackOverlayMutabilityInjector()
					.transform(PACK.replace('/', '.'), bytes, null));
		} finally {
			System.clearProperty("forbric.packRepair");
		}
	}

	// ---------------------------------------------------------------- null-pack guard

	@Test
	void withoutTheGuardANullPackTakesTheRepositoryDown() throws Exception {
		Loader loader = new Loader();
		loader.add(PACK, packStub());
		Class<?> repo = loader.define(REPOSITORY, consumerShape());
		Method consume = repo.getDeclaredMethod("lambda$discoverAvailable$0", Map.class, loader.get(PACK));
		consume.setAccessible(true);

		InvocationTargetException boom = assertThrows(InvocationTargetException.class,
				() -> consume.invoke(null, Map.of(), (Object) null));
		assertEquals(NullPointerException.class, boom.getCause().getClass());
	}

	@Test
	void withTheGuardANullPackIsSkippedAndARealOneStillStreams() throws Exception {
		byte[] guarded = new NullPackGuardInjector().transform(REPOSITORY.replace('/', '.'), consumerShape(), null);

		Loader loader = new Loader();
		Class<?> pack = loader.define(PACK, packStub());
		Class<?> repo = loader.define(REPOSITORY, guarded);
		Method consume = repo.getDeclaredMethod("lambda$discoverAvailable$0", Map.class, pack);
		consume.setAccessible(true);

		consume.invoke(null, Map.of(), (Object) null); // must not throw
		consume.invoke(null, Map.of(), pack.getDeclaredConstructor().newInstance());
	}

	@Test
	void aClassWithNoPackConsumerIsLeftAlone() {
		byte[] stub = packStub();
		assertArrayEquals(stub, new NullPackGuardInjector().transform(REPOSITORY.replace('/', '.'), stub, null));
	}

	// ---------------------------------------------------------------- bytecode fixtures

	/**
	 * The merged base's shape:
	 * <pre>
	 *   overlaySet = vanilla;
	 *   if (neo != null) {
	 *       overlaySet = new ArrayList&lt;&gt;(overlaySet);   // NeoForge's patch begins here
	 *       overlaySet.addAll(neo);
	 *       overlaySet = List.copyOf(overlaySet);
	 *   }
	 *   return overlaySet;
	 * </pre>
	 */
	private static byte[] mergedBaseShape() {
		return shape(true);
	}

	private static byte[] shape(boolean withDefensiveCopy) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, PACK, null, "java/lang/Object", null);

		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "readPackMetadata",
				"(Ljava/util/List;Ljava/util/List;)Ljava/util/List;", null, null);
		mv.visitCode();
		Label end = new Label();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitVarInsn(Opcodes.ASTORE, SLOT);
		mv.visitVarInsn(Opcodes.ALOAD, 1);
		mv.visitJumpInsn(Opcodes.IFNULL, end);
		if (withDefensiveCopy) {
			mv.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
			mv.visitInsn(Opcodes.DUP);
			mv.visitVarInsn(Opcodes.ALOAD, SLOT);
			mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "(Ljava/util/Collection;)V",
					false);
			mv.visitVarInsn(Opcodes.ASTORE, SLOT);
		}
		mv.visitVarInsn(Opcodes.ALOAD, SLOT);
		mv.visitVarInsn(Opcodes.ALOAD, 1);
		mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "addAll", "(Ljava/util/Collection;)Z", true);
		mv.visitInsn(Opcodes.POP);
		mv.visitVarInsn(Opcodes.ALOAD, SLOT);
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/List", "copyOf",
				"(Ljava/util/Collection;)Ljava/util/List;", true);
		mv.visitVarInsn(Opcodes.ASTORE, SLOT);
		mv.visitLabel(end);
		mv.visitVarInsn(Opcodes.ALOAD, SLOT);
		mv.visitInsn(Opcodes.ARETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();

		cw.visitEnd();
		return cw.toByteArray();
	}

	/**
	 * fabric-api's {@code PackMixin} as Mixin applies it: after every STORE of the local, load it, run the handler,
	 * store it back. The handler always answers {@code List.copyOf(...)}, so the local becomes immutable — which is
	 * harmless on Fabric, where nothing mutates it afterwards, and fatal one instruction later on a merged base.
	 * Applied AFTER the kernel's transform, because that is the order the class loader runs them in.
	 */
	private static byte[] applyFabricMixin(byte[] classBytes) {
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);

		for (MethodNode method : node.methods) {
			if (!"readPackMetadata".equals(method.name)) continue;
			List<AbstractInsnNode> stores = new ArrayList<>();
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof VarInsnNode var && var.getOpcode() == Opcodes.ASTORE && var.var == SLOT) {
					stores.add(insn);
				}
			}
			// The first store initialises the local, and is outside the mixin's variable scope in the real base.
			for (AbstractInsnNode store : stores.subList(1, stores.size())) {
				InsnList handler = new InsnList();
				handler.add(new VarInsnNode(Opcodes.ALOAD, SLOT));
				handler.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/List", "copyOf",
						"(Ljava/util/Collection;)Ljava/util/List;", true));
				handler.add(new VarInsnNode(Opcodes.ASTORE, SLOT));
				method.instructions.insert(store, handler);
			}
		}

		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** {@code PackRepository}'s consumer: load the pack, stream it. Exactly where a null arrival explodes. */
	private static byte[] consumerShape() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, REPOSITORY, null, "java/lang/Object", null);

		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
				"lambda$discoverAvailable$0", "(Ljava/util/Map;L" + PACK + ";)V", null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 1);
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PACK, "streamSelfAndChildren", "()Ljava/util/stream/Stream;",
				false);
		mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/stream/Stream", "count", "()J", true);
		mv.visitInsn(Opcodes.POP2);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();

		cw.visitEnd();
		return cw.toByteArray();
	}

	private static byte[] packStub() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, PACK, null, "java/lang/Object", null);

		MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(0, 0);
		init.visitEnd();

		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "streamSelfAndChildren",
				"()Ljava/util/stream/Stream;", null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/stream/Stream", "of",
				"(Ljava/lang/Object;)Ljava/util/stream/Stream;", true);
		mv.visitInsn(Opcodes.ARETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();

		cw.visitEnd();
		return cw.toByteArray();
	}

	// ---------------------------------------------------------------- helpers

	private static int storesTo(byte[] classBytes, int slot) {
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		int stores = 0;
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof VarInsnNode var && var.getOpcode() == Opcodes.ASTORE && var.var == slot) {
					stores++;
				}
			}
		}
		return stores;
	}

	private static Class<?> define(byte[] classBytes) {
		return new Loader().define(PACK, classBytes);
	}

	/** Defines the synthetic net.minecraft classes without letting them escape into the test's own loader. */
	private static final class Loader extends ClassLoader {
		private final Map<String, Class<?>> defined = new java.util.HashMap<>();

		Loader() {
			super(PackRepairInjectorsTest.class.getClassLoader());
		}

		Class<?> define(String internalName, byte[] classBytes) {
			Class<?> c = defineClass(internalName.replace('/', '.'), classBytes, 0, classBytes.length);
			defined.put(internalName, c);
			assertNotNull(c);
			return c;
		}

		void add(String internalName, byte[] classBytes) {
			define(internalName, classBytes);
		}

		Class<?> get(String internalName) {
			return defined.get(internalName);
		}
	}
}
