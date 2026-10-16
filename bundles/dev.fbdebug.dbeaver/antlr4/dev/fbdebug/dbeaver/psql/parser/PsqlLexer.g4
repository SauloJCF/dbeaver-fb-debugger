/*
 * Léxico da camada procedural do PSQL do Firebird 5.
 *
 * Só as palavras que estruturam a camada procedural ou abrem uma Fatia DML viram tokens próprios. O resto
 * (funções, tipos, palavras de expressão e de SQL) sai como IDENTIFIER e é repassado ao servidor como texto
 * (Modo Careful).
 */
lexer grammar PsqlLexer;

options { caseInsensitive = true; }

ALTER     : 'ALTER';
AS        : 'AS';
BEGIN     : 'BEGIN';
BREAK     : 'BREAK';
COLLATE   : 'COLLATE';
CONTINUE  : 'CONTINUE';
CREATE    : 'CREATE';
DECLARE   : 'DECLARE';
DEFAULT   : 'DEFAULT';
DELETE    : 'DELETE';
DO        : 'DO';
ELSE      : 'ELSE';
END       : 'END';
EXECUTE   : 'EXECUTE';
EXIT      : 'EXIT';
IF        : 'IF';
IN        : 'IN';
INSERT    : 'INSERT';
INTO      : 'INTO';
LEAVE     : 'LEAVE';
MERGE     : 'MERGE';
NOT       : 'NOT';
NULL      : 'NULL';
OR        : 'OR';
PROCEDURE : 'PROCEDURE';
RECREATE  : 'RECREATE';
RETURNS   : 'RETURNS';
SELECT    : 'SELECT';
STATEMENT : 'STATEMENT';
THEN      : 'THEN';
UPDATE    : 'UPDATE';
VARIABLE  : 'VARIABLE';
WHILE     : 'WHILE';
WITH      : 'WITH';

SEMI   : ';';
COLON  : ':';
COMMA  : ',';
DOT    : '.';
LPAREN : '(';
RPAREN : ')';
EQ     : '=';

OPERATOR
    : '||' | '<>' | '!=' | '^=' | '~=' | '<=' | '>=' | '!<' | '^<' | '~<' | '!>' | '^>' | '~>'
    | '<' | '>' | '+' | '-' | '*' | '/'
    ;

STRING : '\'' ( ~'\'' | '\'\'' )* '\'';

// Literais Q (FB 3+), nas formas com delimitador de par.
Q_STRING
    : 'Q\'(' .*? ')\''
    | 'Q\'[' .*? ']\''
    | 'Q\'{' .*? '}\''
    | 'Q\'<' .*? '>\''
    ;

HEX_STRING : 'X\'' [0-9A-F]* '\'';

NUMBER
    : '0X' [0-9A-F]+
    | ( [0-9]+ ( '.' [0-9]* )? | '.' [0-9]+ ) ( 'E' [+-]? [0-9]+ )?
    ;

IDENTIFIER        : [A-Z] [A-Z0-9_$]*;
QUOTED_IDENTIFIER : '"' ( ~'"' | '""' )* '"';

LINE_COMMENT  : '--' ~[\r\n]* -> channel(HIDDEN);
BLOCK_COMMENT : '/*' .*? '*/' -> channel(HIDDEN);
WS            : [ \t\r\n\f]+ -> channel(HIDDEN);

// Qualquer outro caractere ('?', '[', ...) segue como texto de expressão.
OTHER : .;
