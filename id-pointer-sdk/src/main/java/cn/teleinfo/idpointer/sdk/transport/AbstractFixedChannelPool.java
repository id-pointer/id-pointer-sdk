package cn.teleinfo.idpointer.sdk.transport;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.pool.ChannelHealthChecker;
import io.netty.channel.pool.ChannelPool;
import io.netty.channel.pool.ChannelPoolHandler;
import io.netty.channel.pool.FixedChannelPool;
import io.netty.util.concurrent.*;
import io.netty.util.internal.ObjectUtil;

import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static io.netty.util.internal.ObjectUtil.checkPositive;


public abstract class AbstractFixedChannelPool extends AbstractChannelPool {
    public enum AcquireTimeoutAction {
        /**
         * Create a new connection when the timeout is detected.
         */
        NEW,

        /**
         * Fail the {@link Future} of the acquire call with a {@link TimeoutException}.
         */
        FAIL
    }

    private final EventExecutor executor;
    private final long acquireTimeoutNanos;
    private final Runnable timeoutTask;

    // There is no need to worry about synchronization as everything that modified the queue or counts is done
    // by the above EventExecutor.
    private final Queue<AbstractFixedChannelPool.AcquireTask> pendingAcquireQueue = new ArrayDeque<AbstractFixedChannelPool.AcquireTask>();
    private final int maxConnections;
    private final int maxPendingAcquires;
    private final AtomicInteger acquiredChannelCount = new AtomicInteger();
    private int pendingAcquireCount;
    private boolean closed;

    private long lastActiveTime;

    @Override
    public long getLastActiveTime() {
        return lastActiveTime;
    }

    @Override
    public void setLastActiveTime(long lastActiveTime) {
        this.lastActiveTime = lastActiveTime;
    }



    /**
     * Creates a new instance using the {@link ChannelHealthChecker#ACTIVE}.
     *
     * @param bootstrap      the {@link Bootstrap} that is used for connections
     * @param handler        the {@link ChannelPoolHandler} that will be notified for the different pool actions
     * @param maxConnections the number of maximal active connections, once this is reached new tries to acquire
     *                       a {@link Channel} will be delayed until a connection is returned to the pool again.
     */
    public AbstractFixedChannelPool(Bootstrap bootstrap,
                                    ChannelPoolHandler handler, int maxConnections) {
        this(bootstrap, handler, maxConnections, Integer.MAX_VALUE);
    }

    /**
     * Creates a new instance using the {@link ChannelHealthChecker#ACTIVE}.
     *
     * @param bootstrap          the {@link Bootstrap} that is used for connections
     * @param handler            the {@link ChannelPoolHandler} that will be notified for the different pool actions
     * @param maxConnections     the number of maximal active connections, once this is reached new tries to
     *                           acquire a {@link Channel} will be delayed until a connection is returned to the
     *                           pool again.
     * @param maxPendingAcquires the maximum number of pending acquires. Once this is exceed acquire tries will
     *                           be failed.
     */
    public AbstractFixedChannelPool(Bootstrap bootstrap,
                                    ChannelPoolHandler handler, int maxConnections, int maxPendingAcquires) {
        this(bootstrap, handler, ChannelHealthChecker.ACTIVE, null, -1, maxConnections, maxPendingAcquires);
    }

    /**
     * Creates a new instance.
     *
     * @param bootstrap            the {@link Bootstrap} that is used for connections
     * @param handler              the {@link ChannelPoolHandler} that will be notified for the different pool actions
     * @param healthCheck          the {@link ChannelHealthChecker} that will be used to check if a {@link Channel} is
     *                             still healthy when obtain from the {@link ChannelPool}
     * @param action               the {@link FixedChannelPool.AcquireTimeoutAction} to use or {@code null} if non should be used.
     *                             In this case {@param acquireTimeoutMillis} must be {@code -1}.
     * @param acquireTimeoutMillis the time (in milliseconds) after which an pending acquire must complete or
     *                             the {@link FixedChannelPool.AcquireTimeoutAction} takes place.
     * @param maxConnections       the number of maximal active connections, once this is reached new tries to
     *                             acquire a {@link Channel} will be delayed until a connection is returned to the
     *                             pool again.
     * @param maxPendingAcquires   the maximum number of pending acquires. Once this is exceed acquire tries will
     *                             be failed.
     */
    public AbstractFixedChannelPool(Bootstrap bootstrap,
                                    ChannelPoolHandler handler,
                                    ChannelHealthChecker healthCheck, AbstractFixedChannelPool.AcquireTimeoutAction action,
                                    final long acquireTimeoutMillis,
                                    int maxConnections, int maxPendingAcquires) {
        this(bootstrap, handler, healthCheck, action, acquireTimeoutMillis, maxConnections, maxPendingAcquires, true);
    }

