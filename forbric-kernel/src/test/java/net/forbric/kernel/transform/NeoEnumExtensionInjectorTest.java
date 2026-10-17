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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;

import net.fabricmc.api.EnvType;
import net.forbric.kernel.ExpectCaught;

/**
 * Covers {@link NeoEnumExtensionInjector}'s decisions that are not NeoForge's compiled code: whether to install at
 * all, which classes to hand over, what to pass, and how to write the node back.
 *
 * <p>The actual enum rewrite is {@code RuntimeEnumExtender}'s and is verified by running mods that declare
 * extensions (Tool Belt, Sophisticated Backpacks, Earth Mobs). Here a stand-in compiled under its name and beside the
 * SPI's two context records does a small rewrite of its own (one declared constant appended in {@code <clinit>}), so
 * the injector's output is defined and run on a checkout without NeoForge.
 */
@ExecutesInjector(NeoEnumExtensionInjector.class)
class NeoEnumExtensionInjectorTest {
	private static final String EXTENDER = "net.neoforged.fml.common.asm.enumextension.RuntimeEnumExtender";

	private static final Map<String, String> SOURCES = Map.of(
			"net.neoforged.neoforgespi.transformation.ClassProcessor", """
					package net.neoforged.neoforgespi.transformation;

					import java.util.function.BiConsumer;
					import java.util.function.Supplier;
					import org.objectweb.asm.Type;
					import org.objectweb.asm.tree.ClassNode;

					public interface ClassProcessor {
						record SelectionContext(Type type, boolean empty) {
						}

						record TransformationContext(Type type, ClassNode node, boolean empty,
								BiConsumer<String, String[]> auditTrail, Supplier<byte[]> initialHash) {
						}

						enum ComputeFlags { NO_REWRITE, SIMPLE_REWRITE, COMPUTE_MAXS, COMPUTE_FRAMES }
					}
					""",
			EXTENDER, """
					package net.neoforged.fml.common.asm.enumextension;

					import java.util.ArrayList;
					import java.util.List;
					import java.util.Set;

					import net.neoforged.neoforgespi.transformation.ClassProcessor;
					import org.objectweb.asm.Opcodes;
					import org.objectweb.asm.tree.*;

					public class RuntimeEnumExtender {
						/** The enums some mod declared an extension of. */
						public static final Set<String> DECLARED = Set.of("fixture/Pose", "fixture/Sealed");
						public static final List<String> calls = new ArrayList<>();
						public static boolean fail;

						public boolean handlesClass(ClassProcessor.SelectionContext context) {
							return DECLARED.contains(context.type().getInternalName());
						}

						public ClassProcessor.ComputeFlags processClass(ClassProcessor.TransformationContext context) {
							ClassNode node = context.node();
							calls.add(context.type().getInternalName() + " " + node.name + " empty=" + context.empty());
							if (fail) throw new IllegalStateException("the processor failed half way");
							if (node.name.equals("fixture/Sealed")) return ClassProcessor.ComputeFlags.NO_REWRITE;
							String self = "L" + node.name + ";", array = "[" + self;
							node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM,
									"WAVING", self, null, null));
							// WAVING = new E("WAVING", $VALUES.length); $VALUES = copyOf($VALUES, n + 1); $VALUES[n] = WAVING;
							InsnList add = new InsnList();
							add.add(new TypeInsnNode(Opcodes.NEW, node.name));
							add.add(new InsnNode(Opcodes.DUP));
							add.add(new LdcInsnNode("WAVING"));
							add.add(new FieldInsnNode(Opcodes.GETSTATIC, node.name, "$VALUES", array));
							add.add(new InsnNode(Opcodes.ARRAYLENGTH));
							add.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.name, "<init>", "(Ljava/lang/String;I)V", false));
							add.add(new FieldInsnNode(Opcodes.PUTSTATIC, node.name, "WAVING", self));
							add.add(new FieldInsnNode(Opcodes.GETSTATIC, node.name, "$VALUES", array));
							add.add(new FieldInsnNode(Opcodes.GETSTATIC, node.name, "$VALUES", array));
							add.add(new InsnNode(Opcodes.ARRAYLENGTH));
							add.add(new InsnNode(Opcodes.ICONST_1));
							add.add(new InsnNode(Opcodes.IADD));
							add.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Arrays", "copyOf",
									"([Ljava/lang/Object;I)[Ljava/lang/Object;", false));
							add.add(new TypeInsnNode(Opcodes.CHECKCAST, array));
							add.add(new InsnNode(Opcodes.DUP));
							add.add(new FieldInsnNode(Opcodes.PUTSTATIC, node.name, "$VALUES", array));
							add.add(new InsnNode(Opcodes.DUP));
							add.add(new InsnNode(Opcodes.ARRAYLENGTH));
							add.add(new InsnNode(Opcodes.ICONST_1));
							add.add(new InsnNode(Opcodes.ISUB));
							add.add(new FieldInsnNode(Opcodes.GETSTATIC, node.name, "WAVING", self));
							add.add(new InsnNode(Opcodes.AASTORE));
							for (MethodNode method : node.methods) {
								if (!method.name.equals("<clinit>")) continue;
								for (AbstractInsnNode insn : method.instructions.toArray()) {
									if (insn.getOpcode() == Opcodes.RETURN) method.instructions.insertBefore(insn, add);
								}
							}
							return ClassProcessor.ComputeFlags.COMPUTE_MAXS;
						}
					}
					""",
			"fixture.Pose", "package fixture; public enum Pose { STANDING, CROUCHING }",
			"fixture.Sealed", "package fixture; public enum Sealed { ONLY }",
			"fixture.Undeclared", "package fixture; public enum Undeclared { ONLY }");

