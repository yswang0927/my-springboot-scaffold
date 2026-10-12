package com.myweb.workflow;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 此节点上的输入。
 * <p>
 * 虽然每个节点执行时通常有独立的 NodeInputs 实例，但为了防御性编程和支持可能的并发访问场景，
 * 使用 ConcurrentHashMap 来保证线程安全。
 */
public class NodeInputs {
    /**
     * 一个端口上可能有多个输入
     * 一个节点可能有多个输入端口
     * 使用 ConcurrentHashMap 保证线程安全
     */
    private final ConcurrentMap<String, List<NodeOutput>> portInputs = new ConcurrentHashMap<>();

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
        if (inputPort == null || output == null) {
            return this;
        }
        // computeIfAbsent 保证原子性，使用 synchronizedList 保证列表的线程安全
        this.portInputs.computeIfAbsent(inputPort, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(output);
        return this;
    }

    /**
     * 获取指定输入端口上的数据
     * @param inputPort 输入端口
     * @return  数据列表的不可变视图
     */
    public List<NodeOutput> getAllInputs(String inputPort) {
        List<NodeOutput> list = this.portInputs.get(inputPort);
        if (list == null) {
            return Collections.emptyList();
        }
        // 返回不可变视图，防止外部修改
        return Collections.unmodifiableList(new ArrayList<>(list));
    }

    /**
     * 获取指定端口的第一个输入数据，并转为指定类型 (最常用场景)
     * @param inputPort 输入端口
     * @param type 数据类型
     */
    public <T> T getInput(String inputPort, Class<T> type) {
        List<NodeOutput> list = this.portInputs.get(inputPort);
        if (list == null || list.isEmpty()) {
            return null;
        }
        // 默认取第一个，因为大部分场景是 1对1 传递
        NodeOutput nodeOutput = list.get(0);
        return nodeOutput != null ? nodeOutput.getPayload(type) : null;
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
        List<NodeOutput> list = this.portInputs.get(inputPort);
        return list == null || list.isEmpty();
    }

}
