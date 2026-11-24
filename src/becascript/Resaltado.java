package becascript;

import java.awt.Color;
import java.awt.Font;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JTextPane;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;

final class Resaltado {
    record Tramo(int inicio, int longitud, String categoria) {}

    static JTextPane panel(String texto, boolean editable) {
        var panel = new JTextPane();
        panel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        panel.setEditable(editable);
        panel.setText(texto);
        return panel;
    }

    /** Envía el código a Pygments y lee las posiciones UTF-16 que espera Swing. */
    static List<Tramo> analizar(String codigo) throws IOException, InterruptedException {
        Path guion = Path.of(System.getProperty("becascript.root", System.getProperty("user.dir")),
                "tools", "resaltado.py");
        Process puente = new ProcessBuilder("python", "-I", "-S", guion.toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            try (var entrada = puente.getOutputStream()) {
                entrada.write(codigo.getBytes(StandardCharsets.UTF_8));
            }
            var tramos = new ArrayList<Tramo>();
            try (var salida = new BufferedReader(new InputStreamReader(puente.getInputStream(),
                    StandardCharsets.UTF_8))) {
                String linea;
                while ((linea = salida.readLine()) != null) {
                    String[] campos = linea.split("\t");
                    tramos.add(new Tramo(Integer.parseInt(campos[0]), Integer.parseInt(campos[1]), campos[2]));
                }
            }
            if (puente.waitFor() != 0) throw new IOException("No se pudo iniciar Pygments");
            return tramos;
        } finally {
            puente.destroy();
        }
    }

    /** Sustituye los estilos del documento sin modificar su texto. */
    static void pintar(JTextPane panel, List<Tramo> tramos) {
        var documento = panel.getStyledDocument();
        documento.setCharacterAttributes(0, documento.getLength(), SimpleAttributeSet.EMPTY, true);
        for (Tramo tramo : tramos) {
            var estilo = new SimpleAttributeSet();
            StyleConstants.setForeground(estilo, color(panel, tramo.categoria()));
            documento.setCharacterAttributes(tramo.inicio(), tramo.longitud(), estilo, false);
        }
    }

    /** Aplica la misma paleta a los tipos oficiales provenientes de JFlex. */
    static void pintarTokens(JTextPane panel, List<String> tokens) {
        panel.setText(String.join("\n", tokens));
        var tramos = new ArrayList<Tramo>();
        int inicio = 0;
        for (String fila : tokens) {
            String tipo = fila.split("  ", 3)[1];
            String categoria = switch (tipo) {
                case "ENTRADA", "ENTERO", "SI", "SINO", "MOSTRAR", "Y", "O" -> "Keyword";
                case "IDENTIFICADOR" -> "Name";
                case "LITERAL_ENTERO" -> "Number";
                case "CADENA" -> "String";
                case "PARENTESIS_ABIERTO", "PARENTESIS_CERRADO", "LLAVE_ABIERTA", "LLAVE_CERRADA", "PUNTO_Y_COMA" -> "Punctuation";
                default -> "Operator";
            };
            tramos.add(new Tramo(inicio, fila.length(), categoria));
            inicio += fila.length() + 1;
        }
        pintar(panel, tramos);
    }

    private static Color color(JTextPane panel, String categoria) {
        Color fondo = panel.getBackground();
        boolean oscuro = fondo.getRed() + fondo.getGreen() + fondo.getBlue() < 384;
        int rgb = switch (categoria) {
            case "Keyword" -> oscuro ? 0xD8AAFF : 0x7037A1;
            case "Name" -> oscuro ? 0x96C9FF : 0x15579B;
            case "Number" -> oscuro ? 0xFFC695 : 0x92401A;
            case "Operator" -> oscuro ? 0xFF9CBE : 0xA32155;
            case "String" -> oscuro ? 0xA6DEA7 : 0x216A35;
            case "Error" -> oscuro ? 0xFF9A94 : 0xB3261E;
            default -> oscuro ? 0xB9C2D0 : 0x545E6B;
        };
        return new Color(rgb);
    }
}
