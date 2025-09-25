grammar MySQL;

@header {
package com.example.personalchatbot.service.sql.antlr.mysql;
}

/* ========= LEXER MEMBERS ========= */
@lexer::members {
    private String delimiter = ";";

    @Override
    public org.antlr.v4.runtime.Token nextToken() {
        if (_mode == DEFAULT_MODE && aheadIsDelimiter()) {
            int start = getCharIndex();
            for (int i = 0; i < delimiter.length(); i++) _input.consume();
            org.antlr.v4.runtime.CommonToken t =
                new org.antlr.v4.runtime.CommonToken(MySQLLexer.DELIM, delimiter);
            t.setStartIndex(start);
            t.setStopIndex(getCharIndex() - 1);
            t.setLine(getLine());
            t.setCharPositionInLine(getCharPositionInLine());
            return t;
        }
        return super.nextToken();
    }

    private boolean aheadIsDelimiter() {
        if (delimiter == null || delimiter.isEmpty()) return false;
        for (int i = 0; i < delimiter.length(); i++) {
            int la = _input.LA(1 + i);
            if (la == org.antlr.v4.runtime.IntStream.EOF) return false;
            if ((char) la != delimiter.charAt(i)) return false;
        }
        return true;
    }

    private boolean atLineHeadIgnoringSpaces() {
        int i = -1;
        int ch;
        while (true) {
            ch = _input.LA(i);
            if (ch == org.antlr.v4.runtime.IntStream.EOF) break;
            char c = (char) ch;
            if (c == '\n' || c == '\r') break;
            i--;
        }
        int j = i + 1;
        while (true) {
            ch = _input.LA(j);
            if (ch == org.antlr.v4.runtime.IntStream.EOF) return true;
            if (j == 1) return true;
            char c = (char) ch;
            if (!(c == ' ' || c == '\t')) return false;
            j++;
        }
    }
}

/* ============================== Parser rules ============================== */
sqlStatements : (sqlStatement (DELIM)?)* EOF ;

sqlStatement
    : createSchemaStatement                      #StCreateSchema
    | createDatabaseStatement                    #StCreateDatabase
    | useStatement                               #StUse
    | dropStatement                              #StDrop
    | createTableStatement                       #StCreateTable
    | createIndexStatement                       #StCreateIndex
    | createViewStatement                        #StCreateView
    | createTriggerStatement                     #StCreateTrigger
    | createEventStatement                       #StCreateEvent
    | createFunctionStatement                    #StCreateFunction
    | createProcedureStatement                   #StCreateProcedure
    | selectStmt                                 #StSelect
    | insertStmt                                 #StInsert
    | updateStmt                                 #StUpdate
    | deleteStmt                                 #StDelete
    | setStatement                               #StSet
    | grantStatement                             #StGrant
    | revokeStatement                            #StRevoke
    | createRoleStatement                        #StCreateRole
    | dropRoleStatement                          #StDropRole
    | createUserStatement                        #StCreateUser
    | dropUserStatement                          #StDropUser
    | callStatement                              #StCall
    | unknownStatement                           #StUnknown
    ;

/* ====================== SQL STATEMENTS ====================== */
/* ---------- CREATE SCHEMA ---------- */
createSchemaStatement
    : CREATE SCHEMA qualifiedName
    ;

/* ---------- CREATE DATABASE ---------- */
createDatabaseStatement
    : CREATE DATABASE ifNotExists? identifier (characterSetClause)? (collateClause)?
    ;

characterSetClause
    : CHARACTER SET identifier ;

collateClause
    : COLLATE identifier ;

/* ---------- USE ---------- */
useStatement
    : USE qualifiedName ;

/* ---------- DROP ---------- */
dropStatement
    : DROP (EVENT|TRIGGER|VIEW|PROCEDURE|FUNCTION|TABLE|SCHEMA|DATABASE)
      ifNotExists? junk*
    ;

ifNotExists
    : IF NOT EXISTS ;

