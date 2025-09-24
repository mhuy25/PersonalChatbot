grammar PostgreSQL;

@header {
package com.example.personalchatbot.service.sql.antlr.postgres;
}

/* ============================== Parser rules ============================== */
sqlStatements
    : (sqlStatement semi?)* EOF
    ;

semi  : SEMI ;

sqlStatement
    : createSchemaStatement
    | createTableStatement
    | createIndexStatement
    | createViewStatement
    | createMaterializedViewStatement
    | createFunctionStatement
    | createProcedureStatement
    | doStatement
    | createTriggerStatement
    | createTypeEnumStatement
    | createTypeCompositeStatement
    | createExtensionStatement
    | createDomainStatement
    | createSequenceStatement
    | createPolicyStatement
    | commentOnStatement
    | otherStatement
    ;

/* ====================== SQL STATEMENTS ====================== */
/* ---------- CREATE SCHEMA ---------- */
createSchemaStatement
    : CREATE SCHEMA (IF NOT EXISTS)?
      ( qualifiedName (AUTHORIZATION identifier)?
    | AUTHORIZATION identifier )
    ;

/* ---------- CREATE TABLE ---------- */
createTableStatement
    : CREATE (TEMPORARY | TEMP)? UNLOGGED? TABLE (IF NOT EXISTS)? tableName=qualifiedName
      (
        // dạng định nghĩa cột
        '(' tableElement (',' tableElement)* ')'
        ( tableWithClause | tableTablespaceClause | tablePartitionClause | inheritsClause )*
      |
        // dạng PARTITION OF (không nhất thiết có cột)
        PARTITION OF parent=qualifiedName partitionBounds?
        ( tableWithClause | tableTablespaceClause )*
      )
    ;

inheritsClause
    : INHERITS parens
    ;

tablePartitionClause
    : PARTITION BY identifier parens
    ;

partitionBounds
    : DEFAULT
    | FOR VALUES ( FROM parens TO parens | IN parens | WITH parens )
    ;

tableWithClause
    : WITH '(' ( . )*? ')';

tableTablespaceClause
    : TABLESPACE identifier;

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

columnConstraint
    : NULL
    | NOT NULL
    | DEFAULT expr
    | CHECK parens
    | PRIMARY KEY
    | UNIQUE
    | GENERATED (ALWAYS | BY DEFAULT) AS IDENTITY identityOptions?
    | GENERATED (ALWAYS | BY DEFAULT)? AS parens STORED
    | REFERENCES refTable=qualifiedName ('(' refColumnList ')')? refConstraintTail?
    ;

identityOptions
    : '(' ( . )*? ')'
    ;

tableConstraint
    : (CONSTRAINT identifier)? (
        PRIMARY KEY '(' columnNameList ')'
      | UNIQUE '(' columnNameList ')'
      | FOREIGN KEY '(' columnNameList ')'
          REFERENCES refTable=qualifiedName ('(' refColumnList ')')? refConstraintTail?
      | EXCLUDE USING identifier parens (WHERE parens)?
      | CHECK parens
      | CONSTRAINT identifier CHECK parens
      )
    ;

matchClause     : MATCH ( FULL | SIMPLE | PARTIAL ) ;

fkActions
    : (ON DELETE fkAction (ON UPDATE fkAction)?)
    | (ON UPDATE fkAction (ON DELETE fkAction)?)
    ;

deferrableClause: ( NOT )? DEFERRABLE ( INITIALLY ( DEFERRED | IMMEDIATE ) )? ;

notValidClause  : NOT VALID ;

fkAction
    : CASCADE
    | RESTRICT
    | NO ACTION
    | SET NULL
    | SET DEFAULT
    ;

refConstraintTail
    : ( matchClause | fkActions | deferrableClause | notValidClause )+
    ;

refColumnList   : identifier (',' identifier)* ;

columnNameList  : identifier (',' identifier)* ;

/* ---------- CREATE INDEX ---------- */
createIndexStatement
    : CREATE UNIQUE? (CONCURRENTLY)? INDEX (IF NOT EXISTS)? indexName=qualifiedName
      ON (ONLY)? tableName=qualifiedName
      usingMethod? '(' indexElem (',' indexElem)* ')'
      includeClause?
      whereClause?
    ;

includeClause : INCLUDE '(' identifier (',' identifier)* ')' ;

usingMethod : USING identifier ;

indexElem
    : exprOrParenExpr collateClause? opclassClause? nullsOrder? sortOrder?
    ;

sortOrder   : ASC | DESC ;

collateClause  : COLLATE qualifiedName ;

opclassClause  : qualifiedName ;

nullsOrder   : NULLS ( FIRST | LAST ) ;

