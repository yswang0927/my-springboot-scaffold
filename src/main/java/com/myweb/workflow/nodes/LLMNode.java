package com.myweb.workflow.nodes;

import com.myweb.workflow.ExecutionContext;
import com.myweb.workflow.NodeExecuteResult;
import com.myweb.workflow.NodeInputs;
import com.myweb.workflow.graph.GNode;

/**
 * LLM大模型调用节点
 * TODO
 */
public class LLMNode extends AbstractNode {

    public LLMNode(GNode gNode) {
        super(gNode);
    }

    @Override
    public String getType() {
        return "llm";
    }

    @Override
    public NodeExecuteResult call(ExecutionContext context, NodeInputs inputs) throws Exception {
        return null;
    }

}
