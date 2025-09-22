grammar SqlServer;

@header {
package com.example.personalchatbot.service.sql.antlr.sqlserver;
import org.antlr.v4.runtime.IntStream;
}

@lexer::members {
  private boolean atLineStartAfterWs() {
    int i = -1, la = _input.LA(i);
    while (la == ' ' || la == '\t') { i--; la = _input.LA(i); }
    return la == '\n' || la == '\r' || la == IntStream.EOF;
  }
}

/* ============================== Parser rules ============================== */
/* Root: child nodes are either a statement or standalone GO */
script          : (topStmt | goOnly)+ EOF ;
sqlStatements   : script ;

topStmt
  : createProcedureStatement                           #TopProc
  | createFunctionStatement                            #TopFunc
  | createViewStatement                                #TopView
  | createTriggerStatement                             #TopTrig
  | s=sqlStatement SEMI* goTerminator*                 #TopWithTerminator
  | s=sqlStatement SEMI*                               #TopNoGo
  ;

goOnly          : goTerminator+                        #TopGoOnly ;

/* ---------- Terminators & opaque “blob” helpers ---------- */
terminator      : SEMI | GO_STMT ;
stopBeforeTerminator
  : { _input.LA(1) == SEMI || _input.LA(1) == GO_STMT || _input.LA(1) == IntStream.EOF }?
  ;
/** Consume everything until just before GO or EOF (not including them). */
blobUntilGoOrEof
  : ( { _input.LA(1) != GO_STMT && _input.LA(1) != IntStream.EOF }? . )+
  ;
/** Blob with balanced right parenthesis. */
blobUntilRparen : ( LPAREN blobUntilRparen RPAREN | ~RPAREN )* ;

/* ---------- Common identifiers / qualified names ---------- */
schemaQualifiedName : (schemaName DOT)? objectName ;
schemaName          : BRACKET_ID | DOUBLE_QUOTED_ID | IDENTIFIER ;
objectName          : BRACKET_ID | DOUBLE_QUOTED_ID | IDENTIFIER ;
identifier          : BRACKET_ID | DOUBLE_QUOTED_ID | IDENTIFIER | AT_ID ;
qualifiedName       : identifier (DOT identifier)? ;

/* ============================== Statement union ============================== */
sqlStatement
  : createDatabaseStatement
  | useStatement
  | dropProcedureStatement
  | dropStatement
  | createSchemaStatement
  | createTypeStatement
  | createSequenceStatement
  | createTableStatement
  | alterTableStatement
  | createIndexStatement
  | alterIndexStatement
  | alterDatabaseStatement
  | dropIndexStatement
  | createSynonymStatement
  | setStatement
  | execStatement
  | throwStatement
  | printStatement
  | ifStatement
  | mergeStmt
  | selectStmt
  | insertStmt
  | updateStmt
  | deleteStmt
  | beginTranStmt
  | commitTranStmt
  | rollbackTranStmt
  | unknownStatement
  ;

/* ============================== DDL basics ============================== */
createDatabaseStatement : CREATE DATABASE (BRACKET_ID | IDENTIFIER) ;
useStatement            : USE (BRACKET_ID | IDENTIFIER) ;
dropStatement           : DROP (DATABASE | TABLE | VIEW | FUNCTION | PROCEDURE | TRIGGER | SEQUENCE | TYPE | SYNONYM) schemaQualifiedName ;
createSchemaStatement   : CREATE SCHEMA schemaName (AUTHORIZATION identifier)? ;

createTypeStatement
  : CREATE TYPE schemaQualifiedName FROM dataType (NOT NULLX | NULLX)?
  | CREATE TYPE schemaQualifiedName AS TABLE LPAREN tableTypeColumnDef (COMMA tableTypeColumnDef)* RPAREN
  ;
tableTypeColumnDef      : identifier dataType columnConstraintBody* ;

createSequenceStatement : CREATE SEQUENCE schemaQualifiedName sequenceOptions? ;
sequenceOptions         : sequenceOption+ ;
sequenceOption
  : AS dataType
  | START WITH INTEGER
  | INCREMENT BY INTEGER
  | MINVALUE INTEGER
  | MAXVALUE INTEGER
  | NO MAXVALUE
  | CACHE INTEGER
  | NO CACHE
  | CYCLE
  | NO CYCLE
  ;

