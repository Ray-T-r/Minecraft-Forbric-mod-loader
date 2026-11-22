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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import net.fabricmc.api.EnvType;
import net.forbric.kernel.access.ClassTweakerTransformer;

/**
 * A Fabric mod and a NeoForge mod extending the same enum (Farmer's Delight Refabricated's class tweaker
 * {@code extend-enum} and TofuCraft's {@code enumextensions.json}, both on {@code RecipeBookType}): both mods'
 * constants must be there, each where its own loader would number it, and the enum must load at all.
 *
 * <p>The class tweaker here is the real library the kernel runs, so its stub sits exactly where it does in game. The
 * NeoForge side is a stand-in compiled under {@code RuntimeEnumExtender}'s name that does what the real one's bytecode
 * does (FML loader 11.0.16, read with javap): count the leading {@code public static final enum} self-typed fields
 * ({@code getVanillaEntryCount}, a {@code takeWhile}), insert its fields there, construct each in {@code <clinit>}
 * with ordinal {@code count + i} before the {@code $values()} call, and in {@code $values()} grow the array by its own
 * number with {@code Arrays.copyOf} and store each constant at its {@code ordinal()} ({@code appendValuesArray}).
 */
@ExecutesInjector(NeoEnumExtensionInjector.class)
class NeoEnumExtensionBesideFabricStubTest {
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

					import net.neoforged.neoforgespi.transformation.ClassProcessor;
					import org.objectweb.asm.Opcodes;
					import org.objectweb.asm.tree.*;

					public class RuntimeEnumExtender {
						static final List<String> ADDED = List.of("TOFU_COOKING", "TOFU_CRAFT");

						public boolean handlesClass(ClassProcessor.SelectionContext context) {
							return context.type().getInternalName().equals("fixture/Book");
						}

