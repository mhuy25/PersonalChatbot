package com.example.personalchatbot.service.sql.llm;

import com.example.personalchatbot.dto.PromptDto;
import com.example.personalchatbot.dto.SearchHitDto;
import com.example.personalchatbot.service.implement.PromptServiceImpl;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SqlPromptService implements PromptServiceImpl {

    @Override
    public PromptDto build(String question, List<SearchHitDto> hits) {

        String system = String.join("\n",
                "You are a SQL metadata extractor.",
                "",
                "TASK",
                "- Read a SQL DIALECT and a list of SQL statements.",
                "- Return a JSON ARRAY with one object per input statement in the same order.",
                "- Output JSON only (no prose, no markdown, no comments).",
                "",
                "OUTPUT SCHEMA (per item)",
                "{",
                "  \"statementType\": \"CREATE_SCHEMA | CREATE_TABLE | CREATE_VIEW | CREATE_INDEX | CREATE_TRIGGER | CREATE_FUNCTION | CREATE_PROCEDURE | STATEMENT\",",
                "  \"schemaName\": string|null,",
                "  \"objectName\": string|null,",
                "  \"tables\": string[],",
                "  \"columns\": string[]",
                "}",
                "",
                "STRICT RULES",
                "1) JSON array only. Number of items MUST equal number of input statements. Keep order.",
                "2) Keep identifiers EXACTLY as written but strip quoting wrappers (\"name\", [name], `name` -> name). Preserve original case.",
                "3) schemaName/objectName:",
                "   - If the defined object is schema-qualified (e.g., sample.orders): schemaName=\"sample\", objectName=\"orders\".",
                "   - If not qualified: schemaName=null, objectName=unqualified name.",
                "   - For generic STATEMENTs, objectName=null.",
                "4) CREATE_SCHEMA: objectName=schema; tables=[]; columns=[].",
                "5) CREATE_TABLE: objectName=table; tables=[table]; columns=all defined column names (exclude constraints, indexes, expressions, computed columns).",
                "6) CREATE_VIEW: objectName=view; tables = dedup base tables from the SELECT (FROM/JOIN; include CTE base tables);",
                "   columns = output columns (use SELECT aliases; if any wildcard *, set []).",
                "7) CREATE_INDEX: objectName=index; tables=[base table]; columns=index key columns in order; omit expression keys (e.g., LOWER(name)).",
                "8) CREATE_TRIGGER: objectName=trigger; tables=[target table in ON <table>];",
                "   columns = columns listed in UPDATE OF ... plus columns referenced in WHEN(...); if none/unclear, [].",
                "9) CREATE_FUNCTION / CREATE_PROCEDURE:",
                "   - objectName=routine name (set schemaName if qualified).",
                "   - Parse dollar-quoted bodies $$...$$ as code, not plain text.",
                "   - Extract SQL statements inside (SELECT/INSERT/UPDATE/DELETE/MERGE/EXECUTE).",
                "   - tables = dedup base tables referenced by those SQL statements.",
                "   - columns = distinct referenced column names from those SQL statements.",
                "   - Ignore variables/params/records (NEW./OLD., p_*, v_*).",
                "10) STATEMENT (all others): objectName=null; tables=dedup base tables; columns=distinct referenced columns; if only * or unclear, [].",
                "11) Deduplicate lists but keep first-seen order. Limit columns to 100 items.",
                "12) If a statement cannot be confidently parsed, return {\"statementType\":\"STATEMENT\",\"schemaName\":null,\"objectName\":null,\"tables\":[],\"columns\":[]}."
        );

        String user = """
            // Provide DIALECT and numbered STATEMENTS.
            // Return ONLY a JSON array. No markdown. No explanations.

            INPUT:
            %s

            OUTPUT:
            [ ... JSON array only ... ]
            """.formatted(question == null ? "" : question.trim());

        return new PromptDto(system, user);
    }
}
