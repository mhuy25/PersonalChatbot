package com.example.personalchatbot.service.sql.llm.implement;

import com.example.personalchatbot.dto.PromptDto;

public interface SqlPromptServiceImpl {

    /**
     * Dựng prompt & gọi LLM để suy luận Metadata cho 1 câu SQL.
     * @param statement câu SQL đơn lẻ
     * @param dialect   tên DbType (ví dụ: "mysql", "postgresql"...)
     */
    PromptDto build(String statement, String dialect);
}
