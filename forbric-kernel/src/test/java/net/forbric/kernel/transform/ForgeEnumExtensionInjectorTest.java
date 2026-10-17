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

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;
import net.forbric.kernel.ExpectCaught;

/**
 * {@link ForgeEnumExtensionInjector}'s output, run: an extensible enum whose {@code create(...)} was the throwing stub
 * goes through the injector, is defined, and {@code create} hands back a new constant.
 *
 * <p>The rewrite itself belongs to MinecraftForge's {@code RuntimeEnumExtender}, which no fresh clone has. So it is a
 * stand-in here, compiled under that exact name together with modlauncher's {@code ILaunchPluginService$Phase} and
 * the {@code IExtensibleEnum} marker, the three names the injector keys on. The stand-in does its own small rewrite
 * (copy {@code $VALUES} one longer, construct the next ordinal, store it back) and records how it was asked. What is
 * under test is the kernel's half: which classes it offers, what it passes, and that the edited node is written back
 * with flags that make it load and run.
 */
@ExecutesInjector(ForgeEnumExtensionInjector.class)
class ForgeEnumExtensionInjectorTest {
	private static final String EXTENDER = "net.minecraftforge.fml.common.asm.RuntimeEnumExtender";
	private static final String POSE = "fixture/Pose";

	private static final String STUB = """
				public static %s create(String name) {
					throw new IllegalStateException("Enum not extended");
				}
			""";

	private static final Map<String, String> SOURCES = Map.of(
			"net.minecraftforge.common.IExtensibleEnum", """
					package net.minecraftforge.common;
					public interface IExtensibleEnum {
					}
					""",
			"cpw.mods.modlauncher.serviceapi.ILaunchPluginService", """
					package cpw.mods.modlauncher.serviceapi;
					public interface ILaunchPluginService {
						enum Phase { BEFORE, AFTER }
					}
					""",
			EXTENDER, """
					package net.minecraftforge.fml.common.asm;

					import java.util.ArrayList;
					import java.util.List;

					import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
					import org.objectweb.asm.Opcodes;
					import org.objectweb.asm.Type;
					import org.objectweb.asm.tree.*;

					public class RuntimeEnumExtender {
						public static final List<String> calls = new ArrayList<>();
						public static boolean fail;

						public int processClassWithFlags(ILaunchPluginService.Phase phase, ClassNode node, Type type, String reason) {
							calls.add(phase.name() + " " + type.getInternalName() + " " + reason);
							if (fail) throw new IllegalStateException("the processor failed half way");
							if (!node.interfaces.contains("net/minecraftforge/common/IExtensibleEnum")) return 0;
							boolean rewrote = false;
							for (MethodNode method : node.methods) {
								if (method.name.equals("create") && (method.access & Opcodes.ACC_STATIC) != 0
										&& method.desc.equals("(Ljava/lang/String;)L" + node.name + ";")) {
									method.instructions = factory(node.name);
									method.tryCatchBlocks.clear();
									method.localVariables = null;
									rewrote = true;
								}
							}
							if (!rewrote) return 0;
							for (FieldNode field : node.fields) {
								if (field.name.equals("$VALUES")) field.access &= ~Opcodes.ACC_FINAL;
							}
							return 2; // ComputeFlags.COMPUTE_FRAMES: maxs and frames are left for the writer
						}

						// values = Arrays.copyOf($VALUES, $VALUES.length + 1); made = new E(name, values.length - 1);
						// values[values.length - 1] = made; $VALUES = values; return made;
						private static InsnList factory(String owner) {
							String array = "[L" + owner + ";";
							InsnList code = new InsnList();
							code.add(new FieldInsnNode(Opcodes.GETSTATIC, owner, "$VALUES", array));
							code.add(new FieldInsnNode(Opcodes.GETSTATIC, owner, "$VALUES", array));
							code.add(new InsnNode(Opcodes.ARRAYLENGTH));
							code.add(new InsnNode(Opcodes.ICONST_1));
							code.add(new InsnNode(Opcodes.IADD));
							code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Arrays", "copyOf",
									"([Ljava/lang/Object;I)[Ljava/lang/Object;", false));
							code.add(new TypeInsnNode(Opcodes.CHECKCAST, array));
							code.add(new VarInsnNode(Opcodes.ASTORE, 1));
							code.add(new TypeInsnNode(Opcodes.NEW, owner));
							code.add(new InsnNode(Opcodes.DUP));
							code.add(new VarInsnNode(Opcodes.ALOAD, 0));
							code.add(new VarInsnNode(Opcodes.ALOAD, 1));
							code.add(new InsnNode(Opcodes.ARRAYLENGTH));
							code.add(new InsnNode(Opcodes.ICONST_1));
							code.add(new InsnNode(Opcodes.ISUB));
							code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, owner, "<init>", "(Ljava/lang/String;I)V", false));
							code.add(new VarInsnNode(Opcodes.ASTORE, 2));
							code.add(new VarInsnNode(Opcodes.ALOAD, 1));
							code.add(new VarInsnNode(Opcodes.ALOAD, 1));
							code.add(new InsnNode(Opcodes.ARRAYLENGTH));
							code.add(new InsnNode(Opcodes.ICONST_1));
							code.add(new InsnNode(Opcodes.ISUB));
							code.add(new VarInsnNode(Opcodes.ALOAD, 2));
							code.add(new InsnNode(Opcodes.AASTORE));
							code.add(new VarInsnNode(Opcodes.ALOAD, 1));
							code.add(new FieldInsnNode(Opcodes.PUTSTATIC, owner, "$VALUES", array));
							code.add(new VarInsnNode(Opcodes.ALOAD, 2));
							code.add(new InsnNode(Opcodes.ARETURN));
							return code;
						}
					}
					""",
			// The shape a mod meets: a marked enum whose create(...) only throws until something rewrites it.
			"fixture.Pose", """
					package fixture;
					public enum Pose implements net.minecraftforge.common.IExtensibleEnum {
						STANDING, CROUCHING;
					""" + STUB.formatted("Pose") + "}\n",
			// The same stub without the marker: not the injector's to offer.
			"fixture.PlainPose", """
					package fixture;
					public enum PlainPose {
						STANDING;
					""" + STUB.formatted("PlainPose") + "}\n",
			// Marked, but nothing for the processor to rewrite: it answers NO_REWRITE.
			"fixture.Sealed", """
					package fixture;
					public enum Sealed implements net.minecraftforge.common.IExtensibleEnum {
						ONLY
					}
					""");