whereClause : WHERE booleanExpr ;

/* ---------- CREATE VIEW ---------- */
createViewStatement
    : CREATE (OR REPLACE)? VIEW viewName=qualifiedName
      (WITH '(' ( . )*? ')')?
      AS selectStmt
      (WITH (CASCADED | LOCAL)? CHECK OPTION)?
      (WITH (NO)? DATA)?
    ;

/* ---------- CREATE MATERIALIZED VIEW ---------- */
createMaterializedViewStatement
    : CREATE MATERIALIZED VIEW viewName=qualifiedName
      (WITH '(' ( . )*? ')')?
      AS selectStmt
      (WITH (NO)? DATA)?
    ;

/* ======================= CREATE FUNCTION / PROCEDURE / DO ======================= */
/* ---------- CREATE FUNCTION ---------- */
createFunctionStatement
    : CREATE (OR REPLACE)? FUNCTION funcName=qualifiedName '(' funcParams? ')'
      RETURNS returnsType
      functionTail
    ;

returnsType
    : SETOF? qualifiedName
    | TABLE '(' funcParam (',' funcParam)* ')'
    | TRIGGER
    | EVENT_TRIGGER
    | VOID
    ;

funcParams
    : funcParam (',' funcParam)*
    ;

funcParam
    : identifier identifier?
    ;

functionTail
    : (funcOption)* functionBodyOrLang
    ;

funcOption
    : IMMUTABLE
    | STABLE
    | VOLATILE
    | STRICT
    | SECURITY ( INVOKER | DEFINER )
    | PARALLEL ( SAFE | RESTRICTED | UNSAFE )
    ;

funcLang : LANGUAGE lang=identifier ;

funcBody : AS (dollarBody | STRING) ;

functionBodyOrLang
    : funcLang funcBody?
    | funcBody funcLang?
    ;

/* ---------- CREATE PROCEDURE ---------- */
createProcedureStatement
    : CREATE (OR REPLACE)? PROCEDURE procName=qualifiedName '(' funcParams? ')'
    procedureTail
    ;

procLang : LANGUAGE lang=identifier ;

procBody : AS (dollarBody | STRING) ;

procedureBodyOrLang
    : procLang procBody?
    | procBody procLang?
    ;

procedureTail
    : procedureBodyOrLang
    ;

/* ---------- DO ---------- */
doStatement
    : DO (LANGUAGE lang=identifier)? (dollarBody | STRING)
    ;

/* ---------- CREATE TRIGGER ---------- */
createTriggerStatement
    : CREATE TRIGGER trgName=identifier
      (CONSTRAINT)?
      (BEFORE | AFTER | INSTEAD OF)? trgEvents
      ON onTable=qualifiedName
      (REFERENCING ( . )*? )?
      (DEFERRABLE ( INITIALLY ( DEFERRED | IMMEDIATE ) )?)?
      (FOR EACH (ROW | STATEMENT))?
      (WHEN '(' booleanExpr ')')?
      EXECUTE (FUNCTION | PROCEDURE) execFunc=qualifiedName '(' argList? ')'
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

/* ---------- CREATE TYPE ---------- */
createTypeEnumStatement
    : CREATE TYPE (IF NOT EXISTS)? typeName=qualifiedName AS ENUM '(' stringList ')'
    ;

createTypeCompositeStatement
    : CREATE TYPE (IF NOT EXISTS)? typeName=qualifiedName AS '(' compField (',' compField)* ')'
    ;

compField : identifier dataType ;

stringList : STRING (',' STRING)* ;

/* ---------- CREATE EXTENSION ---------- */
createExtensionStatement
    : CREATE EXTENSION (IF NOT EXISTS)? extName=identifier (WITH (SCHEMA identifier)?)?
    ;

/* ---------- CREATE DOMAIN ---------- */
createDomainStatement
    : CREATE DOMAIN domainName=qualifiedName AS baseType=qualifiedName
      domainConstraint*
    ;

domainConstraint
    : DEFAULT expr
    | NOT NULL
    | NULL
    | CONSTRAINT identifier CHECK parens
    | CHECK parens
    ;

/* ---------- CREATE SEQUENCE ---------- */
createSequenceStatement
    : CREATE SEQUENCE (IF NOT EXISTS)? seqName=qualifiedName ( ~SEMI )*
    ;

/* ---------- CREATE POLICY ---------- */
createPolicyStatement
    : CREATE POLICY polName=identifier ON tbl=qualifiedName
      (FOR (ALL | SELECT | INSERT | UPDATE | DELETE))?
      (TO roleList)?
      (USING '(' booleanExpr ')')?
      (WITH CHECK '(' booleanExpr ')')?
    ;

