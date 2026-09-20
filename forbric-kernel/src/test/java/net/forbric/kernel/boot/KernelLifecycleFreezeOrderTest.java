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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;

/**
 * The ORDER the two carriers' {@code GameData} hooks are called in: freeze NeoForge-first, unfreeze as before.
 * Two synthetic GameData classes, named by {@link ForeignType#GAME_DATA} so a rename cannot pass silently, record
 * their calls into a system property; the kernel's private freeze/unfreeze are invoked reflectively.
 */
@org.junit.jupiter.api.parallel.ResourceLock("system-properties")
class KernelLifecycleFreezeOrderTest {
	private static final String ORDER = "forbric.test.gameDataOrder";

	@AfterEach
	void reset() {
		System.clearProperty(ORDER);
		System.clearProperty(KernelLifecycle.FREEZE_ORDER_PROPERTY);
	}

	@Test
	void freezeIsNeoForgeFirstAndUnfreezeIsStillForgeFirst(@TempDir Path dir) throws Exception {
		try (URLClassLoader cl = carriers(dir)) {
			System.setProperty(ORDER, "");
			call("freeze", cl);
			assertEquals("neoforge,forge,", System.getProperty(ORDER), "NeoForge binds tags, freezes and snapshots while the registries are still writable");
			System.setProperty(ORDER, "");
			call("unfreeze", cl);
			assertEquals("forge,neoforge,", System.getProperty(ORDER), "only freeze flipped");
		}
	}

	@Test
	void switchedOffFreezeIsTheOldForgeFirstOrder(@TempDir Path dir) throws Exception {
		System.setProperty(KernelLifecycle.FREEZE_ORDER_PROPERTY, "off");
		try (URLClassLoader cl = carriers(dir)) {
			System.setProperty(ORDER, "");
			call("freeze", cl);
			assertEquals("forge,neoforge,", System.getProperty(ORDER));
		}
	}

	private static void call(String name, ClassLoader cl) throws Exception {
		Method m = KernelLifecycle.class.getDeclaredMethod(name, ClassLoader.class);
		m.setAccessible(true);
		m.invoke(null, cl);
	}

	/** A jar with both GameData classes, each appending its tag to the ORDER property on freezeData/unfreezeData. */
	private static URLClassLoader carriers(Path dir) throws Exception {
		Path jar = dir.resolve("carriers.jar");
		try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
			for (Ecosystem eco : new Ecosystem[] { Ecosystem.FORGE, Ecosystem.NEOFORGE }) {
				String internal = ForeignType.GAME_DATA.internal(eco);
				zip.putNextEntry(new ZipEntry(internal + ".class"));
				zip.write(gameData(internal, eco.name().toLowerCase()));
				zip.closeEntry();
			}
		}
		return new URLClassLoader(new URL[] { jar.toUri().toURL() }, ClassLoader.getPlatformClassLoader());
	}

	private static byte[] gameData(String internal, String tag) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null);
		for (String name : new String[] { "freezeData", "unfreezeData" }) {
			MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, "()V", null, null);
			mv.visitCode();
			// System.setProperty(ORDER, System.getProperty(ORDER, "") + tag + ",")
			mv.visitLdcInsn(ORDER);
			mv.visitLdcInsn(ORDER);
			mv.visitLdcInsn("");
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "getProperty", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);
			mv.visitLdcInsn(tag + ",");
			mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false);
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "setProperty", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);
			mv.visitInsn(Opcodes.POP);
			mv.visitInsn(Opcodes.RETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
		}
		cw.visitEnd();
		return cw.toByteArray();
	}
}
