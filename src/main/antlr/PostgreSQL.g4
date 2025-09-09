grammar PostgreSQL;

@header {
package com.example.personalchatbot.service.sql.antlr.postgres;
}

/* ======================= Entry ======================= */
sqlStatements
    : (sqlStatement semi?)* EOF
    ;

semi : ';' ;

sqlStatement
    : createSchemaStatement
    | createTableStatement
    | createIndexStatement
    | createViewStatement
    | createFunctionStatement
    | createProcedureStatement
    | doStatement
    | createTriggerStatement
    | createTypeEnumStatement
    | createExtensionStatement
    | createDomainStatement
    | createPolicyStatement
    | commentOnStatement
    ;

/* ======================= CREATE SCHEMA ======================= */
createSchemaStatement
    : CREATE SCHEMA qualifiedName
    ;

/* ======================= CREATE TABLE ======================= */
createTableStatement
    : CREATE (TEMPORARY | TEMP)? UNLOGGED? TABLE (IF NOT? EXISTS)? tableName=qualifiedName
      '(' tableElement (',' tableElement)* ')'
    ;

tableElement
    : columnDefinition
    | tableConstraint
    ;

columnDefinition
    : columnName=identifier dataType columnConstraint*
    ;

optColumnConstraints
    : columnConstraint*
    ;

// tightened, không có nhánh rỗng
columnConstraint
    : NULL
    | NOT NULL
    | DEFAULT literal
    | PRIMARY KEY
    | UNIQUE
    | REFERENCES refTable=qualifiedName ('(' refColumnList ')')?
        (ON DELETE fkAction)?
        (ON UPDATE fkAction)?
    ;

tableConstraint
    : (CONSTRAINT identifier)? (
          PRIMARY KEY '(' columnNameList ')'
        | UNIQUE '(' columnNameList ')'
        | FOREIGN? KEY '(' columnNameList ')'
          REFERENCES refTable=qualifiedName ('(' refColumnList ')')?
          (ON DELETE fkAction)? (ON UPDATE fkAction)?
      )
    ;

fkAction
    : CASCADE
    | RESTRICT
    | NO ACTION
    | SET NULL
    | SET DEFAULT
    ;

refColumnList   : identifier (',' identifier)* ;
columnNameList  : identifier (',' identifier)* ;

dataType       : identifier typeModifiers? arrayType? ;
typeModifiers  : '(' INTEGER (',' INTEGER)? ')' ;
arrayType      : '[' ']' ;

/* ======================= CREATE INDEX ======================= */
createIndexStatement
    : CREATE UNIQUE? INDEX (IF NOT? EXISTS)? indexName=qualifiedName
      ON tableName=qualifiedName
      usingMethod? '(' indexElem (',' indexElem)* ')'
      whereClause?
    ;

usingMethod : USING identifier ;
indexElem   : identifier sortOrder? ;
sortOrder   : ASC | DESC ;
whereClause : WHERE booleanExpr ;

/* ======================= CREATE VIEW (SELECT gọn) ======================= */
createViewStatement
    : CREATE (OR REPLACE)? VIEW viewName=qualifiedName AS selectStmt
    ;

/* -------- SELECT rút gọn, đủ cho view trong file mẫu -------- */
selectStmt
    : SELECT selectList fromClause whereClause? groupByClause? havingClause? orderByClause?
    ;

selectList
    : selectItem (',' selectItem)*
    ;

selectItem
    : expr (AS identifier)?
    | '*'
    ;

fromClause
    : FROM tableRef (joinClause)*
    ;

tableRef
    : qualifiedName (identifier)?      // alias
    ;

joinClause
    : (JOIN
      | INNER JOIN
      | LEFT JOIN
      | RIGHT JOIN
      | FULL JOIN
      | CROSS JOIN
      ) tableRef ON booleanExpr
    ;

groupByClause  : GROUP BY expr (',' expr)* ;
havingClause   : HAVING booleanExpr ;
orderByClause  : ORDER BY orderItem (',' orderItem)* ;
orderItem      : expr (ASC | DESC)? ;

