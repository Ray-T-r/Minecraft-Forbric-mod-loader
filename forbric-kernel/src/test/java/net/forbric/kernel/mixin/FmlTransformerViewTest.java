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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

import net.forbric.kernel.classloading.LoaderProbePolicy;
import net.forbric.kernel.transform.FmlContextLoaderRewriter;
import net.forbric.kernel.transform.ModuleClassLoaderInitInjector;

/**
 * A NeoForge mod that wraps the Mixin weaver the way NeoForge lets it — through FML's
 * {@code TransformingClassLoader} to {@code FMLMixinClassProcessor.transformer} — is the one that weaves from then
 * on: the rewritten cast gets the game-side view, the view holds the kernel's weaver, and the kernel reads the weaver
 * back through {@link MixinWeaverSlot} for every class.
 *
 * <p>LibJF's ASM layer is the mod that paid for it: its plugin cast the context loader to that class and got
 * {@code ForbricClassLoader}, so "Could not initialize LibJF ASM" and none of its {@code libjf:asm} patches (LibJF
 * Data Manipulation's resource-pack hook) ever ran. The walk below is the walk its {@code MixinPlugin.onLoad}
 * makes, field for field.
 */
class FmlTransformerViewTest {
	private static final Path STAGED = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", "../forbric-loader"), "run");
	private static final String PROBE = "com.example.libjf.MixinPlugin";
	private static final String TRANSFORMING_LOADER = "net/neoforged/fml/classloading/transformation/TransformingClassLoader";

	private final IMixinTransformer kernelWeaver = weaver();

	@BeforeEach
	@AfterEach
	void clean() {
		MixinWeaverSlot.reset();
		System.clearProperty(MixinWeaverSlot.SWITCH);
	}

	@Test
	void aNeoForgeModThatWrapsTheWeaverIsTheOneThatWeaves() throws Exception {
		MixinWeaverSlot.install(kernelWeaver);
		try (GameLoader game = new GameLoader(true, rewritten(plugin()))) {
			Object loader = probe(game);
			assertEquals(TRANSFORMING_LOADER.replace('/', '.'), loader.getClass().getName(),
					"the cast succeeded, on FML's own class");

			// LibJF's MixinPlugin.onLoad, field for field.
			Object classTransformer = read(loader, game.loadClass(TRANSFORMING_LOADER.replace('/', '.')), "classTransformer");
			Object set = read(classTransformer, game.loadClass(
					"net.neoforged.fml.classloading.transformation.ClassTransformer"), "processors");
			Map<?, ?> processors = (Map<?, ?>) read(set, game.loadClass(
					"net.neoforged.fml.classloading.transformation.ClassProcessorSet"), "processors");
			Object mixinId = game.loadClass("net.neoforged.neoforgespi.transformation.ClassProcessorIds")
					.getField("MIXIN").get(null);
			Object processor = processors.get(mixinId);
			Class<?> processorType = game.loadClass("net.neoforged.fml.loading.mixin.FMLMixinClassProcessor");
			assertInstanceOf(processorType, processor);
			Field transformer = processorType.getDeclaredField("transformer");
			transformer.setAccessible(true);
			assertSame(kernelWeaver, transformer.get(processor), "the view holds the weaver the kernel weaves with");

			// Before the write-back the kernel still weaves with its own.
			assertSame(kernelWeaver, MixinWeaverSlot.currentOr(kernelWeaver));
			IMixinTransformer wrapper = weaver();
			transformer.set(processor, wrapper);
			assertSame(wrapper, MixinWeaverSlot.currentOr(kernelWeaver), "the wrapper weaves every class after this");

			// A second mod walks the same view and finds the first one's wrapper, as on NeoForge.
			assertSame(loader, probe(game));
		}
	}

	@Test
	void withoutTheRewriteTheCastFailsAsItAlwaysDid() throws Exception {
		MixinWeaverSlot.install(kernelWeaver);
		try (GameLoader game = new GameLoader(true, plugin())) {
			assertCastFails(game);
		}
	}

	@Test
	void withoutTheInitialiserFixTheViewCannotExistAndTheCastFailsAsBefore() throws Exception {
		assumeTrue(!Object.class.getModule().isOpen("java.lang.invoke",
				getClass().getClassLoader().getUnnamedModule()), "this JVM opened java.lang.invoke");
		MixinWeaverSlot.install(kernelWeaver);
		try (GameLoader game = new GameLoader(false, rewritten(plugin()))) {
			assertCastFails(game);
			assertSame(kernelWeaver, MixinWeaverSlot.currentOr(kernelWeaver), "no view, so nothing to read back");
			assertCastFails(game); // and asking again neither throws anything new nor retries
		}
	}

	@Test
	void beforeMixinIsUpTheKernelsLoaderComesBack() throws Exception {
		try (GameLoader game = new GameLoader(true, rewritten(plugin()))) {
			assertCastFails(game);
		}
	}

	@Test
	void switchedOffTheKernelsLoaderComesBack() throws Exception {
		MixinWeaverSlot.install(kernelWeaver);
		byte[] rewritten = rewritten(plugin());
		System.setProperty(MixinWeaverSlot.SWITCH, "off");
		try (GameLoader game = new GameLoader(true, rewritten)) {
			assertCastFails(game);
		}
	}

	@Test
	void theSlotAnswersWithTheFallbackUntilSomethingIsWatched() {
		IMixinTransformer fallback = weaver();
		assertSame(fallback, MixinWeaverSlot.currentOr(fallback));
		MixinWeaverSlot.watch(() -> "not a transformer");
		assertSame(fallback, MixinWeaverSlot.currentOr(fallback));
		IMixinTransformer wrapper = weaver();
		MixinWeaverSlot.watch(() -> wrapper);
		assertSame(wrapper, MixinWeaverSlot.currentOr(fallback));
	}

