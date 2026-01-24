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
 */
public class FlowExecutor implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(FlowExecutor.class);

    private static final int TASK_POLL_TIMEOUT_SECONDS = 10;
    private static final int SHUTDOWN_AWAIT_SECONDS = 10;

    // 节点信息
    private final ConcurrentMap<String, TaskNode> runNodes = new ConcurrentHashMap<>();
    // 计算每个节点的动态入度, 并用于运行过程中的更新
    private final ConcurrentMap<String, AtomicInteger> currentInDegree = new ConcurrentHashMap<>();
    // 就绪队列
    private final Queue<String> readyQueue = new ConcurrentLinkedQueue<>();
    // 用于重试
    private final ConcurrentMap<String, AtomicInteger> retryCounts = new ConcurrentHashMap<>();

    // 存储处于执行中的节点信息
    private final ConcurrentMap<String, Future<NodeExecutionResult>> runningFutures = new ConcurrentHashMap<>();
    // Future到NodeId的映射, 用于快速找到 nodeId
    private final ConcurrentMap<Future<NodeExecutionResult>, String> future2NodeIdMap = new ConcurrentHashMap<>();

    private final AtomicInteger completedTasksNum = new AtomicInteger(0);
    // 已排程但尚未触发的重试
    private final AtomicInteger scheduledRetryTasksNum = new AtomicInteger(0);
    private final Set<NodeExecutionResult> failedTasks = ConcurrentHashMap.newKeySet();

    // 执行完成的节点
    private final Set<String> completedNodes = ConcurrentHashMap.newKeySet();
    // 记录跳过的节点, 用于类似 IF-ELSE 条件节点, 如果一个节点被跳过了, 它后续节点也要跳过(传递性)
    private final Set<String> skippedNodes = ConcurrentHashMap.newKeySet();

    private final Object stateLock = new Object();
    private volatile ExecutionState executionState = ExecutionState.READY;

    private final ExecutorCompletionService<NodeExecutionResult> executorService;
    private final ScheduledExecutorService retryExecutorService;
    private final ExecutionListener executionListener;
    private ExecutorService threadPoolExecutor;

    private final Graph dagGraph;

    /**
     * 执行状态
     */
    public enum ExecutionState {
        READY, RUNNING, CANCELLED, COMPLETED, FAILED
    }

    public FlowExecutor(Graph flowGraph, ExecutionListener listener) {
        this(flowGraph, listener, null);
    }

    public FlowExecutor(Graph flowGraph, ExecutionListener listener, ExecutorService executor) {
        if (flowGraph == null) {
            throw new FlowExecuteException("`Graph` must not be null");
        }

        this.dagGraph = flowGraph;
        this.dagGraph.initialize();

        if (executor == null) {
            // 计算最大并行度来创建自定义线程池
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
                            Thread t = new Thread(r, "flow-executor-"+ tn.incrementAndGet());
                            t.setDaemon(false);
                            t.setUncaughtExceptionHandler((thread, e) -> {
                                LOG.error(">> ERROR: Flow-Executor thread<{}> 出现未捕获到的异常: ", thread.getName(), e);
                            });
                            return t;
                        }
                    },
                    new ThreadPoolExecutor.CallerRunsPolicy()
            );
        }

        this.initialize();

        this.executionListener = listener != null ? listener : new DefaultExecutionListener();
        this.executorService = new ExecutorCompletionService<NodeExecutionResult>(executor != null ? executor : this.threadPoolExecutor);
        this.retryExecutorService = Executors.newSingleThreadScheduledExecutor();
    }

    private void initialize() {
        List<GNode> nodes = this.dagGraph.getNodes();
        for (GNode node : nodes) {
            this.runNodes.put(node.getId(), TaskNodeFactory.createNode(node));
            // 预设
            this.retryCounts.put(node.getId(), new AtomicInteger(0));
        }

        this.dagGraph.getNodesInDegree().forEach((nodeId, degree) -> this.currentInDegree.put(nodeId, new AtomicInteger(degree)));
    }

    private void resetExecutionState() {
        // 重置入度
        this.dagGraph.getNodesInDegree().forEach((nodeId, degree) -> this.currentInDegree.get(nodeId).set(degree));
        // 重置重试计数
        this.retryCounts.values().forEach(counter -> counter.set(0));
        this.readyQueue.clear();
        this.runningFutures.clear();
        this.future2NodeIdMap.clear();
        this.completedTasksNum.set(0);
        this.failedTasks.clear();
        this.completedNodes.clear();
        this.skippedNodes.clear();
    }

    /**
     * 初始化就绪队列
     */
    private void initializeReadyQueue() {
        this.currentInDegree.forEach((nodeId, degree) -> {
            if (degree.get() == 0) {
                this.readyQueue.offer(nodeId);
            }
        });
    }

    /**
     * 执行workflow
     * @param context 执行上下文
     */
    public void execute(ExecutionContext context) {
        if (this.runNodes.isEmpty()) {
            LOG.warn(">> WARNING: 没有节点需要执行");
            return;
        }

        if (context == null) {
            context = new ExecutionContext();
        }

        try {
            this.executionListener.onFlowStart();
        } catch (Exception e) {
            LOG.error(">> ERROR: 回调 `executionListener.onFlowStart()` 时发生异常: ", e);
        }

        FlowExecutionResult flowExecutionResult = new FlowExecutionResult();
        flowExecutionResult.setStartTime(Instant.now());

        // 重置状态
        resetExecutionState();

        synchronized (this.stateLock) {
            if (this.executionState != ExecutionState.READY) {
                throw new FlowExecuteException("执行器状态不正确: " + this.executionState);
            }
            this.executionState = ExecutionState.RUNNING;
        }

        // 1. 初始化就绪队列
        initializeReadyQueue();

        // 2. 提交第一批任务
        submitReadyTasks(context);

        try {
            // 3. 主循环：等待任务完成，并触发新任务
            while ((this.completedTasksNum.get() + failedTasks.size()) < this.runNodes.size()
                    && this.executionState == ExecutionState.RUNNING) {

                // 获取执行完成的任务节点(不论成功或失败)
                Future<NodeExecutionResult> completedFuture = this.executorService.poll(TASK_POLL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (completedFuture == null) {
                    // 补交就绪任务
                    if (this.runningFutures.isEmpty() && this.readyQueue.size() > 0) {
                        submitReadyTasks(context);
                        continue;
                    }
                    if (this.runningFutures.isEmpty() && this.readyQueue.isEmpty() && this.scheduledRetryTasksNum.get() == 0){
                        // 真正无事可做 → 退出
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
                    NodeExecutionResult taskResult = completedFuture.get();
                    // 更新节点状态
                    this.runNodes.get(finishedNodeId).setTaskState(taskResult.isSuccess() ? TaskState.SUCCESS : TaskState.FAILED);

                    if (taskResult.isSuccess()) {
                        this.completedNodes.add(finishedNodeId);
                        this.completedTasksNum.incrementAndGet();
                        // 在上下文中记录节点输出
                        context.addNodeExecutionResult(finishedNodeId, taskResult);

                        // 触发后续任务
                        Collection<String> allDependents = this.dagGraph.getDownstreamNodes(finishedNodeId);
                        // 如果此节点存在分支情况
                        Collection<String> nodesToActivate = taskResult.getNextNodesToActivate();
                        // 如果节点未指定激活路径，则默认激活所有下游
                        if (nodesToActivate == null || nodesToActivate.isEmpty()) {
                            nodesToActivate = allDependents;
                        }

                        // 处理下游依赖
                        for (String dependentId : allDependents) {
                            if (nodesToActivate.contains(dependentId)) {
                                // 路径被激活：正常处理入度，如果为0则加入就绪队列
                                if (this.currentInDegree.get(dependentId).decrementAndGet() == 0) {
                                    if (this.skippedNodes.contains(dependentId)) {
                                        // 虽然被激活了，但因为之前有其他父节点跳过它，导致它已被标记。
                                        // 现在所有父节点都齐了(入度0)，它正式成为"完成的跳过节点"。
                                        this.completedTasksNum.incrementAndGet();
                                        // 触发下游跳过
                                        Collection<String> childDependents = this.dagGraph.getDownstreamNodes(dependentId);
                                        if (childDependents != null && !childDependents.isEmpty()) {
                                            for(String child : childDependents) {
                                                propagateSkipNode(context, child);
                                            }
                                        }
                                    } else {
                                        // 正常入队
                                        this.readyQueue.offer(dependentId);
                                    }
                                }
                            } else {
                                // 路径被跳过：启动“跳过”传播
                                propagateSkipNode(context, dependentId);
                            }
                        }

                        // 提交新加入就绪队列的任务
                        submitReadyTasks(context);

                        try {
                            this.executionListener.onNodeCompleted(taskResult);
                        } catch (Exception e) {
                            LOG.error(">> ERROR: 回调 `executionListener.onNodeCompleted()` 时发生异常: ", e);
                        }

                    } else {
                        // 容错与重试
                        handleTaskFailure(taskResult, context);
                    }

                } catch (CancellationException e) {
                    LOG.warn(">> WARNING: 任务 <{}> 被取消了.", finishedNodeId);
                    NodeExecutionResult failedResult = NodeExecutionResult.failed(e).setNodeId(finishedNodeId).setErrorMessage("节点被取消执行");
                    this.runNodes.get(finishedNodeId).setTaskState(TaskState.CANCELLED);
                    context.addNodeExecutionResult(finishedNodeId, failedResult);

                    if (this.failedTasks.add(failedResult)) {
                        try {
                            this.executionListener.onNodeCompleted(failedResult);
                        } catch (Exception e2) {
                            LOG.error(">> ERROR: 回调 `executionListener.onNodeCompleted()` 时发生异常: ", e2);
                        }
                        // 如果这个节点被取消执行, 则跳过其下游依赖节点
                        skipDependents(context, finishedNodeId);
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    cancelAllRunningTasks();
                    break;
                } catch (Exception e) {
                    // 容错与重试
                    handleTaskFailure(NodeExecutionResult.failed(e).setNodeId(finishedNodeId), context);
                }

            } // end-while

            // 任务流执行完毕
            synchronized (this.stateLock) {
                if (this.executionState == ExecutionState.RUNNING) {
                    if (this.completedTasksNum.get() == this.runNodes.size()) {
                        this.executionState = ExecutionState.COMPLETED;
                        flowExecutionResult.setSuccess(true);
                    } else {
                        this.executionState = ExecutionState.FAILED;
                    }
                }

                // 确保清理剩余任务
                if (!this.runningFutures.isEmpty()) {
                    cancelAllRunningTasks();
                }
            }

        } catch (InterruptedException e) {
            cancelAllRunningTasks();
            Thread.currentThread().interrupt(); // 恢复中断状态
        } catch (Exception e) {
            cancelAllRunningTasks();
        } finally {
            flowExecutionResult.setSucceedNodes(completedNodes);
            flowExecutionResult.setEndTime(Instant.now());

            for (NodeExecutionResult failedTask : failedTasks) {
                flowExecutionResult.addFailedNode(failedTask.getNodeId(), failedTask.getErrorMessage());
            }

            try {
                this.executionListener.onFlowCompleted(flowExecutionResult);
            } catch (Exception e) {
                LOG.error(">> ERROR: 回调 `executionListener.onFlowCompleted()` 时发生异常: ", e);
            }
        }
    }

    /**
     * 取消执行
     */
    public void cancel() {
        synchronized (this.stateLock) {
            if (this.executionState == ExecutionState.RUNNING) {
                this.executionState = ExecutionState.CANCELLED;
            }
        }
        cancelAllRunningTasks();
    }

    /**
     * 提交所有就绪队列中的任务
     */
    private void submitReadyTasks(ExecutionContext context) {
        while (!this.readyQueue.isEmpty() && this.executionState == ExecutionState.RUNNING) {
            String nodeId = this.readyQueue.poll();
            if (nodeId != null && !this.skippedNodes.contains(nodeId)) {
                submitTask(nodeId, context);
            }
        }
    }

    /**
     * 提交单个任务
     */
    private void submitTask(final String nodeId, final ExecutionContext context) {
        // 检查是否已被取消
        if (this.executionState != ExecutionState.RUNNING || this.skippedNodes.contains(nodeId)) {
            return;
        }

        final TaskNode runNode = this.runNodes.get(nodeId);
        if (runNode == null) {
            return;
        }

        // 提交一个任务，并返回一个 Future ，任务完成后会自动把 Future 放入内部队列
        Future<NodeExecutionResult> future = this.executorService.submit(() -> {
            Instant startTime = Instant.now();
            runNode.setTaskState(TaskState.RUNNING);

            if (Thread.currentThread().isInterrupted()) {
                return NodeExecutionResult.failed("任务线程被中断", new InterruptedException("Task interrupted"))
                        .setNodeId(nodeId)
                        .setStartTime(startTime)
                        .setEndTime(Instant.now());
            }

            // 当执行此节点时，先自动从其上游节点获取输入
            Collection<GNodeInput> upstreamNodeInputs = dagGraph.getUpstreamNodeInputs(nodeId);
            NodeInputs inputs = new NodeInputs();
            for (GNodeInput gNodeInput : upstreamNodeInputs) {
                // 上游节点的输出端口->目标节点的输入端口信息
                final String sourceNodeId = gNodeInput.getSourceNodeId();
                final String sourcePort = gNodeInput.getSourcePort();
                final String targetPort = gNodeInput.getTargetPort();
                Optional<NodeExecutionResult> sourceNodeExecuteResult = context.getNodeExecutionResult(sourceNodeId);
                if (sourceNodeExecuteResult.isPresent()) {
                    NodeExecutionResult sourceResult = sourceNodeExecuteResult.get();
                    if (sourceResult != null && sourceResult.isSuccess() && !sourceResult.isSkipped()) {
                        // 上游节点指定端口的输出写入目标节点的指定输入端口，
                        // 这样节点中执行时就可以获取到自己输入端口上的数据了
                        NodeOutput nodeOutput = sourceResult.getNodeOutput(sourcePort);
                        if (nodeOutput != null) {
                            inputs.addInput(targetPort, nodeOutput);
                        }
                    }
                }
            }

            // 开始节点没有输入，使用流程输入作为输入
            if (runNode instanceof StartNode) {
                inputs.addInput(StartNode.DEFAULT_INPUT_PORT_NAME, new NodeOutput(context.getWorkflowInput()));
            }

            try {
                NodeExecutionResult result = runNode.call(context, inputs);
                result.setNodeId(nodeId);
                result.setStartTime(startTime);
                result.setEndTime(Instant.now());
                return result;
            } catch (Exception e) {
                return NodeExecutionResult.failed(e)
                            .setNodeId(nodeId)
                            .setStartTime(startTime)
                            .setEndTime(Instant.now());
            }
        });

        this.runningFutures.put(nodeId, future);
        this.future2NodeIdMap.put(future, nodeId);
    }

    /**
     * 处理任务失败：重试或永久失败
     */
    private void handleTaskFailure(NodeExecutionResult failedResult, ExecutionContext context) {
        if (this.failedTasks.contains(failedResult)) {
            return;
        }

        final String failedNodeId = failedResult.getNodeId();
        TaskNode failedNode = this.runNodes.get(failedNodeId);
        final int maxRetries = failedNode.getMaxRetries();
        final long retryDelayMillis = failedNode.getRetryDelayMillis();
        AtomicInteger retryCounter = this.retryCounts.get(failedNodeId);

        boolean retrySuccess = false;
        if (retryCounter.incrementAndGet() <= maxRetries) {
            LOG.warn(">> WARNING: 重试任务 <{}> ({}/{})", failedNodeId, retryCounter.get(), maxRetries);
            // 计数延后重试排程
            this.scheduledRetryTasksNum.incrementAndGet();
            // 延迟重试
            try {
                this.retryExecutorService.schedule(() -> {
                    // 更新延后重试排程计数
                    scheduledRetryTasksNum.decrementAndGet();
                    submitTask(failedNodeId, context);
                }, retryDelayMillis, TimeUnit.MILLISECONDS);
                retrySuccess = true;
            } catch (RejectedExecutionException e) {
                LOG.error(">> ERROR: 重试任务 <{}> 提交失败: ", failedNodeId, e);
            }
        } else {
            LOG.error(">> ERROR: 任务 <{}> 在重试 {} 次后仍然失败, 放弃继续执行它.", failedNodeId, maxRetries);
        }

        if (!retrySuccess && this.failedTasks.add(failedResult)) {
            try {
                this.executionListener.onNodeCompleted(failedResult);
            } catch (Exception e) {
                LOG.error(">> ERROR: 回调 `executionListener.onNodeCompleted()` 时发生异常: ", e);
            }

            // 如果此节点达到最大重试次数后,仍然失败,则此节点的下游依赖节点全部跳过
            skipDependents(context, failedNodeId);
        }
    }

    /**
     * 跳过这个节点的下游依赖节点
     */
    private void skipDependents(ExecutionContext context, String nodeId) {
        Collection<String> dependents = this.dagGraph.getDownstreamNodes(nodeId);
        if (dependents != null && dependents.size() > 0) {
            for (String nextNodeId : dependents) {
                propagateSkipNode(context, nextNodeId);
            }
        }
    }

    /**
     * 递归地传播“跳过”状态。
     * 当一个节点被其上游跳过时，它本身也必须被视为“跳过”，并将其完成状态向下游传播。
     */
    private void propagateSkipNode(ExecutionContext context, String nodeIdToSkip) {
        // 使用队列代替递归，避免栈溢出
        Queue<String> skipQueue = new LinkedList<>();
        skipQueue.offer(nodeIdToSkip);

        while (!skipQueue.isEmpty()) {
            final String skipNodeId = skipQueue.poll();
            // 先减入度(入度代表的是“上游是否已表态”)
            final int remaining = this.currentInDegree.get(skipNodeId).decrementAndGet();

            if (this.skippedNodes.add(skipNodeId)) {
                // 更新节点状态
                this.runNodes.get(skipNodeId).setTaskState(TaskState.SKIPPED);
                NodeExecutionResult result = NodeExecutionResult.failed("节点被跳过")
                        .setNodeId(skipNodeId)
                        .setSkipped(true);
                // 上下文记录节点执行结果
                context.addNodeExecutionResult(skipNodeId, result);

                try {
                    this.executionListener.onNodeCompleted(result);
                } catch (Exception e) {
                    LOG.error(">> ERROR: 回调 `executionListener.onNodeCompleted()` 时发生异常: ", e);
                }
            }

            // 只有当入度降为0时，才真正视为该节点“处理完毕”（Finished Skipped），并继续传播
            if (remaining <= 0) {
                this.completedTasksNum.incrementAndGet();
                // 将跳过状态继续向下游传播
                Collection<String> dependents = this.dagGraph.getDownstreamNodes(skipNodeId);
                if (dependents != null && dependents.size() > 0) {
                    skipQueue.addAll(dependents);
                }
            }

        }
    }

    /**
     * 取消所有运行中的任务
     */
    private void cancelAllRunningTasks() {
        synchronized (this.stateLock) {
            if (this.executionState == ExecutionState.RUNNING) {
                this.executionState = ExecutionState.FAILED;
            }

            if (this.runningFutures.size() > 0) {
                for (Map.Entry<String, Future<NodeExecutionResult>> en : this.runningFutures.entrySet()) {
                    // 更新节点状态
                    this.runNodes.get(en.getKey()).setTaskState(TaskState.CANCELLED);
                    Future<NodeExecutionResult> f = en.getValue();
                    if (!f.isDone() && !f.isCancelled()) {
                        try {
                            f.cancel(true);
                        } catch (Exception e) {
                            LOG.error(">> ERROR: 任务 <{}> 取消失败", en.getKey());
                        }
                    }
                }
            }

            this.readyQueue.clear();
            this.runningFutures.clear();
            this.future2NodeIdMap.clear();
        }
    }

    @Override
    public void close() throws Exception {
        // 确保先停止接受新任务，再关闭线程池
        synchronized (this.stateLock) {
            if (this.executionState == ExecutionState.RUNNING) {
                this.executionState = ExecutionState.CANCELLED;
            }
        }

        shutdown(this.threadPoolExecutor);
        shutdown(this.retryExecutorService);
    }

    private void shutdown(ExecutorService executor) {
        if (executor == null || executor.isShutdown() || executor.isTerminated()) {
            return;
        }

        try {
            executor.shutdown();
            if (!executor.awaitTermination(SHUTDOWN_AWAIT_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
            if (!executor.awaitTermination(SHUTDOWN_AWAIT_SECONDS, TimeUnit.SECONDS)) {
                LOG.error(">> ERROR: shutdown `ExecutorService` 未能及时终止");
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.error(">> ERROR: 当 shutdown `ExecutorService` 时发生未知异常", e);
        }
    }

    private static class DefaultExecutionListener implements ExecutionListener {
        @Override
        public void onFlowStart() {
            // nothing
        }

        @Override
        public void onNodeCompleted(NodeExecutionResult nodeExecutionResult) {
            // nothing
        }

        @Override
        public void onFlowCompleted(FlowExecutionResult executionResult) {
            // nothing
        }
    }

}
