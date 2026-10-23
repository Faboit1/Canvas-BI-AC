package io.canvasmc.canvas.anticheat;

import io.canvasmc.canvas.GlobalConfiguration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The worker pools for all asynchronous visibility computations. Work is submitted by {@link PlayerVisibility}, at
 * most one job per player and module at a time, so the pools never accumulate a backlog larger than a few jobs per
 * online player.
 * <p>
 * Urgent jobs, whose results players notice when they are late (entities popping in, chunks waiting to be sent), run
 * on their own threads, so expensive jobs of the other modules (anti-xray passes in large bases) can never delay
 * them. Jobs of a pool run in submission order.
 */
public final class AnticheatExecutor {

    public static final Logger LOGGER = LoggerFactory.getLogger("CanvasAnticheat");

    private static volatile ThreadPoolExecutor urgent;
    private static volatile ThreadPoolExecutor background;

    private AnticheatExecutor() {
    }

    public static void execute(final Runnable task, final boolean urgent) {
        ThreadPoolExecutor executor = urgent ? AnticheatExecutor.urgent : AnticheatExecutor.background;
        if (executor == null) {
            executor = create(urgent);
        }
        executor.execute(task);
    }

    private static synchronized ThreadPoolExecutor create(final boolean urgent) {
        ThreadPoolExecutor ret = urgent ? AnticheatExecutor.urgent : AnticheatExecutor.background;
        if (ret != null) {
            return ret;
        }
        final int configured = GlobalConfiguration.getInstance().anticheat.workerThreads;
        final int total = configured > 0 ? configured : defaultThreadCount();
        // the urgent pool gets the larger half, both pools at least one thread
        final int threads = urgent ? Math.max(1, (total + 1) / 2) : Math.max(1, total / 2);
        final String name = urgent ? "Canvas Anticheat Worker #" : "Canvas Anticheat Background Worker #";
        final AtomicInteger id = new AtomicInteger();
        ret = new ThreadPoolExecutor(
            threads, threads, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
            (final Runnable runnable) -> {
                final Thread thread = new Thread(runnable, name + id.getAndIncrement());
                thread.setDaemon(true);
                thread.setUncaughtExceptionHandler((final Thread t, final Throwable thrown) -> LOGGER.error("Uncaught exception in {}", t.getName(), thrown));
                return thread;
            }
        );
        ret.allowCoreThreadTimeOut(true);
        LOGGER.info("Started {} anticheat worker pool with {} thread(s)", urgent ? "urgent" : "background", threads);
        if (urgent) {
            AnticheatExecutor.urgent = ret;
        } else {
            AnticheatExecutor.background = ret;
        }
        return ret;
    }

    private static int defaultThreadCount() {
        // region threads take most of the cores on Folia, the visibility work is comparatively cheap
        return Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() / 4));
    }
}
