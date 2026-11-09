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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.fabricmc.api.EnvType;

@ExecutesInjector(CarrierPackIdMigrationInjector.class)
class CarrierPackIdMigrationInjectorTest {
	private static final String TARGET = "net/minecraft/server/MinecraftServer";
	private static final String METHOD = "configurePackRepository";
	private static final String CONFIG = "Lnet/minecraft/world/level/WorldDataConfiguration;";
	private static final String DESC = "(Lnet/minecraft/server/packs/repository/PackRepository;" + CONFIG + "ZZ)" + CONFIG;
	private static final String HOOK_SOURCE = "net/forbric/kernel/runtime/KernelCarrierPackIds.java";

	@Test
	void theSavedSelectionIsReplacedBeforeTheBodyReadsIt() {
		byte[] out = new CarrierPackIdMigrationInjector().transform(TARGET, standIn(TARGET, METHOD), null);
		List<AbstractInsnNode> real = realInstructions(out, METHOD, DESC);
		assertEquals(Opcodes.ALOAD, real.get(0).getOpcode());
		assertEquals(1, ((VarInsnNode) real.get(0)).var, "arg1 is the WorldDataConfiguration level.dat held");
		MethodInsnNode hook = (MethodInsnNode) real.get(1);
		assertEquals(Opcodes.INVOKESTATIC, hook.getOpcode());
		assertEquals("net/forbric/kernel/runtime/KernelCarrierPackIds", hook.owner);
		assertEquals("migrate", hook.name);
		assertEquals("(" + CONFIG + ")" + CONFIG, hook.desc);
		assertEquals(Opcodes.ASTORE, real.get(2).getOpcode());
		assertEquals(1, ((VarInsnNode) real.get(2)).var, "the renamed selection replaces the argument");
		assertEquals(Opcodes.ALOAD, real.get(3).getOpcode(), "the original body follows untouched");
	}

	@Test
	void leavesEveryOtherClassAndMethodAlone() {
		byte[] other = standIn("some/other/Server", METHOD);
		assertArrayEquals(other, new CarrierPackIdMigrationInjector().transform("some/other/Server", other, null));
		byte[] renamed = standIn(TARGET, "configurePackRepositoryV2");
		assertArrayEquals(renamed, new CarrierPackIdMigrationInjector().transform(TARGET, renamed, null),
				"a gone anchor hands the class back and says so, never throws");
	}

	/**
	 * The rewritten method runs: a world's saved selection naming the carrier by an earlier install's id reaches the
	 * body renamed in place, and one with nothing to rename reaches it untouched. The hook is the real
	 * KernelCarrierPackIds, compiled from its source against stand-ins of the four game types it touches.
	 */
	@Test
	void anOldWorldsSelectionReachesTheBodyRenamed(@TempDir Path work) throws Throwable {
		Map<String, String> standIns = Map.of(
				"net.minecraft.server.packs.repository.PackRepository",
				"package net.minecraft.server.packs.repository; public class PackRepository {}",
				"net.minecraft.world.flag.FeatureFlagSet", "package net.minecraft.world.flag; public class FeatureFlagSet {}",
				"net.minecraft.world.level.DataPackConfig", """
						package net.minecraft.world.level;
						public class DataPackConfig {
							private final java.util.List<String> enabled, disabled;
							public DataPackConfig(java.util.List<String> enabled, java.util.List<String> disabled) {
								this.enabled = enabled; this.disabled = disabled;
							}
							public java.util.List<String> getEnabled() { return enabled; }
							public java.util.List<String> getDisabled() { return disabled; }
						}
						""",
				"net.minecraft.world.level.WorldDataConfiguration", """
						package net.minecraft.world.level;
						public record WorldDataConfiguration(DataPackConfig dataPacks,
								net.minecraft.world.flag.FeatureFlagSet enabledFeatures) {}
						""",
				"net.minecraft.server.MinecraftServer", """
						package net.minecraft.server;
						import net.minecraft.world.level.WorldDataConfiguration;
						public class MinecraftServer {
							public static WorldDataConfiguration configurePackRepository(
									net.minecraft.server.packs.repository.PackRepository repository, WorldDataConfiguration saved,
									boolean safeMode, boolean initMode) {
								return saved;
							}
						}
						""");
		Map<String, String> sources = new HashMap<>(standIns);
		sources.put(HOOK_SOURCE, java.nio.file.Files.readString(Path.of("src/runtime/java").resolve(HOOK_SOURCE)));
		Map<String, byte[]> classes = new HashMap<>(InjectorExecution.compile(work, sources));
		byte[] original = classes.get(TARGET);
		byte[] rewritten = InjectorExecution.transform(new CarrierPackIdMigrationInjector(), TARGET.replace('/', '.'), original,
				EnvType.SERVER);
		assertNotSame(original, rewritten);
		classes.put(TARGET, rewritten);
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(rewritten, loader));

