grammar OracleSQL;

@header {
package com.example.personalchatbot.service.sql.antlr.oracle;
}

/* ---- Forward token declarations to avoid 'implicit definition' warnings ---- */
tokens { SQLPLUS_SLASH }

/* ===== Lexer helpers ===== */
@lexer::members {
  private boolean atStartOfLine() {
    int prev = _input.LA(-1);
    return prev == '\n' || prev == '\r' || getCharPositionInLine() == 0;
  }
}

/* ================= PARSER (tolerant) ================= */

sqlStatements
    : (sqlStatement terminator?)* EOF
    ;

terminator : SEMI | SQLPLUS_SLASH ;

sqlStatement
    : createSchemaStatement                      #StCreateSchema
    | createTableStatement                       #StCreateTable
    | createIndexStatement                       #StCreateIndex
    | createViewStatement                        #StCreateView
    | createFunctionStatement                    #StCreateFunction
    | createProcedureStatement                   #StCreateProcedure
    | dropStatement                              #StDrop
    | selectStmt                                 #StSelect
    | insertStmt                                 #StInsert
    | updateStmt                                 #StUpdate
    | deleteStmt                                 #StDelete
    | unknownStatement                           #StUnknown
    ;

/* -------- CREATE -------- */

createSchemaStatement : CREATE USER identifier ;

createTableStatement
    : CREATE (GLOBAL TEMPORARY | PRIVATE TEMPORARY)? TABLE tableName=qualifiedName
      LPAREN columnDef (COMMA columnDef)* (COMMA tableConstraint)* RPAREN
      tableOptions                      // <- không dùng dấu ? vì tableOptions đã có thể rỗng
    ;

tableOptions
    : (ORGANIZATION HEAP
      | ORGANIZATION INDEX
      | NOPARALLEL
      | PARALLEL INTEGER
      )*
    ;

columnDef : columnName=identifier dataType columnConstraint* ;
dataType  : identifier (LPAREN INTEGER (COMMA INTEGER)? RPAREN)? ;

columnConstraint
    : (CONSTRAINT identifier)?
      ( PRIMARY KEY
      | UNIQUE
      | (NOT)? NULLX
      | REFERENCES refTable=qualifiedName (LPAREN refColumnList RPAREN)?
      | CHECK LPAREN booleanExpr RPAREN
      )
    ;

tableConstraint
    : (CONSTRAINT identifier)?
      ( PRIMARY KEY LPAREN idList RPAREN
      | UNIQUE LPAREN idList RPAREN
      | FOREIGN KEY LPAREN idList RPAREN REFERENCES refTable=qualifiedName (LPAREN refColumnList RPAREN)?
      )
    ;

idList        : identifier (COMMA identifier)* ;
refColumnList : identifier (COMMA identifier)* ;

createIndexStatement
    : CREATE (UNIQUE)? INDEX indexName=qualifiedName
      ON tableName=qualifiedName LPAREN indexElem (COMMA indexElem)* RPAREN
      whereClause?
    ;
indexElem   : identifier (ASC | DESC)? ;
whereClause : WHERE booleanExpr ;

createViewStatement
    : CREATE (OR REPLACE)? VIEW viewName=qualifiedName AS selectStmt
    ;

createFunctionStatement
    : CREATE (OR REPLACE)? FUNCTION funcName=qualifiedName LPAREN funcParams? RPAREN
      (RETURN typeSpec)?
      (DETERMINISTIC)?
      (IS | AS) routineBody
    ;

createProcedureStatement
    : CREATE (OR REPLACE)? PROCEDURE procName=qualifiedName LPAREN funcParams? RPAREN
      (IS | AS) routineBody
    ;

funcParams : funcParam (COMMA funcParam)* ;
funcParam  : identifier identifier? ;

typeSpec   : qualifiedName (LPAREN INTEGER (COMMA INTEGER)? RPAREN)? ;

routineBody
    : BEGIN junk* END (SEMI)?
    | junk+
    ;

/* -------- DROP -------- */

dropStatement
    : DROP
      ( TABLE
      | VIEW
      | INDEX
      | SEQUENCE
      | TRIGGER
      | FUNCTION
      | PROCEDURE
      | PACKAGE
      | SYNONYM
      | MATERIALIZED VIEW
      | USER
      )
      junk*
    ;

/* -------- DML (tolerant) -------- */

selectStmt : SELECT junk* ;
insertStmt : INSERT junk* ;
updateStmt : UPDATE junk* ;
deleteStmt : DELETE junk* ;

/* -------- Helpers -------- */

