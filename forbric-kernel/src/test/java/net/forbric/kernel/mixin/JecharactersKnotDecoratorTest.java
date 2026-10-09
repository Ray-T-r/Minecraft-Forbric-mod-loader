/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.ext.IExtensionRegistry;
import org.spongepowered.asm.transformers.TreeTransformer;

import net.fabricmc.api.EnvType;
import net.forbric.api.DiscoveredMod;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.fabric.KernelFabricLoader;

/**
 * JECharacters' real, unmodified {@code JechMixinPlugin}, initialised on the kernel's game loader: its static initialiser
 * calls its {@code hook()}, which reflects Knot's {@code delegate.mixinTransformer}, reads its search-target table and
 * config, and writes its {@code MixinTransformerHook} around the weaver it found. As merged that reflection threw
 * NoSuchFieldException on Forbric's loader; nothing rewrites the plugin now, and the hook is what the class pipeline
 * weaves with.
 *
 * <p>The plugin's own dependencies come from where a boot gets them: Guava, Gson, SLF4J and Log4j from Minecraft's library
 * set, {@code FabricLoader} from the kernel. The weaver is a stand-in shaped like Mixin's (a {@code TreeTransformer}
 * that is an {@code IMixinTransformer}, which is what the hook casts it to) that records what it is asked to weave.
 */
@ResourceLock("system-properties")
@ResourceLock("MixinWeaverSlot")
@ResourceLock("KernelFabricEcosystem")
class JecharactersKnotDecoratorTest {
	private static final Path JAR = Path.of("build/sweep100-mac-network/mods/jecharacters-26.1.2-fabric-4.6.8.jar");
	private static final String PLUGIN = "me.towdium.jecharacters.mixin.JechMixinPlugin";

	@TempDir Path work;

	@AfterEach void reset() throws Exception {
		MixinWeaverSlot.reset();
		System.clearProperty(MixinPlatformIdentity.PROPERTY);
		resetFabricLoader();
	}

	/** Mixin's own transformer, as far as the hook can tell: it extends TreeTransformer, and it records each class. */
	static final class RecordingWeaver extends TreeTransformer implements IMixinTransformer {
		final List<String> woven = new ArrayList<>();

		@Override public String getName() { return "recording"; }
		@Override public boolean isDelegationExcluded() { return false; }
		@Override public byte[] transformClassBytes(String name, String transformedName, byte[] bytes) { woven.add(name); return bytes; }
		@Override public void audit(MixinEnvironment environment) { }
		@Override public List<String> reload(String mixinClass, ClassNode classNode) { return List.of(); }
		@Override public boolean computeFramesForClass(MixinEnvironment environment, String name, ClassNode classNode) { return false; }
		@Override public byte[] transformClass(MixinEnvironment environment, String name, byte[] classBytes) { return classBytes; }
		@Override public boolean transformClass(MixinEnvironment environment, String name, ClassNode classNode) { return false; }
		@Override public boolean couldTransformClass(MixinEnvironment environment, String name) { return true; }
		@Override public byte[] generateClass(MixinEnvironment environment, String name) { return null; }
		@Override public boolean generateClass(MixinEnvironment environment, String name, ClassNode classNode) { return false; }
		@Override public IExtensionRegistry getExtensions() { return null; }
	}

	/** One of Minecraft 26.2's own libraries, by the path its version JSON lists. */
	private static Path minecraftLibrary(String json, String under) {
		Matcher path = Pattern.compile("\"path\"\\s*:\\s*\"(" + Pattern.quote(under) + "/[^\"]+\\.jar)\"").matcher(json);
		return path.find() ? TestFixtures.minecraftDir().resolve("libraries").resolve(path.group(1)) : null;
	}

