package com.myweb.workflow.nodes;

/**
 * 大模型调用的可注入接口。
 * <p>
 * 引擎本身<strong>不绑定</strong>具体模型/供应商：调用方实现本接口（封装 OpenAI / DashScope /
 * 本地推理等），并在执行前将其放入 {@link com.myweb.workflow.ExecutionContext}：
 * <pre>
 *   context.put(LlmClient.CONTEXT_KEY, myLlmClient);
 * </pre>
 * {@link LLMNode} / {@link ClassificationLLMNode} 会据此取用；若上下文中不存在，则节点以
 * "占位回显"方式运行（不阻断链路），从而在未配置密钥时也能跑通整条流程。
 */
public interface LlmClient {

    /** 在 {@code ExecutionContext} 中查找本客户端的约定键。 */
    String CONTEXT_KEY = "llmClient";

    /**
     * 发送提示词，返回模型文本输出。
     *
     * @param prompt 组装好的提示词
     * @return 模型返回的文本
     * @throws Exception 调用失败（网络/鉴权/解析等），由节点按失败重试策略处理
     */
    String complete(String prompt) throws Exception;
}
