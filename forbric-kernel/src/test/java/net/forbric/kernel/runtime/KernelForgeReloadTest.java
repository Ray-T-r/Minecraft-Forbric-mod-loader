package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Source-structure pins for the server reload bridge (the {@code KernelGameServerLifecycleTest} shape): what
 * matters is which calls sit inside which guard, and that is a property of the text.
 */
class KernelForgeReloadTest {
	private static final Path SOURCE = Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelForgeReload.java");

	private static String source() throws Exception {
		return Files.readString(SOURCE, StandardCharsets.UTF_8);
	}

	@Test
	void neoForgesHookRunsFirstAndUnconditionally() throws Exception {
		String s = source();
		int neo = s.indexOf("EventHooks.onResourceReload(resources, registries, retained)");
		int off = s.indexOf("System.getProperty(PROPERTY");
		int forge = s.indexOf("ForgeEventFactory.onResourceReload(");
		assertTrue(neo >= 0 && off >= 0 && forge >= 0);
		assertTrue(neo < off && off < forge,
				"NeoForge's hook must run before the switch is even consulted, and before Forge's post — turning "
						+ "the bridge off must leave the NeoForge path exactly as it was");
	}

	@Test
	void theForgePostIsGuardedAndItsFailureReturnsNeoForgesList() throws Exception {
		String s = source();
		int forge = s.indexOf("ForgeEventFactory.onResourceReload(");
		int tryBlock = s.lastIndexOf("try {", forge);
		int catchBlock = s.indexOf("catch (Throwable t)", forge);
		assertTrue(tryBlock >= 0 && tryBlock < forge && catchBlock > forge,
				"the carrier post must be inside a try whose catch follows it");
		String handler = s.substring(catchBlock, s.indexOf('}', catchBlock));
		assertTrue(handler.contains("return neo;"), "a failing Forge post must cost Forge's listeners, not the reload");
		assertTrue(handler.contains("Reflect.unwrap(t)"));
	}

	@Test
	void theSwitchIsReadPerCallAndForgeGetsTheRegistryLookupNotTheRegistryAccess() throws Exception {
		String s = source();
		assertFalse(s.contains("static final boolean"), "the switch must be read per call, never cached");
		assertTrue(s.contains("\"off\".equalsIgnoreCase(System.getProperty(PROPERTY, \"on\"))"));
		assertTrue(s.contains("resources.getRegistryLookup(), neo)"),
				"Forge's event takes the HolderLookup.Provider the resources were built with, not the RegistryAccess");
	}
}
