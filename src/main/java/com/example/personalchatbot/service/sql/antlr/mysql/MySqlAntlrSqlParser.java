package com.example.personalchatbot.service.sql.antlr.mysql;

import com.example.personalchatbot.service.sql.antlr.implement.AntlrSqlParserImpl;
import com.example.personalchatbot.service.sql.dto.MetadataDto;
import com.example.personalchatbot.service.sql.dto.SqlChunkDto;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.misc.Interval;
import org.springframework.stereotype.Component;
import com.example.personalchatbot.service.sql.antlr.mysql.MySQLParser;
import com.example.personalchatbot.service.sql.antlr.mysql.MySQLLexer;
import com.example.personalchatbot.service.sql.antlr.mysql.MySQLBaseVisitor;

import java.util.*;

@Slf4j
@Component
public class MySqlAntlrSqlParser implements AntlrSqlParserImpl {

    @Override
    public String dialect() {
        return "mysql";
    }

    /* --------------------- Split --------------------- */
    @Override
    public List<SqlChunkDto> split(@NonNull String script) {
        MySQLParser.SqlStatementsContext root = parseSql(script);
        List<SqlChunkDto> chunks = new ArrayList<>();

        int i = 0;
        for (MySQLParser.SqlStatementContext st : root.sqlStatement()) {
            Interval itv = intervalOf(st);
            String text = slice(script, itv).trim();

            MetadataDto metadata = analyzeByNode(st);
            String kind = (metadata != null && metadata.getStatementType() != null)
                    ? metadata.getStatementType()
                    : "RAW_STATEMENT";

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

    /* --------------------- Analyze --------------------- */
    @Override
    public MetadataDto analyze(@NonNull String singleStatement) {
        MySQLParser p = newParser(singleStatement);
        MySQLParser.SqlStatementsContext root = p.sqlStatements();
        List<MySQLParser.SqlStatementContext> list = root.sqlStatement();
        if (list.isEmpty()) {
            return MetadataDto.minimal("RAW_STATEMENT");
        }
        MetadataDto md = analyzeByNode(list.getFirst());
        return md != null ? md : MetadataDto.minimal("RAW_STATEMENT");
    }

    /* ===================== ANTLR bootstrap ===================== */

    private MySQLParser.SqlStatementsContext parseSql(String text) {
        MySQLParser parser = newParser(text);
        return parser.sqlStatements();
    }

    private MySQLParser newParser(String text) {
        CharStream cs = CharStreams.fromString(text);
        MySQLLexer lexer = new MySQLLexer(cs);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        MySQLParser parser = new MySQLParser(tokens);
        lexer.removeErrorListeners();
        parser.removeErrorListeners();
        parser.addErrorListener(new SilentErrorListener());
        parser.setErrorHandler(new DefaultErrorStrategy());
        return parser;
    }

    private static Interval intervalOf(ParserRuleContext ctx) {
        Token start = ctx.getStart();
        Token stop  = ctx.getStop();
        int a = (start != null) ? start.getStartIndex() : 0;
        int b = (stop  != null) ? stop.getStopIndex()  : a;
        return Interval.of(a, b);
    }

    private static String slice(String src, Interval itv) {
        if (src == null || itv == null) return "";
        int a = Math.max(0, itv.a);
        int b = Math.min(src.length(), itv.b + 1);
        if (a >= b) return "";
        return src.substring(a, b);
    }

    private static String qnameText(MySQLParser.QualifiedNameContext q) {
        return q != null ? q.getText() : null;
    }

    private static String idText(MySQLParser.IdentifierContext id) {
        return id != null ? id.getText() : null;
    }

    private static String normalizeName(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty()) return null;
        t = t.replace("`", "").replace("\"", "").replace("'", "");
        return t;
    }

    private static String[] splitQname(MySQLParser.QualifiedNameContext q) {
        if (q == null) return new String[]{null, null};
        List<MySQLParser.IdentifierContext> ids = q.identifier();
        if (ids == null || ids.isEmpty()) return new String[]{null, null};
        String obj = normalizeName(ids.getLast().getText());
        String schema = (ids.size() > 1) ? normalizeName(ids.getFirst().getText()) : null;
        return new String[]{schema, obj};
    }

    private static String nameOnly(String qn) {
        if (qn == null) return null;
        int dot = qn.lastIndexOf('.');
        return (dot >= 0) ? qn.substring(dot + 1) : qn;
    }

    /* ===================== Metadata qua Visitor ===================== */

    private MetadataDto analyzeByNode(MySQLParser.SqlStatementContext st) {
        return new AstVisitor().visit(st);
    }

    private static class AstVisitor extends MySQLBaseVisitor<MetadataDto> {

        /* -------- CREATE SCHEMA / DATABASE / USE / DROP -------- */

        @Override
        public MetadataDto visitStCreateSchema(MySQLParser.StCreateSchemaContext x) {
            String[] so = splitQname(x.createSchemaStatement().qualifiedName());
            return MetadataDto.builder()
                    .statementType("CREATE_SCHEMA")
                    .schemaName(so[0])
                    .objectName(so[1])
                    .tables(Collections.emptyList())
                    .columns(Collections.emptyList())
                    .build();
        }

        @Override
        public MetadataDto visitStCreateDatabase(MySQLParser.StCreateDatabaseContext x) {
            MySQLParser.CreateDatabaseStatementContext ctx = x.createDatabaseStatement();
            String db = normalizeName(idText(ctx.identifier()));
            return MetadataDto.builder()
                    .statementType("CREATE_DATABASE")
                    .schemaName(null)
                    .objectName(db)
                    .tables(Collections.emptyList())
                    .columns(Collections.emptyList())
                    .build();
        }

        @Override
        public MetadataDto visitStUse(MySQLParser.StUseContext x) {
            String[] so = splitQname(x.useStatement().qualifiedName());
            return MetadataDto.builder()
                    .statementType("USE")
                    .schemaName(so[0])
                    .objectName(so[1])
                    .tables(Collections.emptyList())
                    .columns(Collections.emptyList())
                    .build();
        }

        @Override
        public MetadataDto visitStDrop(MySQLParser.StDropContext x) {
            return MetadataDto.minimal("DROP");
        }

        /* ---------------------- CREATE TABLE ---------------------- */

        @Override
        public MetadataDto visitStCreateTable(MySQLParser.StCreateTableContext x) {
            MySQLParser.CreateTableStatementContext ctx = x.createTableStatement();
            String qn = normalizeName(qnameText(ctx.tableName));
            String[] so = splitQname(ctx.tableName);
            String schemaName = so[0], objectName = so[1];

            TableScanner scanner = new TableScanner();
            scanner.visit(ctx);

            return MetadataDto.builder()
                    .statementType("CREATE_TABLE")
                    .schemaName(schemaName)
                    .objectName(objectName != null ? objectName : nameOnly(qn))
                    .tables(qn == null ? Collections.emptyList() : Collections.singletonList(qn))
                    .columns(scanner.columns())
                    .build();
        }

        private static class TableScanner extends MySQLBaseVisitor<Void> {
            private final List<String> columns = new ArrayList<>();
            List<String> columns() { return columns; }

            @Override
            public Void visitColumnDef(MySQLParser.ColumnDefContext ctx) {
                String col = ctx.columnName != null ? normalizeName(ctx.columnName.getText()) : null;
                String dt  = (ctx.dataType() != null) ? ctx.dataType().getText() : null;
                if (col != null) {
                    if (dt != null && !dt.isBlank()) {
                        columns.add(col + " " + dt);
                    } else {
                        columns.add(col);
                    }
                }
                return null;
            }
        }

        /* ---------------------- CREATE INDEX ---------------------- */

        @Override
        public MetadataDto visitStCreateIndex(MySQLParser.StCreateIndexContext x) {
            MySQLParser.CreateIndexStatementContext ctx = x.createIndexStatement();
            String idx = normalizeName(qnameText(ctx.indexName));
            String tbl = normalizeName(qnameText(ctx.tableName));

            List<String> cols = new ArrayList<>();
            for (MySQLParser.IndexElemContext e : ctx.indexElem()) {
                cols.add(e.getText());
            }

            return MetadataDto.builder()
                    .statementType("CREATE_INDEX")
                    .schemaName(null)
                    .objectName(idx != null ? nameOnly(idx) : null)
                    .tables(tbl == null ? Collections.emptyList() : Collections.singletonList(tbl))
                    .columns(cols)
                    .build();
        }

        /* ---------------------- CREATE VIEW ---------------------- */

        @Override
        public MetadataDto visitStCreateView(MySQLParser.StCreateViewContext x) {
            MySQLParser.CreateViewStatementContext ctx = x.createViewStatement();
            String v = normalizeName(qnameText(ctx.viewName));
            // selectStmt của MySQL.g4 là tolerant (SELECT junk*), khó trích sâu => để rỗng
            return MetadataDto.builder()
                    .statementType("CREATE_VIEW")
                    .schemaName(null)
                    .objectName(v != null ? nameOnly(v) : null)
                    .tables(Collections.emptyList())
                    .columns(Collections.emptyList())
                    .build();
        }

        /* ---------------------- CREATE TRIGGER ---------------------- */

        @Override
        public MetadataDto visitStCreateTrigger(MySQLParser.StCreateTriggerContext x) {
            MySQLParser.CreateTriggerStatementContext ctx = x.createTriggerStatement();
            String trg = normalizeName(qnameText(ctx.trigName));
            String onTbl = normalizeName(qnameText(ctx.tableName));
            return MetadataDto.builder()
                    .statementType("CREATE_TRIGGER")
                    .schemaName(null)
                    .objectName(trg != null ? nameOnly(trg) : null)
                    .tables(onTbl == null ? Collections.emptyList() : Collections.singletonList(onTbl))
                    .columns(Collections.emptyList())
                    .build();
        }

        /* ---------------------- CREATE EVENT ---------------------- */

        @Override
        public MetadataDto visitStCreateEvent(MySQLParser.StCreateEventContext x) {
            MySQLParser.CreateEventStatementContext ctx = x.createEventStatement();
            String ev = normalizeName(qnameText(ctx.eventName));
            return MetadataDto.builder()
                    .statementType("CREATE_EVENT")
                    .schemaName(null)
                    .objectName(ev != null ? nameOnly(ev) : null)
                    .tables(Collections.emptyList())
                    .columns(Collections.emptyList())
                    .build();
        }

        /* ---------------------- FUNCTION / PROCEDURE ---------------------- */

        @Override
        public MetadataDto visitStCreateFunction(MySQLParser.StCreateFunctionContext x) {
            MySQLParser.CreateFunctionStatementContext ctx = x.createFunctionStatement();
            String fn = normalizeName(qnameText(ctx.funcName));
            return MetadataDto.builder()
                    .statementType("CREATE_FUNCTION")
                    .schemaName(null)
                    .objectName(fn != null ? nameOnly(fn) : null)
                    .tables(Collections.emptyList())
                    .columns(Collections.emptyList())
                    .build();
        }

        @Override
        public MetadataDto visitStCreateProcedure(MySQLParser.StCreateProcedureContext x) {
            MySQLParser.CreateProcedureStatementContext ctx = x.createProcedureStatement();
            String pn = normalizeName(qnameText(ctx.procName));
            return MetadataDto.builder()
                    .statementType("CREATE_PROCEDURE")
                    .schemaName(null)
                    .objectName(pn != null ? nameOnly(pn) : null)
                    .tables(Collections.emptyList())
                    .columns(Collections.emptyList())
                    .build();
        }

        /* -------- BASIC DML -------- */
        @Override public MetadataDto visitStSelect(MySQLParser.StSelectContext x)  { return MetadataDto.minimal("SELECT"); }
        @Override public MetadataDto visitStInsert(MySQLParser.StInsertContext x)  { return MetadataDto.minimal("INSERT"); }
        @Override public MetadataDto visitStUpdate(MySQLParser.StUpdateContext x)  { return MetadataDto.minimal("UPDATE"); }
        @Override public MetadataDto visitStDelete(MySQLParser.StDeleteContext x)  { return MetadataDto.minimal("DELETE"); }
        @Override public MetadataDto visitStSet(MySQLParser.StSetContext x)        { return MetadataDto.minimal("SET"); }
        @Override public MetadataDto visitStGrant(MySQLParser.StGrantContext x)    { return MetadataDto.minimal("GRANT"); }
        @Override public MetadataDto visitStRevoke(MySQLParser.StRevokeContext x)  { return MetadataDto.minimal("REVOKE"); }
        @Override public MetadataDto visitStCreateRole(MySQLParser.StCreateRoleContext x){ return MetadataDto.minimal("CREATE_ROLE"); }
        @Override public MetadataDto visitStDropRole(MySQLParser.StDropRoleContext x)    { return MetadataDto.minimal("DROP_ROLE"); }
        @Override public MetadataDto visitStCreateUser(MySQLParser.StCreateUserContext x){ return MetadataDto.minimal("CREATE_USER"); }
        @Override public MetadataDto visitStDropUser(MySQLParser.StDropUserContext x)    { return MetadataDto.minimal("DROP_USER"); }
        @Override public MetadataDto visitStCall(MySQLParser.StCallContext x)            { return MetadataDto.minimal("CALL"); }

        /* -------- OTHERS -------- */
        @Override public MetadataDto visitStUnknown(MySQLParser.StUnknownContext x)      { return MetadataDto.minimal("RAW_STATEMENT"); }
    }

    private static class SilentErrorListener extends BaseErrorListener {
        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                int line, int charPositionInLine, String msg, RecognitionException e) {
            throw new RuntimeException("ANTLR 4 (MySQL) lỗi dòng " + line + ": " + msg);
        }
    }
}