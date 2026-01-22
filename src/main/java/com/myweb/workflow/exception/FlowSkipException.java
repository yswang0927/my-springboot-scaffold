package com.myweb.workflow.exception;

/**
 * `FlowSkipException` 会将当前任务标记为已跳过.
 * 如果您的代码对其环境有额外的了解并希望更快地跳过，则这些可能会很有用;
 * 例如，当它知道没有可用数据时跳过.
 */
public class FlowSkipException extends FastException {

}