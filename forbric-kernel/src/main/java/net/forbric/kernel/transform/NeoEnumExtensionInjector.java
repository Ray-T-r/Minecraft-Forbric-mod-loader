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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.util.Caught;
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
					ForeignType.RUNTIME_ENUM_EXTENDER.binary(Ecosystem.NEOFORGE), false, gameLoader);
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
			Caught.warn(t, "[Forbric/EnumExt] no RuntimeEnumExtender SPI — enum extension injector not installed");
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
			List<FieldNode> unassigned = hideUnassignedConstants(node);
			int built = leadingConstants(node);
			Object flags = processClass.invoke(extender,
					transformationContext.newInstance(type, node, false, NO_AUDIT, NO_HASH));
			restoreAfterConstants(node, unassigned);

			int writerFlags = writerFlags(flags);
			if (writerFlags < 0) return classBytes; // NO_REWRITE — the processor declined after looking
			ClassWriter writer = new ClassWriter(writerFlags);
			node.accept(writer);
			ForbricLog.info("[Forbric/EnumExt] extended enum %s with the constants its mods declared", className);
			if (!unassigned.isEmpty()) {
				ForbricLog.info("[Forbric/EnumExt] %s: NeoForge's constants numbered after the %d constant(s) the "
						+ "enum builds, not after %s, which nothing assigns yet (another ecosystem's extension fills it "
						+ "later; -D%s=off counts it)", className, built, names(unassigned), UNASSIGNED_SWITCH);
			}
			return writer.toByteArray();
		} catch (Throwable t) {
			// Leaving the enum alone is survivable — the mod that wanted the constant fails on its own lookup, with
			// its own name in the message. Failing the class definition here would take down unrelated code.
			Caught.warn(t, "[Forbric/EnumExt] could not extend %s — a mod's added constant will be missing", className);
			return classBytes;
		}
	}

	/** {@code -Dforbric.enumExtensions.unassigned=off} hands NeoForge the enum with its unassigned constants in place. */
	static final String UNASSIGNED_SWITCH = "forbric.enumExtensions.unassigned";

	/** {@code public static final enum}: what NeoForge's {@code getVanillaEntryCount} requires of a counted field. */
	private static final int ENUM_CONSTANT = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM;

	/**
	 * Takes out of {@code node.fields} every enum-constant field that the class's {@code <clinit>} never assigns, and
	 * returns them in their order.
	 *
	 * <p><b>Why.</b> {@code RuntimeEnumExtender} numbers the constants it adds after the leading run of
	 * {@code public static final enum} fields of the enum's own type ({@code getVanillaEntryCount}, a
	 * {@code takeWhile} over {@code fields}) and stores each into {@code $values()}'s array at that ordinal — while
	 * the array itself grows only by the number it adds, from the array the class really builds. That holds on
	 * NeoForge, where every such field is a constant {@code <clinit>} constructs. Here another ecosystem may already
	 * have declared one that is filled in later: Fabric's class tweaker {@code extend-enum} adds a stub field straight
	 * after the existing constants (ACCESS phase, before this one), and the mod's enum mixin assigns it when Mixin
	 * runs. NeoForge counted the stub as built, numbered its own constants one too high and wrote the last one past
	 * the end of the array — {@code ArrayIndexOutOfBoundsException} in the enum's {@code <clinit>}, and nothing that
	 * touches the enum loads. Either ecosystem extending the enum alone was fine.
	 *
	 * <p>The test is structural, not the stub's attribute: a constant nothing in {@code <clinit>} assigns is not in
	 * the array {@code $values()} builds, whoever added it, so it must not be counted as if it were.
	 */
	static List<FieldNode> hideUnassignedConstants(ClassNode node) {
		if ("off".equalsIgnoreCase(System.getProperty(UNASSIGNED_SWITCH, "on"))) return List.of();
		String self = Type.getObjectType(node.name).getDescriptor();
		Set<String> assigned = new HashSet<>();
		for (MethodNode method : node.methods) {
			if (!"<clinit>".equals(method.name)) continue;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn.getOpcode() == Opcodes.PUTSTATIC && insn instanceof FieldInsnNode put
						&& put.owner.equals(node.name) && put.desc.equals(self)) {
					assigned.add(put.name);
				}
			}
		}
		List<FieldNode> unassigned = new ArrayList<>();
		for (FieldNode field : node.fields) {
			if ((field.access & ENUM_CONSTANT) == ENUM_CONSTANT && self.equals(field.desc)
					&& !assigned.contains(field.name)) {
				unassigned.add(field);
			}
		}
		node.fields.removeAll(unassigned);
		return unassigned;
	}

	/** The length of the leading run of enum constants, counted as NeoForge's {@code getVanillaEntryCount} does. */
	static int leadingConstants(ClassNode node) {
		String self = Type.getObjectType(node.name).getDescriptor();
		int count = 0;
		for (FieldNode field : node.fields) {
			if ((field.access & ENUM_CONSTANT) != ENUM_CONSTANT || !self.equals(field.desc)) break;
			count++;
		}
		return count;
	}

	/**
	 * Puts the hidden fields back straight after the (now longer) run of constants: where the class tweaker would
	 * have put them had NeoForge's constants already been there, and where a later pass — Mixin's enum merge, which
	 * skips a stub when it counts the target's constants — still finds them.
	 */
	static void restoreAfterConstants(ClassNode node, List<FieldNode> unassigned) {
		if (unassigned.isEmpty()) return;
		node.fields.addAll(leadingConstants(node), unassigned);
	}

	private static String names(List<FieldNode> fields) {
		List<String> names = new ArrayList<>();
		for (FieldNode field : fields) names.add(field.name);
		return String.join(", ", names);
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

	@Override
	public AnchorSet anchors() {
		return AnchorSet.scanned("acts on whichever carrier enum a mod asked to extend, which depends entirely "
				+ "on which mods are installed");
	}
}
