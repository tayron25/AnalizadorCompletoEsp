# BecaScript

BecaScript es un proyecto académico de Diseño de Compiladores. Compila reglas editables en español y permite ejecutarlas con distintos datos de entrada. Incluye un lexer generado con JFlex, un parser generado con Java CUP, un árbol de sintaxis abstracta, una tabla de símbolos, cuádruplas, plegado de constantes y un ejecutor de código intermedio.

## Abrir y ejecutar

Usa el JDK 27 configurado en el proyecto. En NetBeans, abre **AnalizadorCompletoEsp** y elige **Run Project**. La clase de arranque es `becascript.Main`.

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
3. En **Optimización**, observa cómo el compilador calcula `40 + 40` y usa `80` directamente, eliminando una instrucción.
4. Pulsa **Ejecutar** con promedio 85 y asistencia 92. La salida será «Beca aprobada». Cambia la asistencia a 87 y ejecuta otra vez para obtener «Beca rechazada».
5. Cambia el mínimo de promedio a `90` y vuelve a compilar. Luego cambia `asistencia` a `asistensia` dentro de la condición y vuelve a compilar para ver el diagnóstico semántico.

## Lenguaje

- Declara entradas al comienzo con `entrada entero nombre;`.
- Escribe condiciones con `== != < <= > >=`, `y`, `o` y paréntesis. La precedencia, de mayor a menor, es multiplicación, suma/resta, comparación, `y`, `o`.
- Las expresiones aritméticas admiten `+`, `-` y `*` con enteros de 32 bits. La resta es binaria.
- Cada `si (condición) { ... } sino { ... }` tiene dos bloques y admite anidamiento. `mostrar "texto";` escribe un mensaje.
- Los comentarios empiezan con `//`. Los textos se escriben en una línea y no admiten comillas escapadas.

Los diagnósticos indican la fase, la línea y la columna. El ejemplo usa una regla ficticia. Puedes editar la regla, abrir otro archivo o guardar el código con extensión `.beca`.
