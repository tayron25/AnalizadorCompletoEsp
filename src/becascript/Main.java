package becascript;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * BecaScript traduce reglas de becas a cuádruplas y luego puede ejecutarlas.
 * La compilación produce código intermedio. Un intérprete en Java lo ejecuta, sin generar código de CPU.
 * Flujo: fuente → JFlex → CUP → árbol → validación y generación → optimización → ejecución.
 * La interfaz muestra cada resultado intermedio para poder explicar el proceso.
 */
public final class Main {
    // El lexer guarda el lexema y su ubicación para informar errores al usuario.
    public record Token(String texto, int linea, int columna) {
        String posicion() { return linea + ":" + columna; }
    }
    // La fase indica quién detectó el problema. Léxico: carácter inválido.
    // Sintáctico: tokens en un orden inválido. Semántico: nombre o tipo incorrecto.
    public static final class ErrorCompilacion extends RuntimeException {
        ErrorCompilacion(String fase, int linea, int columna, String mensaje) {
            super(fase + " (" + linea + ":" + columna + ") " + mensaje);
        }
        ErrorCompilacion(Token ficha, String mensaje) { this("Semántico", ficha.linea(), ficha.columna(), mensaje); }
    }
    // Estos nodos forman el árbol de sintaxis abstracta que devuelve CUP.
    // El programa guarda las declaraciones antes de las sentencias que las usan.
    // Un AST conserva la estructura del programa; no necesita guardar cada llave y separador.
    public record Programa(List<Token> entradas, List<Sentencia> sentencias) {}
    // Una sentencia es una acción completa: mostrar un mensaje o elegir entre dos ramas.
    public sealed interface Sentencia permits Condicional, Mostrar {}
    public record Condicional(Expresion condicion, List<Sentencia> ramaSi, List<Sentencia> ramaSino) implements Sentencia {}
    public record Mostrar(String mensaje) implements Sentencia {}
    // Una expresión produce un valor. Un nodo binario tiene dos hijos; una variable y un número son hojas.
    // «sealed» limita los casos y permite que Java compruebe que los switch cubran todos los nodos.
    public sealed interface Expresion permits OperacionBinaria, Variable, Numero {}
    public record OperacionBinaria(Expresion izquierda, Token operador, Expresion derecha) implements Expresion {}
    public record Variable(Token ficha) implements Expresion {}
    public record Numero(Token ficha) implements Expresion {}

    // Una referencia apunta a una entrada o a un temporal; los literales se guardan directamente.
    record Referencia(String nombre) {}
    // El tipo se comprueba al compilar. El operando puede ser inmediato (80, true) o una referencia ($t1).
    record Valor(Class<?> tipo, Object operando) {}
    // Formato: (operador, primero, segundo, destino). Un campo sin uso se representa con un guion.
    // Para un cálculo, el destino es un temporal. Para un salto, es el nombre de una etiqueta.
    record Cuadrupla(String operador, Object primero, Object segundo, String destino) {
        @Override public String toString() {
            return "(" + operador + ", " + representar(primero) + ", " + representar(segundo) + ", " + representar(destino, false) + ")";
        }
        private static String representar(Object valor) { return representar(valor, true); }
        private static String representar(Object valor, boolean entrecomillar) {
            return switch (valor) {
                case null -> "–";
                case Referencia ref -> ref.nombre();
                case String texto -> entrecomillar ? "\"" + texto + "\"" : texto;
                default -> valor.toString();
            };
        }
    }
    // La clasificación se usa para contar las eliminaciones y justificar cada una en la interfaz.
    enum Metodo {
        // Unidad 5: cálculo anticipado (5.3), eliminación de cálculos repetidos (5.4)
        // y reescritura de un patrón local de cuádruplas (5.8). No hay ciclos en la gramática actual.
        PLEGADO("Plegado de constantes"),
        REDUNDANCIAS("Eliminación de redundancias"),
        MIRILLA("Optimización por mirilla (peephole)");

        final String nombre;
        Metodo(String nombre) { this.nombre = nombre; }
    }

    // Guardamos la instrucción exacta del original y la razón de su eliminación.
    // El temporal que aparece en la razón puede pertenecer a la versión optimizada.
    record Eliminada(Cuadrupla original, Metodo metodo, String motivo) {}
    // Una compilación contiene todas las vistas y puede reutilizarse con distintos datos de entrada.
    record Compilacion(Programa programa, List<String> tokens, Map<String, Token> simbolos,
                       List<Cuadrupla> original, List<Cuadrupla> optimizado, List<Eliminada> eliminadas) {}

