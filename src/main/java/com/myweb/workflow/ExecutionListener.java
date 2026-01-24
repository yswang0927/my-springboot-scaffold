package com.myweb.workflow;

/**
 * 执行过程中的监听器
 */
public interface ExecutionListener {

    /**
     * 流程开始执行
     */
    void onFlowStart();

    /**
     * 当每个节点执行完毕(不论成功或失败)将调用此方法.
     *
     * @param result 节点的执行结果
     */
    void onNodeCompleted(NodeExecutionResult result);

    /**
     * 当整个工作流执行完毕
     *
     * @param result 工作流的最终执行结果
     */
    void onFlowCompleted(FlowExecutionResult result);

    /**
     * 当流程被暂停时，将调用此方法，可以用于保存当前流程的执行状态和后期用于恢复流程。
     * @param executionId 流程执行ID
     * @param flowStateData 流程状态数据
     */
    default void onFlowPaused(String executionId, String flowStateData) {
    }

}
