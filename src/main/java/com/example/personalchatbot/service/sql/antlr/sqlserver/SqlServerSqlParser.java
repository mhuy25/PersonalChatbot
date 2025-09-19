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
        parser.addErrorListener(new SilentErrorListener());
        lexer.addErrorListener(new SilentErrorListener());

        SqlServerParser.ScriptContext root = parser.script();

        List<SqlChunkDto> out = new ArrayList<>();
        int idx = 0;

        for (int i = 0; i < root.getChildCount(); i++) {
            ParseTree child = root.getChild(i);
            if (!(child instanceof SqlServerParser.TopStmtContext top)) continue;

            SqlChunkDto dto;

            // ---- 4 loại Programmability đứng top-level ----
            if (top instanceof SqlServerParser.TopProcContext tp) {
                String fqn = fqnOfProcedure(tp.createProcedureStatement());
                MetadataDto md = MetadataDto.builder()
                        .statementType("CREATE_PROCEDURE")
                        .schemaName(extractSchema(fqn))
                        .objectName(extractName(fqn))
                        .build();
                dto = buildChunk(idx++, dialect(), md.getStatementType(),
                        slice(top, script), md);
                out.add(dto);
                continue;
            }

            if (top instanceof SqlServerParser.TopFuncContext tf) {
                String fqn = fqnOfFunction(tf.createFunctionStatement());
                MetadataDto md = MetadataDto.builder()
                        .statementType("CREATE_FUNCTION")
                        .schemaName(extractSchema(fqn))
                        .objectName(extractName(fqn))
                        .build();
                dto = buildChunk(idx++, dialect(), md.getStatementType(),
                        slice(top, script), md);
                out.add(dto);
                continue;
            }

            if (top instanceof SqlServerParser.TopViewContext tv) {
                String fqn = fqnOfView(tv.createViewStatement());
                MetadataDto md = MetadataDto.builder()
                        .statementType("CREATE_VIEW")
                        .schemaName(extractSchema(fqn))
                        .objectName(extractName(fqn))
                        .build();
                dto = buildChunk(idx++, dialect(), md.getStatementType(),
                        slice(top, script), md);
                out.add(dto);
                continue;
            }

            if (top instanceof SqlServerParser.TopTrigContext tt) {
                String trg = fqnOfTrigger(tt.createTriggerStatement());
                String onTbl = fqnOfTriggerTarget(tt.createTriggerStatement());
                MetadataDto md = MetadataDto.builder()
                        .statementType("CREATE_TRIGGER")
                        .schemaName(extractSchema(trg))
                        .objectName(extractName(trg))
                        .tables(Collections.singletonList(stripQuotes(onTbl)))
                        .build();
                dto = buildChunk(idx++, dialect(), md.getStatementType(),
                        slice(top, script), md);
                out.add(dto);
                continue;
            }

            // ---- Các câu lệnh thường nằm trong s=sqlStatement ----
            SqlServerParser.SqlStatementContext st = null;
            if (top instanceof SqlServerParser.TopWithTerminatorContext tw) st = tw.s;
            else if (top instanceof SqlServerParser.TopNoGoContext tn)      st = tn.s;
            if (st == null) continue;

            String content = slice(top, script);
            MetadataDto md = analyzeByNode(st);
            String kind = (md != null && md.getStatementType() != null) ? md.getStatementType() : "RAW_STATEMENT";
            dto = buildChunk(idx++, dialect(), kind, content, md);
            out.add(dto);
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

    /* ======================== Per-statement metadata ======================== */
    private MetadataDto analyzeByNode(SqlServerParser.SqlStatementContext ctx) {
        if (ctx == null) return MetadataDto.minimal("RAW_STATEMENT");

        if (ctx instanceof SqlServerParser.StCreateTableContext st) {
            String fqn = textOf(st.createTableStatement().schemaQualifiedName());
            return MetadataDto.builder().statementType("CREATE_TABLE")
                    .schemaName(extractSchema(fqn)).objectName(extractName(fqn)).build();
        }

        if (ctx instanceof SqlServerParser.StCreateIndexContext st) {
            var c = st.createIndexStatement();
            String idx = textOf(c.identifier(0));
            String tbl = textOf(c.schemaQualifiedName());
            return MetadataDto.builder().statementType("CREATE_INDEX")
                    .schemaName(extractSchema(tbl))
                    .objectName(stripQuotes(idx))
                    .tables(Collections.singletonList(stripQuotes(tbl)))
                    .build();
        }

        if (ctx instanceof SqlServerParser.StAlterIndexContext st) {
            var c = st.alterIndexStatement();
            String tbl = textOf(c.schemaQualifiedName());
            String idx = (c.ALL() != null) ? "ALL" : textOf(c.identifier());
            return MetadataDto.builder().statementType("ALTER_INDEX")
                    .objectName(stripQuotes(idx))
                    .tables(Collections.singletonList(stripQuotes(tbl)))
                    .build();
        }

        if (ctx instanceof SqlServerParser.StCreateSequenceContext st) {
            String fqn = textOf(st.createSequenceStatement().schemaQualifiedName());
            return MetadataDto.builder().statementType("CREATE_SEQUENCE")
                    .schemaName(extractSchema(fqn)).objectName(extractName(fqn)).build();
        }

        if (ctx instanceof SqlServerParser.StCreateTypeContext st) {
            String fqn = textOf(st.createTypeStatement().schemaQualifiedName());
            return MetadataDto.builder().statementType("CREATE_TYPE")
                    .schemaName(extractSchema(fqn)).objectName(extractName(fqn)).build();
        }

        if (ctx instanceof SqlServerParser.StCreateSchemaContext st) {
            String sch = stripQuotes(textOf(st.createSchemaStatement().schemaName()));
            return MetadataDto.builder().statementType("CREATE_SCHEMA")
                    .schemaName(sch).objectName(sch).build();
        }

        if (ctx instanceof SqlServerParser.StCreateDatabaseContext st) {
            String db = textOf(st.createDatabaseStatement().getChild(2));
            return MetadataDto.builder().statementType("CREATE_DATABASE")
                    .objectName(stripQuotes(db)).build();
        }

        if (ctx instanceof SqlServerParser.StAlterDatabaseContext st) {
            String db = textOf(st.alterDatabaseStatement().getChild(2));
            return MetadataDto.builder().statementType("ALTER_DATABASE")
                    .objectName(stripQuotes(db)).build();
        }

        if (ctx instanceof SqlServerParser.StDropIndexContext st) {
            var c = st.dropIndexStatement();
            String idx = textOf(c.getChild(2));
            String tbl = textOf(c.schemaQualifiedName());
            return MetadataDto.builder().statementType("DROP_INDEX")
                    .objectName(stripQuotes(idx))
                    .tables(Collections.singletonList(stripQuotes(tbl)))
                    .build();
        }

        if (ctx instanceof SqlServerParser.StDropContext st) {
            String obj = textOf(st.dropStatement().schemaQualifiedName());
            return MetadataDto.builder().statementType("DROP")
                    .objectName(stripQuotes(obj)).build();
        }

        if (ctx instanceof SqlServerParser.StAlterTableContext st) {
            String fqn = textOf(st.alterTableStatement().schemaQualifiedName());
            return MetadataDto.builder().statementType("ALTER_TABLE")
                    .schemaName(extractSchema(fqn)).objectName(extractName(fqn)).build();
        }

        if (ctx instanceof SqlServerParser.StUseContext st) {
            String db = textOf(st.useStatement().getChild(1));
            return MetadataDto.builder().statementType("USE").objectName(stripQuotes(db)).build();
        }

        if (ctx instanceof SqlServerParser.StSetContext)      return MetadataDto.builder().statementType("SET").build();
        if (ctx instanceof SqlServerParser.StExecContext)     return MetadataDto.builder().statementType("EXEC").build();
        if (ctx instanceof SqlServerParser.StPrintContext)    return MetadataDto.builder().statementType("PRINT").build();
        if (ctx instanceof SqlServerParser.StIfContext)       return MetadataDto.builder().statementType("IF").build();
        if (ctx instanceof SqlServerParser.StMergeContext)    return MetadataDto.builder().statementType("MERGE").build();
        if (ctx instanceof SqlServerParser.StSelectContext)   return MetadataDto.builder().statementType("SELECT").build();
        if (ctx instanceof SqlServerParser.StInsertContext)   return MetadataDto.builder().statementType("INSERT").build();
        if (ctx instanceof SqlServerParser.StUpdateContext)   return MetadataDto.builder().statementType("UPDATE").build();
        if (ctx instanceof SqlServerParser.StDeleteContext)   return MetadataDto.builder().statementType("DELETE").build();
        if (ctx instanceof SqlServerParser.StThrowContext)    return MetadataDto.builder().statementType("THROW").build();

        return MetadataDto.minimal("RAW_STATEMENT");
    }

    /* -------------------- helpers cho labeled alts -------------------- */
    private static String fqnOfView(SqlServerParser.CreateViewStatementContext c) {
        if (c instanceof SqlServerParser.CreateViewWithGoContext v) return textOf(v.schemaQualifiedName());
        if (c instanceof SqlServerParser.CreateViewNoGoContext v)   return textOf(v.schemaQualifiedName());
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
        if (c instanceof SqlServerParser.CreateTriggerWithGoContext x) return textOf(x.schemaQualifiedName(0));
        if (c instanceof SqlServerParser.CreateTriggerNoGoContext x)   return textOf(x.schemaQualifiedName(0));
        return null;
    }

    private static String fqnOfTriggerTarget(SqlServerParser.CreateTriggerStatementContext c) {
        if (c instanceof SqlServerParser.CreateTriggerWithGoContext x) return textOf(x.schemaQualifiedName(1));
        if (c instanceof SqlServerParser.CreateTriggerNoGoContext x)   return textOf(x.schemaQualifiedName(1));
        return null;
    }

    /* ======================== Parse helpers ======================== */
    private static String textOf(ParseTree t) { return (t == null) ? null : t.getText(); }

    private static String extractSchema(String fqn) {
        if (fqn == null) return null;
        int i = fqn.indexOf('.');
        if (i < 0) return null;
        return stripQuotes(fqn.substring(0, i));
    }

    private static String extractName(String fqn) {
        if (fqn == null) return null;
        int i = fqn.indexOf('.');
        String name = (i < 0) ? fqn : fqn.substring(i + 1);
        return stripQuotes(name);
    }

    private static String stripQuotes(String s) {
        if (s == null) return null;
        String x = s;
        if (x.startsWith("[") && x.endsWith("]")) x = x.substring(1, x.length() - 1);
        if (x.startsWith("\"") && x.endsWith("\"")) x = x.substring(1, x.length() - 1);
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
