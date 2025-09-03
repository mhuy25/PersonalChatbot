package com.example.personalchatbot.dto;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

import java.util.Locale;

@Data
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class ChunkingOptions {
    int maxTokens;          // mặc định ~500
    int overlapTokens;      // mặc định ~80
    int minChunkTokens;     // bỏ qua mẩu quá ngắn
    Locale locale;          // vi-VN/en-US...
    Boolean markdownAware;   // ưu tiên tách theo heading/paragraph
}