    // CUP trabaja con códigos enteros de sym; para la vista Tokens mostramos su nombre legible.
    static String nombreToken(int tipoToken) {
        for (var campo : sym.class.getFields()) {
            try {
                if (campo.getType() == int.class && campo.getInt(null) == tipoToken) return campo.getName();
            } catch (IllegalAccessException excepcion) {
                throw new IllegalStateException(excepcion);
            }
        }
        throw new IllegalArgumentException("Token desconocido: " + tipoToken);
    }

    /** Analiza el texto, crea la tabla de símbolos y genera las dos versiones del código intermedio. */
    static Compilacion compilar(String fuente) throws Exception {
        // CUP solicita los tokens a JFlex mientras analiza y devuelve el nodo raíz en parse().value.
        var analizador = new Lexer(new StringReader(fuente));
        var programa = (Programa) new Parser(analizador).parse().value;
        // La tabla de símbolos registra las entradas declaradas, en el mismo orden que en la fuente.
        // Es distinta de la memoria de ejecución: en este punto sabemos los nombres, no sus valores.
        var simbolos = new LinkedHashMap<String, Token>();
        for (Token entrada : programa.entradas()) {
            if (simbolos.putIfAbsent(entrada.texto(), entrada) != null) {
                throw new ErrorCompilacion(entrada, "Entrada duplicada: " + entrada.texto());
            }
        }
        // La identidad del nodo permite recuperar la cuádrupla exacta, incluso si se repite la expresión.
        var originales = new IdentityHashMap<OperacionBinaria, Cuadrupla>();
        // La primera pasada valida todo el árbol, incluidas las ramas que podrían no ejecutarse.
        // Generamos el original como referencia y repetimos el recorrido aplicando las optimizaciones.
        // La mirilla se ejecuta al final porque necesita ver las cuádruplas vecinas ya emitidas.
        var original = new Generador(simbolos, false, originales).generar(programa);
        var optimizador = new Generador(simbolos, true, originales);
        var generado = optimizador.generar(programa);
        var optimizado = aplicarMirilla(generado, optimizador.eliminadas);
        return new Compilacion(programa, analizador.tokens, simbolos, original, optimizado,
                List.copyOf(optimizador.eliminadas));
    }

    /** Examina una ventana de hasta tres cuádruplas para detectar saltos redundantes. */
    private static List<Cuadrupla> aplicarMirilla(List<Cuadrupla> codigo, List<Eliminada> eliminadas) {
        // La ventana contiene el salto y hasta dos instrucciones siguientes.
        // Si se llega al destino pasando solo por etiquetas, avanzar y saltar tienen el mismo efecto.
        // Las etiquetas se conservan porque otros saltos pueden seguir apuntando a ellas.
        var optimizado = new ArrayList<Cuadrupla>();
        for (int indice = 0; indice < codigo.size(); indice++) {
            Cuadrupla actual = codigo.get(indice);
            boolean caeEnDestino = false;
            if (actual.operador().equals("SALTO")) {
                // Las etiquetas solo marcan posiciones. No se puede saltar sobre una operación real.
                for (int siguiente = indice + 1; siguiente < codigo.size() && siguiente <= indice + 2; siguiente++) {
                    Cuadrupla vecina = codigo.get(siguiente);
                    if (!vecina.operador().equals("ETIQUETA")) break;
                    if (vecina.destino().equals(actual.destino())) {
                        caeEnDestino = true;
                        break;
                    }
                }
            }
            if (caeEnDestino) {
                eliminadas.add(new Eliminada(actual, Metodo.MIRILLA,
                        "El flujo llega a " + actual.destino() + " pasando solo por etiquetas."));
            } else {
                optimizado.add(actual);
            }
        }
        return List.copyOf(optimizado);
    }

    // Clave de redundancias: misma operación, mismos operandos y mismo orden.
    // El record compara los componentes por valor. No se intenta demostrar equivalencias algebraicas.
    record Calculo(String operador, Object primero, Object segundo) {}

    /** Recorre el árbol, verifica los tipos y traduce las sentencias a cuádruplas. */
    private static final class Generador {
        private final Map<String, Token> simbolos;
        private final boolean aplicarOptimizaciones;
        private final List<Cuadrupla> codigo = new ArrayList<>();
        private final Map<OperacionBinaria, Cuadrupla> originales;
        private final List<Eliminada> eliminadas = new ArrayList<>();
        // Este mapa indica en qué temporal está un cálculo disponible dentro de la condición actual.
        private final Map<Calculo, Referencia> calculosDisponibles = new HashMap<>();
        // Numeración local a esta pasada: $t1, $t2... guardan resultados; L1, L2... nombran destinos.
        private int temporal;
        private int etiqueta;

