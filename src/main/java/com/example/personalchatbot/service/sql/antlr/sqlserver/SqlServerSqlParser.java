package com.example.personalchatbot.service.sql.antlr.sqlserver;

import com.example.personalchatbot.service.sql.antlr.implement.AntlrSqlParserImpl;
import com.example.personalchatbot.service.sql.antlr.sqlserver.SqlServerParser;
import com.example.personalchatbot.service.sql.antlr.sqlserver.SqlServerLexer;
import com.example.personalchatbot.service.sql.antlr.sqlserver.SqlServerBaseVisitor;
import com.example.personalchatbot.service.sql.dto.MetadataDto;
import com.example.personalchatbot.service.sql.dto.SqlChunkDto;
import lombok.NonNull;
import org.antlr.v4.runtime.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class SqlServerSqlParser implements AntlrSqlParserImpl {
    @Override
    public String dialect() {
        return "sqlserver";
    }

    /* ===================== split ===================== */

    @Override
    public List<SqlChunkDto> split(@NonNull String sql) {
        CharStream cs = CharStreams.fromString(sql);
        var lexer = new SqlServerLexer(cs);
        lexer.removeErrorListeners();

        List<? extends Token> toks = lexer.getAllTokens();
        List<SqlChunkDto> out = new ArrayList<>();
        int start = 0;

        for (Token t : toks) {
            if (t.getType() == SqlServerLexer.SEMI) {
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
        var p = mkParser(statement);
        var ctx = p.sqlStatement();

        // CREATE DATABASE
        if (ctx instanceof SqlServerParser.StCreateDatabaseContext st) {
            var c = st.createDatabaseStatement();
            String db = stripQuote(c.identifier().getText());
            return MetadataDto.builder()
                    .statementType("CREATE_DATABASE")
                    .schemaName(null)
                    .objectName(db)
                    .tables(List.of())
                    .columns(List.of())
                    .build();
        }

        // CREATE TABLE
        if (ctx instanceof SqlServerParser.StCreateTableContext st) {
            var c = st.createTableStatement();
            QName q = qname(c.tableName);
            List<String> cols = new ArrayList<>();
            for (var cd : c.columnDef()) {
                String col = stripQuote(cd.identifier().getText());
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
        if (ctx instanceof SqlServerParser.StCreateIndexContext st) {
            var c = st.createIndexStatement();
            QName idx = qname(c.indexName);
            QName tbl = qname(c.tableName);
            List<String> cols = new ArrayList<>();
            for (var ic : c.indexElem()) {
                cols.add(stripQuote(ic.identifier().getText()));
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
        if (ctx instanceof SqlServerParser.StCreateViewContext st) {
            var c = st.createViewStatement();
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
        if (ctx instanceof SqlServerParser.StCreateFunctionContext st) {
            var c = st.createFunctionStatement();
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
        if (ctx instanceof SqlServerParser.StCreateProcedureContext st) {
            var c = st.createProcedureStatement();
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
        if (ctx instanceof SqlServerParser.StDropContext) {
            return MetadataDto.builder()
                    .statementType("DROP")
                    .tables(List.of())
                    .columns(List.of())
                    .build();
        }

        return MetadataDto.builder()
                .statementType("STATEMENT")
                .tables(List.of())
                .columns(List.of())
                .build();
    }

    /* ===================== utils ===================== */

    private static SqlServerParser mkParser(String sql) {
        CharStream cs = CharStreams.fromString(sql);
        var lx = new SqlServerLexer(cs);
        CommonTokenStream ts = new CommonTokenStream(lx);
        var p = new SqlServerParser(ts);
        p.removeErrorListeners();
        p.setErrorHandler(new BailErrorStrategy());
        return p;
    }

    /** qualifiedName : identifier (DOT identifier)* ; */
    private static QName qname(SqlServerParser.QualifiedNameContext qn) {
        if (qn == null) return new QName(null, null);
        List<SqlServerParser.IdentifierContext> ids = qn.identifier();
        String schema = null, obj;
        if (ids.size() == 1) {
            obj = stripQuote(ids.getFirst().getText());
        } else {
            schema = stripQuote(ids.get(0).getText());
            obj = stripQuote(ids.get(1).getText());
        }
        return new QName(schema, obj);
    }

    private static String stripQuote(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.length() >= 2) {
            char f = t.charAt(0), l = t.charAt(t.length() - 1);
            if ((f == '"' && l == '"') || (f == '\'' && l == '\'') || (f == '`' && l == '`') || (f == '[' && l == ']')) {
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
