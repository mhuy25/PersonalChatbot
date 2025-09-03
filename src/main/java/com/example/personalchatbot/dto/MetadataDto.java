package com.example.personalchatbot.dto;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;

import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class MetadataDto {
    String project;
    String module;
    String env;
    String title;
    String path;
    Map<String, Object> extra; // optional: tag/version/owner...
}
