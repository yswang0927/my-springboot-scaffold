package com.myweb.workflow.graph;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * DAG图
 * @author yswang
 */
public class Graph {
    private final ConcurrentMap<String, GNode> nodesMap = new ConcurrentHashMap<>();
    // 正向邻接表：fromNode -> [toNode1, toNode2, ...]
    private final ConcurrentMap<String, Set<String>> adjacencyList = new ConcurrentHashMap<>();
    // 反向邻接表：toNode -> [fromNode1, fromNode2, ...]
    private final ConcurrentMap<String, Set<String>> reverseAdjacencyList = new ConcurrentHashMap<>();
    // 初始化每个节点的入度 (原始入度，不可变)
    private final ConcurrentMap<String, Integer> nodeInDegree = new ConcurrentHashMap<>();
    // 目标节点 -> 指向它的输入端口信息，这个关系可以用于执行此节点前收集其依赖的所有输入
    private final ConcurrentMap<String, Set<GNodeInput>> targetNodeInputsMap = new ConcurrentHashMap<>();
    private volatile int maxParallelism = 1;

    private volatile boolean initialized = false;
    private final Object initLock = new Object();

    private List<GNode> nodes = new ArrayList<>();
    private List<GEdge> edges = new ArrayList<>();

    public Graph() {
    }

    public Graph(Collection<GNode> nodes, Collection<GEdge> edges) {
        setNodes(nodes);
        setEdges(edges);
    }

    // 用于接收其它属性
    @JsonAnySetter
    private Map<String, Object> dynamicProps = new LinkedHashMap<>();

    public List<GNode> getNodes() {
        return nodes;
    }

    public synchronized void setNodes(Collection<GNode> nodes) {
        if (this.initialized) {
            throw new UnsupportedOperationException("Graph is already initialized. Cannot modify nodes.");
        }

        this.nodes.clear();
        if (nodes != null) {
            for (GNode node : nodes) {
                if (node != null && node.isValidNode()) {
                    this.nodes.add(node);
                }
            }
        }
    }

    public List<GEdge> getEdges() {
        return edges;
    }

    public synchronized void setEdges(Collection<GEdge> edges) {
        if (this.initialized) {
            throw new UnsupportedOperationException("Graph is already initialized. Cannot modify nodes.");
        }

        this.edges.clear();
        if (edges != null) {
            for (GEdge edge : edges) {
                if (edge != null && edge.isValidEdge()) {
                    this.edges.add(edge);
                }
            }
        }
    }

    public void setDynamicProps(Map<String, Object> dynamicProps) {
        this.dynamicProps = dynamicProps;
    }

    @JsonAnyGetter
    public Map<String, Object> getDynamicProps() {
        return this.dynamicProps;
    }


    public void initialize() {
        if (this.initialized) {
            return;
        }

        synchronized (initLock) {
            if (this.initialized) {
                return;
            }

            if (this.nodes != null) {
                for (GNode node : nodes) {
                    this.nodesMap.put(node.getId(), node);
                    this.adjacencyList.put(node.getId(), new HashSet<>());
                    this.reverseAdjacencyList.put(node.getId(), new HashSet<>());
                    this.nodeInDegree.put(node.getId(), 0);
                }
            }

            if (edges != null) {
                // 构建邻接表和入度
                for (GEdge edge : edges) {
                    String from = edge.getSource();
                    String to = edge.getTarget();

                    if (this.nodesMap.containsKey(from) && this.nodesMap.containsKey(to)) {
                        final boolean isNewDependency = this.adjacencyList.get(from).add(to);
                        this.reverseAdjacencyList.get(to).add(from);

                        // 注意：只有当这是一个新的上游节点依赖时，才增加入度。
                        // 入度（InDegree）在拓扑排序中代表的是“有多少个前置节点尚未完成。
                        // 如果 A -> B 有多条边（比如连接不同的端口），入度也只能增加一次，入度采用和节点绑定，不和边绑定；
                        // 这样当 A 执行完毕，B 的入度减1，当入度变为0时，B 就会加入到可执行队列中。
                        if (isNewDependency) {
                            int newInDegree = this.nodeInDegree.get(to) + 1;
                            this.nodeInDegree.put(to, newInDegree);
                        }

                        // 连接到目标节点的输入
                        this.targetNodeInputsMap.computeIfAbsent(to, k -> new HashSet<>())
                                .add(new GNodeInput(from, edge.getSourceHandle(), edge.getTargetHandle()));
                    }
                }
            }

            this.analyzeGraph();

            // 初始化完后，将一些集合转换为不可变集合
            this.adjacencyList.replaceAll((k, v) -> Collections.unmodifiableSet(v));
            this.reverseAdjacencyList.replaceAll((k, v) -> Collections.unmodifiableSet(v));
            this.targetNodeInputsMap.replaceAll((k, v) -> Collections.unmodifiableSet(v));

            this.initialized = true;
        }
    }