/* ---------- CREATE TABLE ---------- */
createTableStatement
  : CREATE TABLE schemaQualifiedName LPAREN tableElement (COMMA tableElement)* RPAREN tableOptions?
  ;
tableElement
  : columnDef
  | tableConstraint
  ;
tableConstraint
  : (CONSTRAINT identifier)? (
      PRIMARY KEY LPAREN idList RPAREN
    | UNIQUE LPAREN idList RPAREN
    | FOREIGN KEY LPAREN idList RPAREN REFERENCES refTable=qualifiedName (LPAREN refColumnList RPAREN)?
    | CHECK LPAREN booleanExpr RPAREN
    )
  ;
idList           : identifier (COMMA identifier)* ;
refColumnList    : identifier (COMMA identifier)* ;

columnDef
  : identifier dataType columnDefTail*
  | identifier AS LPAREN blobUntilRparen RPAREN (PERSISTED)?
  ;
columnDefTail
  : NULLX
  | NOT NULLX
  | CONSTRAINT identifier columnConstraintBody
  | columnConstraintBody
  ;
columnConstraintBody
  : PRIMARY KEY (CLUSTERED|NONCLUSTERED)? columnList?
  | FOREIGN KEY columnList? REFERENCES schemaQualifiedName columnList?
  | CHECK   LPAREN blobUntilRparen RPAREN
  | DEFAULT LPAREN blobUntilRparen RPAREN
  | UNIQUE
  ;

dataType
  : (qualifiedName | identifier) (LPAREN INTEGER (COMMA INTEGER)? RPAREN)?
  ;

columnList      : LPAREN identifier (COMMA identifier)* RPAREN ;
tableOptions    : WITH LPAREN ( . )*? RPAREN ;
anyExpr         : LPAREN ( anyExpr | ( . ) )*? RPAREN ;

/* ---------- ALTER TABLE / INDEX / DATABASE ---------- */
alterTableStatement : ALTER TABLE schemaQualifiedName alterTableAction ;
alterTableAction
  : ADD columnDef
  | ADD CONSTRAINT identifier columnConstraintBody
  ;
alterIndexStatement
  : ALTER INDEX (ALL | identifier) ON schemaQualifiedName REBUILD (WITH LPAREN ( . )*? RPAREN)?
  ;
alterDatabaseStatement
  : ALTER DATABASE (BRACKET_ID | IDENTIFIER) ( . )+? stopBeforeTerminator
  ;

/* ---------- INDEX / SYNONYM ---------- */
dropIndexStatement
  : DROP INDEX (IDENTIFIER | BRACKET_ID | DOUBLE_QUOTED_ID) ON schemaQualifiedName
  ;
createIndexStatement
  : CREATE (CLUSTERED|NONCLUSTERED)? INDEX identifier
    ON schemaQualifiedName LPAREN indexCol (COMMA indexCol)* RPAREN
    ( INCLUDE LPAREN identifier (COMMA identifier)* RPAREN )?
    ( WHERE predicate )?
  ;
indexCol : identifier (ASC|DESC)? ;
predicate: ( . )+? stopBeforeTerminator ;
createSynonymStatement : CREATE SYNONYM schemaQualifiedName FOR schemaQualifiedName ;

/* ============================== VIEW (opaque body) ============================== */
createViewStatement
  : CREATE (OR REPLACE)? VIEW schemaQualifiedName AS viewBody goTerminator+  #CreateViewWithGo
  | CREATE (OR REPLACE)? VIEW schemaQualifiedName AS viewBody                #CreateViewNoGo
  ;
viewBody : ( . )+? stopBeforeTerminator ;

/* ============================== TRY/CATCH & BEGIN/END helpers ============================== */
notBeginOrEnd    : { _input.LA(1) != BEGIN && _input.LA(1) != END }? . ;
tryCatchBalanced : BEGIN TRY ( beginEndBalanced | notBeginOrEnd )* END TRY BEGIN CATCH ( beginEndBalanced | notBeginOrEnd )* END CATCH ;
beginEndBalanced : BEGIN { _input.LA(1) != TRY }? ( tryCatchBalanced | beginEndBalanced | notBeginOrEnd )* END ;

