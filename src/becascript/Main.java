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

public final class Main {
    // El lexer guarda el lexema y su ubicación para informar errores al usuario.
    public record Token(String texto, int linea, int columna) {
        String posicion() { return linea + ":" + columna; }
    }
    public static final class ErrorCompilacion extends RuntimeException {
        ErrorCompilacion(String fase, int linea, int columna, String mensaje) {
            super(fase + " (" + linea + ":" + columna + ") " + mensaje);
        }
        ErrorCompilacion(Token ficha, String mensaje) { this("Semántico", ficha.linea(), ficha.columna(), mensaje); }
    }
    // Estos nodos forman el árbol de sintaxis abstracta que devuelve CUP.
    public record Programa(List<Token> entradas, List<Sentencia> sentencias) {}
    public sealed interface Sentencia permits Condicional, Mostrar {}
    public record Condicional(Expresion condicion, List<Sentencia> ramaSi, List<Sentencia> ramaSino) implements Sentencia {}
    public record Mostrar(String mensaje) implements Sentencia {}
    public sealed interface Expresion permits OperacionBinaria, Variable, Numero {}
    public record OperacionBinaria(Expresion izquierda, Token operador, Expresion derecha) implements Expresion {}
    public record Variable(Token ficha) implements Expresion {}
    public record Numero(Token ficha) implements Expresion {}

