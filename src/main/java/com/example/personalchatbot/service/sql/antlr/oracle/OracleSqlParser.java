package com.example.personalchatbot.service.sql.antlr.oracle;

import com.example.personalchatbot.service.sql.antlr.implement.AntlrSqlParserImpl;
import com.example.personalchatbot.service.sql.antlr.oracle.OracleSQLParser;
import com.example.personalchatbot.service.sql.antlr.oracle.OracleSQLLexer;
import com.example.personalchatbot.service.sql.antlr.oracle.OracleSQLBaseVisitor;
import com.example.personalchatbot.service.sql.dto.MetadataDto;
import com.example.personalchatbot.service.sql.dto.SqlChunkDto;
import jakarta.annotation.PostConstruct;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.antlr.v4.runtime.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@Component
public class OracleSqlParser implements AntlrSqlParserImpl {
    @Override public String dialect() {
        return "oracle";
    }

    @PostConstruct
    void ok() { log.info("OracleSqlParser bean is alive"); }

    /* ===================== split ===================== */

    @Override
    public List<SqlChunkDto> split(@NonNull String sql) {
        CharStream cs = CharStreams.fromString(sql);
        OracleSQLLexer lexer = new OracleSQLLexer(cs);
        lexer.removeErrorListeners();

        List<? extends Token> toks = lexer.getAllTokens();
        List<SqlChunkDto> out = new ArrayList<>();
        int start = 0;
        int lastStop = -1;

        for (Token t : toks) {
            lastStop = Math.max(lastStop, t.getStopIndex());
            if (t.getType() == OracleSQLLexer.SEMI) {
                int endExclusive = Math.max(start, t.getStartIndex());
                String part = safeSub(sql, start, endExclusive).trim();
                if (!part.isEmpty()) {
                    out.add(SqlChunkDto.builder()
                            .index(out.size())
                            .part(1).totalParts(1)
                            .dialect(dialect())
                            .content(part)
                            .build());
                }
                start = t.getStopIndex() + 1;
            }
        }
        String tail = safeSub(sql, start, sql.length()).trim();
        if (!tail.isEmpty()) {
            out.add(SqlChunkDto.builder()
                    .index(out.size())
                    .part(1).totalParts(1)
                    .dialect(dialect())
                    .content(tail)
                    .build());
        }
        return out;
    }

    private static String safeSub(String s, int from, int toExclusive) {
        int a = Math.max(0, Math.min(from, s.length()));
        int b = Math.max(0, Math.min(toExclusive, s.length()));
        return (a >= b) ? "" : s.substring(a, b);
    }

    /* ===================== analyze ===================== */