		Class<?> packConfig = loader.loadClass("net.minecraft.world.level.DataPackConfig");
		Class<?> flags = loader.loadClass("net.minecraft.world.flag.FeatureFlagSet");
		Class<?> config = loader.loadClass("net.minecraft.world.level.WorldDataConfiguration");
		Object features = flags.getConstructor().newInstance();
		java.util.function.BiFunction<List<String>, List<String>, Object> saved = (enabled, disabled) -> {
			try {
				Object packs = packConfig.getConstructor(List.class, List.class).newInstance(enabled, disabled);
				return config.getConstructor(packConfig, flags).newInstance(packs, features);
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		};
		Class<?> server = loader.loadClass(TARGET.replace('/', '.'));

		Object old = saved.apply(List.of("vanilla", "forbric/carrier/1-forge-runtime-interop",
				"forbric/carrier/2-neoforge-runtime-26.2", "forbric/data/mcwbridges"), List.of("forbric/carrier/2-neoforge-runtime-26.2"));
		Object loaded = InjectorExecution.invokeStatic(server, METHOD, null, old, false, false);
		Object packs = InjectorExecution.invoke(loaded, "dataPacks");
		assertEquals(List.of("vanilla", "forbric/carrier/1-forge-runtime-interop", "forbric/carrier/2-neoforge-runtime",
				"forbric/data/mcwbridges"), InjectorExecution.invoke(packs, "getEnabled"), "renamed where it stood");
		assertEquals(List.of("forbric/carrier/2-neoforge-runtime"), InjectorExecution.invoke(packs, "getDisabled"));
		assertSame(features, InjectorExecution.invoke(loaded, "enabledFeatures"), "the feature flags are carried over");

		Object current = saved.apply(List.of("vanilla", "forbric/carrier/2-neoforge-runtime"), List.of());
		assertSame(current, InjectorExecution.invokeStatic(server, METHOD, null, current, false, false),
				"a selection with nothing to rename reaches the body as saved");
	}

	/** The anchor is the genuine base's own static method with this descriptor. */
	@Test
	void theGenuineBaseHasTheAnchor() {
		byte[] base = TestFixtures.requireEntry(Fixture.STAGED, TestFixtures.stagedBase(), TARGET + ".class");
		byte[] out = new CarrierPackIdMigrationInjector().transform(TARGET, base, null);
		assertNotSame(base, out, "the genuine MinecraftServer has no static " + METHOD + DESC);
		MethodInsnNode hook = (MethodInsnNode) realInstructions(out, METHOD, DESC).get(1);
		assertEquals("migrate", hook.name);
	}

	/** A static method of the target's shape whose body reads its second argument. */
	private static byte[] standIn(String owner, String name) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, DESC, null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 1);
		mv.visitInsn(Opcodes.ARETURN);
		mv.visitMaxs(1, 4);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static List<AbstractInsnNode> realInstructions(byte[] classBytes, String name, String desc) {
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		for (MethodNode m : node.methods) {
			if (!name.equals(m.name) || !desc.equals(m.desc)) continue;
			List<AbstractInsnNode> real = new ArrayList<>();
			for (AbstractInsnNode insn : m.instructions) {
				if (insn.getOpcode() >= 0) real.add(insn);
			}
			return real;
		}
		throw new AssertionError(name + desc + " not found");
	}
}
