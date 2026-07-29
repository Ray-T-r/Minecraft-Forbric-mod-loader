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

package net.forbric.kernel.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Opcodes;

class ForgeModRemapperTest {
	// Same synthetic join as ForbricMappingsTest: named com.example.Foo -> obf a -> intermediary class_100.
	private static final String INTERMEDIARY_TINY =
			"v1\tofficial\tintermediary\nCLASS\ta\tnet/minecraft/class_100\n";
	private static final String MOJMAP_PROGUARD =
			"com.example.Foo -> a:\n";

	@Test
	void remapsAForgeStyleClassFromMojmapToIntermediary(@TempDir Path dir) throws Exception {
		Path inter = Files.write(dir.resolve("intermediary.tiny"), INTERMEDIARY_TINY.getBytes(StandardCharsets.UTF_8));
		Path moj = Files.write(dir.resolve("client.txt"), MOJMAP_PROGUARD.getBytes(StandardCharsets.UTF_8));
		ForbricMappings mappings = ForbricMappings.load(inter, moj);

		// A "Forge" mod class with a field typed by the Mojmap game name com/example/Foo.
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/mod/ForgeThing", null, "java/lang/Object", null);
		cw.visitField(Opcodes.ACC_PUBLIC, "game", "Lcom/example/Foo;", null, null).visitEnd();
		cw.visitEnd();

		Path input = dir.resolve("mod.jar");

		try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(input))) {
			zos.putNextEntry(new ZipEntry("com/example/mod/ForgeThing.class"));
			zos.write(cw.toByteArray());
			zos.closeEntry();
		}

		Path output = dir.resolve("mod-remapped.jar");
		ForgeModRemapper.remapJar(input, output, mappings, List.of());

		assertEquals("Lnet/minecraft/class_100;", fieldDesc(output, "com/example/mod/ForgeThing.class", "game"),
				"the Mojmap field type should be remapped to the intermediary runtime name");
	}

	private static String fieldDesc(Path jar, String entry, String fieldName) throws Exception {
		try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(jar))) {
			ZipEntry e;

			while ((e = zis.getNextEntry()) != null) {
				if (!e.getName().equals(entry)) continue;

				ClassReader reader = new ClassReader(zis.readAllBytes());
				String[] captured = new String[1];

				reader.accept(new ClassVisitor(Opcodes.ASM9) {
					@Override
					public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
						if (name.equals(fieldName)) captured[0] = descriptor;
						return null;
					}
				}, 0);

				return captured[0];
			}
		}

		return null;
	}
}