	/** A mod's declared constant exists after the injector, by name and among values(), after the original ones. */
	@Test
	void aDeclaredConstantIsThereOnceTheEnumHasBeenThrough(@TempDir Path work) throws Throwable {
		Map<String, byte[]> classes = InjectorExecution.compile(work, SOURCES);
		ClassLoader game = InjectorExecution.load(classes);
		NeoEnumExtensionInjector injector = NeoEnumExtensionInjector.create(game);
		assertNotNull(injector, "the stand-in extender and the SPI records are in the loader, so the injector must install");

		byte[] pose = classes.get("fixture/Pose");
		byte[] extended = InjectorExecution.transform(injector, "fixture.Pose", pose, EnvType.CLIENT);
		Map<String, byte[]> after = new HashMap<>(classes);
		after.put("fixture/Pose", extended);
		ClassLoader run = InjectorExecution.load(after);
		Class<?> type = run.loadClass("fixture.Pose");
		Enum<?> waving = (Enum<?>) InjectorExecution.invokeStatic(type, "valueOf", "WAVING");
		assertEquals(2, waving.ordinal());
		Object[] values = (Object[]) InjectorExecution.invokeStatic(type, "values");
		assertEquals(List.of("STANDING", "CROUCHING", "WAVING"), java.util.Arrays.stream(values).map(v -> ((Enum<?>) v).name()).toList());
		assertEquals("", InjectorExecution.verify(extended, run));

		Class<?> stock = InjectorExecution.load(classes).loadClass("fixture.Pose");
		assertThrows(IllegalArgumentException.class, () -> InjectorExecution.invokeStatic(stock, "valueOf", "WAVING"),
				"premise: without the injector the mod's constant does not exist");
		assertEquals(List.of("fixture/Pose fixture/Pose empty=false"), calls(game),
				"asked once, with the class's own type and its node");
	}

	@ExpectCaught("could not extend fixture.Pose")
	@Test
	void anUndeclaredEnumIsNeverHandedOverAndADeclinedOrFailedOneIsLeftAlone(@TempDir Path work) throws Throwable {
		Map<String, byte[]> classes = InjectorExecution.compile(work, SOURCES);
		ClassLoader game = InjectorExecution.load(classes);
		NeoEnumExtensionInjector injector = NeoEnumExtensionInjector.create(game);

		byte[] undeclared = classes.get("fixture/Undeclared");
		assertSame(undeclared, InjectorExecution.transform(injector, "fixture.Undeclared", undeclared, EnvType.CLIENT));
		assertEquals(List.of(), calls(game), "handlesClass said no: processClass is never asked");

		byte[] sealed = classes.get("fixture/Sealed");
		assertSame(sealed, InjectorExecution.transform(injector, "fixture.Sealed", sealed, EnvType.CLIENT), "NO_REWRITE");

		game.loadClass(EXTENDER).getField("fail").setBoolean(null, true);
		byte[] pose = classes.get("fixture/Pose");
		assertSame(pose, InjectorExecution.transform(injector, "fixture.Pose", pose, EnvType.CLIENT),
				"a processor that throws leaves the original bytes, and the throw does not escape");
		assertEquals(List.of("fixture/Sealed fixture/Sealed empty=false", "fixture/Pose fixture/Pose empty=false"), calls(game));
	}

	private static List<?> calls(ClassLoader game) throws ReflectiveOperationException {
		return List.copyOf((List<?>) InjectorExecution.getStatic(game.loadClass(EXTENDER), "calls"));
	}

	@ExpectCaught("no RuntimeEnumExtender SPI")
	@Test
	void isNotInstalledWhenTheSpiIsAbsent() {
		// The test classpath has no net.neoforged.*, the same shape as a runtime without the enumextension package
		// or a Fabric-only instance. KernelBoot registers only a non-null injector, so returning null here is what
		// keeps the transform chain byte-identical on every instance that has no NeoForge enum extensions.
		assertNull(NeoEnumExtensionInjector.create(getClass().getClassLoader()));
	}

	@Test
	void computeFlagsMapOntoTheAsmWriterFlags() {
		assertEquals(-1, NeoEnumExtensionInjector.writerFlags("NO_REWRITE"), "NO_REWRITE must skip the write");
		assertEquals(0, NeoEnumExtensionInjector.writerFlags("SIMPLE_REWRITE"));
		assertEquals(ClassWriter.COMPUTE_MAXS, NeoEnumExtensionInjector.writerFlags("COMPUTE_MAXS"));
		assertEquals(ClassWriter.COMPUTE_FRAMES, NeoEnumExtensionInjector.writerFlags("COMPUTE_FRAMES"));
	}

	@Test
	void anUnknownFlagFallsToTheMostConservativeWriteNotToSkipping() {
		// The ClassNode has already been edited by the time the flags come back. Reading an unrecognised future
		// value as "no rewrite" would silently discard a transform that DID happen — the enum would load looking
		// untouched and the mod would fail on its own lookup with no hint of why.
		assertEquals(ClassWriter.COMPUTE_FRAMES, NeoEnumExtensionInjector.writerFlags("SOME_FUTURE_VALUE"));
		assertEquals(ClassWriter.COMPUTE_FRAMES, NeoEnumExtensionInjector.writerFlags(null));
	}
}
