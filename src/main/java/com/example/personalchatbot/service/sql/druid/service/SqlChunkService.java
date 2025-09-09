package com.example.personalchatbot.service.sql.druid.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLName;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.visitor.SchemaStatVisitor;
import com.alibaba.druid.stat.TableStat;
import com.example.personalchatbot.config.ErrorConfig;
import com.example.personalchatbot.dto.ChunkingOptions;
import com.example.personalchatbot.dto.PromptDto;
import com.example.personalchatbot.service.llm.LlmService;
import com.example.personalchatbot.service.sql.antlr.AntlrRouter;
import com.example.personalchatbot.service.sql.antlr.implement.AntlrSqlParserImpl;
import com.example.personalchatbot.service.sql.antlr.postgres.PgAntlrSqlParser;
import com.example.personalchatbot.service.sql.dto.MetadataDto;
import com.example.personalchatbot.service.sql.dto.SqlChunkDto;
import com.example.personalchatbot.exception.AppException;
import com.example.personalchatbot.service.sql.druid.implement.SqlChunkServiceImpl;
import com.example.personalchatbot.service.sql.llm.SqlPromptService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knuddels.jtokkit.api.Encoding;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SqlChunkService implements SqlChunkServiceImpl {
    private final SqlPromptService sqlPromptService;
    private final LlmService llm;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Encoding embeddingEncoding;
    private final ChunkingOptions sqlOption = new ChunkingOptions(300, 80, 200, Locale.forLanguageTag("vi-VN"), true);
    private static final List<String> CANDIDATES = List.of(
            "getName", "name",
            "getTable", "getTableSource",
            "getOn", "getInto", "getTarget", "getRelation", "getExpr"
    );
    /** Router ANTLR: map theo dbType (lowercase). Hiện mới đăng ký Postgres; các dialect khác thêm sau. */
    private final AntlrRouter antlrRouter = new AntlrRouter()
            .register("postgresql", new PgAntlrSqlParser());
    // .register("mysql", new MyAntlrSqlParser()) ...
    // .register("oracle", new OracleAntlrSqlParser()) ...
    // .register("sqlserver", new SqlServerAntlrSqlParser()) ...

    @Override
    public List<SqlChunkDto> chunk(String sql, String dbType) {
        if (sql == null || sql.isBlank()) return List.of();

        DbType druidDbType;

        try {
            druidDbType  = DbType.valueOf(dbType);
        }
        catch (Exception e) {
            throw new AppException(ErrorConfig.INTERNAL_SERVER_ERROR, "Không thể chunk SQL dialect=" + dbType);
        }

        // 1) Druid trước
        try {
            List<SqlChunkDto> druidChunks = tryDruid(sql, dbType);
            if (!druidChunks.isEmpty()) return reindex(druidChunks);
        } catch (Exception e) {
            log.debug("Druid failed ({}), fallback ANTLR...", e.getMessage());
        }

        List<String> statements;
        AntlrSqlParserImpl parser = null;
        if (antlrRouter.supports(dbType)) {
            try {
                parser = antlrRouter.get(dbType);
                statements = parser.splitStatements(sql);
            } catch (Exception e) {
                log.debug("ANTLR split failed ({}), dùng 1 statement duy nhất.", e.getMessage());
                statements = List.of(sql);
            }
        } else {
            log.debug("ANTLR router chưa có parser cho dialect: {}", dbType);
            statements = List.of(sql);
        }

        // 3) Với mỗi statement: thử Druid(one-by-one) -> ANTLR.analyze; nếu vẫn fail thì gom lại cho LLM
        List<SqlChunkDto> out = new ArrayList<>();
        Map<Integer, String> stmtByIndex = new LinkedHashMap<>();
        Map<Integer, MetadataDto> metaByIndex = new HashMap<>();
        List<Integer> unresolvedIdx = new ArrayList<>();

        for (int i = 0; i < statements.size(); i++) {
            String st = statements.get(i);
            stmtByIndex.put(i, st);

            MetadataDto meta = null;

            // 3.1) Druid parse từng câu (nếu map được DbType)
            try {
                List<SQLStatement> one = SQLUtils.parseStatements(st, druidDbType);
                if (!one.isEmpty()) {
                    meta = buildMetadata(one.getFirst(), dbType);
                }
            } catch (Throwable ignore) {
                // bỏ qua, sẽ thử ANTLR
            }

            // 3.2) ANTLR analyze nếu Druid không ra
            if (meta == null && parser != null) {
                try {
                    meta = parser.analyze(st);
                } catch (Throwable ignore) {
                    // bỏ qua, để LLM xử lý
                }
            }

            if (meta == null) {
                unresolvedIdx.add(i);
            } else {
                metaByIndex.put(i, meta);
            }
        }

        // 4) GỌI LLM CHO TẤT CẢ CÂU CHƯA RESOLVE
        if (!unresolvedIdx.isEmpty()) {
            List<String> needAnalyze = new ArrayList<>(unresolvedIdx.size());
            for (int idx : unresolvedIdx) needAnalyze.add(stmtByIndex.get(idx));

            try {
                // Gợi ý chữ ký: trả về danh sách MetadataDto theo *đúng thứ tự input*
                List<MetadataDto> filled = buildLlmMetadata(needAnalyze, dbType);
                if (!filled.isEmpty()) {
                    for (int j = 0; j < unresolvedIdx.size(); j++) {
                        int idx = unresolvedIdx.get(j);
                        MetadataDto meta = (j < filled.size()) ? filled.get(j) : null;
                        if (meta != null) metaByIndex.put(idx, meta);
                    }
                }
            } catch (Exception e) {
                throw new AppException(ErrorConfig.INTERNAL_SERVER_ERROR, "Không thể chunk SQL dialect=" + dbType);
            }
        }

        // 5) Build kết quả cuối cùng (degrade detectKindFast nếu vẫn chưa có metadata)
        for (int i = 0; i < statements.size(); i++) {
            String st = stmtByIndex.get(i);
            MetadataDto meta = metaByIndex.get(i);

            String kind = (meta != null && meta.getStatementType() != null)
                    ? meta.getStatementType()
                    : detectKindFast(st);

            String schemaName = meta != null ? meta.getSchemaName() : null;
            String objectName = meta != null ? meta.getObjectName() : null;

            out.addAll(buildParts(st, dbType, i, kind, new String[]{schemaName, objectName}, meta));
        }

        if (!out.isEmpty()) return reindex(out);
        throw new AppException(ErrorConfig.INTERNAL_SERVER_ERROR, "Không thể chunk SQL dialect=" + dbType);
    }

    // ============================== DRUID path ==============================

    private List<SqlChunkDto> tryDruid(String sql, String dialect) {
        try {
            DbType dt = DbType.valueOf(dialect);
            List<SQLStatement> statements = SQLUtils.parseStatements(sql, dt);

            List<SqlChunkDto> out = new ArrayList<>();
            int idx = 0;
            for (SQLStatement st : statements) {
                String normalized = SQLUtils.toSQLString(st, dt); // Druid format lại

                // MetadataDto từ Druid
                MetadataDto meta = buildMetadata(st, dialect);

                out.addAll(buildParts(
                        normalized, dialect, idx++, st.getClass().getSimpleName(), meta != null ?
                                new String[]{meta.getSchemaName(), meta.getObjectName()} : new String[]{null, null},
                        meta
                ));
            }
            return out;
        } catch (Exception e) {
            throw new AppException(ErrorConfig.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    private MetadataDto buildMetadata(SQLStatement st, String dialect) {
        DbType dbType = DbType.valueOf(dialect);
        String stmtText = SQLUtils.toSQLString(st, dbType);
        String statementType = detectKindFast(stmtText);
        List<String> druidTables = Collections.emptyList();
        List<String> druidColumns = Collections.emptyList();
        try {
            SchemaStatVisitor visitor = SQLUtils.createSchemaStatVisitor(dbType);
            st.accept(visitor);

            // ĐÚNG kiểu: key là TableStat.Name
            Map<TableStat.Name, TableStat> tmap = visitor.getTables();

            if (tmap != null && !tmap.isEmpty()) {
                druidTables = tmap.keySet().stream()
                        .filter(Objects::nonNull)
                        .map(TableStat.Name::toString)
                        .collect(Collectors.toList());
            }
            if (visitor.getColumns() != null && !visitor.getColumns().isEmpty()) {
                druidColumns = visitor.getColumns().stream()
                        .map(c -> {
                            String tbl = c.getTable();
                            String col = c.getName();
                            return (tbl == null || tbl.isEmpty()) ? col : (tbl + "." + col);
                        })
                        .collect(Collectors.toList());
            }

            statementType = st.getClass().getSimpleName();
            SQLName name = tryGetSQLName(st);
            String[] schemaNames = splitSchemaAndObject(name);

            return MetadataDto.builder()
                    .statementType(statementType)
                    .schemaName(schemaNames[0])
                    .objectName(schemaNames[1])
                    .tables(druidTables)
                    .columns(druidColumns)
                    .build();
        } catch (Throwable t) {
            try {
                MetadataDto antlrMeta;
                if (antlrRouter.supports(dialect)) {
                    AntlrSqlParserImpl antlr = antlrRouter.get(dialect);
                    antlrMeta = antlr.analyze(stmtText);
                    if (antlrMeta != null) {
                        if (antlrMeta.getStatementType() == null) {
                            antlrMeta = antlrMeta.toBuilder()
                                    .statementType(statementType)
                                    .build();
                        }
                        return antlrMeta;
                    }
                    else {
                        throw new AppException(ErrorConfig.INTERNAL_SERVER_ERROR,
                                "Không thể chunk SQL bằng Druid/ANTLR cho dialect=" + dialect);
                    }
                }
                else {
                    throw new AppException(ErrorConfig.INTERNAL_SERVER_ERROR,
                            "Không thể chunk SQL bằng Druid/ANTLR cho dialect=" + dialect);
                }
            } catch (Throwable ignore) {
                log.debug("ANTLR failed , fallback kế tiếp...");
                return null; //sẽ update sau
            }
        }
    }

    private String[] splitSchemaAndObject(SQLName name) {
        if (name instanceof SQLPropertyExpr p) {
            String schema = null;
            if (p.getOwner() instanceof SQLName o) schema = o.getSimpleName();
            else if (p.getOwner() != null) schema = p.getOwner().toString();
            return new String[]{ schema, p.getName() }; // object = phần sau dấu chấm
        }
        if (name != null) {
            return new String[]{ null, name.getSimpleName() };
        }
        return new String[]{ null, null };
    }

    // ============================== LLM Path ====================================

    private List<MetadataDto> buildLlmMetadata(List<String> sqlStatements, String dialect) {
        List<MetadataDto> resultList = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        sb.append("DIALECT: ").append(dialect == null ? "unknown" : dialect).append("\n\n");
        sb.append("STATEMENTS:\n");
        for (int i = 0; i < sqlStatements.size(); i++) {
            sb.append(i + 1).append(") ").append(sqlStatements.get(i)).append("\n\n");
        }
        String question = sb.toString();

        PromptDto prompt = sqlPromptService.build(question, null);
        String text = llm.generate(prompt.getSystem(), prompt.getUser());

        String json = text == null ? "" : text.trim();
        int l = json.indexOf('[');
        int r = json.lastIndexOf(']');
        if (l >= 0 && r >= l) {
            json = json.substring(l, r + 1);
        }

        // 5) Parse JSON -> map thành MetadataDto theo đúng thứ tự
        try {
            // POJO tạm để map JSON
            record LlmItem(String statementType, String schemaName, String objectName,
                           List<String> tables, List<String> columns) {}

            List<LlmItem> items = objectMapper.readValue(
                    json, new TypeReference<List<LlmItem>>() {});

            for (int i = 0; i < sqlStatements.size(); i++) {
                LlmItem it = (items != null && i < items.size()) ? items.get(i) : null;
                if (it == null) {
                    resultList.add(new MetadataDto());
                    continue;
                }
                // Chuẩn hoá type
                String type = it.statementType();
                if (!"CREATE_TABLE".equalsIgnoreCase(type) &&
                        !"CREATE_INDEX".equalsIgnoreCase(type)) {
                    type = "STATEMENT";
                } else {
                    type = type.toUpperCase();
                }

                MetadataDto m = new MetadataDto();
                m.setStatementType(type);
                m.setSchemaName(null); // theo rule: luôn null
                m.setObjectName(trimToNull(it.objectName()));
                m.setTables(safeList(it.tables()));
                m.setColumns(limit100(safeList(it.columns())));

                resultList.add(m);
            }
        } catch (Exception parseEx) {
            // Nếu parse thất bại, degrade từng statement
            for (int i = 0; i < sqlStatements.size(); i++) {
                resultList.add(new MetadataDto());
            }
        }

        return resultList;
    }

    // ============================== chunk builders ==============================

    private List<SqlChunkDto> buildParts(String statement,
                                         String dialect,
                                         int stmtIndex,
                                         String kind,
                                         String[] names,
                                         MetadataDto meta) {

        List<String> slices = sliceByTokens(statement, sqlOption.getMaxTokens(), sqlOption.getOverlapTokens());
        int total = slices.size();

        Map<String, Object> metaJson = toMetadataMap(meta);

        List<SqlChunkDto> out = new ArrayList<>(total);
        for (int p = 0; p < total; p++) {
            String content = slices.get(p);
            out.add(SqlChunkDto.builder()
                    .index(stmtIndex)
                    .part(p + 1)
                    .totalParts(total)
                    .dialect(dialect)
                    .kind(kind)
                    .schemaName(names[0])
                    .objectName(names[1])
                    .content(content)
                    .metadataJson(safeJson(metaJson))
                    .tokenCount(countTokens(content))
                    .build());
        }
        return out;
    }

    // ============================== tokenization (jtokkit) ==============================

    private int countTokens(String s) { return embeddingEncoding.countTokens(s); }

    private List<String> sliceByTokens(String text, int maxTokens, int overlapTokens) {
        if (text == null || text.isBlank()) return List.of();

        // Mã hoá ra token IDs bằng jtokkit
        List<Integer> ids = embeddingEncoding.encode(text);

        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < ids.size()) {
            int end = Math.min(ids.size(), i + maxTokens);
            // decode đoạn [i, end)
            String chunk = embeddingEncoding.decode(ids.subList(i, end));
            out.add(chunk);
            if (end == ids.size()) break;
            i = Math.max(end - overlapTokens, i + 1);
        }
        return out;
    }

    // ============================== helper ==============================

    private Map<String, Object> toMetadataMap(MetadataDto m) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (m == null) return map;
        if (m.getStatementType() != null) map.put("statementType", m.getStatementType());
        if (m.getSchemaName()   != null) map.put("schemaName",   m.getSchemaName());
        if (m.getObjectName()   != null) map.put("objectName",   m.getObjectName());
        if (m.getTables()       != null && !m.getTables().isEmpty())  map.put("tables",  m.getTables());
        if (m.getColumns()      != null && !m.getColumns().isEmpty()) map.put("columns", m.getColumns());
        return map;
    }

    private String safeJson(Object o) {
        try { return objectMapper.writeValueAsString(o); }
        catch (Exception e) { return "{}"; }
    }

    private List<SqlChunkDto> reindex(List<SqlChunkDto> in) {
        int i = 0;
        List<SqlChunkDto> out = new ArrayList<>(in.size());
        for (SqlChunkDto c : in) out.add(c.toBuilder().index(i++).build());
        return out;
    }

    private String detectKindFast(String s) {
        String x = s.trim().toUpperCase(Locale.ROOT);
        if (x.startsWith("CREATE OR REPLACE FUNCTION") || x.startsWith("CREATE FUNCTION")) return "CREATE_FUNCTION";
        if (x.startsWith("CREATE OR REPLACE PROCEDURE") || x.startsWith("CREATE PROCEDURE")) return "CREATE_PROCEDURE";
        if (x.startsWith("CREATE OR REPLACE VIEW") || x.startsWith("CREATE VIEW")) return "CREATE_VIEW";
        if (x.startsWith("CREATE TABLE")) return "CREATE_TABLE";
        /*if (x.startsWith("SELECT")) return "SELECT";
        if (x.startsWith("INSERT")) return "INSERT";
        if (x.startsWith("UPDATE")) return "UPDATE";
        if (x.startsWith("DELETE")) return "DELETE";*/
        return "RAW_STATEMENT";
    }

    private SQLName tryGetSQLName(SQLStatement st) {
        for (String m : CANDIDATES) {
            Method mm = findNoArg(st.getClass(), m);
            if (mm == null) continue;
            try {
                Object v = mm.invoke(st);
                // Trực tiếp là SQLName?
                if (v instanceof SQLName) return (SQLName) v;

                // Bảng dạng table source?
                if (v instanceof SQLExprTableSource ts) {
                    SQLExpr e = ts.getExpr();
                    if (e instanceof SQLName) return (SQLName) e;
                }

                // Có getExpr() trả về SQLName?
                Method getExpr = findNoArg(v.getClass(), "getExpr");
                if (getExpr != null) {
                    Object expr = getExpr.invoke(v);
                    if (expr instanceof SQLName) return (SQLName) expr;
                }
            } catch (Throwable ignore) {}
        }
        return null;
    }

    private Method findNoArg(Class<?> c, String name) {
        try { Method m = c.getMethod(name); m.setAccessible(true); return m; }
        catch (NoSuchMethodException e) { return null; }
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static List<String> safeList(List<String> in) {
        return (in == null) ? List.of() : in.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }

    private static List<String> limit100(List<String> in) {
        return in.size() <= 100 ? in : in.subList(0, 100);
    }
}
