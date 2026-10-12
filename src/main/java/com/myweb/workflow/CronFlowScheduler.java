package com.myweb.workflow;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;

/**
 * 流程级 Cron 调度器（类似 n8n 的 Schedule Trigger）：workflow 构建完成后，由它按 cron 周期
 * 触发<strong>整个流程</strong>的执行，而不是 DAG 内部某个节点。
 * <p>
 * 设计要点：
 * <ul>
 *   <li><b>不重叠</b>：上一次流程尚未执行完毕时，本次 tick 会被跳过(而非排队堆积)。</li>
 *   <li><b>调度与执行解耦</b>：独立的 ticker 线程只负责计算下次触发时间并派发；runner 线程执行
 *       阻塞式 {@link FlowExecutorPro#execute}。下次 tick 在进入本次执行前就已排好，节奏不受
 *       单次执行时长影响。</li>
 *   <li><b>每次执行独立上下文</b>：通过 {@code contextSupplier} 为每一轮生成新的
 *       {@link ExecutionContext}，避免多轮运行的结果互相污染。</li>
 * </ul>
 * <p>
 * cron 采用 Spring {@link CronExpression} 语法：<strong>6 字段、含秒</strong>
 * （秒 分 时 日 月 周），例如 {@code "0 * * * * *"} 表示每分钟整点执行；也支持
 * {@code "@hourly"}、{@code "@daily"} 等宏。
 * <p>
 * 用法：
 * <pre>{@code
 *   FlowExecutorPro executor = new FlowExecutorPro(graph, listener);
 *   try (CronFlowScheduler scheduler =
 *            new CronFlowScheduler(executor, "0/10 * * * * *", "定时触发的输入")) {
 *       scheduler.start();          // 每 10 秒触发一次整条流程
 *       // ... 运行期间可随时 scheduler.stop()
 *   }   // close() 仅停止调度；executor 由调用方自行 close()
 * }</pre>
 */
