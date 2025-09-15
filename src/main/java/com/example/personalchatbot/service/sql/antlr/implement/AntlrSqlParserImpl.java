package com.example.personalchatbot.service.sql.antlr.implement;

import com.example.personalchatbot.service.sql.dto.MetadataDto;
import com.example.personalchatbot.service.sql.dto.SqlChunkDto;

import java.util.List;

public interface AntlrSqlParserImpl {
    String dialect();

    /** Tách script thành các statement top-level, an toàn với ';' nằm trong $$...$$/string/comment */
    /**
     * Tách script thành các câu SQL; KHÔNG dùng regex.
     * Parser chịu trách nhiệm xử lý construct đặc thù (ví dụ DELIMITER của MySQL).
     */
    List<SqlChunkDto> split(String sql);

    MetadataDto analyze(String statement); // ← dùng chung DTO
}
