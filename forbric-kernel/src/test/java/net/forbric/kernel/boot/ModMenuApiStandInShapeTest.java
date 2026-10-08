/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;
import net.forbric.kernel.interop.protocol.modmenu.ModMenuApiStandIn;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.TestFixtures;

/**
 * The Mod Menu API stand-in has exactly the public shape of Mod Menu 20.0.3's API package.
 *
 * <p>Mods are compiled against the real API and link against whatever is defined. A member that differs in name,
 * descriptor, static-ness or default-ness is a {@code NoSuchMethodError} or {@code AbstractMethodError} in a mod's
 * code the first time a player presses Config — and nothing before that would say so. The expected table below was
 * read with {@code javap} off {@code modmenu-20.0.3.jar} (Modrinth, the latest build for 26.2; SHA-1
 * 9fc381627b471c18d67156e846228f51ff2ea5b4). Each member is written {@code kind name descriptor [signature]}, where
 * kind is {@code static}, {@code default}, {@code abstract} or {@code field}.
 */
class ModMenuApiStandInShapeTest {
	private static final Path CLASSES = Path.of("build/classes/java/modMenuApi");
	private static final String API = "com/terraformersmc/modmenu/api/";

	private static final Map<String, Set<String>> MOD_MENU_20_0_3 = Map.of(
			"ModMenuApi", Set.of(
					"static createModsScreen (Lnet/minecraft/client/gui/screens/Screen;)Lnet/minecraft/client/gui/screens/Screen;",
					"static createModsButtonText ()Lnet/minecraft/network/chat/Component;",
					"default getModConfigScreenFactory ()Lcom/terraformersmc/modmenu/api/ConfigScreenFactory; "
							+ "()Lcom/terraformersmc/modmenu/api/ConfigScreenFactory<*>;",
					"default getUpdateChecker ()Lcom/terraformersmc/modmenu/api/UpdateChecker;",
					"default getProvidedConfigScreenFactories ()Ljava/util/Map; "
							+ "()Ljava/util/Map<Ljava/lang/String;Lcom/terraformersmc/modmenu/api/ConfigScreenFactory<*>;>;",
					"default getProvidedUpdateCheckers ()Ljava/util/Map; "
							+ "()Ljava/util/Map<Ljava/lang/String;Lcom/terraformersmc/modmenu/api/UpdateChecker;>;",
					"default attachModpackBadges (Ljava/util/function/Consumer;)V "
							+ "(Ljava/util/function/Consumer<Ljava/lang/String;>;)V"),
			"ConfigScreenFactory", Set.of(
					"abstract create (Lnet/minecraft/client/gui/screens/Screen;)Lnet/minecraft/client/gui/screens/Screen; "
							+ "(Lnet/minecraft/client/gui/screens/Screen;)TS;"),
			"UpdateChecker", Set.of(
					"abstract checkForUpdates ()Lcom/terraformersmc/modmenu/api/UpdateInfo;"),
			"UpdateInfo", Set.of(
					"abstract isUpdateAvailable ()Z",
					"default getUpdateMessage ()Lnet/minecraft/network/chat/Component;",
					"abstract getDownloadLink ()Ljava/lang/String;",
					"abstract getUpdateChannel ()Lcom/terraformersmc/modmenu/api/UpdateChannel;"),
			"UpdateChannel", Set.of(
					"field ALPHA Lcom/terraformersmc/modmenu/api/UpdateChannel;",
					"field BETA Lcom/terraformersmc/modmenu/api/UpdateChannel;",
					"field RELEASE Lcom/terraformersmc/modmenu/api/UpdateChannel;",
					"static values ()[Lcom/terraformersmc/modmenu/api/UpdateChannel;",
					"static valueOf (Ljava/lang/String;)Lcom/terraformersmc/modmenu/api/UpdateChannel;",
					"static getUserPreference ()Lcom/terraformersmc/modmenu/api/UpdateChannel;"));

	/** Class-level facts: interface or enum, and the one class signature the API declares. */
	private static final Map<String, String> KIND = Map.of(
			"ModMenuApi", "interface null",
			"ConfigScreenFactory", "interface <S:Lnet/minecraft/client/gui/screens/Screen;>Ljava/lang/Object;",
			"UpdateChecker", "interface null",
			"UpdateInfo", "interface null",
			"UpdateChannel", "enum Ljava/lang/Enum<Lcom/terraformersmc/modmenu/api/UpdateChannel;>;");

