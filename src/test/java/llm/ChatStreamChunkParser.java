package llm;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 工业级流式内容解析器
 * 用于分离 LLM 输出中的思考过程（Reasoning/Think）与正文内容（Main Content）。
 * 支持处理流式传输中标签被截断的情况（如 "<th" 和 "ink>" 分在两个包中）。
 */
public class ChatStreamChunkParser {
    private static final String START_TAG = "<think>";
    private static final String END_TAG = "</think>";
    private static final String SSE_PREFIX = "data:";
    private static final String SSE_DONE = "[DONE]";
    // 缓冲区保留的最大长度，只需足够容纳最长的标签即可 (7个字符)
    private static final int MAX_BUFFER_KEEP = Math.max(START_TAG.length(), END_TAG.length());

    // 当前是否处于思考模式
    private volatile boolean isThinking = false;

    // 标记是否已经检测到过结束标签（用于防止重复关闭或处理异常流）
    private volatile boolean hasFinishedThinking = false;

    // 内部缓冲区，用于内容标签解析，支持跨 Chunk 的标签截断
    private StringBuilder deltaBuffer = new StringBuilder();
    // 用于 SSE 分行
    private StringBuilder lineBuffer = new StringBuilder();

    private final AtomicBoolean finished = new AtomicBoolean(false);

    // 允许多个思考片段
    private final boolean allowMultipleThinkingSegments;
    private Consumer<String> sseLineConsumer;
    private Consumer<String> thinkContentConsumer;
    private Consumer<String> mainContentConsumer;

    private final ObjectMapper jsonMapper = new ObjectMapper();

    public ChatStreamChunkParser() {
        this(false);
    }

    public ChatStreamChunkParser(boolean allowMultipleThinkingSegments) {
        this.allowMultipleThinkingSegments = allowMultipleThinkingSegments;
    }

    public void pushChunk(String text) {
        if (finished.get()) {
            return;
        }

        lineBuffer.append(text);

        // 优化策略：按 \n 切割比按 \n\n 切割延迟更低
        // 只要凑齐一行，就立刻处理
        int newLineIndex = 0;
        while ((newLineIndex = lineBuffer.indexOf("\n")) != -1) {
            String line = lineBuffer.substring(0, newLineIndex);
            lineBuffer.delete(0, newLineIndex + 1);

            processSseLine(line);
        }
    }

    private void processSseLine(String line) {
        if (sseLineConsumer != null) {
            sseLineConsumer.accept(line);
        }

        if (!line.startsWith(SSE_PREFIX)) {
            return;
        }

        String data = line.substring(SSE_PREFIX.length()).trim();
        if (data.equals(SSE_DONE)) {
            return;
        }

        // 提取 choices[0].delta.content 和 reasoning_content
        try {
            JsonNode node = jsonMapper.readTree(data);
            JsonNode choices = node.path("choices");
            if (choices == null || choices.size() == 0) {
                return;
            }

            JsonNode delta = choices.get(0).path("delta");
            if (delta == null) {
                return;
            }

            String reasoning = null;
            if (delta.has("reasoning_content")) {
                reasoning = delta.get("reasoning_content").asText();
            } else if (delta.has("reasoning")) {
                reasoning = delta.get("reasoning").asText();
            }

            // 场景 A: API 直接返回了 reasoning 字段 (OpenAI 格式的 DeepSeek)
            if (reasoning != null && !reasoning.isEmpty()) {
                appendThink(reasoning);
            }

            // 场景 B: API 返回包含 <think> 的 content (Ollama 常见行为)
            String content = delta.has("content") ? delta.get("content").asText() : "";
            // 或者 reasoning 为空时的普通正文
            if (content != null && !content.isEmpty()) {
                parseContentWithTags(content);
            }

        } catch (JsonProcessingException e) {
            // 容错：单行 JSON 解析失败不应中断整个流
        }
    }

