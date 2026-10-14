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
        CountDownLatch prioritiesStarted = new CountDownLatch(2), prioritiesRelease = new CountDownLatch(1);
        Runnable blocker = () -> {
            int running = active.incrementAndGet(); peak.set(Math.max(peak.get(),running)); started.countDown();
            try { release.await(); } catch (InterruptedException ignored) {} finally { active.decrementAndGet(); }
        };
        try {
            scheduler.submit(0,blocker); scheduler.submit(0,blocker);
            if (!started.await(5,TimeUnit.SECONDS)) throw new AssertionError("Workers failed to start");
            List<DictionaryScheduler.Ticket> tickets = new ArrayList<>();
            tickets.add(scheduler.submit(1,() -> {
                order.add(0); prioritiesStarted.countDown();
                try { prioritiesRelease.await(); } catch (InterruptedException ignored) {} done.countDown();
            }));
            for (int i=1;i<=30;i++) { final int value=i; tickets.add(scheduler.submit(3,() -> {
                order.add(value);
                if (value == 30) {
                    prioritiesStarted.countDown();
                    try { prioritiesRelease.await(); } catch (InterruptedException ignored) {}
                }
                done.countDown();
            })); }
            tickets.get(30).promote(0);
            DictionaryScheduler.Ticket cancelled = scheduler.submit(0,() -> { throw new AssertionError("Cancelled task executed"); }); cancelled.cancel();
            List<DictionaryScheduler.Ticket> filled = new ArrayList<>();
            for(int i=31;i<DictionaryScheduler.MAX_QUEUED;i++) filled.add(scheduler.submit(3,() -> {}));
            if (scheduler.submit(3,() -> {}) != null) throw new AssertionError("Unbounded queue");
            for(DictionaryScheduler.Ticket ticket:filled) ticket.cancel();
            release.countDown();
            if (!prioritiesStarted.await(5, TimeUnit.SECONDS)) throw new AssertionError("Priority tasks did not start");
            if (order.size() != 2 || !order.contains(0) || !order.contains(30)) throw new AssertionError("Deferred work overtook priority tasks: " + order);
            prioritiesRelease.countDown();
            if(!done.await(5,TimeUnit.SECONDS)) throw new AssertionError("Queue did not drain");
            if(order.get(0)!=30 && order.get(1)!=30) throw new AssertionError("Expanded dictionary not scheduled first: "+order);
            if(order.indexOf(0)>2) throw new AssertionError("P1 behind deferred work");
            if(peak.get()>2) throw new AssertionError("Too many workers");
            System.out.println("DictionaryScheduler: 30 dictionaries, P0 promotion, P1 ordering, cancellation and queue bound passed");
        } finally { release.countDown(); prioritiesRelease.countDown(); scheduler.close(); }
        checkFifo();
    }
    private static void checkFifo() throws Exception {
        DictionaryScheduler scheduler = new DictionaryScheduler();
        CountDownLatch started = new CountDownLatch(2), hold = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(5);
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        try {
            scheduler.submit(0, () -> { started.countDown(); try { hold.await(); } catch (InterruptedException ignored) {} });
            scheduler.submit(0, () -> { started.countDown(); try { release.await(); } catch (InterruptedException ignored) {} });
            if (!started.await(5, TimeUnit.SECONDS)) throw new AssertionError("FIFO workers did not start");
            DictionaryScheduler.Ticket first = scheduler.submit(1, () -> { order.add(1); done.countDown(); });
            scheduler.submit(1, () -> { order.add(2); done.countDown(); });
            scheduler.submit(1, () -> { order.add(3); done.countDown(); });
            first.promote(1); // Repeated viewport passes must not reshuffle the queue.
            DictionaryScheduler.Ticket repeated = scheduler.submit(0, () -> { order.add(4); done.countDown(); });
            scheduler.submit(0, () -> { order.add(5); done.countDown(); });
            repeated.promote(0); // A repeated explicit action still becomes the newest user request.
            release.countDown();
            if (!done.await(5, TimeUnit.SECONDS)) throw new AssertionError("FIFO work did not drain");
            if (!order.equals(Arrays.asList(4, 5, 1, 2, 3))) throw new AssertionError("User actions/viewport order changed: " + order);
            System.out.println("DictionaryScheduler: display-order FIFO, unchanged promotion and newest explicit action passed");
        } finally { hold.countDown(); release.countDown(); scheduler.close(); }
    }
}