/* ============================== FUNCTION (opaque) ============================== */
createFunctionStatement
  : CREATE FUNCTION schemaQualifiedName LPAREN paramDefList? RPAREN RETURNS returnType funcBody goTerminator+  #CreateFuncScalarWithGo
  | CREATE FUNCTION schemaQualifiedName LPAREN paramDefList? RPAREN RETURNS returnType funcBody                #CreateFuncScalarNoGo
  | CREATE FUNCTION schemaQualifiedName LPAREN paramDefList? RPAREN RETURNS TABLE AS RETURN LPAREN blobUntilRparen RPAREN (SEMI)? goTerminator+ #CreateFuncInlineWithGo
  | CREATE FUNCTION schemaQualifiedName LPAREN paramDefList? RPAREN RETURNS TABLE AS RETURN LPAREN blobUntilRparen RPAREN (SEMI)?               #CreateFuncInlineNoGo
  ;
paramDefList     : paramDef (COMMA paramDef)* ;
paramDef         : AT_ID? identifier dataType ;
returnType       : dataType | TABLE ;
funcBody         : blobUntilGoOrEof ;

/* ============================== PROCEDURE (opaque body, includes GO) ============================== */
createProcedureStatement
  : CREATE (OR ALTER)? PROCEDURE schemaQualifiedName
    procParams?
    procOptionClause?
    AS
    procBody
    goTerminator+                                #CreateProcWithGo
  | CREATE (OR ALTER)? PROCEDURE schemaQualifiedName
    procParams?
    procOptionClause?
    AS
    procBody                                     #CreateProcNoGo
  ;

dropProcedureStatement
  : DROP PROCEDURE (IF EXISTS)? schemaQualifiedName (COMMA schemaQualifiedName)*
  ;

// WITH (ENCRYPTION, RECOMPILE, EXECUTE AS ...)
procOptionClause
  : WITH procOptionList
  ;
procOptionList
  : procOption (COMMA procOption)*
  ;
procOption
  : ENCRYPTION
  | RECOMPILE
  | EXECUTE AS (CALLER | SELF | OWNER | STRING | NSTRING | identifier)
  ;
procParams       : LPAREN procParamList? RPAREN | procParamList ;
procParamList    : procParam (COMMA procParam)* ;
// An toàn: T-SQL yêu cầu tên @param → ưu tiên AT_ID; cho phép bắt buộc AT_ID để giảm noise
procParam
  : AT_ID dataType procParamAttr* (EQ defaultValue)?
  ;
// Các thuộc tính phổ biến: READONLY (TVP), OUTPUT/OUT, VARYING (tồn tại trên SQL cổ hơn/ODBC)
procParamAttr
  : READONLY
  | OUTPUT
  | OUT
  | VARYING
  ;
// DEFAULT phong phú: số âm/dương, chuỗi N'...', hàm, NULL/DEFAULT
defaultValue
  : sign? INTEGER
  | sign? DECIMAL_LIT
  | STRING
  | NSTRING
  | NULLX
  | identifier          // cho các hằng/builtin
  | functionCall
  ;
sign : PLUS | MINUS ;
procBody
  : (
      { _input.LA(1) != GO_STMT && _input.LA(1) != IntStream.EOF }?
      ( tryCatchBalanced | beginEndBalanced | notBeginOrEnd )
    )+
  ;

/* ============================== TRIGGER (opaque) ============================== */
createTriggerStatement
  // DML trigger: ON <schema.table> + (AFTER|FOR) <dml events>
  : CREATE TRIGGER schemaQualifiedName
    ON schemaQualifiedName
    dmlTriggerTiming
    AS trgBody goTerminator+                        #CreateDmlTriggerWithGo
  | CREATE TRIGGER schemaQualifiedName
    ON schemaQualifiedName
    dmlTriggerTiming
    AS trgBody                                      #CreateDmlTriggerNoGo

  // DDL trigger: ON DATABASE | ON ALL SERVER + FOR <ddl events>
  | CREATE TRIGGER schemaQualifiedName
    ON ddlScope
    ddlEventList
    AS trgBody goTerminator+                        #CreateDdlTriggerWithGo
  | CREATE TRIGGER schemaQualifiedName
    ON ddlScope
    ddlEventList
    AS trgBody                                      #CreateDdlTriggerNoGo
  ;

