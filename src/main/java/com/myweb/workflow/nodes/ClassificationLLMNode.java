package com.myweb.workflow.nodes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.myweb.workflow.ExecutionContext;
import com.myweb.workflow.NodeExecutionResult;
import com.myweb.workflow.NodeInputs;
import com.myweb.workflow.NodeOutput;
import com.myweb.workflow.graph.GNode;

/**
 * 意图分类 LLM 节点：调用大模型对输入做意图/类别分类，并像分支节点一样只激活命中类别对应的下游。
 * <p>
 * 继承 {@link LLMNode} 的可注入 {@link LlmClient} 机制。当上下文未注入客户端时，退化为
 * "占位回显"且<strong>不限制</strong>下游激活（保证无密钥也能跑通）。
 * <p>
 * 期望的 {@code GNode.data} 结构：
 * <pre>
 * {
 *   "prompt": "判断用户意图：{{input}}",             // 可选
 *   "labels": ["查询", "下单", "投诉"],               // 候选类别
 *   "labelNodes": { "下单": ["nodeOrder"], "投诉": ["nodeTicket"] }  // 可选: 类别 -> 命中的直接下游节点
 * }
 * </pre>
 * 配置了 {@code labelNodes} 时，仅激活命中类别对应的下游节点，其余由引擎级联置为 SKIPPED。
 */
public class ClassificationLLMNode extends LLMNode {

    private static final Logger LOG = LoggerFactory.getLogger(ClassificationLLMNode.class);

    public static final String NODE_TYPE = "classification";

    private final List<String> labels = new ArrayList<>();
    private final Map<String, Object> labelNodes = new java.util.HashMap<>();

    public ClassificationLLMNode(GNode gNode) {
        super(gNode);
        Object labelsObj = gNode.getData() == null ? null : gNode.getData().get("labels");
        if (labelsObj == null && gNode.getData() != null) {
            labelsObj = gNode.getData().get("categories");
        }
        if (labelsObj instanceof Collection<?> c) {
            for (Object o : c) {
                if (o != null) {
                    labels.add(String.valueOf(o));
                }
            }
        }
        Object nodesObj = gNode.getData() == null ? null : gNode.getData().get("labelNodes");
        if (nodesObj instanceof Map<?, ?> m) {
            m.forEach((k, v) -> {
                if (k != null) {
                    labelNodes.put(String.valueOf(k), v);
                }
            });
        }
    }

    @Override
    public String getType() {
        return NODE_TYPE;
    }

    @Override
    public NodeExecutionResult call(ExecutionContext context, NodeInputs inputs) throws Exception {
        Object inputValue = inputs == null ? null : inputs.getInput(DEFAULT_INPUT_PORT_NAME, Object.class);
        String prompt = buildPrompt(inputValue);
        if (!labels.isEmpty()) {
            prompt = prompt + "\n请从以下类别中选择最匹配的一个，仅输出类别名称：" + String.join(", ", labels);
        }

        LlmClient client = resolveClient(context);
        if (client == null) {
            LOG.warn(">> No LlmClient configured; ClassificationLLMNode <{}> returns placeholder (no branch restriction).",
                    getId());
            return NodeExecutionResult.success()
                    .addNodeOutput(DEFAULT_OUTPUT_PORT_NAME, new NodeOutput(prompt));
        }

        String text;
        try {
            text = client.complete(prompt);
        } catch (Exception e) {
            LOG.error(">> ClassificationLLMNode <{}> call failed: {}", getId(), e.getMessage(), e);
            return NodeExecutionResult.failed(e);
        }

        String chosen = matchLabel(text);

        // 命中类别 -> 需要激活的直接下游节点集合
        Set<String> activated = new LinkedHashSet<>();
        Object targets = chosen == null ? null : labelNodes.get(chosen);
        if (targets instanceof Collection<?> c) {
            for (Object o : c) {
                if (o != null) {
                    activated.add(String.valueOf(o));
                }
            }
        }

        NodeExecutionResult result = NodeExecutionResult.success()
                .addNodeOutput(DEFAULT_OUTPUT_PORT_NAME, new NodeOutput(chosen != null ? chosen : text))
                .addNodeOutput("label", new NodeOutput(chosen))
                .addNodeOutput("raw", new NodeOutput(text));
        // 仅当确实解析出激活目标时才限制分支，否则保持"不限制"以免误跳过全部下游
        if (!activated.isEmpty()) {
            result.setNextNodesToActivate(activated);
        }
        return result;
    }

    /**
     * 从模型返回文本中匹配候选标签：优先精确(忽略大小写), 其次包含关系。
     */
    private String matchLabel(String text) {
        if (text == null || labels.isEmpty()) {
            return null;
        }
        String norm = text.trim().toLowerCase(Locale.ROOT);
        for (String label : labels) {
            if (label.trim().toLowerCase(Locale.ROOT).equals(norm)) {
                return label;
            }
        }
        for (String label : labels) {
            String ln = label.trim().toLowerCase(Locale.ROOT);
            if (norm.contains(ln) || ln.contains(norm)) {
                return label;
            }
        }
        return null;
    }

}
