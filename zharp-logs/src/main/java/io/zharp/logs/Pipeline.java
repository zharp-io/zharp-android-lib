package io.zharp.logs;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * One running configuration: its queue, its transport and the thread that sends. Logging only
 * adds to the queue; every request is made on that one background thread, never the caller's.
 */
class Pipeline {
    /** Logs kept while they wait to be sent. When the network is down for long, the oldest go first. */
    static final int MAX_QUEUED = 500;

    final Transport transport;
    private final long flushIntervalMillis;
    private final int maxBatchSize;
    private final ScheduledExecutorService sender;

    private final ArrayDeque<JSONObject> queue = new ArrayDeque<>();
    private ScheduledFuture<?> timer;
    private boolean closed;

    Pipeline(Transport transport, long flushIntervalMillis, int maxBatchSize) {
        this.transport = transport;
        this.flushIntervalMillis = flushIntervalMillis;
        this.maxBatchSize = maxBatchSize;
        this.sender = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "zharp-logs");
                // Never what keeps a process alive.
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    void push(JSONObject entry) {
        synchronized (this) {
            if (closed) return;
            if (queue.size() >= MAX_QUEUED) queue.pollFirst();
            queue.addLast(entry);
            armTimer();
        }
    }

    /** Add entries saved from an earlier run, ahead of anything logged since. */
    void pushFirst(List<JSONObject> entries) {
        synchronized (this) {
            if (closed) return;
            for (int i = entries.size() - 1; i >= 0; i--) {
                if (queue.size() >= MAX_QUEUED) break;
                queue.addFirst(entries.get(i));
            }
            if (!queue.isEmpty()) armTimer();
        }
    }

    /** A one-shot timer, armed only while something waits. Call holding the lock. */
    private void armTimer() {
        if (timer != null || closed) return;
        timer = sender.schedule(new Runnable() {
            @Override
            public void run() {
                synchronized (Pipeline.this) {
                    timer = null;
                }
                sendBatch();
            }
        }, flushIntervalMillis, TimeUnit.MILLISECONDS);
    }

    /** Send one batch now, in the background. */
    void flush() {
        submit(new Runnable() {
            @Override
            public void run() {
                sendBatch();
            }
        });
    }

    /** Send everything queued, in the background. The returned future completes when it's out. */
    Future<?> flushAll() {
        return submit(new Runnable() {
            @Override
            public void run() {
                while (sendBatch()) {
                    // until the queue is empty
                }
            }
        });
    }

    /** Take what's queued, for saving when the process is about to end. */
    List<JSONObject> drain() {
        synchronized (this) {
            List<JSONObject> all = new ArrayList<>(queue);
            queue.clear();
            return all;
        }
    }

    /** Send what's queued, then stop the thread. Logs pushed after this are dropped. */
    void close() {
        flushAll();
        synchronized (this) {
            closed = true;
            if (timer != null) timer.cancel(false);
            timer = null;
        }
        // Lets the flush already submitted finish, then ends the thread.
        sender.shutdown();
    }

    /** Send one batch on the calling (sender) thread. Returns whether one was sent. */
    private boolean sendBatch() {
        List<JSONObject> batch = new ArrayList<>();
        synchronized (this) {
            while (!queue.isEmpty() && batch.size() < maxBatchSize) batch.add(queue.pollFirst());
            if (!queue.isEmpty()) armTimer();
        }
        if (batch.isEmpty()) return false;
        transport.send(batch);
        return true;
    }

    private Future<?> submit(Runnable task) {
        try {
            return sender.submit(task);
        } catch (RuntimeException rejected) {
            // Already shut down: nothing more is sent.
            return null;
        }
    }

    /**
     * Run on a fresh thread and wait at most {@code timeoutMillis}; for the moment of a crash,
     * when the sender may be busy and the crashing thread may be the main one (where Android
     * forbids network calls). False when it failed or didn't finish in time.
     */
    static boolean runWithin(Callable<Boolean> task, long timeoutMillis) {
        ExecutorService once = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "zharp-logs-crash");
                thread.setDaemon(true);
                return thread;
            }
        });
        try {
            return Boolean.TRUE.equals(once.submit(task).get(timeoutMillis, TimeUnit.MILLISECONDS));
        } catch (Exception timedOutOrFailed) {
            return false;
        } finally {
            once.shutdownNow();
        }
    }
}
