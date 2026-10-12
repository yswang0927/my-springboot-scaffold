package com.myweb.workflow;

import com.myweb.workflow.graph.GEdge;
import com.myweb.workflow.graph.GNode;
import com.myweb.workflow.graph.Graph;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GraphTest {

    @Test
    void linearGraph_inDegreesAndDownstream() {
        Graph g = TestSupport.graph(
                List.of(TestSupport.node("a", "start"), TestSupport.node("b", "test_pass"),
                        TestSupport.node("c", "output")),
                List.of(TestSupport.edge("e1", "a", "b"), TestSupport.edge("e2", "b", "c"))
        );
        assertEquals(0, g.getNodesInDegree().get("a"));
        assertEquals(1, g.getNodesInDegree().get("b"));
        assertEquals(1, g.getNodesInDegree().get("c"));
        assertTrue(g.getDownstreamNodes("a").contains("b"));
        assertTrue(g.getUpstreamNodes("c").contains("b"));
        assertEquals(1, g.getMaxParallelism());
    }

    @Test
    void parallelBranches_maxParallelismAtLeast2() {
        Graph g = TestSupport.graph(
                List.of(
                        TestSupport.node("s", "start"),
                        TestSupport.node("a", "test_pass"),
                        TestSupport.node("b", "test_pass"),
                        TestSupport.node("j", "output")
                ),
                List.of(
                        TestSupport.edge("1", "s", "a"),
                        TestSupport.edge("2", "s", "b"),
                        TestSupport.edge("3", "a", "j"),
                        TestSupport.edge("4", "b", "j")
                )
        );
        assertTrue(g.getMaxParallelism() >= 2);
        assertEquals(2, g.getNodesInDegree().get("j"));
    }

    @Test
    void cycle_rejectedOnInitialize() {
        GNode a = TestSupport.node("a", "test_pass");
        GNode b = TestSupport.node("b", "test_pass");
        Graph g = new Graph(List.of(a, b), List.of(
                TestSupport.edge("1", "a", "b"),
                TestSupport.edge("2", "b", "a")
        ));
        assertThrows(IllegalStateException.class, g::initialize);
    }

    @Test
    void duplicateEdges_countInDegreeOnce() {
        Graph g = TestSupport.graph(
                List.of(TestSupport.node("a", "test_pass"), TestSupport.node("b", "test_pass")),
                List.of(
                        TestSupport.edge("1", "a", "b"),
                        TestSupport.edge("2", "a", "b") // same source/target ports -> equal, deduped
                )
        );
        assertEquals(1, g.getNodesInDegree().get("b"));
    }

    @Test
    void isolatedNode_detected() {
        Graph g = TestSupport.graph(
                List.of(TestSupport.node("alone", "test_pass"), TestSupport.node("a", "test_pass")),
                List.of()
        );
        assertTrue(g.isIsolatedNode("alone"));
        assertTrue(g.getIsolatedNodes().contains("alone"));
    }

    @Test
    void findSubGraphReachingTarget_missingTarget_returnsNullConsistently() {
        // 边非空 + 目标不存在：旧实现返回空 Graph，与 javadoc“不存在返 null”契约矛盾
        Graph withEdges = TestSupport.graph(
                List.of(TestSupport.node("a", "test_pass"), TestSupport.node("b", "test_pass")),
                List.of(TestSupport.edge("1", "a", "b"))
        );
        assertNull(withEdges.findSubGraphReachingTarget("ghost", true));
        assertNull(withEdges.findSubGraphReachingTarget("ghost", false));

        // 边为空 + 目标不存在：保持原有 null 行为
        Graph noEdges = TestSupport.graph(
                List.of(TestSupport.node("a", "test_pass")),
                List.of()
        );
        assertNull(noEdges.findSubGraphReachingTarget("ghost", true));
        assertNull(noEdges.findSubGraphReachingTarget(null, true));

        // 目标存在时行为不变：反向可达子图含全部前驱
        Graph sub = withEdges.findSubGraphReachingTarget("b", true);
        assertNotNull(sub);
        assertEquals(2, sub.getNodes().size());
        Graph subNoTarget = withEdges.findSubGraphReachingTarget("b", false);
        assertNotNull(subNoTarget);
        assertEquals(1, subNoTarget.getNodes().size());
    }
}
