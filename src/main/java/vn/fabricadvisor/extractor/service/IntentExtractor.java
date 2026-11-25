package vn.fabricadvisor.extractor.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import vn.fabricadvisor.extractor.ai.LanguageModel;
import vn.fabricadvisor.extractor.catalog.KeywordCatalog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public final class IntentExtractor {
    private static final int MAX_QUERY_LENGTH = 4_000;

    private static final List<KeywordField> KEYWORD_FIELDS = List.of(
            new KeywordField("intent", "fabric_types", "fabric_types"),
            new KeywordField("intent", "garment_types", "garment_types"),
            new KeywordField("intent", "occasions", "occasions"),
            new KeywordField("preferences", "fabric_types", "fabric_types"),
            new KeywordField("preferences", "characteristics", "characteristics"),
            new KeywordField("constraints", "seasons", "seasons"),
            new KeywordField("constraints", "required_fabric_types", "fabric_types"),
            new KeywordField("constraints", "excluded_fabric_types", "fabric_types"),
            new KeywordField("constraints", "required_characteristics", "characteristics"),
            new KeywordField("constraints", "excluded_characteristics", "characteristics")
    );

    private final ObjectMapper mapper;
    private final LanguageModel model;
    private final KeywordCatalog catalog;
    private final String prompt;
    private final JsonNode outputSchema;
    private final Schema validator;
    private final ObjectReader modelResponseReader;

    public IntentExtractor(ObjectMapper mapper, LanguageModel model) throws IOException {
        this.mapper = mapper;
        this.model = model;
        // Kiểm tra phản hồi model cả khi caller dùng ObjectMapper với cấu hình mặc định.
        this.modelResponseReader = mapper.reader()
                .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature())
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        try (var catalogFile = resource("/catalog/fabric-keywords.json");
             var promptFile = resource("/prompts/extract-intent.txt");
             var schemaFile = resource("/schema/extraction-result.schema.json")) {
            this.catalog = new KeywordCatalog(mapper, catalogFile);
            this.prompt = new String(promptFile.readAllBytes(), StandardCharsets.UTF_8);
            this.outputSchema = mapper.readTree(schemaFile);
        }
        this.validator = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(outputSchema);
    }

    public JsonNode extract(String query) throws IOException, InterruptedException {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Hãy nhập một câu cần phân tích.");
        }
        String cleanedQuery = query.strip();
        if (cleanedQuery.codePointCount(0, cleanedQuery.length()) > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("Câu nhập vượt quá " + MAX_QUERY_LENGTH + " ký tự.");
        }

        var matches = catalog.retrieve(cleanedQuery);
        JsonNode requestSchema = schemaForMatches(matches);
        String instructions = prompt
                + "\nTừ khóa tra được theo nhóm:\n" + mapper.writeValueAsString(matches);

        String raw = model.complete(instructions, cleanedQuery, requestSchema);
        if (raw == null || raw.isBlank()) {
            throw new IOException("AI trả về nội dung trích xuất rỗng.");
        }

        JsonNode result;
        try {
            result = modelResponseReader.readTree(raw);
        } catch (JsonProcessingException error) {
            throw new IOException("AI trả về nội dung không phải một đối tượng JSON hợp lệ.", error);
        }

        var errors = validator.validate(result);
        if (!errors.isEmpty()) {
            var firstError = errors.getFirst();
            throw new IOException("Kết quả AI sai JSON Schema tại " + firstError.getInstanceLocation()
                    + " (quy tắc " + firstError.getKeyword() + ").");
        }

        validateKeywords(result, matches);

        ExtractionResultNormalizer.normalize(result, cleanedQuery);
        return result;
    }

    private JsonNode schemaForMatches(Map<String, Map<String, List<String>>> matches) {
        JsonNode schema = outputSchema.deepCopy();
        // llama.cpp yêu cầu pattern có neo. Schema gốc vẫn kiểm tra mục chỉ có khoảng trắng.
        ((ObjectNode) schema.at("/properties/unmapped_terms/items")).remove("pattern");
        // Giới hạn lựa chọn của model bằng các mã vừa tra được trong câu nhập.
        for (KeywordField field : KEYWORD_FIELDS) {
            var allowed = matches.getOrDefault(field.group(), Map.of()).keySet();
            var section = (ObjectNode) schema.path("properties").path(field.section()).path("properties");
            var fieldSchema = section.putObject(field.name());

            if (allowed.isEmpty()) {
                fieldSchema.putArray("const");
            } else {
                // llama-server có thể bỏ qua uniqueItems khi tạo quy tắc sinh JSON.
                fieldSchema.put("type", "array").put("uniqueItems", true).put("maxItems", allowed.size());
                var choices = fieldSchema.putObject("items").put("type", "string").putArray("enum");
                allowed.forEach(choices::add);
            }
        }
        return schema;
    }

    private static void validateKeywords(JsonNode result, Map<String, Map<String, List<String>>> matches)
            throws IOException {
        for (KeywordField field : KEYWORD_FIELDS) {
            var allowed = matches.getOrDefault(field.group(), Map.of()).keySet();
            for (JsonNode keyword : result.path(field.section()).path(field.name())) {
                if (!allowed.contains(keyword.textValue())) {
                    throw new IOException(
                            "AI dùng mã từ khóa không có trong kết quả tra cứu ở trường " + field.name() + ".");
                }
            }
        }
    }

    private static InputStream resource(String path) throws IOException {
        InputStream input = IntentExtractor.class.getResourceAsStream(path);
        if (input == null) {
            throw new IOException("Thiếu tài nguyên ứng dụng: " + path);
        }
        return input;
    }

    private record KeywordField(String section, String name, String group) {
    }
}
