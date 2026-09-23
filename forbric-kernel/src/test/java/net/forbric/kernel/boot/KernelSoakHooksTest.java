package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class KernelSoakHooksTest {
	public static final class FakeMinecraft {
		int stops;
		public void stop() { stops++; }
	}

	@AfterEach void clear() { System.clearProperty("forbric.clientSoak"); }

	@Test void aControllerThatCannotStartStopsTheOwnedClientInsteadOfIdling() {
		System.setProperty("forbric.clientSoak", "true");
		FakeMinecraft minecraft = new FakeMinecraft();
		// Not a Minecraft: the controller either fails to link here or rejects the object; both reach the catch.
		KernelSoakHooks.onClientTick(minecraft);
		assertEquals(1, minecraft.stops, "the FATAL path must request the normal client stop");
		KernelSoakHooks.onClientTick(minecraft);
		assertEquals(1, minecraft.stops, "later ticks stay inert after the one stop request");
	}

	@Test void ordinarySessionsNeverTouchTheClient() {
		FakeMinecraft minecraft = new FakeMinecraft();
		KernelSoakHooks.onClientTick(minecraft);
		assertEquals(0, minecraft.stops);
	}
}
