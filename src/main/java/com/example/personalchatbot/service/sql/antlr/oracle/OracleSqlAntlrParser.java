package com.example.personalchatbot.service.sql.antlr.oracle;

import com.example.personalchatbot.service.sql.antlr.implement.AntlrSqlParserImpl;
import com.example.personalchatbot.service.sql.dto.CaseChangingCharStream;
import com.example.personalchatbot.service.sql.dto.MetadataDto;
import com.example.personalchatbot.service.sql.dto.SqlChunkDto;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.springframework.stereotype.Component;
import com.example.personalchatbot.service.sql.antlr.oracle.OracleSQLParser;
import com.example.personalchatbot.service.sql.antlr.oracle.OracleSQLLexer;
import com.example.personalchatbot.service.sql.antlr.oracle.OracleSQLBaseVisitor;

import java.util.*;

@Slf4j
@Component
public class OracleSqlAntlrParser implements AntlrSqlParserImpl {

    @Override
    public String dialect() {
        return "oracle";
    }

    /* --------------------- Split --------------------- */
    @Override
    public List<SqlChunkDto> split(@NonNull String script) {
        OracleSQLParser.SqlContext root = parseSql(script);
        List<SqlChunkDto> chunks = new ArrayList<>();

        int i = 0;
        for (OracleSQLParser.SqlStatementContext statement : root.sqlStatement()) {
            Interval interval = intervalOf(statement);
            String text = slice(script, interval).trim();

            MetadataDto metadata = analyzeByNode(statement);
            String kind = metadata != null && metadata.getStatementType() != null ? metadata.getStatementType() : "RAW_STATEMENT";

            SqlChunkDto dto = SqlChunkDto.builder()
                    .index(i++)
                    .part(1)
                    .totalParts(1)
                    .dialect(dialect())
                    .kind(kind)
                    .schemaName(metadata != null ? metadata.getSchemaName() : null)
                    .objectName(metadata != null ? metadata.getObjectName() : null)
                    .content(text)
                    .metadata(metadata)
                    .metadataJson(null)
                    .build();
            chunks.add(dto);
        }
        return chunks;
    }

    @Override
    public MetadataDto analyze(@NonNull String singleStatement) {
        OracleSQLParser p = newParser(singleStatement);
        OracleSQLParser.SqlStatementContext st = p.sqlStatement();
        MetadataDto md = analyzeByNode(st);
        return md != null ? md : MetadataDto.minimal("RAW_STATEMENT");
    }

    /* ===================== ANTLR bootstrap ===================== */

    //--------------- Lấy schemaName/objectName -----------------
    private static String[] splitQname(OracleSQLParser.QnameContext q) {
        if (q == null) return new String[]{null, null};
        List<OracleSQLParser.IdContext> ids = q.id();
        if (ids == null || ids.isEmpty()) return new String[]{null, null};
        String obj = unquote(ids.getLast().getText());
        String schema = (ids.size() > 1) ? unquote(ids.getFirst().getText()) : null;
        return new String[]{schema, obj};
    }

    private OracleSQLParser.SqlContext parseSql(String text) {
        OracleSQLParser parser = newParser(text);
        return parser.sql();
    }

    private OracleSQLParser newParser(String text) {
        CharStream cs = CharStreams.fromString(text);
        OracleSQLLexer lexer = new OracleSQLLexer(new CaseChangingCharStream(cs, true));
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
            String[] so = splitQname(ctx.qname());
            String schemaName = so[0], objectName = so[1];
            TableScanner scanner = new TableScanner();
            scanner.visit(ctx);

            return MetadataDto.builder()
                    .statementType("CREATE_TABLE")
                    .schemaName(schemaName)
                    .objectName(objectName)
                    .tables(Collections.singletonList(objectName))
                    .columns(scanner.columns())
                    .build();
        }

        private static class TableScanner extends OracleSQLBaseVisitor<Void> {
            private final List<String> columns = new ArrayList<>();
            private final List<Map<String, Object>> columnDetails = new ArrayList<>();
            private final Map<String, Object> tableExtras = new LinkedHashMap<>();

            List<String> columns() { return columns; }

            @Override
            public Void visitColumnDef(OracleSQLParser.ColumnDefContext ctx) {
                String colName = unquote(ctx.id().getText());

                Map<String, Object> col = new LinkedHashMap<>();
                col.put("name", colName);
                if (ctx.dataType() != null && ctx.dataType().getText() != null && !ctx.dataType().getText().trim().isEmpty()) {
                    col.put("dataType", ctx.dataType().getText());
                    colName = colName + "(" + unquote(ctx.dataType().getText()) + ")";
                }
                columns.add(colName);

                for (OracleSQLParser.ColumnRestContext r : ctx.columnRest()) {
                    if (r instanceof OracleSQLParser.ColDefaultContext c) {
                        col.put("default", c.expr().getText());
                    } else if (r instanceof OracleSQLParser.ColDefaultOnNullContext c) {
                        col.put("defaultOnNull", c.expr().getText());
                    } else if (r instanceof OracleSQLParser.ColGeneratedVirtualContext c) {
                        if (c.expr() != null) {
                            col.put("virtualExpr", c.expr().getText());
                        }
                        col.put("virtual", true);
                    } else if (r instanceof OracleSQLParser.ColIdentityAlwaysContext) {
                        col.put("identity", "ALWAYS");
                    } else if (r instanceof OracleSQLParser.ColIdentityByDefaultContext) {
                        col.put("identity", "BY_DEFAULT");
                    } else if (r instanceof OracleSQLParser.ColIdentityByDefaultOnNullContext) {
                        col.put("identity", "BY_DEFAULT_ON_NULL");
                    } else if (r instanceof OracleSQLParser.ColConstraintInlineContext c) {
                        @SuppressWarnings("unchecked")
                        List<String> cs = (List<String>) col.computeIfAbsent("constraints", k -> new ArrayList<String>());
                        cs.add(c.getText());
                    }
                }
                columnDetails.add(col);
                return null;
            }

