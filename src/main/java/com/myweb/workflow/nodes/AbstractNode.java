package com.myweb.workflow.nodes;

import com.myweb.workflow.TaskNode;
import com.myweb.workflow.TaskState;
import com.myweb.workflow.TaskTriggerRule;
import com.myweb.workflow.graph.GNode;

import java.util.concurrent.atomic.AtomicReference;

public abstract class AbstractNode implements TaskNode {
    protected final GNode gNode;
    protected final AtomicReference<TaskState> taskState = new AtomicReference<>(TaskState.PENDING);

    public AbstractNode(GNode gNode) {
        this.gNode = gNode;
    }

    @Override
    public String getId() {
        return this.gNode.getId();
    }

    @Override
    public void setTaskState(TaskState newState) {
        this.taskState.set(newState);
    }

    @Override
    public TaskState getTaskState() {
        return this.taskState.get();
    }

    /**
     * 原子性地将状态从 expected 更新为 newState
     * @param expected 期望的当前状态
     * @param newState 新状态
     * @return true 如果更新成功，false 如果当前状态不是 expected
     */
    public boolean compareAndSetTaskState(TaskState expected, TaskState newState) {
        return this.taskState.compareAndSet(expected, newState);
    }

    @Override
    public TaskTriggerRule getTriggerRule() {
        return TaskTriggerRule.ALL_SUCCESS;
    }

}