/* ---------- CREATE TABLE ---------- */
createTableStatement
    : CREATE (TEMPORARY)? TABLE ifNotExists? tableName=qualifiedName
      ( LPAREN columnDef (COMMA columnDef)* (COMMA tableConstraint)* RPAREN (tableOptions)?
      | LIKE qualifiedName (tableOptions)?
      | AS selectStmt (tableOptions)?
      )
      (PARTITION BY junk+)?
    ;

tableOptions : tableOption+ ;

tableOption
    : ENGINE EQ identifier
    | (DEFAULT)? (CHARACTER SET | CHARSET) (EQ)? identifier
    | COLLATE (EQ)? identifier
    | COMMENT (EQ)? STRING
    | AUTO_INCREMENT (EQ)? INTEGER
    | identifier (EQ identifier)?
    ;

columnDef
    : columnName=identifier dataType columnConstraint* ;

dataType
    : identifier (LPAREN INTEGER (COMMA INTEGER)? RPAREN)? ;

columnConstraint
    : (CONSTRAINT identifier)?
        ( PRIMARY KEY
        | UNIQUE
        | (FOREIGN)? KEY REFERENCES refTable=qualifiedName (LPAREN refColumnList RPAREN)? fkActions?
        | REFERENCES refTable=qualifiedName (LPAREN refColumnList RPAREN)? fkActions?
        | (NOT)? NULLX
        | CHECK LPAREN booleanExpr RPAREN
        )
    ;

tableConstraint
    :   (CONSTRAINT identifier)?
        ( PRIMARY KEY LPAREN idList RPAREN
        | UNIQUE LPAREN idList RPAREN
        | (FOREIGN)? KEY LPAREN idList RPAREN
          REFERENCES refTable=qualifiedName (LPAREN refColumnList RPAREN)? fkActions?
        | CHECK LPAREN booleanExpr RPAREN                    // giữ check
        )
    ;

fkActions
    : ON DELETE fkAction (ON UPDATE fkAction)?
    | ON UPDATE fkAction (ON DELETE fkAction)?
    ;

fkAction
    : RESTRICT | CASCADE | SET NULLX | SET DEFAULT | NO ACTION ;

idList        : identifier (COMMA identifier)* ;

refColumnList : identifier (COMMA identifier)* ;

/* ---------- CREATE INDEX ---------- */
createIndexStatement
    : CREATE (UNIQUE | FULLTEXT | SPATIAL)? INDEX ifNotExists? indexName=qualifiedName
    (USING identifier)?
    ON tableName=qualifiedName
    (USING identifier)?
    LPAREN indexElem (COMMA indexElem)* RPAREN
    whereClause?
    ;

indexElem
    : indexExpr (ASC | DESC)?
    ;

indexExpr
    : identifier (LPAREN INTEGER RPAREN)?
    | LPAREN expr RPAREN
    ;

whereClause : WHERE booleanExpr ;

/* ---------- CREATE VIEW ---------- */
createViewStatement
    : CREATE (OR REPLACE)? (ALGORITHM EQ identifier)? (SQL SECURITY (DEFINER|INVOKER))?
    VIEW viewName=qualifiedName AS selectStmt
    ;

/* ---------- CREATE TRIGGER ---------- */
createTriggerStatement
    : CREATE (DEFINER EQ uid)? TRIGGER trigName=qualifiedName
      (BEFORE | AFTER) (INSERT | UPDATE | DELETE)
      ON tableName=qualifiedName FOR EACH ROW
      junk*
    ;

/* ---------- CREATE EVENT ---------- */
createEventStatement
    : CREATE (DEFINER EQ uid)? EVENT eventName=qualifiedName
      ON SCHEDULE (EVERY | AT) junk*
      (DO junk*)?
    ;

/* ---------- CREATE FUNCTION ---------- */
createFunctionStatement
    : CREATE (OR REPLACE)? FUNCTION funcName=qualifiedName LPAREN funcParams? RPAREN
      (RETURNS typeSpec)?
      routineCharacteristics*
      routineBody
    ;

typeSpec   : qualifiedName (LPAREN INTEGER (COMMA INTEGER)? RPAREN)? ;