    /**
     * 获取节点的下游节点
     * @param nodeId 节点ID
     * @return 下游节点ID列表
     */
    public Collection<String> getDownstreamNodes(String nodeId) {
        ensureInitialized();
        return this.adjacencyList.getOrDefault(nodeId, Collections.emptySet());
    }

    /**
     * 获取节点的上游节点
     * @param nodeId 节点ID
     * @return 上游节点ID列表
     */
    public Collection<String> getUpstreamNodes(String nodeId) {
        ensureInitialized();
        return this.reverseAdjacencyList.getOrDefault(nodeId, Collections.emptySet());
    }

    /**
     * 获取节点的上游节点的输入。
     * 一个节点可以有多个来自上游节点的输入，上游节点可能会有多个输出端口。
     * @param nodeId 节点ID
     * @return 节点的输入列表
     */
    public Collection<GNodeInput> getUpstreamNodeInputs(String nodeId) {
        ensureInitialized();
        return this.targetNodeInputsMap.getOrDefault(nodeId, Collections.emptySet());
    }

    /**
     * DAG图中可以并行执行节点的最大并行度
     * @return 最大并行度
     */
    public int getMaxParallelism() {
        ensureInitialized();
        return this.maxParallelism;
    }

    /**
     * 判断节点是否是叶子节点
     * @param nodeId 节点ID
     * @return true 表示是叶子节点；false 表示不是叶子节点
     */
    public boolean isLeafNode(String nodeId) {
        ensureInitialized();
        return this.adjacencyList.getOrDefault(nodeId, Collections.emptySet()).isEmpty();
    }

    /**
     * 判断节点是否是孤立的节点
     * @param nodeId 节点ID
     * @return true 表示是孤立的节点；false 否则
     */
    public boolean isIsolatedNode(String nodeId) {
        ensureInitialized();
        return this.adjacencyList.getOrDefault(nodeId, Collections.emptySet()).isEmpty()
                && this.reverseAdjacencyList.getOrDefault(nodeId, Collections.emptySet()).isEmpty();
    }

    /**
     * 获取孤立的节点
     * @return 孤立的节点ID列表
     */
    public List<String> getIsolatedNodes() {
        ensureInitialized();
        List<String> isolatedNodes = new ArrayList<>();
        for (String nodeId : this.nodesMap.keySet()) {
            if (this.isIsolatedNode(nodeId)) {
                isolatedNodes.add(nodeId);
            }
        }
        return isolatedNodes;
    }

    public Map<String, Integer> getNodesInDegree() {
        ensureInitialized();
        return Collections.unmodifiableMap(this.nodeInDegree);
    }

    /**
     * “寻找指定节点的所有前驱节点及其路径” 或 “反向可达子图”
     *
     * @param targetNodeId 指定的目标节点
     * @param includeTargetNode 是否包含目标节点
     * @return 可达子图, 如果目标节点不存在, 则返回 null
     */
    public Graph findSubGraphReachingTarget(String targetNodeId, boolean includeTargetNode) {
        ensureInitialized();

        if (targetNodeId == null || targetNodeId.isEmpty()) {
            return null;
        }

        if (this.nodes.isEmpty()) {
            return null;
        }

        if (this.edges.isEmpty()) {
            GNode targetNode = this.nodesMap.get(targetNodeId);
            if (targetNode == null) {
                return null;
            }
            // 如果不包含目标节点，则返回 null
            if (!includeTargetNode) {
                return null;
            }

            return new Graph(Arrays.asList(targetNode.clone()), null);
        }

        // 使用BFS反向遍历所有能够达到 targetNodeId 的节点
        Set<String> visited = new HashSet<>(nodes.size());
        Queue<String> queue = new LinkedList<>();
        queue.add(targetNodeId);

        while (queue.size() > 0) {
            String nodeId = queue.poll();
            if (visited.contains(nodeId)) {
                continue;
            }
            visited.add(nodeId);

            Set<String> upstreams = this.reverseAdjacencyList.getOrDefault(nodeId, Collections.emptySet());
            for (String up : upstreams) {
                if (!visited.contains(up)) {
                    queue.add(up);
                }
            }
        }

        List<GNode> subNodes = new ArrayList<>(visited.size());
        List<GEdge> subEdges = new ArrayList<>(visited.size());

        for (GNode node : this.nodes) {
            if (visited.contains(node.getId())) {
                // 如果不包含目标节点，则跳过目标节点
                if (!includeTargetNode && node.getId().equals(targetNodeId)) {
                    continue;
                }
                subNodes.add(node.clone());
            }
        }

        for (GEdge edge : this.edges) {
            if (visited.contains(edge.getSource()) && visited.contains(edge.getTarget())) {
                // 如果不包含目标节点，则跳过连接到目标节点的边
                if (!includeTargetNode && edge.getTarget().equals(targetNodeId)) {
                    continue;
                }
                subEdges.add(edge.clone());
            }
        }

        return new Graph(subNodes, subEdges);
    }

