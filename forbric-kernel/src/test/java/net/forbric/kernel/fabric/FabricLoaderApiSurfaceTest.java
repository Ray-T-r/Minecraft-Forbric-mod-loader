/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.TestFixtures;

/**
 * The kernel's own copy of Fabric Loader's API, held to Fabric Loader 0.19.3 itself: every public class of
 * {@code net.fabricmc.loader.api} and {@code net.fabricmc.api}, its supertypes, and every public or protected member
 * by name and descriptor. A mod compiled against Fabric Loader links against exactly these names. A class that is
 * not here fails the mod when the method naming it links, thrown or not: JEI's Fabric client lifecycle only catches
 * {@code EntrypointException}, and died on {@code NoClassDefFoundError} while the kernel did not have it. A missing
 * member fails at the call.
 *
 * <p>{@link #KNOWN} is what differs today and is accepted, and the comparison must match it exactly: a new
 * difference is red, and so is one closed without taking it off the list.
 */
class FabricLoaderApiSurfaceTest {
	private static final Path KERNEL = Path.of("build/classes/java/main");
	private static final List<String> PACKAGES = List.of("net/fabricmc/loader/api/", "net/fabricmc/api/");

	/**
	 * Accepted on 2026-10-22: none of 435 local mod jars, nested jars included, names any of these. The interval
	 * algebra and the operators' own test and bounds are how Fabric Loader decides predicates, made public; the
	 * kernel decides them itself. The exception's superclass is the deprecated pre-0.12 class, which the kernel does
	 * not carry at all.
	 */
	private static final Set<String> KNOWN = Set.of(
			"net/fabricmc/loader/api/VersionParsingException extends net/fabricmc/loader/util/version/VersionParsingException, here java/lang/Exception",
			"net/fabricmc/loader/api/metadata/version/VersionComparisonOperator#maxVersion(Lnet/fabricmc/loader/api/SemanticVersion;)Lnet/fabricmc/loader/api/SemanticVersion;",
			"net/fabricmc/loader/api/metadata/version/VersionComparisonOperator#minVersion(Lnet/fabricmc/loader/api/SemanticVersion;)Lnet/fabricmc/loader/api/SemanticVersion;",
			"net/fabricmc/loader/api/metadata/version/VersionComparisonOperator#test(Lnet/fabricmc/loader/api/SemanticVersion;Lnet/fabricmc/loader/api/SemanticVersion;)Z",
			"net/fabricmc/loader/api/metadata/version/VersionInterval#and(Lnet/fabricmc/loader/api/metadata/version/VersionInterval;)Lnet/fabricmc/loader/api/metadata/version/VersionInterval;",
			"net/fabricmc/loader/api/metadata/version/VersionInterval#not()Ljava/util/List;",
			"net/fabricmc/loader/api/metadata/version/VersionInterval#or(Ljava/util/Collection;)Ljava/util/List;",
			"net/fabricmc/loader/api/metadata/version/VersionInterval#static and(Ljava/util/Collection;Ljava/util/Collection;)Ljava/util/List;",
			"net/fabricmc/loader/api/metadata/version/VersionInterval#static and(Lnet/fabricmc/loader/api/metadata/version/VersionInterval;Lnet/fabricmc/loader/api/metadata/version/VersionInterval;)Lnet/fabricmc/loader/api/metadata/version/VersionInterval;",
			"net/fabricmc/loader/api/metadata/version/VersionInterval#static not(Ljava/util/Collection;)Ljava/util/List;",
			"net/fabricmc/loader/api/metadata/version/VersionInterval#static not(Lnet/fabricmc/loader/api/metadata/version/VersionInterval;)Ljava/util/List;",
			"net/fabricmc/loader/api/metadata/version/VersionInterval#static or(Ljava/util/Collection;Lnet/fabricmc/loader/api/metadata/version/VersionInterval;)Ljava/util/List;");

	@Test
	void theKernelsFabricLoaderApiIsFabricLoaders() throws IOException {
		Path jar = TestFixtures.fabricLoaderJar();
		TestFixtures.requireFiles(TestFixtures.Fixture.THIRD_PARTY, "Fabric Loader 0.19.3 in a local launcher's libraries", jar);
		Set<String> differences = new TreeSet<>();
		int classes = 0;
		try (ZipFile fabric = new ZipFile(jar.toFile())) {
			for (ZipEntry entry : fabric.stream().toList()) {
				String name = entry.getName();
				if (!name.endsWith(".class") || PACKAGES.stream().noneMatch(name::startsWith) || name.matches(".*\\$\\d.*")) continue;
				ClassNode theirs = read(fabric.getInputStream(entry).readAllBytes());
				if ((theirs.access & Opcodes.ACC_PUBLIC) == 0) continue;
				classes++;
				Path own = KERNEL.resolve(name);
				if (!Files.isRegularFile(own)) {
					differences.add("class " + theirs.name);
					continue;
				}
				compare(theirs, read(Files.readAllBytes(own)), differences);
			}
		}
		assertEquals(true, classes > 20, "Fabric Loader's API was not found in " + jar);
		assertEquals(KNOWN, differences, "the kernel's Fabric Loader API differs from Fabric Loader's");
	}

	private static void compare(ClassNode theirs, ClassNode ours, Set<String> differences) {
		if (!theirs.superName.equals(ours.superName)) {
			differences.add(theirs.name + " extends " + theirs.superName + ", here " + ours.superName);
		}
		if (!new TreeSet<>(theirs.interfaces).equals(new TreeSet<>(ours.interfaces))) {
			differences.add(theirs.name + " implements " + new TreeSet<>(theirs.interfaces) + ", here " + new TreeSet<>(ours.interfaces));
		}
		if ((theirs.access & Opcodes.ACC_INTERFACE) != (ours.access & Opcodes.ACC_INTERFACE)) {
			differences.add(theirs.name + " is " + kind(theirs) + ", here " + kind(ours));
		}
		Set<String> mine = members(ours);
		for (String member : members(theirs)) {
			if (!mine.contains(member)) differences.add(theirs.name + "#" + member);
		}
	}

	/** Public and protected methods and fields a mod can name, as name and descriptor; synthetic ones are the compiler's. */
	private static Set<String> members(ClassNode node) {
		Set<String> out = new TreeSet<>();
		for (MethodNode method : node.methods) {
			if (visible(method.access)) out.add(((method.access & Opcodes.ACC_STATIC) != 0 ? "static " : "") + method.name + method.desc);
		}
		for (FieldNode field : node.fields) {
			if (visible(field.access)) out.add(((field.access & Opcodes.ACC_STATIC) != 0 ? "static " : "") + field.name + ":" + field.desc);
		}
		return out;
	}

	private static boolean visible(int access) {
		return (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0 && (access & Opcodes.ACC_SYNTHETIC) == 0;
	}

	private static String kind(ClassNode node) {
		return (node.access & Opcodes.ACC_INTERFACE) != 0 ? "an interface" : "a class";
	}

	private static ClassNode read(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);
		return node;
	}
}