dmlTriggerTiming
  : (AFTER | FOR) dmlEventList
  ;
dmlEventList
  : (INSERT | UPDATE | DELETE) (COMMA (INSERT | UPDATE | DELETE))*
  ;

// ON DATABASE | ON ALL SERVER
ddlScope
  : DATABASE
  | IDENTIFIER IDENTIFIER
  ;

// FOR CREATE_TABLE, ALTER_TABLE, ... (để đơn giản cho chunking, chấp nhận identifier)
ddlEventList
  : FOR identifier (COMMA identifier)*
  ;

// Nuốt toàn bộ thân trigger tới trước GO/EOF (đã có sẵn trong file)
trgBody : blobUntilGoOrEof ;

/* ============================== Control/Programmability ============================== */
throwStatement   : THROW (INTEGER COMMA (NSTRING|STRING|identifier|functionCall) COMMA INTEGER)? ;
execStatement
  : EXEC STRING stopBeforeTerminator
  | EXEC LPAREN STRING RPAREN stopBeforeTerminator
  | EXEC qualifiedName execArgs?
  ;
execArgs         : ( . )+? stopBeforeTerminator ;
setStatement     : SET IDENTIFIER (ON|OFF) ;
printStatement   : PRINT ( NSTRING | STRING | functionCall | concatExpr | identifier ) stopBeforeTerminator ;
concatExpr       : (STRING | identifier | functionCall) ((PLUS|COMMA) (STRING | identifier | functionCall))* ;
functionCall     : identifier LPAREN blobUntilRparen RPAREN ;

/* ---------- IF with either BEGIN..END block or single statement ---------- */
ifStatement
  : IF ifPredicate
      ( beginEndBalanced | sqlStatement terminator )
      ( ELSE ( beginEndBalanced | sqlStatement terminator ) )?
  ;
ifPredicate
  : ( . )+?
    { _input.LA(1) == BEGIN
   || _input.LA(1) == ELSE
   || _input.LA(1) == SEMI
   || _input.LA(1) == GO_STMT
   || _input.LA(1) == IntStream.EOF }?
  ;

/* ============================== DML (opaque by terminator) ============================== */

beginTranStmt  : BEGIN (TRAN | TRANSACTION) (identifier)? ;
commitTranStmt : COMMIT (TRAN | TRANSACTION) (identifier)? ;
rollbackTranStmt : ROLLBACK (TRAN | TRANSACTION) (identifier)? ;

mergeStmt        : MERGE  ( . )+? stopBeforeTerminator ;
selectStmt       : SELECT ( . )+? stopBeforeTerminator ;
insertStmt       : INSERT ( . )+? stopBeforeTerminator ;
updateStmt       : UPDATE ( . )+? stopBeforeTerminator ;
deleteStmt       : DELETE ( . )+? stopBeforeTerminator ;
unknownStatement : ( . )+? stopBeforeTerminator ;

/* ---------- Expressions (minimal) ---------- */
booleanExpr      : expr ;
expr
  : expr binaryOp expr
  | functionCall
  | qualifiedName
  | literal
  | LPAREN expr RPAREN
  ;
binaryOp         : CONCAT | PLUS | MINUS | STAR | SLASH | PERCENT | CARET | EQ | LT | LTE | GT | GTE | NEQ ;
literal          : STRING | INTEGER | DECIMAL_LIT | TRUE | FALSE | NULLX ;
goTerminator     : GO_STMT ;

/* ============================== Lexer rules ============================== */
fragment A:[aA]; fragment B:[bB]; fragment C:[cC]; fragment D:[dD]; fragment E:[eE];
fragment F:[fF]; fragment G:[gG]; fragment H:[hH]; fragment I:[iI]; fragment J:[jJ];
fragment K:[kK]; fragment L:[lL]; fragment M:[mM]; fragment N:[nN]; fragment O:[oO];
fragment P:[pP]; fragment Q:[qQ]; fragment R:[rR]; fragment S:[sS]; fragment T:[tT];
fragment U:[uU]; fragment V:[vV]; fragment W:[wW]; fragment X:[xX]; fragment Y:[yY];
fragment Z:[zZ];