    /**
     * 确保在使用图数据前已初始化
     */
    private void ensureInitialized() {
        if (!this.initialized) {
            initialize();
        }
    }

    private void analyzeGraph() {
        Map<String, Integer> tempInDegree = new HashMap<>(this.nodeInDegree);
        Queue<String> queue = new LinkedList<>();
        // 初始化队列
        tempInDegree.forEach((nodeId, degree) -> {
            if (degree == 0) {
                queue.offer(nodeId);
            }
        });

        int visitedCount = 0;
        int currentMaxParallel = 0;

        while (!queue.isEmpty()) {
            int size = queue.size(); // 当前层的节点数，即当前并行度
            currentMaxParallel = Math.max(currentMaxParallel, size);

            // 处理这一层的所有节点
            for (int i = 0; i < size; i++) {
                String nodeId = queue.poll();
                visitedCount++;

                Set<String> neighbors = this.adjacencyList.get(nodeId);
                if (neighbors != null) {
                    for (String neighbor : neighbors) {
                        int newDegree = tempInDegree.get(neighbor) - 1;
                        tempInDegree.put(neighbor, newDegree);
                        if (newDegree == 0) {
                            queue.offer(neighbor);
                        }
                    }
                }
            }
        }

        if (visitedCount != this.nodes.size()) {
            throw new IllegalStateException("DAG图中存在环路，无法初始化");
        }

        // 如果图是空的或只有孤立点，确保 maxParallelism 至少为 1
        this.maxParallelism = Math.max(1, currentMaxParallel);
    }

    /**
     * 检测图中是否存在环（使用 Kahn 算法的变种）
     * @return false 表示无环；true 表示有环
     * @deprecated 被 {@link #analyzeGraph()} 替代
     */
    private boolean detectCycle() {
        // 拷贝入度，用于模拟拓扑排序
        Map<String, Integer> tempInDegree = new HashMap<>(this.nodeInDegree);
        Queue<String> queue = new LinkedList<>();
        // 所有入度为0的入队
        tempInDegree.forEach((nodeId, degree) -> {
            if (degree == 0) {
                queue.offer(nodeId);
            }
        });

        int visitedCount = 0;
        while (!queue.isEmpty()) {
            String nodeId = queue.poll();
            visitedCount++;
            // 遍历其所有后继节点
            Set<String> neighbors = this.adjacencyList.get(nodeId);
            if (neighbors != null) {
                for (String neighbor : neighbors) {
                    int newDegree = tempInDegree.get(neighbor) - 1;
                    tempInDegree.put(neighbor, newDegree);
                    if (newDegree == 0) {
                        queue.offer(neighbor);
                    }
                }
            }
        }

        // 如果访问节点数 < 总节点数，说明有环
        return visitedCount != this.nodes.size();
    }

    /**
     * 计算DAG图中可以并行执行节点的最大并行度.
     * @return 最大并行度
     * @deprecated 被 {@link #analyzeGraph()} 替代
     */
    private int calcMaxParallelism() {
        Map<String, Integer> tempInDegree = new HashMap<>(this.nodeInDegree);
        Queue<String> tempQueue = new LinkedList<>();
        // 所有入度为0的入队
        tempInDegree.forEach((nodeId, degree) -> {
            if (degree == 0) {
                tempQueue.offer(nodeId);
            }
        });

        int maxParallel = 0;

        while (!tempQueue.isEmpty()) {
            int size = tempQueue.size(); // 当前层的节点数
            maxParallel = Math.max(maxParallel, size);

            for (int i = 0; i < size; i++) {
                String nodeId = tempQueue.poll();
                for (String next : this.adjacencyList.get(nodeId)) {
                    tempInDegree.put(next, tempInDegree.get(next) - 1);
                    if (tempInDegree.get(next) == 0) {
                        tempQueue.offer(next);
                    }
                }
            }
        }

        return maxParallel;
    }

    @Override
    public String toString() {
        return "Graph{" +
                "nodes=" + nodes +
                ", edges=" + edges +
                '}';
    }

}
