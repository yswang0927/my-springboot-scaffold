package com.myweb.workflow;

import java.util.Map;

/**
 * 节点输出
 */
public class NodeOutput {
    private String outputText;
    private Map<String, Object> outputParsed;

    public NodeOutput() {}

    public NodeOutput(String outputText) {
        this.outputText = outputText;
    }

    public String getOutputText() {
        return outputText;
    }

    public NodeOutput setOutputText(String outputText) {
        this.outputText = outputText;
        // TODO JSON parse outputText to Map
        return this;
    }

    public Map<String, Object> getOutputParsed() {
        return outputParsed;
    }

    @Override
    public String toString() {
        return "NodeOutput{" +
                "outputText='" + outputText + '\'' +
                '}';
    }
}
