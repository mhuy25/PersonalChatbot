grammar OracleSQL;

@header {
package com.example.personalchatbot.service.sql.antlr.oracle;
}

/*
 * Mục tiêu grammar:
 * - Đủ nhẹ để chunking và trích metadata cơ bản.
 * - Bắt được:
 *   + CREATE TABLE / GLOBAL TEMPORARY TABLE  → danh sách cột (columnDef)
 *   + CREATE VIEW / CREATE MATERIALIZED VIEW → danh sách cột (selectList) & bảng (FROM/JOIN tableRef)
 *   + Các DDL khác (INDEX/SEQUENCE/SYNONYM/TRIGGER/PACKAGE/PROCEDURE) để split + name
 *   + COMMENT, ALTER SESSION, anonymous PL/SQL block
 * - Không fully-typed Oracle SQL. Dùng rule "parens" để chịu được ngoặc lồng nhau.
 */

/* ============================== Parser rules ============================== */

sql
    : (sqlStatement)+ EOF?
    ;

sqlStatement
    : stCreateTable
    | stCreateGtt
    | stCreateIndex
    | stCreateView
    | stCreateMaterializedView
    | stCreateSequence
    | stCreateSynonym
    | stCreateTrigger
    | stCreatePackageSpec
    | stCreatePackageBody
    | stCreateProcedure
    | stAnonymousBlock
    | stAlterSession
    | stComment
    | stOther
    ;

/* ---------- CREATE TABLE & GTT: bắt danh sách cột ---------- */

stCreateTable
    :   CREATE TABLE tblName=qname
        LPAREN tableElement ( COMMA tableElement )* RPAREN
        ( . )*?                         // partitioning / options...
        terminatorSemi
    ;

stCreateGtt
    :   CREATE GLOBAL TEMPORARY TABLE tblName=qname
        LPAREN tableElement ( COMMA tableElement )* RPAREN
        ( . )*?
        terminatorSemi
    ;

/** Một phần tử trong () của CREATE TABLE */
tableElement
    :   columnDef
    |   otherElement
    ;

columnDef
    :   colName=id columnTail
    ;

/** Thân cột: mọi token cho tới ',' hoặc ')' top-level; cho phép ngoặc lồng nhau */
columnTail
    :   ( parens | ~(COMMA | RPAREN) )*
    ;

/** Các phần tử khác như CONSTRAINT ..., v.v. */
otherElement
    :   ( parens | ~(COMMA | RPAREN) )+
    ;

/** Ngoặc lồng nhau dùng đệ quy */
parens
    :   LPAREN ( parens | ~(LPAREN | RPAREN) )* RPAREN
    ;

/* ---------- CREATE INDEX (đơn giản) ---------- */
stCreateIndex
    :   CREATE ( BITMAP )? INDEX idxName=id ON qname
        LPAREN ( parens | ~(LPAREN | RPAREN | SEMI) )* RPAREN
        terminatorSemi
    ;

/* ---------- CREATE VIEW: lấy selectList & FROM/JOIN ---------- */
stCreateView
    :   CREATE ( OR REPLACE )? ( FORCE )? VIEW viewName=qname AS
        selectStatement
        terminatorSemi
    ;

/* ---------- CREATE MATERIALIZED VIEW: lấy selectList & FROM/JOIN ---------- */
stCreateMaterializedView
    :   CREATE MATERIALIZED VIEW mvName=qname
        ( . )*?                         // BUILD/REFRESH ... (bỏ qua chi tiết)
        AS
        selectStatement
        terminatorSemi
    ;

/* ---------- SELECT (tối giản, đủ lấy cột & bảng nguồn) ---------- */
selectStatement
    :   SELECT ( DISTINCT | ALL )? selectList fromClause selectTail?
    ;

selectTail
    :   ( WHERE | GROUP | HAVING | ORDER | UNION ) ( . )*?   // non-greedy: không cần chi tiết
    ;

selectList
    :   selectItem ( COMMA selectItem )*
    ;

selectItem
    :   expr=selectExpr ( (AS)? alias=id )?
    ;

/**
 * Biểu thức select cho tới dấu ',' top-level hoặc tới FROM.
 * Không cho phép FROM/SEMI/SLASH trong nhánh không ngoặc để dừng hợp lý.
 */
selectExpr
    :   ( parens | ~(COMMA | FROM | SEMI | SLASH) )+
    ;

fromClause
    :   FROM tableRef ( joinClause )*
    ;

tableRef
    :   qname ( id )?                   // alias (Oracle thường không dùng AS cho table)
    ;

joinClause
    :   ( LEFT | RIGHT | FULL | INNER )? ( OUTER )? JOIN tableRef joinCond
    ;

joinCond
    :   ON joinExpr
    ;

joinExpr
    :   ( parens | ~(JOIN | WHERE | GROUP | HAVING | ORDER | UNION | SEMI | SLASH) )*
    ;

/* ---------- CREATE SEQUENCE / SYNONYM (đơn giản) ---------- */
stCreateSequence
    :   CREATE SEQUENCE seqName=id ( . )*? terminatorSemi
    ;

stCreateSynonym
    :   CREATE ( OR REPLACE )? SYNONYM synName=id FOR qname terminatorSemi
    ;

/* ---------- CREATE TRIGGER / PACKAGE / PROCEDURE ---------- */
stCreateTrigger
    : CREATE ( OR REPLACE )? TRIGGER trgName=id
      ( . )*?
      BEGIN ( . )*? END ( id )?
      ( terminatorSemi )? slashTerm      // END; /  (có ; rồi /)
    | CREATE ( OR REPLACE )? TRIGGER trgName=id
      ( . )*?
      BEGIN ( . )*? END ( id )?
      terminatorSemi                      // chỉ END;
    ;

