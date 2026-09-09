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

package net.forbric.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The bridge inventory exists to turn a silent partial install into a loud one, so what is pinned here is that a
 * short set really does come back false — and that one pass cannot be satisfied by the other's work.
 */
class EventBridgesTest {
	private String switchBefore;

	@BeforeEach
	void clear() {
		switchBefore = System.getProperty(EventBridges.SWITCH_NAME);
		EventBridges.reset();
	}

	@AfterEach
	void restore() {
		if (switchBefore == null) System.clearProperty(EventBridges.SWITCH_NAME);
		else System.setProperty(EventBridges.SWITCH_NAME, switchBefore);
		EventBridges.reset();
	}

	@Test
	void aPassWithNothingInstalledDoesNotVerify() {
		assertFalse(EventBridges.verify(GameEventBridge.Pass.GAME_BUS),
				"no bridges installed must not read as success — that was the old silent failure");
	}

	@Test
	void aPassVerifiesOnlyWhenEveryOneOfItsBridgesIsIn() {
		for (GameEventBridge b : GameEventBridge.values()) {
			if (b.pass() == GameEventBridge.Pass.GAME_BUS && b != GameEventBridge.SERVER_STARTED) {
				EventBridges.installed(b);
			}
		}
		assertFalse(EventBridges.verify(GameEventBridge.Pass.GAME_BUS),
				"four of five is exactly the case that used to pass unnoticed");

		EventBridges.installed(GameEventBridge.SERVER_STARTED);
		assertTrue(EventBridges.verify(GameEventBridge.Pass.GAME_BUS));
	}

	/** The two passes run at different times; the client one must not be credited to the game-bus one. */
	@Test
	void thePassesAreAccountedSeparately() {
		EventBridges.installed(GameEventBridge.CLIENT_RELOAD_LISTENERS);

		assertTrue(EventBridges.verify(GameEventBridge.Pass.CLIENT_MOD_BUS));
		assertFalse(EventBridges.verify(GameEventBridge.Pass.GAME_BUS),
				"a client-side install must not satisfy the game-bus pass");
	}

	@Test
	void theSwitchIsOnByDefaultAndOffOnlyForTheExactValue() {
		System.clearProperty(EventBridges.SWITCH_NAME);
		assertTrue(EventBridges.enabled());

		System.setProperty(EventBridges.SWITCH_NAME, "off");
		assertFalse(EventBridges.enabled(), "this is the gate's negative control");

		System.setProperty(EventBridges.SWITCH_NAME, "on");
		assertTrue(EventBridges.enabled());
	}

	/**
	 * The enum carries a cost string rather than a bare count so the warning says what the player is about to
	 * experience. A blank one would make the loud failure useless again.
	 */
	@Test
	void everyBridgeSaysWhatItsAbsenceCosts() {
		for (GameEventBridge b : GameEventBridge.values()) {
			assertTrue(b.cost() != null && b.cost().length() > 20, b + " must describe what breaks without it");
			assertTrue(b.event() != null && !b.event().isBlank(), b + " must name its event");
		}
	}
}
