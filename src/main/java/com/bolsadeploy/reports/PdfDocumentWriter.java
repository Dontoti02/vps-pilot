package com.bolsadeploy.reports;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.Deflater;

/**
 * Motor ligero y autosuficiente de generación de documentos PDF 1.4 vectoriales.
 *
 * <p>Características:
 * <ul>
 *   <li>Cero dependencias externas (100 % Java estándar, compresión FlateDecode).</li>
 *   <li>Codificación {@code WinAnsiEncoding} para soporte completo de tildes, eñes y símbolos en español.</li>
 *   <li>Sistema de coordenadas de arriba hacia abajo (A4: 595.28 × 841.89 pt).</li>
 *   <li>Rectángulos redondeados con curvas Bézier, círculos, insignias, tarjetas,
 *       enlaces clicables ({@code /Subtype /Link}) y paginación automática con pies de página.</li>
 * </ul>
 */
public final class PdfDocumentWriter {

    public static final double PAGE_WIDTH = 595.28;
    public static final double PAGE_HEIGHT = 841.89;
    public static final double MARGIN_X = 42.0;
    public static final double MARGIN_TOP = 42.0;
    public static final double MARGIN_BOTTOM = 50.0;
    public static final double CONTENT_WIDTH = PAGE_WIDTH - (MARGIN_X * 2);

    public enum Font {
        REGULAR("F1", "Helvetica", false),
        BOLD("F2", "Helvetica-Bold", false),
        ITALIC("F3", "Helvetica-Oblique", false),
        MONO("F4", "Courier", true),
        MONO_BOLD("F5", "Courier-Bold", true);

        final String key;
        final String baseFont;
        final boolean mono;

        Font(String key, String baseFont, boolean mono) {
            this.key = key;
            this.baseFont = baseFont;
            this.mono = mono;
        }
    }

    public record Color(int r, int g, int b) {
        String fillCmd() {
            return String.format(Locale.US, "%.4f %.4f %.4f rg\n", r / 255.0, g / 255.0, b / 255.0);
        }

        String strokeCmd() {
            return String.format(Locale.US, "%.4f %.4f %.4f RG\n", r / 255.0, g / 255.0, b / 255.0);
        }
    }

    private record LinkAnnot(double x1, double y1Pdf, double x2, double y2Pdf, String uri) {
    }

    private static final class PageData {
        final ByteArrayOutputStream stream = new ByteArrayOutputStream(8192);
        final List<LinkAnnot> links = new ArrayList<>();
    }

    private static final Charset WIN_ANSI = Charset.forName("windows-1252");
    private static final double KAPPA = 0.55228475;

    private final List<PageData> pages = new ArrayList<>();
    private PageData currentPage;
    private double cursorY;
    private String runningHeaderTitle = "";
    private String footerLeftText = "VPSPilot — Documentación Técnica";
    private String footerDateText = "";

    public PdfDocumentWriter() {
        newPage(false);
    }

    public void setRunningHeader(String title) {
        this.runningHeaderTitle = title == null ? "" : title;
    }

    public void setFooter(String leftText, String dateText) {
        this.footerLeftText = leftText == null ? "" : leftText;
        this.footerDateText = dateText == null ? "" : dateText;
    }

    public double y() {
        return cursorY;
    }

    public void setY(double y) {
        this.cursorY = y;
    }

    public void advanceY(double delta) {
        this.cursorY += delta;
    }

    public double remainingHeight() {
        return (PAGE_HEIGHT - MARGIN_BOTTOM) - cursorY;
    }

    /** Garantiza espacio vertical disponible; si no cabe, crea una nueva página con cabecera ligera. */
    public void ensureSpace(double neededHeight) {
        if (cursorY + neededHeight > PAGE_HEIGHT - MARGIN_BOTTOM) {
            newPage(true);
        }
    }

