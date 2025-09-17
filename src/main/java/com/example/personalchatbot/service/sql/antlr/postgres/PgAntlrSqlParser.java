package com.example.personalchatbot.service.sql.antlr.postgres;

import com.example.personalchatbot.service.sql.dto.MetadataDto;
import com.example.personalchatbot.service.sql.dto.SqlChunkDto;
import org.antlr.v4.runtime.*;
import com.example.personalchatbot.service.sql.antlr.implement.AntlrSqlParserImpl;
import org.springframework.stereotype.Component;
import com.example.personalchatbot.service.sql.antlr.postgres.PostgreSQLParser;
import com.example.personalchatbot.service.sql.antlr.postgres.PostgreSQLLexer;
import com.example.personalchatbot.service.sql.antlr.postgres.PostgreSQLBaseVisitor;

import java.util.*;

@Component
public class PgAntlrSqlParser implements AntlrSqlParserImpl {

    @Override
    public String dialect() {
        return "postgresql";
    }

    @Override
    public List<SqlChunkDto> split(String sql) {
        if (sql == null || sql.trim().isEmpty()) return List.of();

        CharStream input = CharStreams.fromString(sql);
        PostgreSQLLexer lexer = new PostgreSQLLexer(input);

        List<SqlChunkDto> out = new ArrayList<>();
        int partStart = 0;
        int idx = 0;

        while (true) {
            Token t = lexer.nextToken();
            if (t == null) break;

            if (t.getType() == Token.EOF) {
                if (t.getStartIndex() >= 0) {
                    String chunk = slice(sql, partStart, sql.length()).trim();
                    if (!chunk.isEmpty()) out.add(buildChunk(idx++, chunk));
                }
                break;
            }

            // cắt theo dấu ';' (không phụ thuộc tên token)
            if (";".equals(t.getText())) {
                String chunk = slice(sql, partStart, t.getStartIndex()).trim();
                if (!chunk.isEmpty()) out.add(buildChunk(idx++, chunk));
                partStart = t.getStopIndex() + 1;
            }
        }
        return out;
    }

    @Override
    public MetadataDto analyze(String statement) {
        if (statement == null || statement.isBlank()) {
            return MetadataDto.builder().statementType("RAW_STATEMENT").build();
        }

        CharStream input = CharStreams.fromString(statement);
        PostgreSQLLexer lexer = new PostgreSQLLexer(input);

        List<Token> tokens = new ArrayList<>();
        for (Token t = lexer.nextToken(); t.getType() != Token.EOF; t = lexer.nextToken()) {
            tokens.add(t);
        }
        List<String> words = new ArrayList<>(tokens.size());
        for (Token t : tokens) {
            words.add(t.getText().toUpperCase(Locale.ROOT));
        }

        int iCreate = indexOf(words, "CREATE");
        if (iCreate >= 0) {
            int iTable = indexOf(words, "TABLE", iCreate + 1);
            if (iTable >= 0) {
                QName np = readQualifiedName(tokens, iTable + 1);
                return MetadataDto.builder()
                        .statementType("CREATE_TABLE")
                        .schemaName(np.schema)
                        .objectName(np.object)
                        .build();
            }
            int iIndex = indexOf(words, "INDEX", iCreate + 1);
            if (iIndex >= 0) {
                QName idx = readQualifiedName(tokens, iIndex + 1);
                int iOn = indexOf(words, "ON", iIndex + 1 + idx.consumed);
                QName tbl = (iOn >= 0) ? readQualifiedName(tokens, iOn + 1) : new QName(null, null, 0);
                return MetadataDto.builder()
                        .statementType("CREATE_INDEX")
                        .schemaName(idx.schema)
                        .objectName(idx.object)
                        .tables(tbl.object == null ? List.of() : List.of(tbl.full()))
                        .build();
            }
            int iView = indexOf(words, "VIEW", iCreate + 1);
            if (iView >= 0) {
                QName np = readQualifiedName(tokens, iView + 1);
                return MetadataDto.builder()
                        .statementType("CREATE_VIEW")
                        .schemaName(np.schema)
                        .objectName(np.object)
                        .build();
            }
            int iFunc = indexOf(words, "FUNCTION", iCreate + 1);
            if (iFunc >= 0) {
                QName np = readQualifiedName(tokens, iFunc + 1);
                return MetadataDto.builder()
                        .statementType("CREATE_FUNCTION")
                        .schemaName(np.schema)
                        .objectName(np.object)
                        .build();
            }
            int iProc = indexOf(words, "PROCEDURE", iCreate + 1);
            if (iProc >= 0) {
                QName np = readQualifiedName(tokens, iProc + 1);
                return MetadataDto.builder()
                        .statementType("CREATE_PROCEDURE")
                        .schemaName(np.schema)
                        .objectName(np.object)
                        .build();
            }
            int iTrig = indexOf(words, "TRIGGER", iCreate + 1);
            if (iTrig >= 0) {
                QName np = readQualifiedName(tokens, iTrig + 1);
                return MetadataDto.builder()
                        .statementType("CREATE_TRIGGER")
                        .schemaName(np.schema)
                        .objectName(np.object)
                        .build();
            }
        }

        return MetadataDto.builder().statementType("RAW_STATEMENT").build();
    }

    /* ---------------------- helpers nhỏ ---------------------- */

    private static SqlChunkDto buildChunk(int idx, String content) {
        return SqlChunkDto.builder()
                .index(idx)
                .part(1)
                .totalParts(1)
                .dialect("postgresql")
                .kind(null)
                .content(content)
                .build();
    }

    private static String slice(String src, int from, int to) {
        int a = Math.max(0, from);
        int b = Math.max(a, Math.min(src.length(), to));
        return src.substring(a, b);
    }

    private static int indexOf(List<String> words, String key) {
        return indexOf(words, key, 0);
    }

    private static int indexOf(List<String> words, String key, int from) {
        for (int i = Math.max(0, from); i < words.size(); i++) {
            if (key.equals(words.get(i))) return i;
        }
        return -1;
    }

    private static QName readQualifiedName(List<Token> tokens, int from) {
        String schema = null, object = null;
        int i = from;

        // bỏ '(' (phòng lỗi)
        while (i < tokens.size() && "(".equals(tokens.get(i).getText())) i++;

        if (i >= tokens.size()) return new QName(null, null, 0);
        String t0 = tokens.get(i).getText();

        if (isIdentLike(t0)) {
            if (i + 1 < tokens.size() && ".".equals(tokens.get(i + 1).getText())) {
                schema = stripQuote(t0);
                if (i + 2 < tokens.size() && isIdentLike(tokens.get(i + 2).getText())) {
                    object = stripQuote(tokens.get(i + 2).getText());
                    i += 3;
                } else {
                    object = stripQuote(t0);
                    i += 1;
                }
            } else {
                object = stripQuote(t0);
                i += 1;
            }
        }

        return new QName(schema, object, Math.max(0, i - from));
    }

    private static boolean isIdentLike(String s) {
        if (s == null || s.isEmpty()) return false;
        char c0 = s.charAt(0);
        return Character.isLetterOrDigit(c0) || c0 == '_' || c0 == '"' || c0 == '\'';
    }

    private static String stripQuote(String s) {
        if (s == null || s.length() < 2) return s;
        char f = s.charAt(0), l = s.charAt(s.length() - 1);
        if ((f == '"' && l == '"') || (f == '\'' && l == '\'')) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private record QName(String schema, String object, int consumed) {

        String full() {
                if (schema == null || schema.isBlank()) return object;
                return (object == null) ? null : (schema + "." + object);
            }
        }
}
