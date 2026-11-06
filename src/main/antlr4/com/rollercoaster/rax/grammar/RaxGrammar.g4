grammar RaxGrammar;

options {
    caseInsensitive = true;
}

// Top-Level Expression Precedence Hierarchy
raxExpression
    : expression EOF
    ;

varDeclaration
    : VAR ID EQ expression
    ;

expression
    : varDeclaration+ RETURN expression                                  # LetExpr
    | primary                                                             # PrimaryExpr
    | (PLUS | MINUS) expression                                           # UnaryExpr
    | expression CARET expression                                         # PowerExpr
    | expression (STAR | SLASH) expression                                # MultDivExpr
    | expression (PLUS | MINUS) expression                                # AddSubExpr
    | expression CONCAT expression                                        # ConcatExpr
    | expression (EQ | EQ_STRICT | NEQ | LT | LTE | GT | GTE) expression  # ComparisonExpr
    | expression IN (tableConstructor | expression)                       # InExpr
    | expression (AND | OR) expression                                    # LogicalExpr
    ;

// Primary Expressions & Function Calls
primary
    : NUMBER                                                       # LiteralNumber
    | STRING_LIT                                                   # LiteralString
    | tableRef? COLUMN_REF                                         # ColumnOrMeasure
    | (ID | NOT) LPAREN (expression (ARG_SEP expression)*)? RPAREN # FunctionCall
    | ID                                                           # VariableReference
    | QUOTED_TABLE                                                 # QuotedTableReference
    | LPAREN expression RPAREN                                     # GroupedExpr
    | tableConstructor                                             # TableLiteral
    ;

tableRef
    : ID
    | QUOTED_TABLE
    ;

// Table Constructors & Row Tuples
tableConstructor
    : LBRACE (tableRow (ARG_SEP tableRow)*)? RBRACE
    ;

tableRow
    : expression
    | LPAREN expression (ARG_SEP expression)* RPAREN
    ;

// 1. Reserved Keywords
VAR          : 'VAR';
RETURN       : 'RETURN';
IN           : 'IN';
NOT          : 'NOT';

// 2. Delimiters & Punctuation (Supports ';' and ',')
LPAREN       : '(';
RPAREN       : ')';
LBRACE       : '{';
RBRACE       : '}';
ARG_SEP      : ',' | ';';

// 3. Operators (Sorted by Category)
// Arithmetic & Concatenation
PLUS         : '+';
MINUS        : '-';
STAR         : '*';
SLASH        : '/';
CARET        : '^';
CONCAT       : '&';

// Comparison
EQ_STRICT    : '==';
EQ           : '=';
NEQ          : '<>' | '!=';
LT           : '<';
LTE          : '<=';
GT           : '>';
GTE          : '>=';

// Logical
AND          : '&&';
OR           : '||';

// 4. Identifiers & Entity Literals
COLUMN_REF   : '[' (~[\]\r\n] | ']]')+ ']';               // Bracketed column/measure with ']]' escape
QUOTED_TABLE : '\'' (~['\r\n] | '\'\'')+ '\'';             // Table name with '\'' escape
ID           : [\p{L}_][\p{L}\p{Nd}_]*;                    // Unicode identifier supporting localized strings

// 5. Scalar Literals & Comments
NUMBER       : [0-9]+ ('.' [0-9]+)?;
STRING_LIT   : '"' (~["] | '""')* '"';                     // Escaped strings with '""'
SL_COMMENT   : ('//' | '--') ~[\r\n]* -> skip;             // Single-line comments
ML_COMMENT   : '/*' .*? '*/'          -> skip;             // Multi-line comments
WS           : [ \t\r\n]+             -> skip;             // Whitespace
