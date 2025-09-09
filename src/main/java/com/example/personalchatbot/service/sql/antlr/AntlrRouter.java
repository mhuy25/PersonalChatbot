package com.example.personalchatbot.service.sql.antlr;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import com.example.personalchatbot.service.sql.antlr.implement.AntlrSqlParserImpl;

public class AntlrRouter {
    private final Map<String, AntlrSqlParserImpl> delegates = new HashMap<>();

    public AntlrRouter register(String dialect, AntlrSqlParserImpl parser) {
        delegates.put(norm(dialect), parser);
        return this;
    }

    public boolean supports(String dialect) {
        return delegates.containsKey(norm(dialect));
    }

    public AntlrSqlParserImpl get(String dialect) {
        return delegates.get(norm(dialect));
    }

    private String norm(String d) {
        return d == null ? "other" : d.trim().toLowerCase(Locale.ROOT);
    }
}
