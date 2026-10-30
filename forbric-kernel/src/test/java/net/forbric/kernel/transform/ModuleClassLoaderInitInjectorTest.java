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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.fabricmc.api.EnvType;
import net.forbric.kernel.mixin.MixinWeaverSlot;

/**
 * NeoForge's {@code ModuleClassLoader} initialises on a JVM that did not open java.lang.invoke — the kernel's —
 * once {@link ModuleClassLoaderInitInjector} has seen it, and not before.
 *
 * <p>Nothing in the kernel runs that initialiser except the TransformingClassLoader view LibJF's ASM layer is
 * handed, so this is the precondition of that view: without it the class, and every subclass, is erroneous for
 * the rest of the run.
 *
 * <p>The carrier's own class is staged; a stand-in with the same guarded {@code IMPL_LOOKUP} lookup is compiled here,
 * so the patched initialiser also runs on a checkout without it.
 */
@ExecutesInjector(ModuleClassLoaderInitInjector.class)
class ModuleClassLoaderInitInjectorTest {
	private static final Path CARRIER = TestFixtures.stagedRoot().resolve("neoforge-runtime/neoforge-runtime.jar");
	private static final String INTERNAL = "net/neoforged/fml/classloading/ModuleClassLoader";
	/** The guarded lookup the repair keys on: it catches three reflective failures, not the module system's refusal. */
	private static final String STAND_IN = """
			package net.neoforged.fml.classloading;

			import java.lang.invoke.MethodHandle;
			import java.lang.invoke.MethodHandles;
			import java.lang.invoke.MethodType;
			import java.lang.reflect.Field;

			public class ModuleClassLoader extends ClassLoader {
				private static final MethodHandle LAYER_BIND_TO_LOADER;

				static {
					MethodHandle bind = null;
					try {
						Field implLookup = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
						implLookup.setAccessible(true);
						MethodHandles.Lookup trusted = (MethodHandles.Lookup) implLookup.get(null);
						bind = trusted.findVirtual(ModuleLayer.class, "toString", MethodType.methodType(String.class));
					} catch (NoSuchFieldException | IllegalAccessException | NoSuchMethodException e) {
						throw new IllegalStateException(e);
					}
					LAYER_BIND_TO_LOADER = bind;
				}
			}
			""";

	@AfterEach
	void clearSwitch() {
		System.clearProperty(MixinWeaverSlot.SWITCH);
	}

	@Test
	void theCarriersInitialiserGainsAHandlerOverTheBlockItAlreadyGuards() throws Exception {
		byte[] in = carrierClass();
		byte[] out = new ModuleClassLoaderInitInjector().transform(ModuleClassLoaderInitInjector.TARGET, in, null);
		assertNotSame(in, out);

		MethodNode clinit = clinit(out);
		TryCatchBlockNode guarded = null;
		TryCatchBlockNode added = null;
		for (TryCatchBlockNode block : clinit.tryCatchBlocks) {
			if ("java/lang/NoSuchFieldException".equals(block.type)) guarded = block;
			if ("java/lang/reflect/InaccessibleObjectException".equals(block.type)) added = block;
		}
		assertNotNull(added, "no InaccessibleObjectException handler was added");
		assertSame(guarded.start, added.start, "the same range the carrier guards: the IMPL_LOOKUP lookup");
		assertSame(guarded.end, added.end);
	}

	@Test
	void unpatchedItIsErroneousOnThisJvmAndPatchedItInitialises() throws Exception {
		byte[] in = carrierClass();
		// The test JVM is build.gradle's, which opens nothing; one that opened java.lang.invoke could not show the
		// unpatched class failing, so that is a broken harness rather than a missing fixture.
		assertFalse(Object.class.getModule().isOpen("java.lang.invoke",
				getClass().getClassLoader().getUnnamedModule()), "this JVM opened java.lang.invoke — nothing to show");

		try (DefiningLoader shipped = new DefiningLoader(in)) {
			// The ground truth the injector exists for: InaccessibleObjectException escapes the carrier's catch.
			Throwable failed = assertThrows(ExceptionInInitializerError.class,
					() -> Class.forName(ModuleClassLoaderInitInjector.TARGET, true, shipped));
			assertInstanceOf(java.lang.reflect.InaccessibleObjectException.class, failed.getCause());
		}

		byte[] out = new ModuleClassLoaderInitInjector().transform(ModuleClassLoaderInitInjector.TARGET, in, null);
		try (DefiningLoader patched = new DefiningLoader(out)) {
			Class<?> type = Class.forName(ModuleClassLoaderInitInjector.TARGET, true, patched);
			Field bind = type.getDeclaredField("LAYER_BIND_TO_LOADER");
			bind.setAccessible(true);
			assertNull(bind.get(null), "the handle only a constructor reads stays null; nothing else changes");
		}
	}

