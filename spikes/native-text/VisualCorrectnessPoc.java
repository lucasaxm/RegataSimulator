package com.boatarde.regatasimulator.spike;

import com.boatarde.regatasimulator.util.ProcessRunner;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Opt-in visual evidence, not production code. run returns after writing FAILs; its caller owns exit policy. */
public final class VisualCorrectnessPoc {
    private static final FontRenderContext FRC = new FontRenderContext(null, true, true);
    private static final Pattern GRAPHEME = Pattern.compile("\\X");
    private static final int GUARD = 160; // >=32, also exceeds the maximum font size + stroke.
    private static final List<String> LIMITS = List.of("-limit", "memory", "128MiB", "-limit", "map", "256MiB",
        "-limit", "disk", "512MiB", "-limit", "thread", "2", "-limit", "time", "25");
    private static final List<String> HASHES = List.of(
        "76d04c18ea243f426b7de1f3ad208e927008f961dc5945e5aad352d0dfde8ee8",
        "788abee4c806d660e8aee46689dd8540cd4bb98da03dcc9d171ce3efd99a9173",
        "058ea80864aef09a23f45cbec2bb5400bc3dfbdea01c3f10538a21fcb497fb74",
        "d754ba427cfe0bca54ae052384baa8f842da5bd6550ad4da024ac441e7a7d5ce",
        "f2b83c763e8afd21709333370bed4774337fae82267937e2b5aea7e2fbd922c1",
        "bd62a0672d0b9b6710b01df434c80ad54fa5f0835207eb7b17b7a761463067bb");
    private record Face(Path path, Font font, String hash, String key) { }
    private record Style(int width, int height, int requested, String face, Color fill, Color outline,
                         int outlineWidth, Color boxFill, int padding, String h, String v, double lineHeight) { }
    private record Fixture(String id, String title, String text, Style style, String rejection) { }
    private record Line(String text, int paragraph, TextLayout layout, Shape shape, double x, double baseline) { }
    private record Plan(int size, List<Line> lines, List<String> paragraphs, Rectangle2D ink) { }
    private record Ink(int x, int y, int width, int height, long pixels, long fill, long outline, long outlineChroma,
                       long outsideBox, long outsideContent, boolean guardTouched, String fingerprint) { }
    private static final class Rejected extends Exception {
        final String type;
        Rejected(String type) { super(type); this.type = type; }
    }
    private static final class Budget {
        final long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        long units;
        void work(long n) throws Rejected {
            units += n;
            if (units > 8_000_000 || System.nanoTime() >= deadline) throw new Rejected("LAYOUT_WORK_LIMIT");
        }
    }
    private final Path magick, output;
    private final Map<String, Face> faces = new LinkedHashMap<>();
    private final List<Map<String, Object>> records = new ArrayList<>();
    private final List<String> csv = new ArrayList<>(List.of("fixture,engine,check,status,detail"));
    private final Map<String, List<String>> fingerprints = new LinkedHashMap<>();

    private VisualCorrectnessPoc(Path magick, Path output) { this.magick = magick; this.output = output; }

    /** output is the fresh 0700 directory already created by NativeTextPoc, not a user-media directory. */
    public static void run(Path magick, Path fonts, Path output) throws Exception {
        Path target = output.toRealPath();
        if (!target.equals(output.toAbsolutePath().normalize()) || !Files.getPosixFilePermissions(target)
            .equals(PosixFilePermissions.fromString("rwx------"))) throw new IllegalArgumentException("Private canonical output required");
        VisualCorrectnessPoc suite = new VisualCorrectnessPoc(magick.toRealPath(), target);
        try {
            suite.loadFonts(fonts);
            for (Fixture fixture : fixtures()) suite.fixture(fixture);
            suite.distinctions();
        } catch (Exception failure) {
            Map<String, Object> result = result("suite");
            suite.records.add(map("id", "suite-error", "title", "Setup/interruption failure", "text", "",
                "style", map(), "results", List.of(result)));
            suite.check("suite-error", result, "execution", "FAIL", failure.getClass().getSimpleName());
            throw failure; // finally publishes evidence before propagating setup/interruption errors.
        } finally {
            privateText(target.resolve("visual-manifest.json"), json(suite.records) + "\n");
            privateText(target.resolve("visual-checks.csv"), String.join("\n", suite.csv) + "\n");
        }
    }

