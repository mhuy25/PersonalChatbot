package com.example.personalchatbot.service.implement;

import com.example.personalchatbot.dto.PromptDto;
import com.example.personalchatbot.dto.SearchHitDto;

import java.util.List;

public interface PromptServiceImpl {
    PromptDto build(String question, List<SearchHitDto> contextHits);
}
