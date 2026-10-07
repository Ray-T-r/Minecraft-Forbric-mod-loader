/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

import net.fabricmc.api.EnvType;
import net.forbric.api.DiscoveredMod;
import net.forbric.api.Ecosystem;
import net.forbric.api.ModPresence;

/**
 * {@link MixinPluginPlatformInjector}'s output, run: Controlify's compat mixin plugin picks the platform of the build
 * that is installed when the mixin service is Forbric's, and JECharacters' plugin decorates the live Mixin transformer
 * through the kernel's slot — where as merged the first threw on a service name it does not know and the second on a
 * Knot field Forbric's loader does not have. Under a native service Controlify decides as it always did.
 *
 * <p>Both the kernel helpers are real: {@code CallerMixinService} reads Controlify's owner from {@code ModPresence},
 * published as a boot publishes it, and {@code ReflectiveWeaverBridge} reads {@code MixinWeaverSlot}, given a transformer as
 * the Mixin bootstrap gives it. The two plugins are stand-ins in the shapes the edits key on. Mixin's
 * {@code MixinService} and {@code IMixinService} are stand-ins too, so the test never starts Mixin's real service
 * lookup in this JVM; {@code IMixinTransformer} is the real interface.
 */
@ExecutesInjector(MixinPluginPlatformInjector.class)
@ResourceLock("system-properties")
@ResourceLock("ModPresence")
@ResourceLock("MixinWeaverSlot")
class MixinPluginPlatformInjectorExecutionTest {
	private static Ecosystem actualOwner;
	private static final String CONTROLIFY = "fixture.unknownplatform.compatibility.CompatMixinPlugin";
	private static final String JECH = "fixture.unknownweaver.mixin.JechMixinPlugin";
	private static final String SLOT = "net.forbric.kernel.mixin.MixinWeaverSlot";

	private static String platform(String binaryName) {
		int dot = binaryName.lastIndexOf('.');
		return "package " + binaryName.substring(0, dot) + "; public class " + binaryName.substring(dot + 1)
				+ " implements fixture.unknownplatform.compatibility.CompatMixinPlatform { }";
	}

	private static final Map<String, String> STAND_INS = Map.of(
			"org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin", "package org.spongepowered.asm.mixin.extensibility; public interface IMixinConfigPlugin {}",
			"org.spongepowered.asm.service.IMixinService", "package org.spongepowered.asm.service; public interface IMixinService { String getName(); }",
			"org.spongepowered.asm.service.MixinService", """
					package org.spongepowered.asm.service;

					public final class MixinService {
						/** The running service's name: Forbric's, or a native loader's. */
						public static String name = "Forbric";

						public static IMixinService getService() {
							return () -> name;
						}
					}
					""",
			"fixture.unknownplatform.compatibility.CompatMixinPlatform", "package fixture.unknownplatform.compatibility; public interface CompatMixinPlatform { }",
			"fixture.unknownplatform.fabric.compatibility.FabricCompatMixinPlatform", platform("fixture.unknownplatform.fabric.compatibility.FabricCompatMixinPlatform"),
			"fixture.unknownplatform.neoforge.compatibility.NeoforgeCompatMixinPlatform",
			platform("fixture.unknownplatform.neoforge.compatibility.NeoforgeCompatMixinPlatform"),
			CONTROLIFY, """
					package fixture.unknownplatform.compatibility;

					import org.spongepowered.asm.service.IMixinService;
					import org.spongepowered.asm.service.MixinService;

					public class CompatMixinPlugin implements org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin {
						public enum Loader { FABRIC, NEOFORGE }

						public record Platform(Loader loader, CompatMixinPlatform impl) {
						}

						/** Controlify's: the loader is read off the mixin service's name. */
						public Platform loadPlatform() {
							IMixinService service = MixinService.getService();
							return switch (service.getName()) {
								case "Knot/Fabric" -> new Platform(Loader.FABRIC, loadPlatformImpl(service,
										"fixture.unknownplatform.fabric.compatibility.FabricCompatMixinPlatform"));
								case "ModLauncher" -> new Platform(Loader.NEOFORGE, loadPlatformImpl(service,
										"fixture.unknownplatform.neoforge.compatibility.NeoforgeCompatMixinPlatform"));
								default -> throw new IllegalStateException("Unknown mixin service: " + service.getName());
							};
						}

						private static CompatMixinPlatform loadPlatformImpl(IMixinService service, String className) {
							try {
								return (CompatMixinPlatform) Class.forName(className, true, CompatMixinPlugin.class.getClassLoader())
										.getConstructor().newInstance();
							} catch (ReflectiveOperationException e) {
								throw new IllegalStateException(e);
							}
						}
					}
					""",
			JECH, """
					package fixture.unknownweaver.mixin;

					import java.lang.reflect.Field;
					import java.lang.reflect.Proxy;
					import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

					public class JechMixinPlugin implements org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin {
						public static IMixinTransformer wrapped;

						/** JECharacters': reach Knot's delegate's transformer slot, then put its decorator in it. */
						public static void hook() throws ReflectiveOperationException {
							ClassLoader knot = JechMixinPlugin.class.getClassLoader();
							Field delegateField = knot.getClass().getDeclaredField("delegate");
							delegateField.setAccessible(true);
							Object delegate = delegateField.get(knot);
							Field transformerField = delegate.getClass().getDeclaredField("mixinTransformer");
							transformerField.setAccessible(true);
							wrapped = (IMixinTransformer) transformerField.get(delegate);
							transformerField.set(delegate, Proxy.newProxyInstance(IMixinTransformer.class.getClassLoader(),
									new Class<?>[] {IMixinTransformer.class}, (proxy, method, args) -> method.getName().equals("toString") ? "jecharacters" : null));
						}
					}
					""");

