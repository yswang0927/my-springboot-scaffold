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

    /**
     * 在输入端口上添加前置依赖节点的输出数据
     *
     * @param inputPort 端口
     * @param output 前置依赖节点的输出
     * @return this
     */
    NodeInputs addInput(String inputPort, NodeOutput output) {
        this.portInputs.computeIfAbsent(inputPort, k -> new ArrayList<>()).add(output);
        return this;
    }

    /**
     * 获取指定输入端口上的数据
     * @param inputPort 输入端口
     * @return  数据
     */
    public List<NodeOutput> getInput(String inputPort) {
        return this.portInputs.getOrDefault(inputPort, Collections.emptyList());
    }

    /**
     * 从默认名为 "input" 的输入端口上获取数据
     * @return  数据
     */
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
