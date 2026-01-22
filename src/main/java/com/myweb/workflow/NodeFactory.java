package com.myweb.workflow;

import com.myweb.workflow.graph.GNode;
import com.myweb.workflow.nodes.OutputNode;
import com.myweb.workflow.nodes.StartNode;

public class NodeFactory {
    public static TaskNode createNode(GNode gNode) {
        if ("start".equals(gNode.getType())) {
            return new StartNode(gNode);
        }

        if ("output".equals(gNode.getType())) {
            return new OutputNode(gNode);
        }

        throw new IllegalArgumentException("Invalid node type: " + gNode.getType());
    }

}