	/** The carrier's failure and the repair, on the stand-in: erroneous for good unpatched, initialised patched. */
	@Test
	void aStandInsInitialiserFinishesOnlyAfterTheRepair(@TempDir Path work) throws Exception {
		// The test JVM is launched without --add-opens; were java.lang.invoke opened, the handler would never run.
		assertFalse(Object.class.getModule().isOpen("java.lang.invoke", getClass().getClassLoader().getUnnamedModule()),
				"this JVM opened java.lang.invoke, so the refusal the repair handles cannot happen here");
		Map<String, byte[]> original = InjectorExecution.compile(work, Map.of(ModuleClassLoaderInitInjector.TARGET, STAND_IN));
		byte[] shipped = original.get(INTERNAL);

		ClassLoader unpatched = InjectorExecution.load(original);
		Throwable failed = assertThrows(ExceptionInInitializerError.class,
				() -> Class.forName(ModuleClassLoaderInitInjector.TARGET, true, unpatched));
		assertInstanceOf(java.lang.reflect.InaccessibleObjectException.class, failed.getCause());
		assertThrows(NoClassDefFoundError.class, () -> Class.forName(ModuleClassLoaderInitInjector.TARGET, true, unpatched),
				"premise: one failed initialiser leaves the class erroneous for the rest of the run");

		byte[] patched = InjectorExecution.transform(new ModuleClassLoaderInitInjector(), ModuleClassLoaderInitInjector.TARGET,
				shipped, EnvType.CLIENT);
		assertNotSame(shipped, patched);
		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(INTERNAL, patched);
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(patched, loader));
		Class<?> type = Class.forName(ModuleClassLoaderInitInjector.TARGET, true, loader);
		assertNull(InjectorExecution.getStatic(type, "LAYER_BIND_TO_LOADER"),
				"the handle only a constructor reads stays null; nothing else changes");
		assertSame(patched, InjectorExecution.transform(new ModuleClassLoaderInitInjector(), ModuleClassLoaderInitInjector.TARGET,
				patched, EnvType.CLIENT), "a class that already tolerates it is left alone");
	}

	@Test
	void switchedOffTheCarrierIsLeftAsShipped() throws Exception {
		System.setProperty(MixinWeaverSlot.SWITCH, "off");
		byte[] in = carrierClass();
		assertSame(in, new ModuleClassLoaderInitInjector().transform(ModuleClassLoaderInitInjector.TARGET, in, null));
	}

	@Test
	void itTouchesNothingElseAndNothingTwice() throws Exception {
		byte[] in = carrierClass();
		ModuleClassLoaderInitInjector injector = new ModuleClassLoaderInitInjector();
		assertSame(in, injector.transform("net.neoforged.fml.classloading.SomethingElse", in, null));
		byte[] once = injector.transform(ModuleClassLoaderInitInjector.TARGET, in, null);
		assertSame(once, injector.transform(ModuleClassLoaderInitInjector.TARGET, once, null),
				"a class that already tolerates it is left alone");
	}

	@Test
	void itDeclaresTheClassItMustLandOn() {
		assertEquals(ModuleClassLoaderInitInjector.TARGET,
				new ModuleClassLoaderInitInjector().anchors().anchors().get(0).binaryName());
	}

	private static MethodNode clinit(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		for (MethodNode method : node.methods) if ("<clinit>".equals(method.name)) return method;
		throw new AssertionError("no <clinit>");
	}

	private static byte[] carrierClass() throws Exception {
		TestFixtures.requireFile(Fixture.STAGED, CARRIER, "NeoForge carrier not staged");
		try (ZipFile zip = new ZipFile(CARRIER.toFile());
				InputStream in = zip.getInputStream(zip.getEntry("net/neoforged/fml/classloading/ModuleClassLoader.class"))) {
			return in.readAllBytes();
		}
	}

	/** Defines ModuleClassLoader from the given bytes; everything else it names comes from the carrier. */
	private static final class DefiningLoader extends URLClassLoader {
		private final byte[] bytes;

		DefiningLoader(byte[] bytes) throws Exception {
			super(new URL[] {CARRIER.toUri().toURL()}, ModuleClassLoaderInitInjectorTest.class.getClassLoader());
			this.bytes = bytes;
		}

		@Override
		protected Class<?> findClass(String name) throws ClassNotFoundException {
			if (ModuleClassLoaderInitInjector.TARGET.equals(name)) return defineClass(name, bytes, 0, bytes.length);
			return super.findClass(name);
		}
	}
}
