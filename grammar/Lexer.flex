package becascript;
import java_cup.runtime.Symbol;
%%
%class Lexer
%unicode
%cup
%line
%column
%{
    final java.util.List<String> tokens = new java.util.ArrayList<>();
    private Symbol token(int kind) {
        var value = new Main.Tok(yytext(), yyline + 1, yycolumn + 1);
        tokens.add(value.pos() + "  " + Main.tokenName(kind) + "  " + value.text());
        return new Symbol(kind, yyline + 1, yycolumn + 1, value);
    }
%}
%eofval{
    return new Symbol(sym.EOF, yyline + 1, yycolumn + 1, null);
%eofval}
%%
[ \t\r\n]+ {}
"//"[^\r\n]* {}
"entrada" { return token(sym.ENTRADA); }
"entero" { return token(sym.ENTERO); }
"si" { return token(sym.SI); }
"sino" { return token(sym.SINO); }
"mostrar" { return token(sym.MOSTRAR); }
"y" { return token(sym.Y); }
"o" { return token(sym.O); }
"(" { return token(sym.LP); }
")" { return token(sym.RP); }
"{" { return token(sym.LB); }
"}" { return token(sym.RB); }
";" { return token(sym.SEMI); }
">=" { return token(sym.GE); }
"<=" { return token(sym.LE); }
"==" { return token(sym.EQ); }
"!=" { return token(sym.NE); }
">" { return token(sym.GT); }
"<" { return token(sym.LT); }
"+" { return token(sym.PLUS); }
"-" { return token(sym.MINUS); }
"*" { return token(sym.TIMES); }
[a-zA-Z_][a-zA-Z_0-9]* { return token(sym.ID); }
[0-9]+ { return token(sym.NUM); }
\"[^\"\r\n]*\" { return token(sym.STR); }
[^] { throw new Main.Failure("Léxico", yyline + 1, yycolumn + 1, "Carácter inválido: " + yytext()); }
