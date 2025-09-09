package com.example.personalchatbot.service.sql.dialect.service;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.parser.ParserException;
import com.example.personalchatbot.service.sql.dialect.config.DialectDetectConfig;
import com.example.personalchatbot.service.sql.dto.DialectDetectDto;
import com.example.personalchatbot.service.sql.dialect.implement.DialectDetectServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

@Slf4j
@Service
public class DialectDetectService implements DialectDetectServiceImpl {
    private record Sig(Pattern p, int w, String label) {
        boolean found(CharSequence s) { return p.matcher(s).find(); }
    }
    private static final Map<DbType, List<Sig>> COMPILED_SIGS = compile();

    private static Map<DbType, List<Sig>> compile() {
        Map<DbType, List<Sig>> out = new EnumMap<>(DbType.class);
        for (var e : DialectDetectConfig.SIGNATURES.entrySet()) {
            List<Sig> list = new ArrayList<>();
            for (DialectDetectConfig.SigDef d : e.getValue()) {
                list.add(new Sig(Pattern.compile(d.regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL), d.weight, d.label));
            }
            out.put(e.getKey(), Collections.unmodifiableList(list));
        }
        return Collections.unmodifiableMap(out);
    }

    @Override
    public DialectDetectDto detect(String ddl) {
        if (ddl == null || ddl.trim().isEmpty()) {
            return DialectDetectDto.builder()
                    .dbType(DbType.other.name())
                    .confidence(0.0)
                    .hits(List.of("Blank input → OTHER"))
                    .debugScores(Map.of())
                    .build();
        }

        final String normOriginal = normalize(ddl);

        // 1) Hint
        DbType hinted = findHint(ddl);
        if (hinted != null) {
            DbType parseDialect = toParseDialect(hinted);
            double conf = tryParseConfidence(ddl, parseDialect);
            if (conf > 0) {
                return DialectDetectDto.builder()
                        .dbType(hinted.name())
                        .confidence(round2(Math.max(0.6, conf)))
                        .hits(List.of("Hint matched: " + hinted.name(),
                                "Parsed OK by Druid as " + parseDialect.name(),
                                String.format("Round-trip similarity=%.2f", conf)))
                        .debugScores(Map.of(hinted.name(), (int)Math.round(conf * 20)))
                        .build();
            }
            // nếu hint parse fail -> tiếp tục heuristic/parse bên dưới
        }

        // 2) Heuristic: chấm điểm
        Map<DbType, Integer> heuristic = scoreByHeuristic(ddl);
        List<Map.Entry<DbType, Integer>> positives = new ArrayList<>();
        for (var e : heuristic.entrySet())
            if (e.getValue() >= DialectDetectConfig.MIN_POSITIVE_SCORE) positives.add(e);

        positives.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        if (positives.size() > DialectDetectConfig.TOP_PARSE_CANDIDATES) {
            positives = positives.subList(0, DialectDetectConfig.TOP_PARSE_CANDIDATES);
        }

        // 3) Parse + roundtrip similarity trên nhóm cao điểm nhất
        Map<String, Integer> debugScores = new LinkedHashMap<>();
        DbType best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        double bestSim = 0.0;
        List<String> bestHits = new ArrayList<>();

        for (var cand : positives) {
            DbType t = cand.getKey();
            DbType parseDialect = toParseDialect(t);
            int hScore = cand.getValue();

            double score = -1e9;
            double similarity = 0.0;
            boolean parsedOk = false;

            try {
                List<SQLStatement> stmts = SQLUtils.parseStatements(ddl, parseDialect);
                if (!stmts.isEmpty()) {
                    parsedOk = true;
                    String formatted = SQLUtils.toSQLString(stmts, parseDialect);
                    String normFormatted = normalize(formatted);
                    similarity = similarity(normOriginal, normFormatted); // 0..1
                    score = 10.0 + (hScore * 0.5) + (similarity * 10.0) + Math.min(5, stmts.size());
                }
            } catch (ParserException ignore) {
                score = -1.0;
            } catch (Throwable th) {
                log.debug("Try dialect {} error: {}", parseDialect, th.toString());
                score = -1.0;
            }

            debugScores.put(t.name(), (int)Math.round(score));

            if (score > bestScore) {
                bestScore = score;
                best = parsedOk ? t : null;
                bestSim = similarity;
                bestHits = new ArrayList<>();
                if (parsedOk) {
                    if (parseDialect != t)
                        bestHits.add("Parsed as " + parseDialect.name() + " (family of " + t.name() + ")");
                    else
                        bestHits.add("Parsed OK by Druid (" + t.name() + ")");
                    bestHits.add(String.format("Round-trip similarity=%.2f", bestSim));
                    bestHits.add("Heuristic score=" + hScore);
                } else {
                    bestHits.add("Parse failed as " + parseDialect.name() + " for candidate " + t.name());
                    bestHits.add("Heuristic score=" + hScore);
                }
            }
        }

        if (best != null) {
            double runnerUp = runnerUpScore(debugScores);
            double margin = Math.max(0, (bestScore - runnerUp) / 10.0);
            double confidence = Math.min(1.0, 0.2 + 0.6 * bestSim + 0.2 * Math.min(1.0, margin));
            return DialectDetectDto.builder()
                    .dbType(best.name())
                    .confidence(round2(confidence))
                    .hits(Collections.unmodifiableList(bestHits))
                    .debugScores(Collections.unmodifiableMap(debugScores))
                    .build();
        }

        // 4) FALLBACK: thử parse bằng DEFAULT (postgresql). Nếu vẫn fail -> OTHER.
        double defConf = tryParseConfidence(ddl, DialectDetectConfig.DEFAULT);
        if (defConf > 0) {
            return DialectDetectDto.builder()
                    .dbType(DialectDetectConfig.DEFAULT.name())
                    .confidence(round2(Math.max(0.3, defConf)))
                    .hits(List.of("Fallback default parsed OK (" + DialectDetectConfig.DEFAULT.name() + ")",
                            String.format("Round-trip similarity=%.2f", defConf)))
                    .debugScores(Map.of(DialectDetectConfig.DEFAULT.name(), (int)Math.round(defConf * 20)))
                    .build();
        }

        // default parse fail -> OTHER
        return DialectDetectDto.builder()
                .dbType(DbType.other.name())
                .confidence(0.0)
                .hits(List.of("Fallback default parse failed → OTHER"))
                .debugScores(Collections.unmodifiableMap(debugScores))
                .build();
    }

