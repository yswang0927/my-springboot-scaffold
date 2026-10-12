package com.myweb.workflow;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.myweb.workflow.exception.FlowExecuteException;
import com.myweb.workflow.exception.FlowFailException;
import com.myweb.workflow.exception.FlowPauseException;
import com.myweb.workflow.exception.FlowSkipException;
import com.myweb.workflow.graph.GNode;
import com.myweb.workflow.graph.GNodeInput;
import com.myweb.workflow.graph.Graph;
import com.myweb.workflow.nodes.AbstractNode;
import com.myweb.workflow.nodes.StartNode;

/**
 * DAG 流程执行器。
 */
public class FlowExecutorPro implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(FlowExecutorPro.class);

    /** 空闲时主循环 poll 上限（秒） */
    private static final int TASK_POLL_TIMEOUT_SECONDS = 2;
    /**
     * 有待执行重试或就绪队列非空时的短 poll（毫秒）。
     * 避免 retry 仅 offerReady 后主循环仍阻塞在 2s poll 上，把 retryDelayMillis 量化成 ~2s。
     */
    private static final long RETRY_WAIT_POLL_MILLIS = 50L;
    private static final int SHUTDOWN_AWAIT_SECONDS = 10;
    private static final int MAX_POOL_SIZE_CEILING = 256;

    private final ConcurrentMap<String, TaskNode> runNodes = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicInteger> currentInDegree = new ConcurrentHashMap<>();
    /** 就绪队列：FIFO；配合 readySet 做原子去重 */
    private final ConcurrentLinkedQueue<String> readyQueue = new ConcurrentLinkedQueue<>();
    private final Set<String> readySet = ConcurrentHashMap.newKeySet();

    // 重试计数
    private final ConcurrentMap<String, AtomicInteger> retryCounts = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, Future<NodeExecutionResult>> runningFutures = new ConcurrentHashMap<>();
    private final ConcurrentMap<Future<NodeExecutionResult>, String> future2NodeIdMap = new ConcurrentHashMap<>();

    private final AtomicInteger completedTasksNum = new AtomicInteger(0);
    private final AtomicInteger scheduledRetryTasksNum = new AtomicInteger(0);
    /** 按 nodeId 去重的失败结果（NodeExecutionResult.equals 基于 nodeId） */
    private final Set<NodeExecutionResult> failedTasks = ConcurrentHashMap.newKeySet();
    private final Set<String> completedNodes = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<String, String> branchExcludedNodes = new ConcurrentHashMap<>();

    private final Object stateLock = new Object();
    private volatile ExecutionState executionState = ExecutionState.READY;

    /** 当前暂停的节点（仅 PAUSED 时有意义） */
    private volatile String pausedNodeId;
    /** 暂停时的上下文引用，供 resume 使用 */
    private volatile ExecutionContext pausedContext;

    private final ExecutorCompletionService<NodeExecutionResult> executorService;
    private final ScheduledExecutorService retryExecutorService;
    private final ExecutionListener executionListener;

    private final ExecutorService managedExecutor;
    private final ExecutorService actualExecutor;

    private final Graph dagGraph;

    /** 整图最大执行时长（毫秒）；<=0 表示不限制 */
    private final long flowTimeoutMillis;

    /**
     * 超时看门狗：到期后主动将状态置为 TIMED_OUT 并 cancel 运行中任务，
     * 无需等待主循环 poll 周期（最多 2s）才发现超时。
     */
    private final ScheduledExecutorService watchdogExecutor;
    private volatile ScheduledFuture<?> watchdogFuture;
    private volatile Instant flowDeadline;
    /** 是否已触发超时（幂等，避免看门狗与主循环双重处理） */
    private final AtomicBoolean flowTimedOut = new AtomicBoolean(false);

    public enum ExecutionState {
        READY, RUNNING, CANCELLED, COMPLETED, FAILED, PAUSED, TIMED_OUT;
    }

    public FlowExecutorPro(Graph flowGraph, ExecutionListener listener) {
        this(flowGraph, listener, null, 0L);
    }

    public FlowExecutorPro(Graph flowGraph, ExecutionListener listener, ExecutorService executor) {
        this(flowGraph, listener, executor, 0L);
    }

    /**
     * @param flowTimeoutMillis 整图超时毫秒，&lt;=0 不限制
     */
    public FlowExecutorPro(Graph flowGraph, ExecutionListener listener, ExecutorService executor, long flowTimeoutMillis) {
        if (flowGraph == null) {
            throw new FlowExecuteException("`Graph` must not be null");
        }

        this.dagGraph = flowGraph;
        this.dagGraph.initialize();
        this.flowTimeoutMillis = flowTimeoutMillis;

        if (executor == null) {
            final int maxParallel = Math.max(1, this.dagGraph.getMaxParallelism());
            final int poolSize = Math.min(maxParallel, MAX_POOL_SIZE_CEILING);

            final ThreadPoolExecutor tpe = new ThreadPoolExecutor(
                    poolSize,
                    poolSize,
                    60L,
                    TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(),
                    new ThreadFactory() {
                        private final AtomicLong tn = new AtomicLong(0);
                        @Override
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "flow-executor-" + tn.incrementAndGet());
                            t.setDaemon(true);
                            t.setUncaughtExceptionHandler((thread, e) ->
                                    LOG.error(">> ERROR: Flow-Executor thread<{}> exception: ", thread.getName(), e));
                            return t;
                        }
                    }
            );
            tpe.allowCoreThreadTimeOut(true);
            this.managedExecutor = tpe;
            this.actualExecutor = tpe;
        } else {
            this.managedExecutor = null;
            this.actualExecutor = executor;
        }

        this.executionListener = listener != null ? listener : new DefaultExecutionListener();
        this.executorService = new ExecutorCompletionService<>(this.actualExecutor);
        this.retryExecutorService = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "flow-retry-scheduler");
            t.setDaemon(true);
            t.setUncaughtExceptionHandler((thread, e) ->
                    LOG.error(">> ERROR: Retry-Scheduler thread exception: ", e));
            return t;
        });
        this.watchdogExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "flow-timeout-watchdog");
            t.setDaemon(true);
            t.setUncaughtExceptionHandler((thread, e) ->
                    LOG.error(">> ERROR: Timeout-Watchdog thread exception: ", e));
            return t;
        });

        try {
            this.initializeNodes();
        } catch (Exception e) {
            try {
                this.close();
            } catch (Exception ignored) {
                // ignored
            }
            throw new FlowExecuteException("Failed to initialize executor", e);
        }
    }

    private void initializeNodes() {
        List<GNode> nodes = this.dagGraph.getNodes();
        for (GNode node : nodes) {
            this.runNodes.put(node.getId(), TaskNodeFactory.createNode(node));
            this.retryCounts.put(node.getId(), new AtomicInteger(0));
        }
        this.dagGraph.getNodesInDegree().forEach((nodeId, degree) -> this.currentInDegree.put(nodeId, new AtomicInteger(degree)));
    }

    private void resetExecutionState() {
        this.dagGraph.getNodesInDegree().forEach((nodeId, degree) -> this.currentInDegree.get(nodeId).set(degree));
        this.runNodes.values().forEach(n -> n.setTaskState(TaskState.PENDING));
        this.retryCounts.values().forEach(counter -> counter.set(0));
        this.readyQueue.clear();
        this.readySet.clear();
        this.runningFutures.values().forEach(f -> f.cancel(true));
        this.runningFutures.clear();
        this.future2NodeIdMap.clear();
        this.completedTasksNum.set(0);
        this.scheduledRetryTasksNum.set(0);
        this.failedTasks.clear();
        this.completedNodes.clear();
        this.branchExcludedNodes.clear();
        this.pausedNodeId = null;
        this.pausedContext = null;
        this.flowTimedOut.set(false);
        this.flowDeadline = null;
        cancelWatchdog();
    }

    private void initializeReadyQueue() {
        this.runNodes.values().forEach(node -> {
            int degree = this.currentInDegree.get(node.getId()).get();
            if (degree == 0 || node.getTriggerRule() == TaskTriggerRule.ALWAYS) {
                offerReady(node.getId());
            }
        });
    }

    /** 原子入就绪队列，避免并发重复入队 */
    private void offerReady(String nodeId) {
        if (nodeId != null && this.readySet.add(nodeId)) {
            this.readyQueue.offer(nodeId);
        }
    }

    private String pollReady() {
        String id = this.readyQueue.poll();
        if (id != null) {
            this.readySet.remove(id);
        }
        return id;
    }

    /**
     * 主循环 poll 超时：空闲用长超时省 CPU；有重试在途或就绪队列非空时用短超时，
     * 使 retryDelayMillis 近似生效，而不会被量化到 {@link #TASK_POLL_TIMEOUT_SECONDS}。
     */
    private long nextPollTimeoutMillis() {
        if (!this.readyQueue.isEmpty() || this.scheduledRetryTasksNum.get() > 0) {
            return RETRY_WAIT_POLL_MILLIS;
        }
        return TimeUnit.SECONDS.toMillis(TASK_POLL_TIMEOUT_SECONDS);
    }

    /**
     * resume 时重新扫描：所有仍为 PENDING、且未被分支排除、触发规则已满足的节点，重新入就绪队列。
     * 覆盖「暂停期间到期的重试回调因 state!=RUNNING 未入队」的情况。
     */
    private void reofferPendingReadyNodes(ExecutionContext context) {
        for (TaskNode node : this.runNodes.values()) {
            final String id = node.getId();
            if (node.getTaskState() != TaskState.PENDING) {
                continue;
            }

            if (this.branchExcludedNodes.containsKey(id)) {
                continue;
            }

            if (this.completedNodes.contains(id)) {
                continue;
            }

            Collection<String> upstreamNodeIds = this.dagGraph.getUpstreamNodes(id);
            List<TaskState> upstreamStates = new ArrayList<>(upstreamNodeIds.size());
            for (String unid : upstreamNodeIds) {
                TaskNode up = this.runNodes.get(unid);
                upstreamStates.add(up != null ? up.getTaskState() : TaskState.PENDING);
            }

            TaskTriggerRule rule = node.getTriggerRule();
            if (rule == null) {
                rule = TaskTriggerRule.ALL_SUCCESS;
            }

            if (rule == TaskTriggerRule.ALWAYS || rule.evaluate(upstreamStates)) {
                offerReady(id);
            }
        }

        submitReadyTasks(context);
    }

    /**
     * 执行 workflow（阻塞直至完成、失败、取消或暂停）。
     */
    public void execute(ExecutionContext context) {
        if (this.runNodes.isEmpty()) {
            LOG.warn(">> WARNING: No nodes to execute.");
            return;
        }

        if (context == null) {
            context = new ExecutionContext();
        }

        try {
            this.executionListener.onFlowStart();
        } catch (Exception e) {
            LOG.error(">> ERROR: Flow start listener threw exception", e);
        }

        FlowExecutionResult flowExecutionResult = new FlowExecutionResult();
        flowExecutionResult.setStartTime(Instant.now());

        synchronized (this.stateLock) {
            if (this.executionState == ExecutionState.RUNNING) {
                throw new FlowExecuteException("Executor is already running: " + this.executionState);
            }
            resetExecutionState();
            this.executionState = ExecutionState.RUNNING;
        }

        initializeReadyQueue();
        submitReadyTasks(context);
        armWatchdog();

        try {
            runMainLoop(context);
            finalizeExecution(flowExecutionResult);
        } catch (Exception e) {
            LOG.error("Execution Loop Error", e);
            cancelAllRunningTasks(ExecutionState.FAILED);
            flowExecutionResult.setSuccess(false);
        } finally {
            cancelWatchdog();
            notifyFlowCompletion(flowExecutionResult);
        }
    }

    /**
     * 从暂停点恢复执行。
     * <p>
     * 典型用法：交互节点抛出 {@link FlowPauseException} 后，外部系统写入用户输入到 context，
     * 再调用本方法。暂停节点会被标记为 SUCCESS（视为交互已完成），并继续评估下游。
     *
     * @param context 可与暂停时相同的上下文，或带用户输入的更新上下文；为 null 时使用暂停时上下文
     */
    public void resume(ExecutionContext context) {
        final String nodeId;
        final ExecutionContext ctx;
        synchronized (this.stateLock) {
            if (this.executionState != ExecutionState.PAUSED) {
                throw new FlowExecuteException("Executor is not paused, current state: " + this.executionState);
            }

            nodeId = this.pausedNodeId;
            ctx = context != null ? context : this.pausedContext;
            if (ctx == null) {
                throw new FlowExecuteException("No execution context available for resume");
            }

            this.executionState = ExecutionState.RUNNING;
            this.pausedNodeId = null;
            this.pausedContext = null;
        }

        FlowExecutionResult flowExecutionResult = new FlowExecutionResult();
        flowExecutionResult.setStartTime(Instant.now());

        if (nodeId != null) {
            TaskNode node = this.runNodes.get(nodeId);
            if (node != null && node.getTaskState() == TaskState.PAUSED) {
                node.setTaskState(TaskState.SUCCESS);
                // 暂停节点在 pause 时已计入 completedTasksNum；若未计入则补计
                if (!this.completedNodes.contains(nodeId)) {
                    this.completedNodes.add(nodeId);
                    this.completedTasksNum.incrementAndGet();
                }

                NodeExecutionResult successResult = NodeExecutionResult.success()
                        .setNodeId(nodeId)
                        .setEndTime(Instant.now());
                // 若上下文尚无结果，写入成功占位，便于下游取数
                if (ctx.getNodeExecutionResult(nodeId).isEmpty()) {
                    ctx.addNodeExecutionResult(nodeId, successResult);
                }
                notifyNodeCompletion(successResult);
                evaluateAndTriggerDownstream(nodeId, ctx, null);
            }
        }

        // PAUSED 期间到期的重试只保持 PENDING、未入队；恢复后把已可运行的 PENDING 节点重新入队
        reofferPendingReadyNodes(ctx);

        armWatchdog();

        try {
            runMainLoop(ctx);
            finalizeExecution(flowExecutionResult);
        } catch (Exception e) {
            LOG.error("Resume Execution Loop Error", e);
            cancelAllRunningTasks(ExecutionState.FAILED);
            flowExecutionResult.setSuccess(false);
        } finally {
            cancelWatchdog();
            notifyFlowCompletion(flowExecutionResult);
        }
    }

    private void runMainLoop(ExecutionContext context) throws InterruptedException {
        while (true) {
            final ExecutionState state = this.executionState;

            // 暂停：不再提交新任务，但必须收完已在跑的 Future，避免结果丢失
            if (state == ExecutionState.PAUSED) {
                drainRunningFutures(context, /*submitNew*/ false);
                break;
            }
            // 超时 / 取消 / 失败终态：收割已取消的 Future 后退出
            if (state == ExecutionState.TIMED_OUT || state == ExecutionState.CANCELLED || state == ExecutionState.FAILED) {
                drainRunningFutures(context, /*submitNew*/ false);
                break;
            }

            if (state != ExecutionState.RUNNING) {
                break;
            }

            if (this.completedTasksNum.get() >= this.runNodes.size()) {
                break;
            }

            // 双保险：主循环也检查 deadline（看门狗负责主动打断，这里兜底）
            if (isFlowDeadlineExceeded()) {
                triggerFlowTimeout("detected in main loop");
                drainRunningFutures(context, /*submitNew*/ false);
                break;
            }

            // 自适应 poll：有重试在途或已有就绪任务时用短超时，避免把 retryDelay 量化到 2s
            final long pollMillis = nextPollTimeoutMillis();
            final Future<NodeExecutionResult> completedFuture;
            try {
                completedFuture = this.executorService.poll(pollMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                cancelAllRunningTasks(ExecutionState.FAILED);
                break;
            }

            if (completedFuture == null) {
                if (!this.readyQueue.isEmpty()) {
                    submitReadyTasks(context);
                    continue;
                }

                if (this.runningFutures.isEmpty() && this.readyQueue.isEmpty() && this.scheduledRetryTasksNum.get() == 0) {
                    if (this.completedTasksNum.get() < this.runNodes.size()) {
                        LOG.warn(">> WARNING: Execution loop exited early. Completed: {}, Total: {}, Missing: {}",
                                this.completedTasksNum.get(),
                                this.runNodes.size(),
                                this.runNodes.size() - this.completedTasksNum.get());
                        Set<String> uncompletedNodes = new HashSet<>(this.runNodes.keySet());
                        uncompletedNodes.removeAll(this.completedNodes);
                        if (!uncompletedNodes.isEmpty()) {
                            LOG.warn(">> Uncompleted nodes: {}", uncompletedNodes);
                        }
                    }
                    break;
                }
                continue;
            }

            processCompletedFuture(completedFuture, context);
        }
    }

    /**
     * 收割已提交但仍在运行的任务结果。
     * @param submitNew 是否在收割后继续提交就绪队列（正常 RUNNING 为 true；PAUSED 为 false）
     */
    private void drainRunningFutures(ExecutionContext context, boolean submitNew) throws InterruptedException {
        while (!this.runningFutures.isEmpty() || this.scheduledRetryTasksNum.get() > 0) {
            Future<NodeExecutionResult> completedFuture = this.executorService.poll(TASK_POLL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (completedFuture == null) {
                // 重试仍在排队时继续等
                if (this.runningFutures.isEmpty() && this.scheduledRetryTasksNum.get() == 0) {
                    break;
                }
                continue;
            }

            processCompletedFuture(completedFuture, context);

            if (submitNew && this.executionState == ExecutionState.RUNNING) {
                submitReadyTasks(context);
            }
        }
    }

    private void processCompletedFuture(Future<NodeExecutionResult> completedFuture,
                                        ExecutionContext context) throws InterruptedException {
        // 先 take 结果，再解析 nodeId：极快任务可能在 future2NodeIdMap.put 之前就完成，
        // 若仅依赖 map 会丢事件，节点永久停在 RUNNING，主循环因 runningFutures 非空而挂死。
        NodeExecutionResult taskResult = null;
        Throwable executionError = null;
        boolean cancelled = false;
        try {
            taskResult = completedFuture.get();
        } catch (ExecutionException e) {
            executionError = e.getCause() != null ? e.getCause() : e;
        } catch (CancellationException e) {
            cancelled = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelAllRunningTasks(ExecutionState.FAILED);
            throw e;
        }

        String finishedNodeId = this.future2NodeIdMap.remove(completedFuture);
        if (finishedNodeId == null && taskResult != null) {
            finishedNodeId = taskResult.getNodeId();
        }
        // 反向：从 runningFutures 找到同一个 future
        if (finishedNodeId == null) {
            for (Map.Entry<String, Future<NodeExecutionResult>> e : this.runningFutures.entrySet()) {
                if (e.getValue() == completedFuture) {
                    finishedNodeId = e.getKey();
                    break;
                }
            }
        }
        if (finishedNodeId == null) {
            LOG.warn(">> WARNING: Completed future with unknown node id, ignored");
            return;
        }

        this.runningFutures.remove(finishedNodeId);
        this.future2NodeIdMap.remove(completedFuture);

        if (cancelled) {
            handleCancellation(finishedNodeId, context);
            return;
        }
        if (executionError != null) {
            NodeExecutionResult execResult = NodeExecutionResult.failed(executionError)
                    .setNodeId(finishedNodeId)
                    .setEndTime(Instant.now());
            dispatchFailure(finishedNodeId, execResult, context);
            return;
        }
        if (taskResult == null) {
            handleTaskFailure(NodeExecutionResult.failed("null result").setNodeId(finishedNodeId), context, true);
            return;
        }
        if (taskResult.getNodeId() == null) {
            taskResult.setNodeId(finishedNodeId);
        }
        handleTaskCompletion(finishedNodeId, taskResult, context);
    }

    private void handleTaskCompletion(String nodeId, NodeExecutionResult result, ExecutionContext context) {
        if (result.isSuccess()) {
            this.runNodes.get(nodeId).setTaskState(TaskState.SUCCESS);
            this.completedNodes.add(nodeId);
            this.completedTasksNum.incrementAndGet();
            context.addNodeExecutionResult(nodeId, result);
            notifyNodeCompletion(result);
            evaluateAndTriggerDownstream(nodeId, context, result.getNextNodesToActivate());
        } else {
            dispatchFailure(nodeId, result, context);
        }
    }

    private void dispatchFailure(String nodeId, NodeExecutionResult failedResult, ExecutionContext context) {
        Throwable cause = failedResult.getError();
        if (cause instanceof FlowPauseException) {
            handleTaskPause(nodeId, (FlowPauseException) cause, context);
        } else if (cause instanceof FlowSkipException) {
            handleTaskSkip(nodeId, (FlowSkipException) cause, context);
        } else if (cause instanceof FlowFailException) {
            handleTaskFailure(failedResult, context, false);
        } else {
            handleTaskFailure(failedResult, context, true);
        }
    }

    /**
     * 处理任务失败（含重试）。
     * <p>
     * 重试前必须将状态恢复为 PENDING，否则 submitTask 的 CAS(PENDING→RUNNING) 会失败。
     */
    private void handleTaskFailure(NodeExecutionResult failedResult, ExecutionContext context, final boolean needRetry) {
        final String failedNodeId = failedResult.getNodeId();
        if (failedNodeId == null) {
            return;
        }

        // 同一节点最终失败只记录一次（equals 按 nodeId）
        TaskNode failedNode = this.runNodes.get(failedNodeId);
        if (failedNode == null) {
            return;
        }

        if (needRetry) {
            int maxRetries = failedNode.getMaxRetries();
            int attempt = this.retryCounts.get(failedNodeId).incrementAndGet();
            if (attempt <= maxRetries) {
                LOG.warn(">> WARNING: Retry task <{}> attempt {}/{}", failedNodeId, attempt, maxRetries);
                failedNode.setTaskState(TaskState.PENDING);
                this.scheduledRetryTasksNum.incrementAndGet();
                final long delay = Math.max(0L, failedNode.getRetryDelayMillis());
                this.retryExecutorService.schedule(() -> {
                    try {
                        if (this.executionState == ExecutionState.RUNNING) {
                            offerReady(failedNodeId);
                        }
                        // PAUSED 时不入队：节点保持 PENDING，resume 时由 reofferPendingReadyNodes 统一激活
                    } catch (Exception e) {
                        LOG.error(">> ERROR: Retry enqueue failed for <{}>", failedNodeId, e);
                    } finally {
                        this.scheduledRetryTasksNum.decrementAndGet();
                    }
                }, delay, TimeUnit.MILLISECONDS);
                return;
            }
        }

        LOG.error(">> ERROR: Task <{}> failed after retries.", failedNodeId);
        failedNode.setTaskState(TaskState.FAILED);
        this.failedTasks.add(failedResult);
        this.completedTasksNum.incrementAndGet();
        this.completedNodes.add(failedNodeId);

        context.addNodeExecutionResult(failedNodeId, failedResult);
        notifyNodeCompletion(failedResult);
        evaluateAndTriggerDownstream(failedNodeId, context, null);
    }

    private void handleTaskSkip(String nodeId, FlowSkipException ex, ExecutionContext context) {
        LOG.info(">> Task <{}> skipped programmatically.", nodeId);

        TaskNode node = this.runNodes.get(nodeId);
        node.setTaskState(TaskState.SKIPPED);

        NodeExecutionResult result = NodeExecutionResult.failed("Skipped by logic")
                .setNodeId(nodeId)
                .setSkipped(true)
                .setErrorMessage(ex.getMessage());

        this.completedTasksNum.incrementAndGet();
        this.completedNodes.add(nodeId);

        context.addNodeExecutionResult(nodeId, result);
        notifyNodeCompletion(result);
        evaluateAndTriggerDownstream(nodeId, context, null);
    }

    private void handleTaskPause(String nodeId, FlowPauseException ex, ExecutionContext context) {
        LOG.info(">> Task <{}> paused flow. Reason: {}", nodeId, ex.getMessage());

        synchronized (this.stateLock) {
            this.executionState = ExecutionState.PAUSED;
            this.pausedNodeId = nodeId;
            this.pausedContext = context;
        }

        TaskNode node = this.runNodes.get(nodeId);
        node.setTaskState(TaskState.PAUSED);

        NodeExecutionResult result = NodeExecutionResult.failed("Paused for interaction")
                .setNodeId(nodeId)
                .setErrorMessage(ex.getMessage() != null ? ex.getMessage() : "Paused");

        // 暂停节点计入完成，避免主循环因“未完成”误判；resume 时不再重复计数
        this.completedTasksNum.incrementAndGet();
        this.completedNodes.add(nodeId);

        context.addNodeExecutionResult(nodeId, result);
        notifyNodeCompletion(result);

        try {
            this.executionListener.onFlowPaused(nodeId, ex.getMessage());
        } catch (Exception e) {
            LOG.error(">> ERROR: onFlowPaused listener threw exception", e);
        }
        // 不评估下游：resume 后再激活
    }

    private void handleCancellation(String nodeId, ExecutionContext context) {
        final boolean dueToTimeout = this.flowTimedOut.get() || this.executionState == ExecutionState.TIMED_OUT;
        LOG.warn(">> Task <{}> Cancelled{}.", nodeId, dueToTimeout ? " (flow timeout)" : "");
        String reason = dueToTimeout ? "cancelled by flow timeout" : "Task Cancelled";
        NodeExecutionResult res = NodeExecutionResult.failed(reason).setNodeId(nodeId);
        TaskNode node = this.runNodes.get(nodeId);
        if (node != null) {
            node.setTaskState(TaskState.CANCELLED);
        }
        // 避免重复计数
        if (this.completedNodes.add(nodeId)) {
            this.completedTasksNum.incrementAndGet();
        }

        this.failedTasks.add(res);

        context.addNodeExecutionResult(nodeId, res);
        notifyNodeCompletion(res);
        if (!dueToTimeout) {
            evaluateAndTriggerDownstream(nodeId, context, null);
        }
    }

    private record DownstreamEval(String nodeId, Collection<String> activatedBranch) {}

    private void evaluateAndTriggerDownstream(String finishedNodeId, ExecutionContext context,
                                              Collection<String> activatedBranch) {
        // 超时/取消后不再推进下游，避免与 finalize 的批量终结冲突
        if (this.executionState == ExecutionState.TIMED_OUT
                || this.executionState == ExecutionState.CANCELLED
                || this.flowTimedOut.get()) {
            return;
        }

        Deque<DownstreamEval> pending = new ArrayDeque<>();
        pending.push(new DownstreamEval(finishedNodeId, activatedBranch));

        while (!pending.isEmpty()) {
            DownstreamEval eval = pending.pop();
            Collection<String> downstreamNodes = this.dagGraph.getDownstreamNodes(eval.nodeId());
            if (downstreamNodes == null || downstreamNodes.isEmpty()) {
                continue;
            }

            for (String dependentId : downstreamNodes) {
                TaskNode dependentNode = this.runNodes.get(dependentId);
                if (dependentNode == null) {
                    continue;
                }

                TaskState currentState = dependentNode.getTaskState();
                if (currentState != TaskState.PENDING) {
                    continue;
                }

                final boolean isBranchSkipped = (eval.activatedBranch() != null
                        && !eval.activatedBranch().isEmpty()
                        && !eval.activatedBranch().contains(dependentId));

                final int remainingDependencies = this.currentInDegree.get(dependentId).decrementAndGet();

                if (isBranchSkipped) {
                    this.branchExcludedNodes.putIfAbsent(dependentId, "Not selected by branch node " + eval.nodeId());
                }

                final boolean excluded = this.branchExcludedNodes.containsKey(dependentId);

                Collection<String> upstreamNodeIds = this.dagGraph.getUpstreamNodes(dependentId);
                List<TaskState> upstreamStates = new ArrayList<>(upstreamNodeIds.size());
                for (String unid : upstreamNodeIds) {
                    TaskNode up = this.runNodes.get(unid);
                    upstreamStates.add(up != null ? up.getTaskState() : TaskState.PENDING);
                }

                TaskTriggerRule rule = dependentNode.getTriggerRule();
                if (rule == null) {
                    rule = TaskTriggerRule.ALL_SUCCESS;
                }

                final boolean shouldRun = !excluded && rule.evaluate(upstreamStates);
                if (shouldRun) {
                    offerReady(dependentId);
                } else if (remainingDependencies <= 0) {
                    if (excluded) {
                        if (markNodeAsSkipped(dependentId, this.branchExcludedNodes.get(dependentId), context)) {
                            pending.push(new DownstreamEval(dependentId, null));
                        }
                    } else if (handleRuleMismatch(dependentId, rule, upstreamStates, context)) {
                        pending.push(new DownstreamEval(dependentId, null));
                    }
                }
            }
        }

        submitReadyTasks(context);
    }

    /**
     * 所有上游已结束但触发规则仍不满足时的终态映射。
     */
    private boolean handleRuleMismatch(String nodeId, TaskTriggerRule rule,
                                       List<TaskState> upstreamStates, ExecutionContext context) {
        TaskNode node = this.runNodes.get(nodeId);

        String reason;
        TaskState finalState;

        boolean anyFailed = upstreamStates.stream().anyMatch(s -> s == TaskState.FAILED || s == TaskState.UPSTREAM_FAILED);
        boolean anySkipped = upstreamStates.stream().anyMatch(s -> s == TaskState.SKIPPED);
        boolean allSkipped = !upstreamStates.isEmpty() && upstreamStates.stream().allMatch(s -> s == TaskState.SKIPPED);
        boolean anySuccess = upstreamStates.stream().anyMatch(s -> s == TaskState.SUCCESS);

        switch (rule) {
            case ALL_SUCCESS:
                finalState = anyFailed ? TaskState.UPSTREAM_FAILED : TaskState.SKIPPED;
                reason = anyFailed ? "Upstream failed." : "Upstream skipped.";
                break;
            case ALL_FAILED:
                finalState = TaskState.SKIPPED;
                reason = "Not all upstreams failed.";
                break;
            case ALL_SKIPPED:
                finalState = TaskState.SKIPPED;
                reason = "Not all upstreams skipped.";
                break;
            case ONE_SUCCESS:
                finalState = anyFailed ? TaskState.UPSTREAM_FAILED : TaskState.SKIPPED;
                reason = "No upstream succeeded.";
                break;
            case ONE_FAILED:
                finalState = TaskState.SKIPPED;
                reason = "No upstream failed.";
                break;
            case ONE_DONE:
                finalState = TaskState.SKIPPED;
                reason = "No upstream done with success/fail.";
                break;
            case NONE_FAILED:
            case NONE_FAILED_MIN_ONE_SUCCESS:
                finalState = anyFailed ? TaskState.UPSTREAM_FAILED : (allSkipped || !anySuccess ? TaskState.SKIPPED : TaskState.UPSTREAM_FAILED);
                reason = anyFailed ? "Upstream failed." : "Trigger rule not satisfied.";
                break;
            case NONE_SKIPPED:
                finalState = anySkipped ? TaskState.SKIPPED : TaskState.UPSTREAM_FAILED;
                reason = "Trigger rule NONE_SKIPPED not satisfied.";
                break;
            case ALL_DONE:
            case ALWAYS:
                // 理论上 evaluate 已为 true；兜底跳过
                finalState = TaskState.SKIPPED;
                reason = "Unexpected mismatch for " + rule;
                break;
            default:
                finalState = TaskState.UPSTREAM_FAILED;
                reason = "Trigger rule " + rule + " not satisfied after all upstreams finished.";
        }

        boolean stateChanged = false;
        if (node instanceof AbstractNode abstractNode) {
            stateChanged = abstractNode.compareAndSetTaskState(TaskState.PENDING, finalState);
        } else {
            synchronized (node) {
                if (node.getTaskState() == TaskState.PENDING) {
                    node.setTaskState(finalState);
                    stateChanged = true;
                }
            }
        }

        if (!stateChanged) {
            return false;
        }

        NodeExecutionResult result = NodeExecutionResult.failed(reason)
                .setNodeId(nodeId)
                .setSkipped(finalState == TaskState.SKIPPED);

        this.completedTasksNum.incrementAndGet();
        this.completedNodes.add(nodeId);
        context.addNodeExecutionResult(nodeId, result);
        notifyNodeCompletion(result);
        return true;
    }

    private boolean markNodeAsSkipped(String nodeId, String reason, ExecutionContext context) {
        TaskNode node = this.runNodes.get(nodeId);

        boolean stateChanged = false;
        if (node instanceof AbstractNode abstractNode) {
            stateChanged = abstractNode.compareAndSetTaskState(TaskState.PENDING, TaskState.SKIPPED);
        } else {
            synchronized (node) {
                if (node.getTaskState() == TaskState.PENDING) {
                    node.setTaskState(TaskState.SKIPPED);
                    stateChanged = true;
                }
            }
        }

        if (!stateChanged) {
            return false;
        }

        NodeExecutionResult result = NodeExecutionResult.failed(reason != null ? reason : "Skipped")
                .setNodeId(nodeId)
                .setSkipped(true);

        this.completedNodes.add(nodeId);
        this.completedTasksNum.incrementAndGet();
        context.addNodeExecutionResult(nodeId, result);
        notifyNodeCompletion(result);
        return true;
    }

    private void submitReadyTasks(ExecutionContext context) {
        while (this.executionState == ExecutionState.RUNNING) {
            String nodeId = pollReady();
            if (nodeId == null) {
                break;
            }
            submitTask(nodeId, context);
        }
    }

    private void submitTask(final String nodeId, final ExecutionContext context) {
        final TaskNode runNode = this.runNodes.get(nodeId);
        if (runNode == null) {
            return;
        }

        if (runNode instanceof AbstractNode abstractNode) {
            if (!abstractNode.compareAndSetTaskState(TaskState.PENDING, TaskState.RUNNING)) {
                return;
            }
        } else {
            synchronized (runNode) {
                if (runNode.getTaskState() != TaskState.PENDING) {
                    return;
                }
                runNode.setTaskState(TaskState.RUNNING);
            }
        }

        // 占位登记，缩小 "任务已完成但 map 未写入" 的窗口；真正 Future 随后替换
        this.runningFutures.put(nodeId, CompletableFuture.completedFuture(null));

        Future<NodeExecutionResult> future = this.executorService.submit(() -> {
            Instant startTime = Instant.now();

            if (Thread.currentThread().isInterrupted()) {
                return NodeExecutionResult.failed("Interrupted", new InterruptedException()).setNodeId(nodeId);
            }

            Collection<GNodeInput> upstreamInputs = dagGraph.getUpstreamNodeInputs(nodeId);
            NodeInputs inputs = new NodeInputs();
            for (GNodeInput inputConf : upstreamInputs) {
                Optional<NodeExecutionResult> upResultOpt = context.getNodeExecutionResult(inputConf.getSourceNodeId());
                if (upResultOpt.isPresent()) {
                    NodeExecutionResult upRes = upResultOpt.get();
                    if (upRes.isSuccess() && !upRes.isSkipped()) {
                        NodeOutput out = upRes.getNodeOutput(inputConf.getSourcePort());
                        if (out != null) {
                            inputs.addInput(inputConf.getTargetPort(), out);
                        }
                    }
                }
            }

            if (runNode instanceof StartNode) {
                inputs.addInput(StartNode.DEFAULT_INPUT_PORT_NAME, new NodeOutput(context.getWorkflowInput()));
            }

            try {
                NodeExecutionResult result = runNode.call(context, inputs);
                if (result == null) {
                    result = NodeExecutionResult.success();
                }
                result.setNodeId(nodeId).setStartTime(startTime).setEndTime(Instant.now());
                return result;
            } catch (Exception e) {
                return NodeExecutionResult.failed(e).setNodeId(nodeId).setStartTime(startTime).setEndTime(Instant.now());
            }
        });

        this.runningFutures.put(nodeId, future);
        this.future2NodeIdMap.put(future, nodeId);
    }

    // ==================== 整图超时看门狗 ====================

    /**
     * 启动整图超时看门狗。若 {@code flowTimeoutMillis <= 0} 则不启动。
     * 到期后将状态置为 {@link ExecutionState#TIMED_OUT} 并 cancel 所有运行中任务。
     */
    private void armWatchdog() {
        cancelWatchdog();
        this.flowTimedOut.set(false);
        if (this.flowTimeoutMillis <= 0) {
            this.flowDeadline = null;
            return;
        }

        this.flowDeadline = Instant.now().plusMillis(this.flowTimeoutMillis);
        final long timeout = this.flowTimeoutMillis;
        this.watchdogFuture = this.watchdogExecutor.schedule(() -> {
            try {
                triggerFlowTimeout("watchdog fired");
            } catch (Exception e) {
                LOG.error(">> ERROR: Watchdog trigger failed", e);
            }
        }, timeout, TimeUnit.MILLISECONDS);
        LOG.info(">> Flow timeout watchdog armed: {} ms (deadline={})", timeout, this.flowDeadline);
    }

    private void cancelWatchdog() {
        ScheduledFuture<?> f = this.watchdogFuture;
        this.watchdogFuture = null;
        if (f != null) {
            f.cancel(false);
        }
    }

    private boolean isFlowDeadlineExceeded() {
        Instant dl = this.flowDeadline;
        return dl != null && Instant.now().isAfter(dl);
    }

    /**
     * 触发整图超时（幂等）。可由看门狗线程或主循环调用。
     */
    private void triggerFlowTimeout(String source) {
        if (!this.flowTimedOut.compareAndSet(false, true)) {
            return; // 已处理
        }

        final String msg = String.format("Flow timed out after %d ms (%s)", this.flowTimeoutMillis, source);
        LOG.error(">> ERROR: {}", msg);

        synchronized (this.stateLock) {
            if (this.executionState == ExecutionState.RUNNING || this.executionState == ExecutionState.PAUSED) {
                this.executionState = ExecutionState.TIMED_OUT;
            }
        }

        // 中断所有运行中任务与就绪队列
        this.runningFutures.values().forEach(f -> f.cancel(true));
        this.readyQueue.clear();
        this.readySet.clear();

        try {
            this.executionListener.onFlowTimedOut(this.flowTimeoutMillis, msg);
        } catch (Exception e) {
            LOG.error(">> ERROR: onFlowTimedOut listener threw exception", e);
        }
    }

    public long getFlowTimeoutMillis() {
        return this.flowTimeoutMillis;
    }

    public boolean isTimedOut() {
        return this.flowTimedOut.get() || this.executionState == ExecutionState.TIMED_OUT;
    }

    private void cancelAllRunningTasks(ExecutionState terminalState) {
        synchronized (this.stateLock) {
            if (this.executionState == ExecutionState.RUNNING || this.executionState == ExecutionState.PAUSED) {
                this.executionState = terminalState;
            }
        }
        this.runningFutures.values().forEach(f -> f.cancel(true));
        this.readyQueue.clear();
        this.readySet.clear();
    }

    public void cancel() {
        synchronized (this.stateLock) {
            if (this.executionState == ExecutionState.RUNNING || this.executionState == ExecutionState.PAUSED) {
                this.executionState = ExecutionState.CANCELLED;
            }
        }
        this.runningFutures.values().forEach(f -> f.cancel(true));
        this.readyQueue.clear();
        this.readySet.clear();
    }

    public ExecutionState getExecutionState() {
        return this.executionState;
    }

    public String getPausedNodeId() {
        return this.pausedNodeId;
    }

    private void finalizeExecution(FlowExecutionResult result) {
        synchronized (this.stateLock) {
            if (this.executionState == ExecutionState.TIMED_OUT || this.flowTimedOut.get()) {
                this.executionState = ExecutionState.TIMED_OUT;
                result.setSuccess(false);
                result.setTimedOut(true);
                result.setTimeoutMessage(String.format("Flow timed out after %d ms", this.flowTimeoutMillis));
                // 将仍未完成的节点记入失败原因，便于排查
                for (String id : this.runNodes.keySet()) {
                    if (!this.completedNodes.contains(id)) {
                        result.addFailedNode(id, "cancelled by flow timeout");
                        TaskNode n = this.runNodes.get(id);
                        if (n != null && (n.getTaskState() == TaskState.RUNNING || n.getTaskState() == TaskState.PENDING)) {
                            n.setTaskState(TaskState.CANCELLED);
                        }
                    }
                }

            } else if (this.executionState == ExecutionState.RUNNING) {
                if (this.completedTasksNum.get() >= this.runNodes.size()) {
                    this.executionState = ExecutionState.COMPLETED;
                    result.setSuccess(this.failedTasks.isEmpty());
                } else {
                    this.executionState = ExecutionState.FAILED;
                    result.setSuccess(false);
                }

            } else if (this.executionState == ExecutionState.PAUSED) {
                result.setSuccess(false);
            } else if (this.executionState == ExecutionState.CANCELLED) {
                result.setSuccess(false);
            } else {
                result.setSuccess(false);
            }
        }

        result.setEndTime(Instant.now());
        result.setSucceedNodes(this.completedNodes);
        for (NodeExecutionResult f : this.failedTasks) {
            result.addFailedNode(f.getNodeId(), f.getErrorMessage());
        }
    }

    private void notifyNodeCompletion(NodeExecutionResult result) {
        try {
            this.executionListener.onNodeCompleted(result);
        } catch (Exception e) {
            LOG.error(">> ERROR: Node completion listener threw exception for node <{}>", result.getNodeId(), e);
        }
    }

    private void notifyFlowCompletion(FlowExecutionResult result) {
        try {
            this.executionListener.onFlowCompleted(result);
        } catch (Exception e) {
            LOG.error(">> ERROR: Flow completion listener threw exception", e);
        }
    }

    @Override
    public void close() throws Exception {
        try {
            cancel();
        } catch (Exception ignored) {}

        cancelWatchdog();
        shutdown(this.managedExecutor);
        shutdown(this.retryExecutorService);
        shutdown(this.watchdogExecutor);
    }

    private void shutdown(ExecutorService es) {
        if (es == null) {
            return;
        }

        try {
            es.shutdown();
            if (!es.awaitTermination(SHUTDOWN_AWAIT_SECONDS, TimeUnit.SECONDS)) {
                es.shutdownNow();
            }
        } catch (InterruptedException e) {
            es.shutdownNow();
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {}
    }

    private static class DefaultExecutionListener implements ExecutionListener {
        @Override public void onFlowStart() {}
        @Override public void onNodeCompleted(NodeExecutionResult res) {}
        @Override public void onFlowCompleted(FlowExecutionResult res) {}
    }
}
