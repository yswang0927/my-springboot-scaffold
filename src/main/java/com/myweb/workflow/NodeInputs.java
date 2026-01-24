package com.myweb.workflow;

import java.util.*;

/**
 * 此节点上的输入
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
    public List<NodeOutput> getAllInputs(String inputPort) {
        return this.portInputs.getOrDefault(inputPort, Collections.emptyList());
    }

    /**
     * 获取指定端口的第一个输入数据，并转为指定类型 (最常用场景)
     * @param inputPort 输入端口
     * @param type 数据类型
     */
    public <T> T getInput(String inputPort, Class<T> type) {
        List<NodeOutput> outputs = getAllInputs(inputPort);
        if (outputs.isEmpty()) {
            return null;
        }
        // 默认取第一个，因为大部分场景是 1对1 传递
        return outputs.get(0).getPayload(type);
    }

    /**
     * 默认端口 "input" 获取数据
     */
    public <T> T getInput(Class<T> type) {
        return getInput(TaskNode.DEFAULT_INPUT_PORT_NAME, type);
    }

    public boolean isEmpty() {
    	return this.portInputs.isEmpty();
    }

    public boolean isEmpty(String inputPort) {
        return this.portInputs.getOrDefault(inputPort, Collections.emptyList()).isEmpty();
    }

}
