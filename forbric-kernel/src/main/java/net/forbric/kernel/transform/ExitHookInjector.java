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

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Calls {@link net.forbric.kernel.interop.ClientShutdown#stopLeakedBackgroundExecutors} at each side's end of life —
 * every return of {@code Minecraft.close()} on the client, of {@code DedicatedServer.onServerExit()} on the
 * dedicated server — so a JVM whose config file-watchers grew a non-daemon executor during the session can still
 * end. On a client that executor holds the process open until vanilla's 15-second shutdown watchdog crashes it;
 * a dedicated server has no watchdog and no {@code System.exit} at all, so it just sits there forever after
 * "Stopping server" (the gates had been quietly killing it after their grace period).
 *
 * <p>The previous-generation loader reached the client half through a mixin
 * ({@code ForbricClientShutdownMixin}, in its {@code forbric-neoforge-bridge.mixins.json}) — and the kernel never
 * applies that loader's mixin configs, so under the kernel the hook had never run once; this injector is how it
 * runs now, and the hook itself is a kernel class. Nothing noticed for a long time because night-config's
 * executors only get a thread when a watched config file actually changes; the runs where one did, hung.
 * FML's own shutdown would close these watchers; the kernel drives both loaders' lifecycles and does not run it.
 *
 * <p>The call takes the hooked class's loader (the game loader): night-config lives on the parent and is reached
 * by delegation, MinecraftForge's config handler is a game class and is reached directly. No branch target is
 * added, so every original stack map frame stays valid.
 */
public final class ExitHookInjector implements ClassTransformer {
	/** Hooked class → the method whose returns mark that side's end of life. */
	private static final java.util.Map<String, String> EXIT_METHODS = java.util.Map.of(
			"net.minecraft.client.Minecraft", "close",
			"net.minecraft.server.dedicated.DedicatedServer", "onServerExit");
	private static final String VOID = "()V";
	private static final String HOOK_OWNER = "net/forbric/kernel/interop/ClientShutdown";
	private static final String HOOK_NAME = "stopLeakedBackgroundExecutors";
	private static final String HOOK_DESC = "(Ljava/lang/ClassLoader;)V";
	private boolean announced;

	@Override
	public String name() {
		return "forbric-exit-hook";
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0) return classBytes;
		String exitMethod = EXIT_METHODS.get(className);
		if (exitMethod == null) return classBytes;
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		MethodNode close = null;
		for (MethodNode m : node.methods) {
			if (exitMethod.equals(m.name) && VOID.equals(m.desc)) {
				close = m;
				break;
			}
		}
		if (close == null) {
			ForbricLog.warn("[Forbric/Shutdown] no %s.%s()V to hook — leaked config file-watchers will not be stopped, and "
					+ "a JVM whose config changed during the session will not end on its own", className, exitMethod);
			return classBytes;
		}
		int returns = 0;
		for (AbstractInsnNode insn = close.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() != Opcodes.RETURN) continue;
			close.instructions.insertBefore(insn, hookCall(node.name));
			returns++;
		}
		if (returns == 0) return classBytes;
		close.maxStack = Math.max(close.maxStack, 1);
		if (!announced) {
			announced = true;
			ForbricLog.info("[Forbric/Shutdown] %s.%s now stops both loaders' config file-watchers before the JVM ends "
					+ "(%d return(s) hooked)", className, exitMethod, returns);
		}
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** {@code ClientShutdown.stopLeakedBackgroundExecutors(Minecraft.class.getClassLoader());} */
	private static InsnList hookCall(String owner) {
		InsnList call = new InsnList();
		call.add(new LdcInsnNode(Type.getObjectType(owner)));
		call.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getClassLoader", "()Ljava/lang/ClassLoader;", false));
		call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER, HOOK_NAME, HOOK_DESC, false));
		return call;
	}
}
