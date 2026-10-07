/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;

/**
 * {@link WorkerNotificationInjector}'s output, run: a Flywheel worker told to stop before it parks no longer parks
 * forever, and a live one with nothing to do still parks until it is notified.
 *
 * <p>The stand-ins carry only what the edit keys on and what the real {@code WorkerPredicateWait} reads: the pool's
 * {@code active} flag and {@code backlog}, its notifier, and an inner worker whose {@code idlePhase} calls
 * {@code parkForWork}. Each test runs the worker on its own thread and reads that thread's state, because the
 * defect is a thread that never comes back.
 */
@ExecutesInjector(WorkerNotificationInjector.class)
class WorkerNotificationInjectorTest {
	private static final String PACKAGE = "unknown.vendor.queue";
	private static final String POOL = PACKAGE + ".ParallelTaskExecutor";
	private static final String WORKER_INTERNAL = "unknown/vendor/queue/ParallelTaskExecutor$WorkerThread";
	private static final long PATIENCE_SECONDS = 10;

	private static final String NOTIFIER_SOURCE = """
			package unknown.vendor.queue;

			public class ThreadGroupNotifier {
				public void parkForWork() {
					synchronized (this) {
						try {
							wait();
						} catch (InterruptedException interrupted) {
							Thread.currentThread().interrupt();
						}
					}
				}

				public void wakeAll() {
					synchronized (this) {
						notifyAll();
					}
				}
			}
			""";

	/** {@code idlePhase} parks without rechecking, which is the window a stop notification falls into. */
	private static String poolSource(String workerBody) {
		return """
				package unknown.vendor.queue;

				import java.util.ArrayDeque;
				import java.util.Deque;
				import java.util.concurrent.atomic.AtomicBoolean;

				public class ParallelTaskExecutor {
					final AtomicBoolean active = new AtomicBoolean(true);
					final Deque<Runnable> backlog = new ArrayDeque<>();
					final ThreadGroupNotifier notifier = new ThreadGroupNotifier();

					public Runnable worker() {
						return new WorkerThread();
					}

					public void stop() {
						active.set(false);
						notifier.wakeAll();
					}

					public void queue(Runnable task) {
						backlog.add(task);
					}

					class WorkerThread implements Runnable {
						@Override
						public void run() {
							idlePhase();
						}

						void idlePhase() {
				%s
						}
					}
				}
				""".formatted(workerBody);
	}

	private static final String PARK = "\t\t\t\tif (!ParallelTaskExecutor.this.backlog.isEmpty()) return;\n\t\t\t\tParallelTaskExecutor.this.notifier.parkForWork();";

	private static Map<String, byte[]> standIns(Path work, String workerBody) throws Exception {
		return InjectorExecution.compile(work, Map.of(
				PACKAGE + ".ThreadGroupNotifier", NOTIFIER_SOURCE,
				POOL, poolSource(workerBody)));
	}

	private static WorkerNotificationInjector injector(Map<String, byte[]> classes) {
		return new WorkerNotificationInjector(name -> {
			byte[] bytes=classes.get(name.replace('.', '/')); if(bytes==null)return null;
			var node=new org.objectweb.asm.tree.ClassNode();new org.objectweb.asm.ClassReader(bytes).accept(node,0);return node;
		});
	}