	/**
	 * Mod Menu's {@code NullScreenFactory}: not API, but what the API's default returns, and the class a reader's
	 * {@code instanceof} recognises as "no config screen". Same shape as Mod Menu 20.0.3's.
	 */
	@Test void theNullScreenFactoryMatchesModMenu() throws Exception {
		ClassNode node = readInternal(ModMenuApiStandIn.NULL_FACTORY);
		assertEquals("class <S:Lnet/minecraft/client/gui/screens/Screen;>Ljava/lang/Object;Lcom/terraformersmc/modmenu/api/ConfigScreenFactory<TS;>;",
				((node.access & Opcodes.ACC_INTERFACE) != 0 ? "interface" : "class") + " " + node.signature);
		assertEquals(List.of("com/terraformersmc/modmenu/api/ConfigScreenFactory"), node.interfaces);
		assertEquals(new TreeSet<>(Set.of("default create (Lnet/minecraft/client/gui/screens/Screen;)Lnet/minecraft/client/gui/screens/Screen; "
				+ "(Lnet/minecraft/client/gui/screens/Screen;)TS;")), publicMembers(node));
		assertTrue(node.methods.stream().anyMatch(m -> m.name.equals("<init>") && m.desc.equals("()V")
				&& (m.access & Opcodes.ACC_PUBLIC) != 0), "a public no-argument constructor, as Mod Menu's");
	}

	@Test void everyPublicMemberMatchesModMenu() throws Exception {
		for (var expected : MOD_MENU_20_0_3.entrySet()) {
			ClassNode node = read(expected.getKey());
			assertEquals(new TreeSet<>(expected.getValue()), publicMembers(node), expected.getKey());
		}
	}

	@Test void everyTypeIsTheSameKindOfType() throws Exception {
		for (var expected : KIND.entrySet()) {
			ClassNode node = read(expected.getKey());
			assertTrue((node.access & Opcodes.ACC_PUBLIC) != 0, expected.getKey());
			String kind = (node.access & Opcodes.ACC_ENUM) != 0 ? "enum"
					: (node.access & Opcodes.ACC_INTERFACE) != 0 ? "interface" : "class";
			assertEquals(expected.getValue(), kind + " " + node.signature, expected.getKey());
		}
	}

	/** The stand-in is all of the API package and nothing outside it, so the jar's resource list is the whole of it. */
	@Test void theStandInIsTheWholeApiPackageAndOnlyIt() throws Exception {
		TestFixtures.requireFiles(TestFixtures.Fixture.GAME_SIDE, "the Mod Menu API stand-in is compiled before the tests",
				CLASSES.resolve(API + "ModMenuApi.class"));
		Set<String> compiled = new TreeSet<>();
		try (var files = Files.walk(CLASSES)) {
			files.filter(Files::isRegularFile)
					.forEach(file -> compiled.add(CLASSES.relativize(file).toString().replace('\\', '/')));
		}
		Set<String> expected = new TreeSet<>();
		for (String name : ModMenuApiStandIn.CLASSES) expected.add(name + ".class");
		assertEquals(expected, compiled, "a nested or synthetic class here would be shipped but never offered");
		for (String name : ModMenuApiStandIn.CLASSES) {
			assertTrue(name.startsWith(API) || name.equals(ModMenuApiStandIn.NULL_FACTORY), name);
		}
	}

	private static ClassNode read(String simpleName) throws Exception {
		return readInternal(API + simpleName);
	}

	private static ClassNode readInternal(String internalName) throws Exception {
		Path file = CLASSES.resolve(internalName + ".class");
		TestFixtures.requireFiles(TestFixtures.Fixture.GAME_SIDE, "the Mod Menu API stand-in is compiled before the tests", file);
		ClassNode node = new ClassNode();
		new ClassReader(Files.readAllBytes(file)).accept(node, ClassReader.SKIP_CODE);
		return node;
	}

	private static Set<String> publicMembers(ClassNode node) {
		Set<String> members = new TreeSet<>();
		for (FieldNode field : node.fields) {
			if ((field.access & Opcodes.ACC_PUBLIC) == 0 || (field.access & Opcodes.ACC_SYNTHETIC) != 0) continue;
			members.add("field " + field.name + " " + field.desc);
		}
		for (MethodNode method : node.methods) {
			if ((method.access & Opcodes.ACC_PUBLIC) == 0 || (method.access & Opcodes.ACC_SYNTHETIC) != 0) continue;
			if (method.name.startsWith("<")) continue;
			String kind = (method.access & Opcodes.ACC_STATIC) != 0 ? "static"
					: (method.access & Opcodes.ACC_ABSTRACT) != 0 ? "abstract" : "default";
			members.add(kind + " " + method.name + " " + method.desc + (method.signature == null ? "" : " " + method.signature));
		}
		return members;
	}
}
