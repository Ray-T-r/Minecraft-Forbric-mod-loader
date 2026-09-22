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

import java.util.Locale;

import net.forbric.kernel.util.ForbricLog;

/**
 * How long this server's ticks actually take.
 *
 * <h2>Why a loader needs to know</h2>
 *
 * <p>There is no tick-time, frame-time, TPS or memory measurement anywhere in this tree. A mod whose entire
 * value is a number — Sodium, Lithium, FerriteCore — proves nothing here by loading, and when a player says
 * this loader is slow, nothing in the project can agree or disagree. Meanwhile the architecture is additive by
 * construction: every event may go to three buses, there are dozens of bridges, dozens of bytecode repairs, a
 * frame recomputer and a pile of pre-staged resource packs.
 *
 * <p>Vanilla's own "Can't keep up!" is a threshold crossing and says nothing until it is crossed. This is the
 * distribution underneath it, so a bug report carries the number instead of an adjective.
 *
 * <h2>What it measures, exactly</h2>
 *
 * <p>The interval between consecutive tick STARTS, which is what a tick rate is. Hooking entry only, rather than
 * entry and every exit, is deliberate: the method has several returns, and the template this follows
 * ({@code ClientSmokeTickInjector}) records the same reasoning. The first interval after a pause — a server with
 * nobody on it stops ticking — is discarded rather than counted as a slow tick.
 *
 * <p><b>The interval includes the sleep, so the MEAN is not the signal.</b> A server that is keeping up holds
 * 50ms exactly, by sleeping off whatever the tick did not use; the first live run read "mean 50.00ms" and, with
 * the threshold at the budget itself, called 48% of ticks late — which was measuring jitter around the target
 * and nothing else. The tail is the signal: an interval at twice the budget means the server could not sleep,
 * and a healthy run has none.
 *
 * <p>On by default, because a measurement nobody switches on is a measurement nobody has;
 * {@code -Dforbric.tickSampler=off} turns it off, and it costs one {@code nanoTime} and a few adds per tick.
 */
public final class KernelServerTicks {

	public static final String SWITCH = "forbric.tickSampler";
	/** Minecraft's own tick budget: 20 ticks a second. A healthy interval sits AT this, not under it. */
	static final long BUDGET_NANOS = 50_000_000L;
	/** Twice the budget: the server had no sleep left to give back, which is lateness rather than jitter. */
	static final long LATE_NANOS = 2 * BUDGET_NANOS;
	/** An interval longer than this is a pause resuming, not a slow tick. */
	static final long PAUSE_NANOS = 2_000_000_000L;
	/** How many ticks between summary lines — 600 is thirty seconds at the intended rate. */
	public static final int REPORT_EVERY = Integer.getInteger("forbric.tickSamplerEvery", 600);
	/**
	 * Ticks before the FIRST line, which is much sooner.
	 *
	 * <p>A gate's server lives about twenty-four seconds — under 600 ticks — so with only the periodic line, a
	 * run that sampled the whole session printed nothing, and an assertion on that line was asserting on
	 * something a short run legitimately never prints. A first line early means any run that ticks at all says
	 * what it measured; the periodic one then carries the long ones.
	 */
	static final int FIRST_REPORT = Math.min(REPORT_EVERY, Integer.getInteger("forbric.tickSamplerFirst", 100));

	private static volatile boolean enabled = !"off".equalsIgnoreCase(System.getProperty(SWITCH, "on"));
	/** Whether {@link #previous} holds a reading. A zero sentinel would not: nanoTime may legitimately be 0. */
	private static boolean started;
	private static long previous;
	private static long count;
	private static long sum;
	private static long max;
	private static long overBudget;
	private static long resumed;
	private static long sinceReport;
	private static boolean reportedOnce;

	private KernelServerTicks() {
	}

	/** Called from the head of the server tick. Never throws: a sampler that can stop a server is not worth it. */
	public static void onServerTick() {
		try {
			if (!enabled) return;
			sample(System.nanoTime());
		} catch (Throwable neverStopTheServer) {
			enabled = false;
		}
	}

	/** The accounting, separated from the clock so it can be driven with known values. */
	static synchronized void sample(long now) {
		long last = previous;
		boolean had = started;
		previous = now;
		started = true;
		if (!had) return;
		long delta = now - last;
		if (delta <= 0L) return;
		if (delta > PAUSE_NANOS) {
			// Nobody was on, so the server stopped ticking. Counting that as one very slow tick would put a
			// number in the log that is true of the clock and false of the server.
			resumed++;
			return;
		}
		count++;
		sum += delta;
		if (delta > max) max = delta;
		if (delta > LATE_NANOS) overBudget++;
		sinceReport++;
		if (sinceReport >= REPORT_EVERY || (!reportedOnce && count >= FIRST_REPORT)) {
			sinceReport = 0;
			reportedOnce = true;
			ForbricLog.info("%s", summary());
		}
	}

	/** The one line a gate greps: what was measured, then what it says. */
	public static synchronized String summary() {
		if (count == 0) {
			return "[Forbric/Tick] no server ticks sampled yet" + (resumed > 0 ? " (" + resumed + " resume(s) after a pause)" : "");
		}
		double meanMs = sum / (double) count / 1_000_000d;
		double maxMs = max / 1_000_000d;
		return String.format(Locale.ROOT,
				"[Forbric/Tick] %d tick(s): mean %.2fms (a server keeping up holds 50), max %.2fms, "
						+ "at twice the budget or worse %d (%.1f%%), resumes after a pause %d",
				count, meanMs, maxMs, overBudget, 100d * overBudget / count, resumed);
	}

	/** Forgets everything. For tests, and for a second server in one process. */
	static synchronized void reset() {
		started = false;
		previous = 0;
		count = 0;
		sum = 0;
		max = 0;
		overBudget = 0;
		resumed = 0;
		sinceReport = 0;
		reportedOnce = false;
		enabled = !"off".equalsIgnoreCase(System.getProperty(SWITCH, "on"));
	}

	public static boolean enabled() {
		return enabled;
	}

	static synchronized long sampled() {
		return count;
	}
}
