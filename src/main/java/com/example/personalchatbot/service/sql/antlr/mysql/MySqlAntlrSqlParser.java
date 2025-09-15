package com.example.personalchatbot.service.sql.antlr.mysql;

import com.example.personalchatbot.service.sql.antlr.implement.AntlrSqlParserImpl;
import com.example.personalchatbot.service.sql.dto.MetadataDto;
import com.example.personalchatbot.service.sql.dto.SqlChunkDto;
import lombok.NonNull;
import org.antlr.v4.runtime.*;
import com.example.personalchatbot.service.sql.antlr.mysql.MySQLParser;
import com.example.personalchatbot.service.sql.antlr.mysql.MySQLLexer;
import com.example.personalchatbot.service.sql.antlr.mysql.MySQLBaseVisitor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class MySqlAntlrSqlParser implements AntlrSqlParserImpl {

    @Override
    public String dialect() {
        return "mysql";
    }

    /* --------------------- Split --------------------- */

    @Override
    public List<SqlChunkDto> split(@NonNull String sql) {
        // Không dùng regex. Quét tuyến tính, hỗ trợ DELIMITER động giống mysql client.
        final String src = sql;
        final int n = src.length();
        String delimiter = ";";       // mặc định
        int i = 0;
        int stmtBeg = 0;
        List<SqlChunkDto> out = new ArrayList<>();

        while (i < n) {
            // bỏ comment dòng & block đơn giản để không làm sai sót delimiter trong comment
            if (i + 1 < n && src.charAt(i) == '-' && src.charAt(i + 1) == '-') {
                int j = i + 2;
                while (j < n && src.charAt(j) != '\n' && src.charAt(j) != '\r') j++;
                i = j; // skip line comment
                continue;
            }
            if (i + 1 < n && src.charAt(i) == '/' && src.charAt(i + 1) == '*') {
                int j = i + 2;
                while (j + 1 < n && !(src.charAt(j) == '*' && src.charAt(j + 1) == '/')) j++;
                i = Math.min(n, j + 2);
                continue;
            }

            // Nếu bắt đầu dòng và là directive DELIMITER -> cập nhật delimiter và flush phần trước đó (nếu có)
            if ((i == 0 || src.charAt(i - 1) == '\n' || src.charAt(i - 1) == '\r')) {
                // đọc token đầu dòng (bỏ trắng)
                int j = i;
                while (j < n && Character.isWhitespace(src.charAt(j))) j++;
                int k = j;
                while (k < n && !Character.isWhitespace(src.charAt(k))) k++;
                String head = src.substring(j, k);
                if ("DELIMITER".equalsIgnoreCase(head)) {
                    // flush statement trước directive theo delimiter cũ (nếu có kí tự hữu ích)
                    String prev = src.substring(stmtBeg, j).trim();
                    if (!prev.isEmpty()) {
                        out.add(SqlChunkDto.builder()
                                .index(out.size()).part(1).totalParts(1)
                                .dialect(dialect()).content(prev).build());
                    }
                    // đọc giá trị delimiter mới tới hết dòng
                    int dBeg = k;
                    while (dBeg < n && Character.isWhitespace(src.charAt(dBeg))) dBeg++;
                    int dEnd = dBeg;
                    while (dEnd < n && src.charAt(dEnd) != '\n' && src.charAt(dEnd) != '\r') dEnd++;
                    delimiter = src.substring(dBeg, dEnd).trim();
                    if (delimiter.isEmpty()) delimiter = ";";
                    // bỏ hẳn directive (không tạo chunk), đặt begin cho statement tiếp theo
                    i = (dEnd < n && src.charAt(dEnd) == '\r' && dEnd + 1 < n && src.charAt(dEnd + 1) == '\n') ? dEnd + 2 : dEnd + 1;
                    stmtBeg = i;
                    continue;
                }
            }

            // kiểm tra kết thúc bởi delimiter hiện tại
            if (src.startsWith(delimiter, i)) {
                String part = src.substring(stmtBeg, i).trim();
                if (!part.isEmpty()) {
                    out.add(SqlChunkDto.builder()
                            .index(out.size()).part(1).totalParts(1)
                            .dialect(dialect()).content(part).build());
                }
                i += delimiter.length();
                stmtBeg = i;
                continue;
            }

            i++;
        }

        // tail
        String tail = src.substring(stmtBeg).trim();
        if (!tail.isEmpty()) {
            out.add(SqlChunkDto.builder()
                    .index(out.size()).part(1).totalParts(1)
                    .dialect(dialect()).content(tail).build());
        }
        return out;
    }

    /* --------------------- Analyze --------------------- */

    @Override
    public MetadataDto analyze(String statement) {
        if (statement == null || statement.trim().isEmpty()) {
            return MetadataDto.builder().statementType("RAW_STATEMENT").build();
        }

        CharStream input = CharStreams.fromString(statement);
        MySQLLexer lexer = new MySQLLexer(input);
        lexer.removeErrorListeners();
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        tokens.fill();

        List<Token> ts = tokens.getTokens();
        int i = nextNonDelim(ts, 0);

        if (i >= ts.size()) {
            return MetadataDto.builder().statementType("RAW_STATEMENT").build();
        }

        Token t0 = ts.get(i);
        if (t0.getType() == MySQLLexer.CREATE) {
            int j = nextNonDelim(ts, i + 1);
            if (j < ts.size()) {
                int type = ts.get(j).getType();

                // CREATE DATABASE/SCHEMA name
                if (type == MySQLLexer.DATABASE || type == MySQLLexer.SCHEMA) {
                    String name = readQualifiedName(ts, j + 1);
                    return MetadataDto.builder()
                            .statementType("SQLCreateDatabaseStatement")
                            .objectName(emptyToNull(name))
                            .build();
                }

                // CREATE TABLE name (...)
                if (type == MySQLLexer.TABLE) {
                    int k = skipIfNotExists(ts, j + 1);
                    String name = readQualifiedName(ts, k);
                    return MetadataDto.builder()
                            .statementType("MySqlCreateTableStatement")
                            .objectName(emptyToNull(nameOnly(name)))
                            .tables(name == null ? Collections.emptyList() :
                                    Collections.singletonList(name))
                            .build();
                }

                // CREATE INDEX idx ON tbl (...)
                if (type == MySQLLexer.INDEX || type == MySQLLexer.UNIQUE) {
                    int k = (type == MySQLLexer.UNIQUE) ? nextType(ts, j + 1, MySQLLexer.INDEX) : j;
                    String idxName = readQualifiedName(ts, k + 1);
                    int onPos = nextType(ts, k + 1, MySQLLexer.ON);
                    String tbl = readQualifiedName(ts, onPos + 1);
                    return MetadataDto.builder()
                            .statementType("SQLCreateIndexStatement")
                            .objectName(emptyToNull(nameOnly(idxName)))
                            .tables(tbl == null ? Collections.emptyList() :
                                    Collections.singletonList(tbl))
                            .build();
                }

                // CREATE VIEW name AS ...
                if (type == MySQLLexer.VIEW) {
                    String name = readQualifiedName(ts, j + 1);
                    return MetadataDto.builder()
                            .statementType("SQLCreateViewStatement")
                            .objectName(emptyToNull(nameOnly(name)))
                            .build();
                }

                // CREATE FUNCTION / PROCEDURE name (...)
                if (type == MySQLLexer.FUNCTION) {
                    String fn = readQualifiedName(ts, j + 1);
                    return MetadataDto.builder()
                            .statementType("CREATE_FUNCTION")
                            .objectName(emptyToNull(nameOnly(fn)))
                            .build();
                }
                if (type == MySQLLexer.PROCEDURE) {
                    String pn = readQualifiedName(ts, j + 1);
                    return MetadataDto.builder()
                            .statementType("CREATE_PROCEDURE")
                            .objectName(emptyToNull(nameOnly(pn)))
                            .build();
                }
            }
        }

        // fallback: chưa nhận dạng -> RAW
        return MetadataDto.builder().statementType("RAW_STATEMENT").build();
    }

    /* --------------------- Tiny utilities (local) --------------------- */

    private static int firstNonWsIndex(CommonTokenStream tokens, String src) {
        for (Token t : tokens.getTokens()) {
            if (t.getType() != Token.EOF && t.getType() != MySQLLexer.DELIM) {
                return Math.max(0, t.getStartIndex());
            }
        }
        // nếu không có token, trả 0
        return 0;
    }

    private static String slice(String s, int from, int to) {
        int a = Math.max(0, Math.min(from, s.length()));
        int b = Math.max(0, Math.min(to, s.length()));
        if (b <= a) return "";
        return s.substring(a, b).trim();
    }

    private static int nextNonDelim(List<Token> ts, int from) {
        int i = Math.max(0, from);
        while (i < ts.size()) {
            int tp = ts.get(i).getType();
            if (tp != MySQLLexer.DELIM && tp != Token.EOF) break;
            i++;
        }
        return i;
    }

    private static int nextType(List<Token> ts, int from, int want) {
        for (int i = Math.max(0, from); i < ts.size(); i++) {
            if (ts.get(i).getType() == want) return i;
        }
        return ts.size(); // not found
    }

    private static int skipIfNotExists(List<Token> ts, int from) {
        int i = nextNonDelim(ts, from);
        // IF NOT EXISTS
        if (i < ts.size() && ts.get(i).getType() == MySQLLexer.IF) {
            int j = nextNonDelim(ts, i + 1);
            if (j < ts.size() && ts.get(j).getType() == MySQLLexer.NOT) {
                int k = nextNonDelim(ts, j + 1);
                if (k < ts.size() && ts.get(k).getType() == MySQLLexer.EXISTS) {
                    return nextNonDelim(ts, k + 1);
                }
            }
        }
        return i;
    }

    /** Đọc qualified name kể từ vị trí 'from' cho tới trước LPAREN/AS/ON/... */
    private static String readQualifiedName(List<Token> ts, int from) {
        StringBuilder sb = new StringBuilder();
        int i = nextNonDelim(ts, from);
        while (i < ts.size()) {
            int tp = ts.get(i).getType();
            if (tp == MySQLLexer.LPAREN || tp == MySQLLexer.AS
                    || tp == MySQLLexer.ON || tp == MySQLLexer.WHERE) break;

            String txt = ts.get(i).getText();
            if (txt == null) break;
            String t = txt.trim();
            if (t.isEmpty()) break;

            if (tp == MySQLLexer.COMMA) break;
            if (tp == MySQLLexer.DELIM) break;

            if (tp == MySQLLexer.DOT) {
                sb.append('.');
            } else {
                if (!sb.isEmpty() && sb.charAt(sb.length()-1) != '.') sb.append(' ');
                sb.append(t);
            }
            i++;
            // dừng khi gặp khoảng trắng trước '('
            if (i < ts.size() && ts.get(i).getType() == MySQLLexer.LPAREN) break;
        }
        return normalizeName(sb.toString());
    }

    private static String normalizeName(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.isEmpty()) return null;
        // bỏ quote/backtick nếu có, và loại space giữa các phần (do cách nối ở trên)
        t = t.replace("`", "").replace("\"", "").replace("'", "");
        t = t.replace(" ", "");
        return t;
    }

    private static String nameOnly(String qn) {
        if (qn == null) return null;
        int dot = qn.lastIndexOf('.');
        return dot >= 0 ? qn.substring(dot + 1) : qn;
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}