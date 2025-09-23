package com.example.personalchatbot.service.sql.antlr.postgres;

import com.example.personalchatbot.service.sql.dto.CaseChangingCharStream;
import com.example.personalchatbot.service.sql.dto.MetadataDto;
import com.example.personalchatbot.service.sql.dto.SqlChunkDto;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.antlr.v4.runtime.*;
import com.example.personalchatbot.service.sql.antlr.implement.AntlrSqlParserImpl;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.springframework.stereotype.Component;
import com.example.personalchatbot.service.sql.antlr.postgres.PostgreSQLParser;
import com.example.personalchatbot.service.sql.antlr.postgres.PostgreSQLLexer;
import com.example.personalchatbot.service.sql.antlr.postgres.PostgreSQLBaseVisitor;

import java.util.*;

@Slf4j
@Component
public class PgAntlrSqlParser implements AntlrSqlParserImpl {

    @Override
    public String dialect() {
        return "postgresql";
    }

    /* --------------------- Split --------------------- */
    @Override
    public List<SqlChunkDto> split(@NonNull String script) {
        PostgreSQLParser.SqlStatementsContext root = parseSql(script);
        List<SqlChunkDto> chunks = new ArrayList<>();

        int i = 0;
        for (PostgreSQLParser.SqlStatementContext st : root.sqlStatement()) {
            Interval itv = intervalOf(st);
            String text = slice(script, itv).trim();

            MetadataDto md = analyzeByNode(st);
            String kind = md != null && md.getStatementType() != null ? md.getStatementType() : "RAW_STATEMENT";

            SqlChunkDto dto = SqlChunkDto.builder()
                    .index(i++)
                    .part(1)
                    .totalParts(1)
                    .dialect(dialect())
                    .kind(kind)
                    .schemaName(md != null ? md.getSchemaName() : null)
                    .objectName(md != null ? md.getObjectName() : null)
                    .content(text)
                    .metadata(md)
                    .metadataJson(null)
                    .build();
            chunks.add(dto);
        }
        return chunks;
    }

    @Override
    public MetadataDto analyze(@NonNull String singleStatement) {
        PostgreSQLParser p = newParser(singleStatement);
        PostgreSQLParser.SqlStatementContext st = p.sqlStatement();
        MetadataDto md = analyzeByNode(st);
        return md != null ? md : MetadataDto.minimal("RAW_STATEMENT");
    }

    /* ===================== ANTLR bootstrap ===================== */

    private PostgreSQLParser.SqlStatementsContext parseSql(String text) {
        PostgreSQLParser parser = newParser(text);
        return parser.sqlStatements();
    }

    private PostgreSQLParser newParser(String text) {
        CharStream cs = new CaseChangingCharStream(CharStreams.fromString(text), true);
        PostgreSQLLexer lexer = new PostgreSQLLexer(cs);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        PostgreSQLParser parser = new PostgreSQLParser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(new SilentErrorListener());
        parser.setErrorHandler(new DefaultErrorStrategy());
        return parser;
    }

    private static Interval intervalOf(ParserRuleContext ctx) {
        Token start = ctx.getStart();
        Token stop = ctx.getStop();
        int a = start != null ? start.getStartIndex() : 0;
        int b = stop != null ? stop.getStopIndex() : a;
        return Interval.of(a, b);
    }

    private static String slice(String src, Interval itv) {
        if (src == null || itv == null) return "";
        int a = Math.max(0, itv.a);
        int b = Math.min(src.length(), itv.b + 1);
        if (a >= b) return "";
        return src.substring(a, b);
    }

    private static String text(ParserRuleContext ctx) {
        return ctx != null ? ctx.getText() : null;
    }

