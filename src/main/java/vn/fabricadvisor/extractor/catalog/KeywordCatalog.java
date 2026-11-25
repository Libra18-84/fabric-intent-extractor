package vn.fabricadvisor.extractor.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class KeywordCatalog {
    private static final Set<String> GROUPS =
            Set.of("fabric_types", "characteristics", "garment_types", "occasions", "seasons");
    private static final Pattern CANONICAL_ID = Pattern.compile("[a-z][a-z0-9_]*");
    private static final Pattern TOKEN_SEPARATOR = Pattern.compile("[^\\p{L}\\p{M}\\p{N}]+");

    private final Map<String, Map<String, List<String>>> entries;

    public KeywordCatalog(ObjectMapper mapper, InputStream input) throws IOException {
        entries = mapper.readValue(input, new TypeReference<>() {});
        if (entries == null || !entries.keySet().equals(GROUPS)) {
            throw new IOException("File từ khóa phải có đủ năm nhóm quy định.");
        }

        for (var group : entries.entrySet()) {
            if (group.getValue() == null || group.getValue().isEmpty()) {
                throw new IOException("Nhóm từ khóa rỗng: " + group.getKey());
            }
            for (var entry : group.getValue().entrySet()) {
                List<String> aliases = entry.getValue();
                if (!CANONICAL_ID.matcher(entry.getKey()).matches()
                        || aliases == null
                        || aliases.isEmpty()
                        || aliases.stream().anyMatch(alias -> alias == null || normalize(alias).isEmpty())) {
                    throw new IOException("Mã hoặc cụm từ khóa không hợp lệ: " + entry.getKey());
                }
            }
        }
    }

    public Map<String, Map<String, List<String>>> retrieve(String query) {
        String tokenizedQuery = " " + normalize(query) + " ";
        var result = new LinkedHashMap<String, Map<String, List<String>>>();

        entries.forEach((group, values) -> {
            var matches = "garment_types".equals(group)
                    ? retrieveGarments(tokenizedQuery, values)
                    : retrieveAliases(tokenizedQuery, values);
            if (!matches.isEmpty()) {
                result.put(group, matches);
            }
        });
        return result;
    }

    private static Map<String, List<String>> retrieveAliases(String query, Map<String, List<String>> values) {
        var result = new LinkedHashMap<String, List<String>>();
        values.forEach((id, aliases) -> {
            // So khớp trọn từ và giữ dấu tiếng Việt để phân biệt “lạnh” với “lanh”.
            var matches = aliases.stream().filter(alias -> query.contains(wholeAlias(alias))).toList();
            if (!matches.isEmpty()) {
                result.put(id, matches);
            }
        });
        return result;
    }

    private static Map<String, List<String>> retrieveGarments(String query, Map<String, List<String>> values) {
        var occurrences = new ArrayList<AliasMatch>();
        values.forEach((id, aliases) -> {
            for (String alias : aliases) {
                String phrase = wholeAlias(alias);
                for (int start = query.indexOf(phrase); start >= 0; start = query.indexOf(phrase, start + 1)) {
                    occurrences.add(new AliasMatch(id, alias, start, start + phrase.length()));
                }
            }
        });
        occurrences.sort(Comparator.comparingInt(AliasMatch::start)
                .thenComparing(Comparator.comparingInt(AliasMatch::end).reversed()));

        var result = new LinkedHashMap<String, List<String>>();
        AliasMatch covering = null;
        for (AliasMatch occurrence : occurrences) {
            // Bỏ “áo” nằm trong “áo khoác”, nhưng giữ lần xuất hiện độc lập của “áo”.
            if (covering != null && occurrence.end() <= covering.end()
                    && (occurrence.start() != covering.start() || occurrence.end() != covering.end())) {
                continue;
            }
            covering = occurrence;
            var aliases = result.computeIfAbsent(occurrence.id(), ignored -> new ArrayList<>());
            if (!aliases.contains(occurrence.alias())) {
                aliases.add(occurrence.alias());
            }
        }
        return result;
    }

    private static String wholeAlias(String alias) {
        return " " + normalize(alias) + " ";
    }

    private static String normalize(String text) {
        String unicodeText = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFC);
        return TOKEN_SEPARATOR.matcher(unicodeText).replaceAll(" ").strip();
    }

    private record AliasMatch(String id, String alias, int start, int end) {
    }
}
