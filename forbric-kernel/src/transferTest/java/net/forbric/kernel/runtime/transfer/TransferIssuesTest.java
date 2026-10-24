package net.forbric.kernel.runtime.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * What a finding carries across to the boot side, which attributes it to a mod by the class it is handed. A provider's
 * NAME could not be followed back: a lambda (hidden class) is not found by name, and a dynamic proxy is found but was
 * defined from no jar -- gate-m39's proxy item handler made the boot side's lookup throw on every run. So the finding
 * carries the class whose code answers for the provider.
 */
class TransferIssuesTest {
	private final List<TransferIssues.Issue> issues = new CopyOnWriteArrayList<>();

	@AfterEach void restore() {
		TransferIssues.resetForTests();
		TransferIssues.setReporter(issue -> { });
	}

	private void capture() {
		TransferIssues.resetForTests();
		TransferIssues.setReporter(issues::add);
	}

	/** A handler a mod wrote; a proxy's code is its handler's. */
	static final class ModHandler implements InvocationHandler {
		@Override public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] arguments) { return null; }
	}

	@Test void aProxyProvidersSourceIsItsInvocationHandler() {
		capture();
		Object proxy = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { Runnable.class }, new ModHandler());
		TransferIssues.report("FORGE_HANDLER_NOT_ROLLBACK_SAFE", proxy, "detail");
		assertEquals(1, issues.size(), issues::toString);
		assertEquals(proxy.getClass().getName(), issues.getFirst().providerClass(), "the finding still names what was offered");
		assertSame(ModHandler.class, issues.getFirst().source());
	}

	@Test void aLambdaHandlersSourceIsTheLambdaItself() {
		capture();
		InvocationHandler lambda = (proxy, method, arguments) -> null;
		Object proxy = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { Runnable.class }, lambda);
		TransferIssues.report("FORGE_HANDLER_NOT_ROLLBACK_SAFE", proxy, "detail");
		// A hidden class: the boot side reads its jar from it directly (its definer's protection domain), never by name.
		assertSame(lambda.getClass(), issues.getFirst().source());
	}

	@Test void anOrdinaryProvidersSourceIsItsOwnClass() {
		capture();
		TransferIssues.report("UNSLOTTED_STORAGE", new StringBuilder(), "detail");
		assertSame(StringBuilder.class, issues.getFirst().source());
	}

	@Test void aFindingWithNoProviderOrOnlyANameCarriesNoSource() {
		capture();
		TransferIssues.report("FORGE_QUERY_HOOK_MISSING", null, "detail");
		TransferIssues.reportType("TRANSFER_HELPER_UNVERIFIED", "net.minecraftforge.items.ItemStackHandler", "detail");
		assertEquals(2, issues.size(), issues::toString);
		assertNull(issues.get(0).source());
		assertNull(issues.get(1).source());
	}
}