        Generador(Map<String, Token> simbolos, boolean aplicarOptimizaciones, Map<OperacionBinaria, Cuadrupla> originales) {
            this.simbolos = simbolos;
            this.aplicarOptimizaciones = aplicarOptimizaciones;
            this.originales = originales;
        }

        // Recorremos las sentencias en orden y entregamos una lista que no se modifica luego.
        List<Cuadrupla> generar(Programa programa) {
            emitir(programa.sentencias());
            return List.copyOf(codigo);
        }

        // Un bloque emite sus instrucciones en el orden de la fuente. Un bloque vacío no emite acciones.
        // «mostrar» se traduce directamente a MOSTRAR; el condicional se traduce con dos etiquetas.
        private void emitir(List<Sentencia> sentencias) {
            for (Sentencia sentencia : sentencias) {
                switch (sentencia) {
                    case Mostrar mostrar -> codigo.add(new Cuadrupla("MOSTRAR", mostrar.mensaje(), null, null));
                    case Condicional condicional -> {
                        // Reutilizamos temporales solo dentro de esta condición, donde todos los cálculos se ejecutan.
                        calculosDisponibles.clear();
                        // Al terminar de emitir esta expresión ya tendremos el valor que usa JF.
                        Valor condicion = expresion(condicional.condicion());
                        if (condicion.tipo() != Boolean.class) {
                            throw new ErrorCompilacion(origen(condicional.condicion()), "La condición de si debe ser booleana");
                        }
                        String etiquetaSino = "L" + ++etiqueta;
                        String etiquetaFin = "L" + ++etiqueta;
                        // JF salta a la rama sino cuando la condición es falsa.
                        codigo.add(new Cuadrupla("JF", condicion.operando(), null, etiquetaSino));
                        emitir(condicional.ramaSi());
                        // Si se ejecutó la rama verdadera, el salto al final evita ejecutar también la rama sino.
                        codigo.add(new Cuadrupla("SALTO", null, null, etiquetaFin));
                        codigo.add(new Cuadrupla("ETIQUETA", null, null, etiquetaSino));
                        emitir(condicional.ramaSino());
                        codigo.add(new Cuadrupla("ETIQUETA", null, null, etiquetaFin));
                    }
                }
            }
        }

        // Recorrido de abajo hacia arriba: resuelve los hijos, verifica sus tipos y luego trata el operador.
        // Los números son inmediatos; las variables son referencias a valores que se ingresarán al ejecutar.
        private Valor expresion(Expresion expresion) {
            return switch (expresion) {
                case Numero numero -> {
                    try {
                        yield new Valor(Integer.class, Integer.parseInt(numero.ficha().texto()));
                    } catch (NumberFormatException excepcion) {
                        throw new ErrorCompilacion(numero.ficha(), "Entero fuera del rango de 32 bits");
                    }
                }
                case Variable variable -> {
                    String nombre = variable.ficha().texto();
                    if (!simbolos.containsKey(nombre)) {
                        throw new ErrorCompilacion(variable.ficha(), "Entrada no declarada: " + nombre);
                    }
                    yield new Valor(Integer.class, new Referencia(nombre));
                }
                case OperacionBinaria operacion -> {
                    Valor primero = expresion(operacion.izquierda());
                    Valor segundo = expresion(operacion.derecha());
                    String operador = operacion.operador().texto();
                    boolean logica = operador.equals("y") || operador.equals("o");
                    boolean aritmetica = operador.equals("+") || operador.equals("-") || operador.equals("*");
                    // +, - y * reciben enteros y devuelven entero. Las comparaciones reciben enteros y
                    // devuelven booleano. «y» y «o» reciben booleanos. El if exige resultado booleano.
                    Class<?> esperado = logica ? Boolean.class : Integer.class;
                    if (primero.tipo() != esperado || segundo.tipo() != esperado) {
                        throw new ErrorCompilacion(operacion.operador(), "Operandos incompatibles para " + operador);
                    }
                    Class<?> resultado = aritmetica ? Integer.class : Boolean.class;
                    // Plegado de constantes: si ambos operandos ya se conocen, se reemplaza la operación por su resultado.
                    if (aplicarOptimizaciones && !(primero.operando() instanceof Referencia) && !(segundo.operando() instanceof Referencia)) {
                        // Usamos calcular(), la misma función de ejecución. Así coinciden las reglas de
                        // enteros de 32 bits, incluso si una operación desborda y Java ajusta el resultado.
                        Object reemplazo = calcular(operador, primero.operando(), segundo.operando());
                        eliminadas.add(new Eliminada(originales.get(operacion), Metodo.PLEGADO,
                                "La operación constante se reemplaza por " + reemplazo + "."));
                        yield new Valor(resultado, reemplazo);
                    }
                    // Si la misma operación ya se emitió, su temporal contiene el resultado que necesitamos.
                    // Es seguro aquí: los operandos son puros y los valores de entrada no se reasignan.
                    // No compartimos temporales entre condiciones, porque alguna rama puede no ejecutarse.
                    var calculo = new Calculo(operador, primero.operando(), segundo.operando());
                    Referencia disponible = calculosDisponibles.get(calculo);
                    if (disponible != null) {
                        eliminadas.add(new Eliminada(originales.get(operacion), Metodo.REDUNDANCIAS,
                                "El cálculo ya se hizo en esta condición. Se reutiliza " + disponible.nombre() + "."));
                        yield new Valor(resultado, disponible);
                    }
                    // Si no se puede optimizar, emitimos la cuádrupla y devolvemos una referencia al resultado.
                    // En la pasada original guardamos la relación entre este nodo y su instrucción exacta.
                    String destino = "$t" + ++temporal;
                    var cuadrupla = new Cuadrupla(operador, primero.operando(), segundo.operando(), destino);
                    codigo.add(cuadrupla);
                    var referencia = new Referencia(destino);
                    if (aplicarOptimizaciones) calculosDisponibles.put(calculo, referencia);
                    else originales.put(operacion, cuadrupla);
                    yield new Valor(resultado, referencia);
                }
            };
        }
    }

