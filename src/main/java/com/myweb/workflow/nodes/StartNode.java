package com.myweb.workflow.nodes;

import com.myweb.workflow.ExecutionContext;
import com.myweb.workflow.NodeExecutionResult;
import com.myweb.workflow.NodeInputs;
import com.myweb.workflow.NodeOutput;
import com.myweb.workflow.graph.GNode;

import java.util.List;

/**
 * 开始节点
 */
public class StartNode extends AbstractNode {

    public StartNode(GNode gNode) {
        super(gNode);
    }

    @Override
    public String getType() {
        return "start";
    }

    @Override
    public NodeExecutionResult call(ExecutionContext context, NodeInputs inputs) throws Exception {
        // 开始节点没有端口输入，直接透传workflow的原始输入
        List<NodeOutput> sourceInputs = inputs.getAllInputs(StartNode.DEFAULT_INPUT_PORT_NAME);
        return NodeExecutionResult.success()
                .addNodeOutput(StartNode.DEFAULT_OUTPUT_PORT_NAME, sourceInputs.size() > 0 ? sourceInputs.get(0) : null);
    }

}
