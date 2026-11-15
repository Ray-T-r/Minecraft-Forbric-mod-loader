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

	/**
	 * What NeoForge's {@code GameData.postRegisterEvents} throws stops the boot, as native NeoForge stops it (owner ruling D82):
	 * the handler covering the reflective call hands the throw to {@code RegistrationStop.stop} and throws what it returns,
	 * and does not cover the Fabric step after it, which a stop must skip; the window's outer catch lets the stop through
	 * instead of reporting "could not register ecosystem content" and booting on.
	 *
	 * <p>This used to pin the opposite — the dispatch's failure stopping at dispatchRegisterEvents so the rest of the window
	 * ran — which was the containment D82 retired: NeoForge rolls every registry back to vanilla on that throw, and the boot
	 * carried on with every mod's content gone. RegisterEventsSeamWeaveTest drives the behaviour; this pins the shape.
	 */
	@Test
	void whatTheRegisterEventDispatchThrowsStopsTheBoot() throws Exception {
		MethodNode dispatch = method("net/forbric/kernel/boot/KernelLifecycle", "dispatchRegisterEvents");
		assertTrue(dispatch != null,
				"KernelLifecycle.dispatchRegisterEvents not found in the compiled src/main classes, which exist before any "
						+ "test runs");
		assertTrue(caughtAndIsolatedFrom(dispatch, "postRegisterEvents", "runAfterDispatch"),
				"postRegisterEvents must sit in a Throwable handler of its own that does not also cover the Fabric step");
		assertTrue(handlerCallsThenThrows(dispatch, "postRegisterEvents", "stop"),
				"the handler covering postRegisterEvents must hand the throw to RegistrationStop.stop and throw the stop");
		MethodNode window = method("net/forbric/kernel/boot/KernelLifecycle", "registerNeoForgeContent");
		assertTrue(window != null && indexOfCall(window, "dispatchRegisterEvents") >= 0,
				"registerNeoForgeContent must dispatch RegisterEvent through dispatchRegisterEvents");
		assertTrue(handlerCallsThenThrows(window, "dispatchRegisterEvents", "isStop"),
				"registerNeoForgeContent's catch around the dispatch must let RegistrationStop's stop through");
	}

	/**
	 * True when a Throwable handler covers {@code calleeName} and its code, up to its first {@code ATHROW}, calls a
	 * {@code RegistrationStop} method named {@code stopCall}.
	 */
	private static boolean handlerCallsThenThrows(MethodNode method, String calleeName, String stopCall) {
		int at = indexOfCall(method, calleeName);
		if (at < 0) return false;
		AbstractInsnNode[] body = method.instructions.toArray();
		for (TryCatchBlockNode block : method.tryCatchBlocks) {
			if (!"java/lang/Throwable".equals(block.type)) continue;
			int start = method.instructions.indexOf(block.start);
			int end = method.instructions.indexOf(block.end);
			if (at <= start || at >= end) continue;
			boolean called = false;
			for (int i = method.instructions.indexOf(block.handler); i < body.length; i++) {
				if (body[i] instanceof MethodInsnNode call && call.owner.equals("net/forbric/kernel/boot/RegistrationStop")
						&& call.name.equals(stopCall)) called = true;
				if (body[i].getOpcode() == org.objectweb.asm.Opcodes.ATHROW) {
					if (called) return true;
					break;
				}
			}
		}
		return false;
	}

	@Test
	void theSiblingCallsThisWasModelledOnAreStillGuardedToo() {
		// The kernel's own wiring of the mods' @EventBusSubscriber classes is not NeoForge's registration: a LinkageError
		// there is the kernel's to report, and it must not skip the window, so it keeps a handler of its own.
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