    // Ubica el error en el operador de la expresión o en el token de la hoja que se analizó.
    private static Token origen(Expresion expresion) {
        return switch (expresion) {
            case OperacionBinaria operacion -> operacion.operador();
            case Variable variable -> variable.ficha();
            case Numero numero -> numero.ficha();
        };
    }

    // Esta función define la semántica de las operaciones tanto al compilar como al ejecutar.
    // Las expresiones «y» y «o» combinan dos operandos ya calculados. El generador actual evalúa ambos.
    static Object calcular(String operador, Object primero, Object segundo) {
        return switch (operador) {
            case "+" -> (int) primero + (int) segundo;
            case "-" -> (int) primero - (int) segundo;
            case "*" -> (int) primero * (int) segundo;
            case ">" -> (int) primero > (int) segundo;
            case "<" -> (int) primero < (int) segundo;
            case ">=" -> (int) primero >= (int) segundo;
            case "<=" -> (int) primero <= (int) segundo;
            case "==" -> primero.equals(segundo);
            case "!=" -> !primero.equals(segundo);
            case "y" -> (boolean) primero && (boolean) segundo;
            case "o" -> (boolean) primero || (boolean) segundo;
            default -> throw new IllegalArgumentException("Operación desconocida: " + operador);
        };
    }

    // Distingue una referencia a memoria de un literal que ya se puede usar directamente.
    private static Object resolver(Object operando, Map<String,Object> memoria) {
        return operando instanceof Referencia ref ? memoria.get(ref.nombre()) : operando;
    }

    /** Interpreta las cuádruplas. Las etiquetas indican dónde continúan los saltos del programa. */
    static String ejecutar(List<Cuadrupla> codigo, Map<String,Integer> entradas) {
        // Cada ejecución empieza de cero con los datos del usuario. La memoria también guardará temporales.
        // Primero indexamos las etiquetas para obtener el destino de cada salto sin recorrer toda la lista.
        var memoria = new HashMap<String,Object>(entradas);
        var etiquetas = new HashMap<String,Integer>();
        for (int indice = 0; indice < codigo.size(); indice++) {
            if (codigo.get(indice).operador().equals("ETIQUETA")) etiquetas.put(codigo.get(indice).destino(), indice);
        }
        var salida = new StringJoiner("\n");
        // El contador señala la siguiente instrucción. Al asignarle el índice de una etiqueta,
        // el incremento del for continúa justo después de esa etiqueta, que no realiza ninguna acción.
        for (int contadorPrograma = 0; contadorPrograma < codigo.size(); contadorPrograma++) {
            Cuadrupla instruccion = codigo.get(contadorPrograma);
            Object primero = resolver(instruccion.primero(), memoria);
            switch (instruccion.operador()) {
                case "JF" -> {
                    if (!(boolean) primero) contadorPrograma = etiquetas.get(instruccion.destino());
                }
                case "SALTO" -> contadorPrograma = etiquetas.get(instruccion.destino());
                case "ETIQUETA" -> { }
                case "MOSTRAR" -> salida.add((String) primero);
                default -> memoria.put(instruccion.destino(),
                        calcular(instruccion.operador(), primero, resolver(instruccion.segundo(), memoria)));
            }
        }
        return salida.toString();
    }

