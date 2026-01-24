package com.myweb.workflow;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 任务执行上下文, 提供节点之间的数据传递.
 */
public class ExecutionContext extends ConcurrentHashMap<String, Object> {

    // 上下文中记录流程中每个节点的输出，这样每个节点中都可以获取到
    private final ConcurrentMap<String, NodeExecutionResult> nodeExecutionResults = new ConcurrentHashMap<>();

    // 流程的初始输入
    private Object workflowInput;

    public Optional<NodeExecutionResult> getNodeExecutionResult(String nodeId) {
        return Optional.ofNullable(this.nodeExecutionResults.get(nodeId));
    }

    void addNodeExecutionResult(String nodeId, NodeExecutionResult result) {
        if (nodeId != null) {
            this.nodeExecutionResults.put(nodeId, result);
        }
    }

    public Object getWorkflowInput() {
        return workflowInput;
    }

    public void setWorkflowInput(Object workflowInput) {
        this.workflowInput = workflowInput;
    }

}
