# BecaScript

BecaScript es un proyecto académico de Diseño de Compiladores. Compila reglas editables en español y permite ejecutarlas con distintos datos de entrada. Incluye un lexer generado con JFlex, un parser generado con Java CUP, un árbol de sintaxis abstracta, una tabla de símbolos, cuádruplas, plegado de constantes y un ejecutor de código intermedio.

## Abrir y ejecutar

Usa el JDK 27 configurado en el proyecto. En NetBeans, abre **AnalizadorCompletoEsp** y elige **Run Project**. La clase de arranque es `becascript.Main`.

Para el resaltado del editor, instala Python y asegúrate de que el comando `python` esté en el PATH. Pygments 2.20.0 viene incluido en `lib/pygments-2.20.0.jar` y aparece en **Libraries** de NetBeans. El archivo contiene el paquete Python y su licencia, y el editor carga el paquete desde ese archivo.

Pygments colorea el editor mientras escribes. En **Tokens**, la misma paleta distingue los tipos reconocidos por JFlex y se conserva la posición de cada token.

También puedes usar PowerShell desde la raíz del proyecto:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\becascript.ps1 run
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\becascript.ps1 verify
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\becascript.ps1 example
```

El script genera el lexer y el parser desde `grammar/` antes de compilar. Para actualizar también las copias que usa NetBeans después de editar la gramática, copia los tres archivos Java de `build/becascript/generated/` a `src/becascript/`.

## Demostración

1. Abre el editor. La regla académica ya está cargada y también está disponible en `ejemplos/beca-academica.beca`.
2. Pulsa **Compilar**. Revisa los tokens, el árbol, la tabla de símbolos y las cuádruplas.
3. En **Optimización**, busca una cuádrupla tachada por cada método. Se calcula `40 + 40`, se reutiliza la comparación `promedio >= 80` y se elimina el salto sobre el `sino` vacío de la mención por excelencia.
4. Pulsa **Ejecutar** con promedio 85 y asistencia 92. La salida será «Beca aprobada». Cambia la asistencia a 87 y ejecuta otra vez para obtener «Beca rechazada».
5. Cambia el mínimo de promedio a `90` y vuelve a compilar. Luego cambia `asistencia` a `asistensia` dentro de la condición y vuelve a compilar para ver el diagnóstico semántico.

## Demostrar los tres métodos de optimización

La regla inicial ya demuestra los tres métodos de la Unidad 5. Si el estudiante cumple la regla y tiene promedio de al menos 95, también se muestra «Mención por excelencia». Para ver una segunda demostración, abre `ejemplos/optimizaciones.beca` y pulsa **Compilar**. La pestaña **Optimización** indica cuántas cuádruplas eliminó cada método. Cada instrucción retirada aparece tachada con su motivo.

| Método | Qué hace en el ejemplo |
| --- | --- |
| Plegado de constantes (5.3) | Calcula `80 + 80` durante la compilación. |
| Eliminación de redundancias (5.4) | Reutiliza el temporal de `promedio * 2` dentro de la misma condición. |
| Optimización por mirilla (5.8) | Elimina el salto al final del condicional porque la rama `sino` está vacía y solo hay etiquetas hasta el destino. |

Con promedio 85 y asistencia 92 se muestran «Cumple los requisitos» y «Evaluación terminada». Con promedio 85 y asistencia 87 solo se muestra «Evaluación terminada». El verificador compara la salida del código original y del optimizado y comprueba que se aplique cada método.

## Recorrido del código para la defensa

1. En `grammar/Lexer.flex`, muestra cómo JFlex reconoce los lexemas y conserva su línea y columna.
2. En `grammar/Parser.cup`, muestra cómo las producciones crean el árbol y cómo se define la precedencia de los operadores.
3. En `src/becascript/Main.java`, sigue `compilar`, `Generador` y `ejecutar`. El primero crea la tabla de símbolos, el segundo valida y emite cuádruplas y el tercero interpreta el código intermedio. Dentro de `Generador` queda registrado qué cuádrupla se elimina al plegar una constante.
4. En `Resaltado.java` y `tools/resaltado.py`, explica cómo Pygments devuelve los tramos de color al editor Swing. JFlex sigue proporcionando los tokens del compilador.

`Lexer.java`, `Parser.java` y `sym.java` son archivos generados. Las reglas y acciones que se editan están en las especificaciones de `grammar/`. Java, Swing, JFlex, CUP y Pygments conservan los nombres de sus API.

## Lenguaje

- Declara entradas al comienzo con `entrada entero nombre;`.
- Escribe condiciones con `== != < <= > >=`, `y`, `o` y paréntesis. La precedencia, de mayor a menor, es multiplicación, suma/resta, comparación, `y`, `o`.
- Las expresiones aritméticas admiten `+`, `-` y `*` con enteros de 32 bits. La resta es binaria.
- Cada `si (condición) { ... } sino { ... }` tiene dos bloques y admite anidamiento. `mostrar "texto";` escribe un mensaje.
- Los comentarios empiezan con `//`. Los textos se escriben en una línea y no admiten comillas escapadas.

Los diagnósticos indican la fase, la línea y la columna. El ejemplo usa una regla ficticia. Puedes editar la regla, abrir otro archivo o guardar el código con extensión `.beca`.
