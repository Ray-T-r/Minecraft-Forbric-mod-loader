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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import net.forbric.api.ForgeLoadingList;

/**
 * Runs the rewrite against a byte-for-byte reconstruction of MinecraftForge's lazy holder, and then really
 * initializes it — because the whole defect lives in class-initialization semantics, not in instruction counts.
 *
 * <p>The reconstruction is generated here rather than read out of a staged jar so this test runs on a clean
 * clone. Its shape is taken from {@code javap -p -c} of
 * {@code net/minecraftforge/fml/loading/LoadingModListImpl$1LazyInit} in {@code forge-runtime.jar}:
 *
 * <pre>{@code
 *    0: new           LoadingModListImpl / 3: dup
 *    4: getstatic     LoadingModListImpl.temp : ModSorter$State;
 *    7: invokevirtual ModSorter$State.files:()Ljava/util/List;
 *   10: getstatic     LoadingModListImpl.temp : ModSorter$State;
 *   13: invokevirtual ModSorter$State.mods:()Ljava/util/List;
 *   16: invokespecial LoadingModListImpl."<init>":(List;List;)V
 *   19: putstatic     INSTANCE
 * }</pre>
 *
 * <p>{@link #theUntransformedHolderIsPoisonedForeverByOneEarlyReader()} runs that original first, so the rest of
 * this file cannot pass vacuously: it proves the wall is real before proving the rewrite removes it.
 */
class ForgeLoadingListHolderInjectorTest {
	private static final String PKG = "net/minecraftforge/fml/loading/";
	private static final String IMPL = PKG + "LoadingModListImpl";
	private static final String HOLDER = IMPL + "$1LazyInit";
	private static final String STATE = PKG + "ModSorter$State";
	private static final String LIST = "Ljava/util/List;";

	private final ForgeLoadingListHolderInjector injector = new ForgeLoadingListHolderInjector();

	@BeforeEach
	@AfterEach
	void clearPublishedList() throws Exception {
		Method reset = ForgeLoadingList.class.getDeclaredMethod("reset");
		reset.setAccessible(true);
		reset.invoke(null);
	}

	@Test
	void theUntransformedHolderIsPoisonedForeverByOneEarlyReader() throws Exception {
		// No transform, and temp left null — exactly what the kernel's boot looked like before the publish point.
		Carrier carrier = new Carrier(holderBytes(2, true));

		ExceptionInInitializerError first = assertThrows(ExceptionInInitializerError.class, carrier::initHolder);
		assertInstanceOf(NullPointerException.class, first.getCause(),
				"the original reads a null temp and NPEs inside a class initializer");

		// Seeding temp afterwards is exactly what PassiveSeeder does, and it changes nothing: JVMS 5.5 leaves the
		// class erroneous for the rest of the JVM's life. This is why the fix cannot be "seed earlier".
		carrier.seedTemp(List.of("fileA"), List.of("modA"));
		assertThrows(NoClassDefFoundError.class, carrier::initHolder,
				"one early reader costs MinecraftForge its mod list for the whole run, seed or no seed");
	}

	@Test
	void theRewrittenHolderBuildsFromThePublishedListWithTempStillNull() throws Exception {
		Carrier carrier = new Carrier(transformed(holderBytes(2, true)));
		ForgeLoadingList.publish(List.of("fileA", "fileB"), List.of("modA", "modB", "modC"));

		Object instance = carrier.initHolder();

		assertNotNull(instance, "the holder must build from the published list");
		assertEquals(List.of("fileA", "fileB"), carrier.filesOf(instance));
		assertEquals(List.of("modA", "modB", "modC"), carrier.modsOf(instance));
		assertNull(carrier.temp(), "temp is never read now — whether the seed has run stops mattering");
	}

	@Test
	void anEmptyPublishedListIsHonoured() throws Exception {
		Carrier carrier = new Carrier(transformed(holderBytes(2, true)));
		ForgeLoadingList.publish(List.of(), List.of());

		assertEquals(List.of(), carrier.modsOf(carrier.initHolder()),
				"an instance with genuinely no MinecraftForge mods gets an empty list, not a crash");
	}

