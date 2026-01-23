package com.myweb.workflow.nodes;

import com.myweb.workflow.TaskNode;
import com.myweb.workflow.TaskState;
import com.myweb.workflow.TaskTriggerRule;
import com.myweb.workflow.graph.GNode;

public abstract class AbstractNode implements TaskNode {
    // 一个节点的默认输入端口名称
    // 如果节点有多个输入端口，则节点自行定义端口名称
    protected static final String DEFAULT_INPUT_PORT_NAME = "input";
    protected static final String DEFAULT_OUTPUT_PORT_NAME = "output";

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
