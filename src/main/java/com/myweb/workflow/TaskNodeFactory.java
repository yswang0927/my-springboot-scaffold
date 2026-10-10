package com.myweb.workflow;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import com.myweb.workflow.graph.GNode;
import com.myweb.workflow.nodes.ClassificationLLMNode;
import com.myweb.workflow.nodes.HttpNode;
import com.myweb.workflow.nodes.IfElseNode;
import com.myweb.workflow.nodes.LLMNode;
import com.myweb.workflow.nodes.OutputNode;
import com.myweb.workflow.nodes.StartNode;

/**
 * 节点工厂。
 * <p>
 * 采用"类型 -> 构造函数"的注册表，替代原先硬编码的 if 判断：
 * 1. 支持引擎内置的所有节点类型(而非只有 start/output)，避免图里存在 http/llm/branch 节点时
 *    在 {@code initializeNodes()} 阶段直接抛异常导致整条链路无法构建。
 * 2. 类型匹配大小写不敏感，并支持别名(如 if_else / ifelse / condition 都指向分支节点)。
 * 3. 通过 {@link #register(String, Function)} 可注册自定义节点类型，便于扩展。
 */
public class TaskNodeFactory {

    private static final Map<String, Function<GNode, TaskNode>> REGISTRY = new ConcurrentHashMap<>();

    static {
        register(StartNode.NODE_TYPE, g -> new StartNode(g));
        register(OutputNode.NODE_TYPE, g -> new OutputNode(g));
        register(HttpNode.NODE_TYPE, g -> new HttpNode(g));
        register(LLMNode.NODE_TYPE, g -> new LLMNode(g));
        register(ClassificationLLMNode.NODE_TYPE, g -> new ClassificationLLMNode(g));
        register(IfElseNode.NODE_TYPE, g -> new IfElseNode(g));

        // 常见别名
        register("ifelse", g -> new IfElseNode(g));
        register("if-else", g -> new IfElseNode(g));
        register("condition", g -> new IfElseNode(g));
        register("intent", g -> new ClassificationLLMNode(g));
        register("classifier", g -> new ClassificationLLMNode(g));
    }

    private TaskNodeFactory() {
    }

    /**
     * 注册(或覆盖)一个节点类型。type 为空则忽略。
     *
     * @param type    节点类型标识(不区分大小写)
     * @param creator 由 GNode 构造 TaskNode 的工厂函数
     */
    public static void register(String type, Function<GNode, TaskNode> creator) {
        if (type != null && creator != null) {
            REGISTRY.put(type.toLowerCase(Locale.ROOT), creator);
        }
    }

    /**
     * 判断某节点类型是否已被支持。
     */
    public static boolean isSupported(String type) {
        return type != null && REGISTRY.containsKey(type.toLowerCase(Locale.ROOT));
    }

    public static TaskNode createNode(GNode gNode) {
        String type = gNode.getType();
        Function<GNode, TaskNode> creator = type != null ? REGISTRY.get(type.toLowerCase(Locale.ROOT)) : null;
        if (creator == null) {
            throw new IllegalArgumentException(
                    "Unsupported node type: '" + type + "' (node id: " + gNode.getId() + ")");
        }
        return creator.apply(gNode);
    }
}