						public ClassProcessor.ComputeFlags processClass(ClassProcessor.TransformationContext context) {
							ClassNode node = context.node();
							String self = "L" + node.name + ";", array = "[" + self;
							// getVanillaEntryCount: fields.stream().takeWhile(ENUM_FLAGS, own type).count()
							int count = 0;
							for (FieldNode f : node.fields) {
								if ((f.access & 16409) != 16409 || !f.desc.equals(self)) break;
								count++;
							}
							List<FieldNode> added = new ArrayList<>();
							InsnList create = new InsnList();
							for (int i = 0; i < ADDED.size(); i++) {
								String name = ADDED.get(i);
								added.add(new FieldNode(16409, name, self, null, null));
								create.add(new TypeInsnNode(Opcodes.NEW, node.name));
								create.add(new InsnNode(Opcodes.DUP));
								create.add(new LdcInsnNode(name));
								create.add(new LdcInsnNode(count + i));
								create.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.name, "<init>", "(Ljava/lang/String;I)V", false));
								create.add(new FieldInsnNode(Opcodes.PUTSTATIC, node.name, name, self));
							}
							node.fields.addAll(count, added);
							for (MethodNode m : node.methods) {
								if (m.name.equals("<clinit>")) {
									for (AbstractInsnNode insn : m.instructions.toArray()) {
										if (insn instanceof MethodInsnNode call && call.name.equals("$values")) {
											m.instructions.insertBefore(insn, create);
											break;
										}
									}
								}
								if (m.name.equals("$values")) {
									// appendValuesArray: copyOf(arr, arr.length + n); arr[c.ordinal()] = c for each
									InsnList append = new InsnList();
									append.add(new InsnNode(Opcodes.DUP));
									append.add(new InsnNode(Opcodes.ARRAYLENGTH));
									append.add(new LdcInsnNode(added.size()));
									append.add(new InsnNode(Opcodes.IADD));
									append.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Arrays", "copyOf",
											"([Ljava/lang/Object;I)[Ljava/lang/Object;", false));
									append.add(new TypeInsnNode(Opcodes.CHECKCAST, array));
									for (FieldNode f : added) {
										append.add(new InsnNode(Opcodes.DUP));
										append.add(new FieldInsnNode(Opcodes.GETSTATIC, node.name, f.name, self));
										append.add(new InsnNode(Opcodes.DUP));
										append.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, "ordinal", "()I", false));
										append.add(new InsnNode(Opcodes.SWAP));
										append.add(new InsnNode(Opcodes.AASTORE));
									}
									for (AbstractInsnNode insn : m.instructions.toArray()) {
										if (insn.getOpcode() == Opcodes.ARETURN) m.instructions.insertBefore(insn, append);
									}
								}
							}
							return ClassProcessor.ComputeFlags.COMPUTE_MAXS;
						}
					}
					""",
			"fixture.Book", "package fixture; public enum Book { CRAFTING, FURNACE, BLAST_FURNACE, SMOKER }");

	/** What Farmer's Delight Refabricated's class tweaker declares, on the fixture enum. */
	private static final String TWEAKER = "classTweaker v2 official\ntransitive-extend-enum fixture/Book FD_COOKING\n";

	@Test
	void neoForgeNumbersItsConstantsAfterTheBuiltOnesNotAfterAFabricStub(@TempDir Path work) throws Throwable {
		Map<String, byte[]> classes = InjectorExecution.compile(work, SOURCES);
		byte[] extended = extend(classes);

		Map<String, byte[]> after = new HashMap<>(classes);
		after.put("fixture/Book", extended);
		Class<?> book = InjectorExecution.load(after).loadClass("fixture.Book");
		Object[] values = (Object[]) InjectorExecution.invokeStatic(book, "values");
		assertEquals(List.of("CRAFTING", "FURNACE", "BLAST_FURNACE", "SMOKER", "TOFU_COOKING", "TOFU_CRAFT"),
				Arrays.stream(values).map(v -> ((Enum<?>) v).name()).toList(),
				"the enum loads, and NeoForge's constants are in values() right after the built ones");
		for (int i = 0; i < values.length; i++) assertEquals(i, ((Enum<?>) values[i]).ordinal());
		assertNull(InjectorExecution.getStatic(book, "FD_COOKING"),
				"the Fabric stub is still declared and still empty: the mod's enum mixin fills it when Mixin runs");

		ClassNode node = new ClassNode();
		new ClassReader(extended).accept(node, 0);
		List<String> order = node.fields.stream().map(f -> f.name).toList();
		assertEquals(List.of("CRAFTING", "FURNACE", "BLAST_FURNACE", "SMOKER", "TOFU_COOKING", "TOFU_CRAFT", "FD_COOKING"),
				order.subList(0, 7), "the stub sits after every built constant, as the class tweaker places it");
		FieldNode stub = node.fields.get(6);
		assertEquals(List.of("org.spongepowered.asm.mixin.StubEnumConstant"),
				stub.attrs == null ? List.of() : stub.attrs.stream().map(a -> a.type).toList(),
				"it still carries the marker Mixin's enum merge skips it by");
	}

	@Test
	void premiseWithTheStubCountedNeoForgeWritesPastTheEndOfTheArray(@TempDir Path work) throws Throwable {
		Map<String, byte[]> classes = InjectorExecution.compile(work, SOURCES);
		String before = System.getProperty(NeoEnumExtensionInjector.UNASSIGNED_SWITCH);
		System.setProperty(NeoEnumExtensionInjector.UNASSIGNED_SWITCH, "off");
		byte[] extended;
		try {
			extended = extend(classes);
		} finally {
			if (before == null) System.clearProperty(NeoEnumExtensionInjector.UNASSIGNED_SWITCH);
			else System.setProperty(NeoEnumExtensionInjector.UNASSIGNED_SWITCH, before);
		}
		Map<String, byte[]> after = new HashMap<>(classes);
		after.put("fixture/Book", extended);
		Class<?> book = InjectorExecution.load(after).loadClass("fixture.Book");
		Throwable thrown = assertThrows(ExceptionInInitializerError.class, () -> InjectorExecution.invokeStatic(book, "values"));
		assertInstanceOf(ArrayIndexOutOfBoundsException.class, thrown.getCause(),
				"the game's failure: Index 6 out of bounds for length 6 in $values");
	}

	/** The chain's order: the class tweaker in ACCESS, then the NeoForge extender in COREMOD. */
	private static byte[] extend(Map<String, byte[]> classes) {
		ClassTweakerTransformer tweaker = ClassTweakerTransformer.createFrom(List.of(new ClassTweakerTransformer.File(
				"fd.jar", TWEAKER.getBytes(StandardCharsets.UTF_8))), (name, bytes) -> { });
		byte[] stubbed = InjectorExecution.transform(tweaker, "fixture.Book", classes.get("fixture/Book"), EnvType.SERVER);
		assertNotSame(classes.get("fixture/Book"), stubbed, "premise: the class tweaker added its stub");

		NeoEnumExtensionInjector injector = NeoEnumExtensionInjector.create(InjectorExecution.load(classes));
		return InjectorExecution.transform(injector, "fixture.Book", stubbed, EnvType.SERVER);
	}
}
