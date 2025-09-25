package com.example.personalchatbot.service.sql.antlr.sqlserver.util;

import com.example.personalchatbot.service.sql.antlr.sqlserver.SqlServerLexer;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import org.antlr.v4.runtime.*;

import java.util.*;

public class SqlBatchSegment {

    @FieldDefaults(level = AccessLevel.PUBLIC)
    public static final class Batch {
        int index;
        int startIndex;    // char offset
        int stopIndex;     // char offset (inclusive)
        int startLine;     // token.getLine()
        int endLine;
        String text;
        int goRepeat;      // nếu theo sau có "GO n" => n, else 0
    }

    public List<Batch> segment(String script) {
        CharStream cs = CharStreams.fromString(script);
        SqlServerLexer lexer = new SqlServerLexer(cs);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        tokens.fill();
        List<Token> tokenLists = tokens.getTokens();

        List<Batch> out = new ArrayList<>();

        int boundaryCharStart = 0;
        Token firstTokInBatch = null;
        Token lastTokInBatch  = null;

        for (Token t : tokenLists) {
            int type = t.getType();


            if (type == Token.EOF) {
                if (lastTokInBatch != null) {
                    addBatch(out, script, boundaryCharStart, lastTokInBatch, firstTokInBatch,
                            out.size(), /*goRepeat*/0);
                }
                break;
            }

            if (type == SqlServerLexer.GO_STMT) {
                if (lastTokInBatch != null) {
                    int repeat = parseGoRepeatNoRegex(t.getText());
                    addBatch(out, script, boundaryCharStart, lastTokInBatch, firstTokInBatch,
                            out.size(), repeat);
                    firstTokInBatch = null;
                    lastTokInBatch = null;
                } else {
                    if (!out.isEmpty()) {
                        out.getLast().goRepeat = parseGoRepeatNoRegex(t.getText());
                    }
                }
                boundaryCharStart = t.getStopIndex() + 1;
                continue;
            }

            if (firstTokInBatch == null) firstTokInBatch = t;
            lastTokInBatch = t;
        }
        return out;
    }

    private int parseGoRepeatNoRegex(String goText) {
        if (goText == null) return 1;
        // Quét sau chữ 'G''O' để tìm số đầu tiên trên dòng (bỏ khoảng trắng)
        int i = 0, n = goText.length();
        // bỏ đầu dòng trắng
        while (i < n && Character.isWhitespace(goText.charAt(i))) i++;
        // bỏ 'G' 'O' (không phân biệt hoa/thường)
        if (i < n && (goText.charAt(i)=='G'||goText.charAt(i)=='g')) i++;
        if (i < n && (goText.charAt(i)=='O'||goText.charAt(i)=='o')) i++;
        // bỏ khoảng trắng
        while (i < n && Character.isWhitespace(goText.charAt(i))) i++;
        // đọc số
        long val = 0;
        boolean hasDigit = false;
        while (i < n && Character.isDigit(goText.charAt(i))) {
            hasDigit = true;
            val = val * 10 + (goText.charAt(i) - '0');
            // chặn tràn vô lý
            if (val > Integer.MAX_VALUE) { val = Integer.MAX_VALUE; break; }
            i++;
        }
        return hasDigit ? (int) Math.max(1, val) : 1;
    }

    private void addBatch(List<Batch> out, String script,
                          int boundaryCharStart, Token lastTokInBatch, Token firstTokInBatch,
                          int index, int goRepeat) {
        int startChar = Math.max(0, boundaryCharStart);
        int endCharInclusive = Math.max(lastTokInBatch.getStopIndex(), boundaryCharStart - 1);
        if (endCharInclusive < startChar) return;

        String text = script.substring(startChar, Math.min(script.length(), endCharInclusive + 1)).trim();
        if (text.isEmpty()) return;

        Batch b = new Batch();
        b.index = index;
        b.startIndex = startChar;
        b.stopIndex = endCharInclusive;
        b.startLine = firstTokInBatch != null ? firstTokInBatch.getLine() : 1;
        b.endLine = lastTokInBatch.getLine();
        b.text = text;
        b.goRepeat = goRepeat; // 0 nếu batch cuối không có GO
        out.add(b);
    }
}