	private static ClassLoader transformed(Map<String, byte[]> original) {
		byte[] worker = original.get(WORKER_INTERNAL);
		byte[] edited = InjectorExecution.transform(injector(original), WORKER_INTERNAL.replace('/', '.'), worker,
				EnvType.CLIENT);
		assertNotSame(worker, edited, "the injector did not touch the worker");
		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(WORKER_INTERNAL, edited);
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(edited, loader));
		return loader;
	}

	private static Thread start(Object pool, String name) throws Throwable {
		Thread thread = new Thread((Runnable) InjectorExecution.invoke(pool, "worker"), name);
		thread.setDaemon(true);
		thread.start();
		return thread;
	}

	private static void awaitState(Thread thread, Thread.State state) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PATIENCE_SECONDS);
		while (thread.getState() != state) {
			assertTrue(System.nanoTime() < deadline, thread.getName() + " never reached " + state + ", is " + thread.getState());
			Thread.sleep(5);
		}
	}

	/**
	 * The hang: the pool stopped (and notified) before the worker parked. Untransformed the worker waits for a
	 * notification that already happened; transformed it reads {@code active} under the notifier's monitor and leaves.
	 */
	@Test void aWorkerWhosePoolAlreadyStoppedReturnsInsteadOfWaitingForever(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = standIns(work, PARK);

		Object stoppedVanilla = InjectorExecution.construct(InjectorExecution.load(original).loadClass(POOL));
		InjectorExecution.invoke(stoppedVanilla, "stop");
		Thread lost = start(stoppedVanilla, "forbric-test-flywheel-untransformed");
		try {
			awaitState(lost, Thread.State.WAITING);
			assertTrue(lost.isAlive(), "premise: untransformed, the stopped worker is parked with nobody left to wake it");
		} finally {
			lost.interrupt();
			lost.join(TimeUnit.SECONDS.toMillis(PATIENCE_SECONDS));
		}

		Object stopped = InjectorExecution.construct(transformed(original).loadClass(POOL));
		InjectorExecution.invoke(stopped, "stop");
		Thread worker = start(stopped, "forbric-test-flywheel-stopped");
		worker.join(TimeUnit.SECONDS.toMillis(PATIENCE_SECONDS));
		assertFalse(worker.isAlive(), "a stopped pool's worker must come back, or the client cannot exit");
	}

	@Test void aWorkerWithQueuedWorkDoesNotPark(@TempDir Path work) throws Throwable {
		Object pool = InjectorExecution.construct(transformed(standIns(work, PARK)).loadClass(POOL));
		InjectorExecution.invoke(pool, "queue", (Runnable) () -> { });
		Thread worker = start(pool, "forbric-test-flywheel-busy");
		worker.join(TimeUnit.SECONDS.toMillis(PATIENCE_SECONDS));
		assertFalse(worker.isAlive(), "work is waiting, so the worker must go back for it instead of parking");
	}

	/** The repair is not "never wait": a live pool with nothing queued still parks its worker until it is told. */
	@Test void anIdleWorkerStillParksUntilItIsNotified(@TempDir Path work) throws Throwable {
		Object pool = InjectorExecution.construct(transformed(standIns(work, PARK)).loadClass(POOL));
		Thread worker = start(pool, "forbric-test-flywheel-idle");
		awaitState(worker, Thread.State.WAITING);
		InjectorExecution.invoke(pool, "stop");
		worker.join(TimeUnit.SECONDS.toMillis(PATIENCE_SECONDS));
		assertFalse(worker.isAlive(), "the stop notification must still wake a parked worker");
	}

	@Test void onlyTheWorkerIsEditedAndOnlyWhenItHasOneParkAndAnOuterPool(@TempDir Path work) throws Exception {
		Map<String, byte[]> original = standIns(work, PARK);
		WorkerNotificationInjector injector = injector(original);
		for (String other : new String[] {POOL, PACKAGE + ".ThreadGroupNotifier"}) {
			byte[] bytes = original.get(other.replace('.', '/'));
			assertSame(bytes, InjectorExecution.transform(injector, other, bytes, EnvType.CLIENT), other);
		}
		byte[] twice = standIns(work, PARK + "\n" + PARK).get(WORKER_INTERNAL);
		assertSame(twice, InjectorExecution.transform(injector, WORKER_INTERNAL.replace('/', '.'), twice, EnvType.CLIENT),
				"two parks are a different method; the edit must not guess which one is the idle wait");
		// No use of the pool, so javac leaves out this$0: there is nothing to hand WorkerPredicateWait.
		byte[] detached = standIns(work, "").get(WORKER_INTERNAL);
		assertSame(detached, InjectorExecution.transform(injector, WORKER_INTERNAL.replace('/', '.'), detached, EnvType.CLIENT));
	}
    @Test void aQueryWithoutAnIdleBranchAndANotifierWithExtraBehaviorAreNotBareWaits(@TempDir Path work) throws Exception {
        Map<String,byte[]> noBranch=standIns(work,"boolean ignored = ParallelTaskExecutor.this.backlog.isEmpty(); ParallelTaskExecutor.this.notifier.parkForWork();");
        byte[] worker=noBranch.get(WORKER_INTERNAL);
        assertSame(worker,injector(noBranch).transform(WORKER_INTERNAL,worker,null),"isEmpty must control the idle park");
        Map<String,byte[]> conditional=InjectorExecution.compile(work,Map.of(PACKAGE+".ThreadGroupNotifier",NOTIFIER_SOURCE.replace("wait();","if(System.nanoTime()<0)return; wait();"),POOL,poolSource(PARK)));
        worker=conditional.get(WORKER_INTERNAL);
        assertSame(worker,injector(conditional).transform(WORKER_INTERNAL,worker,null),"a condition inside the notifier must not be bypassed");
        Map<String,byte[]> rethrow=InjectorExecution.compile(work,Map.of(PACKAGE+".ThreadGroupNotifier",NOTIFIER_SOURCE.replace("Thread.currentThread().interrupt();","throw new IllegalStateException(interrupted);"),POOL,poolSource(PARK)));
        worker=rethrow.get(WORKER_INTERNAL);
        assertSame(worker,injector(rethrow).transform(WORKER_INTERNAL,worker,null),"propagated interruption cannot become a swallowed interruption");
    }

}
