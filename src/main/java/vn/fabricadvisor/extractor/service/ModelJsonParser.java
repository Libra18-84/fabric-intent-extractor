package vn.fabricadvisor.extractor.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.regex.Pattern;

final class ModelJsonParser {
    private static final Pattern REASONING_PREFIX = Pattern.compile("(?is)\\A<think>.*?</think>");
    private static final Pattern JSON_WRAPPER = Pattern.compile(
            "(?is)\\A(?:```[ \\t]*(?:json[ \\t]*)?\\R(?<fenced>.*?)\\R[ \\t]*```"
                    + "|<json>(?<tagged>.*?)</json>)\\z");

    private final ObjectReader reader;

    ModelJsonParser(ObjectMapper mapper) {
        this.reader = mapper.reader()
                .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature())
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    ObjectNode parse(String raw) throws IOException {
        if (raw == null || raw.isBlank()) {
            throw new IOException("AI trả về nội dung trích xuất rỗng.");
        }
        try {
            if (reader.readTree(unwrap(raw)) instanceof ObjectNode result) {
                return result;
            }
        } catch (JsonProcessingException error) {
            throw new IOException("AI phải trả đúng một đối tượng JSON hợp lệ.", error);
        }
        throw new IOException("AI phải trả đúng một đối tượng JSON hợp lệ.");
    }

    private static String unwrap(String raw) {
        String text = raw.strip();
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1).strip();
        }

        // Gỡ lớp bọc hoàn chỉnh. Phần còn lại phải qua parser JSON.
        while (true) {
            var reasoning = REASONING_PREFIX.matcher(text);
            if (reasoning.find()) {
                text = text.substring(reasoning.end()).strip();
                continue;
            }

            var wrapper = JSON_WRAPPER.matcher(text);
            if (!wrapper.matches()) {
                return text;
            }
            String fenced = wrapper.group("fenced");
            text = (fenced != null ? fenced : wrapper.group("tagged")).strip();
        }
    }
}