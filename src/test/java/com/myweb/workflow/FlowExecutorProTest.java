package com.myweb.workflow;

import com.myweb.workflow.graph.Graph;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FlowExecutorPro 完备场景测试：成功路径、并行、重试、分支跳过、Pause/Resume、
 * Cancel、整图超时看门狗、触发规则、可复用执行器。
 */
class FlowExecutorProTest {

    @BeforeAll
    static void boot() {
        TestSupport.ensureRegistered();
    }

    @BeforeEach
    void resetCounters() {
        TestSupport.CountNode.GLOBAL.set(0);
    }

    private ExecutionContext ctx(Object input) {
        ExecutionContext c = new ExecutionContext();
        c.setWorkflowInput(input);
        return c;
    }

    // -------------------- 成功 / 并行 --------------------

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void linearSuccess_startToOutput() throws Exception {
        Graph g = TestSupport.graph(
                List.of(
                        TestSupport.node("s", "start"),
                        TestSupport.node("a", TestSupport.TYPE_PASS),
                        TestSupport.node("o", "output")
                ),
                List.of(
                        TestSupport.edge("e1", "s", "a"),
                        TestSupport.edge("e2", "a", "o")
                )
        );
        TestSupport.CollectingListener listener = new TestSupport.CollectingListener();
        try (FlowExecutorPro ex = new FlowExecutorPro(g, listener)) {
            ExecutionContext c = ctx("hello");
            ex.execute(c);
            assertEquals(FlowExecutorPro.ExecutionState.COMPLETED, ex.getExecutionState());
            assertTrue(c.getNodeExecutionResult("a").isPresent());
            assertTrue(c.getNodeExecutionResult("a").get().isSuccess());
            assertNotNull(c.get("workflowOutput"));
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void parallelDiamond_bothBranchesRun() throws Exception {
        Graph g = TestSupport.graph(
                List.of(
                        TestSupport.node("s", "start"),
                        TestSupport.node("a", TestSupport.TYPE_COUNT),
                        TestSupport.node("b", TestSupport.TYPE_COUNT),
                        TestSupport.node("j", TestSupport.TYPE_PASS)
                ),
                List.of(
                        TestSupport.edge("1", "s", "a"),
                        TestSupport.edge("2", "s", "b"),
                        TestSupport.edge("3", "a", "j"),
                        TestSupport.edge("4", "b", "j")
                )
        );
        try (FlowExecutorPro ex = new FlowExecutorPro(g, new TestSupport.CollectingListener())) {
            ex.execute(ctx("x"));
            assertEquals(FlowExecutorPro.ExecutionState.COMPLETED, ex.getExecutionState());
            assertEquals(2, TestSupport.CountNode.GLOBAL.get());
        }
    }

    // -------------------- 重试 --------------------

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void retry_recoversAfterTransientFailures() throws Exception {
        // fail twice then succeed; maxRetries=3, delay short
        Graph g = TestSupport.graph(
                List.of(
                        TestSupport.node("s", "start"),
                        TestSupport.node("f", TestSupport.TYPE_FAIL, TestSupport.data(
                                "failTimes", 2,
                                "maxRetries", 3,
                                "retryDelayMillis", 50
                        )),
                        TestSupport.node("o", TestSupport.TYPE_PASS)
                ),
                List.of(
                        TestSupport.edge("1", "s", "f"),
                        TestSupport.edge("2", "f", "o")
                )
        );
        try (FlowExecutorPro ex = new FlowExecutorPro(g, new TestSupport.CollectingListener())) {
            ExecutionContext c = ctx("r");
            ex.execute(c);
            assertEquals(FlowExecutorPro.ExecutionState.COMPLETED, ex.getExecutionState());
            assertTrue(c.getNodeExecutionResult("f").get().isSuccess());
            assertTrue(c.getNodeExecutionResult("o").get().isSuccess());
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void retryExhausted_marksFailedAndDownstreamUpstreamFailed() throws Exception {
        Graph g = TestSupport.graph(
                List.of(
                        TestSupport.node("s", "start"),
                        TestSupport.node("f", TestSupport.TYPE_FAIL, TestSupport.data(
                                "failTimes", 10,
                                "maxRetries", 1,
                                "retryDelayMillis", 30
                        )),
                        TestSupport.node("d", TestSupport.TYPE_PASS) // ALL_SUCCESS default
                ),
                List.of(
                        TestSupport.edge("1", "s", "f"),
                        TestSupport.edge("2", "f", "d")
                )
        );
        try (FlowExecutorPro ex = new FlowExecutorPro(g, new TestSupport.CollectingListener())) {
            ExecutionContext c = ctx("r");
            ex.execute(c);
            // flow may COMPLETED with failedTasks or FAILED depending on finalize
            assertTrue(c.getNodeExecutionResult("f").isPresent());
            assertFalse(c.getNodeExecutionResult("f").get().isSuccess());
            // downstream should not succeed under ALL_SUCCESS
            if (c.getNodeExecutionResult("d").isPresent()) {
                assertFalse(c.getNodeExecutionResult("d").get().isSuccess());
            }
        }
    }

    // -------------------- 分支 --------------------

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void branch_activatesSelectedPath_skipsOther() throws Exception {
        Graph g = TestSupport.graph(
                List.of(
                        TestSupport.node("s", "start"),
                        TestSupport.node("br", TestSupport.TYPE_BRANCH, TestSupport.data(
                                "activate", List.of("left")
                        )),
                        TestSupport.node("left", TestSupport.TYPE_COUNT),
                        TestSupport.node("right", TestSupport.TYPE_COUNT),
                        TestSupport.node("j", TestSupport.TYPE_PASS, TestSupport.data(
                                "triggerRule", "ALL_DONE"
                        ))
                ),
                List.of(
                        TestSupport.edge("1", "s", "br"),
                        TestSupport.edge("2", "br", "left"),
                        TestSupport.edge("3", "br", "right"),
                        TestSupport.edge("4", "left", "j"),
                        TestSupport.edge("5", "right", "j")
                )
        );
        try (FlowExecutorPro ex = new FlowExecutorPro(g, new TestSupport.CollectingListener())) {
            ExecutionContext c = ctx("b");
            ex.execute(c);
            assertEquals(1, TestSupport.CountNode.GLOBAL.get(), "only left branch should run");
            assertTrue(c.getNodeExecutionResult("left").isPresent());
            assertTrue(c.getNodeExecutionResult("left").get().isSuccess());
            assertTrue(c.getNodeExecutionResult("right").isPresent());
            assertTrue(c.getNodeExecutionResult("right").get().isSkipped()
                    || !c.getNodeExecutionResult("right").get().isSuccess());
        }
    }

    // -------------------- Skip --------------------

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void programmaticSkip_propagatesUnderAllSuccess() throws Exception {
        Graph g = TestSupport.graph(
                List.of(
                        TestSupport.node("s", "start"),
                        TestSupport.node("sk", TestSupport.TYPE_SKIP),
                        TestSupport.node("d", TestSupport.TYPE_PASS)
                ),
                List.of(
                        TestSupport.edge("1", "s", "sk"),
                        TestSupport.edge("2", "sk", "d")
                )
        );
        try (FlowExecutorPro ex = new FlowExecutorPro(g, new TestSupport.CollectingListener())) {
            ExecutionContext c = ctx("x");
            ex.execute(c);
            assertTrue(c.getNodeExecutionResult("sk").get().isSkipped());
            if (c.getNodeExecutionResult("d").isPresent()) {
                assertFalse(c.getNodeExecutionResult("d").get().isSuccess());
            }
        }
    }

    // -------------------- Pause / Resume --------------------

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void pauseThenResume_continuesDownstream() throws Exception {
        Graph g = TestSupport.graph(
                List.of(
                        TestSupport.node("s", "start"),
                        TestSupport.node("p", TestSupport.TYPE_PAUSE),
                        TestSupport.node("after", TestSupport.TYPE_COUNT)
                ),
                List.of(
                        TestSupport.edge("1", "s", "p"),
                        TestSupport.edge("2", "p", "after")
                )
        );
        TestSupport.CollectingListener listener = new TestSupport.CollectingListener();
        try (FlowExecutorPro ex = new FlowExecutorPro(g, listener)) {
            ExecutionContext c = ctx("pause-me");
            ex.execute(c);
            assertEquals(FlowExecutorPro.ExecutionState.PAUSED, ex.getExecutionState());
            assertEquals("p", ex.getPausedNodeId());
            assertTrue(listener.paused.get() >= 1);
            assertEquals(0, TestSupport.CountNode.GLOBAL.get());

            ex.resume(c);
            assertEquals(FlowExecutorPro.ExecutionState.COMPLETED, ex.getExecutionState());
            assertEquals(1, TestSupport.CountNode.GLOBAL.get());
            assertTrue(c.getNodeExecutionResult("after").get().isSuccess());
        }
    }

    // -------------------- Cancel --------------------

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void cancel_stopsRunningFlow() throws Exception {
        Graph g = TestSupport.graph(
                List.of(
                        TestSupport.node("s", "start"),
                        TestSupport.node("slow", TestSupport.TYPE_SLOW, TestSupport.data("sleepMs", 8000))
                ),
                List.of(TestSupport.edge("1", "s", "slow"))
        );
        TestSupport.CollectingListener listener = new TestSupport.CollectingListener();
        AtomicReference<FlowExecutorPro> ref = new AtomicReference<>();
        Thread runner = new Thread(() -> {
            try (FlowExecutorPro ex = new FlowExecutorPro(g, listener)) {
                ref.set(ex);
                ex.execute(ctx("c"));
            } catch (Exception ignored) {
            }
        });
        runner.start();
        // wait until running
        long deadline = System.currentTimeMillis() + 5000;
        while (ref.get() == null || ref.get().getExecutionState() != FlowExecutorPro.ExecutionState.RUNNING) {
            if (System.currentTimeMillis() > deadline) break;
            Thread.sleep(50);
        }
        assertNotNull(ref.get());
        ref.get().cancel();
        runner.join(10000);
        assertEquals(FlowExecutorPro.ExecutionState.CANCELLED, ref.get().getExecutionState());
    }

    // -------------------- 整图超时看门狗 --------------------

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void flowTimeout_watchdogFires() throws Exception {
        Graph g = TestSupport.graph(
                List.of(
                        TestSupport.node("s", "start"),
                        TestSupport.node("slow", TestSupport.TYPE_SLOW, TestSupport.data("sleepMs", 10000))
                ),
                List.of(TestSupport.edge("1", "s", "slow"))
        );
        TestSupport.CollectingListener listener = new TestSupport.CollectingListener();
        // 整图 400ms 超时
        try (FlowExecutorPro ex = new FlowExecutorPro(g, listener, null, 400L)) {
            ExecutionContext c = ctx("t");
            long t0 = System.currentTimeMillis();
            ex.execute(c);
            long elapsed = System.currentTimeMillis() - t0;
            assertTrue(ex.isTimedOut(), "executor should report timed out");
            assertEquals(FlowExecutorPro.ExecutionState.TIMED_OUT, ex.getExecutionState());
            assertTrue(listener.timedOut.get() >= 1, "onFlowTimedOut should be called");
            assertTrue(elapsed < 5000, "should not wait for full sleepMs, elapsed=" + elapsed);
            assertFalse(listener.flowResults.isEmpty());
            assertTrue(listener.flowResults.get(listener.flowResults.size() - 1).isTimedOut());
        }
    }

    // -------------------- 触发规则 ONE_SUCCESS --------------------

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void oneSuccess_runsWhenAnyUpstreamSucceeds() throws Exception {
        Graph g = TestSupport.graph(
                List.of(
                        TestSupport.node("s", "start"),
                        TestSupport.node("ok", TestSupport.TYPE_PASS),
                        TestSupport.node("bad", TestSupport.TYPE_FAIL, TestSupport.data(
                                "failTimes", 10,
                                "maxRetries", 0,
                                "retryDelayMillis", 10
                        )),
                        TestSupport.node("join", TestSupport.TYPE_COUNT, TestSupport.data(
                                "triggerRule", "ONE_SUCCESS"
                        ))
                ),
                List.of(
                        TestSupport.edge("1", "s", "ok"),
                        TestSupport.edge("2", "s", "bad"),
                        TestSupport.edge("3", "ok", "join"),
                        TestSupport.edge("4", "bad", "join")
                )
        );
        try (FlowExecutorPro ex = new FlowExecutorPro(g, new TestSupport.CollectingListener())) {
            ex.execute(ctx("one"));
            assertTrue(TestSupport.CountNode.GLOBAL.get() >= 1, "join should run via ONE_SUCCESS");
        }
    }

    // -------------------- 空图 / 复用 --------------------

    @Test
    void emptyGraph_noThrow() throws Exception {
        Graph g = new Graph(List.of(), List.of());
        g.initialize();
        try (FlowExecutorPro ex = new FlowExecutorPro(g, new TestSupport.CollectingListener())) {
            ex.execute(ctx(null));
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void executorReusable_afterCompleted() throws Exception {
        Graph g = TestSupport.graph(
                List.of(
                        TestSupport.node("s", "start"),
                        TestSupport.node("a", TestSupport.TYPE_COUNT)
                ),
                List.of(TestSupport.edge("1", "s", "a"))
        );
        try (FlowExecutorPro ex = new FlowExecutorPro(g, new TestSupport.CollectingListener())) {
            ex.execute(ctx("1"));
            assertEquals(1, TestSupport.CountNode.GLOBAL.get());
            ex.execute(ctx("2"));
            assertEquals(2, TestSupport.CountNode.GLOBAL.get());
            assertEquals(FlowExecutorPro.ExecutionState.COMPLETED, ex.getExecutionState());
        }
    }

    @Test
    void concurrentExecute_rejected() throws Exception {
        Graph g = TestSupport.graph(
                List.of(
                        TestSupport.node("s", "start"),
                        TestSupport.node("slow", TestSupport.TYPE_SLOW, TestSupport.data("sleepMs", 2000))
                ),
                List.of(TestSupport.edge("1", "s", "slow"))
        );
        TestSupport.CollectingListener listener = new TestSupport.CollectingListener();
        try (FlowExecutorPro ex = new FlowExecutorPro(g, listener)) {
            Thread t = new Thread(() -> ex.execute(ctx("a")));
            t.start();
            long deadline = System.currentTimeMillis() + 3000;
            while (ex.getExecutionState() != FlowExecutorPro.ExecutionState.RUNNING
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertThrows(Exception.class, () -> ex.execute(ctx("b")));
            t.join(5000);
        }
    }
}
