package becascript;
import java_cup.runtime.Symbol;
%%
%class Lexer
%unicode
%cup
%line
%column
%{
    // La lista conserva el tipo, el lexema y la posición para la vista Tokens.
    final java.util.List<String> tokens = new java.util.ArrayList<>();
    private Symbol ficha(int tipoToken) {
        var valor = new Main.Token(yytext(), yyline + 1, yycolumn + 1);
        tokens.add(valor.posicion() + "  " + Main.nombreToken(tipoToken) + "  " + valor.texto());
        return new Symbol(tipoToken, yyline + 1, yycolumn + 1, valor);
    }
%}
%eofval{
    return new Symbol(sym.EOF, yyline + 1, yycolumn + 1, null);
%eofval}
%%
// Los espacios y comentarios se omiten. Las palabras reservadas preceden al identificador.
[ \t\r\n]+ {}
"//"[^\r\n]* {}
"entrada" { return ficha(sym.ENTRADA); }
"entero" { return ficha(sym.ENTERO); }
"si" { return ficha(sym.SI); }
"sino" { return ficha(sym.SINO); }
"mostrar" { return ficha(sym.MOSTRAR); }
"y" { return ficha(sym.Y); }
"o" { return ficha(sym.O); }
"(" { return ficha(sym.PARENTESIS_ABIERTO); }
")" { return ficha(sym.PARENTESIS_CERRADO); }
"{" { return ficha(sym.LLAVE_ABIERTA); }
"}" { return ficha(sym.LLAVE_CERRADA); }
";" { return ficha(sym.PUNTO_Y_COMA); }
">=" { return ficha(sym.MAYOR_O_IGUAL); }
"<=" { return ficha(sym.MENOR_O_IGUAL); }
"==" { return ficha(sym.IGUAL); }
"!=" { return ficha(sym.DISTINTO); }
">" { return ficha(sym.MAYOR); }
"<" { return ficha(sym.MENOR); }
"+" { return ficha(sym.SUMA); }
"-" { return ficha(sym.RESTA); }
"*" { return ficha(sym.MULTIPLICACION); }
[a-zA-Z_][a-zA-Z_0-9]* { return ficha(sym.IDENTIFICADOR); }
[0-9]+ { return ficha(sym.LITERAL_ENTERO); }
\"[^\"\r\n]*\" { return ficha(sym.CADENA); }
[^] { throw new Main.ErrorCompilacion("Léxico", yyline + 1, yycolumn + 1, "Carácter inválido: " + yytext()); }
