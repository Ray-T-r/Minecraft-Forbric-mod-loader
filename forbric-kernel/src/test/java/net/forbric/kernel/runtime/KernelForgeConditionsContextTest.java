package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** Source-structure pins for the Forge condition-context adapter (W5). */
class KernelForgeConditionsContextTest {
	private static final Path SOURCE = Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelForgeConditions.java");

	private static String body() throws Exception {
		String s = Files.readString(SOURCE, StandardCharsets.UTF_8);
		int start = s.indexOf("public static ICondition.IContext contextOf(");
		assertTrue(start >= 0, "contextOf is gone");
		return s.substring(start, s.indexOf("private static void report(String type)", start));
	}

	@Test
	void theSwitchIsReadPerCallAndAnswersEmpty() throws Exception {
		String b = body();
		assertTrue(b.contains("System.getProperty(CONTEXT_PROPERTY, \"on\")"));
		assertTrue(b.indexOf("return ICondition.IContext.EMPTY;") < b.indexOf("try {"),
				"the switch must answer EMPTY before NeoForge's context is even asked for");
	}

	@Test
	void aNullOrThrowingNeoForgeContextAnswersEmptyNotNull() throws Exception {
		String b = body();
		assertTrue(b.contains("if (neo == null) return ICondition.IContext.EMPTY;"));
		int catchBlock = b.indexOf("catch (Throwable t)");
		assertTrue(catchBlock > 0);
		assertTrue(b.substring(catchBlock).contains("return ICondition.IContext.EMPTY;"));
	}

	@Test
	void theAdapterIsAnAnonymousClassOverForgesInterfaceDelegatingGetTag() throws Exception {
		String b = body();
		assertTrue(b.contains("new ICondition.IContext() {"), "getTag is generic, so a lambda cannot implement it");
		assertTrue(b.contains("return neo.getTag(key);"));
		assertFalse(b.contains("(ICondition.IContext) neo"), "a raw cast would ClassCastException at first use");
	}
}
