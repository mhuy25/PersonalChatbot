package com.example.personalchatbot.service.sql.druid.service;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.*;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;
import com.alibaba.druid.sql.ast.statement.*;
import com.alibaba.druid.sql.dialect.mysql.visitor.MySqlSchemaStatVisitor;
import com.alibaba.druid.sql.dialect.postgresql.visitor.PGSchemaStatVisitor;
import com.alibaba.druid.sql.visitor.SchemaStatVisitor;
import com.example.personalchatbot.service.sql.druid.implement.DruidFacadeImpl;
import com.example.personalchatbot.service.sql.dto.MetadataDto;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Facade cho Alibaba Druid: tách câu & lấy metadata cho từng câu.
 * - splitStatements(): Dùng Druid splitter (không regex). Nếu Druid fail, ném Exception để caller fallback sang ANTLR.
 * - analyze(): Dùng Druid AST để suy ra MetadataDto (CREATE TABLE/INDEX có type riêng; còn lại "RAW_STATEMENT").
 * Lưu ý:
 *   - Class này KHÔNG xử lý fallback. Fallback (ANTLR/LLM) nằm ở tầng service (SqlChunkServiceImpl) theo flow đã thống nhất.
 *   - Với các dialect ngoài MySQL/PostgreSQL, class sẽ ném UnsupportedOperationException để tầng trên quyết định fallback.
 */
@Service
public class DruidFacade implements DruidFacadeImpl {

    /* ===================== SPLIT ===================== */

    @Override
    public List<String> split(String sql, String dialect) {
        try {
            final DbType dbType = DbType.of(dialect); // có thể trả null -> để Druid tự xử/throw
            // Dùng parseStatements để cắt an toàn (không regex, handle ; trong string/comment)
            final List<SQLStatement> list = SQLUtils.parseStatements(sql, dbType);
            if (list.isEmpty() && !sql.trim().isEmpty()) {
                throw new IllegalStateException("Druid parsed no statements.");
            }
            final List<String> out = new ArrayList<>(list.size());
            for (SQLStatement st : list) {
                out.add(SQLUtils.toSQLString(st, dbType).trim());
            }
            return out;
        } catch (Throwable t) {
            // Cho service biết Druid split không xử lý được -> fallback ANTLR.split
            throw new RuntimeException("Druid split failed: " + t.getMessage(), t);
        }
    }

    /* ===================== ANALYZE ===================== */

    @Override
    public MetadataDto analyze(String statement, String dialect) {
        try {
            final DbType dbType = DbType.of(dialect);
            final List<SQLStatement> stmts = SQLUtils.parseStatements(statement, dbType);
            if (stmts.isEmpty()) {
                throw new IllegalStateException("No statement parsed by Druid.");
            }
            final SQLStatement st = stmts.getFirst();
            // CREATE TABLE
            if (st instanceof SQLCreateTableStatement c) {
                QName q = qnameOf(c.getName());
                List<String> cols = extractColumnsFromCreateTable(c);
                return MetadataDto.builder()
                        .statementType(st.getClass().getSimpleName())
                        .schemaName(q.schema)
                        .objectName(q.object)
                        .tables(q.object == null ? Collections.emptyList() : Collections.singletonList(q.objectFull()))
                        .columns(cols)
                        .build();
            }

            // CREATE INDEX (generic)
            if (st instanceof SQLCreateIndexStatement c) {
                QName q = qnameOf(c.getName());
                QName tbl = qnameOf(c.getTable());
                List<String> cols = extractColumnsFromCreateIndex(c);
                List<String> tables = tbl.object == null ? Collections.emptyList() : Collections.singletonList(tbl.objectFull());
                return MetadataDto.builder()
                        .statementType(st.getClass().getSimpleName())
                        .schemaName(q.schema)
                        .objectName(q.object)
                        .tables(tables)
                        .columns(cols)
                        .build();
            }

            // Các loại còn lại: lấy tables/columns cơ bản bằng visitor (nếu có)
            SchemaStatVisitor visitor = createVisitor(dbType);
            if (visitor != null) {
                st.accept(visitor);

                List<String> tables = new ArrayList<>();
                visitor.getTables().forEach((name, stat) -> tables.add(String.valueOf(name)));

                List<String> columns = new ArrayList<>();
                visitor.getColumns().forEach(col -> {
                    String tbl = col.getTable() == null ? "" : col.getTable();
                    String nm  = col.getName()  == null ? "" : col.getName();
                    if (!nm.isEmpty()) columns.add(tbl.isEmpty() ? nm : (tbl + "." + nm));
                });

                String schemaName = visitor.getRepository().getDefaultSchemaName();
                String objectName = null;

                if (st instanceof SQLCreateStatement c) {
                    QName idx = qnameOf(c.getName());
                    schemaName = idx.schema;
                    objectName = idx.object;
                }

                return MetadataDto.builder()
                        .statementType(st.getClass().getSimpleName())
                        .schemaName(schemaName)
                        .objectName(objectName)
                        .tables(tables)
                        .columns(columns)
                        .build();
            }

            // Không có visitor phù hợp -> metadata tối thiểu
            return MetadataDto.builder()
                    .statementType("RAW_STATEMENT")
                    .build();

        } catch (Throwable t) {
            // Để layer trên fallback ANTLR/LLM
            throw new RuntimeException("Druid analyze failed: " + t.getMessage(), t);
        }
    }

