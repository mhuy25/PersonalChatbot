package com.example.personalchatbot.dto;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
@ConfigurationProperties(prefix = "embedding")
public class  EmbeddingDto {
    String provider;          // "openai" (mặc định)
    String baseUrl;           // https://api.openai.com/v1
    String apiKey;            // lấy từ ENV/Secret
    String model;             // text-embedding-3-small
    Integer expectedDim;      // 1536
    Boolean normalize;        // true
    Integer batchSize;         // 64
}
