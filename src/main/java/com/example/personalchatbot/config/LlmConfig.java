package com.example.personalchatbot.config;

import lombok.AccessLevel;
import lombok.Data;
import lombok.experimental.FieldDefaults;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Data
@FieldDefaults(level = AccessLevel.PRIVATE)
public class LlmConfig {

    @Value("${llm.model}")
    String model;

    @Value("${llm.temperature}")
    double temperature;

    @Value("${llm.max-output-token}")
    int outputToken;

    @Value("${llm.max-output-token-metadata}")
    int outputTokenMetadata;
}
