/*
 * Gramática da camada procedural do PSQL do Firebird 5 (ADR 0001).
 *
 * Tipos e expressões são sequências opacas de tokens: o servidor é quem os entende (ADR 0002). A gramática
 * pode ser tolerante, porque a Fonte do banco já foi compilada pelo servidor; o que ela não reconhece é
 * recusado, nunca executado "mais ou menos".
 */
parser grammar PsqlParser;

options { tokenVocab = PsqlLexer; }

procedureDefinition
    : ( CREATE ( OR ALTER )? | ALTER | RECREATE ) PROCEDURE identifier
      inputParameters? outputParameters?
      AS routineBody SEMI? EOF
    ;

inputParameters
    : LPAREN parameterDeclaration ( COMMA parameterDeclaration )* RPAREN
    ;

outputParameters
    : RETURNS LPAREN parameterDeclaration ( COMMA parameterDeclaration )* RPAREN
    ;

parameterDeclaration
    : identifier typeSpec notNull? collation? defaultValue?
    ;

routineBody
    : declaration* block
    ;

declaration
    : variableDeclaration
    | cursorDeclaration
    ;

variableDeclaration
    : DECLARE VARIABLE? identifier typeSpec notNull? collation? defaultValue? SEMI
    ;

// DECLARE c [SCROLL | NO SCROLL] CURSOR FOR (SELECT ...); SCROLL e NO são conferidos pelo AstBuilder.
cursorDeclaration
    : DECLARE identifier ( IDENTIFIER IDENTIFIER? )? CURSOR FOR LPAREN expressionContent* RPAREN SEMI
    ;

typeSpec
    : typePart+
    ;

// CURSOR nunca faz parte de um tipo: é o que separa a declaração de cursor da de variável.
typePart
    : ~( EQ | DEFAULT | NOT | COLLATE | COMMA | SEMI | LPAREN | RPAREN | CURSOR )
    | LPAREN ~RPAREN* RPAREN
    ;

notNull
    : NOT NULL
    ;

collation
    : COLLATE identifier
    ;

defaultValue
    : ( EQ | DEFAULT ) expression
    ;

block
    : BEGIN statement* handler* END SEMI?
    ;

// WHEN <condição>, ... DO statement, no fim do bloco.
handler
    : WHEN errorCondition ( COMMA errorCondition )* DO statement
    ;

// EXCEPTION nome, SQLCODE [-]n, GDSCODE nome, SQLSTATE 'xxxxx' ou ANY. SQLCODE, GDSCODE, SQLSTATE e ANY são
// reservadas (saem como IDENTIFIER) e o AstBuilder confere qual veio.
errorCondition
    : EXCEPTION identifier
    | IDENTIFIER ( OPERATOR? NUMBER | IDENTIFIER | STRING )?
    ;

statement
    : block
    | assignment
    | ifStatement
    | whileStatement
    | forStatement
    | leaveStatement
    | continueStatement
    | exitStatement
    | dmlStatement
    | executeStatement
    | openStatement
    | fetchStatement
    | closeStatement
    | raiseStatement
    | autonomousTransaction
    ;

// FOR SELECT ... [INTO ...] [AS CURSOR c] DO e FOR EXECUTE STATEMENT ... INTO ... DO. Texto opaco até o DO; o
// AstBuilder separa o SELECT (ou o EXECUTE STATEMENT), os alvos do INTO e o nome do cursor.
forStatement
    : label? FOR ( SELECT | WITH | EXECUTE ) forContent* DO statement
    ;

forContent
    : LPAREN expressionContent* RPAREN
    | ~( DO | LPAREN | RPAREN | SEMI )
    ;

openStatement
    : OPEN identifier SEMI
    ;

// FETCH [NEXT | PRIOR | FIRST | LAST | ABSOLUTE n | RELATIVE n FROM] c [INTO ...]; separado pelo AstBuilder.
fetchStatement
    : FETCH ~SEMI+ SEMI
    ;

closeStatement
    : CLOSE identifier SEMI
    ;

// EXCEPTION sozinho relança o erro do handler. A mensagem é qualquer expressão.
raiseStatement
    : EXCEPTION ( identifier ( USING LPAREN expression ( COMMA expression )* RPAREN | expression )? )? SEMI
    ;

// Fatia DML: texto opaco até o ';'. O AstBuilder localiza os :var, os alvos do INTO e as cláusulas recusadas.
dmlStatement
    : ( SELECT | WITH | INSERT | UPDATE | DELETE | MERGE ) ~SEMI* SEMI
    ;

// Opções e INTO separados pelo AstBuilder, como numa Fatia DML.
executeStatement
    : EXECUTE STATEMENT ~SEMI* SEMI
    ;

// IN AUTONOMOUS TRANSACTION DO (AUTONOMOUS e TRANSACTION não são reservadas). Reconhecido só para o Pré-check.
autonomousTransaction
    : IN identifier identifier DO statement
    ;

assignment
    : COLON? identifier EQ expression SEMI
    ;

// O ELSE fica com o IF mais próximo (o ANTLR escolhe a alternativa gulosa), como no Firebird.
ifStatement
    : IF LPAREN condition RPAREN THEN statement ( ELSE statement )?
    ;

// O Firebird só aceita label em laço; num BEGIN ... END ele é recusado.
whileStatement
    : label? WHILE LPAREN condition RPAREN DO statement
    ;

label
    : identifier COLON
    ;

// BREAK é o sinônimo antigo de LEAVE, ainda aceito pelo Firebird 5, sem label.
leaveStatement
    : LEAVE identifier? SEMI
    | BREAK SEMI
    ;

continueStatement
    : CONTINUE identifier? SEMI
    ;

exitStatement
    : EXIT SEMI
    ;

// Condição opaca entre os parênteses do IF/WHILE; o servidor aplica a lógica de três valores.
condition
    : expressionContent+
    ;

// Expressão opaca: tokens até ';', ',' ou ')' fora de parênteses.
expression
    : expressionPart+
    ;

expressionPart
    : LPAREN expressionContent* RPAREN
    | ~( SEMI | COMMA | LPAREN | RPAREN )
    ;

expressionContent
    : LPAREN expressionContent* RPAREN
    | ~( LPAREN | RPAREN )
    ;

// Palavras-chave não reservadas do Firebird, que podem ser nome de variável ou label. THEN, ELSE, WHILE, FOR,
// OPEN, FETCH, CLOSE, CURSOR, USING e WHEN são reservadas. Manter em sincronia com AstBuilder.isIdentifier.
identifier
    : IDENTIFIER
    | QUOTED_IDENTIFIER
    | RECREATE
    | BREAK
    | CONTINUE
    | DO
    | EXCEPTION
    | EXIT
    | IF
    | LEAVE
    | STATEMENT
    ;
