grammar SqlServer;

@header {
package com.example.personalchatbot.service.sql.antlr.sqlserver;
import org.antlr.v4.runtime.IntStream;
}

@lexer::members {
    private boolean atLineStartAfterWs() {
        int i = -1;
        int la = _input.LA(i);

        // đi lùi: nếu gặp newline/EOF trước khi gặp ký tự khác -> đầu dòng
        while (true) {
            if (la == org.antlr.v4.runtime.IntStream.EOF) return true;
            if (la == '\n' || la == '\r') return true;

            // bỏ qua khoảng trắng KHÔNG PHẢI newline
            if (la == ' ' || la == '\t' || Character.isWhitespace(la) && la != '\n' && la != '\r') {
                i--;
                la = _input.LA(i);
                continue;
            }

            // lùi qua block comment ngay trước GO
            if (la == '/' && _input.LA(i - 1) == '*') {
                int depth = 1; i -= 2;
                while (depth > 0) {
                    la = _input.LA(i--);
                    if (la == org.antlr.v4.runtime.IntStream.EOF) return true;
                    if (la == '/' && _input.LA(i) == '*') { depth++; i--; }
                    else if (la == '*' && _input.LA(i) == '/') { depth--; i--; }
                }
                la = _input.LA(i);
                continue;
            }

            // gặp ký tự khác trước newline -> không phải đầu dòng
            return false;
        }
    }
}

/* ============================== Parser rules ============================== */
script
    : (sqlStatement | goOnly)+ EOF ;

sqlStatements
    : script ;

sqlStatement
    : coreStatement (SEMI)*
    ;

innerStatement
    : coreStatement (SEMI)?
    ;

coreStatement
    : createDatabaseStatement
    | createProcedureStatement
    | createFunctionStatement
    | createViewStatement
    | createTriggerStatement
    | dropProcedureStatement
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
    | commitTranStmt
    | rollbackTranStmt
    | createXmlSchemaCollectionStatement
    | dropXmlSchemaCollectionStatement
    | createRoleStatement
    | dropRoleStatement
    | createUserStatement
    | dropUserStatement
    | createPartitionFunctionStatement
    | createPartitionSchemeStatement
    | dropPartitionFunctionStatement
    | dropPartitionSchemeStatement
    | dropDdlTriggerStatement
    | dropStatement
    | ifStatement
    | mergeStmt
    | selectStmt
    | insertStmt
    | updateStmt
    | deleteStmt
    | blockStatement
    | beginTranStmt
    | setStatement
    | execStatement
    | throwStatement
    | printStatement
    | useStatement
    | declareStatement
    | grantStatement
    | unknownStatement
    ;

goOnly
    : goTerminator+                            #TopGoOnly ;

/* ====================== SQL STATEMENTS ====================== */
/* ---------- CREATE DATABASE ---------- */
createDatabaseStatement
    : CREATE DATABASE dbName=identifierLike ( . )*? stopBeforeTerminator
    ;

/* ---------- CREATE SCHEMA ---------- */
createSchemaStatement
    : CREATE SCHEMA schemaName (AUTHORIZATION identifier)? ;

/* ---------- CREATE TYPE ---------- */
createTypeStatement
    : CREATE TYPE schemaQualifiedName FROM dataType (NOT NULLX | NULLX)?
    | CREATE TYPE schemaQualifiedName AS TABLE LPAREN tableTypeColumnDef (COMMA tableTypeColumnDef)* RPAREN
    ;

tableTypeColumnDef      : identifier dataType columnConstraintBody* ;

/* ---------- CREATE SEQUENCE ---------- */
createSequenceStatement
    : CREATE SEQUENCE schemaQualifiedName sequenceOptions?
    ;

sequenceOptions
    : sequenceOption+
    ;

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
    : CREATE TABLE schemaQualifiedName
    LPAREN tableElement (COMMA tableElement)* RPAREN
    tableOptions?
    tableStorageOptions?
    ;

tableOptions
    : WITH LPAREN ( . )*? RPAREN
    ;

tableStorageOptions
    : tableStorageOption (tableStorageOption)*
    ;

tableStorageOption
    : ON ( BRACKET_ID | DOUBLE_QUOTED_ID | IDENTIFIER )
    | TEXTIMAGE_ON  ( BRACKET_ID | DOUBLE_QUOTED_ID | IDENTIFIER )
    | FILESTREAM_ON ( BRACKET_ID | DOUBLE_QUOTED_ID | IDENTIFIER )
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

idList
    : identifier (COMMA identifier)* ;

