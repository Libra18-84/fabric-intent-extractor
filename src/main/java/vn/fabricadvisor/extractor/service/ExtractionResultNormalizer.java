package vn.fabricadvisor.extractor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class ExtractionResultNormalizer {
    private ExtractionResultNormalizer() {
    }

    static void normalize(ObjectNode result) {
        // Điền mặc định cho trường tùy chọn. advice_type vẫn do AI xác định.
        if (result.path("intent") instanceof ObjectNode intent) {
            fillArrays(intent, "fabric_types", "garment_types", "occasions");
        }
        if (optionalObject(result, "preferences") instanceof ObjectNode preferences) {
            fillArrays(preferences, "fabric_types", "characteristics");
            fillNulls(preferences, "price_level", "target_price");
        }
        if (optionalObject(result, "constraints") instanceof ObjectNode constraints) {
            fillArrays(constraints, "seasons", "required_fabric_types", "excluded_fabric_types",
                    "required_characteristics", "excluded_characteristics");
            fillNulls(constraints, "budget");

            if (constraints.path("budget") instanceof ObjectNode budget) {
                // Một cận có thể vắng mặt. Ngân sách vẫn phải có ít nhất một cận hợp lệ.
                fillNulls(budget, "lower", "upper");
            }
        }
        fillArrays(result, "unmapped_terms");
    }

    private static JsonNode optionalObject(ObjectNode parent, String field) {
        var value = parent.path(field);
        return isBlank(value) ? parent.putObject(field) : value;
    }

    private static void fillArrays(ObjectNode parent, String... fields) {
        for (String field : fields) {
            if (isBlank(parent.path(field))) {
                parent.putArray(field);
            }
        }
    }

    private static void fillNulls(ObjectNode parent, String... fields) {
        for (String field : fields) {
            if (isBlank(parent.path(field))) {
                parent.putNull(field);
            }
        }
    }

    private static boolean isBlank(JsonNode value) {
        return value.isMissingNode() || value.isNull()
                || (value.isTextual() && value.textValue().isBlank());
    }
}