package com.myweb.workflow.exception;

/**
 * `FlowPauseException` 会将当前流程暂停, 并保存流程状态.
 * 假如流程中存在"屏幕交互"节点, 流程运行到此节点就需要暂停,
 * 并将界面UI内容输出到客户端浏览器进行渲染展示, 并由用户触发后续执行.
 */
public class FlowPauseException extends FastException {
}
