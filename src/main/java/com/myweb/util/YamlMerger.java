package com.myweb.util;

import java.io.File;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.nodes.*;
import org.yaml.snakeyaml.representer.Represent;
import org.yaml.snakeyaml.representer.Representer;

public class YamlMerger {

    /**
     * 将 source 中的相同配置项值合并到 target 中的同名配置项
     * @param source 来源配置
     * @param target 目标配置
     * @return 合并后的配置
     */
    public static String merge(String source, String target) {
        try {
            // 1. 必须开启注释功能的配置
            LoaderOptions loaderOptions = new LoaderOptions();
            loaderOptions.setProcessComments(true);

            DumperOptions dumperOptions = new DumperOptions();
            dumperOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            dumperOptions.setIndent(2);
            dumperOptions.setLineBreak(DumperOptions.LineBreak.UNIX);
            dumperOptions.setAllowUnicode(true);
            dumperOptions.setPrettyFlow(true);
            dumperOptions.setProcessComments(true);

            Yaml plainYaml = new Yaml();
            Yaml yamlWithComments = new Yaml(new Constructor(loaderOptions),
                new Representer(dumperOptions), dumperOptions, loaderOptions);

            // 2. source 只需要数据，用普通 Map 读取
            Map<String, Object> oldData = plainYaml.load(source);

            // 如果原始文件内容空, 则原样输出 b.yml 内容
            if (oldData == null || oldData.isEmpty()) {
                return target;
            }

            // 3. b.yml 需要保留注释，必须加载为底层 Node 树
            Node newRootNode = yamlWithComments.compose(new StringReader(target));  // compose 会保留注释和元数据

            // 4. 在 Node 树上直接用 a 的数据进行覆盖合并
            if (newRootNode instanceof MappingNode) {
                mergeDataIntoNode((MappingNode) newRootNode, oldData);
            }

            // 5. 输出最终合并后的（注释会被一同序列化）
            try (StringWriter w = new StringWriter()) {
                yamlWithComments.serialize(newRootNode, w);
                return w.toString();
            }

        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * Deep merges properties from the source map (old config) into the target map (new config template).
     * Preserves comments by substituting updated ScalarNodes into the existing Node structure.
     */
    @SuppressWarnings("unchecked")
    private static void mergeDataIntoNode(MappingNode targetNode, Map<String, Object> sourceMap) {
        // 因为 targetNode.getValue() 返回的 List 可能是不可变的，或者直接修改会引发并发修改异常，
        // 我们构建一个新的 List 来替换原有的元组列表。
        List<NodeTuple> updatedTuples = new ArrayList<>();

        for (NodeTuple tuple : targetNode.getValue()) {
            Node keyNode = tuple.getKeyNode();
            Node valueNode = tuple.getValueNode();

            if (keyNode instanceof ScalarNode) {
                String key = ((ScalarNode) keyNode).getValue();

                // 如果旧配置中包含当前新模板的 Key
                if (sourceMap.containsKey(key)) {
                    Object sourceValue = sourceMap.get(key);

                    if (valueNode instanceof MappingNode && sourceValue instanceof Map) {
                        // 1. 如果两边都是 Map，继续向下递归深度合并
                        mergeDataIntoNode((MappingNode) valueNode, (Map<String, Object>) sourceValue);
                        updatedTuples.add(tuple);
                    } else if (valueNode instanceof ScalarNode) {
                        // 2. 如果是叶子节点，我们需要用旧配置的值创建一个全新的 ScalarNode 来进行替换
                        ScalarNode oldScalarNode = (ScalarNode) valueNode;
                        String updatedValueStr = sourceValue == null ? "" : String.valueOf(sourceValue);

                        // 根据实际的值，动态决定使用什么 Tag
                        Tag actualTag = (sourceValue == null) ? Tag.NULL : Tag.STR;
                        // 如果原模板有特殊的 Tag（比如 !!str），且新值不为 null，可以优先沿用原模板的 Tag
                        if (sourceValue != null && oldScalarNode.getTag() != null && !oldScalarNode.getTag().equals(Tag.NULL)) {
                            actualTag = oldScalarNode.getTag();
                        }

                        // 创建新节点，完美继承旧节点的所有核心元数据（Tag, Style, 位置信息等）
                        ScalarNode newScalarNode = new ScalarNode(
                            actualTag,
                            updatedValueStr,
                            oldScalarNode.getStartMark(),
                            oldScalarNode.getEndMark(),
                            oldScalarNode.getScalarStyle()
                        );

                        // 【核心】将旧节点上绑定的所有注释，完美复制到新节点上
                        newScalarNode.setBlockComments(oldScalarNode.getBlockComments());
                        newScalarNode.setInLineComments(oldScalarNode.getInLineComments());
                        newScalarNode.setEndComments(oldScalarNode.getEndComments());

                        // 用全新的节点组合成新的键值对（Tuple）放入队列
                        updatedTuples.add(new NodeTuple(keyNode, newScalarNode));
                    } else {
                        // 其他类型（如 SequenceNode 数组），默认保留原样
                        updatedTuples.add(tuple);
                    }

                } else {
                    // 如果旧配置里没这个 Key，保留新模板的默认值和元组
                    updatedTuples.add(tuple);
                }

            } else {
                updatedTuples.add(tuple);
            }
        }

        // 重新将更新后的元组列表塞回当前的 MappingNode 节点中
        targetNode.setValue(updatedTuples);
    }

    /*private static void main2(String[] args) {
        if (args.length < 2) {
            System.err.println("Error: Missing required arguments for old and new configuration file paths.");
            System.out.println("Usage: java -jar yaml-merger.jar <old-yml-path> <new-yml-path> [output-path]");
            System.exit(1);
        }

        String oldPath = args[0];
        String newPath = args[1];
        // 如果没有指定第三个参数，默认直接覆盖更新到新版 b.yml 中
        String outputPath = args.length > 2 ? args[2] : newPath;

        try {
            Yaml yaml = new Yaml();

            // 1. 加载旧版配置 (a.yml)
            Map<String, Object> oldMap;
            try (InputStream oldStream = new FileInputStream(oldPath)) {
                oldMap = yaml.load(oldStream);
            }

            if (oldMap == null) {
                System.out.println(String.format("Warning: Old yaml file <%s> is empty. No merge required.", getSimpleName(oldPath)));
                return;
            }

            // 2. 加载新版配置 (b.yml)
            Map<String, Object> newMap;
            try (InputStream newStream = new FileInputStream(newPath)) {
                newMap = yaml.load(newStream);
            }
            if (newMap == null) {
                System.err.println(String.format("Error: New yaml file <%s> is empty. Cannot merge it.", getSimpleName(newPath)));
                System.exit(1);
            }

            // 3. 深度合并：将 a 的值更新到 b 的结构中
            deepMerge(oldMap, newMap);

            // 4. 配置 YAML 输出格式（保持可读性）
            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK); // 强制使用块状风格（标准缩进）
            options.setIndent(2);
            options.setLineBreak(DumperOptions.LineBreak.UNIX);
            options.setAllowUnicode(true);
            options.setPrettyFlow(true);
            options.setProcessComments(true);

            Yaml writerYaml = new Yaml(new NullRepresenter(options), options);

            // 5. 写回文件
            try (FileWriter writer = new FileWriter(outputPath)) {
                writerYaml.dump(newMap, writer);
            }

            System.out.println(String.format("Success: Merged active configurations from '%s' into '%s'.",
                    getSimpleName(oldPath), getSimpleName(outputPath)));

        } catch (IOException e) {
            System.err.println("I/O Error: Failed to read or write files: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Error: An unexpected error occurred during YAML parsing or merging: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }*/

    /**
     * 深度合并逻辑：将 source (旧配置) 的值，覆盖到 target (新配置) 中
     */
    @SuppressWarnings("unchecked")
    /*private static void deepMerge(Map<String, Object> source, Map<String, Object> target) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            Object sourceValue = entry.getValue();

            // 只有当新模板中也存在这个 Key 时才处理，忽略新版已废弃的配置
            if (target.containsKey(key)) {
                Object targetValue = target.get(key);

                if (sourceValue instanceof Map && targetValue instanceof Map) {
                    // 如果两边都是 Map，继续向下递归合并
                    deepMerge((Map<String, Object>) sourceValue, (Map<String, Object>) targetValue);
                } else {
                    target.put(key, sourceValue);
                }
            }
        }
    }*/

    private static String getSimpleName(String filePath) {
        String[] parts = filePath.split(File.separator);
        return parts[parts.length - 1];
    }

    static class NullRepresenter extends Representer {
        public NullRepresenter(DumperOptions options) {
            super(options);
            this.nullRepresenter = new RepresentNullToEmpty();
        }

        protected class RepresentNullToEmpty implements Represent {
            public Node representData(Object data) {
                return representScalar(Tag.NULL, "");
            }
        }
    }

}
