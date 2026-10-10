package com.myweb.workflow.nodes;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myweb.workflow.ExecutionContext;
import com.myweb.workflow.NodeExecutionResult;
import com.myweb.workflow.NodeInputs;
import com.myweb.workflow.NodeOutput;
import com.myweb.workflow.graph.GNode;

/**
 * LLM 大模型调用节点。
 * <p>
 * 通过可注入的 {@link LlmClient} 完成实际调用（见 {@link LlmClient}）。本节点<strong>不绑定</strong>
 * 具体模型：当上下文中未注入 {@code LlmClient} 时，以"占位回显"方式返回组装后的提示词，
 * 使整条链路在无密钥场景下依然可跑通。
 * <p>
 * 期望的 {@code GNode.data} 结构：
 * <pre>
 * {
 *   "prompt": "请总结以下内容：{{input}}"   // 可选, 支持 {{input}} 占位符
 * }
 * </pre>
 * 未提供 prompt 时，直接使用输入数据作为提示词。
 */
public class LLMNode extends AbstractNode {

    private static final Logger LOG = LoggerFactory.getLogger(LLMNode.class);

    public static final String NODE_TYPE = "llm";

    protected static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String INPUT_PLACEHOLDER = "{{input}}";

    protected final Map<String, Object> llmData;

    public LLMNode(GNode gNode) {
        super(gNode);
        this.llmData = gNode.getData();
    }

    @Override
    public String getType() {
        return NODE_TYPE;
    }

    @Override
    public NodeExecutionResult call(ExecutionContext context, NodeInputs inputs) throws Exception {
        Object inputValue = inputs == null ? null : inputs.getInput(DEFAULT_INPUT_PORT_NAME, Object.class);
        String prompt = buildPrompt(inputValue);

        LlmClient client = resolveClient(context);
        if (client == null) {
            LOG.warn(">> No LlmClient configured; LLMNode <{}> returns placeholder output (prompt echoed).", getId());
            return NodeExecutionResult.success()
                    .addNodeOutput(DEFAULT_OUTPUT_PORT_NAME, new NodeOutput(prompt));
        }

        try {
            String text = client.complete(prompt);
            return NodeExecutionResult.success()
                    .addNodeOutput(DEFAULT_OUTPUT_PORT_NAME, new NodeOutput(text));
        } catch (Exception e) {
            LOG.error(">> LLMNode <{}> call failed: {}", getId(), e.getMessage(), e);
            return NodeExecutionResult.failed(e);
        }
    }

    /**
     * 组装最终提示词：模板含 {@code {{input}}} 则替换，否则将输入追加到模板后。
     */
    protected String buildPrompt(Object inputValue) throws Exception {
        String template = llmData == null ? null : asString(llmData.get("prompt"));
        String inputText = textValue(inputValue);

        if (template == null || template.isBlank()) {
            return inputText;
        }
        if (template.contains(INPUT_PLACEHOLDER)) {
            return template.replace(INPUT_PLACEHOLDER, inputText);
        }
        return inputText.isEmpty() ? template : template + "\n" + inputText;
    }

    /**
     * 从执行上下文中解析可注入的 LlmClient。
     */
    protected LlmClient resolveClient(ExecutionContext context) {
        if (context == null) {
            return null;
        }
        Object obj = context.get(LlmClient.CONTEXT_KEY);
        return obj instanceof LlmClient client ? client : null;
    }

    protected static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /**
     * 将输入转为文本：字符串原样，其它类型序列化为 JSON。
     */
    protected static String textValue(Object value) throws Exception {
        if (value == null) {
            return "";
        }
        if (value instanceof String s) {
            return s;
        }
        return MAPPER.writeValueAsString(value);
    }

}
