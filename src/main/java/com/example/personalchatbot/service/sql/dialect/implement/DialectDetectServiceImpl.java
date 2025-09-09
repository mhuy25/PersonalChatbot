package com.example.personalchatbot.service.sql.dialect.implement;

import com.example.personalchatbot.service.sql.dto.DialectDetectDto;

public interface DialectDetectServiceImpl {
    /**
     * Phát hiện dialect từ nội dung DDL (heuristic + thử parse bằng Druid nếu có thể).
     */
    DialectDetectDto detect(String ddl);
}
