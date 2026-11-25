package becascript;
import java_cup.runtime.Symbol;
%%
/* JFlex genera Lexer.java desde este archivo. CUP solicita un Symbol por token.
   %line y %column habilitan la ubicación que se usa en los mensajes de error. */
%class Lexer
%unicode
%cup
%line
%column
%{
    // La lista conserva el tipo, el lexema y la posición para la vista Tokens.
    final java.util.List<String> tokens = new java.util.ArrayList<>();
    // JFlex cuenta desde cero. Sumamos uno para mostrar ubicaciones naturales en pantalla.
    // El Symbol de CUP transporta el código de tipo y un Main.Token con el lexema y su posición.
    private Symbol ficha(int tipoToken) {
        var valor = new Main.Token(yytext(), yyline + 1, yycolumn + 1);
        tokens.add(valor.posicion() + "  " + Main.nombreToken(tipoToken) + "  " + valor.texto());
        return new Symbol(tipoToken, yyline + 1, yycolumn + 1, valor);
    }
%}
/* EOF indica a CUP que ya no quedan caracteres. Su posición permite informar si falta cerrar un bloque. */
%eofval{
    return new Symbol(sym.EOF, yyline + 1, yycolumn + 1, null);
%eofval}
%%
// Los espacios y comentarios se omiten. Las palabras reservadas preceden al identificador.
[ \t\r\n]+ {}
"//"[^\r\n]* {}
/* JFlex elige la coincidencia más larga y usa el orden para desempatar.
   Así, «si» es palabra reservada y «siempre» se reconoce como un identificador completo. */
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
/* Los nombres comienzan con letra ASCII o guion bajo. El menos es un operador binario.
   El lexer reconoce la forma del número; la comprobación del rango entero se hace después. */
[a-zA-Z_][a-zA-Z_0-9]* { return ficha(sym.IDENTIFICADOR); }
[0-9]+ { return ficha(sym.LITERAL_ENTERO); }
\"[^\"\r\n]*\" { return ficha(sym.CADENA); }
/* Última regla: un carácter sin coincidencia produce un error en lugar de desaparecer silenciosamente. */
[^] { throw new Main.ErrorCompilacion("Léxico", yyline + 1, yycolumn + 1, "Carácter inválido: " + yytext()); }