    /* ===================== Helpers ===================== */

    private static SchemaStatVisitor createVisitor(DbType dbType) {
        if (dbType == null) return null;
        return switch (dbType) {
            case mysql, mariadb, tidb, polardbx, adb_mysql -> new MySqlSchemaStatVisitor();
            default -> {
                // Họ hàng PostgreSQL có thể dùng chung PG visitor
                if (DbType.isPostgreSQLDbStyle(dbType)) {
                    yield new PGSchemaStatVisitor();
                }
                yield null;
            }
        };
    }

    private static List<String> extractColumnsFromCreateTable(SQLCreateTableStatement c) {
        List<String> cols = new ArrayList<>();
        QName t = qnameOf(c.getName());
        String prefix = t.object != null ? (t.object + ".") : "";
        for (SQLTableElement e : c.getTableElementList()) {
            if (e instanceof SQLColumnDefinition column) {
                String col = safeIdentifier(column.getNameAsString());
                if (column.getDataType() != null && column.getDataType().getName() != null  && !column.getDataType().getName().trim().isEmpty()) {
                    col = col + "(" + column.getDataType().getName() + ")";
                }
                if (col != null) cols.add(prefix + col);
            }
        }
        return cols;
    }

    private static List<String> extractColumnsFromCreateIndex(SQLCreateIndexStatement c) {
        List<String> cols = new ArrayList<>();
        List<SQLSelectOrderByItem> items = c.getItems();
        if (items != null) {
            for (SQLSelectOrderByItem it : items) {
                SQLExpr expr = it.getExpr();
                String col = switch (expr) {
                    case SQLIdentifierExpr sqlIdentifierExpr -> sqlIdentifierExpr.getName();
                    case SQLPropertyExpr sqlPropertyExpr -> sqlPropertyExpr.getName();
                    case SQLName sqlName -> sqlName.getSimpleName();
                    default -> expr.toString();
                };
                if (col != null && !col.isEmpty()) cols.add(col);
            }
        }
        return cols;
    }

    private static String safeIdentifier(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        if ((t.startsWith("`") && t.endsWith("`")) ||
                (t.startsWith("\"") && t.endsWith("\"")) ||
                (t.startsWith("'") && t.endsWith("'"))) {
            t = t.substring(1, t.length() - 1);
        }
        return t;
    }

    private static QName qnameOf(SQLObject nameObj) {
        if (nameObj == null) return new QName(null, null);
        String full = nameObj.toString();
        String[] parts = full.split("\\.");
        String schema = null, object = null;
        if (parts.length == 1) object = stripQuote(parts[0]);
        else if (parts.length >= 2) { schema = stripQuote(parts[0]); object = stripQuote(parts[1]); }
        return new QName(schema, object);
    }

    private static String stripQuote(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return t;
        if ((t.startsWith("`") && t.endsWith("`")) ||
                (t.startsWith("\"") && t.endsWith("\"")) ||
                (t.startsWith("'") && t.endsWith("'"))) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }

    private record QName(String schema, String object) {
            private QName(String schema, String object) {
                this.schema = emptyToNull(schema);
                this.object = emptyToNull(object);
            }

        String objectFull() {
            return (schema == null || schema.isEmpty() || object == null) ? object : (schema + "." + object);
        }
    }
    private static String emptyToNull(String s){ return (s == null || s.trim().isEmpty()) ? null : s.trim(); }
}