roleList : identifier (',' identifier)* ;

/* ---------- COMMENT ON ---------- */
commentOnStatement
    : COMMENT ON
        ( CONSTRAINT consName=identifier ON tbl=qualifiedName
        | SCHEMA sch=qualifiedName
        | commentTarget commentName
        )
      IS (NULL | STRING | dollarBody)
    ;

commentTarget
    : TABLE | COLUMN | SEQUENCE | VIEW | MATERIALIZED VIEW | TYPE | FUNCTION | INDEX | DOMAIN
    ;

commentName
    : ( FUNCTION )? functionNameWithSig
    | qualifiedName ('.' identifier)?
    ;

functionNameWithSig
    : qualifiedName '(' commentTypeList? ')'
    ;

commentTypeList
    : commentType (',' commentType)*
    ;

commentType
    : (IN | OUT | INOUT)? qualifiedName (arrayType)? (typeModifiers)?
    ;

/* ---------- SELECT ---------- */
selectStmt
    : SELECT selectList fromClause? whereClause? groupByClause? havingClause? orderByClause?
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
    : qualifiedName (identifier)?
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

/* ---------- OTHERS ---------- */
otherStatement
    : ( ~SEMI )+
    ;

/* ====================== EXPRESSIONS ====================== */
booleanExpr : orExpr ;
orExpr      : andExpr (OR andExpr)* ;
andExpr     : notExpr (AND notExpr)* ;
notExpr     : NOT notExpr | predicate ;

predicate
    : expr compareOp expr
    | expr IS NOT? NULL
    | '(' booleanExpr ')'
    ;

compareOp : '=' | '!=' | '<>' | '<' | '<=' | '>' | '>=' ;

expr
    : postfixExpr (binaryOp postfixExpr)*
    ;

postfixExpr
    : primary ( '.' identifier )*
    ;

primary
    : functionCall
    | qualifiedName
    | literal
    | '(' expr ')'
    ;

binaryOp
    : CONCAT
    | DBL_COLON
    | '+' | '-' | '*' | '/' | '%' | '^'
    ;

exprOrParenExpr
    : expr
    | '(' expr ')'
    ;

functionCall   : qualifiedName '(' (expr (',' expr)*)? ')' ;

/* ====================== DATATYPE ====================== */
dataType : qualifiedName typeModifiers? arrayType? ;

typeModifiers : '(' INTEGER (',' INTEGER)* ')' ;

arrayType : '[' ']' ('[' ']')* ;

/* ==================== Common helpers ==================== */
qualifiedName : identifier ('.' identifier)? ;
identifier    : IDENTIFIER | QUOTED_IDENT ;
literal       : STRING | INTEGER | DECIMAL | TRUE | FALSE | NULL ;
dollarBody    : DOLLAR_BLOCK ;
parens
    : '(' ( parens | ~( '(' | ')' ) )* ')'
    ;