    // El ejemplo inicial activa una vez cada método: suma constante, comparación duplicada y salto sobre el sino vacío.
    // La segunda condición solo agrega una mención; aprobar o rechazar sigue dependiendo de los dos umbrales iniciales.
    static final String EJEMPLO = """
            // Regla ficticia para demostrar los tres métodos de optimización.
            entrada entero promedio;
            entrada entero asistencia;

            // La comparación del promedio se repite a propósito.
            si (promedio >= (40 + 40) y promedio >= 80 y asistencia >= 90) {
                mostrar "Beca aprobada";
                si (promedio >= 95) {
                    mostrar "Mención por excelencia";
                } sino {
                    // No se imprime una mención cuando el promedio es menor a 95.
                }
            } sino {
                mostrar "Beca rechazada";
            }
            """;

    /** Construye una representación legible del árbol para la pestaña Árbol. */
    private static String arbol(Programa programa) {
        var resultado = new StringBuilder("Programa\n");
        for (Token entrada : programa.entradas()) {
            resultado.append("  Entrada entero ").append(entrada.texto()).append('\n');
        }
        agregarSentencias(resultado, programa.sentencias(), "  ");
        return resultado.toString();
    }

    // La sangría aumenta un nivel para mostrar qué mensajes pertenecen a cada rama del árbol.
    private static void agregarSentencias(StringBuilder resultado, List<Sentencia> sentencias, String sangria) {
        for (Sentencia sentencia : sentencias) {
            switch (sentencia) {
                case Mostrar mostrar -> resultado.append(sangria).append("Mostrar ").append(mostrar.mensaje()).append('\n');
                case Condicional condicional -> {
                    resultado.append(sangria).append("Si\n");
                    agregarExpresion(resultado, condicional.condicion(), sangria + "  ");
                    resultado.append(sangria).append("Entonces\n");
                    agregarSentencias(resultado, condicional.ramaSi(), sangria + "  ");
                    resultado.append(sangria).append("Sino\n");
                    agregarSentencias(resultado, condicional.ramaSino(), sangria + "  ");
                }
            }
        }
    }

    // El operador se muestra arriba de sus hijos. La precedencia ya quedó resuelta por CUP.
    private static void agregarExpresion(StringBuilder resultado, Expresion expresion, String sangria) {
        resultado.append(sangria).append(origen(expresion).texto()).append('\n');
        if (expresion instanceof OperacionBinaria operacion) {
            agregarExpresion(resultado, operacion.izquierda(), sangria + "  ");
            agregarExpresion(resultado, operacion.derecha(), sangria + "  ");
        }
    }

    private static JTextArea area(String texto, boolean editable) {
        var area = new JTextArea(texto);
        area.setEditable(editable);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        area.setTabSize(4);
        return area;
    }

    private static JTextArea pestana(JTabbedPane pestanas, String nombre) {
        var vista = area("", false);
        pestanas.addTab(nombre, new JScrollPane(vista));
        return vista;
    }

    // Usamos JTextPane porque permite tachar rangos específicos, algo que JTextArea no ofrece.
    private static JTextPane pestanaOptimizacion(JTabbedPane pestanas) {
        var vista = Resaltado.panel("", false);
        pestanas.addTab("Optimización", new JScrollPane(vista));
        return vista;
    }

    /** Muestra las operaciones retiradas junto a su sustitución y al código que se ejecutará. */
    static void mostrarOptimizacion(JTextPane vista, Compilacion resultado) {
        // El contador mide cuádruplas generadas, no tiempo de ejecución ni consumo de memoria.
        var texto = new StringBuilder("Métodos disponibles\n");
        for (Metodo metodo : Metodo.values()) {
            long cantidad = resultado.eliminadas().stream().filter(eliminada -> eliminada.metodo() == metodo).count();
            texto.append("• ").append(metodo.nombre).append(": ").append(cantidad).append('\n');
        }
        texto.append('\n')
                .append("Instrucciones: ").append(resultado.original().size())
                .append(" → ").append(resultado.optimizado().size()).append("\n\n")
                .append("Cuádruplas eliminadas\n");
        // Guardamos el inicio y la longitud de cada cuádrupla retirada para tachar solo su texto.
        var intervalos = new ArrayList<int[]>();
        if (resultado.eliminadas().isEmpty()) texto.append("Ninguna en esta compilación.\n");
        for (Eliminada eliminada : resultado.eliminadas()) {
            texto.append(eliminada.metodo().nombre).append('\n');
            int inicio = texto.length();
            texto.append(eliminada.original());
            intervalos.add(new int[] { inicio, texto.length() - inicio });
            texto.append('\n').append(eliminada.motivo()).append("\n\n");
        }
        texto.append("\nCuádruplas optimizadas\n").append(unirLineas(resultado.optimizado()));
        vista.setText(texto.toString());
        var estilo = new javax.swing.text.SimpleAttributeSet();
        javax.swing.text.StyleConstants.setStrikeThrough(estilo, true);
        for (int[] intervalo : intervalos) {
            vista.getStyledDocument().setCharacterAttributes(intervalo[0], intervalo[1], estilo, false);
        }
    }

