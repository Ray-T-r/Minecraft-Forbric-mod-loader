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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

import net.forbric.kernel.mixin.MixinWeaverSlot;

/**
 * NeoForge's {@code ModuleClassLoader} initialises on a JVM that did not open java.lang.invoke — the kernel's —
 * once {@link ModuleClassLoaderInitInjector} has seen it, and not before.
 *
 * <p>Nothing in the kernel runs that initialiser except the TransformingClassLoader view LibJF's ASM layer is
 * handed, so this is the precondition of that view: without it the class, and every subclass, is erroneous for
 * the rest of the run.
 */
class ModuleClassLoaderInitInjectorTest {
	private static final Path CARRIER = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", "../forbric-loader"),
			"run", "neoforge-runtime", "neoforge-runtime.jar");

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
		assumeTrue(!Object.class.getModule().isOpen("java.lang.invoke",
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
		assumeTrue(Files.isRegularFile(CARRIER), "NeoForge carrier not staged");
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
