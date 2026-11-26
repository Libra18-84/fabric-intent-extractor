package vn.fabricadvisor.extractor.service;

import java.util.regex.Pattern;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import vn.fabricadvisor.extractor.ai.LanguageModel;
import vn.fabricadvisor.extractor.catalog.KeywordCatalog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class IntentExtractor {
    private static final int MAX_QUERY_LENGTH = 4_000;
    private static final Pattern SEWING_REQUEST = Pattern.compile(
            "(?iU)\\b(?:cần|muốn|tìm|chọn)\\s+(?:(?:mua|tìm|chọn|loại)\\s+)*vải\\b"
                    + "[^.!?;\\r\\n]*\\bđể\\s+may\\b");
    private static final Pattern FABRIC_QUALIFIER = Pattern.compile(
            "(?iU)%|\\b(?:chỉ|phải|bắt buộc|không|tránh|đừng|trừ|loại bỏ|hoặc|hay|thuần|nguyên chất"
                    + "|yêu cầu|duy nhất|thay vì|so sánh|so với|khác nhau|thông tin|đặc điểm|ưu nhược điểm|là gì)\\b");
    private static final Pattern FINAL_FABRIC_REQUIREMENT = Pattern.compile(
            "(?iU)\\b(?:chỉ\\s+(?:dùng|lấy|chọn)|(?:bắt buộc|phải)\\s+dùng)\\s+(.+?)\\s*$");
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
    private final ModelJsonParser jsonParser;
    private final LanguageModel model;
    private final KeywordCatalog catalog;
    private final String prompt;
    private final JsonNode outputSchema;
    private final Schema validator;

    public IntentExtractor(ObjectMapper mapper, LanguageModel model) throws IOException {
        this.mapper = mapper;
        this.jsonParser = new ModelJsonParser(mapper);
        this.model = model;
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
        var standaloneFabric = catalog.findStandaloneFabric(cleanedQuery);
        JsonNode requestSchema = schemaForMatches(matches);
        standaloneFabric.ifPresent(fabricId -> constrainFabricLookup(requestSchema, fabricId));
        PriceEvidence.constrainAbsentAmounts(requestSchema, cleanedQuery);
        Schema responseValidator = standaloneFabric.isPresent()
                ? SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(requestSchema)
                : validator;
        String instructions = prompt + "\nRetrieved keywords: " + mapper.writeValueAsString(matches);

        String raw = model.complete(instructions, cleanedQuery, requestSchema);
        try {
            return validateResponse(raw, cleanedQuery, matches, responseValidator);
        } catch (IOException firstError) {
            // Chỉ gửi yêu cầu sửa một lần khi Java phát hiện kết quả AI sai.
            String repairInstructions = instructions
                    + "\nValidation error: " + firstError.getMessage()
                    + "\nCorrect the result using the original request. Return only the JSON object.";
            JsonNode repairSchema = PriceEvidence.constrainRepair(requestSchema, cleanedQuery);
            if (firstError instanceof InvalidPriceResult invalidPrice) {
                repairInstructions += "\nCorrect the price fields. Keep all other fields fixed by the schema.";
                repairSchema = preserveNonPriceFields(repairSchema, invalidPrice.result);
            } else if (firstError instanceof InvalidUnmappedTerms invalidTerms) {
                repairInstructions += "\nUse only the verified unmapped_terms fixed by the schema.";
                repairSchema = preserveNonPriceFields(repairSchema, invalidTerms.result);
            }

            String corrected = model.complete(repairInstructions, cleanedQuery, repairSchema);
            var repaired = validateResponse(corrected, cleanedQuery, matches, responseValidator);
            if (firstError instanceof InvalidPriceResult invalidPrice) {
                return applyRepairedPrices(invalidPrice.result, repaired, cleanedQuery);
            }
            if (firstError instanceof InvalidUnmappedTerms invalidTerms) {
                invalidTerms.result.set("unmapped_terms", repaired.path("unmapped_terms"));
                return invalidTerms.result;
            }
            return repaired;
        }
    }

    private JsonNode validateResponse(
            String raw, String cleanedQuery, Map<String, Map<String, List<String>>> matches,
            Schema responseValidator) throws IOException {
        ObjectNode result = jsonParser.parse(raw);
        ExtractionResultNormalizer.normalize(result);

        var errors = responseValidator.validate(result);
        if (!errors.isEmpty()) {
            var firstError = errors.getFirst();
            throw new IOException("Kết quả AI sai JSON Schema tại " + firstError.getInstanceLocation()
                    + " (quy tắc " + firstError.getKeyword() + ").");
        }

        validateKeywords(result, matches);

        ObjectNode constraints = result.withObject("/constraints");
        rejectConflictingConstraints(constraints, "fabric_types");
        rejectConflictingConstraints(constraints, "characteristics");

        normalizeSewingRequest(result, cleanedQuery, matches);
        removeRepeatedFabricTypes(result);
        int invalidTerm = cleanUnmappedTerms(result, cleanedQuery, matches);
        try {
            normalizeAndValidateBudget(constraints);
            PriceEvidence.validate(result, cleanedQuery);
        } catch (IOException error) {
            throw new InvalidPriceResult(result, error);
        }
        if (invalidTerm >= 0) {
            throw new InvalidUnmappedTerms(result, invalidTerm);
        }
        return result;
    }

    private static JsonNode preserveNonPriceFields(JsonNode schema, ObjectNode previous) {
        JsonNode repair = schema.deepCopy();
        // Giữ các trường đã qua kiểm tra trong lần yêu cầu sửa giá.
        for (KeywordField field : KEYWORD_FIELDS) {
            var properties = (ObjectNode) repair.at("/properties/" + field.section() + "/properties");
            properties.putObject(field.name()).set("const",
                    previous.path(field.section()).path(field.name()));
        }
        ((ObjectNode) repair.at("/properties/intent/properties"))
                .putObject("advice_type").set("const", previous.at("/intent/advice_type"));
        ((ObjectNode) repair.path("properties"))
                .putObject("unmapped_terms").set("const", previous.path("unmapped_terms"));
        return repair;
    }

    private static JsonNode applyRepairedPrices(ObjectNode previous, JsonNode repaired, String query)
            throws IOException {
        // Chỉ áp dụng ba trường giá đã được kiểm tra từ phản hồi sửa.
        var preferences = previous.withObject("/preferences");
        preferences.set("price_level", repaired.at("/preferences/price_level"));
        preferences.set("target_price", repaired.at("/preferences/target_price"));
        previous.withObject("/constraints").set("budget", repaired.at("/constraints/budget"));
        PriceEvidence.validate(previous, query);
        return previous;
    }

    private static void constrainFabricLookup(JsonNode schema, String fabricId) {
        // Câu chỉ có tên vải được phân loại là tra thông tin về chất liệu đó.
        for (KeywordField field : KEYWORD_FIELDS) {
            var properties = (ObjectNode) schema.at("/properties/" + field.section() + "/properties");
            var values = properties.putObject(field.name()).putArray("const");
            if ("intent".equals(field.section()) && "fabric_types".equals(field.name())) {
                values.add(fabricId);
            }
        }
        ((ObjectNode) schema.at("/properties/intent/properties"))
                .putObject("advice_type").put("const", "ask_fabric_details");
        var preferences = (ObjectNode) schema.at("/properties/preferences/properties");
        preferences.putObject("price_level").putNull("const");
        preferences.putObject("target_price").putNull("const");
        ((ObjectNode) schema.at("/properties/constraints/properties"))
                .putObject("budget").putNull("const");
        ((ObjectNode) schema.at("/properties"))
                .putObject("unmapped_terms").putArray("const");
    }

    private JsonNode schemaForMatches(Map<String, Map<String, List<String>>> matches) {
        JsonNode schema = outputSchema.deepCopy();
// Bỏ pattern không tương thích với llama.cpp. Java vẫn kiểm tra bằng schema gốc.
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
    private static int cleanUnmappedTerms(
            JsonNode result, String query, Map<String, Map<String, List<String>>> matches) {
        var terms = (ArrayNode) result.path("unmapped_terms");
        int invalidTerm = -1;

        for (int index = terms.size() - 1; index >= 0; index--) {
            String term = terms.get(index).asText();
            if (isRepresentedAlias(result, term, matches)) {
                terms.remove(index);
            } else if (!KeywordCatalog.occursInRequest(query, term)) {
                invalidTerm = index;
                terms.remove(index);
            }
        }
        return invalidTerm;
    }

    private static boolean isRepresentedAlias(
            JsonNode result, String term, Map<String, Map<String, List<String>>> matches) {
        for (KeywordField field : KEYWORD_FIELDS) {
            var aliasesById = matches.getOrDefault(field.group(), Map.of());
            for (JsonNode id : result.path(field.section()).path(field.name())) {
                var aliases = aliasesById.getOrDefault(id.asText(), List.of());
                if (KeywordCatalog.isExactAlias(term, aliases)) {
                    return true;
                }

                // Bỏ cụm "để may" khi tên trang phục đã có mã trong kết quả.
                if ("garment_types".equals(field.group()) && KeywordCatalog.isExactAlias(
                        term, aliases.stream().map(alias -> "để may " + alias).toList())) {
                    return true;
                }
            }
        }
        return false;
    }
    private static void normalizeSewingRequest(
            ObjectNode result, String query, Map<String, Map<String, List<String>>> matches) {
        var fabrics = matches.getOrDefault("fabric_types", Map.of());
        var garments = matches.getOrDefault("garment_types", Map.of()).keySet();
        var constraints = result.withObject("/constraints");

        if (fabrics.size() != 1 || garments.size() != 1
                || !SEWING_REQUEST.matcher(query).find()
                || !constraints.path("excluded_fabric_types").isEmpty()) {
            return;
        }

        String fabric = fabrics.keySet().iterator().next();
        var requirement = FINAL_FABRIC_REQUIREMENT.matcher(query);
        boolean required = requirement.find()
                && KeywordCatalog.isExactAlias(requirement.group(1), fabrics.get(fabric));
        String context = required ? query.substring(0, requirement.start()) : query;
        if (FABRIC_QUALIFIER.matcher(context).find()) {
            return;
        }

        // Dùng vai trò của chất liệu trong câu gốc và mã đã tra từ danh mục.
        var intent = result.withObject("/intent");
        intent.put("advice_type", "find_fabric");
        intent.putArray("fabric_types");
        intent.putArray("garment_types").add(garments.iterator().next());
        var requiredFabrics = constraints.putArray("required_fabric_types");
        var preferredFabrics = result.withObject("/preferences").putArray("fabric_types");
        (required ? requiredFabrics : preferredFabrics).add(fabric);
    }
    private static void removeRepeatedFabricTypes(JsonNode result) {
        var classified = new HashSet<JsonNode>();
        result.at("/constraints/required_fabric_types").forEach(classified::add);

        // Chất liệu bắt buộc đã được biểu diễn trong constraints.
        var preferences = (ArrayNode) result.at("/preferences/fabric_types");
        removeMatches(preferences, classified);

        // Giữ loại vải được hỏi hoặc so sánh trong intent ở các chế độ tư vấn đó.
        if ("find_fabric".equals(result.at("/intent/advice_type").asText())) {
            preferences.forEach(classified::add);
            removeMatches((ArrayNode) result.at("/intent/fabric_types"), classified);
        }
    }

    private static void removeMatches(ArrayNode values, Set<JsonNode> matches) {
        for (int index = values.size() - 1; index >= 0; index--) {
            if (matches.contains(values.get(index))) {
                values.remove(index);
            }
        }
    }

    private static void rejectConflictingConstraints(JsonNode constraints, String group)
            throws IOException {
        var required = new HashSet<JsonNode>();
        constraints.path("required_" + group).forEach(required::add);

        // Cùng một mã ở hai danh sách sẽ tạo điều kiện SQL mâu thuẫn.
        for (JsonNode excluded : constraints.path("excluded_" + group)) {
            if (required.contains(excluded)) {
                throw new IOException(
                        "Ràng buộc bắt buộc và loại trừ mâu thuẫn ở nhóm " + group + ".");
            }
        }
    }

    private static void normalizeAndValidateBudget(ObjectNode constraints) throws IOException {
        JsonNode budget = constraints.path("budget");
        if (budget.isNull()) {
            return;
        }
        JsonNode lower = budget.path("lower");
        JsonNode upper = budget.path("upper");

        if (lower.isNull() && upper.isNull()) {
            // PriceEvidence đối chiếu câu gốc sau khi chuẩn hóa ngân sách rỗng.
            constraints.putNull("budget");
            return;
        }
        if (!lower.isNull() && !upper.isNull()) {
            int comparison = lower.path("amount").decimalValue()
                    .compareTo(upper.path("amount").decimalValue());

            if (comparison > 0 || (comparison == 0
                    && !(lower.path("inclusive").booleanValue() && upper.path("inclusive").booleanValue()))) {
                throw new IOException("Hai cận ngân sách tạo thành một khoảng giá rỗng.");
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
    private static final class InvalidUnmappedTerms extends IOException {
        private final ObjectNode result;

        private InvalidUnmappedTerms(ObjectNode result, int index) {
            super("unmapped_terms[" + index + "]: omit the phrase absent from the original request.");
            this.result = result;
        }
    }

    private static final class InvalidPriceResult extends IOException {
        private final ObjectNode result;

        private InvalidPriceResult(ObjectNode result, IOException cause) {
            super(cause.getMessage(), cause);
            this.result = result;
        }
    }
}
