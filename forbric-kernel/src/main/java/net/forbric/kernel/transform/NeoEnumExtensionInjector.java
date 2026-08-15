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

package net.forbric.kernel.transform;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Runs NeoForge's {@code RuntimeEnumExtender} over each loading game class, adding the enum constants the mods
 * declared (see {@code NeoEnumExtensions}, which loads the declarations this consumes).
 *
 * <p>The kernel supplies the two context records and the {@code ClassNode}; NeoForge's compiled processor does the
 * rewrite. {@code handlesClass} is a map lookup on the enum's internal name, so this is inert — one record
 * allocation and a hash lookup — for every class no mod extends, which is all but a handful.
 *
 * <p><b>Ordering.</b> Registered in {@code COREMOD} with no {@code predepends}: the target enums are vanilla types
 * nothing else in the chain edits, and the rewrite must land before the class is defined, which is the only real
 * constraint. Guest mixins run later and see the extended enum, which is what they would see on genuine NeoForge.
 *
 * <p>If any part of the SPI is missing the injector never installs ({@link #create} returns null) rather than
 * failing per class.
 */
public final class NeoEnumExtensionInjector implements ClassTransformer {
	private final Object extender;
	private final Method handlesClass;
	private final Method processClass;
	private final Constructor<?> selectionContext;
	private final Constructor<?> transformationContext;
	/** Audit sink and initial-hash supplier: NeoForge's processor only records through these; nothing reads them. */
	private static final BiConsumer<String, String[]> NO_AUDIT = (a, b) -> { };
	private static final Supplier<byte[]> NO_HASH = () -> new byte[0];

	private NeoEnumExtensionInjector(Object extender, Method handlesClass, Method processClass,
			Constructor<?> selectionContext, Constructor<?> transformationContext) {
		this.extender = extender;
		this.handlesClass = handlesClass;
		this.processClass = processClass;
		this.selectionContext = selectionContext;
		this.transformationContext = transformationContext;
	}

	/** @return an injector, or null if this runtime has no enumextension SPI (then nothing is registered). */
	public static NeoEnumExtensionInjector create(ClassLoader gameLoader) {
		try {
			Class<?> extenderCls = Class.forName(
					"net.neoforged.fml.common.asm.enumextension.RuntimeEnumExtender", false, gameLoader);
			Class<?> selection = Class.forName(
					"net.neoforged.neoforgespi.transformation.ClassProcessor$SelectionContext", false, gameLoader);
			Class<?> transformation = Class.forName(
					"net.neoforged.neoforgespi.transformation.ClassProcessor$TransformationContext", false, gameLoader);

			return new NeoEnumExtensionInjector(
					extenderCls.getConstructor().newInstance(),
					extenderCls.getMethod("handlesClass", selection),
					extenderCls.getMethod("processClass", transformation),
					selection.getConstructor(Type.class, boolean.class),
					transformation.getConstructor(Type.class, ClassNode.class, boolean.class,
							BiConsumer.class, Supplier.class));
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/EnumExt] no RuntimeEnumExtender SPI — enum extension injector not installed: %s",
					String.valueOf(t));
			return null;
		}
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		Type type = Type.getObjectType(className.replace('.', '/'));
		try {
			if (!(Boolean) handlesClass.invoke(extender, selectionContext.newInstance(type, false))) {
				return classBytes;
			}

			ClassNode node = new ClassNode();
			new ClassReader(classBytes).accept(node, 0);
			Object flags = processClass.invoke(extender,
					transformationContext.newInstance(type, node, false, NO_AUDIT, NO_HASH));

			int writerFlags = writerFlags(flags);
			if (writerFlags < 0) return classBytes; // NO_REWRITE — the processor declined after looking
			ClassWriter writer = new ClassWriter(writerFlags);
			node.accept(writer);
			ForbricLog.info("[Forbric/EnumExt] extended enum %s with the constants its mods declared", className);
			return writer.toByteArray();
		} catch (Throwable t) {
			// Leaving the enum alone is survivable — the mod that wanted the constant fails on its own lookup, with
			// its own name in the message. Failing the class definition here would take down unrelated code.
			ForbricLog.warn("[Forbric/EnumExt] could not extend " + className
					+ " — a mod's added constant will be missing", t);
			return classBytes;
		}
	}

	/** Maps {@code ComputeFlags} onto the ASM writer's, or -1 for {@code NO_REWRITE}. */
	static int writerFlags(Object computeFlags) {
		return switch (String.valueOf(computeFlags)) {
			case "NO_REWRITE" -> -1;
			case "SIMPLE_REWRITE" -> 0;
			case "COMPUTE_MAXS" -> ClassWriter.COMPUTE_MAXS;
			case "COMPUTE_FRAMES" -> ClassWriter.COMPUTE_FRAMES;
			// An unknown future value must not be read as "no rewrite" — the node HAS been edited by now, so the
			// safe reading is "write it back with the most conservative flags".
			default -> ClassWriter.COMPUTE_FRAMES;
		};
	}

	@Override
	public String name() {
		return "forbric:neo-enum-extension";
	}
}
