package com.myweb.workflow;

import com.myweb.workflow.graph.Graph;

public class RunnableFlow {
    private Graph workflow;
    private String destinationNode;
    private boolean executeBefore = false;

    public Graph getWorkflow() {
        return workflow;
    }

    public void setWorkflow(Graph workflow) {
        this.workflow = workflow;
    }

    public String getDestinationNode() {
        return destinationNode;
    }

    public void setDestinationNode(String destinationNode) {
        this.destinationNode = destinationNode;
    }

    public boolean isExecuteBefore() {
        return executeBefore;
    }

    public void setExecuteBefore(boolean executeBefore) {
        this.executeBefore = executeBefore;
    }

    @Override
    public String toString() {
        return "RunnableFlow{" +
                "graph=" + workflow +
                ", destinationNode='" + destinationNode + '\'' +
                ", executeBefore=" + executeBefore +
                '}';
    }
}
