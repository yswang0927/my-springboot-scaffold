package com.myweb.util.timer;

import java.util.concurrent.TimeUnit;

/**
 * `org/apache/kafka/server/util/timer/Timer.java`
 */
public interface Timer extends AutoCloseable {
    /**
     * Add a new task to this executor. It will be executed after the task's delay
     * (beginning from the time of submission)
     * @param timerTask the task to add
     */
    void add(TimerTask timerTask);

    /**
     * Advance the internal clock, executing any tasks whose expiration has been
     * reached within the duration of the passed timeout.
     * @param timeoutMs the time to advance in milliseconds
     * @return whether or not any tasks were executed
     */
    boolean advanceClock(long timeoutMs) throws InterruptedException;

    /**
     * Get the number of tasks pending execution
     * @return the number of tasks
     */
    int size();

    static long hiResClockMs() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
    }
}
