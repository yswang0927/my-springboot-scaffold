package com.myweb.workflow.graph;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public class GNode implements Cloneable {

    public static final String DEFAULT_INPUT_PORT_NAME = "input";
    public static final String DEFAULT_OUTPUT_PORT_NAME = "output";

    /**
     * 节点ID
     */
    private String id;
    /**
     * 节点类型
     */
    private String type;
    /**
     * 节点数据
     */
    private Map<String, Object> data = new HashMap<>();

    // 用于接收其它附属属性(比如：position,width,height等)
    @JsonAnySetter
    private Map<String, Object> dynamicProps = new LinkedHashMap<>();

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public void setData(Map<String, Object> data) {
        this.data = data;
    }

    @JsonAnyGetter
    public Map<String, Object> getDynamicProps() {
        return dynamicProps;
    }

    public void setDynamicProps(Map<String, Object> dynamicProps) {
        this.dynamicProps = dynamicProps;
    }

    public boolean isValidNode() {
        return StringUtils.hasText(id);
    }

    public GNode clone() {
        GNode cloned = new GNode();
        cloned.setId(id);
        cloned.setType(type);
        if (data != null) {
            cloned.setData(new HashMap<>(data));
        }
        cloned.setDynamicProps(new LinkedHashMap<>(dynamicProps));
        return cloned;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof GNode oNode)) {
            return false;
        }
        return Objects.equals(id, oNode.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "GNode{" +
                "id='" + id + '\'' +
                ", type='" + type + '\'' +
                ", data=" + data +
                '}';
    }

}
