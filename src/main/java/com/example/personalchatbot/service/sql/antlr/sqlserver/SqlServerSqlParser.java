package com.example.personalchatbot.service.sql.antlr.sqlserver;

import com.example.personalchatbot.service.sql.antlr.implement.AntlrSqlParserImpl;
import com.example.personalchatbot.service.sql.antlr.sqlserver.util.SqlBatchSegment;
import com.example.personalchatbot.service.sql.dto.MetadataDto;
import com.example.personalchatbot.service.sql.dto.SqlChunkDto;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;
import org.springframework.stereotype.Component;
import com.example.personalchatbot.service.sql.antlr.sqlserver.SqlServerParser;
import com.example.personalchatbot.service.sql.antlr.sqlserver.SqlServerLexer;
import com.example.personalchatbot.service.sql.antlr.sqlserver.SqlServerBaseVisitor;

import java.util.*;

@Slf4j
@Component
public class SqlServerSqlParser implements AntlrSqlParserImpl {
    @Override
    public String dialect() {
        return "sqlserver";
    }

    /* --------------------- Split --------------------- */
    @Override
    public List<SqlChunkDto> split(@NonNull String script) {
        SqlBatchSegment seg = new SqlBatchSegment();
        List<SqlBatchSegment.Batch> batches = seg.segment(script);

        List<SqlChunkDto> chunks = new ArrayList<>();
        int globalIndex = 0;

        for (var b : batches) {
            String batchText = b.text == null ? "" : b.text.trim();
            if (batchText.isBlank()) continue;

            List<StmtSegment> stmts = extractStatementsFromBatch(batchText);
            int total = stmts.size();
            if (total == 0) continue;

            int part = 1;
            for (StmtSegment s : stmts) {
                chunks.add(SqlChunkDto.builder()
                        .index(globalIndex++)
                        .part(part++)
                        .totalParts(total)
                        .dialect(dialect())
                        .content(s.text)
                        .build());
            }
        }
        return chunks;
    }

    @Override
    public MetadataDto analyze(@NonNull String singleStatement) {
        SqlServerParser parser = newParser(singleStatement);
        SqlServerParser.ScriptContext root = parser.script();

        SqlServerParser.SqlStatementContext st = findFirst(root, SqlServerParser.SqlStatementContext.class);
        MetadataDto md = analyzeByNode(st);
        return md != null ? md : MetadataDto.minimal("RAW_STATEMENT");
    }

    /* ===================== ANTLR bootstrap ===================== */

    private SqlServerParser newParser(String text) {
        CharStream cs = CharStreams.fromString(text);
        SqlServerLexer lexer = new SqlServerLexer(cs);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        SqlServerParser parser = new SqlServerParser(tokens);
        lexer.removeErrorListeners();
        parser.removeErrorListeners();
        SilentErrorListener err = new SilentErrorListener();
        lexer.addErrorListener(err);
        parser.addErrorListener(err);
        parser.setErrorHandler(new DefaultErrorStrategy());

        return parser;
    }

    private static String text(ParseTree t) { return t == null ? null : t.getText(); }

    /* ===================== Metadata ===================== */

