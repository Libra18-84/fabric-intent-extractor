package vn.fabricadvisor.extractor.service;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;

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
            assertTrue(instructions.contains("Bạn là chuyên gia"));
            assertTrue(instructions.contains("\"breathable\":[\"thoáng\",\"thoáng mát\",\"mát\"]"));
            assertFalse(instructions.contains("\"linen\""));
            assertEquals("FabricExtractionResult", schema.path("title").asText());
            assertTrue(schema.at("/properties/unmapped_terms/items/pattern").isMissingNode());
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
    @ValueSource(strings = {"", "not JSON", "{\"intent\":{}}", "{} {}", "{\"a\":1,\"a\":2}"})
    void rejectsInvalidModelOutput(String response) throws IOException {
        var extractor = new IntentExtractor(mapper, (instructions, query, schema) -> response);

        assertThrows(IOException.class, () -> extractor.extract(QUERY));
    }

    @Test
    void rejectsDuplicateKeysAndTrailingDocumentsWithDefaultMapper() throws IOException {
        String raw = sample().toString();
        var duplicate = new IntentExtractor(new ObjectMapper(), (instructions, query, schema) ->
                raw.replace("\"intent\":", "\"intent\":{},\"intent\":"));
        var trailing = new IntentExtractor(new ObjectMapper(), (instructions, query, schema) -> raw + " {}");

        assertThrows(IOException.class, () -> duplicate.extract(QUERY));
        assertThrows(IOException.class, () -> trailing.extract(QUERY));
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
    void removesExclusiveUpperLimitRepeatedAsTargetPrice() throws Exception {
        ObjectNode response = sample();
        ((ObjectNode) response.path("preferences")).putObject("target_price")
                .put("amount", 200_000).put("currency", "VND").put("basis", "unspecified");

        var result = extract(QUERY, response);
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
    void removesInclusiveUpperBudgetRepeatedAsTargetPrice() throws Exception {
        var response = sample();
        ((ObjectNode) response.at("/constraints/budget/upper")).put("inclusive", true);
        ((ObjectNode) response.path("preferences")).putObject("target_price")
                .put("amount", 200_000)
                .put("currency", "VND")
                .put("basis", "unspecified");

        var result = extract(QUERY.replace("giá dưới", "giá không quá"), response);

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
    void dropsPreferencesAlreadyRequiredOrExcluded() throws Exception {
        var response = sample();
        var preferences = (ObjectNode) response.path("preferences");
        preferences.putArray("fabric_types").add("polyester").add("cotton");
        preferences.putArray("characteristics").add("breathable").add("transparent").add("soft");
        var constraints = (ObjectNode) response.path("constraints");
        constraints.putArray("excluded_fabric_types").add("polyester");
        constraints.putArray("required_characteristics").add("breathable");
        constraints.putArray("excluded_characteristics").add("transparent");

        var result = extract(QUERY + " Phải thoáng mát và không xuyên thấu. Loại polyester."
                + " Ưu tiên cotton và mềm.", response);

        assertEquals(mapper.createArrayNode().add("cotton"), result.at("/preferences/fabric_types"));
        assertEquals(mapper.createArrayNode().add("soft"), result.at("/preferences/characteristics"));
        assertEquals(mapper.createArrayNode().add("polyester"), result.at("/constraints/excluded_fabric_types"));
    }

    @Test
    void rejectsFabricLeftOnlyInSearchIntent() throws IOException {
        var response = sample();
        ((ObjectNode) response.path("intent")).putArray("fabric_types").add("cotton");

        var failure = assertThrows(IOException.class, () -> extract(QUERY + " Chỉ lấy cotton.", response));

        assertTrue(failure.getMessage().contains("chưa phân loại chất liệu"));
    }

    @Test
    void stillRemovesDuplicateBudgetWhenOnlyFabricIsPreferred() throws Exception {
        var response = sample();
        ((ObjectNode) response.path("preferences")).putObject("target_price")
                .put("amount", 200_000).put("currency", "VND").put("basis", "unspecified");
        String query = QUERY.replace("giá dưới 200 nghìn", "dưới 200k") + " Ưu tiên cotton.";

        var result = extract(query, response);

        assertTrue(result.at("/preferences/target_price").isNull());
        assertEquals(200_000, result.at("/constraints/budget/upper/amount").asInt());
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
