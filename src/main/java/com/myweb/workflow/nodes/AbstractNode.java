package com.myweb.workflow.nodes;

import com.myweb.workflow.TaskNode;
import com.myweb.workflow.TaskState;
import com.myweb.workflow.TaskTriggerRule;
import com.myweb.workflow.graph.GNode;

public abstract class AbstractNode implements TaskNode {
    protected final GNode gNode;
    protected volatile TaskState taskState = TaskState.PENDING;

    public AbstractNode(GNode gNode) {
        this.gNode = gNode;
    }

    @Override
    public String getId() {
        return this.gNode.getId();
    }

    @Override
    public void setTaskState(TaskState taskState) {
        this.taskState = taskState;
    }

    @Override
    public TaskState getTaskState() {
        return this.taskState;
    }

    @Override
    public TaskTriggerRule getTriggerRule() {
        return TaskTriggerRule.ALL_SUCCESS;
    }

}
