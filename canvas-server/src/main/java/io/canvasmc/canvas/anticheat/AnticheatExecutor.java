package io.canvasmc.canvas.anticheat;

import io.canvasmc.canvas.GlobalConfiguration;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The shared worker pool for all asynchronous visibility computations. Work is submitted by {@link PlayerVisibility},
 * at most one job per player and module at a time, so the pool never accumulates a backlog larger than a few jobs per
 * online player.
 * <p>
 * Urgent jobs, whose results players notice when they are late (entities popping in, chunks waiting to be sent),
 * are always run before the others. Jobs of the same urgency run in submission order.
 */
public final class AnticheatExecutor {

    public static final Logger LOGGER = LoggerFactory.getLogger("CanvasAnticheat");

    private static final AtomicLong SEQUENCE = new AtomicLong();

    private static volatile ThreadPoolExecutor executor;

    private AnticheatExecutor() {
    }

    public static void execute(final Runnable task, final boolean urgent) {
        get().execute(new Task(task, urgent, SEQUENCE.getAndIncrement()));
    }

    private static ThreadPoolExecutor get() {
        ThreadPoolExecutor ret = executor;
        if (ret == null) {
            synchronized (AnticheatExecutor.class) {
                ret = executor;
                if (ret == null) {
                    executor = ret = create();
                }
            }
        }
        return ret;
    }

    private static ThreadPoolExecutor create() {
        final int configured = GlobalConfiguration.getInstance().anticheat.workerThreads;
        final int threads = configured > 0 ? configured : defaultThreadCount();
        final AtomicInteger id = new AtomicInteger();
        final ThreadPoolExecutor ret = new ThreadPoolExecutor(
            threads, threads, 60L, TimeUnit.SECONDS, new PriorityBlockingQueue<>(),
            (final Runnable runnable) -> {
                final Thread thread = new Thread(runnable, "Canvas Anticheat Worker #" + id.getAndIncrement());
                thread.setDaemon(true);
                thread.setUncaughtExceptionHandler((final Thread t, final Throwable thrown) -> LOGGER.error("Uncaught exception in {}", t.getName(), thrown));
                return thread;
            }
        );
        ret.allowCoreThreadTimeOut(true);
        LOGGER.info("Started anticheat worker pool with {} thread(s)", threads);
        return ret;
    }

    private static int defaultThreadCount() {
        // region threads take most of the cores on Folia, the visibility work is comparatively cheap
        return Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() / 4));
    }

    private record Task(Runnable delegate, boolean urgent, long sequence) implements Runnable, Comparable<Task> {

        @Override
        public void run() {
            this.delegate.run();
        }

        @Override
        public int compareTo(final Task other) {
            if (this.urgent != other.urgent) {
                return this.urgent ? -1 : 1;
            }
            return Long.compare(this.sequence, other.sequence);
        }
    }
}
