# 使用方式

```java

import com.myweb.workflow.ExecutionContext;

// 创建DAG图，可以从 json 反序列化
Graph dagGraph = new Graph();

// 创建执行上下文
ExecutionContext context = new ExecutionContext();
context.setWorkflowInput("输入信息");

FlowExecutor executor = new FlowExecutor(dagGraph, new ExecutionListener() {
    @Override
    public void onFlowStart() {
        System.out.println(">> 流程开始执行");
    }

    @Override
    public void onNodeCompleted(NodeExecuteResult result) {
        System.out.println(String.format(">> 节点 <%s> 执行完成", result.getNodeId()));
    }

    @Override
    public void onFlowCompleted(FlowExecuteResult executionResult) {
        System.out.println(">> 流程执行结束");
    }
});

executor.execute(context);

```

## 核心流程（以 Pro 版为主）
```
execute(context)
  └─ resetExecutionState()          // 重置所有状态
  └─ initializeReadyQueue()         // 入度为0的节点入队
  └─ submitReadyTasks()             // 批量提交第一批任务
  └─ while loop (主循环)
       └─ executorService.poll()    // 等待任务完成
       └─ handleTaskCompletion()    // 成功路径
            └─ evaluateAndTriggerDownstream()  // 核心：评估下游触发
                 ├─ isBranchSkipped?  → markNodeAsSkipped()
                 ├─ rule.evaluate()   → readyQueue.offer()
                 └─ remainingDeps<=0 → handleRuleMismatch()
       └─ handleTaskFailure()       // 失败路径（含重试）
       └─ handleTaskSkip()          // 跳过路径
       └─ handleTaskPause()         // 暂停路径
```