CREATE: C R E A T E; OR: O R; REPLACE: R E P L A C E; ALTER: A L T E R;
DATABASE: D A T A B A S E; USE: U S E; DROP: D R O P; TABLE: T A B L E;
SCHEMA: S C H E M A; AUTHORIZATION: A U T H O R I Z A T I O N; VIEW: V I E W;
FUNCTION: F U N C T I O N; PROCEDURE: P R O C E D U R E; TRIGGER: T R I G G E R;
RETURNS: R E T U R N S; AS: A S; BEGIN: B E G I N; END: E N D; WITH: W I T H;
PRIMARY: P R I M A R Y; KEY: K E Y; UNIQUE: U N I Q U E; FOREIGN: F O R E I G N;
REFERENCES: R E F E R E N C E S; CHECK: C H E C K; NOT: N O T; NULLX: N U L L;
DEFAULT: D E F A U L T; ASC: A S C; DESC: D E S C; INDEX: I N D E X;
CLUSTERED: C L U S T E R E D; NONCLUSTERED: N O N C L U S T E R E D; ON: O N;
INCLUDE: I N C L U D E; WHERE: W H E R E; SELECT: S E L E C T; INSERT: I N S E R T;
UPDATE: U P D A T E; DELETE: D E L E T E; TRUE: T R U E; FALSE: F A L S E;
EXEC: E X E C; SET: S E T; PRINT: P R I N T; IF: I F; ELSE: E L S E;
SYNONYM: S Y N O N Y M; SEQUENCE: S E Q U E N C E; TYPE: T Y P E; MERGE: M E R G E;
ALL: A L L; AFTER: A F T E R; FOR: F O R; PERSISTED: P E R S I S T E D;
REBUILD: R E B U I L D; CACHE: C A C H E; CYCLE: C Y C L E; MINVALUE: M I N V A L U E;
MAXVALUE: M A X V A L U E; INCREMENT: I N C R E M E N T; START: S T A R T;
FROM: F R O M; BY: B Y; CONSTRAINT: C O N S T R A I N T; NO: N O; ADD: A D D;
RETURN: R E T U R N; OFF: O F F; THROW: T H R O W; TRY: T R Y; CATCH: C A T C H;
TRAN : T R A N;
TRANSACTION : T R A N S A C T I O N;
COMMIT : C O M M I T;
ROLLBACK : R O L L B A C K;
READONLY: R E A D O N L Y;
OUTPUT: O U T P U T;
OUT: O U T;
VARYING: V A R Y I N G;
ENCRYPTION: E N C R Y P T I O N;
RECOMPILE: R E C O M P I L E;
EXECUTE: E X E C U T E;
CALLER: C A L L E R;
SELF: S E L F;
OWNER: O W N E R;
EXISTS : E X I S T S;

SEMI:';'; LPAREN:'(' ; RPAREN:')' ; COMMA:',' ; DOT:'.' ;
CONCAT:'||'; PLUS:'+'; MINUS:'-'; STAR:'*'; SLASH:'/'; PERCENT:'%'; CARET:'^';
EQ:'='; LT:'<'; LTE:'<='; GT:'>'; GTE: '>='; NEQ: '!=' | '<>';

GO_STMT
  : {atLineStartAfterWs()}? [ \t]* 'GO' [ \t]* [0-9]* [ \t]*
  ;

BRACKET_ID       : '[' (~[\]\r\n])+ ']' ;
DOUBLE_QUOTED_ID : '"' ( '""' | ~["\r\n] )* '"' ;
IDENTIFIER       : [A-Za-z_][A-Za-z_0-9$#]* ;
AT_ID            : '@' '@'? [A-Za-z_][A-Za-z_0-9$#]* ;
INTEGER          : [0-9]+ ;
DECIMAL_LIT      : [0-9]+ '.' [0-9]+ ;
STRING           : '\'' ( '\'\'' | ~['\r\n] )* '\'' ;
NSTRING          : [Nn]'\'' ( '\'\'' | ~['\r\n] )* '\'' ;

LINE_COMMENT  : '--' ~[\r\n]* -> skip ;
BLOCK_COMMENT : '/*' .*? '*/' -> skip ;
WS            : [ \t\r\n]+ -> skip ;