    private static String unquote(String s) {
        if (s == null) return null;
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1).replace("\"\"", "\"");
        }
        return s;
    }

    /* ===================== Metadata qua Visitor ===================== */

    private MetadataDto analyzeByNode(PostgreSQLParser.SqlStatementContext st) {
        return new AstVisitor().visit(st);
    }

    private static class AstVisitor extends PostgreSQLBaseVisitor<MetadataDto> {

        @Override
        public MetadataDto visitSqlStatement(PostgreSQLParser.SqlStatementContext ctx) {
            if (ctx.createSchemaStatement() != null)             return visit(ctx.createSchemaStatement());
            if (ctx.createTableStatement() != null)              return visit(ctx.createTableStatement());
            if (ctx.createIndexStatement() != null)              return visit(ctx.createIndexStatement());
            if (ctx.createViewStatement() != null)               return visit(ctx.createViewStatement());
            if (ctx.createMaterializedViewStatement() != null)   return visit(ctx.createMaterializedViewStatement());
            if (ctx.createFunctionStatement() != null)           return visit(ctx.createFunctionStatement());
            if (ctx.createProcedureStatement() != null)          return visit(ctx.createProcedureStatement());
            if (ctx.doStatement() != null)                       return MetadataDto.minimal("DO");
            if (ctx.createTriggerStatement() != null)            return visit(ctx.createTriggerStatement());
            if (ctx.createTypeEnumStatement() != null)           return visit(ctx.createTypeEnumStatement());
            if (ctx.createTypeCompositeStatement() != null)      return visit(ctx.createTypeCompositeStatement());
            if (ctx.createExtensionStatement() != null)          return visit(ctx.createExtensionStatement());
            if (ctx.createDomainStatement() != null)             return visit(ctx.createDomainStatement());
            if (ctx.createSequenceStatement() != null)           return visit(ctx.createSequenceStatement());
            if (ctx.createPolicyStatement() != null)             return visit(ctx.createPolicyStatement());
            if (ctx.commentOnStatement() != null)                return MetadataDto.minimal("COMMENT_ON");
            return MetadataDto.minimal("RAW_STATEMENT");
        }

        /* -------- CREATE SCHEMA -------- */

        @Override
        public MetadataDto visitCreateSchemaStatement(PostgreSQLParser.CreateSchemaStatementContext ctx) {
            // objectName = tên schema
            String name = null;
            if (ctx.qualifiedName() != null) {
                name = unquote(ctx.qualifiedName().getText());
            } else if (ctx.identifier() != null && !ctx.identifier().isEmpty()) {
                name = unquote(ctx.identifier().getText());
            }
            return MetadataDto.builder()
                    .statementType("CREATE_SCHEMA")
                    .schemaName(name)
                    .objectName(name)
                    .tables(Collections.emptyList())
                    .columns(Collections.emptyList())
                    .build();
        }

        /* -------- CREATE TABLE -------- */

        @Override
        public MetadataDto visitCreateTableStatement(PostgreSQLParser.CreateTableStatementContext ctx) {
            String tableName = unquote(text(ctx.tableName));
            TableScanner scanner = new TableScanner();
            scanner.visit(ctx);

            return MetadataDto.builder()
                    .statementType("CREATE_TABLE")
                    .schemaName(null)
                    .objectName(tableName)
                    .tables(Collections.singletonList(tableName))
                    .columns(scanner.columns())
                    .build();
        }

        private static class TableScanner extends PostgreSQLBaseVisitor<Void> {
            private final List<String> columns = new ArrayList<>();

            List<String> columns() { return columns; }

            @Override
            public Void visitColumnDefinition(PostgreSQLParser.ColumnDefinitionContext ctx) {
                String colName = unquote(ctx.columnName.getText());
                String out = colName;
                if (ctx.dataType() != null && ctx.dataType().getText() != null && !ctx.dataType().getText().isBlank()) {
                    out = colName + "(" + ctx.dataType().getText() + ")";
                }
                columns.add(out);
                return null;
            }
        }

        /* -------- CREATE INDEX -------- */

        @Override
        public MetadataDto visitCreateIndexStatement(PostgreSQLParser.CreateIndexStatementContext ctx) {
            String idxName = unquote(text(ctx.indexName));
            String onTable = unquote(text(ctx.tableName));
            List<String> cols = new ArrayList<>();
            for (PostgreSQLParser.IndexElemContext ie : ctx.indexElem()) {
                cols.add(ie.getText());
            }
            return MetadataDto.builder()
                    .statementType("CREATE_INDEX")
                    .schemaName(null)
                    .objectName(idxName)
                    .tables(onTable == null ? Collections.emptyList() : Collections.singletonList(onTable))
                    .columns(cols)
                    .build();
        }

        /* -------- CREATE VIEW / MATERIALIZED VIEW -------- */

        @Override
        public MetadataDto visitCreateViewStatement(PostgreSQLParser.CreateViewStatementContext ctx) {
            String name = unquote(text(ctx.viewName));
            QueryScanner scanner = new QueryScanner();
            if (ctx.selectStmt() != null) scanner.visit(ctx.selectStmt());

            return MetadataDto.builder()
                    .statementType("CREATE_VIEW")
                    .schemaName(null)
                    .objectName(name)
                    .tables(scanner.tables())
                    .columns(scanner.columns())
                    .build();
        }

        @Override
        public MetadataDto visitCreateMaterializedViewStatement(PostgreSQLParser.CreateMaterializedViewStatementContext ctx) {
            String name = unquote(text(ctx.viewName));
            QueryScanner scanner = new QueryScanner();
            if (ctx.selectStmt() != null) scanner.visit(ctx.selectStmt());

            return MetadataDto.builder()
                    .statementType("CREATE_MATERIALIZED_VIEW")
                    .schemaName(null)
                    .objectName(name)
                    .tables(scanner.tables())
                    .columns(scanner.columns())
                    .build();
        }

        /* -------- FUNCTION / PROCEDURE -------- */

        @Override
        public MetadataDto visitCreateFunctionStatement(PostgreSQLParser.CreateFunctionStatementContext ctx) {
            return MetadataDto.builder()
                    .statementType("CREATE_FUNCTION")
                    .objectName(unquote(text(ctx.funcName)))
                    .schemaName(null)
                    .tables(Collections.emptyList())
                    .columns(Collections.emptyList())
                    .build();
        }

        @Override
        public MetadataDto visitCreateProcedureStatement(PostgreSQLParser.CreateProcedureStatementContext ctx) {
            return MetadataDto.builder()
                    .statementType("CREATE_PROCEDURE")
                    .objectName(unquote(text(ctx.procName)))
                    .schemaName(null)
                    .tables(Collections.emptyList())
                    .columns(Collections.emptyList())
                    .build();
        }

        /* -------- TRIGGER -------- */

        @Override
        public MetadataDto visitCreateTriggerStatement(PostgreSQLParser.CreateTriggerStatementContext ctx) {
            String trgName = unquote(text(ctx.trgName));
            String onTable = ctx.onTable != null ? unquote(ctx.onTable.getText()) : null;

            return MetadataDto.builder()
                    .statementType("CREATE_TRIGGER")
                    .objectName(trgName)
                    .schemaName(null)
                    .tables(onTable == null ? Collections.emptyList() : Collections.singletonList(onTable))
                    .columns(Collections.emptyList())
                    .build();
        }

        /* -------- TYPE / DOMAIN / EXTENSION / SEQUENCE / POLICY -------- */

        @Override
        public MetadataDto visitCreateTypeEnumStatement(PostgreSQLParser.CreateTypeEnumStatementContext ctx) {
            return MetadataDto.builder()
                    .statementType("CREATE_TYPE_ENUM")
                    .objectName(unquote(text(ctx.typeName)))
                    .schemaName(null)
                    .tables(Collections.emptyList())
                    .columns(Collections.emptyList())
                    .build();
        }

        @Override
        public MetadataDto visitCreateTypeCompositeStatement(PostgreSQLParser.CreateTypeCompositeStatementContext ctx) {
            String name = unquote(text(ctx.typeName));
            List<String> fields = new ArrayList<>();
            for (PostgreSQLParser.CompFieldContext f : ctx.compField()) {
                fields.add(unquote(f.identifier().getText()) +
                        (f.dataType() != null ? "(" + f.dataType().getText() + ")" : ""));
            }
            return MetadataDto.builder()
                    .statementType("CREATE_TYPE_COMPOSITE")
                    .objectName(name)
                    .schemaName(null)
                    .tables(Collections.emptyList())
                    .columns(fields)
                    .build();
        }

        @Override
        public MetadataDto visitCreateDomainStatement(PostgreSQLParser.CreateDomainStatementContext ctx) {
            return MetadataDto.builder()
                    .statementType("CREATE_DOMAIN")
                    .objectName(unquote(text(ctx.domainName)))
                    .schemaName(null)
                    .tables(Collections.emptyList())
                    .columns(Collections.emptyList())
                    .build();
        }

        @Override
        public MetadataDto visitCreateExtensionStatement(PostgreSQLParser.CreateExtensionStatementContext ctx) {
            return MetadataDto.builder()
                    .statementType("CREATE_EXTENSION")
                    .objectName(ctx.extName != null ? unquote(ctx.extName.getText()) : null)
                    .schemaName(null)
                    .tables(Collections.emptyList())
                    .columns(Collections.emptyList())
                    .build();
        }

        @Override
        public MetadataDto visitCreateSequenceStatement(PostgreSQLParser.CreateSequenceStatementContext ctx) {
            return MetadataDto.builder()
                    .statementType("CREATE_SEQUENCE")
                    .objectName(unquote(text(ctx.seqName)))
                    .schemaName(null)
                    .tables(Collections.emptyList())
                    .columns(Collections.emptyList())
                    .build();
        }

        @Override
        public MetadataDto visitCreatePolicyStatement(PostgreSQLParser.CreatePolicyStatementContext ctx) {
            return MetadataDto.builder()
                    .statementType("CREATE_POLICY")
                    .objectName(ctx.polName != null ? unquote(ctx.polName.getText()) : null)
                    .schemaName(null)
                    .tables(ctx.tbl != null ? List.of(unquote(ctx.tbl.getText())) : Collections.emptyList())
                    .columns(Collections.emptyList())
                    .build();
        }
    }

    /* ---------------- Query scanner for SELECT ---------------- */

    private static class QueryScanner extends PostgreSQLBaseVisitor<Void> {
        private final LinkedHashSet<String> tables = new LinkedHashSet<>();
        private final List<String> columns = new ArrayList<>();

        List<String> columns() { return columns; }
        List<String> tables()  { return new ArrayList<>(tables); }

        @Override
        public Void visitSelectItem(PostgreSQLParser.SelectItemContext ctx) {
            String col;
            if (ctx.identifier() != null) {
                // CASE: expr AS alias
                col = unquote(ctx.identifier().getText());
            } else if (ctx.expr() != null) {
                String raw = ctx.expr().getText();
                String tail = tryExtractTailIdFromExpr(ctx.expr());
                col = (tail != null) ? unquote(tail) : raw;
            } else if (ctx.getText() != null && ctx.getText().equals("*")) {
                col = "*";
            } else {
                col = ctx.getText();
            }
            columns.add(col);
            return null;
        }

        @Override
        public Void visitTableRef(PostgreSQLParser.TableRefContext ctx) {
            if (ctx.qualifiedName() != null) {
                tables.add(unquote(ctx.qualifiedName().getText()));
            }
            return null;
        }

        private String tryExtractTailIdFromExpr(PostgreSQLParser.ExprContext expr) {
            List<TerminalNode> ids = expr.getTokens(PostgreSQLParser.IDENTIFIER);
            if (ids != null && !ids.isEmpty()) {
                return ids.getLast().getText();
            }
            List<TerminalNode> qids = expr.getTokens(PostgreSQLParser.QUOTED_IDENT);
            if (qids != null && !qids.isEmpty()) {
                return qids.getLast().getText();
            }
            return null;
        }
    }

    private static class SilentErrorListener extends BaseErrorListener {
        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                int line, int charPositionInLine, String msg, RecognitionException e) {
            log.info("ANTLR 4 xử lý dòng {} lỗi: {}", line, msg);
        }
    }
}
