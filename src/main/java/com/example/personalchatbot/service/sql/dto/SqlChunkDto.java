package com.example.personalchatbot.service.sql.dto;


import lombok.*;
import lombok.experimental.FieldDefaults;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class SqlChunkDto {

    private int index;       // vị trí trong toàn bộ script
    private int part;        // dùng khi câu bị chia nhiều phần (body routine)
    private int totalParts;  // tổng số phần
    private String dialect;  // tên DbType normalize (vd: "mysql", "postgresql")
    private String kind;     // CREATE_TABLE / CREATE_INDEX / STATEMENT / RAW_STATEMENT ...
    private String schemaName;
    private String objectName;
    private String content;  // nội dung câu

    // giữ metadata dạng object (để service set vào: c.setMetadata(md))
    private MetadataDto metadata;

    // nếu cần đính kèm JSON metadata phụ (ví dụ meta của từng statement trong body routine)
    private String metadataJson;
}
