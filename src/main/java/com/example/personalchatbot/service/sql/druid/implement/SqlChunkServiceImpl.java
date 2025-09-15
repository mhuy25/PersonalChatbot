package com.example.personalchatbot.service.sql.druid.implement;

import com.example.personalchatbot.service.sql.dto.SqlChunkDto;

import java.util.List;

public interface SqlChunkServiceImpl {
    /** Chunk theo pipeline: Druid → Antlr(dialect) → (else) error. */
    List<SqlChunkDto> split(String sql, String dialect);

    /** Enrich metadata cho từng chunk theo pipeline: Druid → Antlr(dialect) → LLM. */
    List<SqlChunkDto> analyzeAndEnrich(List<SqlChunkDto> chunks, String dialect);
}