            @Override
            public Void visitTableConstraint(OracleSQLParser.TableConstraintContext ctx) {
                tableExtras.computeIfAbsent("tableConstraints", k -> new ArrayList<String>());
                @SuppressWarnings("unchecked")
                List<String> cs = (List<String>) tableExtras.get("tableConstraints");
                cs.add(ctx.getText());
                return null;
            }

            @Override
            public Void visitPartitionByRange(OracleSQLParser.PartitionByRangeContext ctx) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("type", "RANGE");
                p.put("key", ctx.id().getText());

                List<String> parts = new ArrayList<>();
                for (OracleSQLParser.PartitionSpecContext sp : ctx.partitionSpec()) {
                    parts.add(sp.getText());
                }
                p.put("partitions", parts);
                tableExtras.put("partitioning", p);
                return null;
            }
        }

        @Override
        public MetadataDto visitStCreateGtt(OracleSQLParser.StCreateGttContext ctx) {
            String[] so = splitQname(ctx.qname());
            String schemaName = so[0], objectName = so[1];
            TableScanner scanner = new TableScanner();
            scanner.visit(ctx);

            return MetadataDto.builder()
                    .statementType("CREATE_GLOBAL_TEMP_TABLE")
                    .schemaName(schemaName)
                    .objectName(objectName)
                    .tables(Collections.singletonList(objectName))
                    .columns(scanner.columns()).build();
        }

        /* -------- CREATE INDEX -------- */

        @Override
        public MetadataDto visitStCreateIndex(OracleSQLParser.StCreateIndexContext ctx) {
            String[] so = splitQname(ctx.qname(0));
            String onTable = unquote(text(ctx.qname(1)));
            String schemaName = so[0], objectName = so[1];
            List<String> cols = new ArrayList<>();
            for (OracleSQLParser.IndexExprContext ie : ctx.indexExpr()) {
                cols.add(ie.getText());
            }

            return MetadataDto.builder()
                    .statementType("CREATE_INDEX")
                    .schemaName(schemaName)
                    .objectName(objectName)
                    .tables(Collections.singletonList(onTable))
                    .columns(cols)
                    .build();
        }

        /* -------- CREATE VIEW -------- */

        @Override
        public MetadataDto visitStCreateView(OracleSQLParser.StCreateViewContext ctx) {
            String[] so = splitQname(ctx.viewName);
            String schemaName = so[0], objectName = so[1];
            QueryScanner scanner = new QueryScanner();
            scanner.visit(ctx.selectStatement());

            return MetadataDto.builder()
                    .statementType("CREATE_VIEW")
                    .schemaName(schemaName)
                    .objectName(objectName)
                    .tables(scanner.tables())
                    .columns(scanner.columns())
                    .build();
        }

        /* -------- CREATE MATERIALIZED VIEW -------- */

        @Override
        public MetadataDto visitStCreateMaterializedView(OracleSQLParser.StCreateMaterializedViewContext ctx) {
            String[] so = splitQname(ctx.qname());
            String schemaName = so[0], objectName = so[1];
            QueryScanner scanner = new QueryScanner();
            scanner.visit(ctx.selectStatement());

            return MetadataDto.builder()
                    .statementType("CREATE_MATERIALIZED_VIEW")
                    .schemaName(schemaName)
                    .objectName(objectName)
                    .tables(scanner.tables())
                    .columns(scanner.columns())
                    .build();
        }


        /* -------- Other DDL -------- */

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
                String raw = ctx.sel.getText();
                String tail = tryExtractTailIdFromExpr(ctx.sel);
                col = (tail != null) ? unquote(tail) : raw;
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
            return super.visitSelectStatement(ctx);
        }

        private String tryExtractTailIdFromExpr(OracleSQLParser.SelectExprContext expr) {
            List<TerminalNode> ids = expr.getTokens(OracleSQLParser.IDENTIFIER);
            if (ids != null && !ids.isEmpty()) {
                return ids.getLast().getText();
            }
            List<TerminalNode> quotedId = expr.getTokens(OracleSQLParser.QUOTED_IDENTIFIER);
            if (quotedId != null && !quotedId.isEmpty()) {
                return quotedId.getLast().getText();
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
