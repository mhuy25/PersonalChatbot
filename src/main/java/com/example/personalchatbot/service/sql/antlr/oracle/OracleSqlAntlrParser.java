package com.example.personalchatbot.service.sql.antlr.oracle;

import com.example.personalchatbot.service.sql.antlr.implement.AntlrSqlParserImpl;
import com.example.personalchatbot.service.sql.dto.MetadataDto;
import com.example.personalchatbot.service.sql.dto.SqlChunkDto;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.misc.Interval;
import org.springframework.stereotype.Component;
import com.example.personalchatbot.service.sql.antlr.oracle.OracleSQLParser;
import com.example.personalchatbot.service.sql.antlr.oracle.OracleSQLLexer;
import com.example.personalchatbot.service.sql.antlr.oracle.OracleSQLBaseVisitor;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class OracleSqlAntlrParser implements AntlrSqlParserImpl {

    @Override
    public String dialect() {
        return "oracle";
    }

    /* --------------------- Split --------------------- */

    /** Cắt script thành các câu lệnh dựa vào rule sql/sqlStatement. */
    @Override
    public List<SqlChunkDto> split(@NonNull String script) {
        OracleSQLParser.SqlContext root = parseSql(script);
        List<SqlChunkDto> chunks = new ArrayList<>();

        int i = 0;
        for (OracleSQLParser.SqlStatementContext st : root.sqlStatement()) {
            Interval interval = intervalOf(st);
            String text = slice(script, interval).trim();

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

    /** Phân tích DUY NHẤT một câu lệnh. */
    @Override
    public MetadataDto analyze(@NonNull String singleStatement) {
        OracleSQLParser p = newParser(singleStatement);
        OracleSQLParser.SqlStatementContext st = p.sqlStatement();
        MetadataDto md = analyzeByNode(st);
        return md != null ? md : MetadataDto.minimal("RAW_STATEMENT");
    }

    /* ===================== ANTLR bootstrap ===================== */

    private OracleSQLParser.SqlContext parseSql(String text) {
        OracleSQLParser parser = newParser(text);
        return parser.sql();
    }

    private OracleSQLParser newParser(String text) {
        CharStream cs = CharStreams.fromString(text);
        OracleSQLLexer lexer = new OracleSQLLexer(cs);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        OracleSQLParser parser = new OracleSQLParser(tokens);
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

    private MetadataDto analyzeByNode(OracleSQLParser.SqlStatementContext st) {
        return new AstVisitor().visit(st);
    }

    /** Visitor gọn nhẹ để trích statementType/objectName/tables/columns. */
    private static class AstVisitor extends OracleSQLBaseVisitor<MetadataDto> {

        @Override
        public MetadataDto visitSqlStatement(OracleSQLParser.SqlStatementContext ctx) {
            if (ctx.stCreateTable() != null)             return visit(ctx.stCreateTable());
            if (ctx.stCreateGtt() != null)               return visit(ctx.stCreateGtt());
            if (ctx.stCreateIndex() != null)             return visit(ctx.stCreateIndex());
            if (ctx.stCreateView() != null)              return visit(ctx.stCreateView());
            if (ctx.stCreateMaterializedView() != null)  return visit(ctx.stCreateMaterializedView());
            if (ctx.stCreateSequence() != null)          return visit(ctx.stCreateSequence());
            if (ctx.stCreateSynonym() != null)           return visit(ctx.stCreateSynonym());
            if (ctx.stCreateTrigger() != null)           return visit(ctx.stCreateTrigger());
            if (ctx.stCreatePackageSpec() != null)       return visit(ctx.stCreatePackageSpec());
            if (ctx.stCreatePackageBody() != null)       return visit(ctx.stCreatePackageBody());
            if (ctx.stCreateProcedure() != null)         return visit(ctx.stCreateProcedure());
            if (ctx.stAnonymousBlock() != null)          return MetadataDto.minimal("BEGIN_BLOCK");
            if (ctx.stAlterSession() != null)            return MetadataDto.minimal("ALTER_SESSION");
            if (ctx.stComment() != null)                 return MetadataDto.minimal("COMMENT");
            return MetadataDto.minimal("RAW_STATEMENT");
        }

        /* -------- CREATE TABLE & GTT -------- */

        @Override
        public MetadataDto visitStCreateTable(OracleSQLParser.StCreateTableContext ctx) {
            String name = text(ctx.tblName);
            List<String> cols = ctx.getRuleContexts(OracleSQLParser.ColumnDefContext.class)
                    .stream()
                    .map(c -> unquote(text(c.colName)))
                    .collect(Collectors.toList());

            return MetadataDto.builder()
                    .statementType("CREATE_TABLE")
                    .schemaName(null)
                    .objectName(unquote(name))
                    .tables(name != null ? Collections.singletonList(unquote(name)) : Collections.emptyList())
                    .columns(cols)
                    .build();
        }

        @Override
        public MetadataDto visitStCreateGtt(OracleSQLParser.StCreateGttContext ctx) {
            String name = text(ctx.tblName);
            List<String> cols = ctx.getRuleContexts(OracleSQLParser.ColumnDefContext.class)
                    .stream()
                    .map(c -> unquote(text(c.colName)))
                    .collect(Collectors.toList());

            return MetadataDto.builder()
                    .statementType("CREATE_GLOBAL_TEMPORARY_TABLE")
                    .schemaName(null)
                    .objectName(unquote(name))
                    .tables(name != null ? Collections.singletonList(unquote(name)) : Collections.emptyList())
                    .columns(cols)
                    .build();
        }

        /* -------- CREATE INDEX -------- */

        @Override
        public MetadataDto visitStCreateIndex(OracleSQLParser.StCreateIndexContext ctx) {
            String name = unquote(text(ctx.idxName));
            return MetadataDto.builder()
                    .statementType("CREATE_INDEX")
                    .schemaName(null)
                    .objectName(name)
                    .tables(Collections.emptyList())
                    .columns(Collections.emptyList())
                    .build();
        }

        /* -------- CREATE VIEW -------- */

        @Override
        public MetadataDto visitStCreateView(OracleSQLParser.StCreateViewContext ctx) {
            String name = unquote(text(ctx.viewName));
            QueryScanner scanner = new QueryScanner();
            scanner.visit(ctx.selectStatement());

            return MetadataDto.builder()
                    .statementType("CREATE_VIEW")
                    .schemaName(null)
                    .objectName(name)
                    .tables(scanner.tables())
                    .columns(scanner.columns())
                    .build();
        }

        /* -------- CREATE MATERIALIZED VIEW -------- */

        @Override
        public MetadataDto visitStCreateMaterializedView(OracleSQLParser.StCreateMaterializedViewContext ctx) {
            String name = unquote(text(ctx.mvName));
            QueryScanner scanner = new QueryScanner();
            scanner.visit(ctx.selectStatement());

            return MetadataDto.builder()
                    .statementType("CREATE_MATERIALIZED_VIEW")
                    .schemaName(null)
                    .objectName(name)
                    .tables(scanner.tables())
                    .columns(scanner.columns())
                    .build();
        }

        /* -------- Các DDL khác -------- */

        @Override
        public MetadataDto visitStCreateSequence(OracleSQLParser.StCreateSequenceContext ctx) {
            return MetadataDto.builder()
                    .statementType("CREATE_SEQUENCE")
                    .objectName(unquote(text(ctx.seqName)))
                    .schemaName(null)
                    .tables(Collections.emptyList())
                    .columns(Collections.emptyList())
                    .build();
        }

        @Override
        public MetadataDto visitStCreateSynonym(OracleSQLParser.StCreateSynonymContext ctx) {
            return MetadataDto.builder()
                    .statementType("CREATE_SYNONYM")
                    .objectName(unquote(text(ctx.synName)))
                    .schemaName(null)
                    .tables(Collections.emptyList())
                    .columns(Collections.emptyList())
                    .build();
        }

        @Override
        public MetadataDto visitStCreateTrigger(OracleSQLParser.StCreateTriggerContext ctx) {
            return MetadataDto.builder()
                    .statementType("CREATE_TRIGGER")
                    .objectName(unquote(text(ctx.trgName)))
                    .schemaName(null)
                    .tables(Collections.emptyList())
                    .columns(Collections.emptyList())
                    .build();
        }

        @Override
        public MetadataDto visitStCreatePackageSpec(OracleSQLParser.StCreatePackageSpecContext ctx) {
            return MetadataDto.builder()
                    .statementType("PACKAGE_SPEC")
                    .objectName(unquote(text(ctx.pkgName)))
                    .schemaName(null)
                    .tables(Collections.emptyList())
                    .columns(Collections.emptyList())
                    .build();
        }

        @Override
        public MetadataDto visitStCreatePackageBody(OracleSQLParser.StCreatePackageBodyContext ctx) {
            return MetadataDto.builder()
                    .statementType("PACKAGE_BODY")
                    .objectName(unquote(text(ctx.pkgName)))
                    .schemaName(null)
                    .tables(Collections.emptyList())
                    .columns(Collections.emptyList())
                    .build();
        }

        @Override
        public MetadataDto visitStCreateProcedure(OracleSQLParser.StCreateProcedureContext ctx) {
            return MetadataDto.builder()
                    .statementType("PROCEDURE")
                    .objectName(unquote(text(ctx.procName)))
                    .schemaName(null)
                    .tables(Collections.emptyList())
                    .columns(Collections.emptyList())
                    .build();
        }
    }

    /** Scanner con để gom bảng & cột trong SELECT (dùng cho VIEW/MVIEW). */
    private static class QueryScanner extends OracleSQLBaseVisitor<Void> {
        private final LinkedHashSet<String> tables = new LinkedHashSet<>();
        private final List<String> columns = new ArrayList<>();

        List<String> columns() { return columns; }
        List<String> tables()  { return new ArrayList<>(tables); }

        @Override
        public Void visitSelectItem(OracleSQLParser.SelectItemContext ctx) {
            String col;
            if (ctx.alias != null) {
                col = unquote(ctx.alias.getText());
            } else {
                col = ctx.expr.getText();
            }
            columns.add(col);
            return null;
        }

        @Override
        public Void visitTableRef(OracleSQLParser.TableRefContext ctx) {
            if (ctx.qname() != null) {
                tables.add(unquote(ctx.qname().getText()));
            }
            return null;
        }

        @Override
        public Void visitSelectStatement(OracleSQLParser.SelectStatementContext ctx) {
            return super.visitSelectStatement(ctx); // duyệt children
        }
    }

    /** Listener im lặng để không throw exception khi gặp lỗi cú pháp. */
    private static class SilentErrorListener extends BaseErrorListener {
        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                int line, int charPositionInLine, String msg, RecognitionException e) {
            // no-op
        }
    }
}
