package com.example.personalchatbot.service.sql.druid.implement;

import com.example.personalchatbot.service.sql.dto.MetadataDto;

import java.util.List;

/**
 * Facade mỏng cho Druid để tách câu & phân tích metadata theo dialect.
 * Bạn giữ implementation cũ (nếu đã có); service chỉ gọi qua interface này.
 */
public interface DruidFacadeImpl {

    /**
     * Tách script SQL thành các statement.
     * @param sql     toàn bộ script
     * @param dialect "mysql" | "postgresql" | "postgres" | "pg"
     * @return danh sách statement (đã trim)
     * @throws RuntimeException nếu Druid không xử lý được (để caller fallback)
     */
    List<String> split(String sql, String dialect);

    /**
     * Phân tích 1 statement để lấy metadata cơ bản bằng Druid.
     * @param statement một câu SQL đơn lẻ
     * @param dialect   "mysql" | "postgresql" | "postgres" | "pg"
     * @return MetadataDto; nếu không phân tích được sẽ ném RuntimeException
     */
    MetadataDto analyze(String statement, String dialect);
}
