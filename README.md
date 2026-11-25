# Fabric Intent Extractor

Ứng dụng Java chạy console để trích xuất yêu cầu tư vấn vải bằng tiếng Việt. JSON đầu ra có `intent`, `preferences`, `constraints` và `unmapped_terms` để tầng truy vấn có thể sử dụng.

## Chạy trong Bash trên Linux hoặc WSL

Mở thư mục chứa `pom.xml`. Project dùng JDK 21 và Maven. Kiểm tra phiên bản Java Maven đang dùng:

```bash
mvn -v
```

Chạy JUnit và in JSON mẫu:

```bash
mvn clean test
mvn -q compile exec:java -Dexec.args=--demo
```

Các bài JUnit dùng phản hồi model giả lập. `--demo` đọc JSON mẫu có sẵn, nên cả hai lệnh chạy được khi `llama-server` đang tắt. CLI gửi lời nhắc và thông báo lỗi vào `stderr`, còn JSON kết quả được ghi vào `stdout`.

Trong IntelliJ IDEA, mở project từ `pom.xml` và dùng **Alt + F12** để mở Terminal. Nếu đang ở dấu nhắc Windows, gõ `wsl` để vào Bash rồi kiểm tra `ls pom.xml`.

## Chạy với Llama thật

Trong tab Bash thứ nhất, chạy `llama-server` từ thư mục chứa chương trình:

```bash
./llama-server -hf bartowski/Llama-3.2-3B-Instruct-GGUF:Q4_K_M \
  --host 127.0.0.1 --port 8080 --ctx-size 4096 --parallel 1 --no-jinja
```

Giữ tab này mở. Cấu hình `--no-jinja` dùng để server áp dụng schema đầu ra cho model Llama trong cấu hình đã kiểm tra. Lần đầu server có thể tải model từ Hugging Face.

Trong tab Bash thứ hai, tại thư mục chứa `pom.xml`, kiểm tra server:

```bash
curl -fsS http://127.0.0.1:8080/health
```

Khi thấy `{"status":"ok"}`, chạy:

```bash
mvn -q compile exec:java
```

Nhập một câu rồi nhấn Enter. Chạy lại lệnh cho mỗi ca thử. Nếu dùng địa chỉ khác, đặt `LLAMA_BASE_URL`. Giá trị mặc định là `http://127.0.0.1:8080`. Dùng `LLAMA_MODEL` nếu server yêu cầu tên model.

## Cấu trúc

Các package Java nằm dưới `src/main/java/vn/fabricadvisor/extractor`.

| Vị trí | Trách nhiệm |
| --- | --- |
| `cli/FabricExtractorCli.java` | Đọc câu nhập và in JSON. |
| `catalog/KeywordCatalog.java` | Tra cụm từ tiếng Việt và mã chuẩn trong từ điển. |
| `service/IntentExtractor.java` | Ghép prompt, từ khóa và schema, gọi model và kiểm tra JSON. |
| `service/ExtractionResultNormalizer.java` | Kiểm tra mâu thuẫn và chuẩn hóa dữ liệu giữa các nhóm. |
| `ai/LanguageModel.java` và `ai/LlamaCppLanguageModel.java` | Giao diện model và bộ gọi API của `llama-server`. |
| `src/main/resources/catalog/fabric-keywords.json` | Từ điển dùng để tra các mã và cách diễn đạt tương ứng. |
| `src/main/resources/prompts/extract-intent.txt` | Prompt mặc định và ví dụ tiếng Việt. |
| `src/main/resources/schema/extraction-result.schema.json` | Hợp đồng JSON đầu ra. |

## Kiểm thử với Llama thật

| Câu nhập | Các trường cần đối chiếu |
| --- | --- |
| Tôi cần vải để may áo khoác mùa đông, chỉ lấy len, giá dưới 350000 đồng. | `find_fabric`, `jacket`, `winter`, `wool` bắt buộc, cận trên `350000` với `inclusive: false` |
| Mình muốn vải may quần, ưu tiên cotton, giá tầm 240k/m. | `pants`, ưu tiên `cotton`, giá mục tiêu `240000` mỗi mét, `budget: null` |
| So sánh vải cotton với linen giúp mình. | `compare_fabrics`, mã `cotton` và `linen` trong `intent.fabric_types` |
| Tìm vải may sơ mi, chỉ lấy cotton, không lấy polyester, giá không quá 270k/m. | `shirt`, bắt buộc `cotton`, loại `polyester`, cận trên `270000` mỗi mét với `inclusive: true` |

## Đọc JSON khi nối với SQLite

- `intent.advice_type` chọn luồng tư vấn. Với yêu cầu so sánh, `intent.fabric_types` chứa các đối tượng cần so sánh.
- `constraints` gồm điều kiện cứng. Trường `inclusive` chọn `<` hoặc `<=` cho cận trên và `>` hoặc `>=` cho cận dưới. Dùng tham số truy vấn cho số tiền và mã tra được. Đối chiếu đồng tiền và đơn vị tính với cột giá của cơ sở dữ liệu. `basis: "unspecified"` nghĩa là câu nhập chưa nêu đơn vị tính.
- `preferences` dùng để xếp hạng, ví dụ độ gần với `target_price`. Phần ràng buộc đã áp dụng trước khi tính mức phù hợp.
- `unmapped_terms` giữ cụm từ chưa ánh xạ được để tầng gọi xử lý hoặc hỏi lại khách.
