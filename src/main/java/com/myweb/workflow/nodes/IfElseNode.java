package com.myweb.workflow.nodes;

import com.myweb.workflow.ExecutionContext;
import com.myweb.workflow.NodeExecuteResult;
import com.myweb.workflow.NodeInputs;
import com.myweb.workflow.graph.GNode;

import java.util.ArrayList;
import java.util.List;

/**
 * if-else分支节点
 */
public class IfElseNode extends AbstractNode {
    private List<Branch> branches = new ArrayList<>(); // 对应 if, else if...
    private String elseTargetNodeId; // 对应 else

    public IfElseNode(GNode gNode) {
        super(gNode);
    }

    @Override
    public String getType() {
        return "branch";
    }

    @Override
    public NodeExecuteResult call(ExecutionContext context, NodeInputs inputs) throws Exception {
        return null;
    }

    public void addBranch(String condition, String targetNodeId) {
        branches.add(new Branch(condition, targetNodeId));
    }

    public static class Branch {
        private String condition;
        private String targetNodeId;

        public Branch() {}

        public Branch(String condition, String targetNodeId) {
            this.condition = condition;
            this.targetNodeId = targetNodeId;
        }

        public String getCondition() {
            return condition;
        }

        public void setCondition(String condition) {
            this.condition = condition;
        }

        public String getTargetNodeId() {
            return targetNodeId;
        }

        public void setTargetNodeId(String targetNodeId) {
            this.targetNodeId = targetNodeId;
        }
    }

}
