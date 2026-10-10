package com.myweb.workflow;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
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
    // 线程数上限：节点多为 I/O 阻塞型，并行度按 DAG 宽度设置而非 CPU 核数，但需防止线程数爆炸
    private static final int MAX_POOL_SIZE_CEILING = 256;

    // 节点信息
    private final ConcurrentMap<String, TaskNode> runNodes = new ConcurrentHashMap<>();
    // 计算每个节点的动态入度，用于判断是否"所有上游都已完结"
    private final ConcurrentMap<String, AtomicInteger> currentInDegree = new ConcurrentHashMap<>();
    // 就绪队列
    private final Queue<String> readyQueue = new ConcurrentLinkedQueue<>();
    // 用于重试
    private final ConcurrentMap<String, AtomicInteger> retryCounts = new ConcurrentHashMap<>();

    // 存储处于执行中的节点信息
    private final ConcurrentMap<String, Future<NodeExecutionResult>> runningFutures = new ConcurrentHashMap<>();
    // Future到NodeId的映射
    private final ConcurrentMap<Future<NodeExecutionResult>, String> future2NodeIdMap = new ConcurrentHashMap<>();

    private final AtomicInteger completedTasksNum = new AtomicInteger(0);
    private final AtomicInteger scheduledRetryTasksNum = new AtomicInteger(0);
    // 记录明确失败的任务（非跳过）
    private final Set<NodeExecutionResult> failedTasks = ConcurrentHashMap.newKeySet();

    // 执行完成的节点 (包括 Success, Failed, Skipped, UpstreamFailed)
    private final Set<String> completedNodes = ConcurrentHashMap.newKeySet();

    // 被上游分支节点显式排除的下游节点 -> 排除原因(粘性)。
    // 这些节点不会因触发规则而运行，需等待"所有上游都已表态"(入度归零)后再统一标记为 SKIPPED
    // 并向下游传播，避免菱形依赖下"上游未全部表态即传播"造成的过早终结与入度计数错乱。
    private final ConcurrentMap<String, String> branchExcludedNodes = new ConcurrentHashMap<>();

    private final Object stateLock = new Object();
    private volatile ExecutionState executionState = ExecutionState.READY;

    private final ExecutorCompletionService<NodeExecutionResult> executorService;
    private final ScheduledExecutorService retryExecutorService;
    private final ExecutionListener executionListener;
    
    // 区分自管理和外部传入的 executor
    private final ExecutorService managedExecutor;  // 自己创建的，需要关闭
    private final ExecutorService actualExecutor;             // 实际使用的（可能是外部传入）

    private final Graph dagGraph;

    public enum ExecutionState {
        READY, RUNNING, CANCELLED, COMPLETED, FAILED, PAUSED;
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
            // 节点执行以 I/O 阻塞为主(HTTP/LLM)，并发度应由 DAG 最大并行宽度决定，而非 CPU 核数；
            // 同时设置上限，避免超宽 DAG 导致线程数爆炸。
            final int maxParallel = Math.max(1, this.dagGraph.getMaxParallelism());
            final int poolSize = Math.min(maxParallel, MAX_POOL_SIZE_CEILING);

            final ThreadPoolExecutor tpe = new ThreadPoolExecutor(
                    poolSize,
                    poolSize,
                    60L,
                    TimeUnit.SECONDS,
                    // 无界队列：就绪任务数不会超过 DAG 宽度，任务不会因拒绝而在调用线程
                    // (主轮询线程/重试调度线程)内联执行而造成阻塞
                    new LinkedBlockingQueue<>(),
                    new ThreadFactory() {
                        private final AtomicLong tn = new AtomicLong(0);
                        @Override
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "flow-executor-" + tn.incrementAndGet());
                            t.setUncaughtExceptionHandler((thread, e) ->
                                    LOG.error(">> ERROR: Flow-Executor thread<{}> exception: ", thread.getName(), e));
                            return t;
                        }
                    }
            );
            // 允许核心线程在空闲时超时回收，避免长期占用线程资源
            tpe.allowCoreThreadTimeOut(true);
            this.managedExecutor = tpe;
            this.actualExecutor = tpe;
        } else {
            this.managedExecutor = null;  // 外部提供，不由我们管理
            this.actualExecutor = executor;
        }

        this.executionListener = listener != null ? listener : new DefaultExecutionListener();
        this.executorService = new ExecutorCompletionService<>(this.actualExecutor);
        this.retryExecutorService = Executors.newSingleThreadScheduledExecutor();

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
        this.dagGraph.getNodesInDegree().forEach((nodeId, degree) ->
                this.currentInDegree.put(nodeId, new AtomicInteger(degree)));
    }

    private void resetExecutionState() {
        // 重置入度
        this.dagGraph.getNodesInDegree().forEach((nodeId, degree) -> this.currentInDegree.get(nodeId).set(degree));
        this.runNodes.values().forEach(n -> n.setTaskState(TaskState.PENDING));
        this.retryCounts.values().forEach(counter -> counter.set(0));
        this.readyQueue.clear();
        // 取消上一轮可能仍在执行的遗留任务(如 PAUSE/异常退出时未跑完的并行任务),
        // 避免其在复用后回写新的共享上下文, 造成状态竞态。
        this.runningFutures.values().forEach(f -> f.cancel(true));
        this.runningFutures.clear();
        this.future2NodeIdMap.clear();
        this.completedTasksNum.set(0);
        // 重试调度计数同样需复位, 否则残留非零会破坏主循环"全部结束"的退出判定。
        this.scheduledRetryTasksNum.set(0);
        this.failedTasks.clear();
        this.completedNodes.clear();
        this.branchExcludedNodes.clear();
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
            LOG.error(">> ERROR: Flow start listener threw exception", e);
            // 注意：监听器异常不应中断流程执行，但记录错误以便排查
        }

        FlowExecutionResult flowExecutionResult = new FlowExecutionResult();
        flowExecutionResult.setStartTime(Instant.now());

        // 复位运行态并进入 RUNNING。"校验-复位-置位"整体置于 stateLock 内以保证原子性:
        // 1) 防止并发重复 execute;
        // 2) executionState 在上一轮被 finalize 置为终态(COMPLETED/FAILED/CANCELLED/PAUSED)后, 允许实例复用重新进入 READY 流程。
        synchronized (this.stateLock) {
            if (this.executionState == ExecutionState.RUNNING) {
                throw new FlowExecuteException("Executor is already running: " + this.executionState);
            }
            // 重置状态(入度/节点状态/计数器/各类队列与集合), 支持执行器复用
            resetExecutionState();
            this.executionState = ExecutionState.RUNNING;
        }

        // 1. 初始化就绪队列
        initializeReadyQueue();

        // 2. 提交第一批任务
        submitReadyTasks(context);

        try {
            while ((this.completedTasksNum.get()) < this.runNodes.size()
                    && this.executionState == ExecutionState.RUNNING) {

                Future<NodeExecutionResult> completedFuture = this.executorService.poll(TASK_POLL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (completedFuture == null) {
                    // 补交就绪任务
                    if (this.runningFutures.isEmpty() && this.readyQueue.size() > 0) {
                        submitReadyTasks(context);
                        continue;
                    }

                    // 所有任务都结束(包括重试队列)，退出循环
                    if (this.runningFutures.isEmpty() && this.readyQueue.isEmpty() && this.scheduledRetryTasksNum.get() == 0) {
                        // 检查是否有节点未完成
                        if (this.completedTasksNum.get() < this.runNodes.size()) {
                            LOG.warn(">> WARNING: Execution loop exited early. Completed: {}, Total: {}, Missing: {}",
                                    this.completedTasksNum.get(),
                                    this.runNodes.size(),
                                    this.runNodes.size() - this.completedTasksNum.get());
                            // 列出未完成的节点以便调试
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

                String finishedNodeId = this.future2NodeIdMap.remove(completedFuture);
                if (finishedNodeId == null) {
                    continue;
                }

                this.runningFutures.remove(finishedNodeId);

                try {
                    NodeExecutionResult taskResult = completedFuture.get();
                    handleTaskCompletion(finishedNodeId, taskResult, context);
                } catch (ExecutionException e) {
                    // 拆包获取真实的业务异常，并按控制流类型分发
                    Throwable cause = e.getCause();
                    NodeExecutionResult execResult = NodeExecutionResult.failed(cause)
                            .setNodeId(finishedNodeId)
                            .setEndTime(Instant.now());
                    dispatchFailure(finishedNodeId, execResult, context);
                }
                catch (CancellationException e) {
                    handleCancellation(finishedNodeId, context);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    cancelAllRunningTasks();
                    break;
                }
                catch (Exception e) {
                    // 系统级异常，视为失败
                    handleTaskFailure(NodeExecutionResult.failed(e).setNodeId(finishedNodeId), context, true);
                }

            } // end while

            finalizeExecution(flowExecutionResult);

        } catch (Exception e) {
            LOG.error("Execution Loop Error", e);
            cancelAllRunningTasks();
            flowExecutionResult.setSuccess(false);
        } finally {
            notifyFlowCompletion(flowExecutionResult);
        }
    }

    /**
     * 处理任务正常完成（包括业务上的成功或失败）
     */
    private void handleTaskCompletion(String nodeId, NodeExecutionResult result, ExecutionContext context) {
        if (result.isSuccess()) {
            this.runNodes.get(nodeId).setTaskState(TaskState.SUCCESS);
            this.completedNodes.add(nodeId);
            this.completedTasksNum.incrementAndGet();
            // 在上下文中记录节点输出
            context.addNodeExecutionResult(nodeId, result);
            notifyNodeCompletion(result);

            // 成功后，评估下游节点
            evaluateAndTriggerDownstream(nodeId, context, result.getNextNodesToActivate());
        } else {
            // 失败 / 控制流异常，统一分发（各处理器负责设置节点最终状态）
            dispatchFailure(nodeId, result, context);
        }
    }

    /**
     * 统一分发失败结果：识别节点抛出的控制流异常并路由到对应处理器。
     * <p>
     * 由于 {@link #submitTask} 中的 Callable 会捕获所有异常并包装成 failed 结果，
     * 控制流异常（暂停/跳过/快速失败）保存在 {@link NodeExecutionResult#getError()} 中，
     * 必须在此显式拆包，否则会被当作普通失败进入重试逻辑。
     */
    private void dispatchFailure(String nodeId, NodeExecutionResult failedResult, ExecutionContext context) {
        Throwable cause = failedResult.getError();
        if (cause instanceof FlowPauseException) {
            // 场景 1: 流程暂停 (如: 屏幕交互)
            handleTaskPause(nodeId, (FlowPauseException) cause, context);
        } else if (cause instanceof FlowSkipException) {
            // 场景 2: 动态跳过 (如: 无数据)
            handleTaskSkip(nodeId, (FlowSkipException) cause, context);
        } else if (cause instanceof FlowFailException) {
            // 场景 3: 快速失败 (忽略重试)
            handleTaskFailure(failedResult, context, false);
        } else {
            // 场景 4: 普通异常 / 业务软失败 (走默认重试机制)
            handleTaskFailure(failedResult, context, true);
        }
    }

    /**
     * 处理任务失败（含重试逻辑）
     */
    private void handleTaskFailure(NodeExecutionResult failedResult, ExecutionContext context, final boolean needRetry) {
        if (this.failedTasks.contains(failedResult)) {
            return;
        }

        final String failedNodeId = failedResult.getNodeId();
        TaskNode failedNode = this.runNodes.get(failedNodeId);

        if (needRetry) {
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
        }

        // 最终失败
        LOG.error(">> ERROR: Task <{}> failed after retries.", failedNodeId);
        failedNode.setTaskState(TaskState.FAILED);
        this.failedTasks.add(failedResult);
        this.completedTasksNum.incrementAndGet(); // 计数+1

        context.addNodeExecutionResult(failedNodeId, failedResult);
        notifyNodeCompletion(failedResult);

        // 即使失败，也需要评估下游（因为可能有 rules 如 ALL_DONE, ALL_FAILED 等需要运行）
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

        this.completedTasksNum.incrementAndGet(); // 计数+1
        this.completedNodes.add(nodeId);

        context.addNodeExecutionResult(nodeId, result);
        notifyNodeCompletion(result);

        // 【关键】：跳过也被视为一种完成，必须评估下游
        // 下游是否运行取决于 TriggerRule (如 ALL_SKIPPED 或 ALL_SUCCESS 等)
        evaluateAndTriggerDownstream(nodeId, context, null);
    }

    private void handleTaskPause(String nodeId, FlowPauseException ex, ExecutionContext context) {
        LOG.info(">> Task <{}> paused flow. Reason: {}", nodeId, ex.getMessage());

        synchronized (this.stateLock) {
            // 1. 修改全局执行状态为 PAUSED
            // 这会让 execute 主循环条件不满足，从而跳出循环，停止提交新任务
            // 注意：已经在运行的其他线程任务会继续跑完，这通常是合理的
            this.executionState = ExecutionState.PAUSED;
        }

        TaskNode node = this.runNodes.get(nodeId);
        // 2. 更新节点状态
        node.setTaskState(TaskState.PAUSED);
        // 3. 记录结果
        // 假设 FlowPauseException 里可以携带 UI 数据
        NodeExecutionResult result = NodeExecutionResult.failed("Paused for interaction")
                .setNodeId(nodeId)
                .setErrorMessage(ex.getMessage());

        // TODO 如果你的 FlowPauseException 有 payload (比如前端需要的 JSON)，记得塞入 result
        //result.addNodeOutput("ui_schema", new NodeOutput(exception.getUiData()));

        context.addNodeExecutionResult(nodeId, result);
        // 4. 通知监听器 (外部系统收到此事件后，应持久化当前 context 和 graph 状态)
        notifyNodeCompletion(result);
    }

    private void handleCancellation(String nodeId, ExecutionContext context) {
        LOG.warn(">> Task <{}> Cancelled.", nodeId);
        NodeExecutionResult res = NodeExecutionResult.failed("Task Cancelled").setNodeId(nodeId);
        this.runNodes.get(nodeId).setTaskState(TaskState.CANCELLED);
        this.completedTasksNum.incrementAndGet();
        this.failedTasks.add(res);

        context.addNodeExecutionResult(nodeId, res);
        notifyNodeCompletion(res);

        // 取消通常意味着流程终止，或者可以视为 FAILED 触发下游
        evaluateAndTriggerDownstream(nodeId, context, null);
    }

    /**
     * 下游级联评估任务的待处理项: 表示"某节点已终结, 需要评估其下游"。
     * 用于将原本的递归传播改为显式工作队列迭代。
     */
    private record DownstreamEval(String nodeId, Collection<String> activatedBranch) {}

    /**
     * 核心逻辑：评估下游节点是否可以触发。
     * <p>
     * 采用显式工作队列迭代处理级联跳过/失败传播，避免长链递归导致栈溢出。
     *
     * @param finishedNodeId 刚刚结束的节点ID
     * @param context 上下文
     * @param activatedBranch (可选) 如果上游是分支节点，这里指定了允许激活的下游分支集合
     */
    private void evaluateAndTriggerDownstream(String finishedNodeId, ExecutionContext context, Collection<String> activatedBranch) {
        // 使用显式工作队列替代递归调用, 避免长跳过链(如整条分支被级联跳过)导致 StackOverflow。
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

                // 1. 快速检查：如果节点已经运行或完成，跳过
                // 读取当前状态（volatile 读取保证可见性）
                TaskState currentState = dependentNode.getTaskState();
                if (currentState != TaskState.PENDING) {
                    continue;
                }

                // 2. 分支逻辑检查
                // 如果上游指定了分支，且当前节点不在分支中，则当前节点被“逻辑排除”。
                // 注意：Airflow 中未被选择的分支会置为 SKIPPED，但此处不立即终结/传播，
                // 需等待该节点的“所有上游都已表态”(入度归零)后再决定，避免菱形依赖下过早传播。
                final boolean isBranchSkipped = (eval.activatedBranch() != null
                        && !eval.activatedBranch().isEmpty()
                        && !eval.activatedBranch().contains(dependentId));

                // 3. 更新入度 (表示有多少上游已经表态)
                final int remainingDependencies = this.currentInDegree.get(dependentId).decrementAndGet();

                if (isBranchSkipped) {
                    // 记录被分支排除(粘性)：即使后续触发规则满足也不会运行
                    this.branchExcludedNodes.putIfAbsent(dependentId, "Not selected by branch node " + eval.nodeId());
                }

                // 被分支排除的节点不可运行；其余按触发规则评估
                final boolean excluded = this.branchExcludedNodes.containsKey(dependentId);

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
                final boolean shouldRun = !excluded && rule.evaluate(upstreamStates);
                if (shouldRun) {
                    // 满足规则，加入就绪队列
                    // 注意：这里需要防止并发重复添加。submitReadyTasks 会再次检查 PENDING
                    // 但为了保险，可以加锁或使用 CAS。此处简化依赖 submitTask 的状态检查。
                    if (!this.readyQueue.contains(dependentId)) {
                        this.readyQueue.offer(dependentId);
                    }
                } else if (remainingDependencies <= 0) {
                    // 6. 所有上游均已表态但仍不可运行 → 给出最终状态并传播(级联入队，非递归)
                    if (excluded) {
                        if (markNodeAsSkipped(dependentId, this.branchExcludedNodes.get(dependentId), context)) {
                            pending.push(new DownstreamEval(dependentId, null));
                        }
                    } else if (handleRuleMismatch(dependentId, rule, context)) {
                        pending.push(new DownstreamEval(dependentId, null));
                    }
                }
            }
        }

        // 所有级联评估完成后，统一提交新产生的就绪任务
        submitReadyTasks(context);
    }

    /**
     * 当所有上游都已结束，但触发规则仍不满足时的处理逻辑
     *
     * @return true 表示本次将节点由 PENDING 终结为最终态(需要继续评估其下游)；false 表示节点已被终结，无需重复处理
     */
    private boolean handleRuleMismatch(String nodeId, TaskTriggerRule rule, ExecutionContext context) {
        TaskNode node = this.runNodes.get(nodeId);
        
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

        // 使用 CAS 确保只有一个线程能将状态从 PENDING 转换到最终态
        boolean stateChanged = false;
        if (node instanceof AbstractNode) {
            AbstractNode abstractNode = (AbstractNode) node;
            stateChanged = abstractNode.compareAndSetTaskState(TaskState.PENDING, finalState);
        } else {
            // 降级：使用同步块
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
                .setSkipped(finalState == TaskState.SKIPPED); // UPSTREAM_FAILED 也可以视作 Skipped 的一种变体，视业务定义

        this.completedTasksNum.incrementAndGet();
        this.completedNodes.add(nodeId);

        context.addNodeExecutionResult(nodeId, result);
        notifyNodeCompletion(result);

        // 级联由调用方通过工作队列处理，不再递归
        return true;
    }

    /**
     * 将节点标记为跳过。
     *
     * @return true 表示本次将节点由 PENDING 终结为 SKIPPED(需要继续评估其下游)；false 表示节点已被终结，无需重复处理
     */
    private boolean markNodeAsSkipped(String nodeId, String reason, ExecutionContext context) {
        TaskNode node = this.runNodes.get(nodeId);
        
        // 使用 CAS 确保只有一个线程能将状态从 PENDING 转换到 SKIPPED
        boolean stateChanged = false;
        if (node instanceof AbstractNode) {
            AbstractNode abstractNode = (AbstractNode) node;
            stateChanged = abstractNode.compareAndSetTaskState(TaskState.PENDING, TaskState.SKIPPED);
        } else {
            // 降级：使用同步块
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

        NodeExecutionResult result = NodeExecutionResult.failed(reason)
                .setNodeId(nodeId)
                .setSkipped(true);

        this.completedNodes.add(nodeId);
        this.completedTasksNum.incrementAndGet();

        context.addNodeExecutionResult(nodeId, result);
        notifyNodeCompletion(result);

        // 级联由调用方通过工作队列处理，不再递归
        return true;
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

        // 使用 CAS 防止重复提交
        // Eager Execution (如 ONE_SUCCESS) 可能导致多次触发 submitTask
        if (runNode instanceof AbstractNode) {
            AbstractNode abstractNode = (AbstractNode) runNode;
            if (!abstractNode.compareAndSetTaskState(TaskState.PENDING, TaskState.RUNNING)) {
                // 状态已不是 PENDING，说明已被其他线程提交或已完成
                return;
            }
        } else {
            // 降级处理：对于非 AbstractNode 的节点，使用同步块
            synchronized (runNode) {
                if (runNode.getTaskState() != TaskState.PENDING) {
                    return;
                }
                runNode.setTaskState(TaskState.RUNNING);
            }
        }

        Future<NodeExecutionResult> future = this.executorService.submit(() -> {
            Instant startTime = Instant.now();

            if (Thread.currentThread().isInterrupted()) {
                return NodeExecutionResult.failed("Interrupted", new InterruptedException()).setNodeId(nodeId);
            }

            // 数据准备：从上游获取数据 (Inputs)
            // 注意：对于 ONE_SUCCESS 等规则，部分上游可能还没跑完，getNodeExecuteResult 可能为空
            Collection<GNodeInput> upstreamInputs = dagGraph.getUpstreamNodeInputs(nodeId);
            NodeInputs inputs = new NodeInputs();
            for (GNodeInput inputConf : upstreamInputs) {
                Optional<NodeExecutionResult> upResultOpt = context.getNodeExecutionResult(inputConf.getSourceNodeId());
                if (upResultOpt.isPresent()) {
                    NodeExecutionResult upRes = upResultOpt.get();
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

    private void finalizeExecution(FlowExecutionResult result) {
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
        for (NodeExecutionResult f : this.failedTasks) {
            result.addFailedNode(f.getNodeId(), f.getErrorMessage());
        }
    }

    private void notifyNodeCompletion(NodeExecutionResult result) {
        try {
            this.executionListener.onNodeCompleted(result);
        } catch (Exception e) {
            LOG.error(">> ERROR: Node completion listener threw exception for node <{}>", result.getNodeId(), e);
            // 注意：监听器异常不应中断流程执行
        }
    }

    private void notifyFlowCompletion(FlowExecutionResult result) {
        try {
            this.executionListener.onFlowCompleted(result);
        } catch (Exception e) {
            LOG.error(">> ERROR: Flow completion listener threw exception", e);
            // 注意：即使监听器失败，流程执行结果也已确定
        }
    }

    @Override
    public void close() throws Exception {
        try {
            cancel();
        } catch (Exception ignored) {}
        // 只关闭自己创建的 executor，不关闭外部传入的
        shutdown(this.managedExecutor);
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
        } catch (Exception ignored) {}
    }

    // Default Listener Implementation
    private static class DefaultExecutionListener implements ExecutionListener {
        @Override public void onFlowStart() {}
        @Override public void onNodeCompleted(NodeExecutionResult res) {}
        @Override public void onFlowCompleted(FlowExecutionResult res) {}
    }
}