    /* ================= helpers ================= */

    private static DbType findHint(String s) {
        String lc = s.toLowerCase(Locale.ROOT);
        String head = lc.substring(0, Math.min(lc.length(), 4096));
        for (var e : DialectDetectConfig.HINT_MAP.entrySet())
            if (head.contains(e.getKey())) return e.getValue();
        return null;
    }

    private static Map<DbType, Integer> scoreByHeuristic(String s) {
        Map<DbType, Integer> res = new EnumMap<>(DbType.class);
        for (var e : COMPILED_SIGS.entrySet()) {
            int sum = 0;
            for (Sig sig : e.getValue()) if (sig.found(s)) sum += sig.w;
            if (sum > 0) res.put(e.getKey(), sum);
        }
        return res;
    }

    private static String normalize(String s) {
        String x = s.toLowerCase(Locale.ROOT);
        x = x.replace('`', '"');
        x = x.replace('[', '"').replace(']', '"');
        x = x.replaceAll("\\s+", " ");
        return x.trim();
    }

    /** Levenshtein similarity: 1 - (distance / maxLen). */
    private static double similarity(String a, String b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        int dist = levenshtein(a, b);
        int max = Math.max(a.length(), b.length());
        return Math.max(0.0, 1.0 - ((double) dist / (double) max));
    }

    private static int levenshtein(String a, String b) {
        int n = a.length(), m = b.length();
        int[] prev = new int[m + 1], curr = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            curr[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                int cost = (ca == b.charAt(j - 1)) ? 0 : 1;
                curr[j] = Math.min(Math.min(prev[j] + 1, curr[j - 1] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[m];
    }

    private static double runnerUpScore(Map<String, Integer> scores) {
        double best = Double.NEGATIVE_INFINITY, second = Double.NEGATIVE_INFINITY;
        for (var e : scores.entrySet()) {
            double v = e.getValue();
            if (v > best) { second = best; best = v; }
            else if (v > second) { second = v; }
        }
        return second;
    }

    /**
     * “Dialect để PARSE” cho một số họ:
     * - MySQL-family (MariaDB/TiDB/StarRocks/Doris/PolarDB/OB-MySQL) -> MySQL
     * - Postgres-family (Redshift/Greenplum/EDB/…​) -> PostgreSQL
     * - Oracle-family (OB-Oracle/Ali_Oracle) -> Oracle
     * - T-SQL family (Synapse/Sybase) -> SQLServer
     */
    private static DbType toParseDialect(DbType t) {
        return switch (t) {
            // MySQL family
            case mariadb, tidb, polardb, polardbx, adb_mysql, starrocks, doris, oceanbase -> DbType.mysql;
            // PostgreSQL family
            case edb, redshift, greenplum, gaussdb, kingbase, highgo, oscar -> DbType.postgresql;
            // Oracle family
            case oceanbase_oracle, ali_oracle -> DbType.oracle;
            // T-SQL family
            case synapse, sybase -> DbType.sqlserver;
            default -> t;
        };
    }

    private static double tryParseConfidence(String sql, DbType t) {
        try {
            List<SQLStatement> stmts = SQLUtils.parseStatements(sql, t);
            if (stmts.isEmpty()) return 0.0;
            String formatted = SQLUtils.toSQLString(stmts, t);
            return similarity(normalize(sql), normalize(formatted));
        } catch (Throwable ignore) {
            return 0.0;
        }
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
