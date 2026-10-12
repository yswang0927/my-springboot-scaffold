package com.myweb.workflow;

public interface TaskNode {
    // 一个节点的默认输入端口名称
    // 如果节点有多个输入端口，则节点自行定义端口名称
    String DEFAULT_INPUT_PORT_NAME = "input";
    String DEFAULT_OUTPUT_PORT_NAME = "output";

    /**
     * 节点ID
     * @return 节点唯一ID
     */
    String getId();

    /**
     * 节点类型
     * @return 节点类型
     */
    String getType();

    /**
     * 设置节点的任务状态
     * @param taskState 任务状态
     */
    void setTaskState(TaskState taskState);

    TaskState getTaskState();

    /**
     * 设置节点的触发规则
     */
    default TaskTriggerRule getTriggerRule() {
        return TaskTriggerRule.ALL_SUCCESS;
    }

    /**
     * 节点失败后的最大重试次数
     */
    default int getMaxRetries() {
        return 3;
    }

    /**
     * 节点失败重试之间的间隔（首次重试的延迟，也是退避增长的基数）
     */
    default long getRetryDelayMillis() {
        return 1000;
    }

    /**
     * 退避系数：第 n 次重试的延迟 = retryDelayMillis × coefficient^(n-1)。
     * 默认 1.0（固定间隔，与历史行为一致）；典型指数退避取 2.0。
     */
    default double getRetryBackoffCoefficient() {
        return 2.0;
    }

    /**
     * 单次重试等待的上限（毫秒），防止退避无界增长拖长流程。默认 30s。
     */
    default long getRetryMaxIntervalMillis() {
        return 30_000L;
    }

    /**
     * 抖动比例 [0, 1)：在封顶后的延迟上再叠加 0~jitter×delay 的随机抖动，
     * 打散多节点/多流程同 delay 同时唤醒的重试风暴。默认 0（不抖动）。
     */
    default double getRetryJitter() {
        return 0.0;
    }

    /**
     * 节点执行
     * @param context 执行上下文，可以从上下文中获取一些全局数据
     * @param inputs 节点的输入数据，可以从中获取此节点输入端口上的数据
     * @return 执行结果
     * @throws Exception
     */
    NodeExecutionResult call(ExecutionContext context, NodeInputs inputs) throws Exception;

}
