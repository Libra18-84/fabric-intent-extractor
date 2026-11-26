# Fabric Intent Extractor

Ứng dụng Java chạy console để trích xuất yêu cầu tư vấn vải bằng tiếng Việt. Chương trình trả về JSON gồm `intent`, `preferences` và `constraints` để bước truy vấn có thể dùng làm đầu vào.

## Yêu cầu

- JDK 21 và Maven.
- Để chạy với model thật trên Windows, dùng `llama-server` trong Ubuntu qua WSL 2.

## Chạy trong IntelliJ IDEA

1. Mở thư mục chứa `pom.xml` và nạp dự án Maven.
2. Vào **Run → Edit Configurations → Application**, chọn module `fabric-intent-extractor` và đặt **Main class** là `vn.fabricadvisor.extractor.cli.FabricExtractorCli`.
3. Để xem dữ liệu mẫu, đặt **Program arguments** là `--demo` rồi chạy. Chế độ này đọc phản hồi mẫu trong `resources/demo`.

## Chạy với Llama thật

Trong Ubuntu, mở thư mục chứa `llama-server` và chạy:

```bash
./llama-server -hf bartowski/Llama-3.2-3B-Instruct-GGUF:Q4_K_M --host 127.0.0.1 --port 8080 --ctx-size 8192 --parallel 1 --no-jinja
```

Giữ cửa sổ Ubuntu mở. Trong CMD trên Windows, kiểm tra server:

```cmd
curl.exe http://127.0.0.1:8080/health
```

Khi nhận `{"status":"ok"}`, xóa `--demo` khỏi **Program arguments**, chạy ứng dụng và nhập:

> Tôi cần vải để may áo khoác mùa đông, chỉ lấy len, giá dưới 350 nghìn.

Các trường cần kiểm tra gồm `garment_types: ["jacket"]`, `seasons: ["winter"]`, `required_fabric_types: ["wool"]`, `target_price: null` và `budget.upper` có `amount: 350000`, `inclusive: false`. Giá trị `false` biểu diễn cận trên loại trừ, tức giá phải nhỏ hơn 350.000 đồng.

Nếu server dùng địa chỉ khác, đặt `LLAMA_BASE_URL` trong cấu hình IntelliJ. Giá trị mặc định là `http://127.0.0.1:8080`. `LLAMA_MODEL` dùng để chỉ định tên model khi server yêu cầu.

## Cấu trúc

- `FabricExtractorCli.java` nhận câu nhập và in JSON ra console.
- `KeywordCatalog.java` tra các mã chuẩn trong `resources/catalog/fabric-keywords.json`.
- `IntentExtractor.java` kết hợp từ khóa, prompt và schema, gọi model rồi kiểm tra kết quả.
- `LanguageModel.java` định nghĩa giao diện model. `LlamaCppLanguageModel.java` triển khai giao diện để gọi `llama-server`.
- `resources/prompts/extract-intent.txt` chứa hướng dẫn trích xuất.
- `resources/schema/extraction-result.schema.json` quy định cấu trúc JSON đầu ra.

## Kiểm thử

Trong IntelliJ, mở **Maven → Lifecycle → test**. Nếu đã cài Maven trong terminal, chạy `mvn test`. Các bài JUnit dùng phản hồi model giả lập, nên có thể chạy cả khi `llama-server` đang tắt.

### Kiểm thử với Llama thật

Giữ `llama-server` chạy và dùng `mvn -q compile exec:java` cho mỗi câu thử.

| Câu thử | Kết quả đã xác nhận |
| --- | --- |
| May áo khoác mùa đông, chỉ lấy len, dưới 350000 đồng | `jacket`, `winter`, `wool` bắt buộc, cận trên `350000` với `inclusive: false` |
| May quần, ưu tiên cotton, giá tầm 240k/m | `pants`, ưu tiên `cotton`, giá mục tiêu `240000` mỗi mét, `budget: null` |
| So sánh cotton với linen | `compare_fabrics`, hai mã `cotton` và `linen` trong `intent.fabric_types` |
| May sơ mi, chỉ lấy cotton, loại polyester, tối đa 270k/m | `shirt`, bắt buộc `cotton`, loại `polyester`, cận trên `270000` mỗi mét với `inclusive: true` |