    @Override
    public MetadataDto analyze(@NonNull String statement) {
        OracleSQLParser p = mkParser(statement);
        OracleSQLParser.SqlStatementContext ctx = p.sqlStatement();

        // CREATE TABLE
        if (ctx instanceof OracleSQLParser.StCreateTableContext st) {
            OracleSQLParser.CreateTableStatementContext c = st.createTableStatement();
            QName q = qname(c.tableName);
            List<String> cols = new ArrayList<>();
            for (OracleSQLParser.ColumnDefContext cd : c.columnDef()) {
                String col = idText(cd.columnName);
                if (col != null) cols.add((q.object != null ? q.object + "." : "") + col);
            }
            return MetadataDto.builder()
                    .statementType("CREATE_TABLE")
                    .schemaName(q.schema)
                    .objectName(q.object)
                    .tables(Collections.singletonList(q.full()))
                    .columns(cols)
                    .build();
        }

        // CREATE INDEX
        if (ctx instanceof OracleSQLParser.StCreateIndexContext st) {
            OracleSQLParser.CreateIndexStatementContext c = st.createIndexStatement();
            QName idx = qname(c.indexName);
            QName tbl = qname(c.tableName);
            List<String> cols = new ArrayList<>();
            for (OracleSQLParser.IndexElemContext ic : c.indexElem()) {
                String col = idText(ic.identifier());
                if (col != null) cols.add(col);
            }
            return MetadataDto.builder()
                    .statementType("CREATE_INDEX")
                    .schemaName(idx.schema)
                    .objectName(idx.object)
                    .tables(tbl.object == null ? List.of() : List.of(tbl.full()))
                    .columns(cols)
                    .build();
        }

        // CREATE VIEW
        if (ctx instanceof OracleSQLParser.StCreateViewContext st) {
            OracleSQLParser.CreateViewStatementContext c = st.createViewStatement();
            QName q = qname(c.viewName);
            return MetadataDto.builder()
                    .statementType("CREATE_VIEW")
                    .schemaName(q.schema)
                    .objectName(q.object)
                    .tables(List.of())
                    .columns(List.of())
                    .build();
        }

        // CREATE FUNCTION
        if (ctx instanceof OracleSQLParser.StCreateFunctionContext st) {
            OracleSQLParser.CreateFunctionStatementContext c = st.createFunctionStatement();
            QName q = qname(c.funcName);
            return MetadataDto.builder()
                    .statementType("CREATE_FUNCTION")
                    .schemaName(q.schema)
                    .objectName(q.object)
                    .tables(List.of())
                    .columns(List.of())
                    .build();
        }

        // CREATE PROCEDURE
        if (ctx instanceof OracleSQLParser.StCreateProcedureContext st) {
            OracleSQLParser.CreateProcedureStatementContext c = st.createProcedureStatement();
            QName q = qname(c.procName);
            return MetadataDto.builder()
                    .statementType("CREATE_PROCEDURE")
                    .schemaName(q.schema)
                    .objectName(q.object)
                    .tables(List.of())
                    .columns(List.of())
                    .build();
        }

        // DROP *
        if (ctx instanceof OracleSQLParser.StDropContext) {
            return MetadataDto.builder()
                    .statementType("DROP")
                    .tables(List.of())
                    .columns(List.of())
                    .build();
        }

        // DML khác
        return MetadataDto.builder()
                .statementType("STATEMENT")
                .tables(List.of())
                .columns(List.of())
                .build();
    }

    /* ===================== utils ===================== */

    private static OracleSQLParser mkParser(String sql) {
        CharStream cs = CharStreams.fromString(sql);
        OracleSQLLexer lx = new OracleSQLLexer(cs);
        CommonTokenStream ts = new CommonTokenStream(lx);
        OracleSQLParser p = new OracleSQLParser(ts);
        p.removeErrorListeners();
        p.setErrorHandler(new BailErrorStrategy());
        return p;
    }

    private static String idText(OracleSQLParser.IdentifierContext id) {
        return id == null ? null : stripQuote(id.getText());
    }

    /** qualifiedName : identifier (DOT identifier)* ; */
    private static QName qname(OracleSQLParser.QualifiedNameContext qn) {
        if (qn == null) return new QName(null, null);
        List<OracleSQLParser.IdentifierContext> ids = qn.identifier(); // list size >= 1
        String schema = null, obj;
        if (ids.size() == 1) {
            obj = stripQuote(ids.getFirst().getText());
        } else {
            schema = stripQuote(ids.get(0).getText());
            obj = stripQuote(ids.get(1).getText()); // lấy 2 thành phần đầu (schema.object)
        }
        return new QName(schema, obj);
    }

    private static String stripQuote(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.length() >= 2) {
            char f = t.charAt(0), l = t.charAt(t.length() - 1);
            if ((f == '"' && l == '"') || (f == '\'' && l == '\'') || (f == '`' && l == '`')) {
                return t.substring(1, t.length() - 1);
            }
        }
        return t;
    }

    private static final class QName {
        final String schema, object;
        QName(String s, String o) { this.schema = emptyToNull(s); this.object = emptyToNull(o); }
        String full() { return schema == null || schema.isEmpty() ? object : (schema + "." + object); }
    }

    private static String emptyToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }
}
