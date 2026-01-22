package com.myweb.workflow.graph;

import java.util.Objects;

/**
 * 节点的输入信息采集
 */
public class GNodeInput {
    private final String sourceNodeId;
    // 源节点的输出端口
    private final String sourcePort;
    // 目标节点的输入端口
    private final String targetPort;

    GNodeInput(String sourceNodeId, String sourcePort, String targetPort) {
        this.sourceNodeId = sourceNodeId;
        this.sourcePort = sourcePort;
        this.targetPort = targetPort;
    }

    public String getSourceNodeId() {
        return sourceNodeId;
    }

    public String getSourcePort() {
        return sourcePort;
    }

    public String getTargetPort() {
        return targetPort;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof GNodeInput gNodePort)) {
            return false;
        }
        return Objects.equals(sourceNodeId, gNodePort.sourceNodeId)
                && Objects.equals(sourcePort, gNodePort.sourcePort)
                && Objects.equals(targetPort, gNodePort.targetPort);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceNodeId, sourcePort, targetPort);
    }

}
