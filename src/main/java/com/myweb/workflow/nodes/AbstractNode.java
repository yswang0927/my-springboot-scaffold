package com.myweb.workflow.nodes;

import com.myweb.workflow.TaskNode;
import com.myweb.workflow.TaskState;
import com.myweb.workflow.TaskTriggerRule;
import com.myweb.workflow.graph.GNode;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 节点基类：从 {@link GNode#getData()} 解析触发规则、重试次数与重试间隔，
 * 使图谱配置真正生效（此前 getTriggerRule 写死 ALL_SUCCESS，导致规则驱动不可用）。
 * <p>
 * 支持的 data 字段（均可选）：
 * <pre>
 * {
 *   "triggerRule": "ALL_SUCCESS",   // 见 TaskTriggerRule 枚举名，大小写不敏感
 *   "maxRetries": 3,
 *   "retryDelayMillis": 1000
 * }
 * </pre>
 */
public abstract class AbstractNode implements TaskNode {
    protected final GNode gNode;
    protected final AtomicReference<TaskState> taskState = new AtomicReference<>(TaskState.PENDING);

    private final TaskTriggerRule triggerRule;
    private final int maxRetries;
    private final long retryDelayMillis;

    public AbstractNode(GNode gNode) {
        this.gNode = gNode;
        Map<String, Object> data = gNode != null ? gNode.getData() : null;
        this.triggerRule = parseTriggerRule(data);
        this.maxRetries = parseNonNegativeInt(data, "maxRetries", 3);
        this.retryDelayMillis = parseNonNegativeLong(data, "retryDelayMillis", 1000L);
    }

    @Override
    public String getId() {
        return this.gNode.getId();
    }

    @Override
    public void setTaskState(TaskState newState) {
        this.taskState.set(newState);
    }

    @Override
    public TaskState getTaskState() {
        return this.taskState.get();
    }

    /**
     * 原子地将状态从 expected 更新为 newState。
     *
     * @return true 若更新成功
     */
    public boolean compareAndSetTaskState(TaskState expected, TaskState newState) {
        return this.taskState.compareAndSet(expected, newState);
    }

    @Override
    public TaskTriggerRule getTriggerRule() {
        return this.triggerRule;
    }

    @Override
    public int getMaxRetries() {
        return this.maxRetries;
    }

    @Override
    public long getRetryDelayMillis() {
        return this.retryDelayMillis;
    }

    private static TaskTriggerRule parseTriggerRule(Map<String, Object> data) {
        if (data == null) {
            return TaskTriggerRule.ALL_SUCCESS;
        }
        Object raw = data.get("triggerRule");
        if (raw == null) {
            raw = data.get("trigger_rule");
        }
        if (raw == null) {
            return TaskTriggerRule.ALL_SUCCESS;
        }
        String name = String.valueOf(raw).trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if (name.isEmpty()) {
            return TaskTriggerRule.ALL_SUCCESS;
        }
        try {
            return TaskTriggerRule.valueOf(name);
        } catch (IllegalArgumentException e) {
            return TaskTriggerRule.ALL_SUCCESS;
        }
    }

    private static int parseNonNegativeInt(Map<String, Object> data, String key, int defaultValue) {
        if (data == null) {
            return defaultValue;
        }
        Object raw = data.get(key);
        if (raw == null) {
            return defaultValue;
        }
        try {
            int v = (raw instanceof Number n) ? n.intValue() : Integer.parseInt(String.valueOf(raw).trim());
            return Math.max(0, v);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private static long parseNonNegativeLong(Map<String, Object> data, String key, long defaultValue) {
        if (data == null) {
            return defaultValue;
        }
        Object raw = data.get(key);
        if (raw == null) {
            return defaultValue;
        }
        try {
            long v = (raw instanceof Number n) ? n.longValue() : Long.parseLong(String.valueOf(raw).trim());
            return Math.max(0L, v);
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
