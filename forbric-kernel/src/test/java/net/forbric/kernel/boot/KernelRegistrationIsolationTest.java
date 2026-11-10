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

package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

/**
 * Calls in the registration window that used to cost far more than themselves when they failed.
 *
 * <p>These are bytecode-shape assertions, in the idiom {@link KernelLifecycleFailureReportingTest} already uses,
 * because what is being pinned is structural: whether a call sits inside a handler of its own. Driving the real
 * failure would need a live game and a mod built to throw, and the property does not need one — the call either
 * has its own try/catch or it does not.
 */
class KernelRegistrationIsolationTest {
	private static final Path CLASSES =
			Path.of(System.getProperty("user.dir"), "build", "classes", "java", "main").normalize();

	@Test
	void theRegisterEventDispatchCannotTakeTheRestOfTheWindowWithIt() throws Exception {
		// NeoForge's GameData.postRegisterEvents is called reflectively, so a LinkageError or NeoForge's own roll-back
		// throw reaches dispatchRegisterEvents. It must stop there: the window's outer catch would skip the Fabric main
		// entrypoints (run from the finally, if the seam never reached them), the content report and everything after.
		// The handler that covers the call must NOT also cover the finally's Fabric step, or a failure takes it too.
		MethodNode dispatch = method("net/forbric/kernel/boot/KernelLifecycle", "dispatchRegisterEvents");
		assertTrue(dispatch != null,
				"KernelLifecycle.dispatchRegisterEvents not found in the compiled src/main classes, which exist before any "
						+ "test runs");
		assertTrue(caughtAndIsolatedFrom(dispatch, "postRegisterEvents", "runAfterDispatch"),
				"postRegisterEvents must sit in a Throwable handler of its own that does not also cover the Fabric step");
		MethodNode window = method("net/forbric/kernel/boot/KernelLifecycle", "registerNeoForgeContent");
		assertTrue(window != null && indexOfCall(window, "dispatchRegisterEvents") >= 0,
				"registerNeoForgeContent must dispatch RegisterEvent through dispatchRegisterEvents");
	}

	@Test
	void theSiblingCallsThisWasModelledOnAreStillGuardedToo() {
		// If registerAll ever loses ITS handler, the assertion above is still green while the same class of
		// failure is back -- so the model is pinned as well as the copy.
		MethodNode window = method("net/forbric/kernel/boot/KernelLifecycle", "registerNeoForgeContent");
		assertTrue(window != null,
				"KernelLifecycle.registerNeoForgeContent not found in the compiled src/main classes, which exist "
						+ "before any test runs");

		assertTrue(isolatedFrom(window, "registerAll", "dispatchRegisterEvents"),
				"KernelEventSubscribers.registerAll is guarded for the same reason and its comment says so");
	}

	/** True when a catch-all (Throwable) handler covers {@code calleeName} and does NOT cover {@code survivorName}. */
	private static boolean caughtAndIsolatedFrom(MethodNode method, String calleeName, String survivorName) {
		int at = indexOfCall(method, calleeName);
		int survivor = indexOfCall(method, survivorName);
		if (at < 0 || survivor < 0) return false;
		for (TryCatchBlockNode block : method.tryCatchBlocks) {
			if (!"java/lang/Throwable".equals(block.type)) continue;
			int start = method.instructions.indexOf(block.start);
			int end = method.instructions.indexOf(block.end);
			if (at > start && at < end && !(survivor > start && survivor < end)) return true;
		}
		return false;
	}

	/**
	 * True when some handler covers {@code calleeName} and does NOT cover {@code survivorName}.
	 *
	 * <p>That is the property worth asserting. "Inside some try block" is satisfied by the window's own outer
	 * handler and therefore says nothing; what matters is that the failure of one call stops at that call and
	 * the work after it still runs.
	 */
	private static boolean isolatedFrom(MethodNode method, String calleeName, String survivorName) {
		int at = indexOfCall(method, calleeName);
		int survivor = indexOfCall(method, survivorName);
		if (at < 0 || survivor < 0) return false;

		for (TryCatchBlockNode block : method.tryCatchBlocks) {
			int start = method.instructions.indexOf(block.start);
			int end = method.instructions.indexOf(block.end);
			if (at > start && at < end && !(survivor > start && survivor < end)) return true;
		}
		return false;
	}

	private static int indexOfCall(MethodNode method, String calleeName) {
		AbstractInsnNode[] body = method.instructions.toArray();
		for (int i = 0; i < body.length; i++) {
			if (body[i] instanceof MethodInsnNode call && calleeName.equals(call.name)) return i;
		}
		return -1;
	}

	private static MethodNode method(String internalName, String methodName) {
		Path file = CLASSES.resolve(internalName + ".class");
		if (!Files.isRegularFile(file)) return null;
		try {
			ClassNode node = new ClassNode();
			new ClassReader(Files.readAllBytes(file)).accept(node, 0);
			List<MethodNode> hits = new ArrayList<>();
			for (MethodNode m : node.methods) {
				if (m.name.equals(methodName)) hits.add(m);
			}
			return hits.size() == 1 ? hits.get(0) : null;
		} catch (Exception unreadable) {
			return null;
		}
	}
}
