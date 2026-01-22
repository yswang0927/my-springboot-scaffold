package com.myweb.workflow.nodes;

import com.myweb.workflow.ExecutionContext;
import com.myweb.workflow.NodeExecuteResult;
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
    public NodeExecuteResult call(ExecutionContext context, NodeInputs inputs) throws Exception {
        List<NodeOutput> allInputs = inputs.getInput(OutputNode.DEFAULT_INPUT_PORT_NAME);
        return null;
    }

}
