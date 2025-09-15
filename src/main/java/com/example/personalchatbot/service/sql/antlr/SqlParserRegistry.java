package com.example.personalchatbot.service.sql.antlr;

import com.example.personalchatbot.service.sql.antlr.implement.AntlrSqlParserImpl;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class SqlParserRegistry {
    private final Map<String, AntlrSqlParserImpl> antlrByDialect = new ConcurrentHashMap<>();

    @Autowired
    public SqlParserRegistry(List<AntlrSqlParserImpl> parsers) {
        parsers.forEach(this::register);
    }

    private static String canon(String d) {
        if (d == null) return null;
        return switch (d.trim().toLowerCase(Locale.ROOT)) {
            case "oracle", "plsql" -> "oracle";
            case "postgres", "postgresql", "pgsql" -> "postgresql";
            case "mssql", "sqlserver", "tsql", "t-sql" -> "sqlserver";
            case "mysql", "mariadb" -> "mysql";
            default -> d.trim().toLowerCase(Locale.ROOT);
        };
    }

    public void register(AntlrSqlParserImpl p) {
        antlrByDialect.put(canon(p.dialect()), p);
    }

    public Optional<AntlrSqlParserImpl> antlrFor(String dialect) {
        return Optional.ofNullable(antlrByDialect.get(normalize(dialect)));
    }

    @PostConstruct
    void ok() { log.info("OracleSqlParser bean is alive"); }

    private static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}
