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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
 * Two calls in the registration window that used to cost far more than themselves when they failed.
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
	void fireRegisterEventsCannotTakeTheRestOfTheWindowWithIt() throws Exception {
		// It resolves a GAME-side class reflectively, so a LinkageError inside it reaches the window's outer
		// catch and skips everything after: the traditional-Forge baseline, the Fabric main entrypoints, the
		// attribute events, the spawn-placement event, BlockEntityTypeAddBlocksEvent and the modded creative-tab
		// categories. The one WARN that reported it blamed the window rather than the call.
		MethodNode window = method("net/forbric/kernel/boot/KernelLifecycle", "registerNeoForgeContent");
		assumeTrue(window != null, "KernelLifecycle not compiled yet");

		// "Is it inside SOME try block" is not the question, and asking it is how this assertion was toothless
		// for its first draft: the whole window body already sits in one. The question is whether the handler
		// that covers this call ALSO covers the work that has to survive it. KernelForgeBaseline.register is that
		// work -- the traditional-Forge baseline is the first thing the old failure skipped.
		assertTrue(isolatedFrom(window, "fireRegisterEvents", "register"),
				"fireRegisterEvents must sit in a handler that does NOT also cover KernelForgeBaseline.register, "
						+ "or a LinkageError inside it still takes the Forge baseline and everything after it");
	}

	@Test
	void theSiblingCallsThisWasModelledOnAreStillGuardedToo() {
		// If registerAll ever loses ITS handler, the assertion above is still green while the same class of
		// failure is back -- so the model is pinned as well as the copy.
		MethodNode window = method("net/forbric/kernel/boot/KernelLifecycle", "registerNeoForgeContent");
		assumeTrue(window != null, "KernelLifecycle not compiled yet");

		assertTrue(isolatedFrom(window, "registerAll", "register"),
				"KernelEventSubscribers.registerAll is guarded for the same reason and its comment says so");
	}

	@Test
	void oneForgeModsRegistryListenerCannotCostEveryOtherModsRegistries() throws Exception {
		// Traditional Forge's NewRegistryEvent goes out on a GLOBAL bus, so there is no seam between listeners.
		// There is one between the post and the fill, and that is where the cost was: a listener throwing used to
		// skip fill() entirely, losing every Forge custom registry in the instance -- including those created by
		// listeners that had already run.
		MethodNode fire = method("net/forbric/kernel/boot/KernelForgeBaseline", "fireNewRegistryEvent");
		assumeTrue(fire != null, "KernelForgeBaseline not compiled yet");

		// The post is reflective -- KernelForgeModContext.single(bus.getClass(), "post").invoke(...) -- so it is
		// located by the resolver call, not by a method named "post", which does not exist in the bytecode.
		int post = indexOfCall(fire, "single");
		int fill = indexOfCall(fire, "getDeclaredMethod");
		assertTrue(post >= 0, "the event is still posted through KernelForgeModContext.single here");
		assertTrue(fill >= 0, "and fill is still resolved here");

		assertTrue(guardedRange(fire, post), "the post needs a handler of its own, or one mod's listener takes "
				+ "fill() with it");
		assertTrue(fill > post, "and fill must still be reached after it");
		assertTrue(fire.tryCatchBlocks.size() >= 2,
				"the outer handler alone means the post and the fill still share a fate");
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

	/** True when instruction {@code index} lies inside some try/catch range of this method. */
	private static boolean guardedRange(MethodNode method, int index) {
		for (TryCatchBlockNode block : method.tryCatchBlocks) {
			int start = method.instructions.indexOf(block.start);
			int end = method.instructions.indexOf(block.end);
			if (index > start && index < end) return true;
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
