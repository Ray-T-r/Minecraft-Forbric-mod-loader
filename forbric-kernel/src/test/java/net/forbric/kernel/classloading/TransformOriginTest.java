/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.classloading;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.nio.file.Files;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import net.fabricmc.api.EnvType;
import net.forbric.api.DiscoveredMod;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.transform.TransformContext;

class TransformOriginTest {
	@TempDir Path root;
	private Path jar(String file) throws Exception {
		Path path = root.resolve(file);
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "unrelated/shared/Guest", null, "java/lang/Object", null);
		writer.visitEnd();
		try (var zip = new JarOutputStream(Files.newOutputStream(path))) {
			zip.putNextEntry(new JarEntry("unrelated/shared/Guest.class"));
			zip.write(writer.toByteArray()); zip.closeEntry();
		}
		return path;
	}
	private static DiscoveredMod mod(Path jar, String id, Ecosystem ecosystem) {
		return new DiscoveredMod(ecosystem, id, "1", id, List.of(), List.of(), null, jar.toString());
	}
	@Test void inspectionAndDefinitionUseTheWinningResourceRatherThanItsPackage() throws Exception {
		Path first = jar("first with spaces.jar"), second = jar("second.jar");
		try (var loader = new ForbricClassLoader(new URL[]{first.toUri().toURL(), second.toUri().toURL()}, getClass().getClassLoader())) {
			loader.setModOrigins(List.of(mod(first, "new_mod", Ecosystem.FABRIC), mod(second, "other_mod", Ecosystem.NEOFORGE)));
			TransformContext base = new TransformContext(EnvType.CLIENT, false, "named");
			List<TransformContext> seen = new ArrayList<>();
			loader.setTransformer((name, bytes) -> { seen.add(loader.contextFor(name, base)); return bytes; });
			assertNotNull(loader.getPreMixinClassBytes("unrelated.shared.Guest"));
			assertSame(loader, loader.loadClass("unrelated.shared.Guest").getClassLoader());
			assertFalse(seen.isEmpty());
			for (TransformContext context : seen) {
				assertEquals(Ecosystem.FABRIC, context.getEcosystem());
				assertEquals("new_mod", context.getSourceModId());
			}
			assertNull(loader.contextFor("java.lang.Object", base).getEcosystem());
		}
	}
	@Test void sharedOriginsDoNotInventAModOrEcosystem() throws Exception {
		Path jar = jar("shared.jar");
		try (var loader = new ForbricClassLoader(new URL[]{jar.toUri().toURL()}, getClass().getClassLoader())) {
			loader.setModOrigins(List.of(mod(jar, "first", Ecosystem.FABRIC), mod(jar, "second", Ecosystem.FABRIC)));
			assertEquals(Ecosystem.FABRIC, loader.ecosystemOfResource("unrelated.shared.Guest"));
			assertNull(loader.originOfResource("unrelated.shared.Guest").modId());
			loader.setModOrigins(List.of(mod(jar, "first", Ecosystem.FABRIC), mod(jar, "second", Ecosystem.NEOFORGE)));
			assertNull(loader.ecosystemOfResource("unrelated.shared.Guest"));
		}
	}
	@Test void changedMetadataBeforeDefinitionInvalidatesTheInspectionCache() throws Exception {
		Path jar = jar("origin-changed.jar");
		try (var loader = new ForbricClassLoader(new URL[]{jar.toUri().toURL()}, getClass().getClassLoader())) {
			TransformContext base = new TransformContext(EnvType.CLIENT, false, "named");
			List<TransformContext> seen = new ArrayList<>();
			loader.setTransformer((name, bytes) -> { seen.add(loader.contextFor(name, base)); return bytes; });
			loader.setModOrigins(List.of(mod(jar, "original", Ecosystem.FABRIC)));
			assertNotNull(loader.getPreMixinClassBytes("unrelated.shared.Guest"));
			loader.setModOrigins(List.of(mod(jar, "selected", Ecosystem.NEOFORGE)));
			assertNotNull(loader.getPreMixinClassBytes("unrelated.shared.Guest"));
			assertEquals(2, seen.size());
			assertEquals(Ecosystem.FABRIC, seen.getFirst().getEcosystem());
			assertEquals(Ecosystem.NEOFORGE, seen.getLast().getEcosystem());
			assertEquals("selected", seen.getLast().getSourceModId());
		}
	}
}
