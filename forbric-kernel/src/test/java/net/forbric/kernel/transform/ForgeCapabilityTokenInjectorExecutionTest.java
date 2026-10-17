/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;
import net.forbric.kernel.ExpectCaught;

/**
 * {@link ForgeCapabilityTokenInjector}'s output, run: a {@code ForgeCapabilities} built from anonymous capability
 * tokens initialises, and each capability names its type, once the tokens have been through the injector.
 *
 * <p>{@code ForgeCapabilityTokenInjectorTest} drives MinecraftForge's own {@code CapabilityTokenSubclass} from the
 * staged carrier. Here that plugin is a stand-in compiled under its name, next to modlauncher's {@code Phase}, doing
 * the same two edits (un-final the base's {@code getType}; give a subclass a {@code getType} returning its type
 * argument) and recording how it was asked. What is under test is the kernel's half: which classes it offers by
 * header, what it passes, and that the edited classes load and run.
 */
@ExecutesInjector(ForgeCapabilityTokenInjector.class)
class ForgeCapabilityTokenInjectorExecutionTest {
	private static final String PLUGIN = ForgeCapabilityTokenInjector.PLUGIN;
	private static final String TOKEN = ForgeCapabilityTokenInjector.TOKEN;
	private static final String CAPABILITIES = "net.minecraftforge.common.capabilities.ForgeCapabilities";

	private static final Map<String, String> SOURCES = Map.of(
			"cpw.mods.modlauncher.serviceapi.ILaunchPluginService", """
					package cpw.mods.modlauncher.serviceapi;
					public interface ILaunchPluginService {
						enum Phase { BEFORE, AFTER }
					}
					""",
			PLUGIN, """
					package net.minecraftforge.fml.common.asm;

					import java.util.ArrayList;
					import java.util.List;

					import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
					import org.objectweb.asm.Opcodes;
					import org.objectweb.asm.Type;
					import org.objectweb.asm.tree.*;

					public class CapabilityTokenSubclass {
						private static final String TOKEN = "net/minecraftforge/common/capabilities/CapabilityToken";
						public static final List<String> calls = new ArrayList<>();

						public int processClassWithFlags(ILaunchPluginService.Phase phase, ClassNode node, Type type, String reason) {
							calls.add(phase.name() + " " + type.getInternalName() + " " + reason);
							if (node.name.equals(TOKEN)) {
								for (MethodNode method : node.methods) {
									if (method.name.equals("getType")) method.access &= ~Opcodes.ACC_FINAL;
								}
								return 256; // SIMPLE_REWRITE
							}
							String prefix = "L" + TOKEN + "<";
							if (node.signature == null || !node.signature.startsWith(prefix)) {
								throw new IllegalStateException(node.name + " has no type argument to read");
							}
							String argument = node.signature.substring(prefix.length(), node.signature.lastIndexOf('>'));
							MethodNode getType = new MethodNode(Opcodes.ACC_PUBLIC, "getType", "()Ljava/lang/String;", null, null);
							getType.instructions.add(new LdcInsnNode(Type.getType(argument).getInternalName()));
							getType.instructions.add(new InsnNode(Opcodes.ARETURN));
							node.methods.add(getType);
							return 1; // COMPUTE_MAXS
						}
					}
					""",
			"net.minecraftforge.common.capabilities.CapabilityToken", """
					package net.minecraftforge.common.capabilities;

					public abstract class CapabilityToken<T> {
						public final String getType() {
							throw new RuntimeException("This will be implemented by a transformer");
						}
					}
					""",
			CAPABILITIES, """
					package net.minecraftforge.common.capabilities;

					public final class ForgeCapabilities {
						public static final String ITEM_HANDLER = new CapabilityToken<fixture.IItemHandler>() { }.getType();
						public static final String ENERGY = new CapabilityToken<fixture.IEnergyStorage>() { }.getType();
					}
					""",
			"fixture.IItemHandler", "package fixture; public interface IItemHandler { }",
			"fixture.IEnergyStorage", "package fixture; public interface IEnergyStorage { }",
			// A raw token: no type argument for the plugin to read.
			"fixture.RawToken", """
					package fixture;
					@SuppressWarnings("rawtypes")
					public class RawToken extends net.minecraftforge.common.capabilities.CapabilityToken {
					}
					""");

