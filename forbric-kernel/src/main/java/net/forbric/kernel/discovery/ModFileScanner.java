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

import java.io.InputStream;
import java.lang.annotation.ElementType;
import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import net.forbric.kernel.util.ForbricLog;

/**
 * Builds a NeoForge {@code ModFileScanData} for a mod jar — the annotation index FML hands to mods, and the only
 * way several of them find their own extensions.
 *
 * <p>The kernel constructs {@code @Mod} classes from its own targeted scan ({@link ModAnnotationScanner}) and never
 * needed a full index, so {@code IModFile.getScanResult()} returned an EMPTY one. That is invisible right up until
 * a mod asks: JEI's {@code ForgePluginFinder} walks {@code ModList.getAllScanData()} for {@code @JeiPlugin} and,
 * finding none, threw {@code IllegalArgumentException: plugins must not be empty} out of its own {@code @Mod}
 * constructor. Jade and Sophisticated Core discover their plugins the same way, and Sodium finds third-party config
 * entry points through it too — those fail silently, which is worse.
 *
 * <p>Scanning is per jar and LAZY: nothing walks a jar until something actually calls {@code getScanResult()}, so
 * an instance whose mods never ask pays nothing. Class bodies are skipped ({@code SKIP_CODE | SKIP_DEBUG |
 * SKIP_FRAMES}) — only the annotation tables are read.
 *
 * <p><b>Both visible and INVISIBLE annotations</b>, on classes, methods and fields. The index is built from
 * bytecode, not reflection, so {@code CLASS}-retention annotations are just as readable — and they are the norm
 * here: {@code @JeiPlugin} is {@code RuntimeInvisible}, so a visible-only scan finds zero JEI plugins and JEI dies
 * with "plugins must not be empty" exactly as it did with no index at all.
 */
public final class ModFileScanner {
	private ModFileScanner() {
	}

	/**
	 * One annotation occurrence, in the shape {@code ModFileScanData.AnnotationData} takes. Package-private so the
	 * tests can assert the member-name SHAPES without a live NeoForge SPI on the classpath — the shapes are the whole
	 * contract here, and getting one wrong is invisible until a mod's plugin system quietly finds nothing.
	 */
	record Found(String annotationDesc, ElementType target, String ownerInternalName, String memberName,
			Map<String, Object> values) {
	}