public class CronFlowScheduler implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(CronFlowScheduler.class);

    private final FlowExecutorPro executor;
    private final CronExpression cron;
    private final Supplier<ExecutionContext> contextSupplier;
    private final String name;

    // ticker: 计算并派发 tick；runner: 实际执行流程(阻塞式 execute)
    private final ScheduledExecutorService ticker;
    private final ExecutorService runner;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicLong firedCount = new AtomicLong(0);
    private final AtomicLong skippedCount = new AtomicLong(0);

    /**
     * 便捷构造：每轮以固定 workflowInput 生成新的 ExecutionContext。
     *
     * @param executor       已构建好的流程执行器
     * @param cronExpression 6 字段 cron 表达式(含秒)
     * @param workflowInput  每轮流程的初始输入(可为 null)
     */
    public CronFlowScheduler(FlowExecutorPro executor, String cronExpression, Object workflowInput) {
        this(executor, cronExpression, () -> {
            ExecutionContext ctx = new ExecutionContext();
            ctx.setWorkflowInput(workflowInput);
            return ctx;
        }, "flow");
    }

    /**
     * @param executor        已构建好的流程执行器
     * @param cronExpression  6 字段 cron 表达式(含秒)；非法时构造即抛 IllegalArgumentException
     * @param contextSupplier 每轮执行的上下文提供者(非空)
     * @param name            日志标识, 便于区分多个调度器
     */
    public CronFlowScheduler(FlowExecutorPro executor, String cronExpression,
                             Supplier<ExecutionContext> contextSupplier, String name) {
        if (executor == null) {
            throw new IllegalArgumentException("`executor` must not be null");
        }
        if (contextSupplier == null) {
            throw new IllegalArgumentException("`contextSupplier` must not be null");
        }
        this.executor = executor;
        this.cron = CronExpression.parse(cronExpression); // 非法表达式在此抛出
        this.contextSupplier = contextSupplier;
        this.name = (name == null || name.isBlank()) ? "flow" : name;

        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "cron-flow-" + this.name);
            t.setDaemon(true);
            t.setUncaughtExceptionHandler((thread, e) ->
                    LOG.error(">> CronFlowScheduler<{}> thread<{}> error:", this.name, thread.getName(), e));
            return t;
        };
        this.ticker = Executors.newSingleThreadScheduledExecutor(tf);
        this.runner = Executors.newSingleThreadExecutor(tf);
    }

    /**
     * 启动调度(幂等)。首次调用后按 cron 排入第一次触发。
     */
    public void start() {
        if (started.compareAndSet(false, true)) {
            LOG.info(">> CronFlowScheduler<{}> started, cron='{}'", name, cron);
            scheduleNextTick();
        }
    }

    private void scheduleNextTick() {
        if (!started.get()) {
            return;
        }
        Instant now = Instant.now();
        Instant next = this.cron.next(now);
        if (next == null) {
            LOG.warn(">> CronFlowScheduler<{}> 无下次触发时间(cron='{}'), 停止调度。", name, cron);
            started.set(false);
            return;
        }
        long delayMillis = Math.max(0L, Duration.between(now, next).toMillis());
        this.ticker.schedule(this::onTick, delayMillis, TimeUnit.MILLISECONDS);
    }

    private void onTick() {
        // 先排好下一次 tick, 使调度节奏与本次执行时长解耦
        scheduleNextTick();

        // 不重叠：上一次仍在执行则跳过本次
        if (!running.compareAndSet(false, true)) {
            long skipped = skippedCount.incrementAndGet();
            LOG.warn(">> CronFlowScheduler<{}> 上一轮流程仍在执行, 跳过本次触发(累计跳过 {}) 次。", name, skipped);
            return;
        }

        firedCount.incrementAndGet();
        final ExecutionContext context = this.contextSupplier.get();
        this.runner.execute(() -> {
            try {
                this.executor.execute(context);
            } catch (Exception e) {
                LOG.error(">> CronFlowScheduler<{}> 流程执行异常:", name, e);
            } finally {
                running.set(false);
            }
        });
    }

    public boolean isStarted() {
        return started.get();
    }

    /**
     * 当前是否有一次流程正在执行中。
     */
    public boolean isRunning() {
        return running.get();
    }

    public long getFiredCount() {
        return firedCount.get();
    }

    public long getSkippedCount() {
        return skippedCount.get();
    }

    /**
     * 停止调度：不再产生新的触发。已提交给 runner 的当前轮次会执行完毕后自然结束。
     * <p>注意：不会关闭被包裹的 {@code executor}(其所有权属于调用方)；如需一并释放请显式调用
     * {@code executor.close()}。
     */
    public void stop() {
        started.set(false);
        
        // 停止 ticker 并等待其终止
        this.ticker.shutdownNow();
        try {
            if (!this.ticker.awaitTermination(2, TimeUnit.SECONDS)) {
                LOG.warn(">> CronFlowScheduler<{}> ticker did not terminate in time", name);
            }
        } catch (InterruptedException e) {
            LOG.warn(">> CronFlowScheduler<{}> interrupted while waiting for ticker termination", name);
            Thread.currentThread().interrupt();
        }
        
        // 停止 runner 并等待当前任务完成
        this.runner.shutdown();
        try {
            // 给予较长的等待时间，因为流程执行可能需要较长时间
            if (!this.runner.awaitTermination(10, TimeUnit.SECONDS)) {
                LOG.warn(">> CronFlowScheduler<{}> runner still executing after 10s, forcing shutdown", name);
                this.runner.shutdownNow();
                // 再次等待强制终止后的清理
                if (!this.runner.awaitTermination(5, TimeUnit.SECONDS)) {
                    LOG.error(">> CronFlowScheduler<{}> runner failed to terminate even after shutdownNow", name);
                }
            }
        } catch (InterruptedException e) {
            LOG.warn(">> CronFlowScheduler<{}> interrupted while waiting for runner termination", name);
            this.runner.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        LOG.info(">> CronFlowScheduler<{}> stopped. fired={}, skipped={}", name, firedCount.get(), skippedCount.get());
    }

    @Override
    public void close() {
        stop();
    }
}