refColumnList
    : identifier (COMMA identifier)* ;

columnDef
    : identifier dataType columnDefTail*
    | identifier AS ( LPAREN blobUntilRparen RPAREN | exprUntilCommaOrRparen ) (PERSISTED)?
    ;

columnDefTail
    : NULLX
    | NOT NULLX
    | CONSTRAINT identifier columnConstraintBody
    | columnConstraintBody
    | IDENTITY LPAREN INTEGER COMMA INTEGER RPAREN
    | ROWGUIDCOL
    | COLLATE identifier
    | SPARSE
    | FILESTREAM
    | MASKED WITH LPAREN opaqueUntilCommaOrRparen RPAREN
    | GENERATED ALWAYS AS ROW START
    | GENERATED ALWAYS AS ROW END
    | HIDDEN_KW
    | DEFAULT LPAREN blobUntilRparen RPAREN
    | opaqueUntilCommaOrRparen
    ;

columnConstraintBody
    : PRIMARY KEY (CLUSTERED|NONCLUSTERED)? columnList?
    | FOREIGN KEY columnList? REFERENCES schemaQualifiedName columnList? fkActions?
    | CHECK   LPAREN blobUntilRparen RPAREN
    | DEFAULT LPAREN blobUntilRparen RPAREN
    | UNIQUE
    ;

columnList
    : LPAREN identifier (COMMA identifier)* RPAREN ;

/* ---------- ALTER TABLE ---------- */
alterTableStatement
    : ALTER TABLE schemaQualifiedName alterTableAction
    ;

alterTableAction
    : ADD columnDef
    | ADD CONSTRAINT identifier columnConstraintBody
    ;

/* ---------- CREATE XML ---------- */
createXmlSchemaCollectionStatement
    : CREATE XML SCHEMA COLLECTION schemaQualifiedName AS (NSTRING | STRING) stopBeforeTerminator
    ;

/* ---------- DROP XML ---------- */
dropXmlSchemaCollectionStatement
    : DROP XML SCHEMA COLLECTION schemaQualifiedName
    ;

/* ---------- ALTER INDEX ---------- */
alterIndexStatement
    : ALTER INDEX (ALL | identifier) ON schemaQualifiedName REBUILD (WITH LPAREN ( . )*? RPAREN)?
    ;

/* ---------- ALTER DATABASE ---------- */
alterDatabaseStatement
    : ALTER  DATABASE dbName=identifierLike ( . )+? stopBeforeTerminator
    ;

/* ---------- INDEX ---------- */
dropIndexStatement
    : DROP INDEX (IF EXISTS)? idx=(IDENTIFIER | BRACKET_ID | DOUBLE_QUOTED_ID) ON schemaQualifiedName
    ;

createIndexStatement
    : CREATE (UNIQUE)? (CLUSTERED|NONCLUSTERED)? (COLUMNSTORE)? INDEX identifier
    ON schemaQualifiedName LPAREN indexCol (COMMA indexCol)* RPAREN
    ( INCLUDE LPAREN identifier (COMMA identifier)* RPAREN )?
    ( WHERE predicate )?
    ( WITH LPAREN ( . )*? RPAREN )?
    opaqueIndexTail?
    ;

opaqueIndexTail
    : ( . )+? stopBeforeTerminator
    ;

indexCol : identifier (ASC|DESC)? ;

predicate: ( . )+? stopBeforeTerminator ;

/* ---------- CREATE SYNONYM ---------- */
createSynonymStatement
    : CREATE SYNONYM schemaQualifiedName FOR schemaQualifiedName
    ;

/* ---------- CREATE VIEW ---------- */
createViewStatement
    : CREATE (OR ALTER)? VIEW schemaQualifiedName (WITH viewOptionList)? AS viewBody  #CreateView
    ;

viewOptionList
    : viewOption (COMMA viewOption)* ;

viewOption
    : SCHEMABINDING | ENCRYPTION | VIEW_METADATA | identifier (EQ ( . )+? )? ;

viewBody : ( . )+? stopBeforeTerminator ;

/* ---------- TRY/CATCH BEGIN/END ---------- */
notBeginOrEnd
    : { _input.LA(1) != BEGIN && _input.LA(1) != END && _input.LA(1) != GO_STMT }? .
    ;

