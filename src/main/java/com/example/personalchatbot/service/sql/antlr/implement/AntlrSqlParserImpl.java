package com.example.personalchatbot.service.sql.antlr.implement;

import com.example.personalchatbot.service.sql.dto.MetadataDto;

import java.util.List;

public interface AntlrSqlParserImpl {
    /** Tách script thành các statement top-level, an toàn với ';' nằm trong $$...$$/string/comment */
    List<String> splitStatements(String sql);
    MetadataDto analyze(String statement); // ← dùng chung DTO
}
