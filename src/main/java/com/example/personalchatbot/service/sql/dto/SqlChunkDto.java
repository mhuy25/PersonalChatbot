package com.example.personalchatbot.service.sql.dto;


import lombok.*;
import lombok.experimental.FieldDefaults;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class SqlChunkDto {
    int index;                 // vị trí trong toàn bộ kết quả (0-based)
    int part;                  // phần thứ m (1-based) của một statement (nếu có chia nhỏ)
    int totalParts;            // tổng phần của statement đó
    String dialect;            // ví dụ: "postgresql" / "oracle" / ...
    String kind;               // CREATE_TABLE / FUNCTION_SIG / FUNCTION_BODY / PROCEDURE_SIG / ...
    String schemaName;         // schema (nếu xác định được)
    String objectName;         // tên object (bảng/hàm/thủ tục)
    String content;            // nội dung chunk (đã cắt ≤ 300 tokens)
    String metadataJson;       // metadata dạng JSON (tables/columns/relationships/…)
    int tokenCount;            // số token của content
}
