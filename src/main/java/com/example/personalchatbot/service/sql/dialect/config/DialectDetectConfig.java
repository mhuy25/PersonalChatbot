package com.example.personalchatbot.service.sql.dialect.config;

import com.alibaba.druid.DbType;
import lombok.NoArgsConstructor;

import java.util.*;

@NoArgsConstructor
public final class DialectDetectConfig {
    /** Dialect mặc định để THỬ PARSE trước khi quyết định OTHER. */
    public static final DbType DEFAULT = DbType.postgresql;

    /** Chỉ thử parse các ứng viên có điểm > 0 và nằm trong top-N. */
    public static final int TOP_PARSE_CANDIDATES = 5;
    public static final int MIN_POSITIVE_SCORE   = 1;

    /** Hints (tìm trong phần header/comment của file) -> DbType. */
    public static final Map<String, DbType> HINT_MAP;
    static {
        Map<String, DbType> m = new LinkedHashMap<>();
        // Postgres family
        m.put("pg_dump", DbType.postgresql);
        m.put("postgresql", DbType.postgresql);
        m.put("dialect:postgresql", DbType.postgresql);
        m.put("dialect:postgres", DbType.postgresql);
        m.put("enterprisedb", DbType.edb);
        m.put("edb", DbType.edb);
        m.put("greenplum", DbType.greenplum);
        m.put("redshift", DbType.redshift);
        m.put("opengauss", DbType.gaussdb);
        m.put("kingbase", DbType.kingbase);
        m.put("highgo", DbType.highgo);
        m.put("oscardb", DbType.oscar);

        // MySQL family
        m.put("mysqldump", DbType.mysql);
        m.put("dialect:mysql", DbType.mysql);
        m.put("mariadb", DbType.mariadb);
        m.put("tidb", DbType.tidb);
        m.put("polardb", DbType.polardb);
        m.put("polardbx", DbType.polardbx);
        m.put("adb_mysql", DbType.adb_mysql);
        m.put("oceanbase mysql", DbType.oceanbase);
        m.put("starrocks", DbType.starrocks);
        m.put("doris", DbType.doris);

        // Oracle / T-SQL / others
        m.put("dialect:oracle", DbType.oracle);
        m.put("oceanbase oracle", DbType.oceanbase_oracle);
        m.put("ali_oracle", DbType.ali_oracle);

        m.put("microsoft sql server", DbType.sqlserver);
        m.put("sql server", DbType.sqlserver);
        m.put("mssql", DbType.sqlserver);
        m.put("synapse", DbType.synapse);

        m.put("sqlite", DbType.sqlite);
        m.put("clickhouse", DbType.clickhouse);

        // Lakehouse / MPP
        m.put("trino", DbType.trino);
        m.put("presto", DbType.presto);
        m.put("athena", DbType.athena);
        m.put("hive", DbType.hive);
        m.put("impala", DbType.impala);
        m.put("snowflake", DbType.snowflake);
        m.put("bigquery", DbType.bigquery);
        m.put("databricks", DbType.databricks);
        m.put("spark", DbType.spark);

        // Khác
        m.put("db2", DbType.db2);
        m.put("teradata", DbType.teradata);
        m.put("sybase", DbType.sybase);
        m.put("informix", DbType.informix);
        m.put("phoenix", DbType.phoenix);
        m.put("h2", DbType.h2);
        m.put("hsql", DbType.hsql);
        m.put("derby", DbType.derby);

        HINT_MAP = Collections.unmodifiableMap(m);
    }