/* ---------- CREATE PROCEDURE ---------- */
createProcedureStatement
    : CREATE (OR REPLACE)? PROCEDURE procName=qualifiedName LPAREN funcParams? RPAREN
      routineCharacteristics*
      routineBody
    ;

funcParams : funcParam (COMMA funcParam)* ;

funcParam  : identifier identifier? ;

routineCharacteristics
    : DETERMINISTIC
    | NOT DETERMINISTIC
    | SQL SECURITY (DEFINER | INVOKER)
    | LANGUAGE SQL
    | CONTAINS SQL
    | NO SQL
    | READS SQL DATA
    | MODIFIES SQL DATA
    ;

routineBody
    : BEGIN junk* END
    | DO junk+
    | junk+
    ;

/* ----------------- BASIC STATEMENT ----------------- */

selectStmt : SELECT junk* ;
insertStmt : INSERT junk* ;
updateStmt : UPDATE junk* ;
deleteStmt : DELETE junk* ;

/* ---------- OTHER Statement ---------- */
setStatement        : SET junk+ ;
grantStatement      : GRANT junk+ ;
revokeStatement     : REVOKE junk+ ;
createRoleStatement : CREATE ROLE junk+ ;
dropRoleStatement   : DROP ROLE junk+ ;
createUserStatement : CREATE USER junk+ ;
dropUserStatement   : DROP USER junk+ ;
callStatement       : CALL junk+ ;
unknownStatement    : junk+ ;

/* ----------------------- Helpers ------------------------ */
qualifiedName : identifier (DOT identifier)? ;
identifier    : IDENTIFIER | BACKTICK_ID | DOUBLE_QUOTED_ID ;
literal       : STRING | INTEGER | DECIMAL_LIT | TRUE | FALSE | NULLX ;
uid           : qualifiedName ;

/* ====================== EXPRESSIONS ====================== */
booleanExpr : expr ;

expr
    : expr binaryOp expr
    | functionCall
    | qualifiedName
    | literal
    | LPAREN expr RPAREN
    ;

binaryOp     : CONCAT | PLUS | MINUS | STAR | SLASH | PERCENT | CARET | EQ ;

functionCall : identifier LPAREN (expr (COMMA expr)*)? RPAREN ;

junk             : ~DELIM ;

/* ============================= LEXER ============================= */

/* Fragments (case-insensitive) */
fragment A:[aA]; fragment B:[bB]; fragment C:[cC]; fragment D:[dD]; fragment E:[eE];
fragment F:[fF]; fragment G:[gG]; fragment H:[hH]; fragment I:[iI]; fragment J:[jJ];
fragment K:[kK]; fragment L:[lL]; fragment M:[mM]; fragment N:[nN]; fragment O:[oO];
fragment P:[pP]; fragment Q:[qQ]; fragment R:[rR]; fragment S:[sS]; fragment T:[tT];
fragment U:[uU]; fragment V:[vV]; fragment W:[wW]; fragment X:[xX]; fragment Y:[yY];
fragment Z:[zZ];

/* DELIMITER directive */
DELIM_DIRECTIVE
    : [ \t]* D E L I M I T E R [ \t]* (~[\r\n])+
    {
      String txt = getText();
      int off = txt.toUpperCase().indexOf("DELIMITER") + 9;
      String rest = txt.length() > off ? txt.substring(off).trim() : "";
      this.delimiter = rest.isEmpty() ? ";" : rest;
    }
    -> skip
    ;

