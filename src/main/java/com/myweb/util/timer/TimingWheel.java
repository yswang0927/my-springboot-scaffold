package com.myweb.util.timer;

import java.util.concurrent.DelayQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * `org/apache/kafka/server/util/timer/TimingWheel.java`
 */
public class TimingWheel {
    private final long tickMs;
    private final int wheelSize;
    private final AtomicInteger taskCounter;
    private final DelayQueue<TimerTaskList> queue;
    private final long interval;
    private final TimerTaskList[] buckets;
    private long currentTimeMs;

    // overflowWheel can potentially be updated and read by two concurrent threads through add().
    // Therefore, it needs to be volatile due to the issue of Double-Checked Locking pattern with JVM
    private volatile TimingWheel overflowWheel = null;

    TimingWheel(long tickMs, int wheelSize, long startMs, AtomicInteger taskCounter, DelayQueue<TimerTaskList> queue) {
        this.tickMs = tickMs;
        this.wheelSize = wheelSize;
        this.taskCounter = taskCounter;
        this.queue = queue;
        this.buckets = new TimerTaskList[wheelSize];
        this.interval = tickMs * wheelSize;
        // rounding down to multiple of tickMs
        this.currentTimeMs = startMs - (startMs % tickMs);

        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = new TimerTaskList(taskCounter);
        }
    }

    private synchronized void addOverflowWheel() {
        if (overflowWheel == null) {
            overflowWheel = new TimingWheel(
                    interval,
                    wheelSize,
                    currentTimeMs,
                    taskCounter,
                    queue
            );
        }
    }

    public boolean add(TimerTaskEntry timerTaskEntry) {
        long expiration = timerTaskEntry.expirationMs;

        if (timerTaskEntry.cancelled()) {
            // Cancelled
            return false;
        } else if (expiration < currentTimeMs + tickMs) {
            // Already expired
            return false;
        } else if (expiration < currentTimeMs + interval) {
            // Put in its own bucket
            long virtualId = expiration / tickMs;
            int bucketId = (int) (virtualId % (long) wheelSize);
            TimerTaskList bucket = buckets[bucketId];
            bucket.add(timerTaskEntry);

            // Set the bucket expiration time
            if (bucket.setExpiration(virtualId * tickMs)) {
                // The bucket needs to be enqueued because it was an expired bucket
                // We only need to enqueue the bucket when its expiration time has changed, i.e. the wheel has advanced
                // and the previous buckets gets reused; further calls to set the expiration within the same wheel cycle
                // will pass in the same value and hence return false, thus the bucket with the same expiration will not
                // be enqueued multiple times.
                queue.offer(bucket);
            }

            return true;
        } else {
            // Out of the interval. Put it into the parent timer
            if (overflowWheel == null) addOverflowWheel();
            return overflowWheel.add(timerTaskEntry);
        }
    }

    public void advanceClock(long timeMs) {
        if (timeMs >= currentTimeMs + tickMs) {
            currentTimeMs = timeMs - (timeMs % tickMs);

            // Try to advance the clock of the overflow wheel if present
            if (overflowWheel != null) {
                overflowWheel.advanceClock(currentTimeMs);
            }
        }
    }
}
