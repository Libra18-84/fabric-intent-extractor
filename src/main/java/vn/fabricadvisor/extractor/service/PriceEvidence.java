package vn.fabricadvisor.extractor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Đối chiếu số tiền, đơn vị và toán tử với câu khách nhập. */
final class PriceEvidence {
    private static final Pattern NUMBER = Pattern.compile("(?iU)(?<![\\p{L}\\p{N}])(?:(?<symbol>[$€])\\s*)?"
            + "(?<amount>[0-9]+(?:[.,][0-9]+)*)"
            + "(?:\\s*(?<scale>nghìn|ngàn|triệu|tr|k)(?![\\p{L}\\p{N}]))?"
            + "(?:\\s*(?<currency>vnd|usd|eur|đồng|đ|₫)(?![\\p{L}\\p{N}]))?"
            + "(?:\\s*(?:/|(?:mỗi|một|cho mỗi)\\s+)(?<basis>mét|m|cái|chiếc|sản phẩm)(?![\\p{L}\\p{N}]))?");
    private static final Pattern CUE = Pattern.compile("(?iU)\\b(?:(?<lowerOpen>trên|cao hơn|lớn hơn)"
            + "|(?<lowerClosed>ít nhất|tối thiểu|không dưới|không ít hơn|không thấp hơn|từ)"
            + "|(?<upperOpen>dưới|ít hơn|thấp hơn)"
            + "|(?<upperClosed>tối đa|không quá|không trên|không cao hơn|không lớn hơn|không vượt quá)"
            + "|(?<target>tầm|khoảng|cỡ|xấp xỉ|ước chừng|quanh|nhắm tới)"
            + "|(?<rangeEnd>đến|tới))\\s*$");
    private static final Pattern PRICE_PREFIX = Pattern.compile(
            "(?iU)\\b(?:giá|mức giá|ngân sách|chi phí)(?:\\s+là)?\\s*$");
    private static final Pattern PRICE_LEVEL = Pattern.compile(
            "(?iU)\\b(?:rẻ|bình dân|tiết kiệm|tầm trung|trung cấp|mức trung bình|giá vừa phải|giá mềm|cao cấp|hạng sang|premium)\\b");
    private static final Pattern TOTAL_PREFIX = Pattern.compile(
            "(?iU)\\b(?:tổng\\s+(?:giá|ngân sách|chi phí)|tổng cộng)\\b");
    private static final Pattern GROUPED_NUMBER = Pattern.compile("[0-9]{1,3}(?:[.,][0-9]{3})+");
    private static final Pattern AMOUNT_CLUE = Pattern.compile(
            "(?iU)[\\p{Nd}$€₫£¥]|\\b(?:giá|ngân sách|chi phí|tiền|đồng|vnd|usd|eur"
                    + "|chục|trăm|nghìn|ngàn|triệu|tỷ|tỉ)\\b");
    private PriceEvidence() {
    }

    static void validate(JsonNode result, String query) throws IOException {
        var mentions = findPrices(query);
        var budget = result.at("/constraints/budget");
        var target = result.at("/preferences/target_price");
        String advice = result.at("/intent/advice_type").asText();
        boolean requireLimits = !List.of("other", "unclear").contains(advice);
        // A budget object holds one currency and one unit of measure for both bounds.
        boolean incompatibleRange = incompatibleRange(mentions);
        validateSide(mentions, Side.LOWER, budget.path("lower"), budget, requireLimits && !incompatibleRange);
        validateSide(mentions, Side.UPPER, budget.path("upper"), budget, requireLimits && !incompatibleRange);
        validateSide(mentions, Side.TARGET, target, target, requireLimits);
        if (!hasPriceLevel(query) && !result.at("/preferences/price_level").isNull()) {
            throw new IOException("/preferences/price_level: use null. The request does not state a price tier.");
        }
    }

