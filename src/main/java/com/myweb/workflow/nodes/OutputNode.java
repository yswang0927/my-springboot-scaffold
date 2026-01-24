package com.myweb.workflow.nodes;

import com.myweb.workflow.ExecutionContext;
import com.myweb.workflow.NodeExecutionResult;
import com.myweb.workflow.NodeInputs;
import com.myweb.workflow.NodeOutput;
import com.myweb.workflow.graph.GNode;

import java.util.List;

public class OutputNode extends AbstractNode {

    public OutputNode(GNode gNode) {
        super(gNode);
    }

    @Override
    public String getType() {
        return "output";
    }

    @Override
    public NodeExecutionResult call(ExecutionContext context, NodeInputs inputs) throws Exception {
        List<NodeOutput> allInputs = inputs.getAllInputs(OutputNode.DEFAULT_INPUT_PORT_NAME);
        return null;
    }

}
