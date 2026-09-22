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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Pins the thread a NeoForge deferred-work queue runs on, because mods can tell the difference.
 *
 * <p>The behavioural test is {@link #deferringToTheGameLoopStaysDeferred}: it reproduces
 * {@code BlockableEventLoop.execute}'s rule — inline when already on the game thread, queued otherwise — and
 * asserts that a task submitted from inside deferred work is still sitting in the queue when the phase returns.
 * That is the whole point. Running the queue on the caller's thread makes such a task run INSIDE
 * {@code Minecraft.<init>}, before {@code createReload} has given the resource manager any packs, which is how
 * Xaero's World Map ended up calling {@code Optional.get()} on an empty {@code getResource}.
 */
class NeoDeferredWorkTest {

	/** A stand-in for {@code Minecraft}: runs inline on its own thread, queues from anywhere else. */
	private static final class FakeGameLoop {
		private final Thread gameThread = Thread.currentThread();
		private final Deque<Runnable> queued = new ArrayDeque<>();

		synchronized void execute(Runnable task) {
			if (Thread.currentThread() == gameThread) {
				task.run();
			} else {
				queued.add(task);
			}
		}

		synchronized int pending() {
			return queued.size();
		}

		synchronized void drain() {
			while (!queued.isEmpty()) queued.poll().run();
		}
	}

	@Test
	void deferringToTheGameLoopStaysDeferred() throws Throwable {
		FakeGameLoop game = new FakeGameLoop();
		AtomicReference<String> ranWhile = new AtomicReference<>();

		ExecutorService sync = Executors.newSingleThreadExecutor();
		try {
			// Stands for a mod's enqueueWork body: it asks the game loop to do the real work later.
			NeoDeferredWork.runBlocking(sync, () -> game.execute(() -> ranWhile.set("the game loop")));

			assertEquals(1, game.pending(),
					"the task a mod handed to the game loop must still be QUEUED when the phase returns — "
							+ "running the queue on the caller's thread executes it inline instead, which on the "
							+ "client is inside Minecraft.<init> with an empty resource manager");
			assertNull(ranWhile.get(), "and it must not have run yet");

			game.drain();
			assertEquals("the game loop", ranWhile.get(), "it runs once the game loop gets to it");
		} finally {
			sync.shutdownNow();
		}
	}

	@Test
	void theWorkRunsOffTheCallersThreadAndTheCallerWaitsForIt() throws Throwable {
		AtomicReference<Thread> ranOn = new AtomicReference<>();
		ExecutorService sync = Executors.newSingleThreadExecutor(r -> new Thread(r, "modloading-sync-worker"));
		try {
			NeoDeferredWork.runBlocking(sync, () -> ranOn.set(Thread.currentThread()));

			assertNotSame(Thread.currentThread(), ranOn.get(), "NeoForge runs deferred work off the game thread");
			assertEquals("modloading-sync-worker", ranOn.get().getName(), "on the executor it was handed");
			assertTrue(ranOn.get() != null, "and runBlocking returned only after the work had run");
		} finally {
			sync.shutdownNow();
		}
	}

	@Test
	void whatTheWorkThrewIsWhatTheCallerCatches() {
		ExecutorService sync = Executors.newSingleThreadExecutor();
		try {
			IOException thrown = new IOException("a mod's own failure");
			IOException caught = assertThrows(IOException.class,
					() -> NeoDeferredWork.runBlocking(sync, () -> {
						throw thrown;
					}),
					"the caller's catch must see the mod's failure, not a CompletionException wrapping it");
			assertSame(thrown, caught);
		} finally {
			sync.shutdownNow();
		}
	}

	@Test
	void noExecutorMeansTheOldInlineBehaviour() throws Throwable {
		AtomicReference<Thread> ranOn = new AtomicReference<>();
		NeoDeferredWork.runBlocking((Executor) null, () -> ranOn.set(Thread.currentThread()));
		assertSame(Thread.currentThread(), ranOn.get(),
				"a carrier without ModWorkManager still runs the work — inline is better than not at all");
	}

	@Test
	void aCarrierWithoutModWorkManagerReportsNoExecutor() {
		assertNull(NeoDeferredWork.syncExecutor(NeoDeferredWorkTest.class.getClassLoader()),
				"net.neoforged.fml.ModWorkManager is not on the test classpath, so this must fail soft");
	}

	/**
	 * The wiring, not just the helper. {@code syncExecutor} fails soft by design, so a {@code fireSetupPhase}
	 * that went back to calling {@code runTasks} itself would still load every mod and still look healthy in the
	 * log — it would only be wrong about the thread, which is the part nothing else notices.
	 */
	@Test
	void theNeoForgeSetupPhaseRunsItsQueueThroughThisHelper() throws Exception {
		// The phase itself is game-side now (KernelNeoSetup); only the "is there anyone to post to" guard and the
		// logging stayed in KernelLifecycle. The assertion follows the code rather than the file it used to be in.
		Path compiled = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "runtime",
				"net", "forbric", "kernel", "runtime", "KernelNeoSetup.class");
		assumeTrue(Files.isRegularFile(compiled), "KernelNeoSetup not compiled yet (no staged game jars)");

		ClassNode node = new ClassNode();
		new ClassReader(Files.readAllBytes(compiled)).accept(node, 0);
		MethodNode phase = node.methods.stream().filter(m -> "firePhase".equals(m.name)).findFirst()
				.orElseThrow(() -> new AssertionError("firePhase is gone"));

		boolean throughHelper = false;
		for (AbstractInsnNode insn : phase.instructions.toArray()) {
			if (insn instanceof MethodInsnNode call
					&& "net/forbric/kernel/boot/NeoDeferredWork".equals(call.owner)
					&& "runBlocking".equals(call.name)) {
				throughHelper = true;
			}
		}
		assertTrue(throughHelper,
				"the NeoForge setup phase must run the DeferredWorkQueue through NeoDeferredWork.runBlocking, not "
						+ "on its own thread — on the client its own thread is the render thread, inside "
						+ "Minecraft.<init>");
	}

	/**
	 * The private shape {@link DeferredWorkFailures} reads on BOTH carriers: a {@code tasks} deque on the queue,
	 * {@code owner} and {@code future} on its TaskInfo. A carrier bump that renames one goes red here rather than
	 * silently losing the attribution of a throwing deferred task.
	 */
	@Test
	void bothCarriersDeferredWorkQueuesStillHaveTheFieldsTheFailureReadNeeds() throws Exception {
		Path run = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", System.getProperty("user.dir") + "/../forbric-loader"), "run").normalize();
		for (String[] carrier : new String[][] {
				{ "neoforge-runtime/neoforge-runtime.jar", "net/neoforged/fml/DeferredWorkQueue" },
				{ "forge-runtime/forge-runtime.jar", "net/minecraftforge/fml/DeferredWorkQueue" } }) {
			Path jar = run.resolve(carrier[0]);
			assumeTrue(Files.isRegularFile(jar), "staged carrier absent: " + jar);
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				assertTrue(declaresField(zip, carrier[1], "tasks"), carrier[1] + ".tasks");
				assertTrue(declaresField(zip, carrier[1] + "$TaskInfo", "owner"), carrier[1] + "$TaskInfo.owner");
				assertTrue(declaresField(zip, carrier[1] + "$TaskInfo", "future"), carrier[1] + "$TaskInfo.future");
			}
		}
	}

	/** Both setup phases hand their queue to {@link DeferredWorkFailures#owners} after a drain that threw. */
	@Test
	void bothSetupPhasesAttributeAThrowingDeferredTask() throws Exception {
		for (String helper : new String[] { "KernelNeoSetup", "KernelForgeSetup" }) {
			Path compiled = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "runtime",
					"net", "forbric", "kernel", "runtime", helper + ".class");
			assumeTrue(Files.isRegularFile(compiled), helper + " not compiled yet (no staged game jars)");
			ClassNode node = new ClassNode();
			new ClassReader(Files.readAllBytes(compiled)).accept(node, 0);
			MethodNode phase = node.methods.stream().filter(m -> "firePhase".equals(m.name)).findFirst()
					.orElseThrow(() -> new AssertionError("firePhase is gone from " + helper));
			// Both phases already mark a mod whose LISTENER threw, so "a mark exists" proves nothing: the pin wants
			// a mark AFTER the owners() read, and the deferred-task wording in the method's constants.
			int owners = -1, markAfterOwners = -1, i = 0;
			boolean wording = false;
			for (AbstractInsnNode insn : phase.instructions.toArray()) {
				if (insn instanceof MethodInsnNode call && "net/forbric/kernel/boot/DeferredWorkFailures".equals(call.owner)
						&& "owners".equals(call.name) && owners < 0) owners = i;
				if (insn instanceof MethodInsnNode call && "net/forbric/api/ModCatalog".equals(call.owner)
						&& "mark".equals(call.name) && owners >= 0 && markAfterOwners < 0) markAfterOwners = i;
				if (insn instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode indy) {
					for (Object arg : indy.bsmArgs) wording |= arg instanceof String s && s.contains("deferred setup tasks threw");
				}
				if (insn instanceof org.objectweb.asm.tree.LdcInsnNode ldc) {
					wording |= ldc.cst instanceof String s && s.contains("deferred setup tasks threw");
				}
				i++;
			}
			assertTrue(owners >= 0, helper + ".firePhase reads the failed tasks' owners");
			assertTrue(markAfterOwners > owners, helper + ".firePhase marks the catalogue with what owners() answered");
			assertTrue(wording, helper + ".firePhase's mark says a deferred setup task threw");
		}
	}

	private static boolean declaresField(ZipFile zip, String internal, String field) throws Exception {
		ZipEntry entry = zip.getEntry(internal + ".class");
		assertTrue(entry != null, internal + " is gone from the carrier");
		ClassNode node = new ClassNode();
		try (InputStream in = zip.getInputStream(entry)) {
			new ClassReader(in).accept(node, 0);
		}
		return node.fields.stream().anyMatch(f -> field.equals(f.name));
	}

	/** If the carrier ever drops this, {@link NeoDeferredWork#syncExecutor} goes quiet and the bug comes back. */
	@Test
	void theCarrierStillOffersTheExecutorNeoForgeRunsDeferredWorkOn() throws Exception {
		Path carrier = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", System.getProperty("user.dir") + "/../forbric-loader"), "run", "neoforge-runtime",
				"neoforge-runtime.jar").normalize();
		assumeTrue(Files.isRegularFile(carrier), "staged NeoForge carrier absent");

		try (ZipFile jar = new ZipFile(carrier.toFile())) {
			ZipEntry entry = jar.getEntry("net/neoforged/fml/ModWorkManager.class");
			assertTrue(entry != null, "net.neoforged.fml.ModWorkManager is gone from the carrier");
			ClassNode node = new ClassNode();
			try (InputStream in = jar.getInputStream(entry)) {
				new ClassReader(in).accept(node, 0);
			}
			assertTrue(node.methods.stream().anyMatch(m -> "syncExecutor".equals(m.name)
					&& "()Ljava/util/concurrent/Executor;".equals(m.desc)),
					"ModWorkManager.syncExecutor() is gone or changed shape — deferred work would silently fall "
							+ "back to the calling thread, which is the render thread on the client");
		}
	}
}
