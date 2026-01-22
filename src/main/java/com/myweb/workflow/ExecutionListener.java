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
    void onNodeCompleted(NodeExecuteResult result);

    /**
     * 当整个工作流执行完毕
     *
     * @param result 工作流的最终执行结果
     */
    void onFlowCompleted(FlowExecuteResult result);

}