    /**
     * Creates a new instance.
     *
     * @param bootstrap            the {@link Bootstrap} that is used for connections
     * @param handler              the {@link ChannelPoolHandler} that will be notified for the different pool actions
     * @param healthCheck          the {@link ChannelHealthChecker} that will be used to check if a {@link Channel} is
     *                             still healthy when obtain from the {@link ChannelPool}
     * @param action               the {@link FixedChannelPool.AcquireTimeoutAction} to use or {@code null} if non should be used.
     *                             In this case {@param acquireTimeoutMillis} must be {@code -1}.
     * @param acquireTimeoutMillis the time (in milliseconds) after which an pending acquire must complete or
     *                             the {@link FixedChannelPool.AcquireTimeoutAction} takes place.
     * @param maxConnections       the number of maximal active connections, once this is reached new tries to
     *                             acquire a {@link Channel} will be delayed until a connection is returned to the
     *                             pool again.
     * @param maxPendingAcquires   the maximum number of pending acquires. Once this is exceed acquire tries will
     *                             be failed.
     * @param releaseHealthCheck   will check channel health before offering back if this parameter set to
     *                             {@code true}.
     */
    public AbstractFixedChannelPool(Bootstrap bootstrap,
                                    ChannelPoolHandler handler,
                                    ChannelHealthChecker healthCheck, AbstractFixedChannelPool.AcquireTimeoutAction action,
                                    final long acquireTimeoutMillis,
                                    int maxConnections, int maxPendingAcquires, final boolean releaseHealthCheck) {
        this(bootstrap, handler, healthCheck, action, acquireTimeoutMillis, maxConnections, maxPendingAcquires,
                releaseHealthCheck, true);
    }

    /**
     * Creates a new instance.
     *
     * @param bootstrap            the {@link Bootstrap} that is used for connections
     * @param handler              the {@link ChannelPoolHandler} that will be notified for the different pool actions
     * @param healthCheck          the {@link ChannelHealthChecker} that will be used to check if a {@link Channel} is
     *                             still healthy when obtain from the {@link ChannelPool}
     * @param action               the {@link FixedChannelPool.AcquireTimeoutAction} to use or {@code null} if non should be used.
     *                             In this case {@param acquireTimeoutMillis} must be {@code -1}.
     * @param acquireTimeoutMillis the time (in milliseconds) after which an pending acquire must complete or
     *                             the {@link FixedChannelPool.AcquireTimeoutAction} takes place.
     * @param maxConnections       the number of maximal active connections, once this is reached new tries to
     *                             acquire a {@link Channel} will be delayed until a connection is returned to the
     *                             pool again.
     * @param maxPendingAcquires   the maximum number of pending acquires. Once this is exceed acquire tries will
     *                             be failed.
     * @param releaseHealthCheck   will check channel health before offering back if this parameter set to
     *                             {@code true}.
     * @param lastRecentUsed       {@code true} {@link Channel} selection will be LIFO, if {@code false} FIFO.
     */
    public AbstractFixedChannelPool(Bootstrap bootstrap,
                                    ChannelPoolHandler handler,
                                    ChannelHealthChecker healthCheck, AbstractFixedChannelPool.AcquireTimeoutAction action,
                                    final long acquireTimeoutMillis,
                                    int maxConnections, int maxPendingAcquires,
                                    boolean releaseHealthCheck, boolean lastRecentUsed) {
        super(bootstrap, handler, healthCheck, releaseHealthCheck, lastRecentUsed);
        checkPositive(maxConnections, "maxConnections");
        checkPositive(maxPendingAcquires, "maxPendingAcquires");
        if (action == null && acquireTimeoutMillis == -1) {
            timeoutTask = null;
            acquireTimeoutNanos = -1;
        } else if (action == null && acquireTimeoutMillis != -1) {
            throw new NullPointerException("action");
        } else if (action != null && acquireTimeoutMillis < 0) {
            throw new IllegalArgumentException("acquireTimeoutMillis: " + acquireTimeoutMillis + " (expected: >= 0)");
        } else {
            acquireTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(acquireTimeoutMillis);
            switch (action) {
                case FAIL:
                    timeoutTask = new AbstractFixedChannelPool.TimeoutTask() {
                        @Override
                        public void onTimeout(AbstractFixedChannelPool.AcquireTask task) {
                            // Fail the promise as we timed out.
                            task.promise.setFailure(new AbstractFixedChannelPool.AcquireTimeoutException());
                        }
                    };
                    break;
                case NEW:
                    timeoutTask = new AbstractFixedChannelPool.TimeoutTask() {
                        @Override
                        public void onTimeout(AbstractFixedChannelPool.AcquireTask task) {
                            // Increment the acquire count and delegate to super to actually acquire a Channel which will
                            // create a new connection.
                            task.acquired();

                            acquireInternal(task.promise);
                        }
                    };
                    break;
                default:
                    throw new Error();
            }
        }
        executor = bootstrap.config().group().next();
        this.maxConnections = maxConnections;
        this.maxPendingAcquires = maxPendingAcquires;
    }

