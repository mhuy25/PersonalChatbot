package com.example.personalchatbot.service.sql.dto;

import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.Collections;
import java.util.List;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class MetadataDto {
    String statementType;      // CREATE_TABLE | CREATE_INDEX | STATEMENT
    String schemaName;         // grammar hiện tại không có schema -> null
    String objectName;         // tableName / indexName
    List<String> tables;       // với CREATE_INDEX: [tableName]
    List<String> columns;

    public static MetadataDto minimal(String kind) {
        return new MetadataDto(kind, null, null, Collections.emptyList(), Collections.emptyList());
    }
}
