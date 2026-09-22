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
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Gives {@code ServerChunkCache$MainThreadExecutor} an {@code execute} that refuses work once its own
 * thread is dead.
 *
 * <p><b>Hardening, not a repair.</b> Nothing here is broken by the merge or by Forbric: the merged
 * {@code ServerChunkCache} and Lithium's {@code getChunkOffThread} were both measured byte-for-byte
 * equivalent to vanilla. This exists because one class of MOD bug — keeping a {@code ServerLevel} from an
 * integrated server that has already stopped — cashes out as three permanently parked threads and a
 * 15-second watchdog kill whose crash report names nobody. See {@code KernelChunkExecutorGuard} for the
 * measured incident.
 *
 * <h2>Why the executor and not {@code getChunk}</h2>
 *
 * <p>{@code ServerChunkCache.getChunk} is the obvious place and the wrong one: Lithium {@code @Overwrite}s
 * it, so a guard there is deleted on any install that has Lithium — which is most of them, and was this
 * one. Both the merged base's own off-thread branch and Lithium's replacement reach the executor through
 * {@code this.mainThreadProcessor}, so guarding the executor covers both and cannot be overwritten out
 * from under itself.
 *
 * <h2>Why a synthesized override rather than an edit</h2>
 *
 * <p>{@code MainThreadExecutor} does not declare {@code execute} at all — it inherits
 * {@code BlockableEventLoop.execute}. Editing the base class would put this guard in front of EVERY event
 * loop in the game (the client's, the server's main loop, the IO worker's), which is a far larger
 * behavioural change than the one being made. Adding the override to the subclass keeps it to exactly the
 * executor that the incident named.
 *
 * <h2>Frames</h2>
 *
 * <p>The synthesized body is straight-line: load {@code this}, ask it for its running thread, hand that
 * to the guard, then {@code super.execute}. The branch lives in the guard, in Java, precisely so that no
 * jump is authored here and no {@code StackMapTable} entry has to be. That is why the guard takes a
 * {@code Thread} and decides for itself rather than returning a boolean this method would have to test.
 */
public final class ChunkExecutorGuardInjector implements ClassTransformer {
	private static final String TARGET = "net.minecraft.server.level.ServerChunkCache$MainThreadExecutor";
	private static final String TARGET_INTERNAL = "net/minecraft/server/level/ServerChunkCache$MainThreadExecutor";
	private static final String SUPER_INTERNAL = "net/minecraft/util/thread/BlockableEventLoop";
	private static final String GUARD_OWNER = "net/forbric/kernel/util/KernelChunkExecutorGuard";

	@Override
	public String name() {
		return "forbric-chunk-executor-guard";
	}

	@Override
	public AnchorSet anchors() {
		// HEDGE, deliberately. A REQUIRED anchor says "this instance is broken without me", and that is not
		// true: without this guard the game is exactly vanilla, and only a misbehaving mod can tell the
		// difference. Claiming otherwise would put a false positive on the Mods screen for every clean install.
		return AnchorSet.of(new AnchorSet.Anchor(TARGET, AnchorSet.Severity.HEDGE,
				"a mod holding a ServerLevel from a stopped integrated server parks the server thread on a future "
						+ "nothing can complete, and the client dies on the shutdown watchdog with a crash report "
						+ "naming three parked stacks and no cause"));
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0) return classBytes;
		if (!TARGET.equals(className)) return classBytes;

		ClassNode node = new ClassNode();
		// Flags 0: the frames are kept as nodes and written back unchanged. NOT SKIP_FRAMES, which DISCARDS
		// the StackMapTable and, with ClassWriter(0), ships a frameless class the verifier rejects.
		new ClassReader(classBytes).accept(node, 0);

		if (node.methods != null) {
			for (MethodNode existing : node.methods) {
				if ("execute".equals(existing.name) && "(Ljava/lang/Runnable;)V".equals(existing.desc)) {
					// Already has one — someone else got here first, or a future base declares it. Adding a
					// second would be an invalid class; saying nothing would hide that the guard is not on.
					ForbricLog.info("[Forbric/ChunkGuard] %s already declares execute(Runnable) — leaving it alone; "
							+ "the stopped-server guard is NOT installed", className);
					return classBytes;
				}
			}
		}

		MethodNode execute = new MethodNode(Opcodes.ACC_PUBLIC, "execute", "(Ljava/lang/Runnable;)V", null, null);
		execute.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		// getRunningThread() is protected on BlockableEventLoop and overridden here to return the cache's own
		// mainThread; invokevirtual on this, inside the declaring class, is the access that is legal.
		execute.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, TARGET_INTERNAL,
				"getRunningThread", "()Ljava/lang/Thread;", false));
		execute.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, GUARD_OWNER,
				"check", "(Ljava/lang/Thread;)V", false));
		execute.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		execute.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
		execute.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, SUPER_INTERNAL,
				"execute", "(Ljava/lang/Runnable;)V", false));
		execute.instructions.add(new InsnNode(Opcodes.RETURN));
		execute.maxStack = 2;
		execute.maxLocals = 2;
		node.methods.add(execute);

		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		ForbricLog.info("[Forbric/ChunkGuard] %s now refuses chunk work once its own thread is dead — hardening "
				+ "only: a mod that keeps a ServerLevel from a stopped integrated server gets a "
				+ "RejectedExecutionException naming itself instead of parking the server thread on a future "
				+ "nothing can complete", className);
		return writer.toByteArray();
	}
}
