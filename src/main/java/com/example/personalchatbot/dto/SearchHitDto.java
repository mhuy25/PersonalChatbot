package com.example.personalchatbot.dto;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

import java.util.UUID;

@Data
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class SearchHitDto {
    UUID id;
    String docId;
    long chunkId;
    String content;
    String metadataJson;
    double distance;   // cosine distance (0 tốt nhất)
    double similarity;  // 1 - distance
}