    private static boolean hasPriceLevel(String query) {
        return PRICE_LEVEL.matcher(query).find();
    }
    static void constrainAbsentAmounts(JsonNode schema, String query) {
        // Chỉ khóa các trường tiền khi câu gốc không có dấu hiệu nêu số tiền.
        if (AMOUNT_CLUE.matcher(Normalizer.normalize(query, Normalizer.Form.NFC)).find()) {
            return;
        }
        var preferences = (ObjectNode) schema.at("/properties/preferences/properties");
        preferences.putObject("target_price").putNull("const");
        if (!hasPriceLevel(query)) {
            preferences.putObject("price_level").putNull("const");
        }
        ((ObjectNode) schema.at("/properties/constraints/properties"))
                .putObject("budget").putNull("const");
    }
    static JsonNode constrainRepair(JsonNode schema, String query) {
        JsonNode repaired = schema.deepCopy();
        var preferenceFields = (ObjectNode) repaired.at("/properties/preferences/properties");
        if (!hasPriceLevel(query)) {
            preferenceFields.putObject("price_level").putNull("const");
        }
        var prices = findPrices(query);
        if (prices.isEmpty() || prices.stream().anyMatch(price -> price.side() == null)
                || incompatibleRange(prices)) {
            return repaired;
        }
        var lowers = forSide(prices, Side.LOWER);
        var uppers = forSide(prices, Side.UPPER);
        var targets = forSide(prices, Side.TARGET);
        if (lowers.size() > 1 || uppers.size() > 1 || targets.size() > 1) {
            return repaired;
        }

        // Narrow the repair schema from unambiguous source evidence. Validate the new response again.
        var budgetFields = (ObjectNode) repaired.at("/properties/constraints/properties");
        if (lowers.isEmpty() && uppers.isEmpty()) {
            budgetFields.putObject("budget").putNull("const");
        } else {
            var budget = (ObjectNode) budgetFields.at("/budget/anyOf/0").deepCopy();
            var fields = (ObjectNode) budget.path("properties");
            constrainBound(fields, "lower", lowers, repaired.at("/$defs/nullable_bound/anyOf/0"));
            constrainBound(fields, "upper", uppers, repaired.at("/$defs/nullable_bound/anyOf/0"));
            Money unit = lowers.isEmpty() ? uppers.getFirst() : lowers.getFirst();
            fields.putObject("currency").put("const", unit.currency());
            if (unit.basis() == null && !uppers.isEmpty()) {
                unit = uppers.getFirst();
            }
            fields.putObject("basis").put("const", unit.basisOrDefault());
            budgetFields.set("budget", budget);
        }
        if (targets.isEmpty()) {
            preferenceFields.putObject("target_price").putNull("const");
        } else {
            var target = (ObjectNode) preferenceFields.at("/target_price/anyOf/0").deepCopy();
            var fields = (ObjectNode) target.path("properties");
            Money price = targets.getFirst();
            fields.putObject("amount").put("const", price.amount());
            fields.putObject("currency").put("const", price.currency());
            fields.putObject("basis").put("const", price.basisOrDefault());
            preferenceFields.set("target_price", target);
        }
        return repaired;
    }

    private static void constrainBound(ObjectNode properties, String field, List<Money> values, JsonNode template) {
        if (values.isEmpty()) {
            properties.putObject(field).putNull("const");
            return;
        }
        Money price = values.getFirst();
        var bound = (ObjectNode) template.deepCopy();
        var fields = (ObjectNode) bound.path("properties");
        fields.putObject("amount").put("const", price.amount());
        fields.putObject("inclusive").put("const", price.inclusive());
        properties.set(field, bound);
    }

    private static List<Money> forSide(List<Money> prices, Side side) {
        return prices.stream().filter(price -> price.side() == side).distinct().toList();
    }

    private static void validateSide(List<Money> mentions, Side side, JsonNode value, JsonNode terms,
                                     boolean requireExplicit) throws IOException {
        var explicit = forSide(mentions, side);
        String path = side.path;
        if (!value.isObject()) {
            if (requireExplicit && explicit.size() == 1) {
                throw new IOException(path + ": " + explicit.getFirst().description());
            }
            return;
        }
        // A cited limit cannot also supply a separate target price without a matching source mention.
        var allowed = explicit.isEmpty() ? mentions.stream().filter(price -> price.side() == null).toList() : explicit;
        if (allowed.stream().noneMatch(price -> price.matches(value, terms))) {
            String detail = explicit.size() == 1 ? explicit.getFirst().description()
                    : "Recheck amount, currency, unit and operator against the original request. Use null when absent.";
            throw new IOException(path + ": " + detail);
        }
    }

    private static boolean incompatibleRange(List<Money> prices) {
        var lowers = prices.stream().filter(price -> price.side() == Side.LOWER).toList();
        var uppers = prices.stream().filter(price -> price.side() == Side.UPPER).toList();
        return lowers.size() == 1 && uppers.size() == 1
                && (!lowers.getFirst().currency().equals(uppers.getFirst().currency())
                || (lowers.getFirst().basis() != null && uppers.getFirst().basis() != null
                && !lowers.getFirst().basis().equals(uppers.getFirst().basis())));
    }

