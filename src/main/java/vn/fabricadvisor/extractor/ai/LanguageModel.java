package vn.fabricadvisor.extractor.ai;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;

@FunctionalInterface
public interface LanguageModel {
    String complete(String instructions, String query, JsonNode outputSchema)
            throws IOException, InterruptedException;
}