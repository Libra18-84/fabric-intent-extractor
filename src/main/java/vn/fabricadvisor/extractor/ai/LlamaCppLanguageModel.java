package vn.fabricadvisor.extractor.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class LlamaCppLanguageModel implements LanguageModel {
    public static final String DEFAULT_BASE_URL = "http://127.0.0.1:8080";

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final String model;

    public LlamaCppLanguageModel(ObjectMapper mapper, String baseUrl, String model) {
        this.mapper = mapper;
        this.endpoint = chatEndpoint(baseUrl);
        this.model = model == null ? "" : model.strip();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public String complete(String instructions, String query, JsonNode outputSchema)
            throws IOException, InterruptedException {
        ObjectNode requestBody = mapper.createObjectNode();

        // Server chạy một model có thể dùng model đã nạp khi bỏ qua trường này.
        if (!model.isEmpty()) {
            requestBody.put("model", model);
        }
        requestBody.put("stream", false);
        requestBody.put("temperature", 0.0);
        requestBody.putObject("response_format")
                .put("type", "json_schema")
                .putObject("json_schema")
                .put("name", "fabric_extraction")
                .put("strict", true)
                .set("schema", outputSchema);

        var messages = requestBody.putArray("messages");
        messages.addObject().put("role", "system").put("content", instructions);
        messages.addObject().put("role", "user").put("content", query);

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMinutes(3))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(requestBody)))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException error) {
            throw new IOException("Không thể gọi llama-server tại " + endpoint
                    + ". Kiểm tra dịch vụ llama-server và LLAMA_BASE_URL.", error);
        }

        if (response.statusCode() == 404) {
            throw new IOException(
                    "llama-server trả HTTP 404. Kiểm tra LLAMA_BASE_URL và LLAMA_MODEL.");
        }
        if (response.statusCode() != 200) {
            throw new IOException("llama-server trả HTTP " + response.statusCode() + ".");
        }

        JsonNode payload;
        try {
            payload = mapper.readTree(response.body());
        } catch (IOException error) {
            throw new IOException("llama-server trả về phản hồi HTTP sai định dạng JSON.", error);
        }
        if (payload == null) {
            throw new IOException("llama-server trả phản hồi trống.");
        }

        JsonNode choice = payload.path("choices").path(0);
        if (!"stop".equals(choice.path("finish_reason").asText())) {
            throw new IOException("llama-server chưa hoàn tất kết quả trích xuất.");
        }

        JsonNode content = choice.path("message").path("content");
        if (!content.isTextual() || content.textValue().isBlank()) {
            throw new IOException("llama-server không trả về nội dung trích xuất.");
        }
        return content.textValue();
    }

    private static URI chatEndpoint(String baseUrl) {
        URI base;
        try {
            base = URI.create(baseUrl.strip());
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(
                    "LLAMA_BASE_URL phải là một URL HTTP hoặc HTTPS hợp lệ.", error);
        }
        if (!("http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme()))
                || base.getHost() == null || base.getRawQuery() != null || base.getRawFragment() != null
                || base.getRawUserInfo() != null) {
            throw new IllegalArgumentException(
                    "LLAMA_BASE_URL phải là URL gốc HTTP hoặc HTTPS hợp lệ.");
        }

        String root = base.toString().replaceFirst("/+$", "");
        String path = base.getRawPath().replaceFirst("/+$", "");
        return URI.create(root + (path.endsWith("/v1") ? "/chat/completions" : "/v1/chat/completions"));
    }
}