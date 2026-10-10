package com.myweb.workflow.nodes;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myweb.workflow.ExecutionContext;
import com.myweb.workflow.NodeExecutionResult;
import com.myweb.workflow.NodeInputs;
import com.myweb.workflow.NodeOutput;
import com.myweb.workflow.graph.GNode;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * HTTP 请求节点：基于 OkHttp 发起真实请求，将响应体解析后写入默认输出端口。
 * <p>
 * 期望的 {@code GNode.data} 结构（除 url 外均可选）：
 * <pre>
 * {
 *   "url": "https://api.example.com/x",
 *   "method": "POST",                 // 默认 GET
 *   "headers": { "Authorization": "..." },
 *   "body": "原始请求体(字符串)",
 *   "jsonBody": { ... },              // 或结构化对象, 序列化为 JSON
 *   "contentType": "application/json; charset=utf-8",
 *   "timeoutMillis": 15000            // 连接/读取超时
 * }
 * </pre>
 * POST/PUT/PATCH 且未显式提供 body 时，会将默认输入端口上的数据序列化为请求体。
 * 非 2xx 视为失败（走引擎默认重试策略）。
 */
public class HttpNode extends AbstractNode {

    private static final Logger LOG = LoggerFactory.getLogger(HttpNode.class);

    public static final String NODE_TYPE = "http";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // 共享基础客户端；per-node 超时可基于它 newBuilder（复用连接池/线程池，代价很小）
    private static final OkHttpClient BASE_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    private final Map<String, Object> httpData;

    public HttpNode(GNode gNode) {
        super(gNode);
        this.httpData = gNode.getData();
    }

    @Override
    public String getType() {
        return NODE_TYPE;
    }

    @Override
    public NodeExecutionResult call(ExecutionContext context, NodeInputs inputs) throws Exception {
        String url = httpData == null ? null : asString(httpData.get("url"));
        if (url == null || url.isBlank()) {
            return NodeExecutionResult.failed("HttpNode url is empty (node id: " + getId() + ")");
        }

        String method = asString(httpData.get("method"));
        method = (method == null || method.isBlank()) ? "GET" : method.trim().toUpperCase(Locale.ROOT);

        MediaType mediaType = MediaType.parse(orDefault(asString(httpData.get("contentType")),
                "application/json; charset=utf-8"));

        boolean bodyAllowed = "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
        String bodyStr = resolveBody(inputs, bodyAllowed);
        if (bodyStr == null && ("POST".equals(method) || "PUT".equals(method))) {
            bodyStr = ""; // OkHttp 要求 POST/PUT 必须有 body
        }
        RequestBody requestBody = (bodyAllowed && bodyStr != null)
                ? RequestBody.create(mediaType, bodyStr) : null;

        Request.Builder builder = new Request.Builder().url(url).method(method, requestBody);
        Object headersObj = httpData.get("headers");
        if (headersObj instanceof Map<?, ?> headers) {
            for (Map.Entry<?, ?> e : headers.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    builder.addHeader(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }
        }

        OkHttpClient client = buildClient();
        Request request = builder.build();

        try (Response response = client.newCall(request).execute()) {
            ResponseBody body = response.body();
            String respText = body != null ? body.string() : "";
            int code = response.code();

            if (!response.isSuccessful()) {
                LOG.warn(">> HttpNode <{}> {} {} -> HTTP {} : {}", getId(), method, url, code, abbreviate(respText));
                return NodeExecutionResult.failed("HTTP " + code + ": " + abbreviate(respText));
            }

            Object parsed = parseResponse(respText, response);
            NodeExecutionResult result = NodeExecutionResult.success()
                    .addNodeOutput(DEFAULT_OUTPUT_PORT_NAME, new NodeOutput(parsed));
            result.addNodeOutput("status", new NodeOutput(code));
            return result;
        }
    }

    private OkHttpClient buildClient() {
        Object timeoutObj = httpData == null ? null : httpData.get("timeoutMillis");
        long timeout = asLong(timeoutObj, -1L);
        if (timeout <= 0) {
            return BASE_CLIENT;
        }
        return BASE_CLIENT.newBuilder()
                .connectTimeout(timeout, TimeUnit.MILLISECONDS)
                .readTimeout(timeout, TimeUnit.MILLISECONDS)
                .writeTimeout(timeout, TimeUnit.MILLISECONDS)
                .build();
    }

    /**
     * 解析请求体：优先 data.body(字符串) -> data.jsonBody(结构化) -> 输入端口数据。
     */
    private String resolveBody(NodeInputs inputs, boolean bodyAllowed) throws Exception {
        Object raw = httpData.get("body");
        if (raw != null) {
            return raw instanceof String ? (String) raw : MAPPER.writeValueAsString(raw);
        }
        Object jsonBody = httpData.get("jsonBody");
        if (jsonBody != null) {
            return MAPPER.writeValueAsString(jsonBody);
        }
        if (bodyAllowed && inputs != null) {
            Object inputPayload = inputs.getInput(DEFAULT_INPUT_PORT_NAME, Object.class);
            if (inputPayload != null) {
                return inputPayload instanceof String
                        ? (String) inputPayload : MAPPER.writeValueAsString(inputPayload);
            }
        }
        return null;
    }

    /**
     * 解析响应体：Content-Type 含 json（或内容看似 JSON）则反序列化为 Map/List，否则返回原始字符串。
     */
    private Object parseResponse(String respText, Response response) {
        if (respText == null || respText.isEmpty()) {
            return null;
        }
        String contentType = response.header("Content-Type", "");
        boolean looksJson = contentType != null && contentType.toLowerCase(Locale.ROOT).contains("json");
        char first = respText.charAt(0);
        if (looksJson || first == '{' || first == '[') {
            try {
                return MAPPER.readValue(respText, Object.class);
            } catch (Exception ignore) {
                // 解析失败则退回原始文本
            }
        }
        return respText;
    }

    private static String orDefault(String v, String def) {
        return (v == null || v.isBlank()) ? def : v;
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static long asLong(Object o, long def) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        if (o instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignore) {
                return def;
            }
        }
        return def;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 200 ? s : s.substring(0, 200) + "...(truncated)";
    }

}
