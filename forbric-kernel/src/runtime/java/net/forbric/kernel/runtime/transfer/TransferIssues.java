package net.forbric.kernel.runtime.transfer;

import java.lang.reflect.Proxy;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import net.forbric.kernel.util.ForbricLog;

/** The boot integration can attribute these runtime findings through its normal compatibility catalog. */
public final class TransferIssues {
	private TransferIssues() { }
	/**
	 * {@code providerClass} is null when the finding has no provider object. {@code source} is the class whose code
	 * answers for the provider -- its own class, or for a dynamic proxy its invocation handler's -- and is what the boot
	 * side attributes the finding by; null when only a class name was reported.
	 *
	 * <p>It travels as a {@code Class} because the name alone cannot be followed back. A hidden class (a lambda) is not
	 * found by name at all, and a proxy is found but was defined from no jar: gate-m39's proxy item handler made the
	 * boot side's lookup throw on every run. The handler is the code the mod wrote, so it says whose provider it is.
	 */
	public record Issue(String code, String providerClass, String detail, Class<?> source) {
		public Issue(String code, String providerClass, String detail) { this(code, providerClass, detail, null); }
	}
	private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
	private static volatile Consumer<Issue> reporter = issue -> ForbricLog.warn("[Forbric/Transfer] %s: %s — %s",
			issue.code(), issue.providerClass() == null ? "unknown" : issue.providerClass(), issue.detail());
	public static void setReporter(Consumer<Issue> sink) { reporter = java.util.Objects.requireNonNull(sink); }
	/** Forgets which findings were reported, so a test sees its own. */
	static void resetForTests() { REPORTED.clear(); }
	public static void report(String code, Object provider, String detail) {
		if (provider == null) { reportType(code, null, detail); return; }
		String owner = provider.getClass().getName();
		if (REPORTED.add(code + ":" + owner)) reporter.accept(new Issue(code, owner, detail, source(provider)));
	}
	public static void reportType(String code, String owner, String detail) {
		if (REPORTED.add(code + ":" + owner)) reporter.accept(new Issue(code, owner, detail));
	}
	/** The class whose code answers for {@code provider}: a dynamic proxy's invocation handler, else its own class. */
	static Class<?> source(Object provider) {
		Class<?> type = provider.getClass();
		return Proxy.isProxyClass(type) ? Proxy.getInvocationHandler(provider).getClass() : type;
	}
}