    private static List<Money> findPrices(String query) {
        String source = Normalizer.normalize(query, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        var found = new ArrayList<Money>();
        var matcher = NUMBER.matcher(source);
        Candidate rangeStart = null;
        int previousEnd = 0;
        while (matcher.find()) {
            String before = source.substring(previousEnd, matcher.start());
            previousEnd = matcher.end();
            var cue = CUE.matcher(before);
            boolean hasCue = cue.find();
            String operator = hasCue ? cue.group().strip() : "";
            boolean atPrice = PRICE_PREFIX.matcher(hasCue ? before.substring(0, cue.start()) : before).find();
            BigDecimal digits = parseNumber(matcher.group("amount"));
            if (digits == null) {
                if (rangeStart != null && rangeStart.isMoney()) {
                    found.add(rangeStart.money());
                }
                rangeStart = null;
                continue;
            }
            String statedBasis = basis(matcher.group("basis"));
            if (statedBasis == null && TOTAL_PREFIX.matcher(before).find()) {
                statedBasis = "total";
            }
            var candidate = new Candidate(digits, scale(matcher.group("scale")),
                    currency(matcher.group("symbol"), matcher.group("currency")), statedBasis,
                    atPrice, side(cue, hasCue), operator);

            if (rangeStart != null) {
                if (List.of("đến", "tới").contains(operator) && before.strip().equals(operator)
                        && (rangeStart.isMoney() || candidate.isMoney())) {
                    found.add(rangeStart.sharedWith(candidate).money());
                    found.add(candidate.sharedWith(rangeStart).money());
                    rangeStart = null;
                    continue;
                }
                if (rangeStart.isMoney()) {
                    found.add(rangeStart.money());
                }
                rangeStart = null;
            }
            if (operator.equals("từ")) {
                rangeStart = candidate;
            } else if (candidate.isMoney()) {
                found.add(candidate.money());
            }
        }
        if (rangeStart != null && rangeStart.isMoney()) {
            found.add(rangeStart.money());
        }
        shareBudgetBasis(found);
        return found;
    }

    private static void shareBudgetBasis(List<Money> prices) {
        if (forSide(prices, Side.LOWER).isEmpty() || forSide(prices, Side.UPPER).isEmpty()) {
            return;
        }
        var bases = prices.stream().filter(Money::isBound).map(Money::basis)
                .filter(basis -> basis != null).distinct().toList();
        if (bases.size() == 1) {
            prices.replaceAll(price -> price.isBound() && price.basis() == null
                    ? new Money(price.amount(), price.currency(), bases.getFirst(), price.side(), price.inclusive())
                    : price);
        }
    }

    private static Side side(java.util.regex.Matcher cue, boolean hasCue) {
        if (!hasCue) {
            return null;
        }
        if (cue.group("lowerOpen") != null || cue.group("lowerClosed") != null) {
            return Side.LOWER;
        }
        if (cue.group("upperOpen") != null || cue.group("upperClosed") != null || cue.group("rangeEnd") != null) {
            return Side.UPPER;
        }
        return Side.TARGET;
    }

    private static BigDecimal parseNumber(String text) {
        String decimal = GROUPED_NUMBER.matcher(text).matches()
                ? text.replace(".", "").replace(",", "") : text.replace(',', '.');
        try {
            return new BigDecimal(decimal);
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static int scale(String value) {
        if (value == null) {
            return 0;
        }
        return switch (value) {
            case "triệu", "tr" -> 6;
            default -> 3;
        };
    }

    private static String currency(String symbol, String word) {
        if (symbol != null) {
            return "$".equals(symbol) ? "USD" : "EUR";
        }
        if (word == null) {
            return null;
        }
        return switch (word) {
            case "usd" -> "USD";
            case "eur" -> "EUR";
            default -> "VND";
        };
    }

    private static String basis(String value) {
        if (value == null) {
            return null;
        }
        return List.of("m", "mét").contains(value) ? "per_meter" : "per_item";
    }

    private enum Side {
        LOWER("/constraints/budget/lower"), UPPER("/constraints/budget/upper"), TARGET("/preferences/target_price");

        private final String path;

        Side(String path) {
            this.path = path;
        }
    }

    private record Candidate(BigDecimal digits, int scale, String currency, String basis, boolean atPrice,
                             Side side, String operator) {
        boolean isMoney() {
            return atPrice || scale != 0 || currency != null || basis != null;
        }

        Candidate sharedWith(Candidate other) {
            return new Candidate(digits, scale == 0 && currency == null ? other.scale : scale,
                    currency == null ? other.currency : currency, basis == null ? other.basis : basis,
                    true, side, operator);
        }

        Money money() {
            boolean inclusive = !List.of("trên", "cao hơn", "lớn hơn", "dưới", "ít hơn", "thấp hơn").contains(operator);
            return new Money(digits.scaleByPowerOfTen(scale), currency == null ? "VND" : currency,
                    basis, side, inclusive);
        }
    }

    private record Money(BigDecimal amount, String currency, String basis, Side side, boolean inclusive) {
        boolean isBound() {
            return side == Side.LOWER || side == Side.UPPER;
        }

        String basisOrDefault() {
            return basis == null ? "unspecified" : basis;
        }

        boolean matches(JsonNode value, JsonNode terms) {
            return value.isObject() && amount.compareTo(value.path("amount").decimalValue()) == 0
                    && currency.equals(terms.path("currency").asText())
                    && basisOrDefault().equals(terms.path("basis").asText())
                    && (side == null || side == Side.TARGET || value.path("inclusive").booleanValue() == inclusive);
        }

        String description() {
            String operator = switch (side) {
                case LOWER -> inclusive ? ">=" : ">";
                case UPPER -> inclusive ? "<=" : "<";
                case TARGET -> "near";
            };
            return "The request specifies " + operator + " " + amount.toPlainString() + " " + currency
                    + " (" + basisOrDefault() + ")"
                    + (side == Side.TARGET ? ". Use target_price." : ". Set inclusive to " + inclusive + ".");
        }
    }
}