	@AfterEach void reset() throws Throwable {
		System.clearProperty(MixinPluginPlatformInjector.PROPERTY);
		actualOwner = null;
		InjectorExecution.invokeStatic(Class.forName(SLOT), "reset");
	}

	private static Map<String, byte[]> transformed(Map<String, byte[]> original) {
		if (actualOwner == null) actualOwner = Ecosystem.FABRIC;
		Map<String, byte[]> classes = new HashMap<>(original);
		for (String target : List.of(CONTROLIFY, JECH)) {
			String internal = target.replace('.', '/');
			byte[] out = InjectorExecution.transform(new MixinPluginPlatformInjector(name -> actualOwner), target, original.get(internal), EnvType.CLIENT);
			assertNotSame(original.get(internal), out, target + " is the reviewed shape");
			classes.put(internal, out);
		}
		return classes;
	}

	/** Controlify's platform under the named mixin service: its loader and implementation, or why it failed. */
	private static String platform(ClassLoader loader, String service) throws Throwable {
		loader.loadClass("org.spongepowered.asm.service.MixinService").getField("name").set(null, service);
		try {
			Object platform = InjectorExecution.invoke(InjectorExecution.construct(loader.loadClass(CONTROLIFY)), "loadPlatform");
			Object impl = InjectorExecution.invoke(platform, "impl");
			return InjectorExecution.invoke(platform, "loader") + " " + impl.getClass().getSimpleName();
		} catch (IllegalStateException failed) {
			return failed.getMessage();
		}
	}


	@Test void controlifyPicksTheInstalledBuildsPlatformUnderForbric(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = InjectorExecution.compile(work, STAND_INS);
		actualOwner = Ecosystem.NEOFORGE;
		Map<String, byte[]> classes = transformed(original);
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(classes.get(CONTROLIFY.replace('.', '/')), loader));

