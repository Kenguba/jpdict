package com.yuen.jpdict;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class DictionarySchedulerTest {
    public static void main(String[] args) throws Exception {
        DictionaryScheduler scheduler = new DictionaryScheduler();
        CountDownLatch started = new CountDownLatch(2), release = new CountDownLatch(1), done = new CountDownLatch(31);
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger();
        Runnable blocker = () -> {
            int running = active.incrementAndGet(); peak.set(Math.max(peak.get(),running)); started.countDown();
            try { release.await(); } catch (InterruptedException ignored) {} finally { active.decrementAndGet(); }
        };
        try {
            scheduler.submit(0,blocker); scheduler.submit(0,blocker);
            if (!started.await(5,TimeUnit.SECONDS)) throw new AssertionError("Workers failed to start");
            List<DictionaryScheduler.Ticket> tickets = new ArrayList<>();
            tickets.add(scheduler.submit(1,() -> { order.add(0); done.countDown(); }));
            for (int i=1;i<=30;i++) { final int value=i; tickets.add(scheduler.submit(3,() -> { order.add(value); done.countDown(); })); }
            tickets.get(30).promote(0);
            DictionaryScheduler.Ticket cancelled = scheduler.submit(0,() -> { throw new AssertionError("Cancelled task executed"); }); cancelled.cancel();
            List<DictionaryScheduler.Ticket> filled = new ArrayList<>();
            for(int i=31;i<DictionaryScheduler.MAX_QUEUED;i++) filled.add(scheduler.submit(3,() -> {}));
            if (scheduler.submit(3,() -> {}) != null) throw new AssertionError("Unbounded queue");
            for(DictionaryScheduler.Ticket ticket:filled) ticket.cancel();
            release.countDown();
            if(!done.await(5,TimeUnit.SECONDS)) throw new AssertionError("Queue did not drain");
            if(order.get(0)!=30 && order.get(1)!=30) throw new AssertionError("Expanded dictionary not scheduled first: "+order);
            if(order.indexOf(0)>2) throw new AssertionError("P1 behind deferred work");
            if(peak.get()>2) throw new AssertionError("Too many workers");
            System.out.println("DictionaryScheduler: 30 dictionaries, P0 promotion, P1 ordering, cancellation and queue bound passed");
        } finally { release.countDown(); scheduler.close(); }
    }
}