    private MetadataDto analyzeByNode(SqlServerParser.SqlStatementContext ctx) {
        if (ctx == null) return MetadataDto.minimal("RAW_STATEMENT");

        // CREATE PROCEDURE
        SqlServerParser.CreateProcedureStatementContext cProc =
                getFirst(ctx, SqlServerParser.CreateProcedureStatementContext.class);
        if (cProc != null) {
            var nameCtx = getProcNameCtx(cProc);
            return MetadataDto.builder()
                    .statementType("CREATE_PROCEDURE")
                    .schemaName(schemaOf(nameCtx))
                    .objectName(objectOf(nameCtx))
                    .build();
        }

        // CREATE FUNCTION
        SqlServerParser.CreateFunctionStatementContext cFunc =
                getFirst(ctx, SqlServerParser.CreateFunctionStatementContext.class);
        if (cFunc != null) {
            var nameCtx = getFuncNameCtx(cFunc);
            return MetadataDto.builder()
                    .statementType("CREATE_FUNCTION")
                    .schemaName(schemaOf(nameCtx))
                    .objectName(objectOf(nameCtx))
                    .build();
        }

        // CREATE VIEW
        SqlServerParser.CreateViewStatementContext cView =
                getFirst(ctx, SqlServerParser.CreateViewStatementContext.class);
        if (cView != null) {
            var nameCtx = getViewNameCtx(cView);
            return MetadataDto.builder()
                    .statementType("CREATE_VIEW")
                    .schemaName(schemaOf(nameCtx))
                    .objectName(objectOf(nameCtx))
                    .build();
        }

        // CREATE TRIGGER
        SqlServerParser.CreateTriggerStatementContext cTrig =
                getFirst(ctx, SqlServerParser.CreateTriggerStatementContext.class);
        if (cTrig != null) {
            var trg = getTriggerNameCtx(cTrig);
            return MetadataDto.builder()
                    .statementType("CREATE_TRIGGER")
                    .schemaName(schemaOf(trg))
                    .objectName(objectOf(trg))
                    .build();
        }

        // CREATE TABLE
        SqlServerParser.CreateTableStatementContext ct = getFirst(ctx, SqlServerParser.CreateTableStatementContext.class);
        if (ct != null) {
            var qn = ct.schemaQualifiedName();
            return MetadataDto.builder()
                    .statementType("CREATE_TABLE")
                    .schemaName(schemaOf(qn))
                    .objectName(objectOf(qn))
                    .build();
        }

        // CREATE INDEX
        SqlServerParser.CreateIndexStatementContext createIndexSt = getFirst(ctx, SqlServerParser.CreateIndexStatementContext.class);
        if (createIndexSt != null) {
            var tbl = createIndexSt.schemaQualifiedName();
            String idxName = stripQuotes(text(createIndexSt.identifier(0)));
            return MetadataDto.builder()
                    .statementType("CREATE_INDEX")
                    .schemaName(schemaOf(tbl))
                    .objectName(idxName)
                    .tables(Collections.singletonList(qNameText(tbl)))
                    .build();
        }

        // ALTER INDEX
        SqlServerParser.AlterIndexStatementContext alterIndexSt = getFirst(ctx, SqlServerParser.AlterIndexStatementContext.class);
        if (alterIndexSt != null) {
            String idxName = (alterIndexSt.ALL() != null) ? "ALL" : stripQuotes(text(alterIndexSt.identifier()));
            var tbl = alterIndexSt.schemaQualifiedName();
            return MetadataDto.builder()
                    .statementType("ALTER_INDEX")
                    .objectName(idxName)
                    .tables(Collections.singletonList(qNameText(tbl)))
                    .build();
        }

        // DROP INDEX
        SqlServerParser.DropIndexStatementContext dropIndexSt = getFirst(ctx, SqlServerParser.DropIndexStatementContext.class);
        if (dropIndexSt != null) {
            String idxName = stripQuotes(dropIndexSt.idx.getText());
            var tbl = dropIndexSt.schemaQualifiedName();
            return MetadataDto.builder()
                    .statementType("DROP_INDEX")
                    .objectName(idxName)
                    .tables(Collections.singletonList(qNameText(tbl)))
                    .build();
        }

        // CREATE SEQUENCE
        SqlServerParser.CreateSequenceStatementContext createSequenceSt = getFirst(ctx, SqlServerParser.CreateSequenceStatementContext.class);
        if (createSequenceSt != null) {
            var qn = createSequenceSt.schemaQualifiedName();
            return MetadataDto.builder()
                    .statementType("CREATE_SEQUENCE")
                    .schemaName(schemaOf(qn))
                    .objectName(objectOf(qn))
                    .build();
        }

        // CREATE TYPE
        SqlServerParser.CreateTypeStatementContext createTypeSt = getFirst(ctx, SqlServerParser.CreateTypeStatementContext.class);
        if (createTypeSt != null) {
            var qn = createTypeSt.schemaQualifiedName();
            boolean isTableType = createTypeSt.AS() != null && createTypeSt.TABLE() != null;
            return MetadataDto.builder()
                    .statementType(isTableType ? "CREATE_TYPE_TABLE" : "CREATE_TYPE")
                    .schemaName(schemaOf(qn))
                    .objectName(objectOf(qn))
                    .build();
        }

        // CREATE SCHEMA
        SqlServerParser.CreateSchemaStatementContext createSchemaSt = getFirst(ctx, SqlServerParser.CreateSchemaStatementContext.class);
        if (createSchemaSt != null) {
            String sch = stripQuotes(text(createSchemaSt.schemaName()));
            return MetadataDto.builder()
                    .statementType("CREATE_SCHEMA")
                    .schemaName(sch).objectName(sch).build();
        }

        // CREATE DATABASE
        SqlServerParser.CreateDatabaseStatementContext cdb = getFirst(ctx, SqlServerParser.CreateDatabaseStatementContext.class);
        if (cdb != null) {
            String db = stripQuotes(cdb.dbName.getText());
            return MetadataDto.builder().statementType("CREATE_DATABASE").objectName(db).build();
        }

        // ALTER DATABASE
        SqlServerParser.AlterDatabaseStatementContext adb = getFirst(ctx, SqlServerParser.AlterDatabaseStatementContext.class);
        if (adb != null) {
            String db = stripQuotes(adb.dbName.getText());
            return MetadataDto.builder().statementType("ALTER_DATABASE").objectName(db).build();
        }

        // DROP PROCEDURE
        SqlServerParser.DropProcedureStatementContext dropProcedureSt = getFirst(ctx, SqlServerParser.DropProcedureStatementContext.class);
        if (dropProcedureSt != null) {
            var first = dropProcedureSt.schemaQualifiedName(0);
            return MetadataDto.builder()
                    .statementType("DROP_PROCEDURE")
                    .schemaName(schemaOf(first))
                    .objectName(objectOf(first))
                    .build();
        }

        // DROP DATABASE | DROP <OBJECT> <qName>
        SqlServerParser.DropStatementContext dropSt = getFirst(ctx, SqlServerParser.DropStatementContext.class);
        if (dropSt != null) {
            String tok1 = stripQuotes(text(dropSt.getChild(1)));
            if ("DATABASE".equalsIgnoreCase(tok1)) {
                int namePos = "IF".equalsIgnoreCase(stripQuotes(text(dropSt.getChild(2)))) ? 4 : 2;
                String firstDb = stripQuotes(text(dropSt.getChild(namePos)));
                return MetadataDto.builder().statementType("DROP_DATABASE").objectName(firstDb).build();
            } else {
                SqlServerParser.SchemaQualifiedNameContext first = dropSt.schemaQualifiedName(0);
                return MetadataDto.builder()
                        .statementType("DROP")
                        .objectName(qNameText(first))
                        .build();
            }
        }

        // ALTER TABLE
        SqlServerParser.AlterTableStatementContext at = getFirst(ctx, SqlServerParser.AlterTableStatementContext.class);
        if (at != null) {
            var qn = at.schemaQualifiedName();
            return MetadataDto.builder()
                    .statementType("ALTER_TABLE")
                    .schemaName(schemaOf(qn))
                    .objectName(objectOf(qn))
                    .build();
        }

        // USE
        SqlServerParser.UseStatementContext use = getFirst(ctx, SqlServerParser.UseStatementContext.class);
        if (use != null) {
            String db = stripQuotes(text(use.getChild(1)));
            return MetadataDto.builder().statementType("USE").objectName(db).build();
        }

        // CREATE/DROP XML SCHEMA COLLECTION
        SqlServerParser.CreateXmlSchemaCollectionStatementContext cXml =
                getFirst(ctx, SqlServerParser.CreateXmlSchemaCollectionStatementContext.class);
        if (cXml != null) {
            var qn = cXml.schemaQualifiedName();
            return MetadataDto.builder()
                    .statementType("CREATE_XML_SCHEMA_COLLECTION")
                    .schemaName(schemaOf(qn))
                    .objectName(objectOf(qn))
                    .build();
        }

        SqlServerParser.DropXmlSchemaCollectionStatementContext dXml =
                getFirst(ctx, SqlServerParser.DropXmlSchemaCollectionStatementContext.class);
        if (dXml != null) {
            var qn = dXml.schemaQualifiedName();
            return MetadataDto.builder()
                    .statementType("DROP_XML_SCHEMA_COLLECTION")
                    .schemaName(schemaOf(qn))
                    .objectName(objectOf(qn))
                    .build();
        }

        // ROLE/USER
        SqlServerParser.CreateRoleStatementContext cRole = getFirst(ctx, SqlServerParser.CreateRoleStatementContext.class);
        if (cRole != null) {
            var ids = cRole.identifier();
            String name = (ids == null || ids.isEmpty()) ? null : stripQuotes(ids.getFirst().getText());
            return MetadataDto.builder().statementType("CREATE_ROLE").objectName(name).build();
        }

        SqlServerParser.DropRoleStatementContext dRole = getFirst(ctx, SqlServerParser.DropRoleStatementContext.class);
        if (dRole != null) {
            String name = stripQuotes(dRole.identifier().getText());
            return MetadataDto.builder().statementType("DROP_ROLE").objectName(name).build();
        }

        SqlServerParser.CreateUserStatementContext cUser = getFirst(ctx, SqlServerParser.CreateUserStatementContext.class);
        if (cUser != null) {
            String name = stripQuotes(cUser.identifier().getText());
            return MetadataDto.builder().statementType("CREATE_USER").objectName(name).build();
        }

        SqlServerParser.DropUserStatementContext dUser = getFirst(ctx, SqlServerParser.DropUserStatementContext.class);
        if (dUser != null) {
            String name = stripQuotes(dUser.identifier().getText());
            return MetadataDto.builder().statementType("DROP_USER").objectName(name).build();
        }

        // PARTITION FUNCTION/SCHEME
        SqlServerParser.DropPartitionFunctionStatementContext dpf = getFirst(ctx, SqlServerParser.DropPartitionFunctionStatementContext.class);
        if (dpf != null) {
            String name = stripQuotes(dpf.identifier().getText());
            return MetadataDto.builder().statementType("DROP_PARTITION_FUNCTION").objectName(name).build();
        }

        SqlServerParser.DropPartitionSchemeStatementContext dps = getFirst(ctx, SqlServerParser.DropPartitionSchemeStatementContext.class);
        if (dps != null) {
            String name = stripQuotes(dps.identifier().getText());
            return MetadataDto.builder().statementType("DROP_PARTITION_SCHEME").objectName(name).build();
        }

        SqlServerParser.CreatePartitionFunctionStatementContext cpf = getFirst(ctx, SqlServerParser.CreatePartitionFunctionStatementContext.class);
        if (cpf != null) {
            String name = stripQuotes(cpf.identifier().getText());
            return MetadataDto.builder().statementType("CREATE_PARTITION_FUNCTION").objectName(name).build();
        }

        SqlServerParser.CreatePartitionSchemeStatementContext cps = getFirst(ctx, SqlServerParser.CreatePartitionSchemeStatementContext.class);
        if (cps != null) {
            String name = stripQuotes(cps.identifier(0).getText());
            return MetadataDto.builder().statementType("CREATE_PARTITION_SCHEME").objectName(name).build();
        }

        // DROP DDL TRIGGER ON DATABASE
        SqlServerParser.DropDdlTriggerStatementContext ddt = getFirst(ctx, SqlServerParser.DropDdlTriggerStatementContext.class);
        if (ddt != null) {
            String name = qNameText(ddt.schemaQualifiedName());
            return MetadataDto.builder()
                    .statementType("DROP_TRIGGER")
                    .objectName(name)
                    .tables(Collections.singletonList("DATABASE"))
                    .build();
        }

        // DECLARE / GRANT (tối thiểu)
        if (has(ctx, SqlServerParser.DeclareStatementContext.class)) return MetadataDto.builder().statementType("DECLARE").build();

        SqlServerParser.GrantStatementContext gs = getFirst(ctx, SqlServerParser.GrantStatementContext.class);
        if (gs != null) {
            String obj = null;
            if (gs.grantTarget().schemaQualifiedName() != null) {
                obj = qNameText(gs.grantTarget().schemaQualifiedName());
            }
            return MetadataDto.builder()
                    .statementType("GRANT")
                    .objectName(obj)
                    .build();
        }

        // OTHER STATEMENT
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

    /* ===================== GT NAME BASE ON RULES ===================== */
    private static SqlServerParser.SchemaQualifiedNameContext getViewNameCtx(SqlServerParser.CreateViewStatementContext c) {
        if (c instanceof SqlServerParser.CreateViewContext x) return x.schemaQualifiedName();
        return null;
    }

    private static SqlServerParser.SchemaQualifiedNameContext getProcNameCtx(SqlServerParser.CreateProcedureStatementContext c) {
        if (c instanceof SqlServerParser.CreateProcWithGoContext x) return x.schemaQualifiedName();
        if (c instanceof SqlServerParser.CreateProcNoGoContext   x) return x.schemaQualifiedName();
        return null;
    }

    private static SqlServerParser.SchemaQualifiedNameContext getFuncNameCtx(SqlServerParser.CreateFunctionStatementContext c) {
        if (c instanceof SqlServerParser.CreateFuncScalarWithGoContext x) return x.schemaQualifiedName();
        if (c instanceof SqlServerParser.CreateFuncScalarNoGoContext   x) return x.schemaQualifiedName();
        if (c instanceof SqlServerParser.CreateFuncInlineWithGoContext x) return x.schemaQualifiedName();
        if (c instanceof SqlServerParser.CreateFuncInlineNoGoContext   x) return x.schemaQualifiedName();
        return null;
    }

    private static SqlServerParser.SchemaQualifiedNameContext getTriggerNameCtx(SqlServerParser.CreateTriggerStatementContext c) {
        if (c instanceof SqlServerParser.CreateDmlTriggerWithGoContext x) return x.schemaQualifiedName(0);
        if (c instanceof SqlServerParser.CreateDmlTriggerNoGoContext   x) return x.schemaQualifiedName(0);
        if (c instanceof SqlServerParser.CreateDdlTriggerWithGoContext x) return x.schemaQualifiedName();
        if (c instanceof SqlServerParser.CreateDdlTriggerNoGoContext   x) return x.schemaQualifiedName();
        return null;
    }

    /* ===================== QName helpers ===================== */

    private static String qNameText(SqlServerParser.SchemaQualifiedNameContext qn) {
        if (qn == null) return null;
        String schema = schemaOf(qn);
        String obj = objectOf(qn);
        if (schema != null && !schema.isBlank()) return schema + "." + obj;
        return obj;
    }

    private static String objectOf(SqlServerParser.SchemaQualifiedNameContext qn) {
        if (qn == null) return null;
        return stripQuotes(qn.objectName().getText());
    }

    private static String schemaOf(SqlServerParser.SchemaQualifiedNameContext qn) {
        if (qn == null || qn.schemaName() == null) return null;
        return stripQuotes(qn.schemaName().getText());
    }

    private static String stripQuotes(String s) {
        if (s == null) return null;
        String x = s;
        if (x.length() >= 2 && x.startsWith("[") && x.endsWith("]")) x = x.substring(1, x.length() - 1);
        if (x.length() >= 2 && x.startsWith("\"") && x.endsWith("\"")) x = x.substring(1, x.length() - 1);
        return x;
    }

    /* ===================== Safe helpers (null-guard) ===================== */

    private static <T extends ParserRuleContext> T getFirst(ParserRuleContext ctx, Class<T> klass) {
        if (ctx == null) return null;
        List<T> ls = ctx.getRuleContexts(klass);
        return ls.isEmpty() ? null : ls.getFirst();
    }

    private static <T extends ParserRuleContext> boolean has(ParserRuleContext ctx, Class<T> klass) {
        if (ctx == null) return false;
        return !ctx.getRuleContexts(klass).isEmpty();
    }

    private static <T extends ParserRuleContext> T findFirst(ParserRuleContext ctx, Class<T> klass) {
        if (ctx == null) return null;
        if (klass.isInstance(ctx)) return klass.cast(ctx);
        for (int i = 0; i < ctx.getChildCount(); i++) {
            ParseTree ch = ctx.getChild(i);
            if (ch instanceof ParserRuleContext prc) {
                T r = findFirst(prc, klass);
                if (r != null) return r;
            }
        }
        return null;
    }

    /* ===================== Batch helpers ===================== */

    private static final class StmtSegment {
        String text;
        int startLine;
        int endLine;
    }

    //Tách từng câu statement từ 1 batch GO
    private List<StmtSegment> extractStatementsFromBatch(String batchText) {
        CharStream cs = CharStreams.fromString(batchText);
        SqlServerLexer lexer = new SqlServerLexer(cs);
        CommonTokenStream ts = new CommonTokenStream(lexer);
        SqlServerParser parser = new SqlServerParser(ts);
        lexer.removeErrorListeners();
        parser.removeErrorListeners();
        parser.addErrorListener(new SilentErrorListener());
        parser.setErrorHandler(new DefaultErrorStrategy());

        SqlServerParser.ScriptContext tree = parser.script();

        List<StmtSegment> out = new ArrayList<>();
        for (int i = 0; i < tree.getChildCount(); i++) {
            ParseTree ch = tree.getChild(i);
            if (ch instanceof SqlServerParser.SqlStatementContext sqlStatement) {
                Token start = sqlStatement.getStart();
                Token stop  = sqlStatement.getStop();

                int from = Math.max(0, start.getStartIndex());
                int to   = Math.max(from - 1, stop.getStopIndex());

                String text = batchText.substring(from, Math.min(batchText.length(), to + 1)).trim();
                if (!text.isEmpty()) {
                    StmtSegment seg = new StmtSegment();
                    seg.text = text;
                    seg.startLine = start.getLine();
                    seg.endLine = stop.getLine();
                    out.add(seg);
                }
            }
        }
        return out;
    }

    public static class SilentErrorListener extends BaseErrorListener {
        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                int line, int charPositionInLine, String msg, RecognitionException e) {
            throw new RuntimeException("ANTLR 4 (SQLServer) lỗi dòng " + line + ": " + msg);
        }
    }
}
