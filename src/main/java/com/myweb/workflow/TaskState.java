package com.myweb.workflow;

/**
 * DAG 节点执行状态
 */
public enum TaskState {
    PENDING,         // 等待执行
    RUNNING,         // 正在执行
    SUCCESS,         // 成功完成
    FAILED,          // 执行失败
    SKIPPED,         // 被跳过
    CANCELLED,        // 被取消
    UPSTREAM_FAILED;  // 因上游失败而无法运行
}
