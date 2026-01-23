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