    private static String unirLineas(List<?> elementos) {
        var union = new StringJoiner("\n");
        for (Object elemento : elementos) union.add(elemento.toString());
        return union.toString();
    }

    // Swing coordina la experiencia. Los métodos estáticos de compilación pueden ejecutarse también por consola.
    private static final class Ventana extends JFrame {
        private final JTextPane editor = Resaltado.panel(EJEMPLO, true);
        private final JTextPane tokens = Resaltado.panel("", false);
        private final JTabbedPane pestanas = new JTabbedPane();
        {
            pestanas.addTab("Tokens", new JScrollPane(tokens));
        }
        private final JTextArea arbol = pestana(pestanas, "Árbol");
        private final JTextArea simbolos = pestana(pestanas, "Símbolos");
        private final JTextArea cuadruplas = pestana(pestanas, "Cuádruplas");
        private final JTextPane optimizado = pestanaOptimizacion(pestanas);
        private final JTextArea salida = pestana(pestanas, "Salida");
        private final JButton ejecutar = new JButton("Ejecutar");
        private final JLabel estado = new JLabel("  Edita una regla y pulsa Compilar.");
        // Recordamos los últimos datos para poder cambiar solo un valor entre dos demostraciones.
        private final Map<String,Integer> ultimasEntradas = new HashMap<>(Map.of("promedio", 85, "asistencia", 92));
        private Compilacion compilacionActual;
        // Esperamos una pausa breve al escribir para evitar iniciar Python en cada pulsación.
        private final javax.swing.Timer colorear = new javax.swing.Timer(220, evento -> resaltarEditor());
        private boolean resaltando;

