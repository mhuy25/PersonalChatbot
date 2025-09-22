grammar SqlServer;

@header {
package com.example.personalchatbot.service.sql.antlr.sqlserver;
import org.antlr.v4.runtime.IntStream;
}

@lexer::members {
  private boolean atStartOfLine() {
    int prev = _input.LA(-1);
    return prev == '\n' || prev == '\r' || getCharPositionInLine() == 0;
  }
  private boolean atLineStartAfterWs() {
    int i = -1;
    int la = _input.LA(i);
    while (la == ' ' || la == '\t') { i--; la = _input.LA(i); }
    return la == '\n' || la == '\r' || la == IntStream.EOF;
  }
}

/* ============================== Parser rules ============================== */
/* Root đúng với Java: các con trực tiếp là topStmt/goOnly */
script
  : (topStmt | goOnly)+ EOF
  ;

/* Alias nếu nơi khác dùng tên này */
sqlStatements
  : script
  ;

topStmt
  : p=createProcedureStatement      #TopProc
  | f=createFunctionStatement       #TopFunc
  | v=createViewStatement           #TopView
  | t=createTriggerStatement        #TopTrig
  | s=sqlStatement SEMI* goTerminator*  #TopWithTerminator
  | s=sqlStatement SEMI*                #TopNoGo
  ;

goOnly
  : goTerminator+                        #TopGoOnly
  ;

terminator
  : SEMI
  | GO_STMT
  ;

stopBeforeTerminator
  : { _input.LA(1) == SEMI || _input.LA(1) == GO_STMT || _input.LA(1) == IntStream.EOF }?
  ;

/** Opaque: nuốt cho tới ngay trước GO/EOF (gated wildcard). */
blobUntilGoOrEof
  : ( { _input.LA(1) != GO_STMT && _input.LA(1) != IntStream.EOF }? . )+
  ;

sqlStatement
  : createDatabaseStatement         #StCreateDatabase
  | useStatement                    #StUse
  | dropStatement                   #StDrop
  | createSchemaStatement           #StCreateSchema
  | createTypeStatement             #StCreateType
  | createSequenceStatement         #StCreateSequence
  | createTableStatement            #StCreateTable
  | alterTableStatement             #StAlterTable
  | createIndexStatement            #StCreateIndex
  | alterIndexStatement             #StAlterIndex
  | alterDatabaseStatement          #StAlterDatabase
  | dropIndexStatement              #StDropIndex
  | createSynonymStatement          #StCreateSynonym
  | setStatement                    #StSet
  | execStatement                   #StExec
  | throwStatement                  #StThrow
  | printStatement                  #StPrint
  | ifStatement                     #StIf
  | mergeStmt                       #StMerge
  | selectStmt                      #StSelect
  | insertStmt                      #StInsert
  | updateStmt                      #StUpdate
  | deleteStmt                      #StDelete
  | unknownStatement                #StUnknown
  ;

/* --- CREATE DATABASE / USE / DROP --- */
createDatabaseStatement
  : CREATE DATABASE (BRACKET_ID | IDENTIFIER)
  ;

useStatement
  : USE (BRACKET_ID | IDENTIFIER)
  ;

dropStatement
  : DROP (DATABASE | TABLE | VIEW | FUNCTION | PROCEDURE | TRIGGER | SEQUENCE | TYPE | SYNONYM)
    schemaQualifiedName
  ;

/* --- CREATE SCHEMA --- */
createSchemaStatement
  : CREATE SCHEMA schemaName (AUTHORIZATION identifier)?
  ;

/* --- Qualified names & identifiers --- */
schemaQualifiedName : (schemaName DOT)? objectName ;
schemaName  : BRACKET_ID | DOUBLE_QUOTED_ID | IDENTIFIER ;
objectName  : BRACKET_ID | DOUBLE_QUOTED_ID | IDENTIFIER ;
identifier  : BRACKET_ID | DOUBLE_QUOTED_ID | IDENTIFIER | AT_ID ;

/* --- CREATE TYPE --- */
createTypeStatement
  : CREATE TYPE schemaQualifiedName FROM dataType (NOT NULLX | NULLX)?
  | CREATE TYPE schemaQualifiedName AS TABLE LPAREN tableTypeColumnDef (COMMA tableTypeColumnDef)* RPAREN
  ;

