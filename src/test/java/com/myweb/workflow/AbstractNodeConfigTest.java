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
    void parsesBackoffConfigAndClampsInvalidValues() {
        AbstractNode full = new TestSupport.PassNode(TestSupport.node("n5", TestSupport.TYPE_PASS, TestSupport.data(
                "retryDelayMillis", 500,
                "retryBackoffCoefficient", 2.0,
                "retryMaxIntervalMillis", 8000,
                "retryJitter", 0.2
        )));
        assertEquals(2.0, full.getRetryBackoffCoefficient(), 1e-9);
        assertEquals(8000L, full.getRetryMaxIntervalMillis());
        assertEquals(0.2, full.getRetryJitter(), 1e-9);

        // 非法值：系数<1回退1.0；负抖动/越界抖动 clamp 到 [0, 0.99]
        AbstractNode bad = new TestSupport.PassNode(TestSupport.node("n6", TestSupport.TYPE_PASS, TestSupport.data(
                "retryBackoffCoefficient", 0.5,
                "retryJitter", -1.0
        )));
        assertEquals(1.0, bad.getRetryBackoffCoefficient(), 1e-9);
        assertEquals(0.0, bad.getRetryJitter(), 1e-9);

        AbstractNode over = new TestSupport.PassNode(TestSupport.node("n7", TestSupport.TYPE_PASS, TestSupport.data(
                "retryJitter", 5.0
        )));
        assertEquals(0.99, over.getRetryJitter(), 1e-9);
    }

    @Test
    void defaultsWhenDataMissing() {
        GNode g = TestSupport.node("n2", TestSupport.TYPE_PASS);
        AbstractNode node = new TestSupport.PassNode(g);
        assertEquals(TaskTriggerRule.ALL_SUCCESS, node.getTriggerRule());
        assertEquals(3, node.getMaxRetries());
        assertEquals(1000L, node.getRetryDelayMillis());
        // 退避默认值：固定间隔、30s 封顶、无抖动 → 与历史行为完全一致
        assertEquals(1.0, node.getRetryBackoffCoefficient(), 1e-9);
        assertEquals(30_000L, node.getRetryMaxIntervalMillis());
        assertEquals(0.0, node.getRetryJitter(), 1e-9);
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
