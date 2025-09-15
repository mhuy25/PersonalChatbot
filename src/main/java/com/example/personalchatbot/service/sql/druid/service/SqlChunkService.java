package com.example.personalchatbot.service.sql.druid.service;

import com.example.personalchatbot.service.llm.LlmService;
import com.example.personalchatbot.service.sql.antlr.SqlParserRegistry;
import com.example.personalchatbot.service.sql.antlr.implement.AntlrSqlParserImpl;
import com.example.personalchatbot.service.sql.dto.MetadataDto;
import com.example.personalchatbot.service.sql.dto.SqlChunkDto;
import com.example.personalchatbot.service.sql.druid.implement.SqlChunkServiceImpl;
import com.example.personalchatbot.service.sql.llm.SqlPromptService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * SqlChunkService
 * - Luồng parse: try Druid -> fail -> try ANTLR -> fail -> try LLM.
 * - Với routine (FUNCTION/PROCEDURE): tách header + chia body theo các khối cấp 1 (BEGIN/IF/CASE/LOOP),
 *   sau đó split thành các câu SQL và gom metadata vào field bodySqlMetaJson; flush theo KHỐI (không nhồi chéo khối).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SqlChunkService implements SqlChunkServiceImpl {

    private final DruidFacade druid;
    private final SqlParserRegistry registry;
    private final SqlPromptService promptService;
    private final LlmService llm;

    /* ================= Split ================= */

    @Override
    public List<SqlChunkDto> split(String sql, String dialect) {
        final String d = norm(dialect);

        // 1) try Druid
        try {
            List<String> parts = druid.split(sql, d);
            log.info("Split bằng Druid");
            List<SqlChunkDto> chunks = new ArrayList<>(parts.size());
            int idx = 0;
            for (String p : parts) {
                if (p == null || p.trim().isEmpty()) continue;
                chunks.add(SqlChunkDto.builder()
                        .index(idx++)
                        .part(1)
                        .totalParts(1)
                        .dialect(d)
                        .kind(null)                 // sẽ fill ở bước analyze
                        .content(p)
                        .build());
            }
            if (!chunks.isEmpty()) return chunks;
        } catch (Exception ignore) {
            // fallback xuống ANTLR
        }

        // 2) fallback ANTLR theo dialect
        AntlrSqlParserImpl antlr = registry.antlrFor(d).orElse(null);
        if (antlr == null) {
            throw new UnsupportedOperationException("No ANTLR parser for dialect: " + dialect);
        }
        try {
            List<SqlChunkDto> chunks = antlr.split(sql);
            log.info("Split bằng ANTLR 4");
            // đảm bảo index liên tục
            int i = 0;
            for (SqlChunkDto c : chunks) {
                c.setIndex(i++);
                c.setDialect(d);
            }
            return chunks;
        } catch (Exception e) {
            // 3) hết fallback cho split
            throw new RuntimeException("Split failed after Druid & ANTLR: " + e.getMessage(), e);
        }
    }

    /* ================= Analyze (metadata) ================= */

    @Override
    public List<SqlChunkDto> analyzeAndEnrich(List<SqlChunkDto> chunks, String dialect) {
        final String d = norm(dialect);
        for (SqlChunkDto c : chunks) {
            MetadataDto md = null;

            // 1) Druid
            try {
                md = druid.analyze(c.getContent(), d);
                log.info("Lấy metadata bằn Druid cho câu SQL :{}", c.getContent());
            } catch (Exception ignore) { /* fallback */ }

            // 2) ANTLR
            if (md == null || isRaw(md)) {
                Optional<AntlrSqlParserImpl> opt = registry.antlrFor(d);
                if (opt.isPresent()) {
                    try {
                        md = opt.get().analyze(c.getContent());
                        log.info("Lấy metadata bằn ANTLR 4 cho câu SQL :{}", c.getContent());
                    } catch (Exception ignore) { /* fallback */ }
                }
            }

            // 3) LLM (chỉ metadata – không split)
            if (md == null || isRaw(md)) {
                try {
                    // 3) Build prompt từ câu hỏi + context đã chọn
                    var prompt = promptService.build(c.getContent(), d);

                    // 4) Gọi LLM sinh câu trả lời
                    String text = llm.generate(prompt.getSystem(), prompt.getUser());
                    log.info("Lấy metadata bằn LLM cho câu SQL :{}", c.getContent());
                    md = parseLlmMetadata(text);
                } catch (Exception e) {
                    // vẫn gán metadata RAW để không chặn cả pipeline
                    md = MetadataDto.builder().statementType("RAW_STATEMENT").build();
                }
            }

            // set kind/object/schema/columns...
            c.setKind(kindFrom(md));
            c.setSchemaName(md.getSchemaName());
            c.setObjectName(md.getObjectName());
            c.setMetadata(md); // nếu SqlChunkDto có trường metadata; nếu không thì set từng field tương ứng
        }
        return chunks;
    }

    /* ================= Helpers ================= */

    private static boolean isRaw(MetadataDto md) {
        if (md == null) return true;
        String t = md.getStatementType();
        return t == null || "RAW_STATEMENT".equalsIgnoreCase(t.trim());
    }

    private static String kindFrom(MetadataDto md) {
        if (md == null || md.getStatementType() == null) return "RAW_STATEMENT";
        return md.getStatementType();
    }

    private static String norm(String d) {
        return d == null ? "" : d.trim().toLowerCase(Locale.ROOT);
    }

    private MetadataDto parseLlmMetadata(String llmText) {
        try {
            if (llmText == null || llmText.isBlank()) {
                return MetadataDto.builder().statementType("RAW_STATEMENT").build();
            }

            // 1) Lấy block JSON đầu tiên trong câu trả lời (kể cả khi có ```json ... ```)
            String s = llmText.trim();
            int startHint = -1;
            String lower = s.toLowerCase();
            int fence = lower.indexOf("```json");
            if (fence >= 0) {
                startHint = s.indexOf('{', fence);
            }
            if (startHint < 0) startHint = s.indexOf('{');
            if (startHint < 0) {
                return MetadataDto.builder().statementType("RAW_STATEMENT").build();
            }

            int depth = 0, end = -1;
            boolean inStr = false;
            char quote = 0;

            for (int i = startHint; i < s.length(); i++) {
                char c = s.charAt(i);
                if (inStr) {
                    // kết thúc chuỗi khi gặp quote cùng loại và trước đó không phải escape '\'
                    if (c == quote && s.charAt(i - 1) != '\\') inStr = false;
                } else {
                    if (c == '"' || c == '\'') {
                        inStr = true; quote = c;
                    } else if (c == '{') {
                        depth++;
                    } else if (c == '}') {
                        depth--;
                        if (depth == 0) { end = i; break; }
                    }
                }
            }
            if (end < 0) {
                return MetadataDto.builder().statementType("RAW_STATEMENT").build();
            }

            String json = s.substring(startHint, end + 1);

            // 2) Map JSON -> holder đơn giản rồi build MetadataDto
            ObjectMapper om = new ObjectMapper()
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

            class Raw {
                public String statementType;
                public String schemaName;
                public String objectName;
                public List<String> tables;
                public List<String> columns;
            }
            Raw r = om.readValue(json, Raw.class);

            String st = (r.statementType == null || r.statementType.isBlank())
                    ? "RAW_STATEMENT" : r.statementType.trim();
            String schema = (r.schemaName == null || r.schemaName.isBlank())
                    ? null : r.schemaName.trim();
            String object = (r.objectName == null || r.objectName.isBlank())
                    ? null : r.objectName.trim();

            List<String> tables = (r.tables == null) ? Collections.emptyList()
                    : r.tables.stream().filter(t -> t != null && !t.isBlank())
                    .map(String::trim).toList();
            List<String> columns = (r.columns == null) ? Collections.emptyList()
                    : r.columns.stream().filter(t -> t != null && !t.isBlank())
                    .map(String::trim).toList();

            return MetadataDto.builder()
                    .statementType(st)
                    .schemaName(schema)
                    .objectName(object)
                    .tables(new ArrayList<>(tables))
                    .columns(new ArrayList<>(columns))
                    .build();

        } catch (Exception e) {
            // Bất kỳ lỗi nào -> trả RAW để không chặn pipeline
            return MetadataDto.builder().statementType("RAW_STATEMENT").build();
        }
    }
}
