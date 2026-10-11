/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.forbric.api.Side;

/**
 * The kernel's mod-loading window opens once per process, however many triggers reach it: an entry can carry each
 * ecosystem's own copy of the call (a merge keeping MinecraftForge's {@code ServerModLoader.load()} in front of
 * NeoForge's {@code load(false)}), and LifecycleHookInjector redirects every one of them to the kernel.
 */
@ResourceLock("kernel-window")
class KernelLifecycleWindowOnceTest {
	@BeforeEach @AfterEach void close() {
		KernelLifecycle.forgetWindow();
	}

	@Test void theFirstTriggerOpensTheWindowAndEveryLaterOneRunsNothing() {
		assertTrue(KernelLifecycle.openWindow(Side.DEDICATED_SERVER), "the first trigger");
		assertFalse(KernelLifecycle.openWindow(Side.DEDICATED_SERVER), "the other ecosystem's copy of it");
		assertFalse(KernelLifecycle.openWindow(Side.CLIENT), "nor another entry's, once the window ran");
		KernelLifecycle.forgetWindow();
		assertTrue(KernelLifecycle.openWindow(Side.CLIENT), "closed again, the next trigger opens it");
	}
}
