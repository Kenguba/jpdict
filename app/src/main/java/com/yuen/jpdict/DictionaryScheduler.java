package com.yuen.jpdict;

import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded two-worker scheduler. Display order is never used as completion order. */
final class DictionaryScheduler implements AutoCloseable {
    static final int MAX_QUEUED = 96;
    private final AtomicLong sequence = new AtomicLong();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 20,
            TimeUnit.SECONDS, new PriorityBlockingQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, "jpdict-dictionary");
                thread.setDaemon(true);
                return thread;
            });

    DictionaryScheduler() { executor.allowCoreThreadTimeOut(true); }

    final class Ticket implements Runnable, Comparable<Ticket> {
        private final Runnable operation;
        private volatile long order = sequence.incrementAndGet();
        private volatile boolean cancelled;
        private volatile int priority;
        private Thread running;
        Ticket(int priority, Runnable operation) { this.priority = priority; this.operation = operation; }
        public int compareTo(Ticket other) {
            int p = Integer.compare(priority, other.priority);
            // Latest explicit user action wins; viewport/background work keeps
            // display order instead of making the last dictionary load first.
            return p != 0 ? p : priority == 0 ? Long.compare(other.order, order) : Long.compare(order, other.order);
        }
        public void run() {
            synchronized (this) {
                if (cancelled) return;
                running = Thread.currentThread();
            }
            try { operation.run(); }
            finally { synchronized (this) { running = null; } }
        }
        void cancel() {
            synchronized (this) {
                cancelled = true;
                executor.remove(this);
                if (running != null) running.interrupt();
            }
        }
        boolean cancelled() { return cancelled; }
        void promote(int value) {
            synchronized (DictionaryScheduler.this) {
                if (cancelled || value > priority || (value == priority && value != 0)) return;
                boolean queued = executor.remove(this);
                priority = value;
                order = sequence.incrementAndGet();
                if (queued) executor.execute(this);
            }
        }
    }
    synchronized Ticket submit(int priority, Runnable operation) {
        if (executor.isShutdown() || executor.getQueue().size() >= MAX_QUEUED) return null;
        Ticket ticket = new Ticket(priority, operation);
        executor.execute(ticket);
        return ticket;
    }
    @Override public void close() { executor.shutdownNow(); }
}
