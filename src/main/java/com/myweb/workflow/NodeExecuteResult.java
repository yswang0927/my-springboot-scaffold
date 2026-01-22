package com.myweb.workflow;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * 节点执行结果.
 */
public class NodeExecuteResult {
    private String nodeId;
    private final boolean success;
    private final Throwable error;
    private String errorMessage = "";
    private boolean skipped = false;
    // 节点的输出<Port, NodeOutput>
    private Map<String, NodeOutput> nodeOutputs = new HashMap<>();
    private Set<String> nextNodesToActivate = new HashSet<>();
    private Instant startTime;
    private Instant endTime;

    private NodeExecuteResult(boolean success, Throwable error) {
        this.success = success;
        this.error = error;
    }

    public static NodeExecuteResult success() {
        return new NodeExecuteResult(true, null);
    }

    public static NodeExecuteResult failed(Throwable error) {
        return new NodeExecuteResult(false, error);
    }

    public String getNodeId() {
        return nodeId;
    }

    public NodeExecuteResult setNodeId(String nodeId) {
        this.nodeId = nodeId;
        return this;
    }

    public boolean isSuccess() {
        return success;
    }

    public Throwable getError() {
        return error;
    }

    public NodeExecuteResult setNextNodesToActivate(Collection<String> nextNodeIds) {
        this.nextNodesToActivate.clear();
        if (nextNodeIds != null && nextNodeIds.size() > 0) {
            this.nextNodesToActivate.addAll(nextNodeIds);
        }
        return this;
    }

    public Collection<String> getNextNodesToActivate() {
        return this.nextNodesToActivate;
    }

    public NodeOutput getNodeOutput(String port) {
        return this.nodeOutputs.get(port);
    }

    public NodeExecuteResult addNodeOutput(String outputPort, NodeOutput nodeOutput) {
        if (outputPort != null && nodeOutput != null) {
            this.nodeOutputs.put(outputPort, nodeOutput);
        }
        return this;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public NodeExecuteResult setStartTime(Instant startTime) {
        this.startTime = startTime;
        return this;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public NodeExecuteResult setEndTime(Instant endTime) {
        this.endTime = endTime;
        return this;
    }

    public NodeExecuteResult setErrorMessage(String message) {
        this.errorMessage = message;
        return this;
    }

    public String getErrorMessage() {
        if (this.errorMessage != null && !this.errorMessage.isEmpty()) {
            return this.errorMessage;
        }

        if (this.error != null) {
            return this.error.getMessage();
        }

        return this.skipped ? "节点被跳过(因前置节点失败或被跳过)" : "";
    }

    public boolean isSkipped() {
        return skipped;
    }

    public NodeExecuteResult setSkipped(boolean skipped) {
        this.skipped = skipped;
        return this;
    }

    /**
     * 计算节点的执行耗时, 单位: 毫秒
     * @return 节点执行耗时多少毫秒, 如果节点没有被执行过, 则返回 null
     */
    public Long getExecutionTimeMillis() {
        if (this.startTime != null && this.endTime != null) {
            return Long.valueOf(Duration.between(this.startTime, this.endTime).toMillis());
        }
        return null;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof NodeExecuteResult that)) {
            return false;
        }
        return Objects.equals(nodeId, that.nodeId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(nodeId);
    }

    @Override
    public String toString() {
        return "NodeExecuteResult{" +
                "nodeId='" + nodeId + '\'' +
                ", success=" + success +
                ", error=" + error +
                ", errorMessage='" + errorMessage + '\'' +
                ", skipped=" + skipped +
                ", nodeOutputs=" + nodeOutputs +
                ", nextNodesToActivate=" + nextNodesToActivate +
                ", startTime=" + startTime +
                ", endTime=" + endTime +
                '}';
    }
}
