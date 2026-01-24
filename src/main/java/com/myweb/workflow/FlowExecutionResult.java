package com.myweb.workflow;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * 整个工作流的执行结果.
 */
public class FlowExecutionResult {
    private List<String> succeedNodes = new ArrayList<>();
    // <nodeId, failedReason>
    private Map<String, String> failedNodes = new HashMap<>();

    private Instant startTime;
    private Instant endTime;

    private boolean success = false;

    public Instant getStartTime() {
        return startTime;
    }

    public FlowExecutionResult setStartTime(Instant startTime) {
        this.startTime = startTime;
        return this;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public FlowExecutionResult setEndTime(Instant endTime) {
        this.endTime = endTime;
        return this;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public FlowExecutionResult addSucceedNode(String nodeId) {
        if (nodeId != null) {
            this.succeedNodes.add(nodeId);
        }
        return this;
    }

    public FlowExecutionResult setSucceedNodes(Collection<String> succeedNodeIds) {
        if (succeedNodeIds != null && succeedNodeIds.size() > 0) {
            this.succeedNodes.addAll(succeedNodeIds);
        }
        return this;
    }

    public FlowExecutionResult addFailedNode(String nodeId, String failedReason) {
        if (nodeId != null) {
            this.failedNodes.put(nodeId, failedReason != null ? failedReason : "");
        }
        return this;
    }

    /**
     * 计算整个工作流的执行耗时, 单位: 毫秒
     * @return 工作流执行耗时多少毫秒, 如果没有被执行过, 则返回 null
     */
    public Long getExecutionTimeMillis() {
        if (this.startTime != null && this.endTime != null) {
            return Long.valueOf(Duration.between(this.startTime, this.endTime).toMillis());
        }
        return null;
    }

}
