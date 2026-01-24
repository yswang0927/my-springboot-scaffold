package com.myweb.workflow;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 节点输出
 */
public class NodeOutput {
    // 输出的数据
    private Object payload;

    public NodeOutput() {}

    public NodeOutput(Object outputPayload) {
        this.payload = outputPayload;
    }

    /**
     * 获取原始数据
     */
    public Object getPayload() {
        return payload;
    }

    public void setPayload(Object payload) {
        this.payload = payload;
    }

    /**
     * 提供类型安全的获取方式
     * @param type 目标类型的 Class
     * @param <T> 泛型
     * @return 转换后的数据
     * @throws ClassCastException 如果类型不匹配
     */
    @JsonIgnore
    public <T> T getPayload(Class<T> type) {
        if (payload == null) {
            return null;
        }

        if (type.isInstance(payload)) {
            return type.cast(payload);
        }

        // TODO 这里可以扩展：如果类型不匹配，尝试使用 Jackson 或其他工具进行转换
        // 例如：payload 是 Map，用户想转成 User 对象
        // return convert(payload, type);

        throw new ClassCastException("NodeOutput payload is type of " +
                payload.getClass().getName() + ", but requested " + type.getName());
    }

    /**
     * 安全获取，带默认值
     */
    public <T> T getPayload(Class<T> type, T defaultValue) {
        try {
            T val = getPayload(type);
            return val != null ? val : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    @Override
    public String toString() {
        return "NodeOutput{" +
                "outputText='" + payload + '\'' +
                '}';
    }

}
