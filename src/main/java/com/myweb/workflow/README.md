# Workflow DAG 执行引擎

基于拓扑排序的并行 DAG 执行器，支持触发规则、分支跳过、失败重试、流程暂停/恢复与 Cron 调度。

## 快速开始

```java
import com.myweb.workflow.*;
import com.myweb.workflow.graph.*;

// 从 JSON 反序列化或手工构建
Graph dagGraph = new Graph(nodes, edges);

ExecutionContext context = new ExecutionContext();
context.setWorkflowInput("输入信息");

try (FlowExecutorPro executor = new FlowExecutorPro(dagGraph, new ExecutionListener() {
    @Override
    public void onFlowStart() {
        System.out.println(">> 流程开始执行");
    }

    @Override
    public void onNodeCompleted(NodeExecutionResult result) {
        System.out.println(">> 节点 <" + result.getNodeId() + "> 执行完成");
    }

    @Override
    public void onFlowCompleted(FlowExecutionResult executionResult) {
        System.out.println(">> 流程执行结束, success=" + executionResult.isSuccess());
    }

    @Override
    public void onFlowPaused(String nodeId, String reason) {
        System.out.println(">> 流程在节点 <" + nodeId + "> 暂停: " + reason);
        // 持久化 context，等待用户交互后调用 executor.resume(context)
    }
})) {
    executor.execute(context);
}
```

## 整图超时与看门狗

构造时传入超时毫秒（`<=0` 表示不限制）：

```java
// 整图最多 120 秒；到期后看门狗主动中断运行中任务
FlowExecutorPro executor = new FlowExecutorPro(dagGraph, listener, null, 120_000L);
```

机制说明：

1. **看门狗线程**（`flow-timeout-watchdog`）：在 `execute` / `resume` 开始时 `armWatchdog()`，到期调用 `triggerFlowTimeout`，将状态置为 `TIMED_OUT` 并 `cancel` 所有运行中 Future。
2. **主循环双保险**：每次 poll 前检查 `flowDeadline`，避免仅依赖 poll 周期（最多约 2s）才感知超时。
3. **幂等**：`flowTimedOut` CAS，看门狗与主循环只会生效一次。
4. **结果**：`FlowExecutionResult.isTimedOut() == true`，未完成节点记为 `cancelled by flow timeout`。
5. **监听**：可实现 `ExecutionListener.onFlowTimedOut(timeoutMillis, message)`。

```java
@Override
public void onFlowTimedOut(long timeoutMillis, String message) {
    // 告警 / 打点
}
```

## 节点 data 配置

在 `GNode.data` 中可配置（均可选）：

| 字段 | 说明 | 默认 |
|------|------|------|
| `triggerRule` | 触发规则，见 `TaskTriggerRule` | `ALL_SUCCESS` |
| `maxRetries` | 失败最大重试次数 | `3` |
| `retryDelayMillis` | 重试间隔毫秒 | `1000` |

示例：

```json
{
  "id": "nodeB",
  "type": "http",
  "data": {
    "url": "https://api.example.com/x",
    "triggerRule": "ONE_SUCCESS",
    "maxRetries": 2,
    "retryDelayMillis": 500
  }
}
```

## 暂停与恢复

节点抛出 `FlowPauseException` 后，执行器进入 `PAUSED`：

1. 监听器 `onFlowPaused` 被调用，请持久化 `ExecutionContext`。
2. 用户交互完成后，将结果写入 context，调用 `executor.resume(context)`。
3. 暂停节点视为 SUCCESS，下游按触发规则继续执行。

## 核心流程

```
execute(context)
  └─ resetExecutionState()
  └─ initializeReadyQueue()      // 入度 0 或 ALWAYS
  └─ submitReadyTasks()
  └─ runMainLoop
       ├─ poll 完成任务
       ├─ handleTaskCompletion / dispatchFailure
       │    ├─ 成功 → evaluateAndTriggerDownstream
       │    ├─ 失败 → 重试(恢复 PENDING 再提交) 或 FAILED 传播
       │    ├─ Skip / Pause / Fail 分流
       └─ 超时 / 无任务可跑 → 退出
  └─ finalizeExecution()

resume(context)                  // 仅 PAUSED
  └─ 暂停节点 → SUCCESS
  └─ evaluateAndTriggerDownstream
  └─ runMainLoop ...
```

## 内置节点类型

| type | 类 |
|------|-----|
| `start` | StartNode |
| `output` | OutputNode |
| `http` | HttpNode |
| `llm` | LLMNode |
| `branch` / `ifelse` / `condition` | IfElseNode |
| `classification` / `intent` / `classifier` | ClassificationLLMNode |

自定义节点：`TaskNodeFactory.register(type, gNode -> new MyNode(gNode))`。

## Cron 调度

```java
try (CronFlowScheduler scheduler =
         new CronFlowScheduler(executor, "0/10 * * * * *", "定时输入")) {
    scheduler.start();
}
```

上一次流程未结束时，本次 tick 会被跳过（不堆积）。
