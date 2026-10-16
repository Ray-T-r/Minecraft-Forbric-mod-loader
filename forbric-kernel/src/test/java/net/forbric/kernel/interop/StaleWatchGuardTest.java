/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.interop;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A watch's handler runs for a change made after the watch was registered, and not for an event that finds the file as
 * it was then: what a directory already watched for another file reports about a file created in it just before its own
 * watch.
 */
class StaleWatchGuardTest {
	@TempDir Path dir;

	@Test void anEventThatFindsTheFileAsItWasAtRegistrationDoesNotRunTheHandler() throws Exception {
		Path file = dir.resolve("orchard-server.toml");
		Files.writeString(file, "ripe = true\n");          // the tracker creates it…
		AtomicInteger reloads = new AtomicInteger();
		Runnable watched = StaleWatchGuard.guard(file, reloads::incrementAndGet);   // …then registers its watch
		watched.run();                                     // the creation, reported late
		assertEquals(0, reloads.get());

		Files.writeString(file, "ripe = false\n");         // a real edit
		watched.run();
		assertEquals(1, reloads.get());
		watched.run();                                     // a second event for the same edit
		assertEquals(1, reloads.get());

		Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 5_000));
		watched.run();                                     // saved again unchanged: a change of time is a change
		assertEquals(2, reloads.get());

		Files.delete(file);
		watched.run();
		assertEquals(3, reloads.get(), "a deletion is a change");
	}

	@Test void aFileThatDidNotExistYetRunsTheHandlerOnceItAppears() throws Exception {
		Path file = dir.resolve("later.toml");
		AtomicInteger reloads = new AtomicInteger();
		Runnable watched = StaleWatchGuard.guard(file.toFile(), reloads::incrementAndGet);
		watched.run();
		assertEquals(0, reloads.get());
		Files.writeString(file, "x = 1\n");
		watched.run();
		assertEquals(1, reloads.get());
	}

	@Test void theSwitchAndANullFileHandBackTheHandlerItself() {
		Runnable handler = () -> { };
		assertSame(handler, StaleWatchGuard.guard((Path) null, handler));
		System.setProperty("forbric.staleWatchGuard", "off");
		try {
			assertSame(handler, StaleWatchGuard.guard(dir.resolve("a"), handler));
		} finally {
			System.clearProperty("forbric.staleWatchGuard");
		}
	}
}
