package com.myweb.workflow.nodes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;

import com.myweb.workflow.ExecutionContext;
import com.myweb.workflow.NodeExecutionResult;
import com.myweb.workflow.NodeInputs;
import com.myweb.workflow.NodeOutput;
import com.myweb.workflow.graph.GNode;

/**
 * if-else 分支节点。
 * <p>
 * 通过触发规则"激活指定下游分支"，未被选中的下游分支会被引擎(见 FlowExecutorPro 的
 * branchExcludedNodes 逻辑)标记为 SKIPPED 并向下游级联传播。
 * <p>
 * 期望的 {@code GNode.data} 结构：
 * <pre>
 * {
 *   "branches": [
 *     { "condition": "#input != null && #input['score'] >= 0.8", "targetNodes": ["nodeA", "nodeB"] },
 *     { "condition": "#input['score'] >= 0.5",                    "targetNodes": ["nodeC"] }
 *   ],
 *   "elseNodes": ["nodeD"]        // 可选：所有条件都不命中时激活的分支
 * }
 * </pre>
 * 说明：
 * <ul>
 *   <li>{@code condition} 使用 SpEL 表达式求值，上下文变量 {@code #input} 为默认输入端口上的数据、
 *       {@code #context} 为执行上下文；{@code condition} 为 "else"/"default"/"*" 视为兜底恒真。</li>
 *   <li>按 if / else-if 语义，命中第一个满足条件的分支后即停止。</li>
 *   <li>{@code targetNodes} 应为该分支节点的<strong>直接下游节点 ID</strong>；只有这些节点会被激活。</li>
 * </ul>
 */
public class IfElseNode extends AbstractNode {

    private static final Logger LOG = LoggerFactory.getLogger(IfElseNode.class);

    public static final String NODE_TYPE = "branch";

    private static final ExpressionParser PARSER = new SpelExpressionParser();

    private final List<Branch> branches = new ArrayList<>(); // 对应 if, else if...
    private List<String> elseNodes = new ArrayList<>();      // 对应 else

    public IfElseNode(GNode gNode) {
        super(gNode);
        parseConfig(gNode.getData());
    }

    @Override
    public String getType() {
        return NODE_TYPE;
    }

    @SuppressWarnings("unchecked")
    private void parseConfig(Map<String, Object> data) {
        if (data == null) {
            return;
        }
        Object branchesObj = data.get("branches");
        if (branchesObj instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    String condition = asString(m.get("condition"));
                    List<String> targets = asStringList(m.get("targetNodes"));
                    this.branches.add(new Branch(condition, targets));
                }
            }
        }
        Object elseObj = data.get("elseNodes");
        if (elseObj != null) {
            this.elseNodes = asStringList(elseObj);
        }
    }

    @Override
    public NodeExecutionResult call(ExecutionContext context, NodeInputs inputs) throws Exception {
        Object inputValue = inputs == null ? null : inputs.getInput(DEFAULT_INPUT_PORT_NAME, Object.class);

        // 使用 SimpleEvaluationContext 替代 StandardEvaluationContext 以防止 RCE 风险
        // SimpleEvaluationContext 只允许访问属性和方法引用，不允许执行任意 Java 代码
        SimpleEvaluationContext evalCtx = SimpleEvaluationContext
                .forReadOnlyDataBinding()
                .build();
        evalCtx.setVariable("input", inputValue);
        evalCtx.setVariable("context", context);

        Set<String> activated = new LinkedHashSet<>();
        String matchedCondition = null;
        for (Branch branch : this.branches) {
            if (evaluateCondition(branch.getCondition(), evalCtx)) {
                activated.addAll(branch.getTargetNodes());
                matchedCondition = branch.getCondition();
                break; // if / else-if 语义：命中第一个即停止
            }
        }

        // 没有任何分支命中：走 else 分支
        if (matchedCondition == null) {
            activated.addAll(this.elseNodes);
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug(">> Branch node <{}> matched condition [{}], activated downstream: {}",
                    getId(), matchedCondition, activated);
        }

        // 关键：将选中的下游写入结果，未选中的将由引擎级联标记为 SKIPPED。
        // 透传输入，便于被激活的下游节点照常取数。
        NodeExecutionResult result = NodeExecutionResult.success()
                .setNextNodesToActivate(activated)
                .addNodeOutput(DEFAULT_OUTPUT_PORT_NAME, new NodeOutput(inputValue));
        result.setErrorMessage("");
        return result;
    }

    /**
     * 求值单个分支条件。解析或求值失败时按"不命中"处理，避免整条链路因单个表达式错误而中断。
     */
    private boolean evaluateCondition(String condition, EvaluationContext evalCtx) {
        if (condition == null || condition.isBlank()) {
            return false;
        }
        String trimmed = condition.trim();
        // 兜底分支
        if ("else".equalsIgnoreCase(trimmed) || "default".equalsIgnoreCase(trimmed) || "*".equals(trimmed)) {
            return true;
        }
        try {
            Boolean value = PARSER.parseExpression(trimmed).getValue(evalCtx, Boolean.class);
            return Boolean.TRUE.equals(value);
        } catch (Exception e) {
            LOG.warn(">> Branch node <{}> failed to evaluate condition [{}]: {}", getId(), condition, e.getMessage());
            return false;
        }
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static List<String> asStringList(Object o) {
        List<String> result = new ArrayList<>();
        if (o instanceof Collection<?> c) {
            for (Object item : c) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
        } else if (o instanceof String s && !s.isBlank()) {
            result.add(s.trim());
        }
        return result;
    }

    public void addBranch(String condition, List<String> targetNodes) {
        branches.add(new Branch(condition, targetNodes));
    }

    public List<Branch> getBranches() {
        return branches;
    }

    public List<String> getElseNodes() {
        return elseNodes;
    }

    /**
     * 单个分支：条件表达式 + 命中后需激活的直接下游节点集合。
     */
    public static class Branch {
        private String condition;
        private List<String> targetNodes = new ArrayList<>();

        public Branch() {}

        public Branch(String condition, List<String> targetNodes) {
            this.condition = condition;
            if (targetNodes != null) {
                this.targetNodes = new ArrayList<>(targetNodes);
            }
        }

        public String getCondition() {
            return condition;
        }

        public void setCondition(String condition) {
            this.condition = condition;
        }

        public List<String> getTargetNodes() {
            return targetNodes;
        }

        public void setTargetNodes(List<String> targetNodes) {
            this.targetNodes = targetNodes != null ? targetNodes : new ArrayList<>();
        }
    }

}