/* ============================== Lexer rules ============================== */
/* ======================= Keywords ======================= */
CREATE      : 'CREATE';
OR          : 'OR';
REPLACE     : 'REPLACE';
FUNCTION    : 'FUNCTION';
PROCEDURE   : 'PROCEDURE';
RETURNS     : 'RETURNS';
LANGUAGE    : 'LANGUAGE';
DO          : 'DO';
TRIGGER     : 'TRIGGER';
BEFORE      : 'BEFORE';
AFTER       : 'AFTER';
INSTEAD     : 'INSTEAD';
OF          : 'OF';
ROW         : 'ROW';
STATEMENT   : 'STATEMENT';
WHEN        : 'WHEN';
EXECUTE     : 'EXECUTE';
TYPE        : 'TYPE';
ENUM        : 'ENUM';
EXTENSION   : 'EXTENSION';
DOMAIN      : 'DOMAIN';
POLICY      : 'POLICY';
FOR         : 'FOR';
ALL         : 'ALL';
TO          : 'TO';
USING       : 'USING';
WITH        : 'WITH';
CHECK       : 'CHECK';
COMMENT     : 'COMMENT';
ON          : 'ON';
IS          : 'IS';
SCHEMA      : 'SCHEMA';
TABLE       : 'TABLE';
INDEX       : 'INDEX';
VIEW        : 'VIEW';
MATERIALIZED: 'MATERIALIZED';
COLUMN      : 'COLUMN';
UNIQUE      : 'UNIQUE';
IF          : 'IF';
NOT         : 'NOT';
EXISTS      : 'EXISTS';
UNLOGGED    : 'UNLOGGED';
TEMPORARY   : 'TEMPORARY';
TEMP        : 'TEMP';
PRIMARY     : 'PRIMARY';
KEY         : 'KEY';
DEFAULT     : 'DEFAULT';
NULL        : 'NULL';
REFERENCES  : 'REFERENCES';
FOREIGN     : 'FOREIGN';
CONSTRAINT  : 'CONSTRAINT';
SEQUENCE    : 'SEQUENCE';
INSERT      : 'INSERT';
UPDATE      : 'UPDATE';
DELETE      : 'DELETE';
SELECT      : 'SELECT';
FROM        : 'FROM';
WHERE       : 'WHERE';
AS          : 'AS';
ASC         : 'ASC';
DESC        : 'DESC';
JOIN        : 'JOIN';
INNER       : 'INNER';
LEFT        : 'LEFT';
RIGHT       : 'RIGHT';
FULL        : 'FULL';
CROSS       : 'CROSS';
GROUP       : 'GROUP';
BY          : 'BY';
HAVING      : 'HAVING';
ORDER       : 'ORDER';
AND         : 'AND';
EACH        : 'EACH';
TRUE        : 'TRUE';
FALSE       : 'FALSE';
CASCADE     : 'CASCADE';
RESTRICT    : 'RESTRICT';
SET         : 'SET';
NO          : 'NO';
ACTION      : 'ACTION';
COLLATE     : 'COLLATE';
NULLS       : 'NULLS';
FIRST       : 'FIRST';
LAST        : 'LAST';
IMMUTABLE   : 'IMMUTABLE';
STABLE      : 'STABLE';
VOLATILE    : 'VOLATILE';
STRICT      : 'STRICT';
SECURITY    : 'SECURITY';
INVOKER     : 'INVOKER';
DEFINER     : 'DEFINER';
PARALLEL    : 'PARALLEL';
SAFE        : 'SAFE';
RESTRICTED  : 'RESTRICTED';
UNSAFE      : 'UNSAFE';
DEFERRABLE  : 'DEFERRABLE';
DEFERRED    : 'DEFERRED';
IMMEDIATE   : 'IMMEDIATE';
MATCH       : 'MATCH';
SIMPLE      : 'SIMPLE';
PARTIAL     : 'PARTIAL';
VALID       : 'VALID';
INITIALLY   : 'INITIALLY';
INCLUDE     : 'INCLUDE';
SETOF       : 'SETOF';
IN          : 'IN';
OUT         : 'OUT';
INOUT       : 'INOUT';
GENERATED   : 'GENERATED';
ALWAYS      : 'ALWAYS';
IDENTITY    : 'IDENTITY';
CONCURRENTLY: 'CONCURRENTLY';
TABLESPACE  : 'TABLESPACE';
PARTITION   : 'PARTITION';
CASCADED    : 'CASCADED';
LOCAL       : 'LOCAL';
OPTION      : 'OPTION';
VOID        : 'VOID';
DATA        : 'DATA';
ONLY        : 'ONLY';
STORED      : 'STORED';
EXCLUDE     : 'EXCLUDE';
INHERITS    : 'INHERITS';
VALUES      : 'VALUES';
REFERENCING : 'REFERENCING';
EVENT_TRIGGER : 'EVENT_TRIGGER';
AUTHORIZATION : 'AUTHORIZATION';

/* ======================= Operators & punctuation ======================= */
CONCAT  : '||';
STAR    : '*' ;
SLASH   : '/' ;
PLUS    : '+' ;
MINUS   : '-' ;
PERCENT : '%' ;
CARET   : '^' ;
SEMI    : ';' ;
DBL_COLON   : '::' ;
TILDE_STAR  : '~*' ;
COLON   : ':' ;
TILDE   : '~' ;
ANDAND      : '&&' ;

/* ======================= Lexical ======================= */
QUOTED_IDENT : '"' (~["\\] | '\\"' | '\\\\')+ '"' ;
IDENTIFIER   : [a-zA-Z_][a-zA-Z_0-9$]* ;
INTEGER      : [0-9]+ ;
DECIMAL      : [0-9]+ '.' [0-9]+ ;
STRING       : '\'' ( ~['\\] | '\\' . )* '\'' ;

// Dollar-quoted body: hỗ trợ cả $$…$$ và $tag$…$tag$
DOLLAR_BLOCK
    : '$$' ( . | '\r' | '\n' )*? '$$'
    | '$' [a-zA-Z_][a-zA-Z_0-9$]* '$' ( . | '\r' | '\n' )*? '$' [a-zA-Z_][a-zA-Z_0-9$]* '$'
    ;

/* Bỏ qua khoảng trắng & comment */
WS           : [ \t\r\n]+ -> skip ;
LINE_COMMENT : '--' ~[\r\n]* -> skip ;
BLOCK_COMMENT: '/*' .*? '*/' -> skip ;