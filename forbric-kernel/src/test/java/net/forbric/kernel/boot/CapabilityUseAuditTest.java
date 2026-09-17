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

package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Which mods are told that the capability system they use is not there.
 *
 * <p>The merge builds the root game types on one loader's attachment system, so the other's capability provider
 * and its storage are gone. A mod attaching an item handler or a fluid tank therefore attaches it to nothing,
 * and the failure arrives as a missing-method error from inside the mod, on a line a player cannot connect to
 * anything. Naming the mods is all that can honestly be done until the merge itself carries the provider.
 */
class CapabilityUseAuditTest {

	@AfterEach
	void forget() {
		CapabilityUseAudit.reset();
	}

	private static byte[] classNaming(String type) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/Machine", null, "java/lang/Object", null);
		MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC, "attach", "()V", null, null);
		m.visitCode();
		if (type != null) {
			// A type a mod names lands in the constant pool verbatim, which is what the scan reads.
			m.visitTypeInsn(Opcodes.NEW, type);
			m.visitInsn(Opcodes.POP);
		}
		m.visitInsn(Opcodes.RETURN);
		m.visitMaxs(2, 1);
		m.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	@Test
	void aClassNamingTheCapabilityPackageIsRecognised() {
		assertTrue(CapabilityUseAudit.namesTheCapabilityPackage(
				classNaming("net/minecraftforge/common/capabilities/Capability")));
	}

	@Test
	void aClassNamingTheOtherLoadersAttachmentsIsNot() {
		// That system IS what the merged game carries, so a mod using it is fine and must not be named.
		assertFalse(CapabilityUseAudit.namesTheCapabilityPackage(
				classNaming("net/neoforged/neoforge/attachment/AttachmentType")));
		assertFalse(CapabilityUseAudit.namesTheCapabilityPackage(classNaming(null)));
	}

	@Test
	void eachJarIsNamedOnceHoweverManyOfItsClassesUseIt() {
		byte[] uses = classNaming("net/minecraftforge/common/capabilities/Capability");

		CapabilityUseAudit.note("machines.jar", uses);
		CapabilityUseAudit.note("machines.jar", uses);
		CapabilityUseAudit.note("machines.jar", uses);

		assertTrue(CapabilityUseAudit.users().equals(java.util.Set.of("machines.jar")),
				"a mod with fifty such classes is still one mod to tell the player about");
	}

	@Test
	void aJarThatDoesNotUseItIsNotNamed() {
		CapabilityUseAudit.note("ordinary.jar", classNaming(null));

		assertTrue(CapabilityUseAudit.users().isEmpty());
	}

	@Test
	void unreadableBytesNameNobody() {
		CapabilityUseAudit.note("broken.jar", new byte[] {1, 2, 3});
		CapabilityUseAudit.note("broken.jar", null);
		CapabilityUseAudit.note(null, classNaming("net/minecraftforge/common/capabilities/Capability"));

		assertTrue(CapabilityUseAudit.users().isEmpty());
	}
}
