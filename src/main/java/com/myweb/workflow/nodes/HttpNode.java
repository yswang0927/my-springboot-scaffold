package com.myweb.workflow.nodes;

import java.util.Map;

import com.myweb.workflow.ExecutionContext;
import com.myweb.workflow.NodeExecutionResult;
import com.myweb.workflow.NodeInputs;
import com.myweb.workflow.graph.GNode;

/**
 * HTTP请求节点
 * TODO
 */
public class HttpNode extends AbstractNode {
    private Map<String, Object> httpData;

    public HttpNode(GNode gNode) {
        super(gNode);

        this.httpData = gNode.getData();
    }

    @Override
    public String getType() {
        return "http";
    }

    @Override
    public NodeExecutionResult call(ExecutionContext context, NodeInputs inputs) throws Exception {

        String url = (String) httpData.getOrDefault("url", "");
        String method = (String) httpData.getOrDefault("method", "GET");

        return null;
    }

}
