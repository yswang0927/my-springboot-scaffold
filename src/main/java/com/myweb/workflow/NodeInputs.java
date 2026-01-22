package com.myweb.workflow;

import java.util.*;

/**
 * 此节点上的输入
 * @author yswang
 */
public class NodeInputs {
    /**
     * 一个端口上可能有多个输入
     * 一个节点可能有多个输入端口
     */
    private Map<String, List<NodeOutput>> portInputs = new HashMap<>();

    public NodeInputs() {
    }

    NodeInputs addInput(String inputPort, NodeOutput output) {
        this.portInputs.computeIfAbsent(inputPort, k -> new ArrayList<>()).add(output);
        return this;
    }

    public List<NodeOutput> getInput(String inputPort) {
        return this.portInputs.getOrDefault(inputPort, Collections.emptyList());
    }

    public List<NodeOutput> getInput() {
        return this.getInput("input");
    }

    public boolean isEmpty() {
    	return this.portInputs.isEmpty();
    }

    public boolean isEmpty(String inputPort) {
        return this.portInputs.getOrDefault(inputPort, Collections.emptyList()).isEmpty();
    }

}
