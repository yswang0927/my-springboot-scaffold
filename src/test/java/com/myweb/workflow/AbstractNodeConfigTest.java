package com.myweb.workflow;

import com.myweb.workflow.graph.GNode;
import com.myweb.workflow.nodes.AbstractNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AbstractNodeConfigTest {

    @Test
    void parsesTriggerRuleAndRetryFromData() {
        TestSupport.ensureRegistered();
        GNode g = TestSupport.node("n1", TestSupport.TYPE_PASS, TestSupport.data(
                "triggerRule", "ONE_SUCCESS",
                "maxRetries", 5,
                "retryDelayMillis", 200
        ));
        AbstractNode node = new TestSupport.PassNode(g);
        assertEquals(TaskTriggerRule.ONE_SUCCESS, node.getTriggerRule());
        assertEquals(5, node.getMaxRetries());
        assertEquals(200L, node.getRetryDelayMillis());
    }

    @Test
    void defaultsWhenDataMissing() {
        GNode g = TestSupport.node("n2", TestSupport.TYPE_PASS);
        AbstractNode node = new TestSupport.PassNode(g);
        assertEquals(TaskTriggerRule.ALL_SUCCESS, node.getTriggerRule());
        assertEquals(3, node.getMaxRetries());
        assertEquals(1000L, node.getRetryDelayMillis());
    }

    @Test
    void invalidTriggerRuleFallsBackToAllSuccess() {
        GNode g = TestSupport.node("n3", TestSupport.TYPE_PASS, TestSupport.data("triggerRule", "NOT_A_RULE"));
        AbstractNode node = new TestSupport.PassNode(g);
        assertEquals(TaskTriggerRule.ALL_SUCCESS, node.getTriggerRule());
    }

    @Test
    void compareAndSetTaskState() {
        AbstractNode node = new TestSupport.PassNode(TestSupport.node("n4", TestSupport.TYPE_PASS));
        assertEquals(TaskState.PENDING, node.getTaskState());
        assertTrue(node.compareAndSetTaskState(TaskState.PENDING, TaskState.RUNNING));
        assertFalse(node.compareAndSetTaskState(TaskState.PENDING, TaskState.SUCCESS));
        assertEquals(TaskState.RUNNING, node.getTaskState());
    }
}
