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
    ;

variableDeclaration
    : DECLARE VARIABLE? identifier typeSpec notNull? collation? defaultValue? SEMI
    ;

typeSpec
    : typePart+
    ;

typePart
    : ~( EQ | DEFAULT | NOT | COLLATE | COMMA | SEMI | LPAREN | RPAREN )
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
    : BEGIN statement* END SEMI?
    ;

statement
    : block
    | assignment
    | ifStatement
    | whileStatement
    | leaveStatement
    | continueStatement
    | exitStatement
    | dmlStatement
    | executeStatement
    | autonomousTransaction
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

// Palavras-chave não reservadas do Firebird, que podem ser nome de variável ou label. THEN, ELSE e WHILE são
// reservadas. Manter em sincronia com AstBuilder.isIdentifier.
identifier
    : IDENTIFIER
    | QUOTED_IDENTIFIER
    | RECREATE
    | BREAK
    | CONTINUE
    | DO
    | EXIT
    | IF
    | LEAVE
    | STATEMENT
    ;
