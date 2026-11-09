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

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
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

class CarrierPackIdMigrationInjectorTest {
	private static final String TARGET = "net/minecraft/server/MinecraftServer";
	private static final String METHOD = "configurePackRepository";
	private static final String CONFIG = "Lnet/minecraft/world/level/WorldDataConfiguration;";
	private static final String DESC = "(Lnet/minecraft/server/packs/repository/PackRepository;" + CONFIG + "ZZ)" + CONFIG;

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