tableTypeColumnDef
  : identifier dataType columnConstraintBody*
  ;

/* --- CREATE SEQUENCE --- */
createSequenceStatement
  : CREATE SEQUENCE schemaQualifiedName sequenceOptions?
  ;

sequenceOptions : (sequenceOption)+ ;
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

/* --- CREATE TABLE --- */
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

idList : identifier (COMMA identifier)* ;
refColumnList : identifier (COMMA identifier)* ;

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

dataType   : identifier (LPAREN INTEGER (COMMA INTEGER)? RPAREN)? ;
columnList : LPAREN identifier (COMMA identifier)* RPAREN ;

tableOptions : WITH LPAREN ( . )*? RPAREN ;
anyExpr     : LPAREN ( anyExpr | ( . ) )*? RPAREN ;

/* --- ALTER TABLE / ALTER INDEX --- */
alterTableStatement : ALTER TABLE schemaQualifiedName alterTableAction ;
alterTableAction
  : ADD columnDef
  | ADD CONSTRAINT identifier columnConstraintBody
  ;

alterIndexStatement
  : ALTER INDEX (ALL | identifier) ON schemaQualifiedName
    REBUILD (WITH LPAREN ( . )*? RPAREN)?
  ;

/* --- ALTER DATABASE --- */
alterDatabaseStatement
  : ALTER DATABASE (BRACKET_ID | IDENTIFIER) ( . )+? stopBeforeTerminator
  ;

/* --- DROP INDEX --- */
dropIndexStatement
  : DROP INDEX (IDENTIFIER | BRACKET_ID | DOUBLE_QUOTED_ID) ON schemaQualifiedName
  ;

/* --- CREATE INDEX --- */
createIndexStatement
  : CREATE (CLUSTERED|NONCLUSTERED)? INDEX identifier
    ON schemaQualifiedName LPAREN indexCol (COMMA indexCol)* RPAREN
    ( INCLUDE LPAREN identifier (COMMA identifier)* RPAREN )?
    ( WHERE predicate )?
  ;
indexCol : identifier (ASC|DESC)? ;
predicate : ( . )+? stopBeforeTerminator ;

/* --- CREATE VIEW (opaque body) --- */
createViewStatement
  : CREATE (OR REPLACE)? VIEW schemaQualifiedName AS viewBody goTerminator+  #CreateViewWithGo
  | CREATE (OR REPLACE)? VIEW schemaQualifiedName AS viewBody                #CreateViewNoGo
  ;
viewBody : ( . )+? stopBeforeTerminator ;

/* ---------- blob cân ngoặc ---------- */
blobUntilRparen
  : ( LPAREN blobUntilRparen RPAREN | ~RPAREN )*
  ;

/* --- BEGIN/END & TRY/CATCH cân bằng (phục vụ vài nơi khác) --- */
notBeginOrEnd : { _input.LA(1) != BEGIN && _input.LA(1) != END }? . ;
tryCatchBalanced
  : BEGIN TRY ( beginEndBalanced | notBeginOrEnd )* END TRY
    BEGIN CATCH ( beginEndBalanced | notBeginOrEnd )* END CATCH
  ;
beginEndBalanced
  : BEGIN { _input.LA(1) != TRY }?
    ( tryCatchBalanced | beginEndBalanced | notBeginOrEnd )*
    END
  ;

/* --- CREATE FUNCTION (opaque body) --- */
createFunctionStatement
  : CREATE FUNCTION schemaQualifiedName LPAREN paramDefList? RPAREN
    RETURNS returnType funcBody goTerminator+                              #CreateFuncScalarWithGo
  | CREATE FUNCTION schemaQualifiedName LPAREN paramDefList? RPAREN
    RETURNS returnType funcBody                                            #CreateFuncScalarNoGo
  | CREATE FUNCTION schemaQualifiedName LPAREN paramDefList? RPAREN
    RETURNS TABLE AS RETURN LPAREN blobUntilRparen RPAREN (SEMI)? goTerminator+ #CreateFuncInlineWithGo
  | CREATE FUNCTION schemaQualifiedName LPAREN paramDefList? RPAREN
    RETURNS TABLE AS RETURN LPAREN blobUntilRparen RPAREN (SEMI)?               #CreateFuncInlineNoGo
  ;

