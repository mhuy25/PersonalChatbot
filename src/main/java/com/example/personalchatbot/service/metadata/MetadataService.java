package com.example.personalchatbot.service.metadata;

import com.example.personalchatbot.config.ErrorConfig;
import com.example.personalchatbot.config.LlmConfig;
import com.example.personalchatbot.dto.MetadataDto;
import com.example.personalchatbot.exception.AppException;
import com.example.personalchatbot.service.implement.MetadataServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
@Service
public class MetadataService implements MetadataServiceImpl {
    private final WebClient embeddingWebClient; // đã cấu hình baseUrl + Authorization
    private final LlmConfig llmConfig;
    private final ObjectMapper om;

    @Override
    public MetadataDto infer(String question, Locale locale) {
        try {
            if (question == null || question.isBlank()) return null;

            // Prompt: ép trả JSON THUẦN với các field cần
            String system = """
              You classify a short user question into fixed metadata fields and MUST reply with pure JSON only.
              Fields:
                - project: string or null (e.g., "Finex", "Lending")
                - module: string or null (e.g., "Payments", "Approvals")
                - env: one of ["DEV","UAT","SIT","STG","PROD"] or null; map synonyms (staging->STG, production->PROD)
                - title: short title (<=80 chars) summarizing the question
                - path: string or null (file/path hint if obvious)
                - extra: object (can include detected keywords)
              Do not include any explanation or extra text outside JSON.
            """;

            Map<String,Object> body = Map.of(
                    "model", llmConfig.getModel(),
                    "temperature", llmConfig.getTemperature(),
                    "max_output_tokens", llmConfig.getOutputTokenMetadata(),
                    // ép JSON output (Responses API hỗ trợ response_format json_object)
                    "text", Map.of("format", Map.of("type","json_object")),
                    "input", List.of(
                            Map.of(
                                    "role","system",
                                    "content", List.of(
                                            Map.of("type","input_text", "text", system)
                                    )
                            ),
                            Map.of(
                                    "role","user",
                                    "content", List.of(
                                            Map.of("type","input_text", "text", question)
                                    )
                            )
                    )
            );

            Map<?,?> resp = embeddingWebClient.post()
                    .uri("/responses")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, errorResp ->
                            errorResp.bodyToMono(String.class).flatMap(err -> {
                                log.error("OpenAI {}: {}", errorResp.statusCode(), err); // <-- xem message cụ thể
                                return Mono.error(new RuntimeException(err));
                            })
                    )
                    .bodyToMono(Map.class)
                    .block();

            String json = extractJsonString(resp);   // bóc phần text JSON
            if (json == null || json.isBlank()) {
                // fallback cực tối thiểu: chỉ gán title
                return new MetadataDto(null, null, null, trim(question,80), null, Map.of());
            }

            // Parse JSON → lấy trường
            JsonNode root = om.readTree(json);
            String project = getStr(root,"project");
            String module  = getStr(root,"module");
            String env     = normalizeEnv(getStr(root,"env")); // chuẩn hoá DEV/UAT/SIT/STG/PROD
            String title   = optTrim(getStr(root,"title"), 80);
            String path    = getStr(root,"path");

            Map<String,Object> extra = new HashMap<>();
            JsonNode ex = root.get("extra");
            if (ex != null && ex.isObject()) {
                ex.fields().forEachRemaining(e -> extra.put(e.getKey(), e.getValue().isValueNode() ? e.getValue().asText() : e.getValue().toString()));
            }
            // luôn thêm vài dấu vết nhẹ
            extra.putIfAbsent("lang", locale!=null ? locale.toLanguageTag() : Locale.forLanguageTag("vi-VN"));
            extra.put("classifiedBy", llmConfig.getModel());
            return new MetadataDto(project, module, env, title!=null?title:trim(question,80), path, extra);
        } catch (Exception e) {
            throw new AppException(ErrorConfig.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    // ----- helpers -----

    // Lấy text JSON từ Responses API: output[0].content[0].text
    private String extractJsonString(Map<?,?> resp) {
        Object ot = resp.get("output_text");
        if (ot instanceof String s && !s.isBlank()) return s;

        Object output = resp.get("output");
        if (!(output instanceof List<?> outList)) return null;

        StringBuilder sb = new StringBuilder();
        for (Object msg : outList) {
            if (!(msg instanceof Map<?,?> m)) continue;
            Object content = m.get("content");
            if (!(content instanceof List<?> items)) continue;
            for (Object it : items) {
                if (!(it instanceof Map<?,?> c)) continue;
                Object type = c.get("type");
                // ƯU TIÊN output_text; fallback summary_text (nếu có)
                if ("output_text".equals(type) || "summary_text".equals(type)) {
                    Object t = c.get("text");
                    if (t instanceof String str) sb.append(str);
                }
            }
        }
        String s = sb.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static String getStr(JsonNode root, String key) {
        JsonNode n = root.get(key);
        return (n!=null && !n.isNull()) ? n.asText(null) : null;
    }

    private static String normalizeEnv(String env) {
        if (env == null) return null;
        String e = env.trim().toUpperCase(Locale.ROOT);
        return switch (e) {
            case "STAGING","STG" -> "STG";
            case "PRODUCTION"    -> "PROD";
            case "DEV","UAT","SIT","PROD" -> e;
            default -> null;
        };
    }

    private static String trim(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max-1) + "…";
    }
    private static String optTrim(String s, int max) { return s==null?null:trim(s,max); }
}