	@Test void theStubCreateReturnsANewConstantAfterTheInjector(@TempDir Path work) throws Throwable {
		Map<String, byte[]> classes = InjectorExecution.compile(work, SOURCES);
		ClassLoader game = InjectorExecution.load(classes);
		ForgeEnumExtensionInjector injector = ForgeEnumExtensionInjector.create(game);
		assertNotNull(injector, "the stand-in extender and Phase are in the loader, so the injector must install");

		byte[] original = classes.get(POSE);
		byte[] transformed = InjectorExecution.transform(injector, "fixture.Pose", original, EnvType.CLIENT);
		assertNotSame(original, transformed, "a marked enum must be rewritten");
		assertEquals(List.of("AFTER fixture/Pose classloading"), calls(game),
				"the processor must be asked once, in the AFTER phase, with the class's type and MinecraftForge's reason");

		Map<String, byte[]> after = new HashMap<>(classes);
		after.put(POSE, transformed);
		ClassLoader run = InjectorExecution.load(after);
		assertEquals("", InjectorExecution.verify(transformed, run));
		Class<?> pose = run.loadClass("fixture.Pose");
		assertEquals(2, values(pose).length);

		Enum<?> waving = (Enum<?>) InjectorExecution.invokeStatic(pose, "create", "WAVING");
		assertSame(pose, waving.getClass());
		assertEquals("WAVING", waving.name());
		assertEquals(2, waving.ordinal());
		Object[] grown = values(pose);
		assertEquals(3, grown.length, "the new constant must be among values()");
		assertSame(waving, grown[2]);
		assertEquals("STANDING", ((Enum<?>) grown[0]).name(), "the existing constants stay where they were");

		// The control: the same class untransformed still throws, so the effect above is the injector's.
		Class<?> stub = InjectorExecution.load(classes).loadClass("fixture.Pose");
		assertEquals("Enum not extended", assertThrows(IllegalStateException.class,
				() -> InjectorExecution.invokeStatic(stub, "create", "WAVING")).getMessage());
		assertEquals(2, values(stub).length);
	}

