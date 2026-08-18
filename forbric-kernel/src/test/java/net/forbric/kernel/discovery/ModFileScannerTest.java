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

package net.forbric.kernel.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.lang.annotation.ElementType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * The member-name SHAPES the annotation index records.
 *
 * <p>These are the whole contract. FML builds this index from bytecode and its consumers take the strings literally
 * — JEI's {@code ForgePluginFinder} reads {@code annotationType()} and {@code memberName()} and calls
 * {@code Class.forName(memberName)} with no normalisation, and Jade and JourneyMap do the same. A wrong shape is
 * invisible right up until a mod's plugin system finds nothing, which is why each shape gets its own assertion
 * rather than one round-trip test.
 *
 * <p>The NeoForge SPI is not on the test classpath, so these cover the ASM half ({@link ModFileScanner#collect})
 * plus the SPI-absent contract of {@link ModFileScanner#scan}.
 */
class ModFileScannerTest {
	/** Not runtime-visible — the norm for this index. {@code @JeiPlugin} is exactly this. */
	private static final String INVISIBLE = "Lmezz/jei/api/JeiPlugin;";
	private static final String VISIBLE = "Lnet/neoforged/fml/common/EventBusSubscriber;";

	@Test
	void typeAnnotationsCarryTheDottedBinaryName(@TempDir Path dir) throws Exception {
		List<ModFileScanner.Found> found = collect(dir, zip -> {
			ClassWriter cw = new ClassWriter(0);
			cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "dev/gigaherz/toolbelt/integration/JeiPlugin", null,
					"java/lang/Object", null);
			cw.visitAnnotation(INVISIBLE, false).visitEnd();
			cw.visitEnd();
			write(zip, "dev/gigaherz/toolbelt/integration/JeiPlugin.class", cw.toByteArray());
		});

		assertEquals(1, found.size());
		ModFileScanner.Found f = found.get(0);
		assertEquals(ElementType.TYPE, f.target());
		// The bug this test exists for: the slashed internal name went straight into Class.forName and died.
		assertEquals("dev.gigaherz.toolbelt.integration.JeiPlugin", f.memberName());
		assertEquals(Type.getType(INVISIBLE), Type.getType(f.annotationDesc()));
	}

	@Test
	void methodAnnotationsCarryNamePlusDescriptorSoOverloadsStaySeparate(@TempDir Path dir) throws Exception {
		List<ModFileScanner.Found> found = collect(dir, zip -> {
			ClassWriter cw = new ClassWriter(0);
			cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/Handlers", null, "java/lang/Object", null);
			MethodVisitor a = cw.visitMethod(Opcodes.ACC_PUBLIC, "on", "(Ljava/lang/String;)V", null, null);
			a.visitAnnotation(VISIBLE, true).visitEnd();
			a.visitEnd();
			MethodVisitor b = cw.visitMethod(Opcodes.ACC_PUBLIC, "on", "(I)V", null, null);
			b.visitAnnotation(VISIBLE, true).visitEnd();
			b.visitEnd();
			cw.visitEnd();
			write(zip, "com/example/Handlers.class", cw.toByteArray());
		});

		assertEquals(2, found.size());
		List<String> members = found.stream().map(ModFileScanner.Found::memberName).sorted().toList();
		assertEquals(List.of("on(I)V", "on(Ljava/lang/String;)V"), members);
		assertTrue(found.stream().allMatch(f -> f.target() == ElementType.METHOD));
	}

	@Test
	void fieldAnnotationsCarryTheBareFieldName(@TempDir Path dir) throws Exception {
		List<ModFileScanner.Found> found = collect(dir, zip -> {
			ClassWriter cw = new ClassWriter(0);
			cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/Holder", null, "java/lang/Object", null);
			FieldVisitor fv = cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "INSTANCE",
					"Ljava/lang/Object;", null, null);
			fv.visitAnnotation(INVISIBLE, false).visitEnd();
			fv.visitEnd();
			cw.visitEnd();
			write(zip, "com/example/Holder.class", cw.toByteArray());
		});

		assertEquals(1, found.size());
		assertEquals(ElementType.FIELD, found.get(0).target());
		assertEquals("INSTANCE", found.get(0).memberName());
	}

	@Test
	void invisibleAndVisibleAnnotationsAreBothCollected(@TempDir Path dir) throws Exception {
		List<ModFileScanner.Found> found = collect(dir, zip -> {
			ClassWriter cw = new ClassWriter(0);
			cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/Both", null, "java/lang/Object", null);
			cw.visitAnnotation(VISIBLE, true).visitEnd();
			cw.visitAnnotation(INVISIBLE, false).visitEnd();
			cw.visitEnd();
			write(zip, "com/example/Both.class", cw.toByteArray());
		});

		assertEquals(2, found.size());
		assertTrue(found.stream().allMatch(f -> f.memberName().equals("com.example.Both")));
	}

	@Test
	void annotationValuesAreCaptured(@TempDir Path dir) throws Exception {
		List<ModFileScanner.Found> found = collect(dir, zip -> {
			ClassWriter cw = new ClassWriter(0);
			cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/Valued", null, "java/lang/Object", null);
			AnnotationVisitor av = cw.visitAnnotation(VISIBLE, true);
			av.visit("modid", "example");
			AnnotationVisitor arr = av.visitArray("targets");
			arr.visit(null, "a");
			arr.visit(null, "b");
			arr.visitEnd();
			av.visitEnd();
			cw.visitEnd();
			write(zip, "com/example/Valued.class", cw.toByteArray());
		});

		assertEquals(1, found.size());
		assertEquals("example", found.get(0).values().get("modid"));
		assertEquals(List.of("a", "b"), found.get(0).values().get("targets"));
	}

	@Test
	void aNullSuperclassStaysNullTheWayFmlRecordsIt(@TempDir Path dir) throws Exception {
		Path jar = dir.resolve("mod.jar");
		try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
			// java/lang/Object itself: the one class with no superclass.
			ClassWriter root = new ClassWriter(0);
			root.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "java/lang/Object", null, null, null);
			root.visitEnd();
			write(zip, "java/lang/Object.class", root.toByteArray());

			ClassWriter child = new ClassWriter(0);
			child.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/Child", null, "com/example/Parent",
					new String[] {"com/example/Iface"});
			child.visitEnd();
			write(zip, "com/example/Child.class", child.toByteArray());
		}

		List<Object[]> classes = new ArrayList<>();
		ModFileScanner.collect(jar, new ArrayList<>(), classes);

		assertEquals(2, classes.size());
		Object[] root = classes.stream()
				.filter(c -> c[0].equals(Type.getObjectType("java/lang/Object"))).findFirst().orElseThrow();
		assertNull(root[1], "a null superclass must stay null, not become java/lang/Object");

		Object[] child = classes.stream()
				.filter(c -> c[0].equals(Type.getObjectType("com/example/Child"))).findFirst().orElseThrow();
		assertEquals(Type.getObjectType("com/example/Parent"), child[1]);
		assertEquals(java.util.Set.of(Type.getObjectType("com/example/Iface")), child[2]);
	}

	@Test
	void moduleAndPackageInfoAreSkipped(@TempDir Path dir) throws Exception {
		Path jar = dir.resolve("mod.jar");
		try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
			ClassWriter pkg = new ClassWriter(0);
			pkg.visit(Opcodes.V17, Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, "com/example/package-info", null,
					"java/lang/Object", null);
			pkg.visitAnnotation(VISIBLE, true).visitEnd();
			pkg.visitEnd();
			write(zip, "com/example/package-info.class", pkg.toByteArray());
		}

		List<ModFileScanner.Found> found = new ArrayList<>();
		List<Object[]> classes = new ArrayList<>();
		ModFileScanner.collect(jar, found, classes);

		assertEquals(0, found.size());
		assertEquals(0, classes.size());
	}

	@Test
	void oneUnreadableClassDoesNotCostTheJarItsIndex(@TempDir Path dir) throws Exception {
		Path jar = dir.resolve("mod.jar");
		try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
			write(zip, "com/example/Broken.class", new byte[] {1, 2, 3, 4});
			ClassWriter cw = new ClassWriter(0);
			cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/Fine", null, "java/lang/Object", null);
			cw.visitAnnotation(INVISIBLE, false).visitEnd();
			cw.visitEnd();
			write(zip, "com/example/Fine.class", cw.toByteArray());
		}

		List<ModFileScanner.Found> found = new ArrayList<>();
		ModFileScanner.collect(jar, found, new ArrayList<>());

		assertEquals(1, found.size());
		assertEquals("com.example.Fine", found.get(0).memberName());
	}

	@Test
	void scanReturnsNullRatherThanThrowingWhenTheSpiIsAbsent(@TempDir Path dir) throws Exception {
		// This classloader has no net.neoforged.* at all. The caller falls back to an empty index; it must not die.
		Path jar = dir.resolve("mod.jar");
		try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
			ClassWriter cw = new ClassWriter(0);
			cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/Any", null, "java/lang/Object", null);
			cw.visitEnd();
			write(zip, "com/example/Any.class", cw.toByteArray());
		}

		assertNull(ModFileScanner.scan(jar, getClass().getClassLoader()));
		assertNull(ModFileScanner.scan(Files.writeString(dir.resolve("nope.jar"), "not a zip"),
				getClass().getClassLoader()));
	}

	private interface JarBody {
		void fill(ZipOutputStream zip) throws Exception;
	}

	private static List<ModFileScanner.Found> collect(Path dir, JarBody body) throws Exception {
		Path jar = dir.resolve("mod.jar");
		try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
			body.fill(zip);
		}
		List<ModFileScanner.Found> found = new ArrayList<>();
		ModFileScanner.collect(jar, found, new ArrayList<>());
		assertNotNull(found);
		return found;
	}

	private static void write(ZipOutputStream zip, String name, byte[] bytes) throws Exception {
		zip.putNextEntry(new ZipEntry(name));
		zip.write(bytes);
		zip.closeEntry();
	}
}