    // Una referencia apunta a una entrada o a un temporal; los literales se guardan directamente.
    record Referencia(String nombre) {}
    record Valor(Class<?> tipo, Object operando) {}
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
    record Eliminada(Cuadrupla original, Object reemplazo) {}
    record Compilacion(Programa programa, List<String> tokens, Map<String, Token> simbolos,
                       List<Cuadrupla> original, List<Cuadrupla> optimizado, List<Eliminada> eliminadas) {}

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
        var analizador = new Lexer(new StringReader(fuente));
        var programa = (Programa) new Parser(analizador).parse().value;
        var simbolos = new LinkedHashMap<String, Token>();
        for (Token entrada : programa.entradas()) {
            if (simbolos.putIfAbsent(entrada.texto(), entrada) != null) {
                throw new ErrorCompilacion(entrada, "Entrada duplicada: " + entrada.texto());
            }
        }
        // La identidad del nodo permite recuperar la cuádrupla exacta, incluso si se repite la expresión.
        var originales = new IdentityHashMap<OperacionBinaria, Cuadrupla>();
        var original = new Generador(simbolos, false, originales).generar(programa);
        var optimizador = new Generador(simbolos, true, originales);
        var optimizado = optimizador.generar(programa);
        return new Compilacion(programa, analizador.tokens, simbolos, original, optimizado,
                List.copyOf(optimizador.eliminadas));
    }

    /** Recorre el árbol, verifica los tipos y traduce las sentencias a cuádruplas. */
    private static final class Generador {
        private final Map<String, Token> simbolos;
        private final boolean plegar;
        private final List<Cuadrupla> codigo = new ArrayList<>();
        private final Map<OperacionBinaria, Cuadrupla> originales;
        private final List<Eliminada> eliminadas = new ArrayList<>();
        private int temporal;
        private int etiqueta;

        Generador(Map<String, Token> simbolos, boolean plegar, Map<OperacionBinaria, Cuadrupla> originales) {
            this.simbolos = simbolos;
            this.plegar = plegar;
            this.originales = originales;
        }

        List<Cuadrupla> generar(Programa programa) {
            emitir(programa.sentencias());
            return List.copyOf(codigo);
        }

        private void emitir(List<Sentencia> sentencias) {
            for (Sentencia sentencia : sentencias) {
                switch (sentencia) {
                    case Mostrar mostrar -> codigo.add(new Cuadrupla("MOSTRAR", mostrar.mensaje(), null, null));
                    case Condicional condicional -> {
                        Valor condicion = expresion(condicional.condicion());
                        if (condicion.tipo() != Boolean.class) {
                            throw new ErrorCompilacion(origen(condicional.condicion()), "La condición de si debe ser booleana");
                        }
                        String etiquetaSino = "L" + ++etiqueta;
                        String etiquetaFin = "L" + ++etiqueta;
                        // JF salta a la rama sino cuando la condición es falsa.
                        codigo.add(new Cuadrupla("JF", condicion.operando(), null, etiquetaSino));
                        emitir(condicional.ramaSi());
                        codigo.add(new Cuadrupla("SALTO", null, null, etiquetaFin));
                        codigo.add(new Cuadrupla("ETIQUETA", null, null, etiquetaSino));
                        emitir(condicional.ramaSino());
                        codigo.add(new Cuadrupla("ETIQUETA", null, null, etiquetaFin));
                    }
                }
            }
        }

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
                    Class<?> esperado = logica ? Boolean.class : Integer.class;
                    if (primero.tipo() != esperado || segundo.tipo() != esperado) {
                        throw new ErrorCompilacion(operacion.operador(), "Operandos incompatibles para " + operador);
                    }
                    Class<?> resultado = aritmetica ? Integer.class : Boolean.class;
                    // Plegado de constantes: si ambos operandos ya se conocen, se reemplaza la operación por su resultado.
                    if (plegar && !(primero.operando() instanceof Referencia) && !(segundo.operando() instanceof Referencia)) {
                        Object reemplazo = calcular(operador, primero.operando(), segundo.operando());
                        eliminadas.add(new Eliminada(originales.get(operacion), reemplazo));
                        yield new Valor(resultado, reemplazo);
                    }
                    String destino = "$t" + ++temporal;
                    var cuadrupla = new Cuadrupla(operador, primero.operando(), segundo.operando(), destino);
                    codigo.add(cuadrupla);
                    if (!plegar) originales.put(operacion, cuadrupla);
                    yield new Valor(resultado, new Referencia(destino));
                }
            };
        }
    }

    private static Token origen(Expresion expresion) {
        return switch (expresion) {
            case OperacionBinaria operacion -> operacion.operador();
            case Variable variable -> variable.ficha();
            case Numero numero -> numero.ficha();
        };
    }

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

    private static Object resolver(Object operando, Map<String,Object> memoria) {
        return operando instanceof Referencia ref ? memoria.get(ref.nombre()) : operando;
    }

    /** Interpreta las cuádruplas. Las etiquetas indican dónde continúan los saltos del programa. */
    static String ejecutar(List<Cuadrupla> codigo, Map<String,Integer> entradas) {
        var memoria = new HashMap<String,Object>(entradas);
        var etiquetas = new HashMap<String,Integer>();
        for (int indice = 0; indice < codigo.size(); indice++) {
            if (codigo.get(indice).operador().equals("ETIQUETA")) etiquetas.put(codigo.get(indice).destino(), indice);
        }
        var salida = new StringJoiner("\n");
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

    static final String EJEMPLO = """
            // Regla ficticia para la defensa
            entrada entero promedio;
            entrada entero asistencia;

            si (promedio >= (40 + 40) y asistencia >= 90) {
                mostrar "Beca aprobada";
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

    private static JTextPane pestanaOptimizacion(JTabbedPane pestanas) {
        var vista = Resaltado.panel("", false);
        pestanas.addTab("Optimización", new JScrollPane(vista));
        return vista;
    }

    /** Muestra las operaciones retiradas junto a su sustitución y al código que se ejecutará. */
    static void mostrarOptimizacion(JTextPane vista, Compilacion resultado) {
        var texto = new StringBuilder("Método: plegado de constantes\n")
                .append("Instrucciones: ").append(resultado.original().size())
                .append(" → ").append(resultado.optimizado().size()).append("\n\n")
                .append("Cuádruplas eliminadas\n");
        var intervalos = new ArrayList<int[]>();
        if (resultado.eliminadas().isEmpty()) texto.append("Ninguna en esta compilación.\n");
        for (Eliminada eliminada : resultado.eliminadas()) {
            texto.append("Eliminada: ");
            int inicio = texto.length();
            texto.append(eliminada.original());
            intervalos.add(new int[] { inicio, texto.length() - inicio });
            texto.append(" → ").append(eliminada.reemplazo()).append('\n');
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
        private final Map<String,Integer> ultimasEntradas = new HashMap<>(Map.of("promedio", 85, "asistencia", 92));
        private Compilacion compilacionActual;
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
        if (compilado.optimizado().size() >= compilado.original().size()) throw new AssertionError("Optimización");
        for (int[] valores : new int[][] {{85,92,1},{85,87,0},{80,90,1},{79,90,0}}) {
            var entradas = Map.of("promedio", valores[0], "asistencia", valores[1]);
            String esperado = valores[2] == 1 ? "Beca aprobada" : "Beca rechazada";
            if (!esperado.equals(ejecutar(compilado.original(), entradas))
                    || !esperado.equals(ejecutar(compilado.optimizado(), entradas))) throw new AssertionError(entradas);
        }
        comprobarError(EJEMPLO.replace(">= 90", "@ 90"), "Léxico");
        comprobarError(EJEMPLO.substring(0, EJEMPLO.lastIndexOf('}')), "Sintáctico");
        comprobarError(EJEMPLO.replace("asistencia >= 90", "asistensia >= 90"), "Semántico");
        comprobarError("entrada entero x; entrada entero x;", "Semántico");
        comprobarError("si (2 + 3) {} sino {}", "Semántico");
        System.out.println("Verificación correcta: 4 casos de ejecución y 5 errores.");
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
