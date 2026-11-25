"""Devuelve intervalos UTF-16 para colorear el documento Swing."""
import sys
from pathlib import Path

# El archivo incluido en Libraries contiene el paquete Python que usa el resaltador.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "lib" / "pygments-2.20.0.jar"))

from pygments.lexer import RegexLexer, words
from pygments.token import Comment, Keyword, Name, Number, Operator, Punctuation, String, Text


# Esta gramática visual tolera texto incompleto mientras se escribe en el editor.
# Estas reglas se usan para pintar, incluso antes de compilar. La entrada oficial de CUP viene de JFlex.
# La frontera de palabra evita colorear como reservada la primera parte de un identificador más largo.
class LexerBeca(RegexLexer):
    tokens = {
        "root": [
            (r"[ \t\r\n]+", Text.Whitespace),
            (r"//[^\r\n]*", Comment.Single),
            (r'"[^"\r\n]*"', String.Double),
            (words(("entrada", "entero", "si", "sino", "mostrar", "y", "o"), suffix=r"(?![A-Za-z_0-9])"), Keyword),
            (r"[A-Za-z_][A-Za-z_0-9]*", Name),
            (r"[0-9]+", Number.Integer),
            (r">=|<=|==|!=|[<>+*-]", Operator),
            (r"[(){};]", Punctuation),
        ]
    }


# La interfaz recibe texto UTF-8. Seguimos su posición original y mandamos solo los intervalos con estilo.
def generar_intervalos():
    fuente = sys.stdin.buffer.read().decode("utf-8")
    posicion = 0
    # get_tokens_unprocessed mantiene la entrada original, incluidas sus tabulaciones y saltos de línea.
    for _, ficha, lexema in LexerBeca().get_tokens_unprocessed(fuente):
        # Python cuenta caracteres Unicode y Swing usa unidades UTF-16.
        longitud = len(lexema.encode("utf-16-le")) // 2
        # Los blancos avanzan la posición, pero se quedan con el color predeterminado.
        if ficha not in Text:
            categoria = "Number" if ficha in Number else "String" if ficha in String else str(ficha).split(".")[1]
            print(f"{posicion}\t{longitud}\t{categoria}")
        posicion += longitud


if __name__ == "__main__":
    generar_intervalos()
