package com.example.personalchatbot.controller;

import com.example.personalchatbot.config.LlmConfig;
import com.example.personalchatbot.dto.AnswerDto;
import com.example.personalchatbot.dto.MetadataDto;
import com.example.personalchatbot.service.sql.dto.SqlChunkDto;
import com.example.personalchatbot.dto.request.MessageRequest;
import com.example.personalchatbot.service.sql.dialect.service.DialectDetectService;
import com.example.personalchatbot.service.sql.druid.service.SqlChunkService;
import com.example.personalchatbot.service.metadata.MetadataService;
import com.example.personalchatbot.service.rag.RagService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/")
public class ChatbotController {
    private final MetadataService metadataService;
    private final SqlChunkService sqlChunkService;
    private final DialectDetectService dialectDetectService;
    private final RagService ragService;
    private final LlmConfig llmConfig;

    @PostMapping("/chatbot")
    public ResponseEntity<String> onMessage(@RequestBody MessageRequest messageRequest) {
        try {
            String message = messageRequest.getMessage();
            if (message == null || message.isEmpty() || message.trim().isEmpty()) {
                return ResponseEntity.badRequest().body("Message is empty");
            }
            else {
                MetadataDto inferred = metadataService.infer(message, Locale.forLanguageTag("vi-VN"));
                Map<String,String> filters  = metadataService.toFilters(inferred);

                AnswerDto ans = ragService.answer(
                        message,
                        60,   // topK
                        8,    // keepN
                        filters,
                        0.2,  // temperature
                        llmConfig.getOutputToken()   // max output tokens
                );

                return ResponseEntity.ok().body(ans.getText());
            }
        } catch (Exception e) {
            log.error("Message xử lý lỗi: ", e);
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    @PostMapping("/sqlchunk")
    public List<SqlChunkDto> onSqlChunk(@RequestBody String  sql, @RequestHeader String dialect, HttpServletResponse response) {
        try {
            if (sql == null || sql.trim().isEmpty()) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                return null;
            }
            else {
                /*DialectDetectDto dialect = dialectDetectService.detect(sql);
                if (dialect == null || dialect.getDbType() == null || dialect.getDbType().isEmpty() || dialect.getDbType().equals(String.valueOf(DbType.other))) {
                    return ResponseEntity.internalServerError().body("Không tìm thấy dialect phù hợp!");
                }
                else {
                    return ResponseEntity.ok().body(sqlChunkService.chunk(sql, dialect.getDbType()).toString());
                }*/
                response.setStatus(HttpServletResponse.SC_OK);
                List<SqlChunkDto> chunks = sqlChunkService.split(sql, dialect);
                return sqlChunkService.analyzeAndEnrich(chunks, dialect);
            }
        } catch (Exception e) {
            log.error("Message xử lý lỗi: ", e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return null;
        }
    }
}

