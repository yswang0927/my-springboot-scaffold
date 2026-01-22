package com.myweb.workflow;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 任务执行上下文, 提供节点之间的数据传递.
 */
public class ExecutionContext extends ConcurrentHashMap<String, Object> {
    // 上下文中记录流程中每个节点的输出，这样每个节点中都可以获取到
    private final ConcurrentMap<String, NodeExecuteResult> nodeExecutedResults = new ConcurrentHashMap<>();

    // 流程的初始输入
    private String workflowInput = "";

    public Optional<NodeExecuteResult> getNodeExecuteResult(String nodeId) {
        return Optional.ofNullable(this.nodeExecutedResults.get(nodeId));
    }

    void addNodeExecuteResult(String nodeId, NodeExecuteResult result) {
        this.nodeExecutedResults.put(nodeId, result);
    }

    public String getWorkflowInput() {
        return workflowInput != null ? workflowInput : "";
    }

    public void setWorkflowInput(String workflowInput) {
        this.workflowInput = workflowInput;
    }


}
