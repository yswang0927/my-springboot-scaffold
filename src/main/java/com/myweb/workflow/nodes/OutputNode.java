package com.myweb.workflow.nodes;

import com.myweb.workflow.ExecutionContext;
import com.myweb.workflow.NodeExecutionResult;
import com.myweb.workflow.NodeInputs;
import com.myweb.workflow.NodeOutput;
import com.myweb.workflow.graph.GNode;

import java.util.List;

/**
 * 输出节点(通常是流程叶子节点)：汇聚上游输入作为流程最终输出。
 */
public class OutputNode extends AbstractNode {

    public static final String NODE_TYPE = "output";

    // 流程最终输出在上下文中的存放键
    public static final String WORKFLOW_OUTPUT_KEY = "workflowOutput";

    public OutputNode(GNode gNode) {
        super(gNode);
    }

    @Override
    public String getType() {
        return NODE_TYPE;
    }

    @Override
    public NodeExecutionResult call(ExecutionContext context, NodeInputs inputs) throws Exception {
        List<NodeOutput> allInputs = inputs.getAllInputs(DEFAULT_INPUT_PORT_NAME);
        // 取默认输入端口上的第一个输出作为流程输出；无输入则为 null
        Object workflowOutput = allInputs.isEmpty() ? null : allInputs.get(0).getPayload();

        // 记录到上下文，供调用方在流程结束后读取
        if (context != null) {
            context.put(WORKFLOW_OUTPUT_KEY, new NodeOutput(workflowOutput));
        }

        return NodeExecutionResult.success()
                .addNodeOutput(DEFAULT_OUTPUT_PORT_NAME, new NodeOutput(workflowOutput));
    }

}
