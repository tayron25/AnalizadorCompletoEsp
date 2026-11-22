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
    public record Tok(String text, int line, int column) {
        String pos() { return line + ":" + column; }
    }
    public static final class Failure extends RuntimeException {
        Failure(String phase, int line, int column, String message) {
            super(phase + " (" + line + ":" + column + ") " + message);
        }
        Failure(Tok token, String message) { this("Semántico", token.line(), token.column(), message); }
    }
    public record Program(List<Tok> inputs, List<Stmt> statements) {}
    public sealed interface Stmt permits Branch, Print {}
    public record Branch(Expr condition, List<Stmt> yes, List<Stmt> no) implements Stmt {}
    public record Print(String message) implements Stmt {}
    public sealed interface Expr permits Binary, Variable, NumberExpr {}
    public record Binary(Expr left, Tok operator, Expr right) implements Expr {}
    public record Variable(Tok token) implements Expr {}
    public record NumberExpr(Tok token) implements Expr {}

    record Ref(String name) {}
    record Value(Class<?> type, Object operand) {}
    record Quad(String op, Object a, Object b, String target) {
        @Override public String toString() {
            return "(" + op + ", " + show(a) + ", " + show(b) + ", " + show(target, false) + ")";
        }
        private static String show(Object value) { return show(value, true); }
        private static String show(Object value, boolean quoteStrings) {
            return switch (value) {
                case null -> "–";
                case Ref ref -> ref.name();
                case String text -> quoteStrings ? "\"" + text + "\"" : text;
                default -> value.toString();
            };
        }
    }
    record Compilation(Program program, List<String> tokens, Map<String, Tok> symbols,
                       List<Quad> plain, List<Quad> optimized) {}

    static String tokenName(int kind) {
        for (var field : sym.class.getFields()) {
            try {
                if (field.getType() == int.class && field.getInt(null) == kind) return field.getName();
            } catch (IllegalAccessException error) {
                throw new IllegalStateException(error);
            }
        }
        throw new IllegalArgumentException("Token desconocido: " + kind);
    }

    static Compilation compile(String source) throws Exception {
        var lexer = new Lexer(new StringReader(source));
        var program = (Program) new Parser(lexer).parse().value;
        var symbols = new LinkedHashMap<String, Tok>();
        for (Tok input : program.inputs()) {
            if (symbols.putIfAbsent(input.text(), input) != null) {
                throw new Failure(input, "Entrada duplicada: " + input.text());
            }
        }
        return new Compilation(program, lexer.tokens, symbols,
                new Generator(symbols, false).generate(program),
                new Generator(symbols, true).generate(program));
    }

    private static final class Generator {
        private final Map<String, Tok> symbols;
        private final boolean fold;
        private final List<Quad> code = new ArrayList<>();
        private int temp;
        private int label;

        Generator(Map<String, Tok> symbols, boolean fold) {
            this.symbols = symbols;
            this.fold = fold;
        }

        List<Quad> generate(Program program) {
            emit(program.statements());
            return List.copyOf(code);
        }

        private void emit(List<Stmt> statements) {
            for (Stmt statement : statements) {
                switch (statement) {
                    case Print print -> code.add(new Quad("MOSTRAR", print.message(), null, null));
                    case Branch branch -> {
                        Value condition = expression(branch.condition());
                        if (condition.type() != Boolean.class) {
                            throw new Failure(origin(branch.condition()), "La condición de si debe ser booleana");
                        }
                        String otherwise = "L" + ++label;
                        String end = "L" + ++label;
                        code.add(new Quad("JF", condition.operand(), null, otherwise));
                        emit(branch.yes());
                        code.add(new Quad("SALTO", null, null, end));
                        code.add(new Quad("ETIQUETA", null, null, otherwise));
                        emit(branch.no());
                        code.add(new Quad("ETIQUETA", null, null, end));
                    }
                }
            }
        }

        private Value expression(Expr expr) {
            return switch (expr) {
                case NumberExpr number -> {
                    try {
                        yield new Value(Integer.class, Integer.parseInt(number.token().text()));
                    } catch (NumberFormatException error) {
                        throw new Failure(number.token(), "Entero fuera del rango de 32 bits");
                    }
                }
                case Variable variable -> {
                    String name = variable.token().text();
                    if (!symbols.containsKey(name)) {
                        throw new Failure(variable.token(), "Entrada no declarada: " + name);
                    }
                    yield new Value(Integer.class, new Ref(name));
                }
                case Binary binary -> {
                    Value a = expression(binary.left());
                    Value b = expression(binary.right());
                    String op = binary.operator().text();
                    boolean logical = op.equals("y") || op.equals("o");
                    boolean arithmetic = op.equals("+") || op.equals("-") || op.equals("*");
                    Class<?> expected = logical ? Boolean.class : Integer.class;
                    if (a.type() != expected || b.type() != expected) {
                        throw new Failure(binary.operator(), "Operandos incompatibles para " + op);
                    }
                    Class<?> result = arithmetic ? Integer.class : Boolean.class;
                    if (fold && !(a.operand() instanceof Ref) && !(b.operand() instanceof Ref)) {
                        yield new Value(result, calculate(op, a.operand(), b.operand()));
                    }
                    String target = "$t" + ++temp;
                    code.add(new Quad(op, a.operand(), b.operand(), target));
                    yield new Value(result, new Ref(target));
                }
            };
        }
    }

    private static Tok origin(Expr expr) {
        return switch (expr) {
            case Binary binary -> binary.operator();
            case Variable variable -> variable.token();
            case NumberExpr number -> number.token();
        };
    }

    static Object calculate(String op, Object a, Object b) {
        return switch (op) {
            case "+" -> (int) a + (int) b;
            case "-" -> (int) a - (int) b;
            case "*" -> (int) a * (int) b;
            case ">" -> (int) a > (int) b;
            case "<" -> (int) a < (int) b;
            case ">=" -> (int) a >= (int) b;
            case "<=" -> (int) a <= (int) b;
            case "==" -> a.equals(b);
            case "!=" -> !a.equals(b);
            case "y" -> (boolean) a && (boolean) b;
            case "o" -> (boolean) a || (boolean) b;
            default -> throw new IllegalArgumentException("Operación desconocida: " + op);
        };
    }

    private static Object resolve(Object operand, Map<String,Object> memory) {
        return operand instanceof Ref ref ? memory.get(ref.name()) : operand;
    }

    static String execute(List<Quad> code, Map<String,Integer> inputs) {
        var memory = new HashMap<String,Object>(inputs);
        var labels = new HashMap<String,Integer>();
        for (int i = 0; i < code.size(); i++) {
            if (code.get(i).op().equals("ETIQUETA")) labels.put(code.get(i).target(), i);
        }
        var output = new StringJoiner("\n");
        for (int pc = 0; pc < code.size(); pc++) {
            Quad instruction = code.get(pc);
            Object a = resolve(instruction.a(), memory);
            switch (instruction.op()) {
                case "JF" -> {
                    if (!(boolean) a) pc = labels.get(instruction.target());
                }
                case "SALTO" -> pc = labels.get(instruction.target());
                case "ETIQUETA" -> { }
                case "MOSTRAR" -> output.add((String) a);
                default -> memory.put(instruction.target(),
                        calculate(instruction.op(), a, resolve(instruction.b(), memory)));
            }
        }
        return output.toString();
    }

    static final String EXAMPLE = """
            // Regla ficticia para la defensa
            entrada entero promedio;
            entrada entero asistencia;

            si (promedio >= (40 + 40) y asistencia >= 90) {
                mostrar "Beca aprobada";
            } sino {
                mostrar "Beca rechazada";
            }
            """;

    private static String tree(Program program) {
        var result = new StringBuilder("Programa\n");
        for (Tok input : program.inputs()) {
            result.append("  Entrada entero ").append(input.text()).append('\n');
        }
        appendStatements(result, program.statements(), "  ");
        return result.toString();
    }

    private static void appendStatements(StringBuilder result, List<Stmt> statements, String indent) {
        for (Stmt statement : statements) {
            switch (statement) {
                case Print print -> result.append(indent).append("Mostrar ").append(print.message()).append('\n');
                case Branch branch -> {
                    result.append(indent).append("Si\n");
                    appendExpression(result, branch.condition(), indent + "  ");
                    result.append(indent).append("Entonces\n");
                    appendStatements(result, branch.yes(), indent + "  ");
                    result.append(indent).append("Sino\n");
                    appendStatements(result, branch.no(), indent + "  ");
                }
            }
        }
    }

    private static void appendExpression(StringBuilder result, Expr expression, String indent) {
        result.append(indent).append(origin(expression).text()).append('\n');
        if (expression instanceof Binary binary) {
            appendExpression(result, binary.left(), indent + "  ");
            appendExpression(result, binary.right(), indent + "  ");
        }
    }

    private static JTextArea area(String text, boolean editable) {
        var area = new JTextArea(text);
        area.setEditable(editable);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        area.setTabSize(4);
        return area;
    }

    private static JTextArea tab(JTabbedPane tabs, String name) {
        var view = area("", false);
        tabs.addTab(name, new JScrollPane(view));
        return view;
    }

    private static String lines(List<?> items) {
        var out = new StringJoiner("\n");
        for (Object item : items) out.add(item.toString());
        return out.toString();
    }

    private static final class Window extends JFrame {
        private final JTextArea editor = area(EXAMPLE, true);
        private final JTabbedPane tabs = new JTabbedPane();
        private final JTextArea tokens = tab(tabs, "Tokens");
        private final JTextArea ast = tab(tabs, "Árbol");
        private final JTextArea symbols = tab(tabs, "Símbolos");
        private final JTextArea quads = tab(tabs, "Cuádruplas");
        private final JTextArea optimized = tab(tabs, "Optimización");
        private final JTextArea console = tab(tabs, "Salida");
        private final JButton run = new JButton("Ejecutar");
        private final JLabel status = new JLabel("  Edita una regla y pulsa Compilar.");
        private final Map<String,Integer> lastInputs = new HashMap<>(Map.of("promedio", 85, "asistencia", 92));
        private Compilation current;

        Window() {
            super("BecaScript");
            var toolbar = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT));
            var open = new JButton("Abrir…");
            var save = new JButton("Guardar…");
            var compile = new JButton("Compilar");
            for (var button : List.of(open, save, compile, run)) toolbar.add(button);
            add(toolbar, BorderLayout.NORTH);
            var split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(editor), tabs);
            split.setResizeWeight(0.5);
            add(split);
            add(status, BorderLayout.SOUTH);
            run.setEnabled(false);
            open.addActionListener(event -> file(false));
            save.addActionListener(event -> file(true));
            compile.addActionListener(event -> build());
            run.addActionListener(event -> runProgram());
            editor.getDocument().addDocumentListener(new DocumentListener() {
                public void insertUpdate(DocumentEvent event) { invalidateCompilation(); }
                public void removeUpdate(DocumentEvent event) { invalidateCompilation(); }
                public void changedUpdate(DocumentEvent event) { invalidateCompilation(); }
            });
            setDefaultCloseOperation(EXIT_ON_CLOSE);
            setSize(1030, 580);
            setLocationRelativeTo(null);
        }

        private void invalidateCompilation() {
            current = null;
            run.setEnabled(false);
            for (var view : List.of(tokens, ast, symbols, quads, optimized, console)) view.setText("");
            status.setText("  Código modificado. Pulsa Compilar.");
        }

        private void file(boolean save) {
            var chooser = new JFileChooser();
            int selected = save ? chooser.showSaveDialog(this) : chooser.showOpenDialog(this);
            if (selected != JFileChooser.APPROVE_OPTION) return;
            Path path = chooser.getSelectedFile().toPath();
            try {
                if (save) {
                    if (Files.exists(path) && JOptionPane.showConfirmDialog(this,
                            "¿Reemplazar " + path.getFileName() + "?", "Guardar",
                            JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
                    Files.writeString(path, editor.getText());
                    status.setText("  Guardado " + path.getFileName());
                } else {
                    editor.setText(Files.readString(path));
                }
            } catch (java.io.IOException error) {
                JOptionPane.showMessageDialog(this, error.getMessage(), "Archivo", JOptionPane.ERROR_MESSAGE);
            }
        }

        private void build() {
            invalidateCompilation();
            try {
                current = compile(editor.getText());
                tokens.setText(lines(current.tokens()));
                ast.setText(tree(current.program()));
                var table = new StringJoiner("\n");
                current.symbols().forEach((name, input) -> table.add(name + "  entero  " + input.pos()));
                symbols.setText(table.toString());
                quads.setText(lines(current.plain()));
                optimized.setText(current.plain().size() + " → " + current.optimized().size()
                        + " instrucciones\n\n" + lines(current.optimized()));
                for (var view : List.of(tokens, ast, symbols, quads, optimized)) view.setCaretPosition(0);
                tabs.setSelectedIndex(0);
                status.setText("  Compilación correcta. Puedes ejecutar la regla.");
                run.setEnabled(true);
            } catch (Exception error) {
                console.setText(error.getMessage());
                tabs.setSelectedIndex(5);
                status.setText("  Corrige el error y vuelve a compilar.");
            }
        }

        private void runProgram() {
            var fields = new LinkedHashMap<String,JTextField>();
            var panel = new JPanel(new GridLayout(0, 2, 8, 8));
            current.symbols().keySet().forEach(name -> {
                var field = new JTextField(Integer.toString(lastInputs.getOrDefault(name, 0)), 10);
                var label = new JLabel(name);
                label.setLabelFor(field);
                panel.add(label);
                panel.add(field);
                fields.put(name, field);
            });
            if (!fields.isEmpty() && JOptionPane.showConfirmDialog(this, panel, "Datos de entrada",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
            var inputs = new LinkedHashMap<String,Integer>();
            for (var entry : fields.entrySet()) {
                try {
                    inputs.put(entry.getKey(), Integer.parseInt(entry.getValue().getText().trim()));
                } catch (NumberFormatException error) {
                    JOptionPane.showMessageDialog(this, "Ingresa un entero para " + entry.getKey(),
                            "Entrada inválida", JOptionPane.ERROR_MESSAGE);
                    return;
                }
            }
            lastInputs.putAll(inputs);
            String message = execute(current.optimized(), inputs);
            console.setText(message.isEmpty() ? "(Sin mensajes)" : message);
            tabs.setSelectedIndex(5);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            SwingUtilities.invokeLater(() -> new Window().setVisible(true));
            return;
        }
        if (args.length == 1 && args[0].equals("--verificar")) {
            verify();
            return;
        }
        Compilation compilation = compile(Files.readString(Path.of(args[0])));
        var inputs = new LinkedHashMap<String,Integer>();
        for (int i = 1; i < args.length; i++) {
            String[] pair = args[i].split("=", 2);
            if (pair.length != 2 || !compilation.symbols().containsKey(pair[0])
                    || inputs.putIfAbsent(pair[0], Integer.parseInt(pair[1])) != null) {
                throw new IllegalArgumentException("Entrada desconocida, duplicada o inválida: " + args[i]);
            }
        }
        if (!inputs.keySet().equals(compilation.symbols().keySet())) {
            throw new IllegalArgumentException("Entradas requeridas: " + compilation.symbols().keySet());
        }
        System.out.println(execute(compilation.optimized(), inputs));
    }

    private static void verify() throws Exception {
        var compiled = compile(EXAMPLE);
        if (compiled.optimized().size() >= compiled.plain().size()) throw new AssertionError("Optimización");
        for (int[] values : new int[][] {{85,92,1},{85,87,0},{80,90,1},{79,90,0}}) {
            var inputs = Map.of("promedio", values[0], "asistencia", values[1]);
            String expected = values[2] == 1 ? "Beca aprobada" : "Beca rechazada";
            if (!expected.equals(execute(compiled.plain(), inputs))
                    || !expected.equals(execute(compiled.optimized(), inputs))) throw new AssertionError(inputs);
        }
        expectFailure(EXAMPLE.replace(">= 90", "@ 90"), "Léxico");
        expectFailure(EXAMPLE.substring(0, EXAMPLE.lastIndexOf('}')), "Sintáctico");
        expectFailure(EXAMPLE.replace("asistencia >= 90", "asistensia >= 90"), "Semántico");
        expectFailure("entrada entero x; entrada entero x;", "Semántico");
        expectFailure("si (2 + 3) {} sino {}", "Semántico");
        System.out.println("Verificación correcta: 4 casos de ejecución y 5 errores.");
    }

    private static void expectFailure(String source, String phase) throws Exception {
        try {
            compile(source);
        } catch (Failure error) {
            if (error.getMessage().startsWith(phase)) return;
            throw error;
        }
        throw new AssertionError("Faltó el error " + phase);
    }
}
