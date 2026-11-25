package vn.fabricadvisor.extractor.cli;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import vn.fabricadvisor.extractor.ai.LanguageModel;
import vn.fabricadvisor.extractor.ai.LlamaCppLanguageModel;
import vn.fabricadvisor.extractor.service.IntentExtractor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class FabricExtractorCli {
    private static final String DEMO_QUERY =
            "Mình muốn mua vải để may áo đi làm mùa hè, mặc thoáng mát, giá dưới 200 nghìn.";

    private FabricExtractorCli() {
    }

    public static void main(String[] args) {
        int status = run(args, System.in, System.out, System.err, System.getenv());
        if (status != 0) {
            System.exit(status);
        }
    }

    static int run(String[] args, InputStream input, PrintStream output, PrintStream diagnostics,
                   Map<String, String> environment) {
        if (args.length > 1 || (args.length == 1 && !args[0].equals("--demo"))) {
            diagnostics.println("Chạy không có đối số để nhập câu, hoặc dùng --demo để xem dữ liệu mẫu.");
            return 2;
        }

        var mapper = JsonMapper.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .build();
        boolean demo = args.length == 1;

        try {
            String query;
            LanguageModel model;
            if (demo) {
                query = DEMO_QUERY;
                model = (instructions, userInput, schema) -> demoResponse();
                diagnostics.println("Chế độ mẫu, không gọi AI. Câu mẫu: " + query);
            } else {
                diagnostics.println("Nhập yêu cầu về vải rồi nhấn Enter:");
                query = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8)).readLine();
                model = new LlamaCppLanguageModel(mapper,
                        environment.getOrDefault("LLAMA_BASE_URL", LlamaCppLanguageModel.DEFAULT_BASE_URL),
                        environment.get("LLAMA_MODEL"));
            }

            var extractor = new IntentExtractor(mapper, model);
            var result = extractor.extract(query);
            output.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
            return 0;
        } catch (IllegalArgumentException error) {
            diagnostics.println(error.getMessage());
            return 2;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            diagnostics.println("Quá trình trích xuất đã bị gián đoạn.");
            return 1;
        } catch (IOException error) {
            diagnostics.println(error.getMessage());
            return 1;
        }
    }

    private static String demoResponse() throws IOException {
        try (var input = FabricExtractorCli.class.getResourceAsStream("/demo/extraction-result.json")) {
            if (input == null) {
                throw new IOException("Thiếu phản hồi mẫu cho chế độ --demo.");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}