	/** The game loader over JECharacters' jar, its Fabric build, with the libraries a boot puts beneath it. */
	private ForbricClassLoader game() throws Exception {
		TestFixtures.require(Fixture.THIRD_PARTY, Files.isRegularFile(JAR), "local mod fixture absent: " + JAR);
		Path versionJson = TestFixtures.minecraftDir().resolve("versions/26.2/26.2.json");
		TestFixtures.requireFiles(Fixture.MC_LIBRARIES, "Minecraft 26.2's version JSON", versionJson);
		String json = Files.readString(versionJson);
		List<URL> libraries = new ArrayList<>();
		for (String under : List.of("com/google/guava/guava", "com/google/guava/failureaccess", "com/google/code/gson/gson", "org/slf4j/slf4j-api",
				"org/apache/logging/log4j/log4j-api")) {
			Path library = minecraftLibrary(json, under);
			TestFixtures.require(Fixture.MC_LIBRARIES, library != null && Files.isRegularFile(library), "Minecraft library " + under + ": " + library);
			libraries.add(library.toUri().toURL());
		}
		URLClassLoader beneath = new URLClassLoader(libraries.toArray(URL[]::new), getClass().getClassLoader());
		ForbricClassLoader game = new ForbricClassLoader(new URL[] {JAR.toUri().toURL()}, beneath);
		game.setModOrigins(List.of(new DiscoveredMod(Ecosystem.FABRIC, "jecharacters", "4.6.8", "JECharacters", List.of(), List.of(),
				null, JAR.toString())));
		resetFabricLoader();
		KernelFabricLoader.create(EnvType.CLIENT, work, work.resolve("config"), new String[0], "26.2");
		return game;
	}

	private static void resetFabricLoader() throws Exception {
		Method reset = KernelFabricLoader.class.getDeclaredMethod("resetForTests");
		reset.setAccessible(true);
		reset.invoke(null);
	}

	private static byte[] someClass() {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "unrelated/Woven", null, "java/lang/Object", null);
		writer.visitEnd();
		return writer.toByteArray();
	}

	@Test void jecharactersOwnHookWrapsTheWeaverTheClassPipelineReads() throws Exception {
		RecordingWeaver mixin = new RecordingWeaver();
		try (ForbricClassLoader game = game()) {
			MixinWeaverSlot.install(mixin);
			game.knotDelegate().attach();

			Class.forName(PLUGIN, true, game);

			IMixinTransformer weaving = MixinWeaverSlot.currentOr(mixin);
			assertEquals("me.towdium.jecharacters.mixin.MixinTransformerHook", weaving.getClass().getName(),
					"JECharacters' decorator is what weaves every class from here on");
			assertTrue(Files.isRegularFile(work.resolve("config/jecharacters-extra.json")), "and the hook ran to its end");
			byte[] bytes = someClass();
			assertSame(bytes, weaving.transformClassBytes("unrelated.Woven", "unrelated.Woven", bytes));
			assertEquals(List.of("unrelated.Woven"), mixin.woven, "it hands every class to the weaver it wrapped");
		}
	}

	/** Premise: on a loader without Knot's field — Forbric's as merged — the same initialiser fails on that field. */
	@Test void onALoaderWithoutKnotsFieldTheHookFails() throws Exception {
		try (ForbricClassLoader game = game(); URLClassLoader plain = new URLClassLoader(new URL[] {JAR.toUri().toURL()}, game.getParent())) {
			ExceptionInInitializerError failed = assertThrows(ExceptionInInitializerError.class, () -> Class.forName(PLUGIN, true, plain));
			assertInstanceOf(NoSuchFieldException.class, failed.getCause().getCause(), String.valueOf(failed.getCause()));
			assertEquals("delegate", failed.getCause().getCause().getMessage());
		}
	}

	@Test void switchedOffItsHookGoesIntoAFieldTheClassPipelineDoesNotRead() throws Exception {
		System.setProperty(MixinPlatformIdentity.PROPERTY, "off");
		RecordingWeaver mixin = new RecordingWeaver();
		try (ForbricClassLoader game = game()) {
			MixinWeaverSlot.install(mixin);
			game.knotDelegate().attach();

			Class.forName(PLUGIN, true, game);

			assertSame(mixin, MixinWeaverSlot.currentOr(mixin));
		}
	}
}
