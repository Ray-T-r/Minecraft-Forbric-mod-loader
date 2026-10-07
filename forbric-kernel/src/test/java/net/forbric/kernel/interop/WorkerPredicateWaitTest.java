/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.interop;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class WorkerPredicateWaitTest {
	private static class Pool {
		private final AtomicBoolean running=new AtomicBoolean(true);
		private final java.util.Deque<Runnable> taskQueue=new ConcurrentLinkedDeque<>();
	}
	@Test void notificationBeforeWaitingCannotStrandAStoppedWorkerOrQueuedTask() {
		Pool pool=new Pool();Object notifier=new Object();
		pool.running.set(false);
		synchronized(notifier){notifier.notifyAll();}
		assertTimeoutPreemptively(Duration.ofSeconds(1),()->WorkerPredicateWait.awaitNotification(notifier,pool,"running","taskQueue"));
		pool.running.set(true);pool.taskQueue.add(()->{});
		synchronized(notifier){notifier.notifyAll();}
		assertTimeoutPreemptively(Duration.ofSeconds(1),()->WorkerPredicateWait.awaitNotification(notifier,pool,"running","taskQueue"));
	}
	@Test void anIdleWorkerWaitsAndAStopUnderTheSameMonitorWakesIt() throws Exception {
		Pool pool=new Pool();Object notifier=new Object();
		Thread worker=new Thread(()->WorkerPredicateWait.awaitNotification(notifier,pool,"running","taskQueue"));worker.setDaemon(true);worker.start();
		try {
			assertTimeoutPreemptively(Duration.ofSeconds(2),()->{while(worker.getState()!=Thread.State.WAITING)Thread.sleep(1);});
			synchronized(notifier){pool.running.set(false);notifier.notifyAll();}
			worker.join(1000);assertFalse(worker.isAlive());
		}finally{pool.running.set(false);synchronized(notifier){notifier.notifyAll();}worker.interrupt();worker.join(1000);}
	}
    private static final class DerivedPool extends Pool {
        private final AtomicBoolean running=new AtomicBoolean(true);
        private final java.util.Deque<Runnable> taskQueue=new ConcurrentLinkedDeque<>();
    }
    @Test void aProvedDeclaringClassCannotReadASubclassShadowInstead() {
        DerivedPool pool=new DerivedPool();((Pool)pool).running.set(false);
        assertTimeoutPreemptively(Duration.ofSeconds(1),()->WorkerPredicateWait.awaitNotification(new Object(),pool,Pool.class,"running","taskQueue",false));
    }
    @Test void interruptionPolicyMatchesTheOriginalBareWait() throws Exception {
        for(boolean restore:new boolean[]{false,true}) {
            Pool pool=new Pool();Object notifier=new Object();AtomicBoolean interrupted=new AtomicBoolean();
            Thread worker=new Thread(()->{WorkerPredicateWait.awaitNotification(notifier,pool,"running","taskQueue",restore);interrupted.set(Thread.currentThread().isInterrupted());});
            worker.setDaemon(true);worker.start();
            try {
                assertTimeoutPreemptively(Duration.ofSeconds(2),()->{while(worker.getState()!=Thread.State.WAITING)Thread.sleep(1);});
                worker.interrupt();worker.join(1000);assertFalse(worker.isAlive());assertEquals(restore,interrupted.get());
            } finally {worker.interrupt();synchronized(notifier){notifier.notifyAll();}worker.join(1000);}
        }
    }

}
