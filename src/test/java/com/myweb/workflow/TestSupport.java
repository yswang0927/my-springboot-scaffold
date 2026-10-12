package com.myweb.workflow;

import com.myweb.workflow.graph.GEdge;
import com.myweb.workflow.graph.GNode;
import com.myweb.workflow.graph.Graph;
import com.myweb.workflow.nodes.AbstractNode;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

/**
 * 测试用图构建与可编程节点。
 */
public final class TestSupport {

    public static final String TYPE_PASS = "test_pass";
    public static final String TYPE_FAIL = "test_fail";
    public static final String TYPE_SLOW = "test_slow";
    public static final String TYPE_PAUSE = "test_pause";
    public static final String TYPE_SKIP = "test_skip";
    public static final String TYPE_COUNT = "test_count";
    public static final String TYPE_BRANCH = "test_branch";

    private TestSupport() {}

    static {
        TaskNodeFactory.register(TYPE_PASS, PassNode::new);
        TaskNodeFactory.register(TYPE_FAIL, FailNode::new);
        TaskNodeFactory.register(TYPE_SLOW, SlowNode::new);
        TaskNodeFactory.register(TYPE_PAUSE, PauseNode::new);
        TaskNodeFactory.register(TYPE_SKIP, SkipNode::new);
        TaskNodeFactory.register(TYPE_COUNT, CountNode::new);
        TaskNodeFactory.register(TYPE_BRANCH, BranchStubNode::new);
    }

    /** 确保工厂已加载静态块 */
    public static void ensureRegistered() {
        // no-op, static init
    }

    public static GNode node(String id, String type) {
        return node(id, type, null);
    }

    public static GNode node(String id, String type, Map<String, Object> data) {
        GNode n = new GNode();
        n.setId(id);
        n.setType(type);
        if (data != null) {
            n.setData(new HashMap<>(data));
        }
        return n;
    }

    public static GEdge edge(String id, String from, String to) {
        GEdge e = new GEdge();
        e.setId(id);
        e.setSource(from);
        e.setTarget(to);
        return e;
    }

    public static Graph graph(List<GNode> nodes, List<GEdge> edges) {
        Graph g = new Graph(nodes, edges);
        g.initialize();
        return g;
    }

