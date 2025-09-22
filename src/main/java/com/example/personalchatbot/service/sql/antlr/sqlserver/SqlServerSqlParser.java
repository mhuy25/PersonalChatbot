package com.example.personalchatbot.service.sql.antlr.sqlserver;

import com.example.personalchatbot.service.sql.antlr.implement.AntlrSqlParserImpl;
import com.example.personalchatbot.service.sql.antlr.sqlserver.SqlServerParser;
import com.example.personalchatbot.service.sql.antlr.sqlserver.SqlServerLexer;
import com.example.personalchatbot.service.sql.antlr.sqlserver.SqlServerBaseVisitor;
import com.example.personalchatbot.service.sql.dto.MetadataDto;
import com.example.personalchatbot.service.sql.dto.SqlChunkDto;
import lombok.NonNull;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.tree.ParseTree;
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
    public List<SqlChunkDto> split(@NonNull String script) {
        SqlServerLexer lexer = new SqlServerLexer(CharStreams.fromString(script));
        SqlServerParser parser = new SqlServerParser(new CommonTokenStream(lexer));

        parser.removeErrorListeners();
        lexer.removeErrorListeners();
        SilentErrorListener sil = new SilentErrorListener();
        parser.addErrorListener(sil);
        lexer.addErrorListener(sil);

        SqlServerParser.ScriptContext root = parser.script();

        List<SqlChunkDto> out = new ArrayList<>();
        int idx = 0;

        for (int i = 0; i < root.getChildCount(); i++) {
            ParseTree child = root.getChild(i);
            if (!(child instanceof SqlServerParser.TopStmtContext top)) continue;

            // ---- 4 loại Programmability đứng top-level ----
            SqlServerParser.CreateProcedureStatementContext cProc =
                    getFirst(top, SqlServerParser.CreateProcedureStatementContext.class);
            if (cProc != null) {
                String fqn = fqnOfProcedure(cProc);
                List<String> params = extractProcParams(cProc);
                MetadataDto md = MetadataDto.builder()
                        .statementType("CREATE_PROCEDURE")
                        .schemaName(extractSchema(fqn))
                        .objectName(extractObject(fqn))
                        .columns(params.isEmpty() ? null : params)
                        .build();
                out.add(buildChunk(idx++, dialect(), md.getStatementType(), slice(top, script), md));
                continue;
            }

            SqlServerParser.CreateFunctionStatementContext cFunc =
                    getFirst(top, SqlServerParser.CreateFunctionStatementContext.class);
            if (cFunc != null) {
                String fqn = fqnOfFunction(cFunc);
                List<String> params = extractFuncParams(cFunc);
                MetadataDto md = MetadataDto.builder()
                        .statementType("CREATE_FUNCTION")
                        .schemaName(extractSchema(fqn))
                        .objectName(extractObject(fqn))
                        .columns(params.isEmpty() ? null : params)
                        .build();
                out.add(buildChunk(idx++, dialect(), md.getStatementType(), slice(top, script), md));
                continue;
            }

            SqlServerParser.CreateViewStatementContext cView =
                    getFirst(top, SqlServerParser.CreateViewStatementContext.class);
            if (cView != null) {
                String fqn = fqnOfView(cView);
                MetadataDto md = MetadataDto.builder()
                        .statementType("CREATE_VIEW")
                        .schemaName(extractSchema(fqn))
                        .objectName(extractObject(fqn))
                        .build();
                out.add(buildChunk(idx++, dialect(), md.getStatementType(), slice(top, script), md));
                continue;
            }

            SqlServerParser.CreateTriggerStatementContext cTrig =
                    getFirst(top, SqlServerParser.CreateTriggerStatementContext.class);
            if (cTrig != null) {
                String trg = fqnOfTrigger(cTrig);
                String onTbl = fqnOfTriggerTarget(cTrig);

                List<String> tables = (onTbl == null || onTbl.isBlank())
                        ? Collections.emptyList()
                        : Collections.singletonList(stripQuotes(onTbl));

                MetadataDto md = MetadataDto.builder()
                        .statementType("CREATE_TRIGGER")
                        .schemaName(extractSchema(trg))
                        .objectName(extractObject(trg))
                        .tables(tables)
                        .build();

                out.add(buildChunk(idx++, dialect(), md.getStatementType(), slice(top, script), md));
                continue;
            }

            // ---- Các câu lệnh thường: s=sqlStatement ----
            SqlServerParser.SqlStatementContext st =
                    getFirst(top, SqlServerParser.SqlStatementContext.class);
            if (st == null) continue;

            String content = slice(top, script);
            MetadataDto md = analyzeByNode(st);
            String kind = (md != null && md.getStatementType() != null) ? md.getStatementType() : "RAW_STATEMENT";
            out.add(buildChunk(idx++, dialect(), kind, content, md));
        }
        return out;
    }

    private static SqlChunkDto buildChunk(int idx, String dialect, String kind, String content, MetadataDto md) {
        SqlChunkDto dto = new SqlChunkDto();
        dto.setIndex(idx);
        dto.setDialect(dialect);
        dto.setKind(kind);
        dto.setContent(content);
        dto.setMetadata(md);
        return dto;
    }

    /* ======================== Analyze (single statement) ======================== */
    @Override
    public MetadataDto analyze(@NonNull String sql) {
        List<SqlChunkDto> list = split(sql);
        if (list.isEmpty()) return MetadataDto.minimal("RAW_STATEMENT");
        return list.getFirst().getMetadata() != null
                ? list.getFirst().getMetadata()
                : MetadataDto.minimal(list.getFirst().getKind());
    }

    /* ======================== Per-statement metadata (sqlStatement) ======================== */
    private MetadataDto analyzeByNode(SqlServerParser.SqlStatementContext ctx) {
        if (ctx == null) return MetadataDto.minimal("RAW_STATEMENT");

        // CREATE TABLE
        SqlServerParser.CreateTableStatementContext ct =
                getFirst(ctx, SqlServerParser.CreateTableStatementContext.class);
        if (ct != null) {
            String fqn = textOf(ct.schemaQualifiedName());
            return MetadataDto.builder().statementType("CREATE_TABLE")
                    .schemaName(extractSchema(fqn)).objectName(extractObject(fqn)).build();
        }

        // CREATE INDEX
        SqlServerParser.CreateIndexStatementContext cidx =
                getFirst(ctx, SqlServerParser.CreateIndexStatementContext.class);
        if (cidx != null) {
            String idxName = textOf(cidx.identifier(0));
            String tbl = textOf(cidx.schemaQualifiedName());
            return MetadataDto.builder().statementType("CREATE_INDEX")
                    .schemaName(extractSchema(tbl))
                    .objectName(stripQuotes(idxName))
                    .tables(Collections.singletonList(stripQuotes(tbl)))
                    .build();
        }

        // ALTER INDEX
        SqlServerParser.AlterIndexStatementContext caidx =
                getFirst(ctx, SqlServerParser.AlterIndexStatementContext.class);
        if (caidx != null) {
            String tbl = textOf(caidx.schemaQualifiedName());
            String idxName = (caidx.ALL() != null) ? "ALL" : textOf(caidx.identifier());
            return MetadataDto.builder().statementType("ALTER_INDEX")
                    .objectName(stripQuotes(idxName))
                    .tables(Collections.singletonList(stripQuotes(tbl)))
                    .build();
        }

        // CREATE SEQUENCE
        SqlServerParser.CreateSequenceStatementContext cseq =
                getFirst(ctx, SqlServerParser.CreateSequenceStatementContext.class);
        if (cseq != null) {
            String fqn = textOf(cseq.schemaQualifiedName());
            return MetadataDto.builder().statementType("CREATE_SEQUENCE")
                    .schemaName(extractSchema(fqn)).objectName(extractObject(fqn)).build();
        }

        // CREATE TYPE
        SqlServerParser.CreateTypeStatementContext ctype =
                getFirst(ctx, SqlServerParser.CreateTypeStatementContext.class);
        if (ctype != null) {
            String fqn = textOf(ctype.schemaQualifiedName());
            return MetadataDto.builder().statementType("CREATE_TYPE")
                    .schemaName(extractSchema(fqn)).objectName(extractObject(fqn)).build();
        }

        // CREATE SCHEMA
        SqlServerParser.CreateSchemaStatementContext csch =
                getFirst(ctx, SqlServerParser.CreateSchemaStatementContext.class);
        if (csch != null) {
            String sch = stripQuotes(textOf(csch.schemaName()));
            return MetadataDto.builder().statementType("CREATE_SCHEMA")
                    .schemaName(sch).objectName(sch).build();
        }

        // CREATE DATABASE
        SqlServerParser.CreateDatabaseStatementContext cdb =
                getFirst(ctx, SqlServerParser.CreateDatabaseStatementContext.class);
        if (cdb != null) {
            String db = textOf(cdb.getChild(2));
            return MetadataDto.builder().statementType("CREATE_DATABASE")
                    .objectName(stripQuotes(db)).build();
        }

        // ALTER DATABASE
        SqlServerParser.AlterDatabaseStatementContext cadb =
                getFirst(ctx, SqlServerParser.AlterDatabaseStatementContext.class);
        if (cadb != null) {
            String db = textOf(cadb.getChild(2));
            return MetadataDto.builder().statementType("ALTER_DATABASE")
                    .objectName(stripQuotes(db)).build();
        }

        // DROP INDEX
        SqlServerParser.DropIndexStatementContext cdidx =
                getFirst(ctx, SqlServerParser.DropIndexStatementContext.class);
        if (cdidx != null) {
            // grammar: DROP INDEX (IDENTIFIER | BRACKET_ID | DOUBLE_QUOTED_ID) ON schemaQualifiedName
            String idxName = textOf(cdidx.getChild(2)); // giữ như cũ, vì nhánh là tokens/ids
            String tbl = textOf(cdidx.schemaQualifiedName());
            return MetadataDto.builder().statementType("DROP_INDEX")
                    .objectName(stripQuotes(idxName))
                    .tables(Collections.singletonList(stripQuotes(tbl)))
                    .build();
        }

        // DROP (DATABASE | TABLE | VIEW | FUNCTION | PROCEDURE | TRIGGER | SEQUENCE | TYPE | SYNONYM) schemaQualifiedName
        SqlServerParser.DropStatementContext cdrop =
                getFirst(ctx, SqlServerParser.DropStatementContext.class);
        if (cdrop != null) {
            String obj = textOf(cdrop.schemaQualifiedName());
            return MetadataDto.builder().statementType("DROP")
                    .objectName(stripQuotes(obj)).build();
        }

        // ALTER TABLE
        SqlServerParser.AlterTableStatementContext calt =
                getFirst(ctx, SqlServerParser.AlterTableStatementContext.class);
        if (calt != null) {
            String fqn = textOf(calt.schemaQualifiedName());
            return MetadataDto.builder().statementType("ALTER_TABLE")
                    .schemaName(extractSchema(fqn)).objectName(extractObject(fqn)).build();
        }

        // USE
        SqlServerParser.UseStatementContext cuse =
                getFirst(ctx, SqlServerParser.UseStatementContext.class);
        if (cuse != null) {
            String db = textOf(cuse.getChild(1));
            return MetadataDto.builder().statementType("USE").objectName(stripQuotes(db)).build();
        }

        // Các loại đơn giản còn lại theo presence
        if (has(ctx, SqlServerParser.SetStatementContext.class))    return MetadataDto.builder().statementType("SET").build();
        if (has(ctx, SqlServerParser.ExecStatementContext.class))   return MetadataDto.builder().statementType("EXEC").build();
        if (has(ctx, SqlServerParser.PrintStatementContext.class))  return MetadataDto.builder().statementType("PRINT").build();
        if (has(ctx, SqlServerParser.IfStatementContext.class))     return MetadataDto.builder().statementType("IF").build();
        if (has(ctx, SqlServerParser.MergeStmtContext.class))       return MetadataDto.builder().statementType("MERGE").build();
        if (has(ctx, SqlServerParser.SelectStmtContext.class))      return MetadataDto.builder().statementType("SELECT").build();
        if (has(ctx, SqlServerParser.InsertStmtContext.class))      return MetadataDto.builder().statementType("INSERT").build();
        if (has(ctx, SqlServerParser.UpdateStmtContext.class))      return MetadataDto.builder().statementType("UPDATE").build();
        if (has(ctx, SqlServerParser.DeleteStmtContext.class))      return MetadataDto.builder().statementType("DELETE").build();
        if (has(ctx, SqlServerParser.ThrowStatementContext.class))  return MetadataDto.builder().statementType("THROW").build();

        return MetadataDto.minimal("RAW_STATEMENT");
    }

    /* -------------------- helpers: labeled alts / rule access -------------------- */
    private static String fqnOfView(SqlServerParser.CreateViewStatementContext c) {
        if (c instanceof SqlServerParser.CreateViewWithGoContext x) return textOf(x.schemaQualifiedName());
        if (c instanceof SqlServerParser.CreateViewNoGoContext x)   return textOf(x.schemaQualifiedName());
        return null;
    }

    private static String fqnOfFunction(SqlServerParser.CreateFunctionStatementContext c) {
        if (c instanceof SqlServerParser.CreateFuncScalarWithGoContext x) return textOf(x.schemaQualifiedName());
        if (c instanceof SqlServerParser.CreateFuncScalarNoGoContext x)   return textOf(x.schemaQualifiedName());
        if (c instanceof SqlServerParser.CreateFuncInlineWithGoContext x) return textOf(x.schemaQualifiedName());
        if (c instanceof SqlServerParser.CreateFuncInlineNoGoContext x)   return textOf(x.schemaQualifiedName());
        return null;
    }

    private static String fqnOfProcedure(SqlServerParser.CreateProcedureStatementContext c) {
        if (c instanceof SqlServerParser.CreateProcWithGoContext x) return textOf(x.schemaQualifiedName());
        if (c instanceof SqlServerParser.CreateProcNoGoContext x)   return textOf(x.schemaQualifiedName());
        return null;
    }

    private static String fqnOfTrigger(SqlServerParser.CreateTriggerStatementContext c) {
        if (c instanceof SqlServerParser.CreateDmlTriggerWithGoContext x) return textOf(x.schemaQualifiedName(0));
        if (c instanceof SqlServerParser.CreateDmlTriggerNoGoContext   x) return textOf(x.schemaQualifiedName(0));
        if (c instanceof SqlServerParser.CreateDdlTriggerWithGoContext x) return textOf(x.schemaQualifiedName());
        if (c instanceof SqlServerParser.CreateDdlTriggerNoGoContext   x) return textOf(x.schemaQualifiedName());
        return null;
    }

    private static String fqnOfTriggerTarget(SqlServerParser.CreateTriggerStatementContext c) {
        if (c instanceof SqlServerParser.CreateDmlTriggerWithGoContext x) return textOf(x.schemaQualifiedName(1));
        if (c instanceof SqlServerParser.CreateDmlTriggerNoGoContext   x) return textOf(x.schemaQualifiedName(1));
        if (c instanceof SqlServerParser.CreateDdlTriggerWithGoContext) return null;
        if (c instanceof SqlServerParser.CreateDdlTriggerNoGoContext)   return null;
        return null;
    }

    /* -------------------- helpers: extract params from grammar -------------------- */
    private static List<String> extractProcParams(SqlServerParser.CreateProcedureStatementContext c) {
        List<String> out = new ArrayList<>();
        SqlServerParser.ProcParamsContext pp = null;
        if (c instanceof SqlServerParser.CreateProcWithGoContext x) pp = x.procParams();
        if (c instanceof SqlServerParser.CreateProcNoGoContext x)   pp = x.procParams();
        if (pp == null) return out;

        SqlServerParser.ProcParamListContext list = pp.procParamList();
        if (list == null) return out;

        for (SqlServerParser.ProcParamContext p : list.procParam()) {
            String name = p.AT_ID() != null ? p.AT_ID().getText()
                    : (p.identifier() != null ? p.identifier().getText() : null);
            if (name != null) out.add(name);
        }
        return out;
    }

    private static List<String> extractFuncParams(SqlServerParser.CreateFunctionStatementContext c) {
        List<String> out = new ArrayList<>();
        SqlServerParser.ParamDefListContext list = null;
        if (c instanceof SqlServerParser.CreateFuncScalarWithGoContext x) list = x.paramDefList();
        if (c instanceof SqlServerParser.CreateFuncScalarNoGoContext x)   list = x.paramDefList();
        if (c instanceof SqlServerParser.CreateFuncInlineWithGoContext x) list = x.paramDefList();
        if (c instanceof SqlServerParser.CreateFuncInlineNoGoContext x)   list = x.paramDefList();
        if (list == null) return out;

        for (SqlServerParser.ParamDefContext p : list.paramDef()) {
            String name = p.AT_ID() != null ? p.AT_ID().getText()
                    : (p.identifier() != null ? p.identifier().getText() : null);
            if (name != null) out.add(name);
        }
        return out;
    }

    /* ======================== Parse helpers ======================== */

    private static <T extends ParserRuleContext> T getFirst(ParserRuleContext ctx, Class<T> klass) {
        List<T> ls = ctx.getRuleContexts(klass);
        return ls.isEmpty() ? null : ls.getFirst();
    }

    private static <T extends ParserRuleContext> boolean has(ParserRuleContext ctx, Class<T> klass) {
        return !ctx.getRuleContexts(klass).isEmpty();
    }

    private static String textOf(ParseTree t) { return (t == null) ? null : t.getText(); }

    /** Split a multipart name by DOT, respecting [] and "" quoting. */
    private static List<String> splitQualifiedName(String fqn) {
        List<String> parts = new ArrayList<>();
        if (fqn == null) return parts;

        StringBuilder cur = new StringBuilder();
        boolean inBrackets = false;
        boolean inDQuotes  = false;

        for (int i = 0; i < fqn.length(); i++) {
            char c = fqn.charAt(i);

            if (inBrackets) {
                if (c == ']') {
                    if (i + 1 < fqn.length() && fqn.charAt(i + 1) == ']') { cur.append(']'); i++; }
                    else { inBrackets = false; }
                } else cur.append(c);
                continue;
            }

            if (inDQuotes) {
                if (c == '"') {
                    if (i + 1 < fqn.length() && fqn.charAt(i + 1) == '"') { cur.append('"'); i++; }
                    else { inDQuotes = false; }
                } else cur.append(c);
                continue;
            }

            if (c == '[') { inBrackets = true; continue; }
            if (c == '"') { inDQuotes  = true; continue; }

            if (c == '.') { parts.add(cur.toString()); cur.setLength(0); }
            else { cur.append(c); }
        }
        parts.add(cur.toString());
        return parts;
    }

    private static String stripDelimiters(String s) {
        if (s == null) return null;
        if (s.length() >= 2 && s.charAt(0) == '[' && s.charAt(s.length() - 1) == ']') {
            return s.substring(1, s.length() - 1);
        }
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String extractObject(String fqn) {
        List<String> parts = splitQualifiedName(fqn);
        if (parts.isEmpty()) return null;
        return stripDelimiters(parts.getLast());
    }

    private static String extractSchema(String fqn) {
        List<String> parts = splitQualifiedName(fqn);
        if (parts.size() < 2) return null;
        return stripDelimiters(parts.get(parts.size() - 2));
    }

    @SuppressWarnings("unused")
    private static String extractDatabase(String fqn) {
        List<String> parts = splitQualifiedName(fqn);
        return (parts.size() >= 3) ? stripDelimiters(parts.get(parts.size() - 3)) : null;
    }

    @SuppressWarnings("unused")
    private static String extractServer(String fqn) {
        List<String> parts = splitQualifiedName(fqn);
        return (parts.size() >= 4) ? stripDelimiters(parts.get(parts.size() - 4)) : null;
    }

    private static String stripQuotes(String s) {
        if (s == null) return null;
        String x = s;
        if (x.length() >= 2 && x.startsWith("[") && x.endsWith("]")) x = x.substring(1, x.length() - 1);
        if (x.length() >= 2 && x.startsWith("\"") && x.endsWith("\"")) x = x.substring(1, x.length() - 1);
        return x;
    }

    private static String slice(ParserRuleContext ctx, String script) {
        Interval itv = Interval.of(ctx.getStart().getStartIndex(), ctx.getStop().getStopIndex());
        return script.substring(itv.a, itv.b + 1);
    }

    /** Listener that swallows syntax errors (tolerant). */
    private static class SilentErrorListener extends BaseErrorListener {
        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                int line, int charPositionInLine, String msg, RecognitionException e) {
            // no-op
        }
    }
}