qualifiedName : identifier (DOT identifier)* ;
identifier    : IDENTIFIER | DOUBLE_QUOTED_ID ;
literal       : STRING | INTEGER | DECIMAL_LIT | TRUE | FALSE | NULLX ;

booleanExpr : expr ;
expr
    : expr binaryOp expr
    | functionCall
    | qualifiedName
    | literal
    | LPAREN expr RPAREN
    ;
binaryOp     : CONCAT | PLUS | MINUS | STAR | SLASH | PERCENT | CARET | EQ | LT | LTE | GT | GTE | NEQ ;
functionCall : identifier LPAREN (expr (COMMA expr)*)? RPAREN ;

junk             : ~SEMI ;
unknownStatement : junk+ ;

/* ================= LEXER ================= */

/* case-insensitive fragments */
fragment A:[aA]; fragment B:[bB]; fragment C:[cC]; fragment D:[dD]; fragment E:[eE];
fragment F:[fF]; fragment G:[gG]; fragment H:[hH]; fragment I:[iI]; fragment J:[jJ];
fragment K:[kK]; fragment L:[lL]; fragment M:[mM]; fragment N:[nN]; fragment O:[oO];
fragment P:[pP]; fragment Q:[qQ]; fragment R:[rR]; fragment S:[sS]; fragment T:[tT];
fragment U:[uU]; fragment V:[vV]; fragment W:[wW]; fragment X:[xX]; fragment Y:[yY];
fragment Z:[zZ];

/* keywords */
CREATE: C R E A T E;
OR: O R;
REPLACE: R E P L A C E;
USER: U S E R;
GLOBAL: G L O B A L;
PRIVATE: P R I V A T E;
TEMPORARY: T E M P O R A R Y;
TABLE: T A B L E;
PRIMARY: P R I M A R Y;
KEY: K E Y;
UNIQUE: U N I Q U E;
FOREIGN: F O R E I G N;
REFERENCES: R E F E R E N C E S;
CHECK: C H E C K;
CONSTRAINT: C O N S T R A I N T;   // <— thêm
NOT: N O T;                       // <— thêm
VIEW: V I E W;
INDEX: I N D E X;
SEQUENCE: S E Q U E N C E;
TRIGGER: T R I G G E R;
PACKAGE: P A C K A G E;
SYNONYM: S Y N O N Y M;
MATERIALIZED: M A T E R I A L I Z E D;
ON: O N;
WHERE: W H E R E;
ASC: A S C;
DESC: D E S C;
FUNCTION: F U N C T I O N;
PROCEDURE: P R O C E D U R E;
RETURN: R E T U R N;
RETURNS: R E T U R N S;
IS: I S;
AS: A S;
BEGIN: B E G I N;
END: E N D;
DETERMINISTIC: D E T E R M I N I S T I C;
ORGANIZATION: O R G A N I Z A T I O N;
HEAP: H E A P;
PARALLEL: P A R A L L E L;
NOPARALLEL: N O P A R A L L E L;

DROP: D R O P;

SELECT: S E L E C T;
INSERT: I N S E R T;
UPDATE: U P D A T E;
DELETE: D E L E T E;
NULLX: N U L L;
TRUE: T R U E;
FALSE: F A L S E;

/* ops & punctuation */
CONCAT : '||';
STAR   : '*';
SLASH  : '/';
PLUS   : '+';
MINUS  : '-';
PERCENT: '%';
CARET  : '^';
LT     : '<';
GT     : '>';
LTE    : '<=';
GTE    : '>=';
NEQ    : '!=' | '<>';
LPAREN : '(';
RPAREN : ')';
COMMA  : ',';
DOT    : '.';
EQ     : '=' ;
SEMI   : ';' ;

/* sqlplus "/" line as terminator */
SQLPLUS_SLASH
 : {atStartOfLine()}? '/' [ \t]* -> type(SEMI)
 ;

/* identifiers & literals */
DOUBLE_QUOTED_ID : '"' ( '""' | ~["\r\n] )* '"' ;
IDENTIFIER       : [A-Za-z_][A-Za-z_0-9$]* ;
INTEGER          : [0-9]+ ;
DECIMAL_LIT      : [0-9]+ '.' [0-9]+ ;
STRING           : '\'' ( ~['\\] | '\\' . )* '\'' ;

/* comments & ws */
LINE_COMMENT  : '--' ~[\r\n]* -> skip ;
BLOCK_COMMENT : '/*' .*? '*/' -> skip ;
WS            : [ \t\r\n]+ -> skip ;