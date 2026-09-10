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

package net.forbric.loader.impl.forge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Scans a Forge/NeoForge mod jar with ASM for classes carrying {@code @Mod}, so Forbric can bring up each
 * mod entry without the mod's main class being named by hand. A single jar may declare several {@code @Mod}
 * classes; all are returned, in deterministic (class-name) order.
 *
 * <p>The {@code @Mod} annotation is matched by its bytecode descriptor (no compile-time dependency on the
 * NeoForge class) — the real {@code net.neoforged.fml.common.Mod} comes from the runtime-supplied NeoForge jar.
 */
public final class ModAnnotationScanner {
	/** A discovered {@code @Mod} class and its declared mod id. */
	public static final class ModClassInfo {
		public final String className; // binary (dot-separated) name
		public final String modId;     // @Mod value, or null if absent

		ModClassInfo(String className, String modId) {
			this.className = className;
			this.modId = modId;
		}

		@Override
		public String toString() {
			return className + (modId == null ? "" : " (@Mod \"" + modId + "\")");
		}
	}

	/** Traditional MinecraftForge {@code @Mod} descriptor (the primary target). */
	public static final String MOD_DESC_MINECRAFTFORGE = "Lnet/minecraftforge/fml/common/Mod;";
	/** NeoForge {@code @Mod} descriptor (the parked NeoForge path). */
	public static final String MOD_DESC_NEOFORGE = "Lnet/neoforged/fml/common/Mod;";
	/** Both Forge-family {@code @Mod} annotations share the same shape ({@code String value()}); recognise either. */
	private static final java.util.Set<String> MOD_DESCRIPTORS =
			java.util.Set.of(MOD_DESC_MINECRAFTFORGE, MOD_DESC_NEOFORGE);

	private ModAnnotationScanner() {
	}

	public static List<ModClassInfo> scan(Path jar) throws IOException {
		List<ModClassInfo> found = new ArrayList<>();

		try (FileSystem fs = FileSystems.newFileSystem(jar, (ClassLoader) null)) {
			for (Path root : fs.getRootDirectories()) {
				try (Stream<Path> walk = Files.walk(root)) {
					for (Path entry : (Iterable<Path>) walk::iterator) {
						if (!entry.toString().endsWith(".class")) continue;
						if (!Files.isRegularFile(entry)) continue;

						ModClassInfo info = scanClass(entry);
						if (info != null) found.add(info);
					}
				}
			}
		}

		found.sort(Comparator.comparing(i -> i.className));
		return found;
	}

	private static ModClassInfo scanClass(Path classFile) throws IOException {
		byte[] bytes;
		try (InputStream in = Files.newInputStream(classFile)) {
			bytes = in.readAllBytes();
		}

		ModCollector collector = new ModCollector();
		new ClassReader(bytes).accept(collector, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		return collector.isMod ? new ModClassInfo(collector.className, collector.modId) : null;
	}

	private static final class ModCollector extends ClassVisitor {
		String className;
		String modId;
		boolean isMod;

		ModCollector() {
			super(Opcodes.ASM9);
		}

		@Override
		public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
			this.className = name.replace('/', '.');
		}

		@Override
		public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
			if (!MOD_DESCRIPTORS.contains(descriptor)) return null;

			isMod = true;
			return new AnnotationVisitor(Opcodes.ASM9) {
				@Override
				public void visit(String name, Object value) {
					if ("value".equals(name) && value instanceof String) modId = (String) value;
				}
			};
		}
	}
}
