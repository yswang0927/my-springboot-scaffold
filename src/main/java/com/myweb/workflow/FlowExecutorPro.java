package com.myweb.workflow;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.myweb.workflow.exception.FlowExecuteException;
import com.myweb.workflow.graph.GNode;
import com.myweb.workflow.graph.GNodeInput;
import com.myweb.workflow.graph.Graph;
import com.myweb.workflow.nodes.StartNode;

/**
 * DAG流程执行器
 * <p>
 * 修改说明：
 * 1. 集成 TaskTriggerRule，从"计数器驱动"转变为"状态规则驱动"。
 * 2. 支持分支、条件执行、Eager Execution (如 ONE_SUCCESS)。
 */
public class FlowExecutorPro implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(FlowExecutorPro.class);

    private static final int TASK_POLL_TIMEOUT_SECONDS = 2; // 缩短轮询时间以便更快响应状态变化
    private static final int SHUTDOWN_AWAIT_SECONDS = 10;

    // 节点信息
    private final ConcurrentMap<String, TaskNode> runNodes = new ConcurrentHashMap<>();
    // 计算每个节点的动态入度，用于判断是否"所有上游都已完结"
    private final ConcurrentMap<String, AtomicInteger> currentInDegree = new ConcurrentHashMap<>();
    // 就绪队列
    private final Queue<String> readyQueue = new ConcurrentLinkedQueue<>();
    // 用于重试
    private final ConcurrentMap<String, AtomicInteger> retryCounts = new ConcurrentHashMap<>();

    // 存储处于执行中的节点信息
    private final ConcurrentMap<String, Future<NodeExecuteResult>> runningFutures = new ConcurrentHashMap<>();
    // Future到NodeId的映射
    private final ConcurrentMap<Future<NodeExecuteResult>, String> future2NodeIdMap = new ConcurrentHashMap<>();

    private final AtomicInteger completedTasksNum = new AtomicInteger(0);
    private final AtomicInteger scheduledRetryTasksNum = new AtomicInteger(0);
    // 记录明确失败的任务（非跳过）
    private final Set<NodeExecuteResult> failedTasks = ConcurrentHashMap.newKeySet();

    // 执行完成的节点 (包括 Success, Failed, Skipped, UpstreamFailed)
    private final Set<String> completedNodes = ConcurrentHashMap.newKeySet();

    private final Object stateLock = new Object();
    private volatile ExecutionState executionState = ExecutionState.READY;

    private final ExecutorCompletionService<NodeExecuteResult> executorService;
    private final ScheduledExecutorService retryExecutorService;
    private final ExecutionListener executionListener;
    private ExecutorService threadPoolExecutor;

    private final Graph dagGraph;

    public enum ExecutionState {
        READY, RUNNING, CANCELLED, COMPLETED, FAILED
    }

    public FlowExecutorPro(Graph flowGraph, ExecutionListener listener) {
        this(flowGraph, listener, null);
    }

    public FlowExecutorPro(Graph flowGraph, ExecutionListener listener, ExecutorService executor) {
        if (flowGraph == null) {
            throw new FlowExecuteException("`Graph` must not be null");
        }

        this.dagGraph = flowGraph;
        this.dagGraph.initialize(); // 确保图已初始化

        if (executor == null) {
            final int maxParallel = this.dagGraph.getMaxParallelism();
            final int cpuCores = Runtime.getRuntime().availableProcessors();
            final int corePoolSize = Math.max(1, Math.min(maxParallel / 2, cpuCores / 2));
            final int maxPoolSize = Math.min(maxParallel, cpuCores);
            this.threadPoolExecutor = new ThreadPoolExecutor(
                    corePoolSize,
                    maxPoolSize,
                    60L,
                    TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(maxParallel * 2),
                    new ThreadFactory() {
                        private final AtomicLong tn = new AtomicLong(0);
                        @Override
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "flow-executor-" + tn.incrementAndGet());
                            t.setUncaughtExceptionHandler((thread, e) ->
                                    LOG.error(">> ERROR: Flow-Executor thread<{}> exception: ", thread.getName(), e));
                            return t;
                        }
                    },
                    new ThreadPoolExecutor.CallerRunsPolicy()
            );
        }

        this.initializeNodes();

        this.executionListener = listener != null ? listener : new DefaultExecutionListener();
        this.executorService = new ExecutorCompletionService<>(executor != null ? executor : this.threadPoolExecutor);
        this.retryExecutorService = Executors.newSingleThreadScheduledExecutor();
    }

    private void initializeNodes() {
        List<GNode> nodes = this.dagGraph.getNodes();
        for (GNode node : nodes) {
            this.runNodes.put(node.getId(), TaskNodeFactory.createNode(node));
            this.retryCounts.put(node.getId(), new AtomicInteger(0));
        }
        this.dagGraph.getNodesInDegree().forEach((nodeId, degree) ->
                this.currentInDegree.put(nodeId, new AtomicInteger(degree)));
    }

    private void resetExecutionState() {
        // 重置入度
        this.dagGraph.getNodesInDegree().forEach((nodeId, degree) -> this.currentInDegree.get(nodeId).set(degree));
        this.runNodes.values().forEach(n -> n.setTaskState(TaskState.PENDING));
        this.retryCounts.values().forEach(counter -> counter.set(0));
        this.readyQueue.clear();
        this.runningFutures.clear();
        this.future2NodeIdMap.clear();
        this.completedTasksNum.set(0);
        this.failedTasks.clear();
        this.completedNodes.clear();
    }

    private void initializeReadyQueue() {
        // 初始入度为0的节点，或者触发规则为 ALWAYS 的节点
        this.runNodes.values().forEach(node -> {
            int degree = this.currentInDegree.get(node.getId()).get();
            if (degree == 0 || node.getTriggerRule() == TaskTriggerRule.ALWAYS) {
                // 避免重复添加，execute loop 中有 PENDING 检查
                this.readyQueue.offer(node.getId());
            }
        });
    }

    /**
     * 执行workflow
     *
     * @param context 执行上下文
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
            LOG.error("Start listener error", e);
        }

        FlowExecuteResult flowExecuteResult = new FlowExecuteResult();
        flowExecuteResult.setStartTime(Instant.now());

        // 重置状态
        resetExecutionState();

        synchronized (this.stateLock) {
            if (this.executionState != ExecutionState.READY) {
                throw new FlowExecuteException("Executor state invalid: " + this.executionState);
            }
            this.executionState = ExecutionState.RUNNING;
        }

        // 1. 初始化就绪队列
        initializeReadyQueue();
        // 2. 提交第一批任务
        submitReadyTasks(context);

        try {
            while ((this.completedTasksNum.get()) < this.runNodes.size()
                    && this.executionState == ExecutionState.RUNNING) {

                Future<NodeExecuteResult> completedFuture = this.executorService.poll(TASK_POLL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (completedFuture == null) {
                    // 补交就绪任务
                    if (this.runningFutures.isEmpty() && this.readyQueue.size() > 0) {
                        submitReadyTasks(context);
                        continue;
                    }
                    // 所有任务都结束(包括重试队列)，退出循环
                    if (this.runningFutures.isEmpty() && this.readyQueue.isEmpty() && this.scheduledRetryTasksNum.get() == 0) {
                        break;
                    }
                    continue;
                }

                String finishedNodeId = this.future2NodeIdMap.remove(completedFuture);
                if (finishedNodeId == null) {
                    continue;
                }
                this.runningFutures.remove(finishedNodeId);

                try {
                    NodeExecuteResult taskResult = completedFuture.get();
                    handleTaskCompletion(finishedNodeId, taskResult, context);
                } catch (CancellationException e) {
                    handleCancellation(finishedNodeId, context);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    cancelAllRunningTasks();
                    break;
                } catch (Exception e) {
                    // 系统级异常，视为失败
                    handleTaskFailure(NodeExecuteResult.failed(e).setNodeId(finishedNodeId), context);
                }
            } // end while

            finalizeExecution(flowExecuteResult);

        } catch (Exception e) {
            LOG.error("Execution Loop Error", e);
            cancelAllRunningTasks();
            flowExecuteResult.setSuccess(false);
        } finally {
            notifyFlowCompletion(flowExecuteResult);
        }
    }

    /**
     * 处理任务正常完成（包括业务上的成功或失败）
     */
    private void handleTaskCompletion(String nodeId, NodeExecuteResult result, ExecutionContext context) {
        TaskNode node = this.runNodes.get(nodeId);
        // 更新状态
        TaskState finalState = result.isSuccess() ? TaskState.SUCCESS : TaskState.FAILED;
        node.setTaskState(finalState);

        if (result.isSuccess()) {
            this.completedNodes.add(nodeId);
            this.completedTasksNum.incrementAndGet();
            // 在上下文中记录节点输出
            context.addNodeExecuteResult(nodeId, result);
            notifyNodeCompletion(result);

            // 成功后，评估下游节点
            evaluateAndTriggerDownstream(nodeId, context, result.getNextNodesToActivate());
        } else {
            // 失败处理 (内部包含重试逻辑)
            handleTaskFailure(result, context);
        }
    }

    /**
     * 处理任务失败（含重试逻辑）
     */
    private void handleTaskFailure(NodeExecuteResult failedResult, ExecutionContext context) {
        if (this.failedTasks.contains(failedResult)) {
            return;
        }

        final String failedNodeId = failedResult.getNodeId();
        TaskNode failedNode = this.runNodes.get(failedNodeId);
        // 检查重试
        int maxRetries = failedNode.getMaxRetries();
        if (this.retryCounts.get(failedNodeId).incrementAndGet() <= maxRetries) {
            LOG.warn(">> WARNING: Retry task <{}>", failedNodeId);
            this.scheduledRetryTasksNum.incrementAndGet();
            this.retryExecutorService.schedule(() -> {
                this.scheduledRetryTasksNum.decrementAndGet();
                submitTask(failedNodeId, context);
            }, failedNode.getRetryDelayMillis(), TimeUnit.MILLISECONDS);
            return; // 正在重试，暂不视为完结
        }

        // 最终失败
        LOG.error(">> ERROR: Task <{}> failed after retries.", failedNodeId);
        failedNode.setTaskState(TaskState.FAILED);
        this.failedTasks.add(failedResult);
        this.completedTasksNum.incrementAndGet(); // 计数完成
        context.addNodeExecuteResult(failedNodeId, failedResult);
        notifyNodeCompletion(failedResult);

        // 即使失败，也需要评估下游（因为可能有 rules 如 ALL_DONE, ALL_FAILED 等需要运行）
        evaluateAndTriggerDownstream(failedNodeId, context, null);
    }

    private void handleCancellation(String nodeId, ExecutionContext context) {
        LOG.warn(">> Task <{}> Cancelled.", nodeId);
        NodeExecuteResult res = NodeExecuteResult.failed("Task Cancelled").setNodeId(nodeId);
        this.runNodes.get(nodeId).setTaskState(TaskState.CANCELLED);
        this.failedTasks.add(res);
        this.completedTasksNum.incrementAndGet();
        context.addNodeExecuteResult(nodeId, res);
        notifyNodeCompletion(res);
        // 取消通常意味着流程终止，或者可以视为 FAILED 触发下游
        evaluateAndTriggerDownstream(nodeId, context, null);
    }

    /**
     * 核心逻辑：评估下游节点是否可以触发
     * 替代了原有的 "入度减1即触发" 逻辑
     *
     * @param finishedNodeId 刚刚结束的节点ID
     * @param context 上下文
     * @param activatedBranch (可选) 如果上游是分支节点，这里指定了允许激活的下游分支集合
     */
    private void evaluateAndTriggerDownstream(String finishedNodeId, ExecutionContext context, Collection<String> activatedBranch) {
        Collection<String> downstreamNodes = this.dagGraph.getDownstreamNodes(finishedNodeId);
        if (downstreamNodes == null || downstreamNodes.isEmpty()) {
            return;
        }

        for (String dependentId : downstreamNodes) {
            TaskNode dependentNode = this.runNodes.get(dependentId);

            // 1. 快速检查：如果节点已经运行或完成，跳过
            // 使用 double-check 或是依赖 synchronized 保证状态一致性
            // 这里为了性能，先读 volatile state
            if (dependentNode.getTaskState() != TaskState.PENDING) {
                continue;
            }

            // 2. 分支逻辑检查
            // 如果上游指定了分支，且当前节点不在分支中，则当前节点被“逻辑排除”
            // 注意：Airflow中未被选择的分支会置为 SKIPPED。
            // 只有当 finishedNode 是成功状态且显式指定了 nextNodes 时才应用分支逻辑
            final boolean isBranchSkipped = (activatedBranch != null && !activatedBranch.isEmpty() && !activatedBranch.contains(dependentId));

            // 3. 更新入度 (表示有多少上游已经表态)
            final int remainingDependencies = this.currentInDegree.get(dependentId).decrementAndGet();

            if (isBranchSkipped) {
                // 如果被分支排除，直接视为 SKIPPED 并不再评估规则
                markNodeAsSkipped(dependentId, "Not selected by branch node " + finishedNodeId, context);
                continue;
            }

            // 4. 获取所有上游状态
            Collection<String> upstreamNodeIds = this.dagGraph.getUpstreamNodes(dependentId);
            List<TaskState> upstreamStates = new ArrayList<>(upstreamNodeIds.size());
            for (String unid : upstreamNodeIds) {
                upstreamStates.add(this.runNodes.get(unid).getTaskState());
            }

            // 5. 评估触发规则
            TaskTriggerRule rule = dependentNode.getTriggerRule();
            if (rule == null) {
                rule = TaskTriggerRule.ALL_SUCCESS;
            }
            final boolean shouldRun = rule.evaluate(upstreamStates);
            if (shouldRun) {
                // 满足规则，加入就绪队列
                // 注意：这里需要防止并发重复添加。submitReadyTasks 会再次检查 PENDING
                // 但为了保险，可以加锁或使用 CAS。此处简化依赖 submitTask 的状态检查。
                if (!this.readyQueue.contains(dependentId)) {
                    this.readyQueue.offer(dependentId);
                }
            } else {
                // 6. 如果规则不满足，且所有上游都已完结，则必须给出一个最终状态 (SKIPPED 或 UPSTREAM_FAILED)
                if (remainingDependencies <= 0) {
                    handleRuleMismatch(dependentId, rule, context);
                }
            }
        }

        // 尝试提交新产生的就绪任务
        submitReadyTasks(context);
    }

    /**
     * 当所有上游都已结束，但触发规则仍不满足时的处理逻辑
     */
    private void handleRuleMismatch(String nodeId, TaskTriggerRule rule, ExecutionContext context) {
        TaskNode node = this.runNodes.get(nodeId);
        synchronized (node) {
            if (node.getTaskState() != TaskState.PENDING) {
                return;
            }

            String reason;
            TaskState finalState;

            // 简单的映射逻辑，可根据需要扩展
            if (rule == TaskTriggerRule.ALL_SUCCESS) {
                finalState = TaskState.UPSTREAM_FAILED;
                reason = "Upstream failed or skipped.";
            } else if (rule == TaskTriggerRule.ALL_FAILED) {
                finalState = TaskState.SKIPPED;
                reason = "Some upstreams succeeded.";
            } else {
                finalState = TaskState.UPSTREAM_FAILED;
                reason = "Trigger rule " + rule + " not satisfied after all upstreams finished.";
            }

            node.setTaskState(finalState);

            NodeExecuteResult result = NodeExecuteResult.failed(reason)
                    .setNodeId(nodeId)
                    .setSkipped(finalState == TaskState.SKIPPED); // UPSTREAM_FAILED 也可以视作 Skipped 的一种变体，视业务定义

            context.addNodeExecuteResult(nodeId, result);
            this.completedTasksNum.incrementAndGet();
            this.completedNodes.add(nodeId);
            notifyNodeCompletion(result);

            // 递归：这个节点现在的状态变了，需要通知它的下游
            evaluateAndTriggerDownstream(nodeId, context, null);
        }
    }

    private void markNodeAsSkipped(String nodeId, String reason, ExecutionContext context) {
        TaskNode node = this.runNodes.get(nodeId);
        synchronized (node) {
            if (node.getTaskState() != TaskState.PENDING) {
                return;
            }

            node.setTaskState(TaskState.SKIPPED);
            NodeExecuteResult result = NodeExecuteResult.failed(reason).setNodeId(nodeId).setSkipped(true);

            context.addNodeExecuteResult(nodeId, result);
            this.completedTasksNum.incrementAndGet();
            this.completedNodes.add(nodeId);
            notifyNodeCompletion(result);

            evaluateAndTriggerDownstream(nodeId, context, null);
        }
    }

    private void submitReadyTasks(ExecutionContext context) {
        while (!this.readyQueue.isEmpty() && this.executionState == ExecutionState.RUNNING) {
            String nodeId = this.readyQueue.poll();
            if (nodeId != null) {
                submitTask(nodeId, context);
            }
        }
    }

    private void submitTask(final String nodeId, final ExecutionContext context) {
        final TaskNode runNode = this.runNodes.get(nodeId);

        // 关键：CAS 或 同步块防止重复提交
        // Eager Execution (如 ONE_SUCCESS) 可能导致多次触发 submitTask
        synchronized (runNode) {
            if (runNode.getTaskState() != TaskState.PENDING) {
                return;
            }
            runNode.setTaskState(TaskState.RUNNING);
        }

        Future<NodeExecuteResult> future = this.executorService.submit(() -> {
            Instant startTime = Instant.now();

            if (Thread.currentThread().isInterrupted()) {
                return NodeExecuteResult.failed("Interrupted", new InterruptedException()).setNodeId(nodeId);
            }

            // 数据准备：从上游获取数据 (Inputs)
            // 注意：对于 ONE_SUCCESS 等规则，部分上游可能还没跑完，getNodeExecuteResult 可能为空
            Collection<GNodeInput> upstreamInputs = dagGraph.getUpstreamNodeInputs(nodeId);
            NodeInputs inputs = new NodeInputs();
            for (GNodeInput inputConf : upstreamInputs) {
                Optional<NodeExecuteResult> upResultOpt = context.getNodeExecuteResult(inputConf.getSourceNodeId());
                if (upResultOpt.isPresent()) {
                    NodeExecuteResult upRes = upResultOpt.get();
                    if (upRes.isSuccess() && !upRes.isSkipped()) {
                        // 上游节点指定端口的输出写入目标节点的指定输入端口，
                        // 这样节点中执行时就可以获取到自己输入端口上的数据了
                        NodeOutput out = upRes.getNodeOutput(inputConf.getSourcePort());
                        if (out != null) {
                            inputs.addInput(inputConf.getTargetPort(), out);
                        }
                    }
                }
                // 如果上游没跑完，这里就拿不到数据。这对于 ONE_SUCCESS 是正常的。
                // 节点内部逻辑需要处理 input 可能缺失的情况。
            }

            // 开始节点没有输入，使用流程输入作为输入
            if (runNode instanceof StartNode) {
                inputs.addInput(StartNode.DEFAULT_INPUT_PORT_NAME, new NodeOutput(context.getWorkflowInput()));
            }

            try {
                NodeExecuteResult result = runNode.call(context, inputs);
                if (result == null) {
                    result = NodeExecuteResult.success();
                }
                result.setNodeId(nodeId).setStartTime(startTime).setEndTime(Instant.now());
                return result;
            } catch (Exception e) {
                return NodeExecuteResult.failed(e).setNodeId(nodeId).setStartTime(startTime).setEndTime(Instant.now());
            }
        });

        this.runningFutures.put(nodeId, future);
        this.future2NodeIdMap.put(future, nodeId);
    }

    private void cancelAllRunningTasks() {
        synchronized (this.stateLock) {
            if (this.executionState == ExecutionState.RUNNING) {
                this.executionState = ExecutionState.FAILED;
            }
        }
        this.runningFutures.values().forEach(f -> f.cancel(true));
        this.readyQueue.clear();
    }

    public void cancel() {
        synchronized (this.stateLock) {
            if (this.executionState == ExecutionState.RUNNING) {
                this.executionState = ExecutionState.CANCELLED;
            }
        }
        cancelAllRunningTasks();
    }

    private void finalizeExecution(FlowExecuteResult result) {
        synchronized (this.stateLock) {
            if (this.executionState == ExecutionState.RUNNING) {
                if (this.completedTasksNum.get() >= this.runNodes.size()) {
                    this.executionState = ExecutionState.COMPLETED;
                    result.setSuccess(this.failedTasks.isEmpty());
                } else {
                    this.executionState = ExecutionState.FAILED;
                    result.setSuccess(false);
                }
            } else {
                result.setSuccess(false);
            }
        }

        result.setEndTime(Instant.now());
        result.setSucceedNodes(this.completedNodes);
        for (NodeExecuteResult f : this.failedTasks) {
            result.addFailedNode(f.getNodeId(), f.getErrorMessage());
        }
    }

    private void notifyNodeCompletion(NodeExecuteResult result) {
        try {
            this.executionListener.onNodeCompleted(result);
        } catch (Exception e) {
            LOG.error("Listener error", e);
        }
    }

    private void notifyFlowCompletion(FlowExecuteResult result) {
        try {
            this.executionListener.onFlowCompleted(result);
        } catch (Exception e) {
            LOG.error("Listener error", e);
        }
    }

    @Override
    public void close() throws Exception {
        cancel();
        shutdown(this.threadPoolExecutor);
        shutdown(this.retryExecutorService);
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
        }
    }

    // Default Listener Implementation
    private static class DefaultExecutionListener implements ExecutionListener {
        @Override public void onFlowStart() {}
        @Override public void onNodeCompleted(NodeExecuteResult res) {}
        @Override public void onFlowCompleted(FlowExecuteResult res) {}
    }
}