tryCatchBalanced
    : BEGIN TRY
        (
          { _input.LA(1) != END }?
          (
              tryCatchBalanced
            | beginEndBalanced
            | beginTranStmt
            | commitTranStmt
            | rollbackTranStmt
            | notBeginOrEnd
          )
        )*
      END TRY
      BEGIN CATCH
        (
          { _input.LA(1) != END }?
          (
              tryCatchBalanced
            | beginEndBalanced
            | beginTranStmt
            | commitTranStmt
            | rollbackTranStmt
            | notBeginOrEnd
          )
        )*
      END CATCH
    ;

beginEndBalanced
    : BEGIN { _input.LA(1) != TRY }? ( tryCatchBalanced | beginEndBalanced | notBeginOrEnd )* END ;

/* ---------- CREATE FUNCTION ---------- */
createFunctionStatement
    : CREATE (OR ALTER)? FUNCTION schemaQualifiedName LPAREN paramDefList? RPAREN RETURNS returnType funcBody goTerminator+  #CreateFuncScalarWithGo
    | CREATE (OR ALTER)? FUNCTION schemaQualifiedName LPAREN paramDefList? RPAREN RETURNS returnType funcBody                #CreateFuncScalarNoGo
    | CREATE (OR ALTER)? FUNCTION schemaQualifiedName LPAREN paramDefList? RPAREN RETURNS TABLE AS RETURN LPAREN blobUntilRparen RPAREN (SEMI)? goTerminator+ #CreateFuncInlineWithGo
    | CREATE (OR ALTER)? FUNCTION schemaQualifiedName LPAREN paramDefList? RPAREN RETURNS TABLE AS RETURN LPAREN blobUntilRparen RPAREN (SEMI)?               #CreateFuncInlineNoGo
    ;

paramDefList     : paramDef (COMMA paramDef)* ;

paramDef         : AT_ID? identifier dataType ;

returnType       : dataType | TABLE ;

funcBody         : blobUntilGoOrEof ;

/* ---------- CREATE PROCEDURE ---------- */
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
procParams
    : LPAREN procParamList? RPAREN | procParamList ;

procParamList
    : procParam (COMMA procParam)* ;

procParam
    : AT_ID dataType procParamAttr* (EQ defaultValue)?
    ;

procParamAttr
    : READONLY
    | OUTPUT
    | OUT
    | VARYING
    ;

defaultValue
    : sign? INTEGER
    | sign? DECIMAL_LIT
    | STRING
    | NSTRING
    | NULLX
    | identifier
    | functionCall
    ;

sign : PLUS | MINUS ;

procBody
    : (
        { _input.LA(1) != GO_STMT && _input.LA(1) != IntStream.EOF }?
        ( tryCatchBalanced
        | beginEndBalanced
        | beginTranStmt
        | commitTranStmt
        | rollbackTranStmt
        | notBeginOrEnd
        )
      )+
    ;
/* ---------- DROP PROCEDURE ---------- */
dropProcedureStatement
    : DROP PROCEDURE (IF EXISTS)? schemaQualifiedName (COMMA schemaQualifiedName)*
    ;

/* ---------- CREATE TRIGGER ---------- */
createTriggerStatement
    : CREATE TRIGGER schemaQualifiedName
    ON schemaQualifiedName
    dmlTriggerTiming
    AS trgBody goTerminator+                        #CreateDmlTriggerWithGo
    | CREATE TRIGGER schemaQualifiedName
    ON schemaQualifiedName
    dmlTriggerTiming
    AS trgBody                                      #CreateDmlTriggerNoGo
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

ddlScope
    : DATABASE
    | IDENTIFIER IDENTIFIER
    ;

ddlEventList
    : FOR identifier (COMMA identifier)*
    ;

trgBody : blobUntilGoOrEof ;

/* ---------- DROP TRIGGER ... ON DATABASE ---------- */
dropDdlTriggerStatement
    : DROP TRIGGER schemaQualifiedName ON DATABASE
    ;

