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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * A future whose value is computed the first time anyone looks at it, and which from then on is an ordinary
 * completed future.
 *
 * <p>It exists for one field: NeoForge's {@code ModFile.futureScanResult}, which FML fills by
 * {@code startScan(executor)} — a background scan of every jar at discovery time. The kernel runs no FML
 * discovery and must not walk every jar at boot for the few mods that ever ask, and a seeded {@code ModFile} with
 * the field left null answers {@code getScanResult()} with "Scanning of this mod file has not started yet." So the
 * field holds this instead: "started", and done the moment it is read.
 *
 * <p>Every read path computes first — {@code get}, {@code join}, {@code getNow}, {@code resultNow} and the state
 * queries alike — so it never shows the half-state a real in-flight future has: a caller that checks
 * {@code isDone()} before {@code getNow(null)} gets the value, not its default. The composition methods
 * ({@code thenApply} and the rest) are left as they are; they are unreachable here, because the field is private
 * and {@code ModFile}'s only read of it is {@code get()} (verified on the carrier's bytecode: {@code startScan}
 * only null-checks it).
 *
 * <p>The supplier is expected to answer rather than throw. If it throws anyway, that becomes the future's
 * exceptional completion, which {@code ModFile.getScanResult()} reports the way it reports a failed FML scan.
 */
final class LazyScanFuture extends CompletableFuture<Object> {
	private Supplier<Object> compute;

	LazyScanFuture(Supplier<Object> compute) {
		this.compute = compute;
	}

	/**
	 * Runs the supplier once, whoever gets here first. The lock is held across the computation, so a second
	 * reader waits for the one scan rather than starting its own; everyone after reads the completed value.
	 */
	private void ensure() {
		if (super.isDone()) return;
		Supplier<Object> pending;
		synchronized (this) {
			pending = compute;
			compute = null;
			if (pending == null) return; // already ran, and has completed this future
			try {
				complete(pending.get());
			} catch (Throwable t) {
				completeExceptionally(t);
			}
		}
	}

	@Override
	public Object get() throws InterruptedException, ExecutionException {
		ensure();
		return super.get();
	}

	@Override
	public Object get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
		ensure();
		return super.get(timeout, unit);
	}

	@Override
	public Object join() {
		ensure();
		return super.join();
	}

	@Override
	public Object getNow(Object valueIfAbsent) {
		ensure();
		return super.getNow(valueIfAbsent);
	}

	@Override
	public Object resultNow() {
		ensure();
		return super.resultNow();
	}

	@Override
	public Throwable exceptionNow() {
		ensure();
		return super.exceptionNow();
	}

	@Override
	public boolean isDone() {
		ensure();
		return super.isDone();
	}

	@Override
	public boolean isCompletedExceptionally() {
		ensure();
		return super.isCompletedExceptionally();
	}

	@Override
	public State state() {
		ensure();
		return super.state();
	}
}