    /** Crea una nueva página A4. */
    public void newPage(boolean withRunningHeader) {
        currentPage = new PageData();
        pages.add(currentPage);
        cursorY = MARGIN_TOP;
        if (withRunningHeader && !runningHeaderTitle.isBlank()) {
            drawText(runningHeaderTitle, MARGIN_X, 26, Font.BOLD, 8.5, new Color(100, 116, 139));
            drawText("VPSPilot", PAGE_WIDTH - MARGIN_X - measureWidth("VPSPilot", Font.BOLD, 8.5), 26,
                    Font.BOLD, 8.5, new Color(79, 70, 229));
            drawLine(MARGIN_X, 32, PAGE_WIDTH - MARGIN_X, 32, 0.6, new Color(226, 232, 240));
            cursorY = 46;
        }
    }

    // ------------------------------------------------------------------
    // Primitivas vectoriales (coordenadas top-down)
    // ------------------------------------------------------------------

    public void fillRect(double x, double y, double w, double h, Color fill) {
        writeRaw("q\n");
        writeRaw(fill.fillCmd());
        writeRaw(String.format(Locale.US, "%.2f %.2f %.2f %.2f re f\n", x, toPdfY(y + h), w, h));
        writeRaw("Q\n");
    }

    public void drawRoundedRect(double x, double y, double w, double h, double radius,
                                Color fill, Color stroke, double lineWidth) {
        double r = Math.min(radius, Math.min(w / 2.0, h / 2.0));
        double x0 = x;
        double x1 = x + w;
        double yBottom = toPdfY(y + h);
        double yTop = toPdfY(y);
        double c = r * KAPPA;

        writeRaw("q\n");
        if (fill != null) {
            writeRaw(fill.fillCmd());
        }
        if (stroke != null) {
            writeRaw(stroke.strokeCmd());
            writeRaw(String.format(Locale.US, "%.2f w\n", lineWidth));
        }

        StringBuilder path = new StringBuilder();
        path.append(String.format(Locale.US, "%.2f %.2f m\n", x0 + r, yBottom));
        path.append(String.format(Locale.US, "%.2f %.2f l\n", x1 - r, yBottom));
        path.append(String.format(Locale.US, "%.2f %.2f %.2f %.2f %.2f %.2f c\n",
                x1 - r + c, yBottom, x1, yBottom + r - c, x1, yBottom + r));
        path.append(String.format(Locale.US, "%.2f %.2f l\n", x1, yTop - r));
        path.append(String.format(Locale.US, "%.2f %.2f %.2f %.2f %.2f %.2f c\n",
                x1, yTop - r + c, x1 - r + c, yTop, x1 - r, yTop));
        path.append(String.format(Locale.US, "%.2f %.2f l\n", x0 + r, yTop));
        path.append(String.format(Locale.US, "%.2f %.2f %.2f %.2f %.2f %.2f c\n",
                x0 + r - c, yTop, x0, yTop - r + c, x0, yTop - r));
        path.append(String.format(Locale.US, "%.2f %.2f l\n", x0, yBottom + r));
        path.append(String.format(Locale.US, "%.2f %.2f %.2f %.2f %.2f %.2f c\n",
                x0, yBottom + r - c, x0 + r - c, yBottom, x0 + r, yBottom));
        path.append("h ");
        if (fill != null && stroke != null) {
            path.append("B\n");
        } else if (fill != null) {
            path.append("f\n");
        } else {
            path.append("S\n");
        }
        writeRaw(path.toString());
        writeRaw("Q\n");
    }

