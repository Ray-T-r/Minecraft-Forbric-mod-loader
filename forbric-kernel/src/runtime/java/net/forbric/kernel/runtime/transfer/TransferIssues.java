package net.forbric.kernel.runtime.transfer;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import net.forbric.kernel.util.ForbricLog;

/** The boot integration can attribute these runtime findings through its normal compatibility catalog. */
public final class TransferIssues {
	private TransferIssues() { }
	public record Issue(String code, String providerClass, String detail) { }
	private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
	private static volatile Consumer<Issue> reporter = issue -> ForbricLog.warn("[Forbric/Transfer] %s: %s — %s",
			issue.code(), issue.providerClass(), issue.detail());
	public static void setReporter(Consumer<Issue> sink) { reporter = java.util.Objects.requireNonNull(sink); }
	public static void report(String code, Object provider, String detail) {
		String owner = provider == null ? "unknown" : provider.getClass().getName();
		reportType(code, owner, detail);
	}
	public static void reportType(String code, String owner, String detail) {
		if (REPORTED.add(code + ":" + owner)) reporter.accept(new Issue(code, owner, detail));
	}
}