        Ventana() {
            super("BecaScript");
            var barra = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT));
            var abrir = new JButton("Abrir…");
            var guardar = new JButton("Guardar…");
            var compilar = new JButton("Compilar");
            for (var boton : List.of(abrir, guardar, compilar, ejecutar)) barra.add(boton);
            add(barra, BorderLayout.NORTH);
            var divisor = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(editor), pestanas);
            divisor.setResizeWeight(0.5);
            add(divisor);
            add(estado, BorderLayout.SOUTH);
            ejecutar.setEnabled(false);
            abrir.addActionListener(evento -> gestionarArchivo(false));
            guardar.addActionListener(evento -> gestionarArchivo(true));
            compilar.addActionListener(evento -> compilarEditor());
            ejecutar.addActionListener(evento -> ejecutarPrograma());
            editor.getDocument().addDocumentListener(new DocumentListener() {
                public void insertUpdate(DocumentEvent evento) { cambioFuente(); }
                public void removeUpdate(DocumentEvent evento) { cambioFuente(); }
                // Cambiar un estilo (color) no equivale a modificar la fuente ni invalida la compilación.
                public void changedUpdate(DocumentEvent evento) { }
            });
            colorear.setRepeats(false);
            colorear.start();
            setDefaultCloseOperation(EXIT_ON_CLOSE);
            setSize(1030, 580);
            setLocationRelativeTo(null);
        }

        private void cambioFuente() {
            invalidarCompilacion();
            colorear.restart();
        }

        // Pygments trabaja fuera del hilo de Swing para mantener el editor disponible.
        private void resaltarEditor() {
            if (resaltando) return;
            String fuente = editor.getText();
            resaltando = true;
            new SwingWorker<List<Resaltado.Tramo>, Void>() {
                @Override protected List<Resaltado.Tramo> doInBackground() throws Exception {
                    return Resaltado.analizar(fuente);
                }

                @Override protected void done() {
                    resaltando = false;
                    try {
                        var tramos = get();
                        // Si el usuario editó mientras Python trabajaba, descartamos los intervalos antiguos.
                        if (fuente.equals(editor.getText())) Resaltado.pintar(editor, tramos);
                        editor.setToolTipText(null);
                    } catch (java.util.concurrent.ExecutionException excepcion) {
                        editor.setToolTipText("Resaltado no disponible. Comprueba Python y lib/pygments-2.20.0.jar.");
                    } catch (InterruptedException excepcion) {
                        Thread.currentThread().interrupt();
                    }
                    if (!fuente.equals(editor.getText())) colorear.restart();
                }
            }.execute();
        }

        // Una edición invalida las vistas y la ejecución hasta volver a compilar.
        private void invalidarCompilacion() {
            compilacionActual = null;
            ejecutar.setEnabled(false);
            for (var vista : List.of(tokens, arbol, simbolos, cuadruplas, optimizado, salida)) vista.setText("");
            estado.setText("  Código modificado. Pulsa Compilar.");
        }

        // Abrir y guardar solo leen o escriben texto; la compilación se hace con el botón correspondiente.
        private void gestionarArchivo(boolean guardar) {
            var selector = new JFileChooser();
            int seleccion = guardar ? selector.showSaveDialog(this) : selector.showOpenDialog(this);
            if (seleccion != JFileChooser.APPROVE_OPTION) return;
            Path ruta = selector.getSelectedFile().toPath();
            try {
                if (guardar) {
                    if (Files.exists(ruta) && JOptionPane.showConfirmDialog(this,
                            "¿Reemplazar " + ruta.getFileName() + "?", "Guardar",
                            JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
                    Files.writeString(ruta, editor.getText());
                    estado.setText("  Guardado " + ruta.getFileName());
                } else {
                    editor.setText(Files.readString(ruta));
                }
            } catch (java.io.IOException excepcion) {
                JOptionPane.showMessageDialog(this, excepcion.getMessage(), "Archivo", JOptionPane.ERROR_MESSAGE);
            }
        }

        // Solo habilitamos Ejecutar cuando todas las fases terminaron bien. Si fallan, mostramos el diagnóstico.
        private void compilarEditor() {
            invalidarCompilacion();
            try {
                compilacionActual = compilar(editor.getText());
                Resaltado.pintarTokens(tokens, compilacionActual.tokens());
                arbol.setText(arbol(compilacionActual.programa()));
                var tabla = new StringJoiner("\n");
                compilacionActual.simbolos().forEach((nombre, entrada) -> tabla.add(nombre + "  entero  " + entrada.posicion()));
                simbolos.setText(tabla.toString());
                cuadruplas.setText(unirLineas(compilacionActual.original()));
                mostrarOptimizacion(optimizado, compilacionActual);
                for (var vista : List.of(tokens, arbol, simbolos, cuadruplas, optimizado)) vista.setCaretPosition(0);
                pestanas.setSelectedIndex(0);
                estado.setText("  Compilación correcta. Puedes ejecutar la regla.");
                ejecutar.setEnabled(true);
            } catch (Exception excepcion) {
                salida.setText(excepcion.getMessage());
                pestanas.setSelectedIndex(5);
                estado.setText("  Corrige el error y vuelve a compilar.");
            }
        }

        // Los campos se generan con la tabla de símbolos; también funciona si el usuario cambia los nombres.
        // Ejecutar usa las cuádruplas optimizadas almacenadas. No vuelve a ejecutar JFlex ni CUP.
        private void ejecutarPrograma() {
            var campos = new LinkedHashMap<String,JTextField>();
            var panel = new JPanel(new GridLayout(0, 2, 8, 8));
            compilacionActual.simbolos().keySet().forEach(nombre -> {
                var campo = new JTextField(Integer.toString(ultimasEntradas.getOrDefault(nombre, 0)), 10);
                var etiqueta = new JLabel(nombre);
                etiqueta.setLabelFor(campo);
                panel.add(etiqueta);
                panel.add(campo);
                campos.put(nombre, campo);
            });
            if (!campos.isEmpty() && JOptionPane.showConfirmDialog(this, panel, "Datos de entrada",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
            var entradas = new LinkedHashMap<String,Integer>();
            for (var entrada : campos.entrySet()) {
                try {
                    entradas.put(entrada.getKey(), Integer.parseInt(entrada.getValue().getText().trim()));
                } catch (NumberFormatException excepcion) {
                    JOptionPane.showMessageDialog(this, "Ingresa un entero para " + entrada.getKey(),
                            "Entrada inválida", JOptionPane.ERROR_MESSAGE);
                    return;
                }
            }
            ultimasEntradas.putAll(entradas);
            String mensaje = ejecutar(compilacionActual.optimizado(), entradas);
            salida.setText(mensaje.isEmpty() ? "(Sin mensajes)" : mensaje);
            pestanas.setSelectedIndex(5);
        }
    }

    /** Abre Swing o ejecuta el modo de consola según los argumentos recibidos. */
    public static void main(String[] argumentos) throws Exception {
        // Sin argumentos se abre Swing. En consola se acepta --verificar o una ruta con valores nombre=entero.
        if (argumentos.length == 0) {
            SwingUtilities.invokeLater(() -> new Ventana().setVisible(true));
            return;
        }
        if (argumentos.length == 1 && argumentos[0].equals("--verificar")) {
            verificar();
            return;
        }
        Compilacion compilacion = compilar(Files.readString(Path.of(argumentos[0])));
        var entradas = new LinkedHashMap<String,Integer>();
        for (int indice = 1; indice < argumentos.length; indice++) {
            String[] par = argumentos[indice].split("=", 2);
            if (par.length != 2 || !compilacion.simbolos().containsKey(par[0])
                    || entradas.putIfAbsent(par[0], Integer.parseInt(par[1])) != null) {
                throw new IllegalArgumentException("Entrada desconocida, duplicada o inválida: " + argumentos[indice]);
            }
        }
        if (!entradas.keySet().equals(compilacion.simbolos().keySet())) {
            throw new IllegalArgumentException("Entradas requeridas: " + compilacion.simbolos().keySet());
        }
        System.out.println(ejecutar(compilacion.optimizado(), entradas));
    }

    // Compara la salida original y optimizada y comprueba diagnósticos de las tres fases.
    private static void verificar() throws Exception {
        var compilado = compilar(EJEMPLO);
        var metodosDelEjemplo = EnumSet.noneOf(Metodo.class);
        compilado.eliminadas().forEach(eliminada -> metodosDelEjemplo.add(eliminada.metodo()));
        if (!metodosDelEjemplo.equals(EnumSet.allOf(Metodo.class)) || compilado.eliminadas().size() != 3) {
            throw new AssertionError("El ejemplo inicial debe activar los tres métodos");
        }
        for (int[] valores : new int[][] {{85,92,1},{85,87,0},{80,90,1},{79,90,0},{95,90,2},{95,89,0}}) {
            var entradas = Map.of("promedio", valores[0], "asistencia", valores[1]);
            String esperado = switch (valores[2]) {
                case 2 -> "Beca aprobada\nMención por excelencia";
                case 1 -> "Beca aprobada";
                default -> "Beca rechazada";
            };
            if (!esperado.equals(ejecutar(compilado.original(), entradas))
                    || !esperado.equals(ejecutar(compilado.optimizado(), entradas))) throw new AssertionError(entradas);
        }
        comprobarError(EJEMPLO.replace(">= 90", "@ 90"), "Léxico");
        comprobarError(EJEMPLO.substring(0, EJEMPLO.lastIndexOf('}')), "Sintáctico");
        comprobarError(EJEMPLO.replace("asistencia >= 90", "asistensia >= 90"), "Semántico");
        comprobarError("entrada entero x; entrada entero x;", "Semántico");
        comprobarError("si (2 + 3) {} sino {}", "Semántico");
        comprobarNuevasOptimizaciones();
        System.out.println("Verificación correcta: casos de ejecución, diagnósticos y tres métodos de optimización.");
    }

    // La prueba exige una eliminación por método, verifica su procedencia y compara ambas salidas.
    // Los casos a ambos lados de los límites detectan cambios indebidos en el flujo del condicional.
    private static void comprobarNuevasOptimizaciones() throws Exception {
        var compilado = compilar("""
                entrada entero promedio;
                si (promedio * 2 >= (80 + 80) y promedio * 2 <= 200) {
                    mostrar "Cumple";
                } sino {}
                mostrar "Fin";
                """);
        var metodos = EnumSet.noneOf(Metodo.class);
        for (Eliminada eliminada : compilado.eliminadas()) {
            if (!compilado.original().contains(eliminada.original())) {
                throw new AssertionError("La cuádrupla eliminada no figura en el original");
            }
            metodos.add(eliminada.metodo());
        }
        if (!metodos.equals(EnumSet.allOf(Metodo.class)) || compilado.eliminadas().size() != 3
                || compilado.original().size() - compilado.optimizado().size() != 3) {
            throw new AssertionError("Se esperaba una eliminación por cada método");
        }
        for (int promedio : new int[] {79, 80, 100, 101}) {
            var entradas = Map.of("promedio", promedio);
            String esperado = promedio >= 80 && promedio <= 100 ? "Cumple\nFin" : "Fin";
            if (!esperado.equals(ejecutar(compilado.original(), entradas))
                    || !esperado.equals(ejecutar(compilado.optimizado(), entradas))) {
                throw new AssertionError("La optimización cambió la salida para " + promedio);
            }
        }
    }

    private static void comprobarError(String fuente, String fase) throws Exception {
        try {
            compilar(fuente);
        } catch (ErrorCompilacion excepcion) {
            if (excepcion.getMessage().startsWith(fase)) return;
            throw excepcion;
        }
        throw new AssertionError("Faltó el error " + fase);
    }
}
