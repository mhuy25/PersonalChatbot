package com.example.personalchatbot.service.sql.llm;

import com.example.personalchatbot.dto.PromptDto;
import com.example.personalchatbot.service.sql.llm.implement.SqlPromptServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class SqlPromptService implements SqlPromptServiceImpl {

    @Override
    public PromptDto build(String question, String dialect) {
        final String d = norm(dialect);

        // System prompt: yêu cầu vai trò, output JSON tối giản và kiên định.
        String system =
                "Bạn là chuyên gia phân tích SQL đa dialect (MySQL, PostgreSQL, ...). " +
                        "Nhiệm vụ: nhận MỘT câu SQL và trả về metadata ở dạng JSON THUẦN, không thêm lời giải thích. " +
                        "Quy ước JSON:\n" +
                        "{\n" +
                        "  \"statementType\": string,               // ví dụ: SELECT / INSERT / UPDATE / DELETE / CREATE_TABLE / CREATE_INDEX / CREATE_VIEW / CREATE_FUNCTION / CREATE_PROCEDURE / CREATE_TRIGGER / CREATE_EVENT / các trường hợp còn lại...\n" +
                        "  \"schemaName\": string|null,\n" +
                        "  \"objectName\": string|null,             // tên bảng/view/index/hàm/thủ tục/trigger/event nếu có\n" +
                        "  \"tables\": string[] ,                   // danh sách bảng có liên quan (kể cả alias được quy chiếu về tên bảng thật nếu suy được)\n" +
                        "  \"columns\": string[]                    // danh sách cột (ưu tiên định dạng table.column nếu xác định được)\n" +
                        "}\n" +
                        "YÊU CẦU:\n" +
                        "- Không in thêm text ngoài JSON. Không chú thích, không markdown.\n" +
                        "- Tôn trọng dialect; với MySQL có thể gặp DELIMITER, nhưng đầu vào ở đây luôn là 01 statement đã được tách.\n" +
                        "- Với CREATE INDEX/VIEW: suy ra bảng đích nếu thấy cú pháp ON <table> hoặc trong SELECT của VIEW.\n" +
                        "- Với routine (FUNCTION/PROCEDURE/ TRIGGER/EVENT): statementType phải là CREATE_FUNCTION/CREATE_PROCEDURE/CREATE_TRIGGER/CREATE_EVENT; " +
                        "  objectName là tên routine/trigger/event; tables/columns lấy trong phần thân nếu có thể (nếu không, để rỗng).";

        // User prompt: truyền dialect + SQL
        String user =
                "Dialect: " + (d == null ? "unknown" : d) + "\n" +
                        "SQL:\n" +
                        question;

        return new PromptDto(system, user);
    }

    private static String norm(String d) {
        return d == null ? null : d.trim().toLowerCase();
    }
}
