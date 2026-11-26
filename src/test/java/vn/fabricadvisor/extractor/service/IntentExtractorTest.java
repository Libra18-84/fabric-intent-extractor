package vn.fabricadvisor.extractor.service;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class IntentExtractorTest {
    private static final String QUERY =
            "Mình muốn mua vải để may áo đi làm mùa hè, mặc thoáng mát, giá dưới 200 nghìn.";

    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    @Test
    void sendsPromptAndRetrievedAliasesAndPreservesExclusiveBudget() throws Exception {
        var response = sample();
        var extractor = new IntentExtractor(mapper, (instructions, query, schema) -> {
            assertEquals(QUERY, query);
            assertTrue(instructions.contains("You are a fabric advisor"));
            assertTrue(instructions.contains("\"breathable\":[\"thoáng\",\"thoáng mát\",\"mát\"]"));
            assertFalse(instructions.contains("\"linen\""));
            assertEquals("FabricExtractionResult", schema.path("title").asText());
            assertEquals("FabricExtractionResult", schema.path("title").asText());
            assertEquals("breathable",
                    schema.at("/properties/preferences/properties/characteristics/items/enum/0").asText());
            assertEquals(mapper.createArrayNode(),
                    schema.at("/properties/intent/properties/fabric_types/const"));
            assertEquals(1,
                    schema.at("/properties/preferences/properties/characteristics/maxItems").asInt());
            return response.toString();
        });

        var upper = extractor.extract("  " + QUERY + "  ").at("/constraints/budget/upper");
        assertEquals(200_000, upper.path("amount").asInt());
        assertFalse(upper.path("inclusive").asBoolean());
    }

    @Test
    void acceptsUnspecifiedInputsWithoutInventingFields() throws Exception {
        var response = mapper.readTree("""
                {
                  "intent": {"advice_type":"unclear", "fabric_types":[], "garment_types":[], "occasions":[]},
                  "preferences": {"fabric_types":[], "characteristics":[], "price_level":null, "target_price":null},
                  "constraints": {"seasons":[], "required_fabric_types":[], "excluded_fabric_types":[],
                                  "required_characteristics":[], "excluded_characteristics":[], "budget":null},
                  "unmapped_terms":[]
                }
                """);
        assertEquals(response, extract("Tư vấn giúp mình với.", response));
    }

    @Test
    void rejectsBlankInputBeforeCallingTheModel() throws IOException {
        var extractor = new IntentExtractor(
                mapper, (instructions, query, schema) -> fail("Model call was unexpected."));

        assertThrows(IllegalArgumentException.class, () -> extractor.extract("   "));
        assertThrows(IllegalArgumentException.class, () -> extractor.extract("x".repeat(4_001)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "not JSON", "{\"intent\":{}}", "{} {}", "{\"a\":1,\"a\":2}",
            "<json>{} {}</json>"
    })
    void rejectsInvalidModelOutput(String response) throws IOException {
        var extractor = new IntentExtractor(mapper, (instructions, query, schema) -> response);

        assertThrows(IOException.class, () -> extractor.extract(QUERY));
    }

    @Test
    void rejectsFabricThatWasNotRetrieved() throws IOException {
        ObjectNode response = sample();
        ((ObjectNode) response.path("preferences")).putArray("fabric_types").add("linen");

        var failure = assertThrows(IOException.class, () -> extract(QUERY, response));
        assertTrue(failure.getMessage().contains("fabric_types"));
    }

    @Test
    void rejectsAnEmptyPriceIntervalEvenWhenTheJsonSchemaPasses() throws IOException {
        ObjectNode response = sample();
        ((ObjectNode) response.at("/constraints/budget")).putObject("lower")
                .put("amount", 200_000).put("inclusive", true);

        var failure = assertThrows(IOException.class, () -> extract(QUERY, response));
        assertTrue(failure.getMessage().contains("khoảng giá rỗng"));
    }

    @Test
    void keepsRequiredFabricOnceAndPreservesASeparatePreference() throws Exception {
        ObjectNode response = sample();
        ((ObjectNode) response.path("intent")).putArray("fabric_types").add("wool").add("cotton");
        ((ObjectNode) response.path("preferences")).putArray("fabric_types").add("wool").add("cotton");
        ((ObjectNode) response.path("constraints")).putArray("required_fabric_types").add("wool");

        JsonNode result = extract(QUERY + " Chỉ lấy len, ưu tiên cotton.", response);
        assertTrue(result.at("/intent/fabric_types").isEmpty());
        assertEquals(mapper.createArrayNode().add("cotton"), result.at("/preferences/fabric_types"));
        assertEquals(mapper.createArrayNode().add("wool"), result.at("/constraints/required_fabric_types"));
    }

    @Test
    void repairsExclusiveUpperLimitRepeatedAsTargetPrice() throws Exception {
        ObjectNode response = sample();
        ((ObjectNode) response.path("preferences")).putObject("target_price")
                .put("amount", 200_000).put("currency", "VND").put("basis", "unspecified");

        var result = repairRepeatedTarget(QUERY, response);
        assertTrue(result.at("/preferences/target_price").isNull());
        assertEquals(200_000, result.at("/constraints/budget/upper/amount").asInt());

        var explicitTarget = extract(QUERY + " Tầm 200 nghìn là mức giá mình nhắm tới.", response);
        assertEquals(200_000, explicitTarget.at("/preferences/target_price/amount").asInt());
    }
    @Test
    void rejectsFabricThatIsBothRequiredAndExcluded() throws IOException {
        var response = sample();
        var constraints = (ObjectNode) response.path("constraints");
        constraints.putArray("required_fabric_types").add("cotton");
        constraints.putArray("excluded_fabric_types").add("cotton");

        var failure = assertThrows(IOException.class,
                () -> extract(QUERY + " Chỉ lấy cotton.", response));

        assertTrue(failure.getMessage().contains("mâu thuẫn"));
    }

    @Test
    void repairsInclusiveUpperBudgetRepeatedAsTargetPrice() throws Exception {
        var response = sample();
        ((ObjectNode) response.at("/constraints/budget/upper")).put("inclusive", true);
        ((ObjectNode) response.path("preferences")).putObject("target_price")
                .put("amount", 200_000)
                .put("currency", "VND")
                .put("basis", "unspecified");

        var result = repairRepeatedTarget(QUERY.replace("giá dưới", "giá không quá"), response);

        assertTrue(result.at("/preferences/target_price").isNull());
        assertTrue(result.at("/constraints/budget/upper/inclusive").asBoolean());
    }

    @Test
    void stillRejectsWhitespaceOnlyUnmappedTerms() throws IOException {
        var response = sample();
        response.putArray("unmapped_terms").add("   ");

        var failure = assertThrows(IOException.class, () -> extract(QUERY, response));

        assertTrue(failure.getMessage().contains("unmapped_terms"));
    }

    @Test
    void retriesIncorrectStandaloneFabricLookup() throws Exception {
        ObjectNode wrong = sample();
        wrong.withObject("/intent").putArray("garment_types");
        wrong.withObject("/intent").putArray("occasions");
        wrong.withObject("/preferences").putArray("fabric_types").add("linen");
        wrong.withObject("/preferences").putArray("characteristics");
        wrong.withObject("/constraints").putArray("seasons");
        wrong.withObject("/constraints").putNull("budget");
        wrong.putArray("unmapped_terms").add("linen");

        ObjectNode correct = wrong.deepCopy();
        correct.withObject("/intent").put("advice_type", "ask_fabric_details");
        correct.withObject("/intent").putArray("fabric_types").add("linen");
        correct.withObject("/preferences").putArray("fabric_types");
        correct.putArray("unmapped_terms");

        var calls = new AtomicInteger();
        var extractor = new IntentExtractor(mapper, (instructions, query, schema) -> {
            assertEquals("ask_fabric_details",
                    schema.at("/properties/intent/properties/advice_type/const").asText());
            if (calls.incrementAndGet() == 1) {
                return wrong.toString();
            }
            assertTrue(instructions.contains("Validation error:"));
            return correct.toString();
        });

        assertEquals(correct, extractor.extract("linen"));
        assertEquals(2, calls.get());
    }

    @Test
    void normalizesMissingOptionalFieldsWithoutRetry() throws Exception {
        String response = """
                {
                  "intent": {"advice_type":"find_fabric", "garment_types":["top"], "occasions":null},
                  "preferences": {"fabric_types":["cotton"], "price_level":"   ", "target_price":""},
                  "constraints": {"seasons":null, "budget":" "}
                }
                """;
        var calls = new AtomicInteger();
        var extractor = new IntentExtractor(mapper, (instructions, query, schema) -> {
            calls.incrementAndGet();
            return response;
        });

        var result = extractor.extract("Tìm vải cotton để may áo.");
        assertEquals(mapper.createArrayNode().add("cotton"), result.at("/preferences/fabric_types"));
        assertEquals(mapper.createArrayNode(), result.at("/intent/occasions"));
        assertEquals(mapper.createArrayNode(), result.at("/constraints/seasons"));
        assertEquals(mapper.createArrayNode(), result.path("unmapped_terms"));
        assertTrue(result.at("/preferences/price_level").isNull());
        assertTrue(result.at("/preferences/target_price").isNull());
        assertTrue(result.at("/constraints/budget").isNull());
        assertEquals(1, calls.get());
    }

    @Test
    void fillsMissingBudgetBoundAndKeepsExplicitAmount() throws Exception {
        var response = sample();
        response.withObject("/constraints/budget").remove("lower");

        var budget = extract(QUERY, response).at("/constraints/budget");
        assertTrue(budget.path("lower").isNull());
        assertEquals(200_000, budget.at("/upper/amount").asInt());
        assertFalse(budget.at("/upper/inclusive").asBoolean());
    }

    @Test
    void repairsInventedUpperBoundAndPriceUnitFromLowerLimit() throws Exception {
        String query = "Tôi cần vải để may áo thun mùa thu, chỉ lấy len, giá trên 200000 đồng.";
        var wrong = sample();
        var intent = wrong.withObject("/intent");
        intent.putArray("garment_types").add("t_shirt");
        intent.putArray("occasions");
        var preferences = wrong.withObject("/preferences");
        preferences.putArray("characteristics");
        preferences.put("price_level", "mid_range");
        preferences.putObject("target_price").put("amount", 200_000)
                .put("currency", "VND").put("basis", "per_meter");
        var constraints = wrong.withObject("/constraints");
        constraints.putArray("seasons").add("autumn");
        constraints.putArray("required_fabric_types").add("wool");
        var budget = constraints.withObject("/budget").put("basis", "per_meter");
        budget.putObject("lower").put("amount", 200_000).put("inclusive", false);
        budget.putObject("upper").put("amount", 300_000).put("inclusive", true);

        var correct = wrong.deepCopy();
        correct.withObject("/preferences").putNull("price_level").putNull("target_price");
        correct.withObject("/constraints/budget").put("basis", "unspecified").putNull("upper");
        var modelRepair = correct.deepCopy();
        modelRepair.withObject("/intent").put("advice_type", "compare_fabrics").putArray("garment_types");
        modelRepair.withObject("/constraints").putArray("seasons");
        modelRepair.withObject("/constraints").putArray("required_fabric_types");

        var calls = new AtomicInteger();
        var extractor = new IntentExtractor(mapper, (instructions, input, schema) -> {
            if (calls.incrementAndGet() == 1) {
                return wrong.toString();
            }
            assertTrue(instructions.contains("/constraints/budget/lower"));
            var priceSchema = schema.at("/properties/constraints/properties/budget/properties");
            assertEquals("unspecified", priceSchema.at("/basis/const").asText());
            assertEquals(200_000, priceSchema.at("/lower/properties/amount/const").asInt());
            assertFalse(priceSchema.at("/lower/properties/inclusive/const").asBoolean());
            assertTrue(priceSchema.at("/upper/const").isNull());
            assertTrue(schema.at("/properties/preferences/properties/target_price/const").isNull());
            assertEquals("find_fabric",
                    schema.at("/properties/intent/properties/advice_type/const").asText());
            return modelRepair.toString();
        });

        assertEquals(correct, extractor.extract(query));
        assertEquals(2, calls.get());
    }
    @Test
    void removesRepresentedAliasAndPreservesUnsupportedPhrase() throws Exception {
        var response = sample();
        response.withObject("/constraints").putArray("seasons").add("autumn");
        response.putArray("unmapped_terms").add("mùa thu").add("chống tia UV");

        var result = extract(QUERY.replace("mùa hè", "mùa thu") + " Cần chống tia UV.", response);

        assertEquals("autumn", result.at("/constraints/seasons/0").asText());
        assertEquals(mapper.createArrayNode().add("chống tia UV"), result.path("unmapped_terms"));
    }

    @Test
    void retriesWhenUnmappedTermIsAbsentFromTheRequest() throws Exception {
        var correct = sample();
        var wrong = correct.deepCopy();
        wrong.putArray("unmapped_terms").add("chống tia UV");
        var calls = new AtomicInteger();
        var extractor = new IntentExtractor(mapper, (instructions, query, schema) -> {
            if (calls.incrementAndGet() == 1) {
                return wrong.toString();
            }
            assertTrue(instructions.contains("unmapped_terms[0]"));
            return correct.toString();
        });

        assertEquals(correct, extractor.extract(QUERY));
        assertEquals(2, calls.get());
    }
    @ParameterizedTest
    @ValueSource(strings = {
            "```json\n%s\n```",
            "<json>%s</json>",
            "\uFEFF<think>Phân tích yêu cầu.</think>\n<json>\n```json\n%s\n```\n</json>"
    })
    void acceptsJsonInsideKnownWrappers(String wrapper) throws Exception {
        var expected = sample();
        var extractor = new IntentExtractor(mapper, (instructions, query, schema) ->
                wrapper.formatted(expected.toString()));

        assertEquals(expected, extractor.extract(QUERY));
    }

    @Test
    void stillValidatesSchemaAfterRemovingWrapper() throws Exception {
        var invalid = sample();
        invalid.withObject("/intent").remove("advice_type");
        var extractor = new IntentExtractor(mapper, (instructions, query, schema) ->
                "<json>" + invalid + "</json>");

        var failure = assertThrows(IOException.class, () -> extractor.extract(QUERY));
        assertTrue(failure.getMessage().contains("JSON Schema"));
    }
    @Test
    void extractsNeutralSewingRequestWithEmptyBudget() throws Exception {
        var response = sample();
        response.withObject("/intent").put("advice_type", "compare_fabrics");
        response.withObject("/intent").putArray("garment_types");
        response.withObject("/intent").putArray("occasions");
        response.withObject("/preferences").putArray("characteristics");
        response.withObject("/constraints").putArray("seasons");
        response.withObject("/constraints/budget").putNull("upper");

        var calls = new AtomicInteger();
        var extractor = new IntentExtractor(mapper, (instructions, query, schema) -> {
            calls.incrementAndGet();
            assertTrue(schema.at("/properties/constraints/properties/budget/const").isNull());
            assertTrue(schema.at("/properties/preferences/properties/target_price/const").isNull());
            return response.toString();
        });

        var result = extractor.extract("Tôi cần vải cotton để may áo.");

        assertEquals("find_fabric", result.at("/intent/advice_type").asText());
        assertEquals(mapper.createArrayNode().add("top"), result.at("/intent/garment_types"));
        assertTrue(result.at("/constraints/budget").isNull());
        assertEquals(mapper.createArrayNode().add("cotton"), result.at("/preferences/fabric_types"));
        assertEquals(1, calls.get());
    }

    @Test
    void retriesEmptyBudgetWhenRequestStatesAnUpperLimit() throws Exception {
        var correct = sample();
        var wrong = correct.deepCopy();
        wrong.withObject("/constraints/budget").putNull("upper");

        var calls = new AtomicInteger();
        var extractor = new IntentExtractor(mapper, (instructions, query, schema) -> {
            if (calls.incrementAndGet() == 1) {
                return wrong.toString();
            }
            assertTrue(instructions.contains("/constraints/budget/upper"));
            return correct.toString();
        });

        assertEquals(correct, extractor.extract(QUERY));
        assertEquals(2, calls.get());
    }
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void distinguishesNeutralFabricFromExplicitRequirement(boolean explicitlyRequired) throws Exception {
        var response = sample();
        response.withObject("/intent").putArray("occasions");
        response.withObject("/preferences").putArray("characteristics");
        var constraints = response.withObject("/constraints");
        constraints.putArray("seasons");
        constraints.putNull("budget");
        constraints.putArray("required_fabric_types").add("cotton");
        response.putArray("unmapped_terms").add("để may áo");

        String query = explicitlyRequired
                ? "Tôi cần vải cotton để may áo, chỉ dùng cotton."
                : "Tôi cần vải cotton để may áo.";
        var result = extract(query, response);
        var cotton = mapper.createArrayNode().add("cotton");
        var empty = mapper.createArrayNode();

        assertEquals(explicitlyRequired ? cotton : empty, result.at("/constraints/required_fabric_types"));
        assertEquals(explicitlyRequired ? empty : cotton, result.at("/preferences/fabric_types"));
        assertTrue(result.at("/constraints/budget").isNull());
        assertTrue(result.path("unmapped_terms").isEmpty());
    }
    @Test
    void repairsUnmappedTermsWithoutChangingExplicitFabricRequirement() throws Exception {
        var expected = sample();
        expected.withObject("/intent").putArray("occasions");
        expected.withObject("/preferences").putArray("characteristics");
        var constraints = expected.withObject("/constraints");
        constraints.putArray("seasons");
        constraints.putNull("budget");
        constraints.putArray("required_fabric_types").add("cotton");
        expected.putArray("unmapped_terms").add("chống tia UV");

        var wrong = expected.deepCopy();
        wrong.withArray("unmapped_terms").add("kháng khuẩn");
        var corrected = expected.deepCopy();
        corrected.withObject("/constraints").putArray("required_fabric_types");
        corrected.withObject("/preferences").putArray("fabric_types").add("cotton");

        var calls = new AtomicInteger();
        var extractor = new IntentExtractor(mapper, (instructions, query, schema) -> {
            if (calls.incrementAndGet() == 1) {
                return wrong.toString();
            }
            assertTrue(instructions.contains("unmapped_terms[1]"));
            assertEquals(expected.path("unmapped_terms"), schema.at("/properties/unmapped_terms/const"));
            assertEquals(expected.at("/constraints/required_fabric_types"),
                    schema.at("/properties/constraints/properties/required_fabric_types/const"));
            return corrected.toString();
        });

        var result = extractor.extract(
                "Tôi cần vải cotton để may áo, chỉ dùng cotton. Cần chống tia UV.");

        assertEquals(expected, result);
        assertEquals(2, calls.get());
    }

    @Test
    void readsExplicitFabricRequirementWhenModelOmitsIt() throws Exception {
        var response = sample();
        response.withObject("/intent").put("advice_type", "compare_fabrics");
        response.withObject("/intent").putArray("garment_types");
        response.withObject("/intent").putArray("occasions");
        response.withObject("/preferences").putArray("characteristics");
        response.withObject("/constraints").putArray("seasons");
        response.withObject("/constraints").putNull("budget");

        var result = extract("Tôi cần vải cotton để may áo, chỉ dùng cotton.", response);

        assertEquals("find_fabric", result.at("/intent/advice_type").asText());
        assertEquals(mapper.createArrayNode().add("top"), result.at("/intent/garment_types"));
        assertEquals(mapper.createArrayNode().add("cotton"), result.at("/constraints/required_fabric_types"));
        assertTrue(result.at("/preferences/fabric_types").isEmpty());
    }
    private JsonNode repairRepeatedTarget(String query, ObjectNode wrong) throws Exception {
        ObjectNode correct = wrong.deepCopy();
        correct.withObject("/preferences").putNull("target_price");
        var calls = new AtomicInteger();
        var extractor = new IntentExtractor(mapper, (instructions, input, schema) -> {
            if (calls.incrementAndGet() == 1) {
                return wrong.toString();
            }
            assertTrue(instructions.contains("/preferences/target_price"));
            return correct.toString();
        });

        JsonNode result = extractor.extract(query);
        assertEquals(2, calls.get());
        return result;
    }

    private JsonNode extract(String query, JsonNode response) throws IOException, InterruptedException {
        return new IntentExtractor(mapper, (instructions, input, schema) -> response.toString())
                .extract(query);
    }

    private ObjectNode sample() throws IOException {
        try (var file = getClass().getResourceAsStream("/demo/extraction-result.json")) {
            return (ObjectNode) mapper.readTree(file);
        }
    }
}
