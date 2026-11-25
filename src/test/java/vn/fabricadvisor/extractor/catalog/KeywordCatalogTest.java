package vn.fabricadvisor.extractor.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KeywordCatalogTest {
    @Test
    void retrievesTheMostSpecificGarmentAtEachOccurrence() throws IOException {
        var catalog = catalog();

        assertEquals(Map.of("jacket", List.of("áo khoác")),
                catalog.retrieve("Vải để may áo khoác").get("garment_types"));
        assertEquals(Map.of("skirt", List.of("chân váy")),
                catalog.retrieve("Vải để may chân váy").get("garment_types"));
    }

    @Test
    void preservesSeparateMentionsOfGenericGarments() throws IOException {
        assertEquals(Map.of("top", List.of("áo"), "jacket", List.of("áo khoác")),
                catalog().retrieve("Vải để may áo và áo khoác").get("garment_types"));
    }

    private KeywordCatalog catalog() throws IOException {
        try (var input = getClass().getResourceAsStream("/catalog/fabric-keywords.json")) {
            return new KeywordCatalog(new ObjectMapper(), input);
        }
    }
}