	@Test
	void readingBeforeThePublishThrowsTheDiagnosisInsteadOfANullTemp() throws Exception {
		Carrier carrier = new Carrier(transformed(holderBytes(2, true)));

		ExceptionInInitializerError boom = assertThrows(ExceptionInInitializerError.class, carrier::initHolder);
		// Still fatal, still one-shot — but it names what went wrong instead of leaving an NPE on a field name.
		// Silently answering "empty" here is the trade this whole fix exists to refuse.
		assertInstanceOf(IllegalStateException.class, boom.getCause());
		assertTrue(boom.getCause().getMessage().contains("before the kernel published one"),
				boom.getCause().getMessage());
	}

	@Test
	void refusesToBootWhenTheHolderNoLongerReadsTempTwice() {
		// A carrier whose holder reads temp once is a DIFFERENT computation; replacing its whole body wholesale
		// would no longer be equivalent, so the rewrite must refuse rather than guess.
		IllegalStateException refused = assertThrows(IllegalStateException.class,
				() -> transformed(holderBytes(1, true)));
		assertTrue(refused.getMessage().contains("refusing to boot"), refused.getMessage());
		assertTrue(refused.getMessage().contains("found 1/"), refused.getMessage());
	}

	@Test
	void refusesToBootWhenInstanceIsNoLongerFinal() {
		// INSTANCE being written exactly once is what makes the first read decide the whole run — and therefore
		// what makes this transform the right shape of fix. A second writer invalidates that reasoning.
		IllegalStateException refused = assertThrows(IllegalStateException.class,
				() -> transformed(holderBytes(2, false)));
		assertTrue(refused.getMessage().contains("no longer static final"), refused.getMessage());
	}

	@Test
	void leavesEveryOtherClassUntouched() {
		byte[] impl = implBytes();
		assertSame(impl, injector.transform("net.minecraftforge.fml.loading.LoadingModListImpl", impl, null),
				"only the lazy holder is rewritten");
		assertSame(impl, injector.transform("net.neoforged.fml.loading.LoadingModList", impl, null),
				"NeoForge's list has no lazy holder and is seeded directly");
	}

	private byte[] transformed(byte[] holder) {
		byte[] out = injector.transform(HOLDER.replace('/', '.'), holder, null);
		assertNotNull(out);
		return out;
	}

	/** Loads the three reconstructed classes in their own loader, so each test gets a fresh initialization state. */
	private final class Carrier extends ClassLoader {
		private final Map<String, byte[]> bytes = new HashMap<>();
		private Class<?> implClass;

		Carrier(byte[] holder) {
			super(ForgeLoadingListHolderInjectorTest.class.getClassLoader());
			bytes.put(STATE.replace('/', '.'), stateBytes());
			bytes.put(IMPL.replace('/', '.'), implBytes());
			bytes.put(HOLDER.replace('/', '.'), holder);
		}

		@Override
		protected Class<?> findClass(String name) throws ClassNotFoundException {
			byte[] b = bytes.get(name);
			if (b == null) throw new ClassNotFoundException(name);
			return defineClass(name, b, 0, b.length);
		}

		Object initHolder() throws Exception {
			Class<?> holder = Class.forName(HOLDER.replace('/', '.'), true, this);
			var instance = holder.getDeclaredField("INSTANCE");
			instance.setAccessible(true);
			return instance.get(null);
		}

		void seedTemp(List<?> files, List<?> mods) throws Exception {
			Class<?> state = Class.forName(STATE.replace('/', '.'), false, this);
			// ModSorter$State is package-private on the real carrier (flags 0x0030), like this reconstruction.
			var stateCtor = state.getDeclaredConstructor(List.class, List.class);
			stateCtor.setAccessible(true);
			Object built = stateCtor.newInstance(files, mods);
			var temp = impl().getDeclaredField("temp");
			temp.setAccessible(true);
			temp.set(null, built);
		}

		Object temp() throws Exception {
			var temp = impl().getDeclaredField("temp");
			temp.setAccessible(true);
			return temp.get(null);
		}

		List<?> filesOf(Object instance) throws Exception {
			return (List<?>) impl().getMethod("files").invoke(instance);
		}

		List<?> modsOf(Object instance) throws Exception {
			return (List<?>) impl().getMethod("mods").invoke(instance);
		}

		private Class<?> impl() throws Exception {
			if (implClass == null) implClass = Class.forName(IMPL.replace('/', '.'), false, this);
			return implClass;
		}
	}

