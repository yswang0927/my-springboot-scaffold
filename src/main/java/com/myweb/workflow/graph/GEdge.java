package com.myweb.workflow.graph;

import com.fasterxml.jackson.annotation.JsonAlias;
import org.springframework.util.StringUtils;

import java.util.Objects;

public class GEdge {
    /**
     * 边ID
     */
    private String id;
    /**
     * 源节点ID
     */
    private String source;
    /**
     * 目标节点ID
     */
    private String target;
    /**
     * 源节点的输出端口(如果源节点只有一个输出端口,则默认名称为output)
     */
    @JsonAlias({"sourceHandle", "source_handle", "sourcePort", "source_port"})
    private String sourceHandle = GNode.DEFAULT_OUTPUT_PORT_NAME;
    /**
     * 目标节点的输入端口(如果目标节点只有一个输入端口,则默认名称为input)
     */
    @JsonAlias({"targetHandle", "target_handle", "targetPort", "target_port"})
    private String targetHandle = GNode.DEFAULT_INPUT_PORT_NAME;

    public GEdge() {}

    public GEdge(String id, String source, String target) {
        this.id = id;
        this.source = source;
        this.target = target;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public String getSourceHandle() {
        return sourceHandle;
    }

    public void setSourceHandle(String sourceHandle) {
        if (sourceHandle != null && !sourceHandle.isEmpty()) {
            this.sourceHandle = sourceHandle;
        }
    }

    public String getTargetHandle() {
        return targetHandle;
    }

    public void setTargetHandle(String targetHandle) {
        if (targetHandle != null && !targetHandle.isEmpty()) {
            this.targetHandle = targetHandle;
        }
    }

    public boolean isValidEdge() {
        return StringUtils.hasText(id) && StringUtils.hasText(source) && StringUtils.hasText(target);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof GEdge gEdge)) {
            return false;
        }
        return Objects.equals(source, gEdge.source)
                && Objects.equals(target, gEdge.target)
                && Objects.equals(sourceHandle, gEdge.sourceHandle)
                && Objects.equals(targetHandle, gEdge.targetHandle);
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, target, sourceHandle, targetHandle);
    }

    @Override
    public String toString() {
        return "GEdge{" +
                "id='" + id + '\'' +
                ", source='" + source + '\'' +
                ", target='" + target + '\'' +
                ", sourceHandle='" + sourceHandle + '\'' +
                ", targetHandle='" + targetHandle + '\'' +
                '}';
    }
}