stCreatePackageSpec
    :   CREATE ( OR REPLACE )? PACKAGE pkgName=id
        ( . )*?
        END ( id )?
        ( terminatorSemi )? slashTerm          // ✅ cho phép END ... ; /
    |   CREATE ( OR REPLACE )? PACKAGE pkgName=id
        ( . )*?
        END ( id )?
        terminatorSemi                         // ✅ hoặc chỉ END ... ;
    ;

stCreatePackageBody
    :   CREATE ( OR REPLACE )? PACKAGE BODY pkgName=id
        ( . )*?
        END ( id )?
        ( terminatorSemi )? slashTerm
    |   CREATE ( OR REPLACE )? PACKAGE BODY pkgName=id
        ( . )*?
        END ( id )?
        terminatorSemi
    ;

stCreateProcedure
    :   CREATE ( OR REPLACE )? PROCEDURE procName=id
        ( . )*?
        END ( id )?
        ( terminatorSemi )? slashTerm
    |   CREATE ( OR REPLACE )? PROCEDURE procName=id
        ( . )*?
        END ( id )?
        terminatorSemi
    ;

/* ---------- COMMENT ---------- */
stComment
    :   COMMENT ON ( TABLE qname | COLUMN qname DOT id ) IS stringLiteral terminatorSemi
    ;

/* ---------- ALTER SESSION ---------- */
stAlterSession
    :   ALTER SESSION SET ( . )*? terminatorSemi
    ;

/* ---------- Anonymous PL/SQL block (BEGIN ... END;) + "/" ---------- */
stAnonymousBlock
    :   BEGIN ( . )*? END terminatorSemi? slashTerm
    ;

/* ---------- Fallback cho statement khác (để vẫn split được) ---------- */
stOther
    :   ( . )*? ( terminatorSemi | slashTerm )
    ;

/* ---------- Helpers ---------- */
terminatorSemi : SEMI ;
slashTerm      : SLASH ;
qname          : id ( DOT id )* ;
id             : IDENTIFIER | QUOTED_IDENTIFIER ;
stringLiteral  : STRING ;

/* ============================== Lexer rules ============================== */

// ======= Operators & symbols (add these) =======
CONCAT      : '||' ;                // đặt trước PIPE
NEQ2        : '<>' ;
NEQ1        : '!=' ;
LE          : '<=' ;
GE          : '>=' ;

EQUAL       : '=' ;
LT          : '<' ;
GT          : '>' ;
PLUS        : '+' ;
MINUS       : '-' ;
STAR        : '*' ;
PERCENT     : '%' ;
COLON       : ':' ;
PIPE        : '|' ;

// ======= Numbers (đủ dùng cho DDL) =======
INT         : [0-9]+ ;
DECIMAL     : [0-9]+ '.' [0-9]+ ;

/* Ký hiệu */
LPAREN  : '(' ;
RPAREN  : ')' ;
COMMA   : ',' ;
DOT     : '.' ;
SEMI    : ';' ;
SLASH   : '/' ;

/* Keywords (uppercase) */
CREATE  : 'CREATE' ;
OR      : 'OR' ;
REPLACE : 'REPLACE' ;
FORCE   : 'FORCE' ;
TABLE   : 'TABLE' ;
GLOBAL  : 'GLOBAL' ;
TEMPORARY : 'TEMPORARY' ;
INDEX   : 'INDEX' ;
BITMAP  : 'BITMAP' ;
VIEW    : 'VIEW' ;
MATERIALIZED : 'MATERIALIZED' ;
SEQUENCE: 'SEQUENCE' ;
SYNONYM : 'SYNONYM' ;
TRIGGER : 'TRIGGER' ;
PACKAGE : 'PACKAGE' ;
BODY    : 'BODY' ;
PROCEDURE : 'PROCEDURE' ;
AS      : 'AS' ;
IS      : 'IS' ;
BEGIN   : 'BEGIN' ;
END     : 'END' ;
COMMENT : 'COMMENT' ;
ON      : 'ON' ;
COLUMN  : 'COLUMN' ;
ALTER   : 'ALTER' ;
SESSION : 'SESSION' ;
SET     : 'SET' ;
SELECT  : 'SELECT' ;
DISTINCT: 'DISTINCT' ;
ALL     : 'ALL' ;
FROM    : 'FROM' ;
WHERE   : 'WHERE' ;
GROUP   : 'GROUP' ;
BY      : 'BY' ;
HAVING  : 'HAVING' ;
ORDER   : 'ORDER' ;
JOIN    : 'JOIN' ;
LEFT    : 'LEFT' ;
RIGHT   : 'RIGHT' ;
FULL    : 'FULL' ;
INNER   : 'INNER' ;
OUTER   : 'OUTER' ;
UNION   : 'UNION' ;
BUILD   : 'BUILD' ;
IMMEDIATE : 'IMMEDIATE' ;
REFRESH : 'REFRESH' ;
COMPLETE: 'COMPLETE' ;
DEMAND  : 'DEMAND' ;
FUNCTION: 'FUNCTION' ;
RETURN  : 'RETURN' ;
FOR     : 'FOR' ;

/* Identifier & literal */
QUOTED_IDENTIFIER
    :   '"' ( '""' | ~["\r\n] )* '"'
    ;

IDENTIFIER
    :   [A-Za-z_] [A-Za-z_0-9$#]*
    ;

/* Chuỗi ký tự (string literal) */
STRING
    :   '\'' ( '\'\'' | ~['\r\n] )* '\''
    ;

/* Bỏ qua khoảng trắng & comment */
WS              : [ \t\r\n]+ -> skip ;
LINE_COMMENT    : '--' ~[\r\n]* -> skip ;
BLOCK_COMMENT   : '/*' .*? '*/' -> skip ;