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

package forbric.brokencanary;

import net.fabricmc.api.ModInitializer;

/**
 * A mod that fails the way real mods fail, on purpose.
 *
 * <p>Every gate in this tree asserts that NO mod failed — gate-m4 and gate-m7 both carry a
 * {@code check_absent "no @Mod construction failure"}. That is the right assertion for those gates and it is the
 * opposite of this one. Nothing anywhere proved the other half: that when a mod does fail, the instance carries
 * on and the failure is attributed.
 *
 * <p>Which matters because carrying on is Forbric's whole posture. It loads as much as it can rather than
 * stopping at the first problem, and the cost of that trade is exactly this: one mod's failure has to stay one
 * mod's failure, and it has to be findable afterwards. Both halves were asserted only by unit tests over
 * hand-built fixtures until this canary existed.
 *
 * <p>It throws from {@code onInitialize} rather than from a static initializer, because that is where a real mod
 * breaks: a missing transitive dependency, a changed vanilla signature, a config it cannot read. A
 * {@code <clinit>} failure is a different (and rarer) shape and would test the class loader instead.
 */
public final class ForbricBrokenCanary implements ModInitializer {

	/** Printed before throwing, so the gate can tell "never ran" apart from "ran and failed". */
	public static final String REACHED = "[ForbricBrokenCanary] reached onInitialize, about to fail on purpose";

	@Override
	public void onInitialize() {
		System.out.println(REACHED);
		throw new IllegalStateException("forbricbrokencanary fails on purpose — this is what the gate is for");
	}
}
