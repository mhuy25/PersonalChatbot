package com.example.personalchatbot.config;

import com.knuddels.jtokkit.api.*;
import com.knuddels.jtokkit.*;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TokenizerConfig {
    @Bean
    public Encoding embeddingEncoding() {
        EncodingRegistry reg = Encodings.newDefaultEncodingRegistry();
        // CL100K_BASE: dùng được cho đa số embedding OpenAI hiện tại
        return reg.getEncoding(EncodingType.CL100K_BASE);
        // Nếu bạn dùng model có encoding O200K_BASE thì đổi ở đây.
    }
}
