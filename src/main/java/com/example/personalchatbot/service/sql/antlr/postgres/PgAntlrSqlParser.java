package com.example.personalchatbot.service.sql.antlr.postgres;

import com.example.personalchatbot.service.sql.dto.MetadataDto;
import org.antlr.v4.runtime.*;
import com.example.personalchatbot.service.sql.antlr.implement.AntlrSqlParserImpl;
import com.example.personalchatbot.service.sql.antlr.postgres.PostgreSQLLexer;
import com.example.personalchatbot.service.sql.antlr.postgres.PostgreSQLParser;
import com.example.personalchatbot.service.sql.antlr.postgres.PostgreSQLBaseVisitor;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class PgAntlrSqlParser implements AntlrSqlParserImpl {
    // ---------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------

    @Override
    public List<String> splitStatements(String sql) {
        if (sql == null || sql.isBlank()) return List.of();

        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();

        boolean inSingle = false;      // '...'
        boolean inDollar = false;      // $$...$$
        boolean inLineCmt = false;     // -- ...
        boolean inBlockCmt = false;    // /* ... */

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);

            // đang trong comment dòng
            if (inLineCmt) {
                cur.append(c);
                if (c == '\n' || c == '\r') inLineCmt = false;
                continue;
            }
            // đang trong comment block
            if (inBlockCmt) {
                cur.append(c);
                if (c == '*' && i + 1 < sql.length() && sql.charAt(i + 1) == '/') {
                    cur.append('/');
                    i++;
                    inBlockCmt = false;
                }
                continue;
            }
            // đang trong dollar-quoted body
            if (inDollar) {
                cur.append(c);
                if (c == '$' && i + 1 < sql.length() && sql.charAt(i + 1) == '$') {
                    cur.append('$');
                    i++;
                    inDollar = false;
                }
                continue;
            }
            // đang trong single-quoted string
            if (inSingle) {
                cur.append(c);
                if (c == '\'') {
                    // Postgres escape '' -> bước qua ký tự thứ hai
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                        cur.append('\'');
                        i++;
                    } else {
                        inSingle = false;
                    }
                }
                continue;
            }

            // ngoài mọi cấu trúc đặc biệt
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                cur.append("--");
                i++;
                inLineCmt = true;
                continue;
            }
            if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                cur.append("/*");
                i++;
                inBlockCmt = true;
                continue;
            }
            if (c == '$' && i + 1 < sql.length() && sql.charAt(i + 1) == '$') {
                cur.append("$$");
                i++;
                inDollar = true;
                continue;
            }
            if (c == '\'') {
                cur.append(c);
                inSingle = true;
                continue;
            }
            if (c == ';') {
                String s = cur.toString().trim();
                if (!s.isEmpty()) out.add(s);
                cur.setLength(0);
                continue;
            }

            cur.append(c);
        }

        String last = cur.toString().trim();
        if (!last.isEmpty()) out.add(last);
        return out;
    }

    @Override
    public MetadataDto analyze(String statement) {
        String srcForParse = statement.toUpperCase(Locale.ROOT);

        CharStream cs = CharStreams.fromString(srcForParse);
        PostgreSQLLexer lexer = new PostgreSQLLexer(cs);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        PostgreSQLParser parser = new PostgreSQLParser(tokens);

        SyntaxErrorCounter err = new SyntaxErrorCounter();
        lexer.removeErrorListeners();
        parser.removeErrorListeners();
        lexer.addErrorListener(err);
        parser.addErrorListener(err);

        PostgreSQLParser.SqlStatementContext ctx;

        try {
            ctx = parser.sqlStatement();
        } catch (Exception e) {
            return null;
        }

        if (err.hasError()) {
            return null;
        }

        return ctx.accept(new MetaVisitor());
    }

    // ---------------------------------------------------------------------
    // Visitor: rút MetadataDto cho từng loại câu lệnh
    // ---------------------------------------------------------------------

    private static class MetaVisitor extends PostgreSQLBaseVisitor<MetadataDto> {

        // ---------- CREATE TABLE ----------
        @Override
        public MetadataDto visitCreateTableStatement(PostgreSQLParser.CreateTableStatementContext ctx) {
            String table = ctx.tableName.getText();

            List<String> cols = new ArrayList<>();
            if (ctx.tableElement() != null) {
                for (PostgreSQLParser.TableElementContext el : ctx.tableElement()) {
                    if (el.columnDefinition() != null && el.columnDefinition().columnName != null) {
                        cols.add(el.columnDefinition().columnName.getText());
                    }
                }
            }

            return MetadataDto.builder()
                    .statementType("CREATE_TABLE")
                    .schemaName(null)
                    .objectName(table)
                    .tables(List.of())
                    .columns(cols)
                    .build();
        }

        // ---------- CREATE INDEX ----------
        @Override
        public MetadataDto visitCreateIndexStatement(PostgreSQLParser.CreateIndexStatementContext ctx) {
            String indexName = ctx.indexName.getText();
            String onTable   = ctx.tableName.getText();

            List<String> cols = new ArrayList<>();
            if (ctx.indexElem() != null) {
                for (PostgreSQLParser.IndexElemContext e : ctx.indexElem()) {
                    if (e.identifier() != null) cols.add(e.identifier().getText());
                }
            }

            return MetadataDto.builder()
                    .statementType(ctx.UNIQUE() != null ? "CREATE_UNIQUE_INDEX" : "CREATE_INDEX")
                    .objectName(indexName)
                    .tables(List.of(onTable))
                    .columns(cols)
                    .build();
        }

        // ---------- CREATE FUNCTION ----------
        @Override
        public MetadataDto visitCreateFunctionStatement(PostgreSQLParser.CreateFunctionStatementContext ctx) {
            return MetadataDto.builder()
                    .statementType("CREATE_FUNCTION")
                    .objectName(ctx.funcName.getText())
                    .tables(Collections.emptyList())
                    .columns(Collections.emptyList())
                    .build();
        }

        // ---------- CREATE PROCEDURE ----------
        @Override
        public MetadataDto visitCreateProcedureStatement(PostgreSQLParser.CreateProcedureStatementContext ctx) {
            return MetadataDto.builder()
                    .statementType("CREATE_PROCEDURE")
                    .objectName(ctx.procName.getText())
                    .tables(Collections.emptyList())
                    .columns(Collections.emptyList())
                    .build();
        }

        // ---------- DO $$...$$ ----------
        @Override
        public MetadataDto visitDoStatement(PostgreSQLParser.DoStatementContext ctx) {
            return MetadataDto.builder()
                    .statementType("DO_BLOCK")
                    .tables(Collections.emptyList())
                    .columns(Collections.emptyList())
                    .build();
        }

        // ---------- CREATE TRIGGER ----------
        @Override
        public MetadataDto visitCreateTriggerStatement(PostgreSQLParser.CreateTriggerStatementContext ctx) {
            return MetadataDto.builder()
                    .statementType("CREATE_TRIGGER")
                    .objectName(ctx.trgName.getText())
                    .tables(List.of(ctx.onTable.getText()))
                    .columns(Collections.emptyList())
                    .build();
        }

        // ---------- CREATE TYPE ... AS ENUM ----------
        @Override
        public MetadataDto visitCreateTypeEnumStatement(PostgreSQLParser.CreateTypeEnumStatementContext ctx) {
            List<String> labels = new ArrayList<>();
            if (ctx.stringList() != null) {
                for (TerminalNode s : ctx.stringList().STRING()) {
                    labels.add(s.getText());
                }
            }
            return MetadataDto.builder()
                    .statementType("CREATE_TYPE_ENUM")
                    .objectName(ctx.typeName.getText())
                    .tables(Collections.emptyList())
                    .columns(labels) // lưu nhãn enum vào 'columns' cho tiện tra cứu
                    .build();
        }

        // ---------- CREATE EXTENSION ----------
        @Override
        public MetadataDto visitCreateExtensionStatement(PostgreSQLParser.CreateExtensionStatementContext ctx) {
            return MetadataDto.builder()
                    .statementType("CREATE_EXTENSION")
                    .objectName(ctx.extName.getText())
                    .tables(Collections.emptyList())
                    .columns(Collections.emptyList())
                    .build();
        }

        // ---------- CREATE DOMAIN ----------
        @Override
        public MetadataDto visitCreateDomainStatement(PostgreSQLParser.CreateDomainStatementContext ctx) {
            return MetadataDto.builder()
                    .statementType("CREATE_DOMAIN")
                    .objectName(ctx.domainName.getText())
                    .tables(Collections.emptyList())
                    .columns(Collections.emptyList())
                    .build();
        }

        // ---------- CREATE POLICY (RLS) ----------
        @Override
        public MetadataDto visitCreatePolicyStatement(PostgreSQLParser.CreatePolicyStatementContext ctx) {
            return MetadataDto.builder()
                    .statementType("CREATE_POLICY")
                    .objectName(ctx.polName.getText())
                    .tables(List.of(ctx.tbl.getText()))
                    .columns(Collections.emptyList())
                    .build();
        }

        // ---------- COMMENT ON ... ----------
        @Override
        public MetadataDto visitCommentOnStatement(PostgreSQLParser.CommentOnStatementContext ctx) {
            // Ví dụ: COLUMN schema.tbl.col  |  TABLE schema.tbl
            String fullName = ctx.commentName().getText();

            String schema = null, object = fullName;
            int dot = fullName.indexOf('.');
            if (dot > 0) {
                schema = fullName.substring(0, dot);
                object = fullName.substring(dot + 1);
            }

            return MetadataDto.builder()
                    .statementType("COMMENT_ON") // giữ thống nhất; nếu cần, nối target: "COMMENT_ON_" + ctx.commentTarget().getText()
                    .schemaName(schema)
                    .objectName(object)
                    .tables(Collections.emptyList())
                    .columns(Collections.emptyList())
                    .build();
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private Token findFollowingSemicolon(CommonTokenStream tokens, int fromTokenIndex) {
        List<Token> list = tokens.getTokens();
        for (int i = fromTokenIndex + 1; i < list.size(); i++) {
            Token t = list.get(i);
            if (";".equals(t.getText())) return t;
            if (t.getType() == Token.EOF) break;
        }
        return null;
    }

    private String safe(String s, int a, int b) {
        int x = Math.max(0, Math.min(a, s.length()));
        int y = Math.max(x, Math.min(b, s.length()));
        return s.substring(x, y);
    }

    private static final class SyntaxErrorCounter extends BaseErrorListener {
        private int count = 0;
        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                int line, int charPositionInLine, String msg, RecognitionException e) {
            count++;
        }
        boolean hasError() { return count > 0; }
    }
}