    public static Map<String, Object> data(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    // ---------- Nodes ----------

    public static class PassNode extends AbstractNode {
        public PassNode(GNode g) { super(g); }
        @Override public String getType() { return TYPE_PASS; }
        @Override
        public NodeExecutionResult call(ExecutionContext context, NodeInputs inputs) {
            Object in = inputs != null ? inputs.getInput(Object.class) : null;
            Object out = in != null ? in : "ok:" + getId();
            return NodeExecutionResult.success()
                    .addNodeOutput(DEFAULT_OUTPUT_PORT_NAME, new NodeOutput(out));
        }
    }

    /** 前 failTimes 次失败，之后成功（用于验证重试） */
    public static class FailNode extends AbstractNode {
        private final AtomicInteger attempts = new AtomicInteger(0);
        private final int failTimes;

        public FailNode(GNode g) {
            super(g);
            Object ft = g.getData() != null ? g.getData().get("failTimes") : null;
            this.failTimes = ft instanceof Number n ? n.intValue() : 1;
        }

        @Override public String getType() { return TYPE_FAIL; }

        public int getAttempts() { return attempts.get(); }

        @Override
        public NodeExecutionResult call(ExecutionContext context, NodeInputs inputs) {
            int a = attempts.incrementAndGet();
            if (a <= failTimes) {
                return NodeExecutionResult.failed("intentional fail #" + a);
            }
            return NodeExecutionResult.success()
                    .addNodeOutput(DEFAULT_OUTPUT_PORT_NAME, new NodeOutput("recovered after " + a));
        }
    }

    public static class SlowNode extends AbstractNode {
        private final long sleepMs;

        public SlowNode(GNode g) {
            super(g);
            Object s = g.getData() != null ? g.getData().get("sleepMs") : null;
            this.sleepMs = s instanceof Number n ? n.longValue() : 5000L;
        }

        @Override public String getType() { return TYPE_SLOW; }

        @Override
        public NodeExecutionResult call(ExecutionContext context, NodeInputs inputs) throws Exception {
            Thread.sleep(sleepMs);
            return NodeExecutionResult.success()
                    .addNodeOutput(DEFAULT_OUTPUT_PORT_NAME, new NodeOutput("slow-done"));
        }
    }

    public static class PauseNode extends AbstractNode {
        public PauseNode(GNode g) { super(g); }
        @Override public String getType() { return TYPE_PAUSE; }
        @Override
        public NodeExecutionResult call(ExecutionContext context, NodeInputs inputs) {
            throw new com.myweb.workflow.exception.FlowPauseException("need interaction");
        }
    }

    public static class SkipNode extends AbstractNode {
        public SkipNode(GNode g) { super(g); }
        @Override public String getType() { return TYPE_SKIP; }
        @Override
        public NodeExecutionResult call(ExecutionContext context, NodeInputs inputs) {
            throw new com.myweb.workflow.exception.FlowSkipException("no data");
        }
    }

    public static class CountNode extends AbstractNode {
        public static final AtomicInteger GLOBAL = new AtomicInteger(0);
        public CountNode(GNode g) { super(g); }
        @Override public String getType() { return TYPE_COUNT; }
        @Override
        public NodeExecutionResult call(ExecutionContext context, NodeInputs inputs) {
            GLOBAL.incrementAndGet();
            return NodeExecutionResult.success()
                    .addNodeOutput(DEFAULT_OUTPUT_PORT_NAME, new NodeOutput(getId()));
        }
    }

    /** 简单分支：从 data.activate 读取要激活的下游 id 列表 */
    public static class BranchStubNode extends AbstractNode {
        public BranchStubNode(GNode g) { super(g); }
        @Override public String getType() { return TYPE_BRANCH; }
        @Override
        public NodeExecutionResult call(ExecutionContext context, NodeInputs inputs) {
            Object act = gNode.getData() != null ? gNode.getData().get("activate") : null;
            Collection<String> targets = new ArrayList<>();
            if (act instanceof Collection<?> c) {
                for (Object o : c) {
                    if (o != null) targets.add(String.valueOf(o));
                }
            } else if (act != null) {
                targets.add(String.valueOf(act));
            }
            Object in = inputs != null ? inputs.getInput(Object.class) : null;
            return NodeExecutionResult.success()
                    .setNextNodesToActivate(targets)
                    .addNodeOutput(DEFAULT_OUTPUT_PORT_NAME, new NodeOutput(in != null ? in : "branch"));
        }
    }

    public static class CollectingListener implements ExecutionListener {
        public final List<String> completedNodes = Collections.synchronizedList(new ArrayList<>());
        public final List<FlowExecutionResult> flowResults = Collections.synchronizedList(new ArrayList<>());
        public final AtomicInteger timedOut = new AtomicInteger(0);
        public final AtomicInteger paused = new AtomicInteger(0);
        public final CountDownLatch flowDone = new CountDownLatch(1);

        @Override public void onFlowStart() {}
        @Override
        public void onNodeCompleted(NodeExecutionResult result) {
            if (result != null && result.getNodeId() != null) {
                completedNodes.add(result.getNodeId());
            }
        }
        @Override
        public void onFlowCompleted(FlowExecutionResult result) {
            flowResults.add(result);
            flowDone.countDown();
        }
        @Override
        public void onFlowPaused(String executionId, String flowStateData) {
            paused.incrementAndGet();
        }
        @Override
        public void onFlowTimedOut(long timeoutMillis, String message) {
            timedOut.incrementAndGet();
        }

        public boolean await(long sec) throws InterruptedException {
            return flowDone.await(sec, TimeUnit.SECONDS);
        }
    }
}
