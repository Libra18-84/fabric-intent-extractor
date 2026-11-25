package vn.fabricadvisor.extractor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Kiểm tra và chuẩn hóa các trường liên quan trước khi chuyển kết quả cho tầng truy vấn. */
final class ExtractionResultNormalizer {
    private static final String LIMIT_WORDS = "(?:dưới|ít hơn|thấp hơn|tối đa|không quá)";
    private static final String NUMBER = "\\d+(?:[.,]\\d+)*";
    private static final String MONEY_UNIT = "(?:\\s*(?:k|nghìn|ngàn|triệu|đồng|vnd|usd|eur|đ)\\b|\\s*₫)";
    private static final Pattern UPPER_BUDGET_CUE = Pattern.compile(
            "(?iU)\\b(?:(?:giá|ngân sách|chi phí)\\s+" + LIMIT_WORDS + "\\s+\\d"
                    + "|" + LIMIT_WORDS + "\\s+" + NUMBER + MONEY_UNIT + ")");
    private static final Pattern TARGET_PRICE_CUE = Pattern.compile(
            "(?iU)\\b(?:tầm|khoảng|cỡ|xấp xỉ|ước chừng|quanh|nhắm(?: tới)?)\\s+\\d"
                    + "|\\b(?:mức giá|giá)\\s+(?:mong muốn|mục tiêu|lý tưởng)\\b"
                    + "|\\b(?:mong muốn|ưu tiên)\\s+(?:mức giá|giá)\\s+(?:là\\s+)?\\d");

    private ExtractionResultNormalizer() {
    }

    static void normalize(JsonNode result, String query) throws IOException {
        var constraints = result.path("constraints");
        var preferences = result.path("preferences");
        var budget = constraints.path("budget");
        validateBudget(budget);

        Set<JsonNode> classifiedFabrics = preferConstraints(constraints, preferences, "fabric_types");
        preferConstraints(constraints, preferences, "characteristics");
        if ("find_fabric".equals(result.at("/intent/advice_type").asText())) {
            normalizeFabricSearch(result, classifiedFabrics);
        }
        removeRepeatedUpperLimit((ObjectNode) preferences, budget, query);
    }

    private static Set<JsonNode> preferConstraints(JsonNode constraints, JsonNode preferences, String field)
            throws IOException {
        var classified = new HashSet<JsonNode>();
        constraints.path("required_" + field).forEach(classified::add);
        for (JsonNode excluded : constraints.path("excluded_" + field)) {
            if (!classified.add(excluded)) {
                throw new IOException("Ràng buộc bắt buộc và loại trừ mâu thuẫn ở nhóm " + field + ".");
            }
        }

        // Tầng truy vấn áp dụng điều kiện bắt buộc và loại trừ trước khi xếp hạng.
        removeMatches((ArrayNode) preferences.path(field), classified);
        return classified;
    }

    private static void normalizeFabricSearch(JsonNode result, Set<JsonNode> classified) throws IOException {
        var fabrics = (ArrayNode) result.at("/intent/fabric_types");
        result.at("/preferences/fabric_types").forEach(classified::add);
        removeMatches(fabrics, classified);
        if (!fabrics.isEmpty()) {
            throw new IOException("AI chưa phân loại chất liệu cần tìm vào sở thích hoặc ràng buộc.");
        }
    }

    private static void removeMatches(ArrayNode values, Set<JsonNode> matches) {
        for (int index = values.size() - 1; index >= 0; index--) {
            if (matches.contains(values.get(index))) {
                values.remove(index);
            }
        }
    }

    private static void removeRepeatedUpperLimit(ObjectNode preferences, JsonNode budget, String query) {
        var upper = budget.path("upper");
        var target = preferences.path("target_price");
        if (!upper.isObject() || !target.isObject()) {
            return;
        }

        String normalizedQuery = Normalizer.normalize(query, Normalizer.Form.NFC);
        // Giữ giá mục tiêu riêng khi khách diễn đạt rõ mức muốn chi.
        if (target.path("amount").decimalValue().compareTo(upper.path("amount").decimalValue()) == 0
                && target.path("currency").equals(budget.path("currency"))
                && target.path("basis").equals(budget.path("basis"))
                && UPPER_BUDGET_CUE.matcher(normalizedQuery).find()
                && !TARGET_PRICE_CUE.matcher(normalizedQuery).find()) {
            preferences.putNull("target_price");
        }
    }

    private static void validateBudget(JsonNode budget) throws IOException {
        if (budget.isNull()) {
            return;
        }
        var lower = budget.path("lower");
        var upper = budget.path("upper");
        if (lower.isNull() && upper.isNull()) {
            throw new IOException("Ngân sách cần ít nhất một cận giá.");
        }
        if (lower.isNull() || upper.isNull()) {
            return;
        }

        int comparison = lower.path("amount").decimalValue().compareTo(upper.path("amount").decimalValue());
        // Hai cận bằng nhau chỉ tạo khoảng hợp lệ nếu cả hai cùng bao gồm mức giá đó.
        if (comparison > 0 || (comparison == 0
                && !(lower.path("inclusive").booleanValue() && upper.path("inclusive").booleanValue()))) {
            throw new IOException("Hai cận ngân sách tạo thành một khoảng giá rỗng.");
        }
    }
}