    public void fillCircle(double cx, double cyTopDown, double radius, Color fill) {
        double cy = toPdfY(cyTopDown);
        double k = radius * KAPPA;
        writeRaw("q\n");
        writeRaw(fill.fillCmd());
        writeRaw(String.format(Locale.US, "%.2f %.2f m\n", cx, cy + radius));
        writeRaw(String.format(Locale.US, "%.2f %.2f %.2f %.2f %.2f %.2f c\n",
                cx + k, cy + radius, cx + radius, cy + k, cx + radius, cy));
        writeRaw(String.format(Locale.US, "%.2f %.2f %.2f %.2f %.2f %.2f c\n",
                cx + radius, cy - k, cx + k, cy - radius, cx, cy - radius));
        writeRaw(String.format(Locale.US, "%.2f %.2f %.2f %.2f %.2f %.2f c\n",
                cx - k, cy - radius, cx - radius, cy - k, cx - radius, cy));
        writeRaw(String.format(Locale.US, "%.2f %.2f %.2f %.2f %.2f %.2f c\n",
                cx - radius, cy + k, cx - k, cy + radius, cx, cy + radius));
        writeRaw("h f\nQ\n");
    }

    public void drawLine(double x1, double y1, double x2, double y2, double width, Color color) {
        writeRaw("q\n");
        writeRaw(color.strokeCmd());
        writeRaw(String.format(Locale.US, "%.2f w %.2f %.2f m %.2f %.2f l S\n",
                width, x1, toPdfY(y1), x2, toPdfY(y2)));
        writeRaw("Q\n");
    }

    // ------------------------------------------------------------------
    // Texto y enlaces clicables
    // ------------------------------------------------------------------

    /** Dibuja una línea de texto con baseline en {@code baselineY} (coordenada top-down). */
    public void drawText(String text, double x, double baselineY, Font font, double size, Color color) {
        if (text == null || text.isEmpty()) {
            return;
        }
        String sanitized = sanitizeForWinAnsi(text);
        writeRaw("q\n");
        writeRaw(color.fillCmd());
        writeRaw("BT\n");
        writeRaw(String.format(Locale.US, "/%s %.2f Tf\n", font.key, size));
        writeRaw(String.format(Locale.US, "1 0 0 1 %.2f %.2f Tm\n", x, toPdfY(baselineY)));
        writePdfStringLiteral(sanitized);
        writeRaw(" Tj\nET\nQ\n");
    }

    /** Dibuja texto centrado horizontalmente dentro de un ancho dado. */
    public void drawCenteredText(String text, double centerX, double baselineY, Font font, double size, Color color) {
        double w = measureWidth(text, font, size);
        drawText(text, centerX - (w / 2.0), baselineY, font, size, color);
    }

    /** Dibuja un enlace azul subrayado y registra su anotación clicable URI en el PDF. */
    public void drawLinkText(String label, String uri, double x, double baselineY, Font font, double size, Color color) {
        drawText(label, x, baselineY, font, size, color);
        double w = measureWidth(label, font, size);
        drawLine(x, baselineY + 1.8, x + w, baselineY + 1.8, 0.55, color);
        if (uri != null && !uri.isBlank() && (uri.startsWith("http://") || uri.startsWith("https://"))) {
            double y1Pdf = toPdfY(baselineY + 3.0);
            double y2Pdf = toPdfY(baselineY - size - 1.0);
            currentPage.links.add(new LinkAnnot(x, y1Pdf, x + w, y2Pdf, uri.trim()));
        }
    }

    /** Dibuja una insignia (pill) redondeada y devuelve el ancho ocupado. */
    public double drawBadge(String text, double x, double topY, double height,
                            Font font, double fontSize, Color bg, Color fg) {
        double textW = measureWidth(text, font, fontSize);
        double padX = 8.0;
        double w = textW + padX * 2;
        drawRoundedRect(x, topY, w, height, height / 2.0, bg, null, 0);
        double baseline = topY + (height / 2.0) + (fontSize * 0.34);
        drawText(text, x + padX, baseline, font, fontSize, fg);
        return w;
    }