	/**
	 * The kernel's class pipeline weaves through the slot, not through the transformer it captured at bootstrap:
	 * the captured one is exactly the field nobody would read after a wrapper replaced it.
	 */
	@Test
	void theKernelWeavesWithWhatTheSlotHolds() throws Exception {
		Path compiled = Path.of("build", "classes", "java", "main", "net", "forbric", "kernel", "mixin",
				"KernelMixinBootstrap.class");
		assumeTrue(Files.isRegularFile(compiled), "main classes not compiled yet");
		ClassNode node = new ClassNode();
		new ClassReader(Files.readAllBytes(compiled)).accept(node, 0);
		List<String> weaving = new ArrayList<>();
		for (MethodNode method : node.methods) {
			boolean weaves = false;
			boolean throughSlot = false;
			for (AbstractInsnNode insn : method.instructions) {
				if (!(insn instanceof MethodInsnNode call)) continue;
				if (call.name.equals("transformClassBytes")) weaves = true;
				if (call.owner.equals("net/forbric/kernel/mixin/MixinWeaverSlot") && call.name.equals("currentOr")) {
					throughSlot = true;
				}
			}
			if (weaves) {
				weaving.add(method.name);
				assertTrue(throughSlot, method.name + " weaves with a transformer it did not read from MixinWeaverSlot");
			}
		}
		assertTrue(!weaving.isEmpty(), "found no weaving call to check");
	}

	private static void assertCastFails(GameLoader game) {
		InvocationTargetException thrown = assertThrows(InvocationTargetException.class, () -> probe(game));
		assertInstanceOf(ClassCastException.class, thrown.getCause());
	}

	private static Object probe(ClassLoader game) throws Exception {
		Method probe = Class.forName(PROBE, true, game).getMethod("onLoad");
		return probe.invoke(null);
	}

	private static Object read(Object target, Class<?> owner, String name) throws Exception {
		Field field = owner.getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}

	private static byte[] rewritten(byte[] plugin) {
		byte[] out = new FmlContextLoaderRewriter(name -> LoaderProbePolicy.Family.NEOFORGE).transform(PROBE, plugin, null);
		assertTrue(out != plugin, "the rewriter did not touch the plugin");
		return out;
	}

	/** {@code return (TransformingClassLoader) Thread.currentThread().getContextClassLoader();} */
	private static byte[] plugin() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, PROBE.replace('.', '/'), null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "onLoad", "()Ljava/lang/Object;", null, null);
		mv.visitCode();
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread", "currentThread", "()Ljava/lang/Thread;", false);
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Thread", "getContextClassLoader", "()Ljava/lang/ClassLoader;", false);
		mv.visitTypeInsn(Opcodes.CHECKCAST, TRANSFORMING_LOADER);
		mv.visitInsn(Opcodes.ARETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static IMixinTransformer weaver() {
		return (IMixinTransformer) Proxy.newProxyInstance(IMixinTransformer.class.getClassLoader(),
				new Class<?>[] {IMixinTransformer.class}, (proxy, method, args) -> switch (method.getName()) {
					case "hashCode" -> System.identityHashCode(proxy);
					case "equals" -> proxy == args[0];
					case "toString" -> "weaver@" + Integer.toHexString(System.identityHashCode(proxy));
					default -> null;
				});
	}

	/**
	 * The game side as far as this path needs it: the kernel's runtime classes, the NeoForge carrier and log4j
	 * (ClassTransformer's logger). ModuleClassLoader goes through the injector, as the kernel's chain would put it;
	 * the plugin is defined from the bytes given.
	 */
	private static final class GameLoader extends URLClassLoader {
		private final boolean fixInitialiser;
		private final byte[] plugin;

		GameLoader(boolean fixInitialiser, byte[] plugin) throws Exception {
			super(urls(), FmlTransformerViewTest.class.getClassLoader());
			this.fixInitialiser = fixInitialiser;
			this.plugin = plugin;
		}

		private static URL[] urls() throws Exception {
			Path compiled = Path.of(System.getProperty("forbric.testRuntimeClasses", "build/classes/java/runtime"));
			Path carrier = STAGED.resolve("neoforge-runtime/neoforge-runtime.jar");
			assumeTrue(Files.isDirectory(compiled) && Files.isRegularFile(carrier), "staged runtime classes/carrier absent");
			String log4j = System.getProperty("forbric.log4jApiForTests", "");
			assumeTrue(!log4j.isEmpty() && Files.isRegularFile(Path.of(log4j)), "log4j-api absent");
			return new URL[] {compiled.toUri().toURL(), carrier.toUri().toURL(), Path.of(log4j).toUri().toURL()};
		}

		@Override
		protected Class<?> findClass(String name) throws ClassNotFoundException {
			if (PROBE.equals(name)) return defineClass(name, plugin, 0, plugin.length);
			if (fixInitialiser && "net.neoforged.fml.classloading.ModuleClassLoader".equals(name)) {
				try (InputStream in = getResourceAsStream(name.replace('.', '/') + ".class")) {
					byte[] bytes = new ModuleClassLoaderInitInjector().transform(name, in.readAllBytes(), null);
					return defineClass(name, bytes, 0, bytes.length);
				} catch (java.io.IOException unreadable) {
					throw new ClassNotFoundException(name, unreadable);
				}
			}
			return super.findClass(name);
		}
	}
}