    /**
     * 解析流式 Chunk
     *
     * @param deltaContent LLM 返回的文本片段 (delta content)
     */
    private void parseContentWithTags(String deltaContent) {
        // 1. 将新数据追加到缓冲区
        deltaBuffer.append(deltaContent);

        // 2. 循环处理缓冲区，直到没有完整的标签为止
        while (true) {
            if (isThinking) {
                // 当前在思考模式，寻找结束标签 </think>
                int endIndex = deltaBuffer.indexOf(END_TAG);
                if (endIndex != -1) {
                    // 找到了结束标签
                    // 截取标签前的内容追加到思考内容
                    String content = deltaBuffer.substring(0, endIndex);
                    appendThink(content);

                    // 状态切换：思考结束
                    isThinking = false;
                    hasFinishedThinking = true;

                    // 移除缓冲区中已处理的部分（包括标签本身）
                    deltaBuffer.delete(0, endIndex + END_TAG.length());
                    // 继续循环，处理剩余缓冲区可能存在的正文
                } else {
                    // 未找到结束标签，但需防止缓冲区无限增长
                    // 保留缓冲区末尾可能构成标签前缀的字符，其余确认为思考内容
                    handlePartialBuffer(true);
                    break; // 等待下一个 chunk
                }

            } else {
                // 如果已经结束过思考，就不再检测新的 <think> 标签
                // 这能防止正文中出现的 "<think>" 字符串误触发思考模式
                if (!allowMultipleThinkingSegments && hasFinishedThinking) {
                    // 既然思考已经结束，缓冲区剩余的所有内容都是正文
                    // 但我们需要保留最后几个字符以防 "split buffer" (虽然理论上这里不需要了，但为了统一逻辑可以保留，或者直接全刷)
                    // 为了简单和高性能，如果确认思考结束，可以直接把 buffer 全部追加到 mainContent
                    appendMain(deltaBuffer.toString());
                    deltaBuffer.setLength(0);
                    break; // 等待下一个 chunk
                }

                // 当前在正文模式（或初始状态），寻找开始标签 <think>
                // 注意：如果已经结束过思考，理论上不应再次进入（视模型而定，DeepSeek R1 通常只有一次）
                int startIndex = deltaBuffer.indexOf(START_TAG);
                if (startIndex != -1) {
                    // 找到了开始标签
                    // 截取标签前的内容追加到正文（可能是正文前的寒暄，虽少见）
                    appendMain(deltaBuffer.substring(0, startIndex));
                    // 状态切换：开始思考
                    isThinking = true;
                    // 移除缓冲区中已处理的部分
                    deltaBuffer.delete(0, startIndex + START_TAG.length());
                    // 继续循环
                } else {
                    // 未找到开始标签
                    handlePartialBuffer(false);
                    break; // 等待下一个 chunk
                }
            }
        }
    }

    /**
     * 处理缓冲区中未匹配完整标签的剩余部分
     * 策略：除了末尾可能构成标签的一部分字符外，前面的都可以安全flush
     * @param targetIsThink true表示目标是思考内容，false表示正文
     */
    private void handlePartialBuffer(boolean targetIsThink) {
        int length = deltaBuffer.length();
        if (length == 0) {
            return;
        }

        // 我们只需要保留可能形成标签的后缀
        // 例如：内容是 "abcde<thi"，我们必须保留 "<thi" 等待下一个包的 "nk>"
        // 如果内容是 "abcdefg"，且不包含标签头，理论上可以全刷，但为了安全保留最后几个字符

        // 只有当缓冲区长度超过标签最大长度时，才需要 Flush 前面的内容
        // 这样可以大幅减少 substring 操作，提升性能
        if (length > MAX_BUFFER_KEEP) {
            // 保留末尾 MAX_BUFFER_KEEP 个字符，把前面的都刷入内容
            int keepLength = MAX_BUFFER_KEEP;
            String safeContent = deltaBuffer.substring(0, length - keepLength);

            if (targetIsThink) {
                appendThink(safeContent);
            } else {
                appendMain(safeContent);
            }

            // 删除已 flush 的部分
            deltaBuffer.delete(0, length - keepLength);
        }
    }

    private void appendThink(String text) {
        if (this.thinkContentConsumer != null) {
            this.thinkContentConsumer.accept(text);
        }
    }

    private void appendMain(String text) {
        if (this.mainContentConsumer != null) {
            this.mainContentConsumer.accept(text);
        }
    }

    /**
     * 流传输结束时调用，将缓冲区剩余内容全部刷入当前状态
     */
    public void finish() {
        if (finished.compareAndSet(false, true)) {
            // 1. 处理 lineBuffer 剩余的残缺数据 (通常不应该有，除非流异常截断)
            if (lineBuffer.length() > 0) {
                processSseLine(lineBuffer.toString());
                lineBuffer.setLength(0);
            }

            // 2. 处理 deltaBuffer 剩余的内容
            if (deltaBuffer.length() > 0) {
                String remaining = deltaBuffer.toString();
                if (isThinking) {
                    // 如果流断了还在思考模式，则视为思考内容
                    appendThink(remaining);
                } else {
                    appendMain(remaining);
                }
                deltaBuffer.setLength(0);
            }
        }
    }

    public void onSSELine(Consumer<String> lineConsumer) {
        this.sseLineConsumer = lineConsumer;
    }

    public void onThink(Consumer<String> thinkConsumer) {
        this.thinkContentConsumer = thinkConsumer;
    }

    public void onMain(Consumer<String> mainConsumer) {
        this.mainContentConsumer = mainConsumer;
    }

}
