package com.myweb.workflow.exception;

/**
 * 会将当前任务标记为失败，忽略任何剩余的重试尝试.
 * 如果您的代码对其环境有额外的了解并希望更快地失败，则这些可能会很有用;
 * 例如，当它检测到其 API 密钥无效时快速失败（因为重试不会修复）。
 */
public class FlowFailException extends FastException {

}