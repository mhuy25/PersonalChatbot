package com.example.personalchatbot.service.implement;

public interface LlmServiceImpl {
    /**
     * Gọi LLM sinh câu trả lời từ cặp (system, user)
     * @param system      system prompt
     * @param user        user prompt
     * @param temperature nhiệt
     * @param maxOutputTokens giới hạn token output (null = dùng mặc định)
     * @return text trả lời
     */
    String generate(String system, String user, double temperature, Integer maxOutputTokens);

    String generate(String system, String user);
}