    /** Heuristic (regex nhẹ) để xếp hạng ứng viên. */
    public static final Map<DbType, List<SigDef>> SIGNATURES;
    static {
        Map<DbType, List<SigDef>> map = new EnumMap<>(DbType.class);

        // PostgreSQL / family
        add(map, DbType.postgresql, new SigDef("\\bCREATE\\s+EXTENSION\\b", 6, "CREATE EXTENSION"));
        add(map, DbType.postgresql, new SigDef("\\bSERIAL\\b|\\bBIGSERIAL\\b|\\bSMALLSERIAL\\b", 6, "SERIAL family"));
        add(map, DbType.postgresql, new SigDef("\\bJSONB\\b", 4, "JSONB"));
        add(map, DbType.postgresql, new SigDef("\\bUSING\\s+(GIN|GIST)\\b", 3, "USING GIN/GIST"));
        add(map, DbType.greenplum,  new SigDef("\\bDISTRIBUTED\\s+BY\\b|\\bDISTRIBUTED\\s+RANDOMLY\\b", 6, "GP DISTRIBUTED"));
        add(map, DbType.redshift,   new SigDef("\\bDISTSTYLE\\b|\\bDISTKEY\\b|\\bSORTKEY\\b|\\bENCODE\\b", 7, "RS DIST/SORT/ENCODE"));

        // MySQL / family
        add(map, DbType.mysql,   new SigDef("\\bAUTO_INCREMENT\\b", 6, "AUTO_INCREMENT"));
        add(map, DbType.mysql,   new SigDef("ENGINE\\s*=", 6, "ENGINE="));
        add(map, DbType.mysql,   new SigDef("`[^`]+`", 4, "Backtick quotes"));
        add(map, DbType.mysql,   new SigDef("\\bUNSIGNED\\b", 3, "UNSIGNED"));
        add(map, DbType.mariadb, new SigDef("\\bROW_FORMAT\\s*=\\b", 3, "ROW_FORMAT"));
        add(map, DbType.starrocks,new SigDef("\\bDUPLICATE\\s+KEY\\b|\\bAGGREGATE\\s+KEY\\b|\\bDISTRIBUTED\\s+BY\\b", 6, "StarRocks keys/dist"));
        add(map, DbType.doris,   new SigDef("\\bDUPLICATE\\s+KEY\\b|\\bAGGREGATE\\s+KEY\\b|\\bDISTRIBUTED\\s+BY\\b", 6, "Doris keys/dist"));

        // Oracle / family
        add(map, DbType.oracle,  new SigDef("\\bVARCHAR2\\b|\\bNVARCHAR2\\b", 6, "VARCHAR2/NVARCHAR2"));
        add(map, DbType.oracle,  new SigDef("\\bNUMBER\\s*\\(\\d+(,\\s*\\d+)?\\)", 4, "NUMBER(p[,s])"));
        add(map, DbType.oracle,  new SigDef("\\bENABLE\\s+ROW\\s+MOVEMENT\\b", 4, "ENABLE ROW MOVEMENT"));

        // SQL Server / T-SQL
        add(map, DbType.sqlserver, new SigDef("\\[[^\\]]+\\]", 6, "Square bracket identifiers"));
        add(map, DbType.sqlserver, new SigDef("\\bIDENTITY\\s*\\(", 6, "IDENTITY(seed, increment)"));
        add(map, DbType.sqlserver, new SigDef("(?m)^\\s*GO\\s*$", 4, "GO batch separator"));
        add(map, DbType.synapse,   new SigDef("\\bDISTRIBUTION\\s*=\\b|\\bHEAP\\b|\\bHASH\\s*\\(", 5, "Synapse dist/heap"));

        // SQLite
        add(map, DbType.sqlite, new SigDef("\\bINTEGER\\s+PRIMARY\\s+KEY\\s+AUTOINCREMENT\\b", 8, "INTEGER PK AUTOINCREMENT"));
        add(map, DbType.sqlite, new SigDef("\\bWITHOUT\\s+ROWID\\b", 5, "WITHOUT ROWID"));
        add(map, DbType.sqlite, new SigDef("(?i)\\bPRAGMA\\b", 6, "PRAGMA"));

        // ClickHouse
        add(map, DbType.clickhouse, new SigDef("\\bENGINE\\s*=\\s*(MergeTree|ReplacingMergeTree|AggregatingMergeTree|SummingMergeTree|CollapsingMergeTree)\\b", 8, "ENGINE=...MergeTree"));
        add(map, DbType.clickhouse, new SigDef("\\bLowCardinality\\b|\\bUInt(8|16|32|64)\\b", 4, "CH types"));

        // Hive / Impala
        add(map, DbType.hive,   new SigDef("\\bROW\\s+FORMAT\\b|\\bSTORED\\s+AS\\b", 5, "ROW FORMAT / STORED AS"));
        add(map, DbType.hive,   new SigDef("\\bLOCATION\\s+'hdfs://", 5, "LOCATION 'hdfs://'"));
        add(map, DbType.hive,   new SigDef("\\bTBLPROPERTIES\\b", 3, "TBLPROPERTIES"));
        add(map, DbType.impala, new SigDef("\\bKUDU\\b|\\bDELIMITED\\b|\\bPARQUET\\b", 3, "Impala/Hive-ish"));

        // Lakehouse
        add(map, DbType.trino,  new SigDef("\\bCREATE\\s+TABLE\\b[\\s\\S]*\\bWITH\\s*\\(", 4, "CREATE TABLE ... WITH (...)"));
        add(map, DbType.presto, new SigDef("\\bCREATE\\s+TABLE\\b[\\s\\S]*\\bWITH\\s*\\(", 4, "CREATE TABLE ... WITH (...)"));
        add(map, DbType.athena, new SigDef("\\bEXTERNAL\\s+TABLE\\b|\\bROW\\s+FORMAT\\s+SERDE\\b|\\bLOCATION\\s+'s3://", 6, "EXTERNAL/SERDE/S3"));

        // Cloud DWH
        add(map, DbType.snowflake, new SigDef("\\bVARIANT\\b|\\bOBJECT\\b|\\bARRAY\\b", 6, "Semi-structured types"));
        add(map, DbType.snowflake, new SigDef("\\bCOPY\\s+INTO\\b|\\bFILE\\s+FORMAT\\b|\\bSTAGE\\b", 4, "Snowflake COPY/FILE FORMAT"));
        add(map, DbType.bigquery,  new SigDef("`[\\w-]+\\.[\\w-]+\\.[\\w-]+`", 6, "project.dataset.table backticks"));
        add(map, DbType.bigquery,  new SigDef("\\bOPTIONS\\s*\\(", 4, "OPTIONS(...)"));

        // Khác
        add(map, DbType.teradata, new SigDef("\\bPRIMARY\\s+INDEX\\b|\\bPARTITION\\s+BY\\b", 5, "Teradata PI/PARTITION"));
        add(map, DbType.db2,      new SigDef("\\bORGANIZE\\s+BY\\s+ROW\\b|\\bCCSID\\b", 3, "DB2 traits"));
        add(map, DbType.h2,       new SigDef("\\bIDENTITY\\b|\\bAUTO_INCREMENT\\b", 2, "H2 auto inc"));
        add(map, DbType.hsql,     new SigDef("\\bCACHED\\s+TABLE\\b|\\bMEMORY\\s+TABLE\\b", 2, "HSQL traits"));
        add(map, DbType.derby,    new SigDef("\\bGENERATED\\s+ALWAYS\\s+AS\\s+IDENTITY\\b", 3, "Derby identity"));

        SIGNATURES = freeze(map);
    }

    private static void add(Map<DbType, List<SigDef>> map, DbType t, SigDef sig) {
        map.computeIfAbsent(t, k -> new ArrayList<>()).add(sig);
    }
    private static Map<DbType, List<SigDef>> freeze(Map<DbType, List<SigDef>> map) {
        Map<DbType, List<SigDef>> out = new EnumMap<>(DbType.class);
        for (var e : map.entrySet())
            out.put(e.getKey(), Collections.unmodifiableList(new ArrayList<>(e.getValue())));
        return Collections.unmodifiableMap(out);
    }

    /** Định nghĩa signature (regex + weight + nhãn) cho heuristic. */
    public static final class SigDef {
        public final String regex; public final int weight; public final String label;
        public SigDef(String regex, int weight, String label) { this.regex = regex; this.weight = weight; this.label = label; }
    }
}
