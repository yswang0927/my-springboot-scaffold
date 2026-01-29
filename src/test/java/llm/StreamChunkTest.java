package llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Flow;

public class StreamChunkTest {
    public static void main(String[] args) {
        String targetUrl = "http://127.0.0.1:11434/v1/chat/completions";
        String jsonBody = """
                {
                  "model": "deepseek-r1:7b",
                  "messages": [
                    { "role": "user", "content": "请解释一下什么是量子纠缠，并思考一下如何向小学生解释。" }
                  ],
                  "stream": true
                }
                """;

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(targetUrl))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream") // 重要：告知服务端需流式返回
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        System.out.println("开始请求 LLM...\n");

        client.sendAsync(request, new StreamingBodyHandler()).join();

    }

    /**
     * BodyHandler：把响应体转换为 Publisher<List<ByteBuffer>>
     */
    static class StreamingBodyHandler implements HttpResponse.BodyHandler<Void> {
        @Override
        public HttpResponse.BodySubscriber<Void> apply(HttpResponse.ResponseInfo info) {
            return HttpResponse.BodySubscribers.fromSubscriber(
                    new StreamingSubscriber(),
                    s -> null
            );
        }
    }

    /**
     * 真正处理流式 ByteBuffer 的 Subscriber
     */
    static class StreamingSubscriber implements Flow.Subscriber<List<ByteBuffer>> {

        private Flow.Subscription subscription;

        private final ChatStreamChunkParser chunkParser = new ChatStreamChunkParser();
        private final ByteBufferDecoder decoder = new ByteBufferDecoder(chunkParser::pushChunk);

        StreamingSubscriber() {
            chunkParser.onSSELine(line -> {
                System.out.println(line);
            });
            chunkParser.onThink(chunk -> {
                System.out.print("\u001B[33m" + chunk + "\u001B[0m"); // 黄色输出思考
            });

            chunkParser.onMain(chunk -> {
                System.out.print("\u001B[32m" + chunk + "\u001B[0m"); // 绿色输出正文
            });
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            this.subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                decoder.onBytes(buffer);
            }

            this.subscription.request(1);
        }

        @Override
        public void onComplete() {
            decoder.finish();
            chunkParser.finish();
        }

        @Override
        public void onError(Throwable throwable) {
            decoder.finish();
            chunkParser.finish();
        }

    }

}
