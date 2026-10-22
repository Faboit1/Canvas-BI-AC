package io.canvasmc.canvas.anticheat;

import io.canvasmc.canvas.GlobalConfiguration;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The shared worker pool for all asynchronous visibility computations. Work is submitted per player by
 * {@link PlayerVisibility}, at most one job per player at a time, so the pool never accumulates a backlog
 * larger than the amount of online players.
 */
public final class AnticheatExecutor {

    public static final Logger LOGGER = LoggerFactory.getLogger("CanvasAnticheat");

    private static volatile ThreadPoolExecutor executor;

    private AnticheatExecutor() {
    }

    public static Executor get() {
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
            threads, threads, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
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
}
