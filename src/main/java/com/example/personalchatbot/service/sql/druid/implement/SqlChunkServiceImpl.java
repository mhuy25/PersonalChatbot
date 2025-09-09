package com.example.personalchatbot.service.sql.druid.implement;

import com.example.personalchatbot.service.sql.dto.SqlChunkDto;

import java.util.List;

public interface SqlChunkServiceImpl {

    /**
     * Parse & chunk chuỗi SQL theo dialect.
     */
    List<SqlChunkDto> chunk(String sql, String dbType);
}