	/** {@code ModSorter$State}: the two accessors the original holder calls, and nothing else. */
	private static byte[] stateBytes() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_SUPER, STATE, null, "java/lang/Object", null);
		cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "files", LIST, null, null).visitEnd();
		cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "mods", LIST, null, null).visitEnd();

		MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + LIST + LIST + ")V", null, null);
		ctor.visitCode();
		ctor.visitVarInsn(Opcodes.ALOAD, 0);
		ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		ctor.visitVarInsn(Opcodes.ALOAD, 0);
		ctor.visitVarInsn(Opcodes.ALOAD, 1);
		ctor.visitFieldInsn(Opcodes.PUTFIELD, STATE, "files", LIST);
		ctor.visitVarInsn(Opcodes.ALOAD, 0);
		ctor.visitVarInsn(Opcodes.ALOAD, 2);
		ctor.visitFieldInsn(Opcodes.PUTFIELD, STATE, "mods", LIST);
		ctor.visitInsn(Opcodes.RETURN);
		ctor.visitMaxs(0, 0);
		ctor.visitEnd();

		getter(cw, STATE, "files");
		getter(cw, STATE, "mods");
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** {@code LoadingModListImpl}: the {@code temp} field and the {@code (List, List)} constructor. */
	private static byte[] implBytes() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, IMPL, null, "java/lang/Object", null);
		cw.visitField(Opcodes.ACC_STATIC, "temp", "L" + STATE + ";", null, null).visitEnd();
		cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "files", LIST, null, null).visitEnd();
		cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "mods", LIST, null, null).visitEnd();

		MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + LIST + LIST + ")V", null, null);
		ctor.visitCode();
		ctor.visitVarInsn(Opcodes.ALOAD, 0);
		ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		ctor.visitVarInsn(Opcodes.ALOAD, 0);
		ctor.visitVarInsn(Opcodes.ALOAD, 1);
		ctor.visitFieldInsn(Opcodes.PUTFIELD, IMPL, "files", LIST);
		ctor.visitVarInsn(Opcodes.ALOAD, 0);
		ctor.visitVarInsn(Opcodes.ALOAD, 2);
		ctor.visitFieldInsn(Opcodes.PUTFIELD, IMPL, "mods", LIST);
		ctor.visitInsn(Opcodes.RETURN);
		ctor.visitMaxs(0, 0);
		ctor.visitEnd();

		getter(cw, IMPL, "files");
		getter(cw, IMPL, "mods");
		cw.visitEnd();
		return cw.toByteArray();
	}

	/**
	 * The holder, with the two knobs the seam assertions read.
	 *
	 * @param tempReads   how many times {@code <clinit>} reads {@code temp} — 2 on the real carrier
	 * @param instanceFinal whether {@code INSTANCE} is {@code final}, which is what makes the first value permanent
	 */
	private static byte[] holderBytes(int tempReads, boolean instanceFinal) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, HOLDER, null, "java/lang/Object", null);
		int access = Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | (instanceFinal ? Opcodes.ACC_FINAL : 0);
		cw.visitField(access, "INSTANCE", "L" + IMPL + ";", null, null).visitEnd();

		MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
		clinit.visitCode();
		clinit.visitTypeInsn(Opcodes.NEW, IMPL);
		clinit.visitInsn(Opcodes.DUP);
		// Read 1: state.files(). Always present.
		clinit.visitFieldInsn(Opcodes.GETSTATIC, IMPL, "temp", "L" + STATE + ";");
		clinit.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STATE, "files", "()" + LIST, false);
		if (tempReads >= 2) {
			// Read 2: state.mods(). A carrier that hoisted temp into a local would have only one.
			clinit.visitFieldInsn(Opcodes.GETSTATIC, IMPL, "temp", "L" + STATE + ";");
			clinit.visitMethodInsn(Opcodes.INVOKEVIRTUAL, STATE, "mods", "()" + LIST, false);
		} else {
			clinit.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/List", "of", "()" + LIST, true);
		}
		clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, IMPL, "<init>", "(" + LIST + LIST + ")V", false);
		clinit.visitFieldInsn(Opcodes.PUTSTATIC, HOLDER, "INSTANCE", "L" + IMPL + ";");
		clinit.visitInsn(Opcodes.RETURN);
		clinit.visitMaxs(0, 0);
		clinit.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static void getter(ClassWriter cw, String owner, String field) {
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, field, "()" + LIST, null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitFieldInsn(Opcodes.GETFIELD, owner, field, LIST);
		mv.visitInsn(Opcodes.ARETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}
}
