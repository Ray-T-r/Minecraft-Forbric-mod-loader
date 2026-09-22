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

package net.forbric.kernel.util;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * Refuses work offered to a chunk-cache executor whose thread is already dead.
 *
 * <p><b>This is HARDENING, not a repair.</b> It fixes no defect of Forbric's and no defect of the merged
 * base — the merged {@code ServerChunkCache} and Lithium's {@code getChunkOffThread} were both measured
 * byte-for-byte equivalent to vanilla. It exists so that one particular way for a MOD to be wrong stops
 * costing the player an unreadable death.
 *
 * <h2>The shape it catches</h2>
 *
 * <p>Measured on the player's pack, 2026-09-22 11:47:03. A mod kept a {@code ServerLevel} belonging to an
 * integrated server that had already stopped: its background worker survived a
 * {@code Thread.interrupt()} + {@code join(5000)} that timed out (5.001 s, the worker's loop being pure
 * CPU and never checking the interrupt), then re-populated the mod's own dimension map with the dead
 * server's level after the shutdown handler had cleared it. On the next world entry the mod submitted
 * chunk work through that stale level, so a live server thread ended up in
 * {@code CompletableFuture.supplyAsync(..., deadServer.mainThreadProcessor).join()}.
 *
 * <p>Nothing was ever going to run that task, and {@code join()} is uninterruptible, so three threads
 * parked forever — the server thread on the future, the mod's map thread on the server thread, and the
 * render thread on the map thread — until Mojang's 15-second shutdown watchdog killed the process. The
 * crash report named three parked stacks and no cause. The player's report was "it froze when I closed
 * the window".
 *
 * <h2>Why the liveness of the executor's thread, and not {@code MinecraftServer.isRunning()}</h2>
 *
 * <p>{@code isRunning()} goes false at the TOP of {@code stopServer}, and a great deal of legitimate
 * chunk work — saving every loaded chunk, for one — is submitted after that. Gating on it would reject
 * the shutdown's own work and break saving, trading a rare hang for a common data loss.
 *
 * <p>The executor's own running thread is the exact question instead: while it is alive the queue will
 * be drained and this guard can never fire, including all through a normal shutdown. Once it is dead
 * nothing will ever drain that queue again, so every {@code join()} on a task offered to it is an
 * infinite park by construction. Rejecting is not a policy choice there; it is stating what is already
 * true.
 *
 * <h2>What the caller gets</h2>
 *
 * <p>It lives in {@code net.forbric.kernel.util}, which {@code DelegationPolicy} pins ALWAYS_PARENT, rather
 * than beside the game-side bridges: it names no game type at all, only {@link Thread}. Parent-loaded is
 * therefore both sufficient — game classes already resolve {@code ForbricLog} from this package — and
 * better, because the decision it makes is then testable on a machine with no staged Minecraft artifacts,
 * and that decision is the whole risk.
 *
 * <p>A {@link RejectedExecutionException} out of {@code Executor.execute}, which
 * {@code CompletableFuture.supplyAsync} propagates synchronously to whoever asked for the chunk. That
 * unwinds into the offending mod's own frame, so the failure is attributed where it belongs and the
 * shutdown completes. A crash naming the mod is worse than no crash and far better than a frozen client
 * whose report names nobody.
 */
public final class KernelChunkExecutorGuard {
	private static final AtomicBoolean SAID = new AtomicBoolean();

	private KernelChunkExecutorGuard() {
	}

	/**
	 * @param runningThread the executor's own thread, i.e. the only thread that can ever drain its queue.
	 *                      Null is treated as alive: an executor that will not say which thread it belongs
	 *                      to is not evidence that the thread is gone, and a guard must never invent one.
	 */
	public static void check(Thread runningThread) {
		if (runningThread == null || runningThread.isAlive()) return;

		// Once per process. This fires on a path that repeats, and the first one carries the diagnosis.
		if (SAID.compareAndSet(false, true)) {
			ForbricLog.warn("[Forbric/ChunkGuard] refusing chunk work offered to the executor of a server that has "
					+ "already stopped (its thread '%s' is dead) — something is holding a ServerLevel from a "
					+ "previous integrated server, and without this the caller would join() a future nothing can "
					+ "ever complete. Expect a RejectedExecutionException naming the culprit in the frame below "
					+ "the chunk request; that stack is the bug report", runningThread.getName());
		}
		throw new RejectedExecutionException("Forbric: chunk work was offered to the MainThreadExecutor of a "
				+ "stopped server (thread '" + runningThread.getName() + "' is dead). The caller below this "
				+ "frame is holding a ServerLevel from a previous integrated server.");
	}
}