    /** Divide texto en líneas que caben estrictamente dentro de {@code maxWidth}. */
    public List<String> wrapText(String text, Font font, double size, double maxWidth) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return result;
        }
        String clean = sanitizeForWinAnsi(text);
        for (String paragraph : clean.split("\\R", -1)) {
            if (paragraph.isEmpty()) {
                result.add("");
                continue;
            }
            String[] words = paragraph.split(" ");
            StringBuilder line = new StringBuilder();
            for (String word : words) {
                if (word.isEmpty()) {
                    continue;
                }
                if (line.isEmpty()) {
                    if (measureWidth(word, font, size) <= maxWidth) {
                        line.append(word);
                    } else {
                        // Romper palabra muy larga (URL, ruta o contraseña larga)
                        splitLongToken(word, font, size, maxWidth, result, line);
                    }
                } else {
                    String candidate = line + " " + word;
                    if (measureWidth(candidate, font, size) <= maxWidth) {
                        line.append(' ').append(word);
                    } else {
                        result.add(line.toString());
                        line.setLength(0);
                        if (measureWidth(word, font, size) <= maxWidth) {
                            line.append(word);
                        } else {
                            splitLongToken(word, font, size, maxWidth, result, line);
                        }
                    }
                }
            }
            if (!line.isEmpty()) {
                result.add(line.toString());
            }
        }
        return result;
    }

    private void splitLongToken(String token, Font font, double size, double maxWidth,
                                List<String> out, StringBuilder remainder) {
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < token.length(); i++) {
            char ch = token.charAt(i);
            String cand = cur.toString() + ch;
            if (!cur.isEmpty() && measureWidth(cand, font, size) > maxWidth) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            cur.append(ch);
        }
        remainder.append(cur);
    }

    /** Mide el ancho aproximado en puntos de una cadena usando métricas Helvetica/Courier. */
    public double measureWidth(String text, Font font, double size) {
        if (text == null || text.isEmpty()) {
            return 0.0;
        }
        String s = sanitizeForWinAnsi(text);
        if (font.mono) {
            return s.length() * 0.60 * size;
        }
        double factorBold = (font == Font.BOLD) ? 1.045 : 1.0;
        double units = 0.0;
        for (int i = 0; i < s.length(); i++) {
            units += helveticaCharWidth(s.charAt(i));
        }
        return (units / 1000.0) * size * factorBold;
    }

    private static double helveticaCharWidth(char c) {
        return switch (c) {
            case 'i', 'l', 'I', '!', '|', '.', ',', ':', ';', '\'', '¡', 'í', 'Í', 'ì', 'ï' -> 278;
            case 'j', 'f', 't', 'r', '(', ')', '[', ']', '{', '}', '/', '\\', '-' -> 333;
            case ' ' -> 278;
            case 's', 'c', 'k', 'v', 'x', 'y', 'z', 'J', '?', '¿' -> 500;
            case 'm', 'M', 'W', 'w', '—' -> 833;
            case '@', '%' -> 950;
            case 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'K', 'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'X', 'Y', 'Z',
                 'Á', 'É', 'Ó', 'Ú', 'Ñ' -> 700;
            default -> 556;
        };
    }

    // ------------------------------------------------------------------
    // Serialización PDF 1.4 completa
    // ------------------------------------------------------------------

    /** Finaliza los pies de página y escribe el archivo PDF en disco. */
    public void save(Path destino) throws IOException {
        byte[] pdfBytes = toBytes();
        if (destino.getParent() != null) {
            Files.createDirectories(destino.getParent());
        }
        Files.write(destino, pdfBytes);
    }

    /** Genera los bytes completos del documento PDF 1.4 válido. */
    public byte[] toBytes() {
        stampFooters();

        // Estructura de objetos PDF:
        // 1 = Catalog
        // 2 = Pages
        // 3..7 = Fuentes (F1..F5)
        // Luego por cada página: PageObj, ContentObj, y sus AnnotObjs
        int totalPages = pages.size();
        int nextObjId = 8;

        int[] pageObjIds = new int[totalPages];
        int[] contentObjIds = new int[totalPages];
        List<List<Integer>> pageAnnotIds = new ArrayList<>();

        for (int i = 0; i < totalPages; i++) {
            pageObjIds[i] = nextObjId++;
            contentObjIds[i] = nextObjId++;
            List<Integer> aIds = new ArrayList<>();
            for (int j = 0; j < pages.get(i).links.size(); j++) {
                aIds.add(nextObjId++);
            }
            pageAnnotIds.add(aIds);
        }

        int totalObjects = nextObjId - 1;
        long[] offsets = new long[totalObjects + 1];

        ByteArrayOutputStream out = new ByteArrayOutputStream(32768);
        // Header PDF 1.4 con bytes binarios estándar
        out.writeBytes("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(new byte[]{'%', (byte) 0xE2, (byte) 0xE3, (byte) 0xCF, (byte) 0xD3, '\n'});

        // 1: Catalog
        writeObj(out, offsets, 1, "<< /Type /Catalog /Pages 2 0 R >>");

        // 2: Pages
        StringBuilder kids = new StringBuilder();
        for (int pid : pageObjIds) {
            kids.append(pid).append(" 0 R ");
        }
        writeObj(out, offsets, 2,
                "<< /Type /Pages /Count " + totalPages + " /Kids [ " + kids.toString().trim() + " ] >>");

        // 3..7: Standard Type1 Fonts con WinAnsiEncoding (tildes y ñ perfectas)
        Font[] fonts = Font.values();
        for (int i = 0; i < fonts.length; i++) {
            writeObj(out, offsets, 3 + i,
                    "<< /Type /Font /Subtype /Type1 /BaseFont /" + fonts[i].baseFont
                            + " /Encoding /WinAnsiEncoding >>");
        }

        String fontResources = "/Font << /F1 3 0 R /F2 4 0 R /F3 5 0 R /F4 6 0 R /F5 7 0 R >>";

        // Páginas, contenido comprimido y anotaciones de enlace
        for (int i = 0; i < totalPages; i++) {
            PageData p = pages.get(i);
            List<Integer> aIds = pageAnnotIds.get(i);

            StringBuilder annotsPart = new StringBuilder();
            if (!aIds.isEmpty()) {
                annotsPart.append(" /Annots [ ");
                for (int aid : aIds) {
                    annotsPart.append(aid).append(" 0 R ");
                }
                annotsPart.append("]");
            }

            writeObj(out, offsets, pageObjIds[i],
                    String.format(Locale.US,
                            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 %.2f %.2f] /Resources << %s >> /Contents %d 0 R%s >>",
                            PAGE_WIDTH, PAGE_HEIGHT, fontResources, contentObjIds[i], annotsPart));

            byte[] compressed = deflate(p.stream.toByteArray());
            writeStreamObj(out, offsets, contentObjIds[i], compressed);

            for (int j = 0; j < p.links.size(); j++) {
                LinkAnnot a = p.links.get(j);
                String safeUri = escapeAsciiForUri(a.uri());
                String annotBody = String.format(Locale.US,
                        "<< /Type /Annot /Subtype /Link /Rect [%.2f %.2f %.2f %.2f] /Border [0 0 0] /A << /S /URI /URI (%s) >> >>",
                        a.x1(), a.y1Pdf(), a.x2(), a.y2Pdf(), safeUri);
                writeObj(out, offsets, aIds.get(j), annotBody);
            }
        }

        // Tabla xref
        long xrefStart = out.size();
        out.writeBytes(("xref\n0 " + (totalObjects + 1) + "\n").getBytes(StandardCharsets.US_ASCII));
        out.writeBytes("0000000000 65535 f \n".getBytes(StandardCharsets.US_ASCII));
        for (int id = 1; id <= totalObjects; id++) {
            out.writeBytes(String.format(Locale.US, "%010d 00000 n \n", offsets[id]).getBytes(StandardCharsets.US_ASCII));
        }

        // Trailer
        out.writeBytes(("trailer\n<< /Size " + (totalObjects + 1) + " /Root 1 0 R >>\n"
                + "startxref\n" + xrefStart + "\n%%EOF\n").getBytes(StandardCharsets.US_ASCII));

        return out.toByteArray();
    }

    private void stampFooters() {
        int total = pages.size();
        PageData saved = currentPage;
        for (int i = 0; i < total; i++) {
            currentPage = pages.get(i);
            double lineY = PAGE_HEIGHT - 34.0;
            drawLine(MARGIN_X, lineY, PAGE_WIDTH - MARGIN_X, lineY, 0.6, new Color(226, 232, 240));
            String left = footerDateText.isBlank() ? footerLeftText : footerLeftText + "  •  " + footerDateText;
            drawText(left, MARGIN_X, PAGE_HEIGHT - 21.0, Font.REGULAR, 8.0, new Color(100, 116, 139));
            String right = "Página " + (i + 1) + " de " + total;
            drawText(right, PAGE_WIDTH - MARGIN_X - measureWidth(right, Font.BOLD, 8.0),
                    PAGE_HEIGHT - 21.0, Font.BOLD, 8.0, new Color(71, 85, 105));
        }
        currentPage = saved;
    }

    private static void writeObj(ByteArrayOutputStream out, long[] offsets, int id, String body) {
        offsets[id] = out.size();
        out.writeBytes((id + " 0 obj\n" + body + "\nendobj\n").getBytes(StandardCharsets.ISO_8859_1));
    }

    private static void writeStreamObj(ByteArrayOutputStream out, long[] offsets, int id, byte[] compressed) {
        offsets[id] = out.size();
        out.writeBytes((id + " 0 obj\n<< /Filter /FlateDecode /Length " + compressed.length + " >>\nstream\n")
                .getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(compressed);
        out.writeBytes("\nendstream\nendobj\n".getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] deflate(byte[] input) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        deflater.setInput(input);
        deflater.finish();
        ByteArrayOutputStream baos = new ByteArrayOutputStream(input.length);
        byte[] buf = new byte[4096];
        while (!deflater.finished()) {
            int n = deflater.deflate(buf);
            baos.write(buf, 0, n);
        }
        deflater.end();
        return baos.toByteArray();
    }

    private double toPdfY(double yTopDown) {
        return PAGE_HEIGHT - yTopDown;
    }

    private void writeRaw(String ascii) {
        currentPage.stream.writeBytes(ascii.getBytes(StandardCharsets.US_ASCII));
    }

    private void writePdfStringLiteral(String text) {
        byte[] winBytes = text.getBytes(WIN_ANSI);
        currentPage.stream.write('(');
        for (byte b : winBytes) {
            int u = b & 0xFF;
            if (u == '\\' || u == '(' || u == ')') {
                currentPage.stream.write('\\');
                currentPage.stream.write(u);
            } else if (u == '\r' || u == '\n') {
                currentPage.stream.write(' ');
            } else {
                currentPage.stream.write(u);
            }
        }
        currentPage.stream.write(')');
    }

    private static String escapeAsciiForUri(String uri) {
        return uri.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
    }

    /** Convierte símbolos Unicode fuera de Windows-1252 a equivalentes limpios y legibles. */
    static String sanitizeForWinAnsi(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '→', '➜', '➔' -> sb.append("->");
                case '←' -> sb.append("<-");
                case '✓', '✔' -> sb.append("[OK]");
                case '✗', '✘' -> sb.append("[X]");
                case '☑' -> sb.append("[X]");
                case '☐' -> sb.append("[ ]");
                case '…' -> sb.append("...");
                case '\u00A0' -> sb.append(' ');
                default -> {
                    if (c <= 0xFF || c == '—' || c == '–' || c == '•' || c == '€' || c == '“' || c == '”' || c == '‘' || c == '’') {
                        sb.append(c);
                    } else if (!Character.isSurrogate(c)) {
                        sb.append('?');
                    }
                }
            }
        }
        return sb.toString();
    }
}
