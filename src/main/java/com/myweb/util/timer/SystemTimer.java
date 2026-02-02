package com.myweb.util.timer;

import java.util.concurrent.DelayQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * `org/apache/kafka/server/util/timer/SystemTimer.java`
 */
public class SystemTimer implements Timer {
    private static final Logger LOG = LoggerFactory.getLogger(SystemTimer.class);

    public static final String SYSTEM_TIMER_THREAD_PREFIX = "timingwheel-executor-";

    // timeout timer
    private final ExecutorService taskExecutor;
    private final DelayQueue<TimerTaskList> delayQueue;
    private final AtomicInteger taskCounter;
    private final TimingWheel timingWheel;

    // Locks used to protect data structures while ticking
    private final ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.ReadLock readLock = readWriteLock.readLock();
    private final ReentrantReadWriteLock.WriteLock writeLock = readWriteLock.writeLock();

    public SystemTimer(String executorName) {
        this(executorName, 1, 20, Timer.hiResClockMs());
    }

    /**
     * @param executorName 定时器名称，用于标识
     * @param tickMs 时间轮每个tick的毫秒数(基本时间单元)
     * @param wheelSize 时间轮的槽位数
     */
    public SystemTimer(String executorName, long tickMs, int wheelSize) {
        this(executorName, tickMs, wheelSize, Timer.hiResClockMs());
    }

    /**
     * @param executorName 定时器名称，用于标识
     * @param tickMs 时间轮每个tick的毫秒数(基本时间单元)
     * @param wheelSize 时间轮的槽位数
     * @param startMs 起始时间戳(毫秒)
     */
    public SystemTimer(final String executorName, final long tickMs, final int wheelSize, final long startMs) {
        this(tickMs, wheelSize, startMs, Executors.newFixedThreadPool(1,
                runnable -> new Thread(runnable, SYSTEM_TIMER_THREAD_PREFIX + executorName)));
    }

    public SystemTimer(long tickMs, int wheelSize, ExecutorService taskExecutor) {
        this(tickMs, wheelSize, Timer.hiResClockMs(), taskExecutor);
    }

    public SystemTimer(long tickMs, int wheelSize, long startMs, ExecutorService taskExecutor) {
        this.taskExecutor = taskExecutor;
        this.delayQueue = new DelayQueue<>();
        this.taskCounter = new AtomicInteger(0);
        this.timingWheel = new TimingWheel(tickMs, wheelSize, startMs, taskCounter, delayQueue);
    }

    public void add(TimerTask timerTask) {
        readLock.lock();
        try {
            addTimerTaskEntry(new TimerTaskEntry(timerTask, timerTask.delayMs + Timer.hiResClockMs()));
        } finally {
            readLock.unlock();
        }
    }

    private void addTimerTaskEntry(TimerTaskEntry timerTaskEntry) {
        if (!timingWheel.add(timerTaskEntry)) {
            // Already expired or cancelled
            if (!timerTaskEntry.cancelled()) {
                taskExecutor.submit(timerTaskEntry.timerTask);
            }
        }
    }

    /**
     * Advances the clock if there is an expired bucket. If there isn't any expired bucket when called,
     * waits up to timeoutMs before giving up.
     *
     * 时间推进: 通过advanceClock(timeoutMs)方法推进时间轮
     * 通常在实际使用中，Kafka会有后台线程定期调用此方法
     */
    public boolean advanceClock(long timeoutMs) throws InterruptedException {
        TimerTaskList bucket = delayQueue.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (bucket != null) {
            writeLock.lock();
            try {
                while (bucket != null) {
                    timingWheel.advanceClock(bucket.getExpiration());
                    bucket.flush(this::addTimerTaskEntry);
                    bucket = delayQueue.poll();
                }
            } finally {
                writeLock.unlock();
            }
            return true;
        } else {
            return false;
        }
    }

    public int size() {
        return taskCounter.get();
    }

    @Override
    public void close() {
        shutdownExecutorServiceQuietly(taskExecutor, 5, TimeUnit.SECONDS);
    }

    // visible for testing
    boolean isTerminated() {
        return taskExecutor.isTerminated();
    }

    private static void shutdownExecutorServiceQuietly(ExecutorService executorService, long timeout, TimeUnit timeUnit) {
        if (executorService == null) {
            return;
        }

        executorService.shutdown(); // Disable new tasks from being submitted
        try {
            // Wait a while for existing tasks to terminate
            if (!executorService.awaitTermination(timeout, timeUnit)) {
                executorService.shutdownNow(); // Cancel currently executing tasks
                // Wait a while for tasks to respond to being cancelled
                if (!executorService.awaitTermination(timeout, timeUnit)) {
                    LOG.error("Executor {} did not terminate in time", executorService);
                }
            }
        } catch (InterruptedException e) {
            // (Re-)Cancel if current thread also interrupted
            executorService.shutdownNow();
            // Preserve interrupt status
            Thread.currentThread().interrupt();
        }
    }
}