		actualOwner = Ecosystem.NEOFORGE;
		assertEquals("NEOFORGE NeoforgeCompatMixinPlatform", platform(loader, "Forbric"), "the NeoForge build gets NeoForge's platform");
		actualOwner = Ecosystem.FABRIC;
		ClassLoader fabricLoader = InjectorExecution.load(transformed(original));
		assertEquals("FABRIC FabricCompatMixinPlatform", platform(fabricLoader, "Forbric"), "the Fabric build gets Fabric's");
		assertEquals("Forbric", loader.loadClass("org.spongepowered.asm.service.MixinService").getField("name").get(null), "the real service identity is unchanged");
		assertEquals("NEOFORGE NeoforgeCompatMixinPlatform", platform(loader, "ModLauncher"), "a native service decides as it always did");

		assertEquals("Unknown mixin service: Forbric", platform(InjectorExecution.load(original), "Forbric"),
				"premise: as merged, Controlify does not know Forbric's service");
	}

	@Test void jecharactersDecoratesTheLiveTransformerThroughTheKernelsSlot(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = InjectorExecution.compile(work, STAND_INS);
		Map<String, byte[]> classes = transformed(original);
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(classes.get(JECH.replace('.', '/')), loader));

		IMixinTransformer mixins = (IMixinTransformer) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {IMixinTransformer.class},
				(proxy, method, args) -> method.getName().equals("toString") ? "mixin's own" : null);
		Class<?> slot = Class.forName(SLOT);
		InjectorExecution.invokeStatic(slot, "install", mixins);
		Class<?> plugin = loader.loadClass(JECH);
		InjectorExecution.invokeStatic(plugin, "hook");
		assertSame(mixins, InjectorExecution.getStatic(plugin, "wrapped"), "JECharacters wraps the live transformer");
		assertEquals("jecharacters", String.valueOf(InjectorExecution.invokeStatic(slot, "currentOr", (Object) null)),
				"and the kernel's slot now hands out its decorator");

		ClassLoader stock = InjectorExecution.load(original);
		assertThrows(NoSuchFieldException.class, () -> InjectorExecution.invokeStatic(stock.loadClass(JECH), "hook"),
				"premise: as merged, JECharacters looks for Knot's delegate on Forbric's loader");
		for (String target : List.of(CONTROLIFY, JECH)) {
			byte[] once = classes.get(target.replace('.', '/'));
			assertSame(once, InjectorExecution.transform(new MixinPluginPlatformInjector(name -> actualOwner), target, once, EnvType.CLIENT), target);
		}
	}

	@Test void switchedOffBothPluginsAreLeftAsShipped(@TempDir Path work) throws Exception {
		Map<String, byte[]> original = InjectorExecution.compile(work, STAND_INS);
		System.setProperty(MixinPluginPlatformInjector.PROPERTY, "off");
		for (String target : List.of(CONTROLIFY, JECH)) {
			byte[] bytes = original.get(target.replace('.', '/'));
			assertSame(bytes, InjectorExecution.transform(new MixinPluginPlatformInjector(name -> actualOwner), target, bytes, EnvType.CLIENT), target);
		}
	}

	@Test void unknownOwnershipAndDifferentReflectionFieldsAreRefused(@TempDir Path work) throws Exception {
		Map<String, byte[]> original = InjectorExecution.compile(work, STAND_INS);
		byte[] platform = original.get(CONTROLIFY.replace('.', '/'));
		assertSame(platform, new MixinPluginPlatformInjector(name -> null).transform(CONTROLIFY, platform, null), "a shared or unattributed caller cannot choose a platform");
		var node = new org.objectweb.asm.tree.ClassNode();
		new org.objectweb.asm.ClassReader(original.get(JECH.replace('.', '/'))).accept(node, 0);
		for (var method : node.methods) for (var instruction : method.instructions)
			if (instruction instanceof org.objectweb.asm.tree.LdcInsnNode constant && "delegate".equals(constant.cst)) constant.cst = "unrelatedState";
		var writer = new org.objectweb.asm.ClassWriter(0); node.accept(writer); byte[] wrong = writer.toByteArray();
		assertSame(wrong, new MixinPluginPlatformInjector(name -> Ecosystem.FABRIC).transform(JECH, wrong, null), "only the complete loader transformer ABI is adapted");
	}
}
