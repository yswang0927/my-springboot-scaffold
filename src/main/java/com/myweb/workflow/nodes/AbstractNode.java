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
 *   "retryDelayMillis": 1000,
 *   "retryBackoffCoefficient": 2.0, // 退避系数，>=1.0；1.0=固定间隔（默认）
 *   "retryMaxIntervalMillis": 30000,// 单次重试等待上限（默认 30s）
 *   "retryJitter": 0.2               // 抖动比例 [0,1)，默认 0
 * }
 * </pre>
 */
public abstract class AbstractNode implements TaskNode {
    protected final GNode gNode;
    protected final AtomicReference<TaskState> taskState = new AtomicReference<>(TaskState.PENDING);

    private final TaskTriggerRule triggerRule;
    private final int maxRetries;
    private final long retryDelayMillis;
    private final double retryBackoffCoefficient;
    private final long retryMaxIntervalMillis;
    private final double retryJitter;

    public AbstractNode(GNode gNode) {
        this.gNode = gNode;
        Map<String, Object> data = gNode != null ? gNode.getData() : null;
        this.triggerRule = parseTriggerRule(data);

        this.maxRetries = parseNonNegativeInt(data, "maxRetries", 3);
        this.retryDelayMillis = parseNonNegativeLong(data, "retryDelayMillis", 1000L);
        // 非法/小于 1 的系数回退为 1.0（固定间隔），保证退避只增不减
        double coeff = parseDouble(data, "retryBackoffCoefficient", 2.0);
        this.retryBackoffCoefficient = Math.max(1.0, coeff);
        this.retryMaxIntervalMillis = Math.max(0L, parseNonNegativeLong(data, "retryMaxIntervalMillis", 30_000L));
        // clamp 到 [0, 1)：1.0 会让延迟最多翻三倍，超出设计意图
        this.retryJitter = Math.min(0.99, Math.max(0.0, parseDouble(data, "retryJitter", 0.0)));
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

    @Override
    public double getRetryBackoffCoefficient() {
        return this.retryBackoffCoefficient;
    }

    @Override
    public long getRetryMaxIntervalMillis() {
        return this.retryMaxIntervalMillis;
    }

    @Override
    public double getRetryJitter() {
        return this.retryJitter;
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

    private static double parseDouble(Map<String, Object> data, String key, double defaultValue) {
        if (data == null) {
            return defaultValue;
        }

        Object raw = data.get(key);
        if (raw == null) {
            return defaultValue;
        }

        try {
            double v = (raw instanceof Number n) ? n.doubleValue() : Double.parseDouble(String.valueOf(raw).trim());
            return Double.isFinite(v) ? v : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

}