	@Test void forgeCapabilitiesInitialisesAndEachTokenNamesItsType(@TempDir Path work) throws Throwable {
		Map<String, byte[]> classes = InjectorExecution.compile(work, SOURCES);
		ClassLoader game = InjectorExecution.load(classes);
		ForgeCapabilityTokenInjector injector = ForgeCapabilityTokenInjector.create(game);
		assertNotNull(injector, "the stand-in plugin and Phase are in the loader, so the injector must install");

		Map<String, byte[]> after = new HashMap<>(classes);
		for (String token : List.of(TOKEN, CAPABILITIES.replace('.', '/') + "$1", CAPABILITIES.replace('.', '/') + "$2")) {
			byte[] out = InjectorExecution.transform(injector, token.replace('/', '.'), classes.get(token), EnvType.SERVER);
			assertNotSame(classes.get(token), out, token);
			after.put(token, out);
		}

		ClassLoader run = InjectorExecution.load(after);
		for (String token : List.of(TOKEN, CAPABILITIES.replace('.', '/') + "$1")) {
			assertEquals("", InjectorExecution.verify(after.get(token), run), token);
		}
		Class<?> capabilities = Class.forName(CAPABILITIES, true, run);
		assertEquals("fixture/IItemHandler", InjectorExecution.getStatic(capabilities, "ITEM_HANDLER"));
		assertEquals("fixture/IEnergyStorage", InjectorExecution.getStatic(capabilities, "ENERGY"));

		ExceptionInInitializerError dead = assertThrows(ExceptionInInitializerError.class,
				() -> Class.forName(CAPABILITIES, true, InjectorExecution.load(classes)));
		assertEquals("This will be implemented by a transformer", dead.getCause().getMessage(),
				"premise: untransformed, every Forge mod naming ForgeCapabilities dies in its class initialiser");

		byte[] holder = classes.get(CAPABILITIES.replace('.', '/'));
		assertSame(holder, InjectorExecution.transform(injector, CAPABILITIES, holder, EnvType.SERVER),
				"ForgeCapabilities extends Object: passed by its header");
		assertEquals(List.of("AFTER " + TOKEN + " classloading", "AFTER " + CAPABILITIES.replace('.', '/') + "$1 classloading",
				"AFTER " + CAPABILITIES.replace('.', '/') + "$2 classloading"), calls(game));
	}

	/** The plugin throws on a token without a type argument: the class passes through, and the throw stays inside. */
	@ExpectCaught("could not process capability token fixture.RawToken")
	@Test void aTokenThePluginCannotReadPassesThroughUnchanged(@TempDir Path work) throws Throwable {
		Map<String, byte[]> classes = InjectorExecution.compile(work, SOURCES);
		ClassLoader game = InjectorExecution.load(classes);
		ForgeCapabilityTokenInjector injector = ForgeCapabilityTokenInjector.create(game);
		byte[] raw = classes.get("fixture/RawToken");
		assertSame(raw, InjectorExecution.transform(injector, "fixture.RawToken", raw, EnvType.SERVER));
		assertEquals(List.of("AFTER fixture/RawToken classloading"), calls(game));
	}

	@ExpectCaught("no MinecraftForge CapabilityTokenSubclass plugin")
	@Test void nothingInstallsWithoutMinecraftForgesPlugin() {
		assertNull(ForgeCapabilityTokenInjector.create(getClass().getClassLoader()));
	}

	private static List<?> calls(ClassLoader game) throws ReflectiveOperationException {
		return List.copyOf((List<?>) InjectorExecution.getStatic(game.loadClass(PLUGIN), "calls"));
	}
}
