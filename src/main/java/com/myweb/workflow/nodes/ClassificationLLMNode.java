package com.myweb.workflow.nodes;

import com.myweb.workflow.graph.GNode;

/**
 * 意图分类LLM节点，通过大模型对用户输入进行意图分类。
 * TODO
 */
public class ClassificationLLMNode extends LLMNode {

    public ClassificationLLMNode(GNode gNode) {
        super(gNode);
    }

}