/* ----------------- Biểu thức/điều kiện gọn ----------------- */
booleanExpr
    : booleanExpr AND booleanExpr
    | booleanExpr OR  booleanExpr
    | NOT booleanExpr
    | predicate
    ;

predicate
    : expr compareOp expr
    | expr IS NOT? NULL
    | '(' booleanExpr ')'
    ;

compareOp : '=' | '!=' | '<>' | '<' | '<=' | '>' | '>=' ;

expr
    : expr binaryOp expr
    | functionCall
    | qualifiedName
    | literal
    | '(' expr ')'
    ;

binaryOp
    : CONCAT
    | '+' | '-' | '*' | '/' | '%' | '^'
    ;

functionCall
    : identifier '(' (expr (',' expr)*)? ')'
    ;

/* ======================= CREATE FUNCTION / PROCEDURE / DO ======================= */
createFunctionStatement
    : CREATE (OR REPLACE)? FUNCTION funcName=qualifiedName '(' funcParams? ')'
      RETURNS typeName=identifier
      (LANGUAGE lang=identifier)?
      (AS (dollarBody | STRING))?
    ;

createProcedureStatement
    : CREATE (OR REPLACE)? PROCEDURE procName=qualifiedName '(' funcParams? ')'
      (LANGUAGE lang=identifier)?
      (AS (dollarBody | STRING))?
    ;

doStatement
    : DO (LANGUAGE lang=identifier)? (dollarBody | STRING)
    ;

funcParams
    : funcParam (',' funcParam)*
    ;

funcParam
    : identifier identifier?                 // (type) | (name type)
    ;

/* ======================= CREATE TRIGGER ======================= */
createTriggerStatement
    : CREATE TRIGGER trgName=identifier
      (BEFORE | AFTER | INSTEAD OF)? trgEvents
      ON onTable=qualifiedName
      (FOR EACH (ROW | STATEMENT))?
      (WHEN '(' booleanExpr ')')?
      EXECUTE FUNCTION execFunc=qualifiedName '(' argList? ')'
    ;

trgEvents
    : INSERT
    | DELETE
    | UPDATE (OF columnList)?
    | INSERT OR UPDATE OR DELETE
    ;

columnList : identifier (',' identifier)* ;

argList
    : (STRING | INTEGER | identifier) (',' (STRING | INTEGER | identifier))*
    ;

/* ======================= CREATE TYPE … AS ENUM ======================= */
createTypeEnumStatement
    : CREATE TYPE typeName=qualifiedName AS ENUM '(' stringList ')'
    ;

stringList : STRING (',' STRING)* ;

/* ======================= CREATE EXTENSION ======================= */
createExtensionStatement
    : CREATE EXTENSION (IF NOT? EXISTS)? extName=identifier (WITH (SCHEMA identifier)?)?
    ;

/* ======================= CREATE DOMAIN ======================= */
createDomainStatement
    : CREATE DOMAIN domainName=qualifiedName AS baseType=identifier
      (DEFAULT literal)?
      (NOT NULL | NULL)?
    ;

/* ======================= CREATE POLICY (RLS) ======================= */
createPolicyStatement
    : CREATE POLICY polName=identifier ON tbl=qualifiedName
      (FOR (ALL | SELECT | INSERT | UPDATE | DELETE))?
      (TO roleList)?
      (USING '(' booleanExpr ')')?
      (WITH CHECK '(' booleanExpr ')')?
    ;

roleList : identifier (',' identifier)* ;

/* ======================= COMMENT ON ======================= */
commentOnStatement
    : COMMENT ON commentTarget commentName IS (NULL | STRING | dollarBody)
    ;

commentTarget
    : TABLE
    | COLUMN
    | SEQUENCE
    | VIEW
    | MATERIALIZED VIEW
    | TYPE
    | FUNCTION
    | INDEX
    | DOMAIN
    ;

commentName
    : qualifiedName ('.' identifier)?
    ;