/* Keywords */
CREATE      : C R E A T E;
OR          : O R;
REPLACE     : R E P L A C E;
SCHEMA      : S C H E M A;
DATABASE    : D A T A B A S E;
USE         : U S E;
DROP        : D R O P;
TABLE       : T A B L E;
TEMPORARY   : T E M P O R A R Y;
IF          : I F;
NOT         : N O T;
EXISTS      : E X I S T S;
PRIMARY     : P R I M A R Y;
KEY         : K E Y;
UNIQUE      : U N I Q U E;
FOREIGN     : F O R E I G N;
REFERENCES  : R E F E R E N C E S;
CONSTRAINT  : C O N S T R A I N T;
CHECK       : C H E C K;
AS          : A S;
DATA        : D A T A;
INDEX       : I N D E X;
WHERE       : W H E R E;
VIEW        : V I E W;
SELECT      : S E L E C T;
INSERT      : I N S E R T;
UPDATE      : U P D A T E;
DELETE      : D E L E T E;
FROM        : F R O M;
INTO        : I N T O;
VALUES      : V A L U E S;
SET         : S E T;
NULLX       : N U L L;
JOIN        : J O I N;
LEFT        : L E F T;
RIGHT       : R I G H T;
CROSS       : C R O S S;
GROUP       : G R O U P;
BY          : B Y;
HAVING      : H A V I N G;
ORDER       : O R D E R;
AND         : A N D;
IS          : I S;
FUNCTION    : F U N C T I O N;
PROCEDURE   : P R O C E D U R E;
RETURNS     : R E T U R N S;
TRUE        : T R U E;
FALSE       : F A L S E;
TRIGGER     : T R I G G E R;
EVENT       : E V E N T;
BEFORE      : B E F O R E;
AFTER       : A F T E R;
ON          : O N;
FOR         : F O R;
EACH        : E A C H;
ROW         : R O W;
SCHEDULE    : S C H E D U L E;
EVERY       : E V E R Y;
AT          : A T;
STARTS      : S T A R T S;
ENDS        : E N D S;
DO          : D O;
BEGIN       : B E G I N;
END         : E N D;
DEFINER     : D E F I N E R;
INVOKER     : I N V O K E R;
LANGUAGE    : L A N G U A G E;
SECURITY    : S E C U R I T Y;
CONTAINS    : C O N T A I N S;
READS       : R E A D S;
MODIFIES    : M O D I F I E S;
SQL         : S Q L;
NO          : N O;
ENGINE      : E N G I N E;
DEFAULT     : D E F A U L T;
DETERMINISTIC: D E T E R M I N I S T I C;
CHARACTER   : C H A R A C T E R;
COLLATE     : C O L L A T E;
COMMENT     : C O M M E N T;
RESTRICT    : R E S T R I C T;
CASCADE     : C A S C A D E;
ACTION      : A C T I O N;
AUTO_INCREMENT: A U T O '_'? I N C R E M E N T;
ASC         : A S C;
DESC        : D E S C;
CHARSET     : C H A R S E T;
USING       : U S I N G;
LIKE        : L I K E;
PARTITION   : P A R T I T I O N;
ROLE        : R O L E;
USER        : U S E R;
GRANT       : G R A N T;
REVOKE      : R E V O K E;
CALL        : C A L L;
FULLTEXT    : F U L L T E X T;
SPATIAL     : S P A T I A L;
ALGORITHM   : A L G O R I T H M;

// ======= Operators & symbols =======
CONCAT : '||';
STAR   : '*' ;
SLASH  : '/' ;
PLUS   : '+' ;
MINUS  : '-' ;
PERCENT: '%' ;
CARET  : '^' ;
LPAREN : '(' ;
RPAREN : ')' ;
COMMA  : ',' ;
DOT    : '.' ;
EQ     : '=' ;

/* Identifiers & literals */
BACKTICK_ID      : '`' ( '``' | ~[`\r\n] )* '`' ;

DOUBLE_QUOTED_ID : '"' ( '""' | ~["\r\n] )* '"' ;

IDENTIFIER
  : [A-Za-z_]
    ( { !aheadIsDelimiter() }? [A-Za-z_0-9$] )*
  ;

// ======= Numbers and String =======
INTEGER          : [0-9]+ ;
DECIMAL_LIT      : [0-9]+ '.' [0-9]+ ;
STRING           : '\'' ( ~['\\] | '\\' . )* '\'' ;

/* Comments & whitespace */
LINE_COMMENT  : '--' ~[\r\n]* -> skip ;
HASH_COMMENT  : '#'  ~[\r\n]* -> skip ;
BLOCK_COMMENT : '/*' .*? '*/' -> skip ;
WS            : [ \t\r\n]+ -> skip ;

/* Dummy DELIM*/
DELIM : '§§DUMMY_DELIM§§' ;