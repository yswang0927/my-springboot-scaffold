package llm;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.util.function.Consumer;

/**
 * ByteBuffer流式增量解码器。
 * <pre>
 * 设计目标：
 * - 适用于 JDK HttpClient streaming (List<ByteBuffer>)
 * - 正确处理 UTF-8 字符被拆分到多个 ByteBuffer 的情况
 * - 每个响应流实例化一个，不可跨流复用
 * </pre>
 *
 * 使用方式：
 * <pre>
 *   ByteBufferDecoder decoder = new ByteBufferDecoder(chunk -> {
 *       parser.parseChunk(chunk);
 *   });
 *
 *   decoder.onBytes(byteBuffer);   // onNext
 *   decoder.finish();              // onComplete / onError
 * </pre>
 */
public final class ByteBufferDecoder {

    private static final int DEFAULT_CHAR_BUFFER_SIZE = 8 * 1024;

    private final CharsetDecoder decoder;
    private final CharBuffer charBuffer;
    private final Consumer<String> chunkConsumer;

    public ByteBufferDecoder(Consumer<String> chunkConsumer) {
        this(StandardCharsets.UTF_8, chunkConsumer, DEFAULT_CHAR_BUFFER_SIZE);
    }

    public ByteBufferDecoder(Charset charset, Consumer<String> chunkConsumer) {
        this(charset, chunkConsumer, DEFAULT_CHAR_BUFFER_SIZE);
    }

    public ByteBufferDecoder(Charset charset, Consumer<String> chunkConsumer, int charBufferSize) {
        this.chunkConsumer = chunkConsumer;

        // REPLACE 策略防止非法字节流导致崩坏，适合流式容错
        this.decoder = (charset != null ? charset : StandardCharsets.UTF_8)
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPLACE)
                            .onUnmappableCharacter(CodingErrorAction.REPLACE);

        this.charBuffer = CharBuffer.allocate(charBufferSize);
    }

    /**
     * 解码一个 ByteBuffer（通常来自 onNext）
     */
    public void onBytes(ByteBuffer byteBuffer) {
        if (byteBuffer == null || !byteBuffer.hasRemaining()) {
            return;
        }

        process(byteBuffer, false);
    }

    /**
     * 流结束时调用（onComplete / onError）
     */
    public void finish() {
        // 传入空 buffer 并标记 endOfInput=true，强制 flush 内部状态
        process(ByteBuffer.allocate(0), true);
    }

    private void process(ByteBuffer buffer, boolean endOfInput) {
        while (true) {
            // decode 方法会从 input 读取字节，写入 charBuffer
            CoderResult result = decoder.decode(buffer, charBuffer, endOfInput);

            // 无论结果如何，先将已解码的字符发出去
            if (charBuffer.position() > 0) {
                charBuffer.flip();
                if (charBuffer.hasRemaining()) {
                    emit(charBuffer);
                }
                charBuffer.clear();
            }

            // 如果是 UNDERFLOW (需要更多输入) 或其他状态，跳出等待新数据
            // 注意：finish 时如果 endOfInput 为 true，decode 会自动处理 flush逻辑
            if (!result.isOverflow()) {
                break;
            }
            // 如果是 OVERFLOW (输出缓冲区满了)，说明 input 还有数据，循环继续
        }
    }

    private void emit(CharBuffer buffer) {
        if (chunkConsumer != null) {
            chunkConsumer.accept(buffer.toString());
        }
    }

}

