package com.myweb.workflow;

public interface TaskNode {

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
     * 节点失败重试之间的间隔
     */
    default long getRetryDelayMillis() {
        return 1000;
    }

    /**
     * 节点执行
     * @param context 执行上下文
     * @param inputs 节点的输入数据
     * @return 执行结果
     * @throws Exception
     */
    NodeExecuteResult call(ExecutionContext context, NodeInputs inputs) throws Exception;

}