/* ======================= Common helpers ======================= */
qualifiedName : identifier ('.' identifier)? ;
identifier    : IDENTIFIER | QUOTED_IDENT ;
literal       : STRING | INTEGER | DECIMAL | TRUE | FALSE | NULL ;
dollarBody    : DOLLAR_BLOCK ;

/* ======================= Keywords ======================= */
CREATE: 'CREATE';
OR: 'OR';
REPLACE: 'REPLACE';
FUNCTION: 'FUNCTION';
PROCEDURE: 'PROCEDURE';
RETURNS: 'RETURNS';
LANGUAGE: 'LANGUAGE';
DO: 'DO';

TRIGGER: 'TRIGGER';
BEFORE: 'BEFORE';
AFTER: 'AFTER';
INSTEAD: 'INSTEAD';
OF: 'OF';
ROW: 'ROW';
STATEMENT: 'STATEMENT';
WHEN: 'WHEN';
EXECUTE: 'EXECUTE';

TYPE: 'TYPE';
ENUM: 'ENUM';
EXTENSION: 'EXTENSION';
DOMAIN: 'DOMAIN';

POLICY: 'POLICY';
FOR: 'FOR';
ALL: 'ALL';
TO: 'TO';
USING: 'USING';
WITH: 'WITH';
CHECK: 'CHECK';

COMMENT: 'COMMENT';
ON: 'ON';
IS: 'IS';

SCHEMA: 'SCHEMA';

TABLE: 'TABLE';
INDEX: 'INDEX';
VIEW: 'VIEW';
MATERIALIZED: 'MATERIALIZED';
COLUMN: 'COLUMN';
UNIQUE: 'UNIQUE';
IF: 'IF';
NOT: 'NOT';
EXISTS: 'EXISTS';
UNLOGGED: 'UNLOGGED';
TEMPORARY: 'TEMPORARY';
TEMP: 'TEMP';
PRIMARY: 'PRIMARY';
KEY: 'KEY';
DEFAULT: 'DEFAULT';
NULL: 'NULL';
REFERENCES: 'REFERENCES';
FOREIGN: 'FOREIGN';
CONSTRAINT: 'CONSTRAINT';

SEQUENCE: 'SEQUENCE';

INSERT: 'INSERT';
UPDATE: 'UPDATE';
DELETE: 'DELETE';
SELECT: 'SELECT';
FROM: 'FROM';
WHERE: 'WHERE';
AS: 'AS';

ASC: 'ASC';
DESC: 'DESC';

JOIN: 'JOIN';
INNER: 'INNER';
LEFT: 'LEFT';
RIGHT: 'RIGHT';
FULL: 'FULL';
CROSS: 'CROSS';

GROUP: 'GROUP';
BY: 'BY';
HAVING: 'HAVING';
ORDER: 'ORDER';

AND: 'AND';
EACH: 'EACH';

TRUE: 'TRUE';
FALSE: 'FALSE';

CASCADE: 'CASCADE';
RESTRICT: 'RESTRICT';
SET: 'SET';
NO: 'NO';
ACTION: 'ACTION';

/* ======================= Operators & punctuation ======================= */
CONCAT : '||';
STAR   : '*';
SLASH  : '/';
PLUS   : '+';
MINUS  : '-';
PERCENT: '%';
CARET  : '^';

/* ======================= Lexical ======================= */
QUOTED_IDENT : '"' (~["\\] | '\\"' | '\\\\')+ '"' ;
IDENTIFIER   : [a-zA-Z_][a-zA-Z_0-9$]* ;
INTEGER      : [0-9]+ ;
DECIMAL      : [0-9]+ '.' [0-9]+ ;
STRING       : '\'' ( ~['\\] | '\\' . )* '\'' ;

// Dollar-quoted body: $$ … $$
DOLLAR_BLOCK : '$$' ( . | '\r' | '\n' )*? '$$' ;

WS           : [ \t\r\n]+ -> skip ;
LINE_COMMENT : '--' ~[\r\n]* -> skip ;
BLOCK_COMMENT: '/*' .*? '*/' -> skip ;