package com.myweb.workflow;

import com.myweb.workflow.graph.GNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TaskNodeFactoryTest {

    @Test
    void builtInTypesSupported() {
        assertTrue(TaskNodeFactory.isSupported("start"));
        assertTrue(TaskNodeFactory.isSupported("output"));
        assertTrue(TaskNodeFactory.isSupported("http"));
        assertTrue(TaskNodeFactory.isSupported("llm"));
        assertTrue(TaskNodeFactory.isSupported("branch"));
        assertTrue(TaskNodeFactory.isSupported("IFELSE"));
        assertTrue(TaskNodeFactory.isSupported("condition"));
    }

    @Test
    void unsupportedTypeThrows() {
        GNode g = TestSupport.node("x", "no_such_type");
        assertThrows(IllegalArgumentException.class, () -> TaskNodeFactory.createNode(g));
    }

    @Test
    void customRegisterWorks() {
        TaskNodeFactory.register("custom_probe", g -> new TestSupport.PassNode(g));
        assertTrue(TaskNodeFactory.isSupported("custom_probe"));
        TaskNode n = TaskNodeFactory.createNode(TestSupport.node("c1", "custom_probe"));
        assertEquals("c1", n.getId());
    }
}