paramDefList : paramDef (COMMA paramDef)* ;
paramDef     : AT_ID? identifier dataType ;
returnType   : dataType | TABLE ;
funcBody : blobUntilGoOrEof ;

/* --- CREATE PROCEDURE (opaque body, ôm GO) --- */
createProcedureStatement
  : CREATE PROCEDURE schemaQualifiedName procParams? AS procBody goTerminator+  #CreateProcWithGo
  | CREATE PROCEDURE schemaQualifiedName procParams? AS procBody                #CreateProcNoGo
  ;

procParams
  : LPAREN procParamList? RPAREN
  | procParamList
  ;

procParamList : procParam (COMMA procParam)* ;
procParam     : AT_ID? identifier dataType (EQ defaultValue)? ;
defaultValue  : STRING | INTEGER | DECIMAL_LIT | NULLX ;
procBody : blobUntilGoOrEof ;

stopBeforeGoOrEof
  : { _input.LA(1) == GO_STMT || _input.LA(1) == IntStream.EOF }?
  ;

/* --- CREATE TRIGGER (opaque body, ôm GO) --- */
createTriggerStatement
  : CREATE TRIGGER schemaQualifiedName ON schemaQualifiedName
    triggerTiming AS trgBody goTerminator+  #CreateTriggerWithGo
  | CREATE TRIGGER schemaQualifiedName ON schemaQualifiedName
    triggerTiming AS trgBody                #CreateTriggerNoGo
  ;

triggerTiming
  : AFTER (INSERT | UPDATE | DELETE) (COMMA (INSERT|UPDATE|DELETE))*
  ;
trgBody  : blobUntilGoOrEof ;

/* --- CREATE SYNONYM --- */
createSynonymStatement
  : CREATE SYNONYM schemaQualifiedName FOR schemaQualifiedName
  ;

/* --- Programmability & Control --- */
throwStatement
  : THROW (INTEGER COMMA (NSTRING|STRING|identifier|functionCall) COMMA INTEGER)?
  ;

execStatement
  : EXEC STRING stopBeforeTerminator
  | EXEC LPAREN STRING RPAREN stopBeforeTerminator
  | EXEC qualifiedName execArgs?
  ;
execArgs : ( . )+? stopBeforeTerminator ;

setStatement
  : SET IDENTIFIER ON
  | SET IDENTIFIER OFF
  ;

printStatement
  : PRINT ( NSTRING | STRING | functionCall | concatExpr | identifier ) stopBeforeTerminator
  ;

concatExpr
  : (STRING | identifier | functionCall) ((PLUS|COMMA) (STRING | identifier | functionCall))*
  ;

functionCall : identifier LPAREN blobUntilRparen RPAREN ;

/* --- IF --- */
ifStatement
  : IF ifPredicate
      ( beginEndBalanced
      | sqlStatement terminator
      )
      ( ELSE
          ( beginEndBalanced
          | sqlStatement terminator
          )
      )?
  ;

ifPredicate
  : ( . )+?
    { _input.LA(1) == BEGIN
   || _input.LA(1) == ELSE
   || _input.LA(1) == SEMI
   || _input.LA(1) == GO_STMT
   || _input.LA(1) == IntStream.EOF }?
  ;

/* --- MERGE & DML cơ bản --- */
mergeStmt  : MERGE ( . )+? stopBeforeTerminator ;
selectStmt : SELECT ( . )+? stopBeforeTerminator ;
insertStmt : INSERT ( . )+? stopBeforeTerminator ;
updateStmt : UPDATE ( . )+? stopBeforeTerminator ;
deleteStmt : DELETE ( . )+? stopBeforeTerminator ;

unknownStatement : ( . )+? stopBeforeTerminator ;

/* --- Expr cơ bản --- */
booleanExpr : expr ;
expr
  : expr binaryOp expr
  | functionCall
  | qualifiedName
  | literal
  | LPAREN expr RPAREN
  ;
binaryOp
  : CONCAT | PLUS | MINUS | STAR | SLASH | PERCENT | CARET
  | EQ | LT | LTE | GT | GTE | NEQ
  ;
qualifiedName : identifier (DOT identifier)? ;
literal
  : STRING | INTEGER | DECIMAL_LIT | TRUE | FALSE | NULLX
  ;

goTerminator : GO_STMT ;

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