    /**
     * Returns the number of acquired channels that this pool thinks it has.
     */

    @Override
    public int acquiredChannelCount() {
        return acquiredChannelCount.get();
    }


    @Override
    public Future<Channel> acquire(final Promise<Channel> promise) {
        try {
            if (executor.inEventLoop()) {
                acquire0(promise);
            } else {
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        acquire0(promise);
                    }
                });
            }
        } catch (Throwable cause) {
            promise.setFailure(cause);
        }
        return promise;
    }

    private void acquire0(final Promise<Channel> promise) {
        assert executor.inEventLoop();

        if (closed) {
            promise.setFailure(new IllegalStateException("FixedChannelPool was closed"));
            return;
        }
        if (acquiredChannelCount.get() < maxConnections) {
            assert acquiredChannelCount.get() >= 0;

            // We need to create a new promise as we need to ensure the AcquireListener runs in the correct
            // EventLoop
            Promise<Channel> p = executor.newPromise();
            AbstractFixedChannelPool.AcquireListener l = new AbstractFixedChannelPool.AcquireListener(promise);
            l.acquired();
            p.addListener(l);
            acquireInternal(p);
        } else {
            if (pendingAcquireCount >= maxPendingAcquires) {
                tooManyOutstanding(promise);
            } else {
                AbstractFixedChannelPool.AcquireTask task = new AbstractFixedChannelPool.AcquireTask(promise);
                if (pendingAcquireQueue.offer(task)) {
                    ++pendingAcquireCount;

                    if (timeoutTask != null) {
                        task.timeoutFuture = executor.schedule(timeoutTask, acquireTimeoutNanos, TimeUnit.NANOSECONDS);
                    }
                } else {
                    tooManyOutstanding(promise);
                }
            }

            assert pendingAcquireCount > 0;
        }
    }

    private void tooManyOutstanding(Promise<?> promise) {
        promise.setFailure(new IllegalStateException("Too many outstanding acquire operations"));
    }

    @Override
    public Future<Void> release(final Channel channel, final Promise<Void> promise) {
        ObjectUtil.checkNotNull(promise, "promise");
        final Promise<Void> p = executor.newPromise();
        super.release(channel, p.addListener(new FutureListener<Void>() {

            @Override
            public void operationComplete(Future<Void> future) throws Exception {
                assert executor.inEventLoop();

                if (closed) {
                    // Since the pool is closed, we have no choice but to close the channel
                    channel.close();
                    promise.setFailure(new IllegalStateException("FixedChannelPool was closed"));
                    return;
                }

                if (future.isSuccess()) {
                    decrementAndRunTaskQueue();
                    promise.setSuccess(null);
                } else {
                    Throwable cause = future.cause();
                    // Check if the exception was not because of we passed the Channel to the wrong pool.
                    if (!(cause instanceof IllegalArgumentException)) {
                        decrementAndRunTaskQueue();
                    }
                    promise.setFailure(future.cause());
                }
            }
        }));
        return promise;
    }

    private void decrementAndRunTaskQueue() {
        // We should never have a negative value.
        int currentCount = acquiredChannelCount.decrementAndGet();
        assert currentCount >= 0;

        // Run the pending acquire tasks before notify the original promise so if the user would
        // try to acquire again from the ChannelFutureListener and the pendingAcquireCount is >=
        // maxPendingAcquires we may be able to run some pending tasks first and so allow to add
        // more.
        runTaskQueue();
    }

    private void runTaskQueue() {
        while (acquiredChannelCount.get() < maxConnections) {
            AbstractFixedChannelPool.AcquireTask task = pendingAcquireQueue.poll();
            if (task == null) {
                break;
            }

            // Cancel the timeout if one was scheduled
            ScheduledFuture<?> timeoutFuture = task.timeoutFuture;
            if (timeoutFuture != null) {
                timeoutFuture.cancel(false);
            }

            --pendingAcquireCount;
            task.acquired();

            acquireInternal(task.promise);
        }

        // We should never have a negative value.
        assert pendingAcquireCount >= 0;
        assert acquiredChannelCount.get() >= 0;
    }

    protected abstract void acquireInternal(Promise<Channel> promise);

    // AcquireTask extends AcquireListener to reduce object creations and so GC pressure
    private final class AcquireTask extends AbstractFixedChannelPool.AcquireListener {
        final Promise<Channel> promise;
        final long expireNanoTime = System.nanoTime() + acquireTimeoutNanos;
        ScheduledFuture<?> timeoutFuture;

        AcquireTask(Promise<Channel> promise) {
            super(promise);
            // We need to create a new promise as we need to ensure the AcquireListener runs in the correct
            // EventLoop.
            this.promise = executor.<Channel>newPromise().addListener(this);
        }
    }

    private abstract class TimeoutTask implements Runnable {
        @Override
        public final void run() {
            assert executor.inEventLoop();
            long nanoTime = System.nanoTime();
            for (; ; ) {
                AbstractFixedChannelPool.AcquireTask task = pendingAcquireQueue.peek();
                // Compare nanoTime as descripted in the javadocs of System.nanoTime()
                //
                // See https://docs.oracle.com/javase/7/docs/api/java/lang/System.html#nanoTime()
                // See https://github.com/netty/netty/issues/3705
                if (task == null || nanoTime - task.expireNanoTime < 0) {
                    break;
                }
                pendingAcquireQueue.remove();

                --pendingAcquireCount;
                onTimeout(task);
            }
        }

        public abstract void onTimeout(AbstractFixedChannelPool.AcquireTask task);
    }

    private class AcquireListener implements FutureListener<Channel> {
        private final Promise<Channel> originalPromise;
        protected boolean acquired;

        AcquireListener(Promise<Channel> originalPromise) {
            this.originalPromise = originalPromise;
        }

        @Override
        public void operationComplete(Future<Channel> future) throws Exception {
            assert executor.inEventLoop();

            if (closed) {
                if (future.isSuccess()) {
                    // Since the pool is closed, we have no choice but to close the channel
                    future.getNow().close();
                }
                originalPromise.setFailure(new IllegalStateException("FixedChannelPool was closed"));
                return;
            }

            if (future.isSuccess()) {
                originalPromise.setSuccess(future.getNow());
            } else {
                if (acquired) {
                    decrementAndRunTaskQueue();
                } else {
                    runTaskQueue();
                }

                originalPromise.setFailure(future.cause());
            }
        }

        public void acquired() {
            if (acquired) {
                return;
            }
            acquiredChannelCount.incrementAndGet();
            acquired = true;
        }
    }

    @Override
    public void close() {
        try {
            closeAsync().await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    /**
     * Closes the pool in an async manner.
     *
     * @return Future which represents completion of the close task
     */
    @Override
    public Future<Void> closeAsync() {
        if (executor.inEventLoop()) {
            return close0();
        } else {
            final Promise<Void> closeComplete = executor.newPromise();
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    close0().addListener(new FutureListener<Void>() {
                        @Override
                        public void operationComplete(Future<Void> f) throws Exception {
                            if (f.isSuccess()) {
                                closeComplete.setSuccess(null);
                            } else {
                                closeComplete.setFailure(f.cause());
                            }
                        }
                    });
                }
            });
            return closeComplete;
        }
    }

    private Future<Void> close0() {
        assert executor.inEventLoop();

        if (!closed) {
            closed = true;
            for (; ; ) {
                AbstractFixedChannelPool.AcquireTask task = pendingAcquireQueue.poll();
                if (task == null) {
                    break;
                }
                ScheduledFuture<?> f = task.timeoutFuture;
                if (f != null) {
                    f.cancel(false);
                }
                task.promise.setFailure(new ClosedChannelException());
            }
            acquiredChannelCount.set(0);
            pendingAcquireCount = 0;

            // Ensure we dispatch this on another Thread as close0 will be called from the EventExecutor and we need
            // to ensure we will not block in a EventExecutor.
            return GlobalEventExecutor.INSTANCE.submit(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    AbstractFixedChannelPool.super.close();
                    return null;
                }
            });
        }

        return GlobalEventExecutor.INSTANCE.newSucceededFuture(null);
    }

    private static final class AcquireTimeoutException extends TimeoutException {

        private AcquireTimeoutException() {
            super("Acquire operation took longer then configured maximum time");
        }

        // Suppress a warning since the method doesn't need synchronization
        @Override
        public Throwable fillInStackTrace() {   // lgtm[java/non-sync-override]
            return this;
        }
    }



}
