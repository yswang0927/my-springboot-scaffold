package com.myweb.workflow.graph;

import java.util.*;

public class GraphUtils {

    /**
     * “寻找指定节点的所有前驱节点及其路径” 或 “反向可达子图”
     *
     * @param graph 图数据
     * @param targetNodeId 指定的目标节点
     * @return 可达子图, 如果目标节点不存在, 则返回 null
     */
    public static Graph findSubgraphReachingTarget(Graph graph, String targetNodeId) {
        Objects.requireNonNull(graph);

        if (targetNodeId == null || targetNodeId.isEmpty()) {
            return graph;
        }

        List<GNode> nodes = graph.getNodes();
        List<GEdge> edges = graph.getEdges();

        if (nodes == null || nodes.isEmpty()) {
            return null;
        }

        if (edges == null || edges.isEmpty()) {
            GNode targetNode = null;
            for (GNode node : nodes) {
                if (targetNodeId.equals(node.getId())) {
                    targetNode = node;
                    break;
                }
            }
            if (targetNode == null) {
                return null;
            }
            return new Graph(Arrays.asList(targetNode), null);
        }

        // 构建反向邻接表: target -> [source1, source2, ...]
        Map<String, List<String>> reverseAdj = new HashMap<>();
        for (GEdge edge : edges) {
            if (!edge.isValidEdge()) {
                continue;
            }
            String source = edge.getSource();
            String target = edge.getTarget();
            if (!reverseAdj.containsKey(target)) {
                reverseAdj.put(target, new ArrayList<>());
            }
            reverseAdj.get(target).add(source);
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

            List<String> predecessors = reverseAdj.getOrDefault(nodeId, Collections.emptyList());
            for (String predecessor : predecessors) {
                if (!visited.contains(predecessor)) {
                    queue.add(predecessor);
                }
            }
        }

        List<GNode> subNodes = new ArrayList<>(visited.size());
        List<GEdge> subEdges = new ArrayList<>(visited.size());

        for (GNode node : nodes) {
            if (visited.contains(node.getId())) {
                subNodes.add(node);
            }
        }

        for (GEdge edge : edges) {
            if (visited.contains(edge.getSource()) && visited.contains(edge.getTarget())) {
                subEdges.add(edge);
            }
        }

        return new Graph(subNodes, subEdges);
    }

    /**
     * 提取可达目标节点的所有代码.
     *
     * @param graph 图数据
     * @param targetNodeId 目标节点ID
     * @return 按照可执行顺序提取所有代码组合在一起
     */
    public static String extractCodesReachingTarget(Graph graph, String targetNodeId) {
        Graph subgraph = findSubgraphReachingTarget(graph, targetNodeId);
        if (subgraph == null) {
            return "";
        }

        List<GNode> nodes = subgraph.getNodes();
        List<GEdge> edges = subgraph.getEdges();
        if (nodes.size() == 0) {
            return "";
        }

        // 对子图进行拓扑排序,来正确获取代码顺序
        Map<String, GNode> nodesMap = new HashMap<>(nodes.size());
        // 邻接表
        Map<String, List<String>> adjMap = new HashMap<>(edges.size());
        // 入度表
        Map<String, Integer> inDegree = new HashMap<>(nodes.size());

        for (GNode node : nodes) {
            nodesMap.put(node.getId(), node);
            adjMap.put(node.getId(), new ArrayList<>());
            inDegree.put(node.getId(), 0);
        }

        for (GEdge edge : edges) {
            String source = edge.getSource();
            String target = edge.getTarget();
            if (nodesMap.containsKey(source) && nodesMap.containsKey(target)) {
                adjMap.get(source).add(target);
                inDegree.put(target, inDegree.get(target) + 1);
            }
        }

        // Kahn 算法
        List<String> topoOrder = new ArrayList<>();
        Queue<String> zeroInDegree = new LinkedList<>();

        // 先找到所有入度为 0 的节点
        for (Map.Entry<String, Integer> deg : inDegree.entrySet()) {
            if (deg.getValue().intValue() == 0) {
                zeroInDegree.add(deg.getKey());
            }
        }

        while (zeroInDegree.size() > 0) {
            String nodeId = zeroInDegree.poll();
            topoOrder.add(nodeId);

            List<String> neighbors = adjMap.getOrDefault(nodeId, Collections.emptyList());
            for (String neighbor : neighbors) {
                int newInDegree = inDegree.get(neighbor) - 1;
                inDegree.put(neighbor, newInDegree);
                if (newInDegree == 0) {
                    zeroInDegree.add(neighbor);
                }
            }
        }

        // 按照拓扑排序顺序合并代码
        StringBuilder codes = new StringBuilder(topoOrder.size() * 128);
        for (String nodeId : topoOrder) {
            GNode node = nodesMap.get(nodeId);
            if (node == null) {
                continue;
            }
            if (GNodeType.CODE.name().equalsIgnoreCase(node.getType())) {
                Object code = node.getData().get("code");
                codes.append("// 节点 `").append(node.getId()).append("` 代码\n");
                if (code != null) {
                    codes.append(String.valueOf(code));
                    codes.append('\n').append('\n');
                }
            }
        }

        return codes.toString();
    }

}