	/**
	 * Scans {@code jar} and returns a populated {@code ModFileScanData}, or null if one cannot be built.
	 *
	 * @param gameLoader the loader holding the NeoForge SPI — all game types are reached reflectively, as everywhere
	 *                   on the kernel's boot side
	 */
	public static Object scan(Path jar, ClassLoader gameLoader) {
		try {
			Class<?> scanDataCls = Class.forName(
					"net.neoforged.neoforgespi.language.ModFileScanData", false, gameLoader);
			Class<?> annotationDataCls = Class.forName(
					"net.neoforged.neoforgespi.language.ModFileScanData$AnnotationData", false, gameLoader);
			Class<?> classDataCls = Class.forName(
					"net.neoforged.neoforgespi.language.ModFileScanData$ClassData", false, gameLoader);

			List<Found> found = new ArrayList<>();
			List<Object[]> classes = new ArrayList<>();
			collect(jar, found, classes);
			if (found.isEmpty() && classes.isEmpty()) return scanDataCls.getConstructor().newInstance();

			Object scanData = scanDataCls.getConstructor().newInstance();
			@SuppressWarnings("unchecked")
			Set<Object> annotations = (Set<Object>) scanDataCls.getMethod("getAnnotations").invoke(scanData);
			@SuppressWarnings("unchecked")
			Set<Object> classSet = (Set<Object>) scanDataCls.getMethod("getClasses").invoke(scanData);

			Constructor<?> annotationCtor = annotationDataCls.getConstructor(
					Type.class, ElementType.class, Type.class, String.class, Map.class);
			for (Found f : found) {
				annotations.add(annotationCtor.newInstance(
						Type.getType(f.annotationDesc()), f.target(),
						Type.getObjectType(f.ownerInternalName()), f.memberName(), f.values()));
			}
			Constructor<?> classCtor = classDataCls.getConstructor(Type.class, Type.class, Set.class);
			for (Object[] c : classes) {
				classSet.add(classCtor.newInstance(c[0], c[1], c[2]));
			}

			ForbricLog.debug("[Forbric/Scan] %s: %d annotation(s) over %d class(es)", jar.getFileName(),
					found.size(), classes.size());
			return scanData;
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/Scan] could not scan %s: %s", jar.getFileName(), String.valueOf(t));
			return null;
		}
	}

	/**
	 * The ASM pass on its own — every annotation occurrence and every class triple in {@code jar}. Package-private:
	 * this half needs no game classes, so it is the half the tests can reach.
	 */
	static void collect(Path jar, List<Found> found, List<Object[]> classes) throws Exception {
		try (JarFile zip = new JarFile(jar.toFile())) {
			for (var entries = zip.entries(); entries.hasMoreElements();) {
				ZipEntry entry = entries.nextElement();
				String name = entry.getName();
				if (entry.isDirectory() || !name.endsWith(".class")) continue;
				// module-info/package-info carry no members anything looks up, and module-info is not a normal class.
				if (name.endsWith("module-info.class") || name.endsWith("package-info.class")) continue;

				try (InputStream in = zip.getInputStream(entry)) {
					new ClassReader(in).accept(new Collector(found, classes),
							ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
				} catch (Throwable perClass) {
					// One unreadable class (a newer class-file version, a shaded oddity) must not cost the jar its
					// whole index — the mods that read it would then silently find nothing.
					ForbricLog.debug("[Forbric/Scan] skipping %s in %s: %s", name, jar.getFileName(),
							String.valueOf(perClass));
				}
			}
		}
	}

	/** Records every annotation, visible or not, on the class and on each member, plus the class/super/interfaces triple. */
	private static final class Collector extends ClassVisitor {
		private final List<Found> found;
		private final List<Object[]> classes;
		private String internalName;

		Collector(List<Found> found, List<Object[]> classes) {
			super(Opcodes.ASM9);
			this.found = found;
			this.classes = classes;
		}

		@Override
		public void visit(int version, int access, String name, String signature, String superName,
				String[] interfaces) {
			this.internalName = name;
			Set<Type> parents = new LinkedHashSet<>();
			if (interfaces != null) {
				for (String i : interfaces) parents.add(Type.getObjectType(i));
			}
			// A null superclass stays null, the way FML records it. Only java/lang/Object itself has none, but a
			// consumer comparing parent() against null has to get the same answer it would from genuine FML.
			classes.add(new Object[] {
					Type.getObjectType(name),
					superName == null ? null : Type.getObjectType(superName),
					parents});
		}

		@Override
		public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
			// FML records a TYPE annotation's member as the DOTTED binary name, and its consumers take that
			// literally: JEI's ForgePluginFinder reads only annotationType() and memberName(), then calls
			// Class.forName(memberName) with no normalisation at all. Handing it the slashed internal name found
			// every @JeiPlugin in the pack and then lost all nine to ClassNotFoundException, leaving JEI to throw
			// "plugins must not be empty" out of its own @Mod constructor. Jade and JourneyMap discover their
			// plugins the same way and failed the same way. FIELD and METHOD keep their own shapes below.
			return collect(descriptor, ElementType.TYPE, Type.getObjectType(internalName).getClassName());
		}

		@Override
		public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
			return new FieldVisitor(Opcodes.ASM9) {
				@Override
				public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
					return collect(desc, ElementType.FIELD, name);
				}
			};
		}

		@Override
		public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
				String[] exceptions) {
			// FML records a method as "<name><descriptor>" so an overload is distinguishable.
			String member = name + descriptor;
			return new MethodVisitor(Opcodes.ASM9) {
				@Override
				public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
					return collect(desc, ElementType.METHOD, member);
				}
			};
		}

		private AnnotationVisitor collect(String descriptor, ElementType target, String memberName) {
			Map<String, Object> values = new LinkedHashMap<>();
			found.add(new Found(descriptor, target, internalName, memberName, values));
			return new ValueCollector(values);
		}
	}

	/** Captures an annotation's members. Nested annotations are recorded as their own value maps. */
	private static final class ValueCollector extends AnnotationVisitor {
		private final Map<String, Object> values;

		ValueCollector(Map<String, Object> values) {
			super(Opcodes.ASM9);
			this.values = values;
		}

		@Override
		public void visit(String name, Object value) {
			values.put(name == null ? "value" : name, value);
		}

		@Override
		public void visitEnum(String name, String descriptor, String value) {
			// FML stores an enum member as the constant's simple name, which is what consumers compare against.
			values.put(name == null ? "value" : name, value);
		}

		@Override
		public AnnotationVisitor visitArray(String name) {
			List<Object> items = new ArrayList<>();
			values.put(name == null ? "value" : name, items);
			return new AnnotationVisitor(Opcodes.ASM9) {
				@Override
				public void visit(String ignored, Object value) {
					items.add(value);
				}

				@Override
				public void visitEnum(String ignored, String descriptor, String value) {
					items.add(value);
				}

				@Override
				public AnnotationVisitor visitAnnotation(String ignored, String descriptor) {
					Map<String, Object> nested = new LinkedHashMap<>();
					items.add(nested);
					return new ValueCollector(nested);
				}
			};
		}

		@Override
		public AnnotationVisitor visitAnnotation(String name, String descriptor) {
			Map<String, Object> nested = new LinkedHashMap<>();
			values.put(name == null ? "value" : name, nested);
			return new ValueCollector(nested);
		}
	}
}