/* ---------- IF ---------- */
ifStatement
    : IF ifPredicate
      (
          { _input.LA(1) == BEGIN }? beginEndBalanced
        | innerStatement (terminator)?
      )
      ( ELSE
        (
            { _input.LA(1) == BEGIN }? beginEndBalanced
          | innerStatement (terminator)?
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

/* ---------- ROLE ---------- */
createRoleStatement
    : CREATE ROLE identifier (AUTHORIZATION identifier)?
    ;

dropRoleStatement
    : DROP ROLE (IF EXISTS)? identifier
    ;

/* ---------- USER ---------- */
createUserStatement
    : CREATE USER identifier (WITHOUT LOGIN)?
    ;

dropUserStatement
    : DROP USER (IF EXISTS)? identifier
    ;

/* ---------- PARTITION ---------- */
dropPartitionFunctionStatement
    : DROP PARTITION FUNCTION (IF EXISTS)? identifier (IF EXISTS)?
    ;

dropPartitionSchemeStatement
    : DROP PARTITION SCHEME (IF EXISTS)? identifier (IF EXISTS)?
    ;

createPartitionFunctionStatement
    : CREATE PARTITION FUNCTION identifier LPAREN dataType RPAREN
      AS RANGE (LEFT | RIGHT) FOR VALUES anyExpr stopBeforeTerminator
    ;

createPartitionSchemeStatement
    : CREATE PARTITION SCHEME identifier
      AS PARTITION identifier
      TO anyExpr stopBeforeTerminator
    ;

/* ---------- DECLARE ---------- */
declareStatement
    : DECLARE declareItem (COMMA declareItem)* ;

declareItem
    : AT_ID dataType (EQ expr)?
    ;

/* ---------- GRANT ---------- */
grantStatement
    : GRANT grantPermList ON grantTarget TO identifier
    ;

grantPermList
    : identifier (COMMA identifier)*
    ;

grantTarget
    : OBJECT DCOLON schemaQualifiedName
    | TYPE   DCOLON schemaQualifiedName
    | SCHEMA DCOLON schemaName
    | schemaQualifiedName
    ;

/* ---------- BEGIN ... END ---------- */
blockStatement
    : beginEndBalanced
    ;

/* ---------- BASIC STATEMENT ---------- */
beginTranStmt  : BEGIN (TRAN | TRANSACTION) (identifier)? ;

commitTranStmt : COMMIT (TRAN | TRANSACTION) (identifier)? ;

rollbackTranStmt : ROLLBACK (TRAN | TRANSACTION) (identifier)? ;

useStatement
    : USE (BRACKET_ID | IDENTIFIER) ;

dropStatement
    : DROP DATABASE (IF EXISTS)? (BRACKET_ID | IDENTIFIER) (COMMA (BRACKET_ID | IDENTIFIER))*
    | DROP (TABLE | VIEW | FUNCTION | PROCEDURE | TRIGGER | SEQUENCE | TYPE | SYNONYM)
    (IF EXISTS)? schemaQualifiedName (COMMA schemaQualifiedName)*
    ;

mergeStmt        : MERGE  ( . )+? stopBeforeTerminator ;
selectStmt       : SELECT ( . )+? stopBeforeTerminator ;
insertStmt       : INSERT ( . )+? stopBeforeTerminator ;
updateStmt       : UPDATE ( . )+? stopBeforeTerminator ;
deleteStmt       : DELETE ( . )+? stopBeforeTerminator ;

/* ---------- Other Statement ---------- */
throwStatement
    : THROW (INTEGER COMMA (NSTRING|STRING|identifier|functionCall) COMMA INTEGER)? ;

execStatement
    : EXEC STRING stopBeforeTerminator
    | EXEC LPAREN STRING RPAREN stopBeforeTerminator
    | EXEC qualifiedName execArgs?
    ;

execArgs
    : ( . )+? stopBeforeTerminator ;

setStatement
    : SET IDENTIFIER (ON|OFF)
    | SET AT_ID EQ expr
    ;

printStatement
    : PRINT ( NSTRING | STRING | functionCall | concatExpr | identifier ) stopBeforeTerminator
    ;

functionCall
    : (BRACKET_ID | DOUBLE_QUOTED_ID | IDENTIFIER) (DCOLON identifier)? LPAREN blobUntilRparen RPAREN ;

/* ---------- ANONYMOUS STATEMENT ---------- */
unknownStatement : ( . )+? stopBeforeTerminator ;

/* ---------- Expressions ---------- */
expr
    : expr binaryOp expr
    | functionCall
    | qualifiedName
    | literal
    | LPAREN expr RPAREN
    ;

booleanExpr
    : expr ;

anyExpr
    : LPAREN ( anyExpr | ( . ) )*? RPAREN ;

concatExpr
    : (STRING | identifier | functionCall) ((PLUS|COMMA) (STRING | identifier | functionCall))* ;

exprUntilCommaOrRparen
    : ( . )+? stopBeforeCommaOrRparen ;

binaryOp         : CONCAT | PLUS | MINUS | STAR | SLASH | PERCENT | CARET | EQ | LT | LTE | GT | GTE | NEQ ;
literal          : STRING | INTEGER | DECIMAL_LIT | TRUE | FALSE | NULLX ;
goTerminator     : GO_STMT ;

/* ====================== DATATYPE ====================== */
dataType
    : (qualifiedName | BRACKET_ID | DOUBLE_QUOTED_ID | IDENTIFIER)
    (LPAREN INTEGER (COMMA INTEGER)? RPAREN)?
    ;

/* ==================== Common helpers ==================== */
terminator      : SEMI | GO_STMT ;

stopBeforeTerminator
    : { _input.LA(1) == SEMI || _input.LA(1) == GO_STMT || _input.LA(1) == IntStream.EOF }?
    ;

blobUntilGoOrEof
    : ( { _input.LA(1) != GO_STMT && _input.LA(1) != IntStream.EOF }? . )+
    ;

blobUntilRparen
    : ( LPAREN blobUntilRparen RPAREN | ~RPAREN )* ;

stopBeforeCommaOrRparen
    : { _input.LA(1) == COMMA || _input.LA(1) == RPAREN }?
    ;

opaqueUntilCommaOrRparen
    : ( . )+? stopBeforeCommaOrRparen
    ;

fkActions
    : (ON DELETE (NO ACTION | CASCADE | SET NULLX | SET DEFAULT))?
      (ON UPDATE (NO ACTION | CASCADE | SET NULLX | SET DEFAULT))?
    ;

/* ---------- Common identifiers / qualified names ---------- */
schemaQualifiedName : (schemaName DOT)? objectName ;
schemaName          : BRACKET_ID | DOUBLE_QUOTED_ID | IDENTIFIER ;
objectName          : BRACKET_ID | DOUBLE_QUOTED_ID | IDENTIFIER ;
identifier          : BRACKET_ID | DOUBLE_QUOTED_ID | IDENTIFIER | AT_ID ;
qualifiedName       : identifier (DOT identifier)? ;
identifierLike      : BRACKET_ID | DOUBLE_QUOTED_ID | IDENTIFIER ;

/* ============================== Lexer rules ============================== */
fragment A:[aA]; fragment B:[bB]; fragment C:[cC]; fragment D:[dD]; fragment E:[eE];
fragment F:[fF]; fragment G:[gG]; fragment H:[hH]; fragment I:[iI]; fragment J:[jJ];
fragment K:[kK]; fragment L:[lL]; fragment M:[mM]; fragment N:[nN]; fragment O:[oO];
fragment P:[pP]; fragment Q:[qQ]; fragment R:[rR]; fragment S:[sS]; fragment T:[tT];
fragment U:[uU]; fragment V:[vV]; fragment W:[wW]; fragment X:[xX]; fragment Y:[yY];
fragment Z:[zZ];

/* Keywords */
CREATE          : C R E A T E;
OR              : O R;
REPLACE         : R E P L A C E;
ALTER           : A L T E R;
DATABASE        : D A T A B A S E;
USE             : U S E;
DROP            : D R O P;
TABLE           : T A B L E;
SCHEMA          : S C H E M A;
AUTHORIZATION   : A U T H O R I Z A T I O N;
VIEW            : V I E W;
FUNCTION        : F U N C T I O N;
PROCEDURE       : P R O C E D U R E;
TRIGGER         : T R I G G E R;
RETURNS         : R E T U R N S;
AS              : A S;
BEGIN           : B E G I N;
END             : E N D;
WITH            : W I T H;
PRIMARY         : P R I M A R Y;
KEY             : K E Y;
UNIQUE          : U N I Q U E;
FOREIGN         : F O R E I G N;
REFERENCES      : R E F E R E N C E S;
CHECK           : C H E C K;
NOT             : N O T;
NULLX           : N U L L;
DEFAULT         : D E F A U L T;
ASC             : A S C;
DESC            : D E S C;
INDEX           : I N D E X;
CLUSTERED       : C L U S T E R E D;
NONCLUSTERED    : N O N C L U S T E R E D;
ON              : O N;
INCLUDE         : I N C L U D E;
WHERE           : W H E R E;
SELECT          : S E L E C T;
INSERT          : I N S E R T;
UPDATE          : U P D A T E;
DELETE          : D E L E T E;
TRUE            : T R U E;
FALSE           : F A L S E;
EXEC            : E X E C;
SET             : S E T;
PRINT           : P R I N T;
IF              : I F;
ELSE            : E L S E;
SYNONYM         : S Y N O N Y M;
SEQUENCE        : S E Q U E N C E;
TYPE            : T Y P E;
MERGE           : M E R G E;
ALL             : A L L;
AFTER           : A F T E R;
FOR             : F O R;
PERSISTED       : P E R S I S T E D;
REBUILD         : R E B U I L D;
CACHE           : C A C H E;
CYCLE           : C Y C L E;
MINVALUE        : M I N V A L U E;
MAXVALUE        : M A X V A L U E;
INCREMENT       : I N C R E M E N T;
START           : S T A R T;
FROM            : F R O M;
BY              : B Y;
CONSTRAINT      : C O N S T R A I N T;
NO              : N O;
ADD             : A D D;
RETURN          : R E T U R N;
OFF             : O F F;
THROW           : T H R O W;
TRY             : T R Y;
CATCH           : C A T C H;
TRAN            : T R A N;
TRANSACTION     : T R A N S A C T I O N;
COMMIT          : C O M M I T;
ROLLBACK        : R O L L B A C K;
READONLY        : R E A D O N L Y;
OUTPUT          : O U T P U T;
OUT             : O U T;
VARYING         : V A R Y I N G;
ENCRYPTION      : E N C R Y P T I O N;
RECOMPILE       : R E C O M P I L E;
EXECUTE         : E X E C U T E;
CALLER          : C A L L E R;
SELF            : S E L F;
OWNER           : O W N E R;
EXISTS          : E X I S T S;
IDENTITY        : I D E N T I T Y;
ROWGUIDCOL      : R O W G U I D C O L;
COLLATE         : C O L L A T E;
SPARSE          : S P A R S E;
FILESTREAM      : F I L E S T R E A M;
MASKED          : M A S K E D;
GENERATED       : G E N E R A T E D;
ALWAYS          : A L W A Y S;
ROW             : R O W;
HIDDEN_KW       : H I D D E N;
XML             : X M L;
COLLECTION      : C O L L E C T I O N;
ROLE            : R O L E;
USER            : U S E R;
WITHOUT         : W I T H O U T;
LOGIN           : L O G I N;
PARTITION       : P A R T I T I O N;
SCHEME          : S C H E M E;
RANGE           : R A N G E;
LEFT            : L E F T;
RIGHT           : R I G H T;
VALUES          : V A L U E S;
OBJECT          : O B J E C T;
TO              : T O;
TEXTIMAGE_ON    : T E X T I M A G E '_' O N;
FILESTREAM_ON   : F I L E S T R E A M '_' O N;
SCHEMABINDING   : S C H E M A B I N D I N G ;
VIEW_METADATA   : V I E W '_' M E T A D A T A ;
COLUMNSTORE     : C O L U M N S T O R E ;
DECLARE         : D E C L A R E ;
GRANT           : G R A N T ;
REVOKE          : R E V O K E ;
DENY            : D E N Y ;
ACTION          : A C T I O N;
CASCADE         : C A S C A D E;

// ======= Operators & symbols =======
SEMI        :';';
LPAREN      :'(';
RPAREN      :')';
COMMA       :',';
DOT         :'.';
CONCAT      :'||';
PLUS        :'+';
MINUS       :'-';
STAR        :'*';
SLASH       :'/';
PERCENT     :'%';
CARET       :'^';
EQ          :'=';
LT          :'<';
LTE         :'<=';
GT          :'>';
GTE         :'>=';
NEQ         :'!=' | '<>';
DCOLON      : '::';
COLON       : ':';

/* Identifier & literal */
GO_STMT
    : {atLineStartAfterWs()}?
      [ \t]* G O [ \t]* [0-9]* [ \t]*
      ( '--' ~[\r\n]* )?
      ( '\r'? '\n' | EOF )
    ;
BRACKET_ID       : '[' (~[\]\r\n])+ ']' ;
DOUBLE_QUOTED_ID : '"' ( '""' | ~["\r\n] )* '"' ;
IDENTIFIER       : [A-Za-z_$#][A-Za-z_0-9$#]* ;
AT_ID            : '@' '@'? [A-Za-z_][A-Za-z_0-9$#]* ;
INTEGER          : [0-9]+ ;
DECIMAL_LIT      : [0-9]+ '.' [0-9]+ ;
STRING
    : '\'' ( '\'\'' | ~'\'' )* '\''
    ;

NSTRING
    : [Nn]'\'' ( '\'\'' | ~'\'' )* '\''
    ;

/* Bỏ qua khoảng trắng & comment */
LINE_COMMENT  : '--' ~[\r\n]* -> skip ;
BLOCK_COMMENT : '/*' .*? '*/' -> skip ;
WS            : [ \t\r\n]+ -> skip ;