	@ExpectCaught("could not read a class header to check for the extensible-enum marker")
	@Test void aClassWithoutTheMarkerIsNotOffered(@TempDir Path work) throws Throwable {
		Map<String, byte[]> classes = InjectorExecution.compile(work, SOURCES);
		ClassLoader game = InjectorExecution.load(classes);
		ForgeEnumExtensionInjector injector = ForgeEnumExtensionInjector.create(game);

		byte[] plain = classes.get("fixture/PlainPose");
		assertSame(plain, InjectorExecution.transform(injector, "fixture.PlainPose", plain, EnvType.CLIENT));
		byte[] garbage = { 1, 2, 3 };
		assertSame(garbage, InjectorExecution.transform(injector, "fixture.Garbage", garbage, EnvType.CLIENT));
		assertEquals(List.of(), calls(game), "the header decides; the processor is never asked about an unmarked class");

		Class<?> loaded = InjectorExecution.load(classes).loadClass("fixture.PlainPose");
		assertThrows(IllegalStateException.class, () -> InjectorExecution.invokeStatic(loaded, "create", "WAVING"));
	}

	@ExpectCaught("could not make fixture.Pose extensible")
	@Test void aDeclinedOrFailedRewriteLeavesTheClassAsItWas(@TempDir Path work) throws Throwable {
		Map<String, byte[]> classes = InjectorExecution.compile(work, SOURCES);
		ClassLoader game = InjectorExecution.load(classes);
		ForgeEnumExtensionInjector injector = ForgeEnumExtensionInjector.create(game);

		byte[] sealed = classes.get("fixture/Sealed");
		assertSame(sealed, InjectorExecution.transform(injector, "fixture.Sealed", sealed, EnvType.CLIENT),
				"NO_REWRITE must keep the original bytes");
		assertEquals(List.of("AFTER fixture/Sealed classloading"), calls(game), "a marked class is offered");

		// A processor that throws after editing the node: the original bytes, not the half-edited node, and no throw
		// out of the transformer, which would fail the enum's definition and every class that names it.
		game.loadClass(EXTENDER).getField("fail").setBoolean(null, true);
		byte[] pose = classes.get(POSE);
		assertSame(pose, InjectorExecution.transform(injector, "fixture.Pose", pose, EnvType.CLIENT));
		assertEquals(List.of("AFTER fixture/Sealed classloading", "AFTER fixture/Pose classloading"), calls(game));
	}

	@ExpectCaught("no MinecraftForge RuntimeEnumExtender")
	@Test void nothingInstallsWithoutMinecraftForgesExtender(@TempDir Path work) throws Exception {
		// The kernel registers only a non-null injector, so this is how a runtime without MinecraftForge keeps its
		// transform chain free of it. The test's own loader has neither class.
		assertNull(ForgeEnumExtensionInjector.create(getClass().getClassLoader()));

		Map<String, byte[]> classes = InjectorExecution.compile(work, SOURCES);
		Map<String, byte[]> noPhase = new HashMap<>(classes);
		noPhase.keySet().removeIf(name -> name.startsWith("cpw/"));
		assertNull(ForgeEnumExtensionInjector.create(InjectorExecution.load(noPhase)), "an extender without modlauncher's Phase");

		Map<String, byte[]> noExtender = new HashMap<>(classes);
		noExtender.remove(EXTENDER.replace('.', '/'));
		assertNull(ForgeEnumExtensionInjector.create(InjectorExecution.load(noExtender)), "modlauncher without the extender");
	}

	private static List<?> calls(ClassLoader game) throws ReflectiveOperationException {
		return List.copyOf((List<?>) InjectorExecution.getStatic(game.loadClass(EXTENDER), "calls"));
	}

	private static Object[] values(Class<?> enumClass) throws Throwable {
		return (Object[]) InjectorExecution.invokeStatic(enumClass, "values");
	}
}
