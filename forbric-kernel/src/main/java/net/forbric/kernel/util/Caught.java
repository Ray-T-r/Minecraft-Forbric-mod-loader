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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Where every exception the kernel catches and does not rethrow goes, so that none of them is lost.
 *
 * <h2>Why</h2>
 *
 * <p>The kernel is fail-soft by design: a repair whose shape does not match declines, a bridge whose other side is
 * missing stands down, a mod's listener that throws does not take the other mods' listeners with it. Each of those
 * is a {@code catch}, and before this class each {@code catch} decided on its own whether anybody would hear about
 * it. About half of them said nothing at all, a hundred and fifty more said it only under {@code -Dforbric.debug},
 * and the event forwarders said it once and then never again, so the second and every later failure of a mod's
 * listener vanished. A bug that lives in one of those paths is invisible in the log a player sends and invisible to
 * every test, which only ever sees that the game kept running.
 *
 * <h2>The two answers a catch can give</h2>
 *
 * <ul>
 * <li>{@link #warn}/{@link #error}: something went wrong and the kernel carried on. Always logged, at the level
 *     named, with the full stack trace the first time a site is hit and one line for each of the next
 *     {@value #DEFAULT_PRINTED_PER_SITE} (set with {@code -Dforbric.caught.printedPerSite}). Past that the same
 *     message again is counted instead of printed, and {@link #logSummary} names the site with its count, so a
 *     listener that throws every tick neither floods the log nor disappears from it. A message the site has not
 *     said before is printed anyway (up to {@value #DISTINCT_PER_SITE} of them), so a helper that forwards many
 *     events still names every event that failed. Every one of them counts for {@link #errorCount}, which is
 *     what the unit tests' {@code CaughtErrorsExtension} checks after each test.
 * <li>{@link #expected}: the exception is the answer, not a failure — a class probe that says "not installed", a
 *     bytecode shape that is not the one a repair is for, an exception handed on as a value to the code that does
 *     report it. Counted, and logged only under {@code -Dforbric.debug}.
 * </ul>
 *
 * <p>{@code EveryCatchIsVisibleTest} holds every {@code catch} in the kernel to one of these or to a rethrow, so a
 * new silent one cannot be written.
 *
 * <p>With {@code FORBRIC_CAUGHT_FILE} (or {@code -Dforbric.caught.file}) naming a file, every failure site is also
 * appended there the first time it is hit, and its count when the JVM exits. The gates set it, so a gate reports
 * what the kernel caught in every JVM it started, server and client alike, whatever its own checks look at.
 *
 * <p>A site is the first stack frame outside this class: the {@code catch} that called it. This class never throws.
 */
public final class Caught {
	/** Every line this class writes starts with it; the gates grep for it. */
	public static final String PREFIX = "[Forbric/Caught]";
	static final int DEFAULT_PRINTED_PER_SITE = 20;
	private static final int PRINTED_PER_SITE = Math.max(1,
			Integer.getInteger("forbric.caught.printedPerSite", DEFAULT_PRINTED_PER_SITE));
	static final int DISTINCT_PER_SITE = 100;
	private static final StackWalker WALKER = StackWalker.getInstance();
	private static final String SELF = Caught.class.getName();

	private static final Path SINK = sink();

	private static final ConcurrentHashMap<String, Site> SITES = new ConcurrentHashMap<>();
	private static final AtomicLong ERRORS = new AtomicLong();
	private static final AtomicBoolean HOOKED = new AtomicBoolean();

	/** What a catch said about what it caught. */
	public enum Kind {
		/** Logged at WARN. A failure. */
		WARN,
		/** Logged at ERROR. A failure. */
		ERROR,
		/** Not a failure: the exception was the answer. */
		EXPECTED;

		boolean failure() {
			return this != EXPECTED;
		}
	}

	/** One catch site and what it has caught so far. */
	public static final class Site {
		private final String where;
		private final Kind kind;
		private final String firstMessage;
		private final Throwable first;
		private final AtomicLong count = new AtomicLong();
		private final java.util.Set<String> messages = ConcurrentHashMap.newKeySet();

		Site(String where, Kind kind, String firstMessage, Throwable first) {
			this.where = where;
			this.kind = kind;
			this.firstMessage = firstMessage;
			this.first = first;
		}

		/** {@code Class.method:line} of the catch. */
		public String where() {
			return where;
		}

		public Kind kind() {
			return kind;
		}

		/** The message the first occurrence was logged with ({@code null} for {@link Kind#EXPECTED}). */
		public String firstMessage() {
			return firstMessage;
		}

		public Throwable first() {
			return first;
		}

		public long count() {
			return count.get();
		}

		@Override
		public String toString() {
			int distinct = messages.size();
			return count() + "x " + kind + " at " + where + (firstMessage == null ? "" : ": " + firstMessage)
					+ (distinct > 1 ? " (and " + (distinct - 1) + " other message(s))" : "") + " — " + describe(first);
		}
	}

	private Caught() {
	}

	/**
	 * A failure the kernel carried on from, logged at WARN.
	 *
	 * @param thrown what was caught
	 * @param format what failed and what happens instead, as a {@link String#format} pattern
	 * @param args   its arguments
	 */
	public static void warn(Throwable thrown, String format, Object... args) {
		failure(Kind.WARN, thrown, format, args);
	}

	/** A failure the kernel carried on from, logged at ERROR. See {@link #warn}. */
	public static void error(Throwable thrown, String format, Object... args) {
		failure(Kind.ERROR, thrown, format, args);
	}

	/**
	 * An exception that is the answer rather than a failure: counted, and logged only under {@code -Dforbric.debug}.
	 *
	 * <p>Use it only where nothing went wrong. A repair that declines because the bytecode is not the shape it is
	 * for is expected; a repair that declines because reading the bytecode threw something nobody anticipated is
	 * not, and belongs in {@link #warn}.
	 */
	public static void expected(Throwable thrown) {
		try {
			Throwable bug = programmingError(thrown);
			if (bug != null) {
				failure(Kind.WARN, thrown, "a catch that treats this exception as an answer caught a programming "
						+ "error (%s) — the kernel's own bug, reported as a failure", bug);
				return;
			}
			String where = where();
			Site site = SITES.computeIfAbsent(key(where, Kind.EXPECTED), k -> new Site(where, Kind.EXPECTED, null, thrown));
			long n = site.count.incrementAndGet();
			if (n == 1 && ForbricLog.debugEnabled()) {
				ForbricLog.debug(PREFIX + " expected at " + where + ": " + describe(thrown));
			}
		} catch (Throwable broken) {
			last(broken, thrown);
		}
	}

	private static void failure(Kind kind, Throwable caught, String format, Object... args) {
		// Reflection's wrapper says only that the call threw; the stack worth printing is what it threw.
		Throwable unwrapped = caught;
		while ((unwrapped instanceof InvocationTargetException || unwrapped instanceof UndeclaredThrowableException)
				&& unwrapped.getCause() != null) {
			unwrapped = unwrapped.getCause();
		}
		Throwable thrown = unwrapped;
		try {
			String message = render(format, args);
			String where = where();
			ERRORS.incrementAndGet();
			hookSummary();
			Site site = SITES.computeIfAbsent(key(where, kind), k -> new Site(where, kind, message, thrown));
			long n = site.count.incrementAndGet();
			// A message this site has not said before is always printed (up to DISTINCT_PER_SITE of them): one
			// forwarding helper is one site for every event it forwards, and the twenty-first event to fail there
			// must still be named. Only the same message again is subject to the per-site cap.
			boolean fresh = site.messages.size() < DISTINCT_PER_SITE && site.messages.add(message);
			if (n == 1) {
				emit(kind, PREFIX + " " + message + " — caught at " + where, thrown);
				append("FAILURE\t" + kind + '\t' + where + '\t' + flat(message) + '\t' + flat(describe(thrown)));
			} else if (fresh || n <= PRINTED_PER_SITE) {
				emit(kind, PREFIX + " " + message + " — caught at " + where + " again (#" + n + "): " + describe(thrown)
						+ (n == PRINTED_PER_SITE ? "; later repeats here are counted, not printed (see the summary)" : ""),
						null);
			}
		} catch (Throwable broken) {
			last(broken, thrown);
		}
	}

	/**
	 * The exception in {@code thrown}'s cause chain that only a bug throws, or null.
	 *
	 * <p>Most {@link #expected} catches are wider than the answer they expect. A bytecode recogniser catches
	 * {@code AnalyzerException} to mean "not this shape", and ASM's {@code Analyzer} wraps whatever its interpreter
	 * threw in exactly that exception, a {@code NullPointerException} from the kernel's own interpreter included; a
	 * presence probe catches {@code RuntimeException} around a reflective call whose target may itself fail. None of
	 * the exceptions listed here is ever an answer when Forbric's own code threw it: something dereferenced null,
	 * cast to the wrong type or indexed past the end. Linkage errors are left out on purpose — a probe of an optional mod's API meets them legitimately
	 * when the installed version is older.
	 */
	static Throwable programmingError(Throwable thrown) {
		int depth = 0;
		for (Throwable t = thrown; t != null && depth < 16; t = t.getCause(), depth++) {
			// Only Forbric's own code counts. ASM's analysis Frame says "this bytecode does not balance" with an
			// IndexOutOfBoundsException of its own, and NeoForge's ModList dereferences null for a container it has
			// not published yet: those are answers about the code analysed or the code asked, not kernel bugs.
			if (!thrownByForbric(t)) continue;
			if (t instanceof NullPointerException || t instanceof ClassCastException
					|| t instanceof IndexOutOfBoundsException || t instanceof ArithmeticException
					|| t instanceof NegativeArraySizeException || t instanceof ArrayStoreException
					|| t instanceof IllegalMonitorStateException || t instanceof java.util.ConcurrentModificationException
					|| t instanceof StackOverflowError) {
				return t;
			}
			if (t.getCause() == t) break;
		}
		return null;
	}

	/** Whether the first frame of {@code t} outside the JDK (which throws on its callers' behalf) is Forbric's. */
	private static boolean thrownByForbric(Throwable t) {
		for (StackTraceElement frame : t.getStackTrace()) {
			String owner = frame.getClassName();
			if (owner.startsWith("java.") || owner.startsWith("javax.") || owner.startsWith("jdk.")
					|| owner.startsWith("sun.")) {
				continue;
			}
			return owner.startsWith("net.forbric.");
		}
		return false;
	}

	/** Failures caught so far, every site and every occurrence. */
	public static long errorCount() {
		return ERRORS.get();
	}

	/** Every site hit so far, most-hit first. */
	public static List<Site> sites() {
		List<Site> all = new ArrayList<>(SITES.values());
		all.sort(Comparator.comparingLong(Site::count).reversed().thenComparing(Site::where));
		return all;
	}

	/** Every failure site hit so far, most-hit first. */
	public static List<Site> failureSites() {
		List<Site> out = new ArrayList<>();
		for (Site site : sites()) if (site.kind().failure()) out.add(site);
		return out;
	}

	/**
	 * Logs how many failures were caught and where, one line per site.
	 *
	 * <p>Runs by itself when the JVM exits; call it where a stage ends to have it in the log there too. Says
	 * nothing when nothing failed.
	 */
	public static void logSummary(String when) {
		try {
			List<Site> failures = failureSites();
			if (failures.isEmpty()) return;
			long unprinted = 0;
			for (Site site : failures) unprinted += Math.max(0, site.count() - PRINTED_PER_SITE);
			ForbricLog.warn(PREFIX + " summary " + when + ": " + errorCount() + " failure(s) caught at " + failures.size()
					+ " site(s)" + (unprinted > 0 ? ", " + unprinted + " of them counted, not printed" : ""));
			for (Site site : failures) ForbricLog.warn(PREFIX + "   " + site);
		} catch (Throwable broken) {
			last(broken, null);
		}
	}

	/** Forgets everything caught so far. For tests. */
	public static void reset() {
		SITES.clear();
		ERRORS.set(0);
	}

	private static void hookSummary() {
		if (!HOOKED.compareAndSet(false, true)) return;
		try {
			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				logSummary("at exit");
				for (Site site : failureSites()) append("COUNT\t" + site.kind() + '\t' + site.where() + '\t' + site.count());
			}, "Forbric caught-summary"));
		} catch (IllegalStateException alreadyExiting) {
			// The JVM is shutting down already, so there is no exit left to summarise at; the failure itself was
			// printed in full by failure().
		}
	}

	private static Path sink() {
		String named = System.getProperty("forbric.caught.file");
		if (named == null || named.isBlank()) named = System.getenv("FORBRIC_CAUGHT_FILE");
		if (named == null || named.isBlank()) return null;
		try {
			return Path.of(named);
		} catch (RuntimeException invalid) {
			System.err.println(PREFIX + " FORBRIC_CAUGHT_FILE is not a usable path, so nothing is written to it: " + invalid);
			return null;
		}
	}

	/** One line to {@link #SINK}, if there is one. Lines from several JVMs may interleave, never mix. */
	private static synchronized void append(String line) {
		if (SINK == null) return;
		try {
			Files.writeString(SINK, line + System.lineSeparator(), StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
		} catch (Throwable unwritable) {
			last(unwritable, null);
		}
	}

	private static String flat(String text) {
		return text == null ? "" : text.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
	}

	private static String key(String where, Kind kind) {
		return kind.name() + ' ' + where;
	}

	private static String where() {
		Optional<StackWalker.StackFrame> frame = WALKER.walk(frames -> frames
				.filter(f -> !f.getClassName().equals(SELF))
				.findFirst());
		return frame.map(f -> f.getClassName() + "." + f.getMethodName() + ":" + f.getLineNumber()).orElse("?");
	}

	private static String render(String format, Object... args) {
		if (format == null) return "(no message)";
		return ForbricLog.render(format, args).message();
	}

	private static void emit(Kind kind, String line, Throwable thrown) {
		if (kind == Kind.ERROR) {
			if (thrown == null) ForbricLog.error(line);
			else ForbricLog.error(line, thrown);
		} else {
			if (thrown == null) ForbricLog.warn(line);
			else ForbricLog.warn(line, thrown);
		}
	}

	/** The exception a reflective call or a static initialiser wrapped, and what it said. */
	static String describe(Throwable thrown) {
		if (thrown == null) return "(nothing thrown)";
		Throwable t = thrown;
		while ((t instanceof InvocationTargetException || t instanceof UndeclaredThrowableException
				|| t instanceof ExceptionInInitializerError) && t.getCause() != null) {
			t = t.getCause();
		}
		return String.valueOf(t);
	}

	/** This class failed. Say so where something will see it, and never throw from a catch block. */
	private static void last(Throwable broken, Throwable thrown) {
		try {
			System.err.println(PREFIX + " could not record a caught exception (" + broken + "): " + thrown);
		} catch (Throwable ignored) {
			// Nothing is left to report to.
		}
	}
}
