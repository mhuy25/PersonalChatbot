package com.example.personalchatbot.service.implement;

import com.example.personalchatbot.dto.AnswerDto;

import java.util.Map;

public interface RagServiceImpl {
    AnswerDto answer(String question,
                     int topK,
                     int keepN,
                     Map<String,String> filters,
                     double temperature,
                     Integer maxOutputTokens);
}