    private void loadFonts(Path directory) throws Exception {
        int index = 0;
        for (String family : List.of("Sans", "Serif", "Mono")) for (String weight : List.of("Regular", "Bold")) {
            String key = family + "-" + weight;
            Path path = directory.resolve("Liberation" + key + ".ttf");
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 2_000_000)
                throw new Rejected("FONT_FILE");
            path = path.toRealPath();
            String hash = hash(Files.readAllBytes(path));
            if (!hash.equals(HASHES.get(index++))) throw new Rejected("FONT_PIN_MISMATCH");
            Font font = Font.createFont(Font.TRUETYPE_FONT, path.toFile());
            String expected = "Liberation " + family + (weight.equals("Bold") ? " Bold" : "");
            if (!font.getFontName(Locale.ROOT).equals(expected)) throw new Rejected("FONT_IDENTITY");
            faces.put(key, new Face(path, font, hash, key));
        }
    }

    private static Style style(String face, Color fill, int stroke, int padding, String h, String v) {
        return new Style(640, 180, 48, face, fill, fill.equals(Color.BLACK) ? Color.WHITE : Color.BLACK,
            stroke, new Color(235, 219, 177), padding, h, v, 1.25);
    }

    private static List<Fixture> fixtures() {
        List<Fixture> list = new ArrayList<>();
        String sample = "João: ação Agpq ÇÃÕ"; // Fits even Mono at the identical 48px size, without wrapping.
        for (String family : List.of("Sans", "Serif", "Mono")) for (String weight : List.of("Regular", "Bold")) {
            String face = family + "-" + weight;
            list.add(new Fixture("face-" + face.toLowerCase(Locale.ROOT), "Mesma frase / " + face, sample,
                style(face, Color.BLACK, 0, 8, "LEFT", "MIDDLE"), null));
        }
        for (int stroke : List.of(0, 1, 3)) {
            list.add(new Fixture("controlled-outline-" + stroke, "Tamanho fixo: variar apenas o contorno " + stroke + "px",
                "Água, João! pq", style("Sans-Bold", Color.RED, stroke, 20, "LEFT", "MIDDLE"), null));
        }
        String combining = "Ac\u0327a\u0303o Joa\u0303o e\u0301 pqgy ".repeat(3);
        for (String form : List.of("nfd", "nfc")) {
            String text = form.equals("nfd") ? combining : java.text.Normalizer.normalize(combining, java.text.Normalizer.Form.NFC);
            list.add(new Fixture("controlled-wrap-" + form, "28px fixos / " + form + " / quebra independente", text,
                new Style(230, 200, 28, "Sans-Regular", Color.RED, Color.BLACK, 1, new Color(225, 240, 220),
                    8, "LEFT", "TOP", 1.3), null));
        }
        int i = 0;
        for (String h : List.of("LEFT", "CENTER", "RIGHT")) for (String v : List.of("TOP", "MIDDLE", "BOTTOM")) {
            Color fill = List.of(Color.RED, Color.GREEN, Color.BLUE).get(i % 3);
            list.add(new Fixture("align-" + h.toLowerCase(Locale.ROOT) + "-" + v.toLowerCase(Locale.ROOT),
                "Alinhamento " + h + " / " + v, "Água, João! pq", style("Sans-Bold", fill,
                    List.of(0, 1, 3).get(i % 3), List.of(0, 8, 20).get(i++ % 3), h, v), null));
        }
        list.add(new Fixture("white-outline", "Branco opaco / contorno preto", "Órgão: Agpq!",
            style("Serif-Bold", Color.WHITE, 3, 20, "CENTER", "MIDDLE"), null));
        list.add(new Fixture("black-outline", "Preto opaco / contorno branco", "Bênção: Agpq!",
            style("Mono-Bold", Color.BLACK, 1, 8, "RIGHT", "BOTTOM"), null));
        list.add(new Fixture("paragraphs", "Quebras iniciais, vazias e finais", "\nJoão, ação!\n\nágua: pqgy\n",
            new Style(520, 300, 32, "Serif-Regular", Color.BLUE, Color.WHITE, 1, new Color(85, 110, 65),
                20, "CENTER", "MIDDLE", 1.4), null));
        list.add(new Fixture("combining", "Grafemas e fronteiras combinantes", "Ac\u0327a\u0303o Joa\u0303o e\u0301 pqgy ".repeat(3),
            new Style(230, 200, 36, "Sans-Regular", Color.RED, Color.BLACK, 1, new Color(225, 240, 220),
                8, "LEFT", "TOP", 1.3), null));
        list.add(new Fixture("combining-nfc", "Auto-fit com acentos precompostos (comparar com combining)",
            java.text.Normalizer.normalize(combining, java.text.Normalizer.Form.NFC),
            new Style(230, 200, 36, "Sans-Regular", Color.RED, Color.BLACK, 1, new Color(225, 240, 220),
                8, "LEFT", "TOP", 1.3), null));
        list.add(new Fixture("long-shrink", "Texto longo: reduzir sem omitir", "João da Conceição entrou na regata. ".repeat(8),
            new Style(300, 120, 96, "Sans-Bold", Color.WHITE, Color.BLACK, 1, new Color(65, 90, 125),
                8, "CENTER", "MIDDLE", 1.25), null));
        list.add(new Fixture("overlong-word", "Palavra longa sem cortes de grafema", "Anticonstitucionalissimamente".repeat(5),
            new Style(240, 110, 64, "Mono-Regular", Color.BLACK, Color.WHITE, 0, new Color(240, 210, 200),
                8, "RIGHT", "BOTTOM", 1.25), null));
        list.add(new Fixture("tiny", "Impossível mesmo no piso positivo de 1px", "Ág\nÁg",
            new Style(1, 1, 128, "Sans-Regular", Color.RED, Color.BLACK, 3, Color.WHITE, 0, "LEFT", "TOP", 1.25), "FIT_REJECTED"));
        list.add(new Fixture("null", "Rejeitar null", null, style("Sans-Regular", Color.BLACK, 0, 8, "LEFT", "TOP"), "INVALID_TEXT"));
        list.add(new Fixture("control", "Rejeitar controles", "João\u0000ação", style("Sans-Regular", Color.BLACK, 0, 8, "LEFT", "TOP"), "INVALID_TEXT"));
        list.add(new Fixture("glyph", "Rejeitar glifo ausente sem fallback", "João \uDBFF\uDFFF", style("Sans-Regular", Color.BLACK, 0, 8, "LEFT", "TOP"), "MISSING_GLYPH"));
        list.add(new Fixture("utf16", "Rejeitar UTF-16 malformado", "João \uD800", style("Sans-Regular", Color.BLACK, 0, 8, "LEFT", "TOP"), "INVALID_TEXT"));
        list.add(new Fixture("empty-paragraphs", "Preservar parágrafos sem tinta", "\n\n",
            style("Sans-Regular", Color.BLACK, 0, 8, "LEFT", "TOP"), "INVALID_TEXT"));
        list.add(new Fixture("trailing-paragraphs", "Uma linha e dois parágrafos finais", "Agpq\n\n",
            style("Mono-Regular", Color.GREEN, 0, 20, "LEFT", "BOTTOM"), null));
        return list;
    }

    private void fixture(Fixture f) throws Exception {
        Style s = f.style;
        Face face = faces.get(s.face);
        Map<String, Object> styles = map("width", s.width, "height", s.height, "requestedFontSize", s.requested,
            "fontFace", s.face, "weight", s.face.endsWith("Bold") ? 700 : 400, "fontFile", face.path.getFileName().toString(),
            "fontName", face.font.getFontName(Locale.ROOT), "fontSha256", face.hash, "color", rgba(s.fill),
            "outlineColor", rgba(s.outline), "outlineWidth", s.outlineWidth, "boxFill", rgba(s.boxFill),
            "padding", s.padding, "hAlign", s.h, "vAlign", s.v, "lineHeight", s.lineHeight, "units", "original-pixels",
            "comparisonMode", f.id.startsWith("controlled-") ? "fixed-size; independent rasterization/wrapping" : "independent auto-fit; not equal-size raster comparison",
            "outlineMeaning", "Requested path stroke width; Java paints stroke then fill (inner half covered). Native paint semantics are not normalized.",
            "fittedFontSizes", new LinkedHashMap<String, Object>());
        List<Map<String, Object>> results = new ArrayList<>();
        records.add(map("id", f.id, "title", f.title, "text", f.text, "expectedRejection", f.rejection, "style", styles, "results", results));
        Budget budget = new Budget();
        Path scratch = Files.createTempDirectory(output, ".visual-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        try {
            for (String engine : List.of("java2d", "magick")) {
                Map<String, Object> result = result(engine); results.add(result);
                result.put("expectedRejection", f.rejection);
                try {
                    validate(f, face);
                    check(f.id, result, "font-identity", "PASS", face.path.getFileName() + " / " + face.font.getFontName(Locale.ROOT) + " / " + face.hash);
                    if (engine.equals("java2d")) javaCase(f, face, result, budget);
                    else nativeCase(f, face, result, scratch, budget);
                    check(f.id, result, "expected-outcome", f.rejection == null ? "PASS" : "FAIL", "Rendered; expected=" + f.rejection);
                } catch (Rejected rejected) {
                    result.put("rejectionType", rejected.type);
                    check(f.id, result, rejected.type, rejected.type.equals(f.rejection) ? "PASS" : "FAIL",
                        (f.rejection == null ? "UNEXPECTED_REJECTION" : "EXPECTED_REJECTION") + ": " + rejected.type + "; no text dropped, no dummy sources");
                    check(f.id, result, "expected-outcome", rejected.type.equals(f.rejection) ? "PASS" : "FAIL", "Typed rejection; expected=" + f.rejection);
                } catch (InterruptedException interrupted) {
                    check(f.id, result, "execution", "FAIL", "Interrupted; artifacts retained");
                    Thread.currentThread().interrupt(); throw interrupted;
                } catch (Exception failure) {
                    check(f.id, result, "execution", "FAIL", failure.getClass().getSimpleName() + "; artifacts retained, exception paths/output omitted");
                }
                @SuppressWarnings("unchecked") Map<String, Object> fitted = (Map<String, Object>) styles.get("fittedFontSizes");
                fitted.put(engine, result.get("fontSize"));
            }
        } finally {
            try (var paths = Files.walk(scratch)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void validate(Fixture f, Face face) throws Rejected {
        Style s = f.style;
        String text = f.text;
        if (text == null || text.isBlank() || text.length() > 4096) throw new Rejected("INVALID_TEXT");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) throw new Rejected("INVALID_TEXT");
            } else if (Character.isLowSurrogate(c) || (Character.isISOControl(c) && c != '\n')) throw new Rejected("INVALID_TEXT");
        }
        if (face.font.canDisplayUpTo(text.replace("\n", "")) != -1) throw new Rejected("MISSING_GLYPH");
        if (s.width < 1 || s.height < 1 || s.width > 2048 || s.height > 2048 || (long) s.width * s.height > 2_000_000
            || s.requested < 1 || s.requested > 128 || s.padding < 0 || s.padding > 20 || s.outlineWidth < 0 || s.outlineWidth > 3
            || !Double.isFinite(s.lineHeight) || s.lineHeight < 1 || s.lineHeight > 2) throw new Rejected("STYLE_LIMIT");
        if (s.width <= 2 * s.padding || s.height <= 2 * s.padding) throw new Rejected("FIT_REJECTED");
    }

    private static Shape painted(TextLayout layout, int stroke) {
        Shape glyph = layout.getOutline(null);
        return stroke == 0 ? glyph : new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND).createStrokedShape(glyph);
    }

    private static Plan plan(Fixture f, Face face, int size, Budget budget) throws Rejected {
        Style s = f.style;
        Font font = face.font.deriveFont((float) size);
        double step = font.getLineMetrics("Agpq", FRC).getHeight() * s.lineHeight;
        double ascent = font.getLineMetrics("Agpq", FRC).getAscent();
        List<String> paragraphs = List.of(f.text.split("\n", -1));
        List<Line> lines = new ArrayList<>();
        Rectangle2D union = null;
        int row = 0, leading = 0, trailing = 0;
        for (int p = 0; p < paragraphs.size(); p++) {
            String paragraph = paragraphs.get(p);
            if (paragraph.isEmpty()) { row++; if (lines.isEmpty()) leading++; trailing++; continue; }
            trailing = 0;
            List<String> clusters = GRAPHEME.matcher(paragraph).results().map(m -> m.group()).toList();
            int offset = 0;
            while (offset < clusters.size()) {
                int low = offset + 1, high = clusters.size(), end = offset;
                while (low <= high) {
                    int mid = (low + high) >>> 1;
                    String candidate = String.join("", clusters.subList(offset, mid)); budget.work(candidate.length());
                    TextLayout measured = new TextLayout(candidate, font, FRC);
                    Rectangle2D b = painted(measured, s.outlineWidth).getBounds2D();
                    if (Math.max(measured.getAdvance(), b.getMaxX()) - Math.min(0, b.getMinX()) <= s.width - 2 * s.padding - 2) {
                        end = mid; low = mid + 1;
                    } else high = mid - 1;
                }
                if (end == offset) return null;
                if (end < clusters.size()) for (int k = end - 1; k > offset; k--) {
                    if (clusters.get(k).equals(" ")) { end = k + 1; break; }
                }
                String text = String.join("", clusters.subList(offset, end)); budget.work(text.length());
                TextLayout layout = new TextLayout(text, font, FRC);
                Shape shape = layout.getOutline(null);
                Rectangle2D b = painted(layout, s.outlineWidth).getBounds2D();
                double x = aligned(s.h, s.padding, s.width - 2 * s.padding, b.getWidth()) - b.getX();
                double baseline = ascent + row++ * step;
                Rectangle2D placed = new Rectangle2D.Double(x + b.getX(), baseline + b.getY(), b.getWidth(), b.getHeight());
                if (!b.isEmpty()) { if (union == null) union = placed; else union = union.createUnion(placed); }
                lines.add(new Line(text, p, layout, shape, x, baseline)); offset = end;
            }
        }
        if (union == null) return null;
        double top = union.getY() - leading * step, height = union.getHeight() + (leading + trailing) * step;
        if (height > s.height - 2 * s.padding - 2) return null;
        double dy = aligned(s.v, s.padding, s.height - 2 * s.padding, height) - top;
        List<Line> placed = lines.stream().map(l -> new Line(l.text, l.paragraph, l.layout, l.shape, l.x, l.baseline + dy)).toList();
        return new Plan(size, placed, paragraphs, new Rectangle2D.Double(union.getX(), union.getY() + dy, union.getWidth(), union.getHeight()));
    }

    private static double aligned(String align, double start, double available, double extent) {
        return start + switch (align) { case "CENTER", "MIDDLE" -> (available - extent) / 2; case "RIGHT", "BOTTOM" -> available - extent; default -> 0; };
    }

    private static BufferedImage paint(Plan plan, Style s) {
        BufferedImage image = new BufferedImage(s.width + 2 * GUARD, s.height + 2 * GUARD, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g.translate(GUARD, GUARD); // No content/box clip: escaped ink must stay observable.
            for (Line l : plan.lines) {
                Shape glyph = AffineTransform.getTranslateInstance(l.x, l.baseline).createTransformedShape(l.shape);
                if (s.outlineWidth > 0) {
                    g.setColor(s.outline); g.setStroke(new BasicStroke(s.outlineWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); g.draw(glyph);
                }
                g.setColor(s.fill); g.fill(glyph);
            }
        } finally { g.dispose(); }
        return image;
    }

    private void javaCase(Fixture f, Face face, Map<String, Object> r, Budget budget) throws Exception {
        boolean fixed = f.id.startsWith("controlled-");
        r.put("fittingPolicy", fixed ? "fixed-size" : "Java grapheme wrapping and descending measured fit; requested lineHeight applied");
        for (int size = f.style.requested; size >= (fixed ? f.style.requested : 1); size--) {
            budget.work(1);
            Plan plan = plan(f, face, size, budget);
            if (plan == null) continue;
            BufferedImage raster = paint(plan, f.style);
            Ink ink = scan(raster, f.style, GUARD, GUARD);
            if (ink.outsideContent != 0 || ink.guardTouched || ink.pixels == 0) { raster.flush(); continue; }
            StringBuilder restored = new StringBuilder();
            for (int p = 0; p < plan.paragraphs.size(); p++) {
                if (p > 0) restored.append('\n');
                for (Line l : plan.lines) if (l.paragraph == p) restored.append(l.text);
            }
            check(f.id, r, "no-content-loss", restored.toString().equals(f.text) ? "PASS" : "FAIL",
                "Reconstructed wrapped lines + explicit paragraphs (including empty/trailing): " + json(restored.toString()));
            check(f.id, r, "alignment", near(ink, plan.ink) ? "PASS" : "FAIL", "Actual painted bounds vs glyph/stroke target <=2px; no anisotropic scaling; target=" + json(bounds(plan.ink)));
            r.put("alignmentTarget", bounds(plan.ink));
            r.put("wrappedLines", plan.lines.stream().map(l -> map("text", l.text, "paragraph", l.paragraph, "x", l.x, "baseline", l.baseline)).toList());
            r.put("paragraphs", plan.paragraphs);
            if (fixed) check(f.id, r, "fixed-size", size == f.style.requested ? "PASS" : "FAIL", "No auto-fit in controlled comparison");
            if (f.id.startsWith("face-")) check(f.id, r, "identical-single-line", plan.lines.size() == 1 && size == 48 ? "PASS" : "FAIL", "Same text/style, one line, 48px; lines=" + plan.lines.size());
            if (f.id.equals("long-shrink") || f.id.equals("overlong-word")) check(f.id, r, "auto-shrink", size < f.style.requested ? "PASS" : "FAIL", "Positive fitted size=" + size);
            save(f, r, raster, ink, size); raster.flush(); return;
        }
        throw new Rejected("FIT_REJECTED");
    }

    private String command(Path scratch, Budget budget, String... arguments) throws Exception {
        budget.work(1);
        List<String> args = new ArrayList<>(List.of(magick.toString())); args.addAll(LIMITS); args.addAll(List.of(arguments));
        // Include exit-zero diagnostics in the strict fitter response, not silently discarded stderr.
        ProcessBuilder builder = new ProcessBuilder(args).directory(scratch.toFile()).redirectErrorStream(true);
        builder.environment().put("MAGICK_TEMPORARY_PATH", scratch.toString());
        long remaining = budget.deadline - System.nanoTime();
        if (remaining <= 0) throw new Rejected("LAYOUT_WORK_LIMIT");
        return new ProcessRunner(Duration.ofNanos(Math.min(Duration.ofSeconds(30).toNanos(), remaining))).run(builder, ProcessBuilder::start);
    }

    private void nativeCase(Fixture f, Face face, Map<String, Object> r, Path scratch, Budget budget) throws Exception {
        Style s = f.style;
        // Shared admission rule, not a claim to have observed native glyphs in a 1px clipped caption.
        if (s.width - 2 * s.padding <= s.outlineWidth || s.height - 2 * s.padding <= s.outlineWidth)
            throw new Rejected("FIT_REJECTED");
        Path text = scratch.resolve("owned.txt"), candidate = scratch.resolve("caption.png");
        privateText(text, f.text);
        String gravity = switch (s.h) { case "CENTER" -> "North"; case "RIGHT" -> "NorthEast"; default -> "NorthWest"; };
        List<String> base = List.of("-density", "72", "-font", face.path.toString(), "-background", "none",
            "-fill", rgba(s.fill), "-stroke", s.outlineWidth == 0 ? "none" : rgba(s.outline), "-strokewidth", Integer.toString(s.outlineWidth), "-gravity", gravity);
        boolean fixed = f.id.startsWith("controlled-");
        r.put("fittingPolicy", fixed ? "exact requested integer only; free-height native caption:split=true"
            : "explicit descending integers; batches <=8; free-height native caption:split=true; no monotonicity assumption");
        r.put("lineHeightApplied", false);
        MagickCaptionFitter.Request request = new MagickCaptionFitter.Request(f.text, s.width - 2 * s.padding,
            s.height - 2 * s.padding, s.requested, fixed, base, text, candidate);
        MagickCaptionFitter.Result fitted;
        try {
            fitted = MagickCaptionFitter.fit(request, arguments -> command(scratch, budget, arguments));
        } catch (MagickCaptionFitter.Failure rejected) {
            throw new Rejected(rejected.type().name());
        }
        r.put("sourceTextSha256", hash(f.text.getBytes(StandardCharsets.UTF_8)));
        r.put("ownedTextSha256", hash(Files.readAllBytes(text)));
        check(f.id, r, "source-bytes-unchanged", r.get("sourceTextSha256").equals(r.get("ownedTextSha256")) ? "PASS" : "FAIL",
            "Original UTF-8 bytes retained, including NFD; no normalization or shortening");
        r.put("fitProbeCount", fitted.probes());
        r.put("fitCommandCount", fitted.fitCommands());
        r.put("fitWorkUnits", fitted.workUnits());
        r.put("fitWorkMeaning", "UTF16 length * size squared for probes + final rerender; NOT a native memory/allocation proof");
        r.put("nativeMeasurements", fitted.measurements().stream()
            .map(p -> map("size", p.size(), "width", p.width(), "height", p.height(), "fits", p.fits(request.width(), request.height()))).toList());
        r.put("checkedLargerSizes", fitted.checkedLargerSizes().stream().map(MagickCaptionFitter.Probe::size).toList());
        boolean largerChecked = fitted.checkedLargerSizes().size() == (fixed ? 0 : s.requested - fitted.size())
            && fitted.checkedLargerSizes().stream().noneMatch(p -> p.fits(request.width(), request.height()));
        check(f.id, r, "highest-measured-integer", largerChecked ? "PASS" : "FAIL",
            "Every integer above selection explicitly measured and rejected; fixed fixtures test requested size only");
        check(f.id, r, "fit-counter-caps", fitted.probes() <= MagickCaptionFitter.MAX_PROBES
            && fitted.fitCommands() <= MagickCaptionFitter.MAX_COMMANDS
            && fitted.fitCommands() == (fitted.probes() + 7) / 8 + 1
            && fitted.workUnits() <= MagickCaptionFitter.MAX_WORK ? "PASS" : "FAIL",
            "Actual probes=" + fitted.probes() + "; fit commands including rerender=" + fitted.fitCommands()
                + "; scalar work including rerender=" + fitted.workUnits());
        if (f.id.equals("combining")) {
            List<String> probeArguments = MagickCaptionFitter.batchArguments(request, List.of(28));
            MagickCaptionFitter.Probe measured = MagickCaptionFitter.parseBatch(
                command(scratch, budget, probeArguments.toArray(String[]::new)), List.of(28), request.width()).getFirst();
            boolean largerFits = measured.fits(request.width(), request.height());
            r.put("fitSanityProbe", map("size", 28, "width", measured.width(), "height", measured.height(), "fits", largerFits));
            r.put("regressionProbeCommandsOutsideFit", 1);
            r.put("regressionProbeWorkOutsideFit", (long) f.text.length() * 28 * 28);
            check(f.id, r, "corrected-combining-fit", largerFits && fitted.size() >= 28 ? "PASS" : "FAIL",
                "Original NFD explicit 28px fits=" + largerFits + "; selected=" + fitted.size()
                    + "; controlled-wrap-nfd retains independent fixed 28px comparison; dimensions are not full-glyph proof");
        }
        if (f.id.equals("overlong-word")) check(f.id, r, "corrected-longword-fit", fitted.size() >= 12 ? "PASS" : "FAIL",
            "Explicit split=true free-height selected=" + fitted.size() + "; no fixed-height dummy clipping; Unicode grapheme preservation remains unproven");
        {
            int size = fitted.size();
            BufferedImage caption = read(candidate);
            if (caption.getWidth() != fitted.width() || caption.getHeight() != fitted.height()) {
                caption.flush();
                throw new Rejected("OUTPUT_MISMATCH");
            }
            Ink ci = scan(caption, s, 0, 0);
            int y = (int) Math.round(aligned(s.v, s.padding, s.height - 2 * s.padding, caption.getHeight()));
            BufferedImage overscan = new BufferedImage(s.width + 2 * GUARD, s.height + 2 * GUARD, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = overscan.createGraphics(); g.drawImage(caption, GUARD + s.padding, GUARD + y, null); g.dispose();
            Ink ink = scan(overscan, s, GUARD, GUARD);
            save(f, r, overscan, ink, size); overscan.flush();
            if (fixed) check(f.id, r, "fixed-size", size == s.requested ? "PASS" : "FAIL", "No auto-fit in controlled comparison");
            check(f.id, r, "free-height-positive-floor", "PASS", "Owned-file caption free height=" + caption.getHeight() + "; fitted=" + size);
            Rectangle2D target = new Rectangle2D.Double(aligned(s.h, s.padding, s.width - 2 * s.padding, ci.width),
                aligned(s.v, s.padding, s.height - 2 * s.padding, ci.height), ci.width, ci.height);
            r.put("alignmentTarget", bounds(target));
            check(f.id, r, "alignment", near(ink, target) ? "PASS" : "FAIL", "Painted glyph target <=2px; candidate external v-placement uses free caption height; target=" + json(bounds(target)));
            boolean simple = !f.text.contains("\n") && !Pattern.compile("\\p{M}").matcher(f.text).find()
                && new TextLayout(f.text, face.font.deriveFont((float) size), FRC).getAdvance() < s.width - 2 * s.padding - 8;
            if (f.id.startsWith("face-")) check(f.id, r, "identical-single-line", simple && size == 48 ? "PASS" : "FAIL", "One-line eligibility at identical 48px; actual caption coverage checked against annotate");
            if (f.id.equals("long-shrink") || f.id.equals("overlong-word")) check(f.id, r, "auto-shrink", size < s.requested ? "PASS" : "FAIL", "Positive fitted size=" + size);
            if (simple) reference(f, r, face, caption, base, size, text, scratch, budget);
            else check(f.id, r, "full-glyph-coverage", "LIMITATION", "Caption multiline/wrapping/graphemes and explicit blank-line preservation unproven; outer overscan cannot detect internal clipping");
            if (f.text.contains("\n") || !simple) check(f.id, r, "line-height-and-content", "LIMITATION", "Native caption owns wrapping/line-height; requested lineHeight is recorded, not proven equivalent; no nativeGlyphs interpretation");
            caption.flush(); return;
        }
    }

    private void reference(Fixture f, Map<String, Object> r, Face face, BufferedImage caption, List<String> base,
                           int size, Path text, Path scratch, Budget budget) throws Exception {
        Path path = scratch.resolve("uncropped.png");
        List<String> args = new ArrayList<>(base);
        args.addAll(List.of("-pointsize", Integer.toString(size), "-size", (f.style.width + 2 * GUARD) + "x" + (f.style.height + 2 * GUARD),
            "xc:none", "-gravity", "NorthWest", "-annotate", "+" + GUARD + "+" + GUARD, "@" + text, path.toString()));
        command(scratch, budget, args.toArray(String[]::new));
        BufferedImage ref = read(path);
        Ink a = scan(ref, f.style, 0, 0), b = scan(caption, f.style, 0, 0);
        String filename = "visual-magick-" + f.id + "-reference.png";
        png(filename, ref);
        r.put("reference", filename);
        boolean equal = !a.guardTouched && a.pixels > 0 && a.width == b.width && a.height == b.height && a.fingerprint.equals(b.fingerprint);
        check(f.id, r, "full-glyph-coverage", equal ? "PASS" : "FAIL", "Fresh uncropped owned-file annotate vs caption at identical pinned face/size/colors/stroke, translation-normalized exact RGBA; reference=" + filename
            + "; referenceBounds=" + a.width + "x" + a.height + "; captionBounds=" + b.width + "x" + b.height
            + "; pixelCounts=" + a.pixels + "/" + b.pixels + "; font=" + face.path.getFileName()
            + (equal ? "" : "; KNOWN_CANDIDATE_FAILURE: internal clipping or differing glyph raster, not a full-coverage pass"));
        ref.flush();
    }

    private static Ink scan(BufferedImage image, Style s, int ox, int oy) throws Exception {
        int minX = image.getWidth(), minY = image.getHeight(), maxX = -1, maxY = -1;
        long pixels = 0, fill = 0, outline = 0, outlineChroma = 0, box = 0, content = 0; boolean edge = false;
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            int argb = image.getRGB(x, y); if ((argb >>> 24) == 0) continue;
            minX = Math.min(minX, x); minY = Math.min(minY, y); maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); pixels++;
            if (argb == s.fill.getRGB()) fill++; if (argb == s.outline.getRGB()) outline++;
            if ((argb & 0xffffff) == (s.outline.getRGB() & 0xffffff)) outlineChroma++;
            int px = x - ox, py = y - oy;
            if (px < 0 || py < 0 || px >= s.width || py >= s.height) box++;
            if (px < s.padding || py < s.padding || px >= s.width - s.padding || py >= s.height - s.padding) content++;
            edge |= x == 0 || y == 0 || x == image.getWidth() - 1 || y == image.getHeight() - 1;
        }
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (int y = minY; y <= maxY; y++) for (int x = minX; x <= maxX; x++) {
            int c = image.getRGB(x, y); if ((c >>> 24) == 0) c = 0;
            md.update((byte) (c >>> 24)); md.update((byte) (c >>> 16)); md.update((byte) (c >>> 8)); md.update((byte) c);
        }
        return new Ink(minX - ox, minY - oy, maxX < 0 ? 0 : maxX - minX + 1, maxY < 0 ? 0 : maxY - minY + 1,
            pixels, fill, outline, outlineChroma, box, content, edge, HexFormat.of().formatHex(md.digest()));
    }

    private void save(Fixture f, Map<String, Object> r, BufferedImage overscan, Ink ink, int size) throws Exception {
        Style s = f.style; String engine = (String) r.get("engine"), prefix = "visual-" + engine + "-" + f.id;
        r.put("fontSize", size); r.put("inkBounds", map("x", ink.x, "y", ink.y, "width", ink.width, "height", ink.height));
        r.put("readability", map("status", size < 12 ? "WARNING" : "NOT_ASSESSED",
            "detail", size < 12 ? "Below illustrative 12 original-pixel size: inspect preview at intended display size; enlarge the box or shorten text. Not a production threshold or submit rejection."
                : "Fit/color checks do not establish readability at the intended display size."));
        r.put("pixelCounts", map("ink", ink.pixels, "exactOpaqueFill", ink.fill, "exactOpaqueOutline", ink.outline,
            "outlineChromaIncludingAA", ink.outlineChroma, "outsideBox", ink.outsideBox, "outsideContent", ink.outsideContent)); r.put("fingerprint", ink.fingerprint);
        check(f.id, r, "overscan", ink.pixels > 0 && ink.outsideBox == 0 && ink.outsideContent == 0 && !ink.guardTouched ? "PASS" : "FAIL",
            "Transparent guard=" + GUARD + "; ALL pixels scanned, no content clip; outsideBox=" + ink.outsideBox + "; outsidePadding=" + ink.outsideContent);
        check(f.id, r, "fill-color", ink.fill > 0 ? "PASS" : "FAIL", "Fully covered exact RGBA " + rgba(s.fill) + " pixels=" + ink.fill);
        check(f.id, r, "outline-color", (s.outlineWidth == 0 ? ink.outlineChroma == 0 : ink.outlineChroma > 0) ? "PASS" : "FAIL",
            "Width=" + s.outlineWidth + "; contrasting fixture outline chroma including AA pixels=" + ink.outlineChroma + "; zero width requires zero outline pixels");
        if (s.outlineWidth > 0) check(f.id, r, "opaque-outline-color", ink.outline > 0 ? "PASS" : s.outlineWidth >= 3 ? "FAIL" : "LIMITATION",
            "Exact opaque " + rgba(s.outline) + " pixels=" + ink.outline + "; 3px requires full coverage; 1px centered stroke may leave only subpixel external coverage after fill");
        BufferedImage clean = new BufferedImage(s.width, s.height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = clean.createGraphics(); g.setColor(s.boxFill); g.fillRect(0, 0, s.width, s.height); g.drawImage(overscan, -GUARD, -GUARD, null); g.dispose();
        check(f.id, r, "box-dimensions", clean.getWidth() == s.width && clean.getHeight() == s.height ? "PASS" : "FAIL", "Exact original-pixel box=" + s.width + "x" + s.height);
        check(f.id, r, "background-corner", clean.getRGB(0, 0) == s.boxFill.getRGB() ? "PASS" : "FAIL", "Corner RGBA=" + String.format(Locale.ROOT, "%08x", clean.getRGB(0, 0)) + "; expected=" + rgba(s.boxFill));
        png(prefix + ".png", clean); clean.flush(); r.put("image", prefix + ".png");
        BufferedImage guides = new BufferedImage(overscan.getWidth(), overscan.getHeight() + 52, BufferedImage.TYPE_INT_ARGB);
        g = guides.createGraphics();
        for (int y = 0; y < guides.getHeight(); y += 12) for (int x = 0; x < guides.getWidth(); x += 12) {
            g.setColor(((x / 12 + y / 12) % 2 == 0) ? new Color(210, 210, 210) : Color.WHITE); g.fillRect(x, y, 12, 12);
        }
        g.setColor(s.boxFill); g.fillRect(GUARD, GUARD, s.width, s.height); g.drawImage(overscan, 0, 0, null);
        g.setColor(Color.ORANGE); g.drawRect(GUARD, GUARD, s.width - 1, s.height - 1);
        g.setColor(Color.CYAN); g.drawRect(GUARD + s.padding, GUARD + s.padding, s.width - 2 * s.padding - 1, s.height - 2 * s.padding - 1);
        // Debug labels also use the pinned physical face; never an OS/logical font.
        g.setFont(faces.get("Sans-Regular").font.deriveFont(14f)); g.setColor(Color.BLACK);
        g.drawString(f.id + " / " + engine + " / " + size + "px / " + s.h + " / " + s.v, 8, guides.getHeight() - 28);
        g.drawString("DEBUG: orange=box; cyan=content; checkerboard=outside; padding=" + s.padding, 8, guides.getHeight() - 8); g.dispose();
        png(prefix + "-guides.png", guides); guides.flush(); r.put("guides", prefix + "-guides.png");
        if (f.id.startsWith("face-")) fingerprints.computeIfAbsent(engine, ignored -> new ArrayList<>()).add(size + ":" + ink.width + ":" + ink.height + ":" + ink.fingerprint);
    }

    @SuppressWarnings("unchecked")
    private void distinctions() {
        for (String engine : List.of("java2d", "magick")) {
            List<String> hashes = fingerprints.getOrDefault(engine, List.of());
            Map<String, Object> r = ((List<Map<String, Object>>) records.getFirst().get("results")).stream()
                .filter(v -> v.get("engine").equals(engine)).findFirst().orElseThrow();
            boolean pass = hashes.size() == 6 && hashes.stream().allMatch(h -> h.startsWith("48:"))
                && hashes.stream().map(h -> h.substring(h.lastIndexOf(':') + 1)).distinct().count() == 6;
            check("face-sans-regular", r, "six-distinct-face-pixels", pass ? "PASS" : "FAIL", "Identical one-line sample at 48px; ordered Sans/Serif/Mono 400/700 translation-normalized RGBA fingerprints=" + json(hashes));
        }
    }

    private static boolean near(Ink ink, Rectangle2D target) {
        return Math.abs(ink.x - target.getX()) <= 2 && Math.abs(ink.y - target.getY()) <= 2
            && Math.abs(ink.x + ink.width - target.getMaxX()) <= 2 && Math.abs(ink.y + ink.height - target.getMaxY()) <= 2;
    }
    private static Map<String, Object> bounds(Rectangle2D b) { return map("x", b.getX(), "y", b.getY(), "width", b.getWidth(), "height", b.getHeight()); }
    private static Map<String, Object> result(String engine) { return map("engine", engine, "image", null, "guides", null, "checks", new ArrayList<Map<String, Object>>(), "fontSize", null, "inkBounds", null); }
    @SuppressWarnings("unchecked")
    private void check(String id, Map<String, Object> r, String name, String status, String detail) {
        ((List<Map<String, Object>>) r.get("checks")).add(map("name", name, "status", status, "detail", detail));
        csv.add(List.of(id, r.get("engine").toString(), name, status, detail).stream().map(v -> "\"" + v.replace("\"", "\"\"") + "\"").collect(java.util.stream.Collectors.joining(",")));
    }
    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>(); for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], pairs[i + 1]); return map;
    }
    private static String rgba(Color c) { return String.format(Locale.ROOT, "#%02x%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue(), c.getAlpha()); }
    private static String hash(byte[] data) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
    private static BufferedImage read(Path path) throws Exception {
        // Inspect dimensions before decode, including generated native output, never full 40MP.
        try (var input = ImageIO.createImageInputStream(path.toFile())) {
            var readers = ImageIO.getImageReaders(input); if (!readers.hasNext()) throw new Rejected("PNG_DECODE");
            var reader = readers.next();
            try {
                reader.setInput(input); int w = reader.getWidth(0), h = reader.getHeight(0);
                if (w < 1 || h < 1 || w > 2048 + 2 * GUARD || h > 2048 + 2 * GUARD || (long) w * h > 3_500_000) throw new Rejected("NATIVE_RASTER_LIMIT");
                return reader.read(0);
            } finally { reader.dispose(); }
        }
    }
    private void png(String filename, BufferedImage image) throws Exception {
        Path path = output.resolve(filename); Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        if (!ImageIO.write(image, "png", path.toFile())) throw new Rejected("PNG_WRITE");
    }
    private static void privateText(Path path, String text) throws Exception {
        Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))); Files.writeString(path, text, StandardCharsets.UTF_8);
    }
    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> m) return m.entrySet().stream().map(e -> json(e.getKey().toString()) + ":" + json(e.getValue())).collect(java.util.stream.Collectors.joining(",", "{", "}"));
        if (value instanceof Iterable<?> values) { List<String> parts = new ArrayList<>(); values.forEach(v -> parts.add(json(v))); return "[" + String.join(",", parts) + "]"; }
        String text = value.toString(); StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 32 || (Character.isSurrogate(c) && !(Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))))) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            else { out.append(c); if (Character.isHighSurrogate(c)) out.append(text.charAt(++i)); }
        }
        return out.append('"').toString();
    }
}
