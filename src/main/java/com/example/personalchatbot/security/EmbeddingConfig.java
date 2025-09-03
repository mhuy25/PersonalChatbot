package com.example.personalchatbot.security;


import com.example.personalchatbot.dto.EmbeddingDto;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@EnableConfigurationProperties(EmbeddingDto.class)
public class EmbeddingConfig {

    @Bean
    WebClient embeddingWebClient(EmbeddingDto embeddingDto) {
        return WebClient.builder()
                .baseUrl(embeddingDto.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + embeddingDto.getApiKey())
                .build();
    }
}
