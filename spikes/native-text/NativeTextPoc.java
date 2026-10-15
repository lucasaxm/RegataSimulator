package com.boatarde.regatasimulator.spike;

import com.boatarde.regatasimulator.util.ProcessRunner;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
import java.util.Properties;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Generated-fixture, opt-in experiment. Not a production renderer or template codec. */
public final class NativeTextPoc {
    private static final int MAX_TEXT = 4096;
    private static final long MAX_BOX_PIXELS = 2_000_000;
    private static final int INSET = 8;
    private static final Pattern GRAPHEME = Pattern.compile("\\X");
    private static final FontRenderContext FRC = new FontRenderContext(null, true, true);
    private static final List<String> LIMITS = List.of("-limit", "memory", "128MiB", "-limit", "map", "256MiB",
        "-limit", "disk", "512MiB", "-limit", "thread", "2", "-limit", "time", "25");
    private final Path executable;
    private final Path output;
    private final Map<String, Face> faces;
    private final List<String> checks = new ArrayList<>(List.of("case,status,detail"));
    private final List<String> timings = new ArrayList<>(List.of("case,engine,milliseconds,fontSize,processes,outputBytes"));
    private final List<String> commands = new ArrayList<>(List.of("sequence,milliseconds"));
    private int processCount;
    private int failures;
    private long jobDeadline = Long.MAX_VALUE;

    private record Face(Path path, Font font) { }
    private record Line(String text, TextLayout layout, float x, float baseline) { }
    private record Layout(int size, List<Line> lines, double height) { }
    private record Raster(Path path, int size) { }
    private record Layer(Path path, int x, int y, boolean background) { }
    private record TextArea(String pattern, Integer slot) { }

    private NativeTextPoc(Path executable, Path output, Map<String, Face> faces) {
        this.executable = executable;
        this.output = output;
        this.faces = faces;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || Runtime.version().feature() != 21) {
            throw new IllegalArgumentException("Use Java 21: self-test FONT_DIR | run MAGICK FONT_DIR NEW_OUTPUT smoke|boundary ROUNDS");
        }
        Map<String, Face> faces = loadFonts(Path.of(args[args[0].equals("self-test") ? 1 : 2]));
        selfTests(faces);
        if (args[0].equals("self-test") && args.length == 2) {
            System.out.println("PASS pure model/layout/font tests; no native processes");
            return;
        }
        if (!args[0].equals("run") || args.length != 6 || !List.of("smoke", "boundary").contains(args[4])) {
            throw new IllegalArgumentException("Invalid experiment arguments");
        }
        int rounds = Integer.parseInt(args[5]);
        if (rounds < 1 || rounds > 5) throw new IllegalArgumentException("Rounds must be 1..5");
        Path target = Path.of(args[3]).toAbsolutePath().normalize();
        if (!target.getParent().toRealPath().equals(target.getParent()) || Files.exists(target)) {
            throw new IllegalArgumentException("Output must be a new canonical directory");
        }
        Files.createDirectory(target, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        NativeTextPoc experiment = new NativeTextPoc(Path.of(args[1]).toRealPath(), target, faces);
        try {
            experiment.metadata(args[4], rounds);
            experiment.literalProbes();
            experiment.fontAndFitCases();
            for (int i = 0; i < rounds; i++) experiment.scenes(i);
            if (args[4].equals("boundary")) experiment.boundaryCases();
        } finally {
            Files.write(target.resolve("checks.csv"), experiment.checks, StandardCharsets.UTF_8);
            Files.write(target.resolve("measurements.csv"), experiment.timings, StandardCharsets.UTF_8);
            Files.write(target.resolve("commands.csv"), experiment.commands, StandardCharsets.UTF_8);
        }
        long rejected = experiment.checks.stream().filter(row -> row.contains("\"CAPACITY_REJECTED\"")).count();
        System.out.printf(Locale.ROOT, "Completed checks=%d failures=%d capacityRejected=%d nativeProcesses=%d output=%s%n",
            experiment.checks.size() - 1, experiment.failures, rejected, experiment.processCount, target);
        if (experiment.failures != 0) throw new AssertionError("Experiment has failed correctness gates; inspect checks.csv");
    }

    private static Map<String, Face> loadFonts(Path directory) throws Exception {
        Map<String, Face> result = new LinkedHashMap<>();
        for (String family : List.of("Sans", "Serif", "Mono")) {
            for (String weight : List.of("Regular", "Bold")) {
                Path path = directory.resolve("Liberation" + family + "-" + weight + ".ttf").toRealPath();
                if (Files.size(path) > 2_000_000) throw new IllegalArgumentException("Font exceeds experiment bound");
                Font font = Font.createFont(Font.TRUETYPE_FONT, path.toFile());
                require(font.canDisplayUpTo("João ação açúcar Órgão bênção ÇÃÕ ÁÉÍÓÚ àêü") == -1, "pt-BR glyphs");
                result.put(family + "-" + weight, new Face(path, font));
            }
        }
        return result;
    }

    private static String validateText(String text) {
        if (text == null || text.isBlank() || text.length() > MAX_TEXT) throw new IllegalArgumentException("Invalid text length");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) throw new IllegalArgumentException("Invalid surrogate");
            } else if (Character.isLowSurrogate(c) || (Character.isISOControl(c) && c != '\n')) {
                throw new IllegalArgumentException("Invalid text character");
            }
        }
        return text;
    }

    private static List<String> graphemes(String text) {
        return GRAPHEME.matcher(text).results().map(match -> match.group()).toList();
    }

    private static String resolve(TextArea area, Map<Integer, String> selected) {
        String pattern = validateText(area.pattern());
        boolean dynamic = pattern.contains("{{source.description}}");
        String stripped = pattern.replace("{{source.description}}", "");
        if (stripped.contains("{{") || stripped.contains("}}")) throw new IllegalArgumentException("Unknown token");
        if (!dynamic && area.slot() == null) return pattern;
        if (area.slot() == null || !selected.containsKey(area.slot())) throw new IllegalArgumentException("Missing slot");
        // One replacement pass; substituted braces/percent sequences remain content.
        return validateText(pattern.replace("{{source.description}}", validateText(selected.get(area.slot()))));
    }

    private static int slots(List<TextArea> areas) {
        TreeSet<Integer> required = new TreeSet<>();
        areas.forEach(area -> { if (area.slot() != null) required.add(area.slot()); });
        int index = 1;
        for (int slot : required) if (slot != index++) throw new IllegalArgumentException("Noncontiguous slots");
        return required.size();
    }

    private static Layout fit(String text, Face face, int width, int height, int requested, int outline) {
        validateText(text);
        if (width <= INSET * 2 || height <= INSET * 2 || width > 2048 || height > 2048
            || (long) width * height > MAX_BOX_PIXELS || requested < 1 || requested > 128 || outline < 0 || outline > 4) {
            throw new IllegalArgumentException("Box/style exceeds experiment limits");
        }
        if (face.font().canDisplayUpTo(text.replace("\n", "")) != -1) throw new IllegalArgumentException("Unsupported glyph");
        // Exhaustive bounded descending search does not assume hinting metrics are monotone.
        for (int size = requested; size >= 1; size--) {
            Layout layout = layout(text, face.font().deriveFont((float) size), width, size, outline);
            if (layout.height() <= height - INSET) return layout;
        }
        throw new IllegalArgumentException("Text cannot fit at positive font floor");
    }

    private static Layout layout(String text, Font font, int width, int size, int outline) {
        List<Line> lines = new ArrayList<>();
        float baseline = INSET + outline;
        double bottom = baseline;
        float lineHeight = (float) (font.getLineMetrics("Ag", FRC).getHeight() * 1.2);
        double available = width - INSET * 2.0 - outline * 2.0 - 2;
        for (String paragraph : text.split("\n", -1)) {
            if (paragraph.isEmpty()) {
                baseline += lineHeight;
                bottom = baseline;
                continue;
            }
            List<String> clusters = graphemes(paragraph);
            int offset = 0;
            while (offset < clusters.size()) {
                int end = offset;
                int lastSpace = -1;
                String candidate = "";
                while (end < clusters.size()) {
                    String next = candidate + clusters.get(end);
                    TextLayout measured = new TextLayout(next, font, FRC);
                    var bounds = measured.getPixelBounds(FRC, 0, 0);
                    double extent = Math.max(measured.getAdvance(), bounds.getMaxX()) - Math.min(0, bounds.x);
                    if (extent > available) break;
                    candidate = next;
                    if (clusters.get(end).equals(" ")) lastSpace = end + 1;
                    end++;
                }
                if (end == offset) return new Layout(size, List.of(), Double.POSITIVE_INFINITY);
                if (end < clusters.size() && lastSpace > offset) end = lastSpace;
                String line = String.join("", clusters.subList(offset, end));
                TextLayout measured = new TextLayout(line, font, FRC);
                var bounds = measured.getPixelBounds(FRC, 0, 0);
                baseline += measured.getAscent();
                float x = INSET + outline - Math.min(0, bounds.x);
                lines.add(new Line(line, measured, x, baseline));
                bottom = Math.max(bottom, baseline + Math.max(measured.getDescent(), bounds.getMaxY()) + outline + 1);
                baseline += lineHeight - measured.getAscent();
                offset = end;
            }
        }
        return new Layout(size, List.copyOf(lines), bottom);
    }

    private static BufferedImage javaRaster(String text, Face face, int width, int height, int requested, int outline, Color boxFill) {
        Layout plan = fit(text, face, width, height, requested, outline);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            if (boxFill != null) {
                graphics.setColor(boxFill);
                graphics.fillRect(0, 0, width, height);
            }
            for (Line line : plan.lines()) {
                var glyphs = line.layout().getOutline(AffineTransform.getTranslateInstance(line.x(), line.baseline()));
                if (outline > 0) {
                    graphics.setColor(Color.BLACK);
                    graphics.setStroke(new BasicStroke(outline * 2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    graphics.draw(glyphs);
                }
                graphics.setColor(Color.WHITE);
                graphics.fill(glyphs);
            }
        } finally { graphics.dispose(); }
        return image;
    }

    private static void selfTests(Map<String, Face> faces) {
        Face face = faces.get("Sans-Regular");
        require(graphemes("e\u0301👨‍👩‍👧‍👦🇧🇷").size() == 3, "grapheme boundaries");
        require(resolve(new TextArea("Olá {{source.description}}", 1), Map.of(1, "%[fx:1+1] {{x}}"))
            .equals("Olá %[fx:1+1] {{x}}"), "one-pass literal substitution");
        require(slots(List.of(new TextArea("{{source.description}}", 1), new TextArea("Oi {{source.description}}", 1))) == 1,
            "text-only shared slot");
        require(slots(List.of(new TextArea("Regata sem sources", null))) == 0, "literal K=0");
        require(resolve(new TextArea("Regata sem sources", null), Map.of()).equals("Regata sem sources"), "empty source list");
        expectInvalid(() -> resolve(new TextArea("{{unexpected}}", null), Map.of()));
        expectInvalid(() -> resolve(new TextArea("{{source.description}}", 1), Map.of()));
        expectInvalid(() -> slots(List.of(new TextArea("texto", 2))));
        expectInvalid(() -> validateText("\uD800"));
        expectInvalid(() -> validateText("a\0b"));
        expectInvalid(() -> validateText("x".repeat(MAX_TEXT + 1)));
        expectInvalid(() -> fit("texto", face, 10_000, 10_000, 32, 1));
        expectInvalid(() -> fit("texto", face, 1, 1, 32, 0));
        expectInvalid(() -> fit("emoji 😀", face, 300, 100, 32, 0));
        String longText = "João da Conceição entrou na regata. ".repeat(8);
        Layout fitted = fit(longText, face, 300, 90, 64, 1);
        require(fitted.size() < 64 && fitted.size() > 0, "automatic shrink");
        require(fitted.lines().stream().map(Line::text).reduce("", String::concat).equals(longText), "no truncation");
        for (Face selected : faces.values()) {
            BufferedImage raster = javaRaster("João\nAção e açúcar!", selected, 400, 130, 32, 2, null);
            require(inkInside(raster, 3), "glyph/outline margin");
            raster.flush();
        }
    }

    private static void expectInvalid(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected bounded validation failure");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private String nativeCommand(Path job, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of(executable.toString()));
        int offset = arguments.length > 0 && arguments[0].equals("identify") ? 1 : 0;
        if (offset == 1) command.add("identify");
        // -version is a standalone query, not a conversion with an output image.
        if (arguments.length != 1 || !arguments[0].equals("-version")) command.addAll(LIMITS);
        command.addAll(List.of(arguments).subList(offset, arguments.length));
        ProcessBuilder builder = new ProcessBuilder(command).directory(job.toFile());
        builder.environment().put("MAGICK_TEMPORARY_PATH", job.toString());
        long remaining = jobDeadline - System.nanoTime();
        if (remaining <= 0) throw new IOException("Whole-job experiment deadline exceeded");
        long start = System.nanoTime();
        processCount++;
        try {
            return new ProcessRunner(Duration.ofNanos(Math.min(Duration.ofSeconds(30).toNanos(), remaining)))
                .run(builder, ProcessBuilder::start);
        } finally {
            commands.add(processCount + "," + milliseconds(start));
        }
    }

    private void metadata(String profile, int rounds) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("java", Runtime.version().toString());
        properties.setProperty("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        properties.setProperty("magick", nativeCommand(output, "-version").lines().findFirst().orElse("unknown"));
        properties.setProperty("javaMaxHeapBytes", Long.toString(Runtime.getRuntime().maxMemory()));
        properties.setProperty("profile", profile);
        properties.setProperty("rounds", Integer.toString(rounds));
        properties.setProperty("limits", "memory128MiB,map256MiB,disk512MiB,threads2,time25s,process30s,job90s");
        properties.setProperty("scope", "isolated prototype; not Spring publication; no Linux hardening/cgroup enforcement proof");
        properties.setProperty("sourceSha256", "record harness/source identity externally with artifacts");
        for (var entry : faces.entrySet()) properties.setProperty("font." + entry.getKey(), digest(entry.getValue().path()));
        try (var stream = Files.newOutputStream(output.resolve("metadata.properties"))) { properties.store(stream, "Generated PoC identities"); }
    }

    private Path job(String name) throws IOException {
        jobDeadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        return Files.createTempDirectory(output, name + "-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    }

    private static void cleanup(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    private Path png(String name, BufferedImage image) throws IOException {
        Path path = output.resolve(name + ".png");
        if (!ImageIO.write(image, "png", path.toFile())) throw new IOException("PNG writer missing");
        image.flush();
        return path;
    }

    private Path rawMagickText(Path job, String name, String text, boolean file, boolean caption) throws Exception {
        Path input = job.resolve(name + ".txt");
        Files.writeString(input, text, StandardCharsets.UTF_8);
        String value = file ? "@" + input : text;
        Path result = job.resolve(name + ".png");
        Face face = faces.get("Sans-Regular");
        if (caption) {
            nativeCommand(job, "-density", "72", "-font", face.path().toString(), "-pointsize", "24", "-background", "none",
                "-fill", "white", "-size", "700x", "caption:" + value, result.toString());
        } else {
            nativeCommand(job, "-density", "72", "-font", face.path().toString(), "-pointsize", "24", "-background", "none",
                "-fill", "white", "-size", "700x120", "xc:none", "-gravity", "NorthWest", "-annotate", "+8+8", value, result.toString());
        }
        return result;
    }

    private void literalProbes() throws Exception {
        Path job = job("literal");
        try {
            Files.writeString(job.resolve("sentinel.txt"), "SENTINEL_MARKER", StandardCharsets.UTF_8);
            for (boolean caption : List.of(false, true)) {
                String engine = caption ? "caption-file" : "annotate-file";
                Path fx = rawMagickText(job, engine + "-fx", "%[fx:1+1]", true, caption);
                Path two = rawMagickText(job, engine + "-two", "2", false, caption);
                check(engine + "-percent-no-evaluation", !samePixels(fx, two), "literal expression differs from result");
                Path escaped = rawMagickText(job, engine + "-slash", "X\\nY", true, caption);
                Path newline = rawMagickText(job, engine + "-newline", "X\nY", true, caption);
                check(engine + "-backslash-no-expansion", !samePixels(escaped, newline), "literal backslash differs from newline");
                Path at = rawMagickText(job, engine + "-at", "@sentinel.txt", true, caption);
                Path sentinel = rawMagickText(job, engine + "-sentinel", "SENTINEL_MARKER", true, caption);
                check(engine + "-no-secondary-indirection", !samePixels(at, sentinel), "owned text is not a second filename");
                // Trusted escaped-argument oracle, not a production untrusted-text entry path.
                Path oracle = rawMagickText(job, engine + "-oracle", "%%[fx:1+1]", false, caption);
                check(engine + "-literal-pixel-oracle", samePixels(fx, oracle), "file matches escaped literal oracle");
                Files.copy(fx, output.resolve(engine + "-literal.png"));
            }
        } finally { cleanup(job); }
    }

    private Raster nativeCaption(Path job, Face face, String text, int width, int height, int requested, int outline, Color boxFill)
        throws Exception {
        // Validate with the same policy, but native caption metrics/wrapping determine its own fit.
        fit(text, face, width, height, requested, outline);
        Path textFile = job.resolve("caption.txt");
        Files.writeString(textFile, text, StandardCharsets.UTF_8);
        Path candidate = job.resolve("caption.png");
        String fitted = nativeCommand(job, "-density", "72", "-font", face.path().toString(),
            "-define", "caption:max-pointsize=" + requested, "-define", "caption:start-pointsize=" + requested,
            "-background", "none", "-fill", "white", "-stroke", "black", "-strokewidth", Integer.toString(outline),
            "-size", (width - INSET * 2) + "x" + (height - INSET * 2), "caption:@" + textFile,
            "-format", "%[caption:pointsize]", "info:").strip();
        double pointSize;
        try { pointSize = Double.parseDouble(fitted); }
        catch (NumberFormatException invalid) { throw new IOException("Invalid native fitted-size response"); }
        if (!Double.isFinite(pointSize) || pointSize < 1 || pointSize > requested) throw new IOException("Invalid native fitted size");
        int chosen = 0;
        // Verify free-height output at the native suggested size: fixed-height
        // creation alone would hide overflow. Any correction still has a finite bound.
        for (int size = (int) Math.floor(pointSize); size >= 1; size--) {
            nativeCommand(job, "-density", "72", "-font", face.path().toString(), "-pointsize", Integer.toString(size),
                "-background", "none", "-fill", "white", "-stroke", "black", "-strokewidth", Integer.toString(outline),
                "-size", (width - INSET * 2) + "x", "caption:@" + textFile, candidate.toString());
            int[] dimensions = dimensions(job, candidate);
            if (dimensions[0] <= width - INSET * 2 && dimensions[1] <= height - INSET * 2) { chosen = size; break; }
        }
        if (chosen == 0) throw new IllegalArgumentException("Native text cannot fit");
        Path result = job.resolve("native-box.png");
        nativeCommand(job, "-size", width + "x" + height, boxFill == null ? "xc:none" : "xc:" + rgb(boxFill),
            candidate.toString(), "-geometry", "+" + INSET + "+" + INSET, "-compose", "Over", "-composite", result.toString());
        return new Raster(result, chosen);
    }

    private void fontAndFitCases() throws Exception {
        for (var entry : faces.entrySet()) {
            textCase(entry.getKey(), "João\nAção, açúcar e bênção!", entry.getValue(), 480, 140, 32, 1, null);
        }
        textCase("long-shrink", "João da Conceição entrou na regata. ".repeat(8), faces.get("Sans-Bold"), 300, 100, 48, 1, null);
        textCase("oversized-word", "Anticonstitucionalissimamente".repeat(5), faces.get("Serif-Regular"), 260, 100, 48, 0, null);
        textCase("metacharacters", "<b>João</b> %[filename] {{unexpected}} \\n @sentinel", faces.get("Mono-Regular"), 600, 120, 24, 1, null);
        textCase("combining", "Ac\u0327a\u0303o e Joa\u0303o", faces.get("Sans-Regular"), 400, 100, 32, 1, null);
        textCase("box-fill-outline", "Texto sobre o template", faces.get("Sans-Bold"), 480, 120, 40, 2, new Color(55, 90, 125));
    }

    private void textCase(String name, String text, Face face, int width, int height, int requested, int outline, Color fill) throws Exception {
        Path job = job(name);
        try {
            long start = System.nanoTime();
            Layout layout = fit(text, face, width, height, requested, outline);
            Path javaPath = png("java2d-" + name, javaRaster(text, face, width, height, requested, outline, fill));
            timings.add(csv(name, "java2d", milliseconds(start), layout.size(), 0, Files.size(javaPath)));
            int before = processCount;
            start = System.nanoTime();
            Raster nativeRaster = nativeCaption(job, face, text, width, height, requested, outline, fill);
            Path nativePath = output.resolve("magick-" + name + ".png");
            Files.copy(nativeRaster.path(), nativePath);
            timings.add(csv(name, "magick-caption", milliseconds(start), nativeRaster.size(), processCount - before, Files.size(nativePath)));
            check(name + "-box-size", dimensions(job, nativePath)[0] == width && dimensions(job, nativePath)[1] == height, "bounded raster");
            check(name + "-java-no-truncation", layout.lines().stream().map(Line::text).reduce("", String::concat).equals(text.replace("\n", "")), "preserved glyph content");
            if (fill == null) {
                BufferedImage javaImage = ImageIO.read(javaPath.toFile());
                BufferedImage nativeImage = ImageIO.read(nativePath.toFile());
                check(name + "-java-margin", inkInside(javaImage, 3), "no ink at raster boundary");
                check(name + "-native-margin", inkInside(nativeImage, 3), "no ink at final box boundary; caption-internal clipping is separately limited evidence");
                javaImage.flush(); nativeImage.flush();
            }
        } finally { cleanup(job); }
    }

    private void scenes(int round) throws Exception {
        for (String engine : List.of("java2d", "magick")) {
            scene("image-only", engine, round, false, false);
            scene("mixed-shared-slot", engine, round, true, false);
            scene("text-only-shared-slot", engine, round, true, true);
            scene("literal-k0", engine, round, false, true);
        }
    }

    private void scene(String name, String engine, int round, boolean dynamic, boolean textOnly) throws Exception {
        Path job = job(name);
        long start = System.nanoTime();
        int before = processCount;
        try {
            Path template = job.resolve("template.png");
            BufferedImage bitmap = new BufferedImage(640, 480, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = bitmap.createGraphics();
            graphics.setColor(new Color(65, 70, 80)); graphics.fillRect(0, 0, 640, 480);
            graphics.setComposite(AlphaComposite.Clear); graphics.fillRect(20, 20, 280, 300); graphics.dispose();
            ImageIO.write(bitmap, "png", template.toFile()); bitmap.flush();
            Map<Integer, String> selected = dynamic || !textOnly ? Map.of(1, "João da Conceição") : Map.of();
            List<TextArea> texts = new ArrayList<>();
            if (dynamic) texts.addAll(List.of(new TextArea("{{source.description}}", 1), new TextArea("Olá {{source.description}}", 1)));
            else if (textOnly) texts.add(new TextArea("Regata sem sources: ação e açúcar", null));
            check(engine + "-" + name + "-slots-r" + round, slots(texts) == (dynamic ? 1 : 0), "text reference union; fixture image slot is separately selected");
            List<Layer> layers = new ArrayList<>();
            if (!textOnly) {
                Path image = job.resolve("source.png");
                BufferedImage source = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
                Graphics2D painter = source.createGraphics(); painter.setColor(new Color(25, 125, 190)); painter.fillRect(0, 0, 64, 64);
                painter.setColor(new Color(240, 130, 30)); painter.fillRect(32, 0, 32, 64); painter.dispose();
                ImageIO.write(source, "png", image.toFile()); source.flush();
                Path distorted = distort(job, image);
                layers.add(new Layer(distorted, 0, 0, true));
            }
            int position = 0;
            for (TextArea area : texts) {
                String text = resolve(area, selected);
                Path raster;
                if (engine.equals("java2d")) {
                    raster = job.resolve("text-" + position + ".png");
                    BufferedImage rendered = javaRaster(text, faces.get("Sans-Bold"), 300, 80, 32, 1, new Color(45, 100, 70));
                    ImageIO.write(rendered, "png", raster.toFile()); rendered.flush();
                } else {
                    Raster nativeRaster = nativeCaption(job, faces.get("Sans-Bold"), text, 300, 80, 32, 1, new Color(45, 100, 70));
                    raster = job.resolve("text-" + position + ".png");
                    Files.copy(nativeRaster.path(), raster);
                }
                // Exercise off-canvas placement and both planes without changing canonical coordinates.
                layers.add(new Layer(raster, position == 0 ? -12 : 320, position == 0 ? 340 : 360, position == 0 && dynamic));
                position++;
            }
            Path result = output.resolve(engine + "-" + name + "-r" + round + ".png");
            composite(job, template, layers, result, 640, 480);
            if (name.equals("image-only")) {
                // Exact decoded-pixel baseline using the existing image compositor's
                // full-canvas source -> template ordering, without an extra empty layer.
                Path baseline = job.resolve("legacy-image-baseline.png");
                nativeCommand(job, layers.getFirst().path().toString(), template.toString(), "-composite", baseline.toString());
                check(engine + "-image-only-native-baseline-r" + round, samePixels(result, baseline), "exact current-pipeline native pixels");
            }
            BufferedImage oracle = new BufferedImage(640, 480, BufferedImage.TYPE_INT_ARGB);
            Graphics2D painter = oracle.createGraphics();
            for (Layer layer : layers) if (layer.background()) draw(painter, layer);
            BufferedImage base = ImageIO.read(template.toFile()); painter.drawImage(base, 0, 0, null); base.flush();
            for (Layer layer : layers) if (!layer.background()) draw(painter, layer);
            painter.dispose();
            BufferedImage actual = ImageIO.read(result.toFile());
            check(engine + "-" + name + "-plane-order-r" + round, opaquePixelsEqual(actual, oracle),
                "exact opaque/transparent ordering and placement; fractional-alpha cross-engine rounding excluded");
            actual.flush(); oracle.flush();
            timings.add(csv(name + "-r" + round, engine, milliseconds(start), 0, processCount - before, Files.size(result)));
        } finally { cleanup(job); }
    }

    private Path distort(Path job, Path source) throws Exception {
        Path resized = job.resolve("resized.png"), warped = job.resolve("warped.png"), mask = job.resolve("mask.png"), result = job.resolve("distorted.png");
        nativeCommand(job, source.toString(), "-resize", "640x480!", resized.toString());
        nativeCommand(job, resized.toString(), "-alpha", "set", "-virtual-pixel", "transparent", "-distort", "Perspective",
            "0,0 -30,10 0,480 0,330 640,0 300,25 640,480 285,310", warped.toString());
        nativeCommand(job, "-size", "640x480", "xc:black", "-fill", "white", "-draw", "polygon -30,10 300,25 285,310 0,330", mask.toString());
        nativeCommand(job, warped.toString(), mask.toString(), "-alpha", "off", "-compose", "CopyOpacity", "-composite", result.toString());
        return result;
    }

    private void composite(Path job, Path template, List<Layer> layers, Path result, int width, int height) throws Exception {
        List<String> arguments = new ArrayList<>(List.of("-size", width + "x" + height, "xc:none"));
        for (Layer layer : layers) if (layer.background()) appendLayer(arguments, layer);
        appendLayer(arguments, new Layer(template, 0, 0, false));
        for (Layer layer : layers) if (!layer.background()) appendLayer(arguments, layer);
        arguments.add(result.toString());
        nativeCommand(job, arguments.toArray(String[]::new));
    }

    private static void appendLayer(List<String> args, Layer layer) {
        args.addAll(List.of(layer.path().toString(), "-geometry", String.format(Locale.ROOT, "%+d%+d", layer.x(), layer.y()),
            "-compose", "Over", "-composite"));
    }

    private static void draw(Graphics2D graphics, Layer layer) throws IOException {
        BufferedImage bitmap = ImageIO.read(layer.path().toFile());
        graphics.drawImage(bitmap, layer.x(), layer.y(), null);
        bitmap.flush();
    }

    private void boundaryCases() throws Exception {
        Path job = job("boundary");
        try {
            // Native-only large-canvas work; never decode a full 40MP image into JVM heap.
            long start = System.nanoTime();
            int before = processCount;
            Path base = job.resolve("40mp-base.png"), result = output.resolve("40mp-literal.png");
            String stage = "baseline-cache-before-text";
            try {
                nativeCommand(job, "-size", "8000x5000", "xc:#414650", base.toString());
                stage = "small-text-raster";
                Path text = job.resolve("40mp-text.png");
                BufferedImage raster = javaRaster("Texto pt-BR: ação e açúcar", faces.get("Sans-Bold"), 1000, 160, 80, 2, null);
                ImageIO.write(raster, "png", text.toFile()); raster.flush();
                stage = "native-composition";
                composite(job, base, List.of(new Layer(text, 100, 100, false)), result, 8000, 5000);
                int[] dimensions = dimensions(job, result);
                check("40mp-bounded-compose", dimensions[0] == 8000 && dimensions[1] == 5000, "native dimensions; not a full pixel regression");
                timings.add(csv("40mp-compose", "java2d+magick", milliseconds(start), 80, processCount - before, Files.size(result)));
            } catch (IOException rejected) {
                checks.add(csv("40mp-bounded-compose", "CAPACITY_REJECTED", stage + ": " + rejected.getMessage()));
            }
        } finally { cleanup(job); }
        job = job("area-count");
        try {
            for (String engine : List.of("java2d", "magick")) {
                long start = System.nanoTime();
                int before = processCount;
                Path base = job.resolve("many-template.png");
                nativeCommand(job, "-size", "640x480", "xc:#414650", base.toString());
                List<Layer> layers = new ArrayList<>();
                for (int i = 0; i < 128; i++) {
                    String text = "Área " + (i + 1) + ": João";
                    Path path = job.resolve(engine + "-area-" + i + ".png");
                    if (engine.equals("java2d")) {
                        BufferedImage raster = javaRaster(text, faces.get("Sans-Regular"), 150, 50, 14, 0, null);
                        ImageIO.write(raster, "png", path.toFile()); raster.flush();
                    } else {
                        Raster raster = nativeCaption(job, faces.get("Sans-Regular"), text, 150, 50, 14, 0, null);
                        Files.copy(raster.path(), path);
                    }
                    layers.add(new Layer(path, (i % 4) * 155, (i / 4) * 12, false));
                }
                Path result = output.resolve(engine + "-128-text-areas.png");
                composite(job, base, layers, result, 640, 480);
                check(engine + "-128-areas", dimensions(job, result)[0] == 640, "area-bound prototype, sequential box rasters");
                timings.add(csv("128-areas", engine, milliseconds(start), 14, processCount - before, Files.size(result)));
            }
        } finally { cleanup(job); }
    }

    private int[] dimensions(Path job, Path path) throws Exception {
        String[] dimensions = nativeCommand(job, "identify", "-format", "%w %h", path.toString()).strip().split("\\s+");
        return new int[] { Integer.parseInt(dimensions[0]), Integer.parseInt(dimensions[1]) };
    }

    private void check(String name, boolean passed, String detail) {
        checks.add(csv(name, passed ? "PASS" : "FAIL", detail));
        if (!passed) failures++;
    }

    private static boolean inkInside(BufferedImage image, int margin) {
        boolean ink = false;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) == 0) continue;
                ink = true;
                if (x < margin || y < margin || x >= image.getWidth() - margin || y >= image.getHeight() - margin) return false;
            }
        }
        return ink;
    }

    private static boolean samePixels(Path first, Path second) throws IOException {
        BufferedImage a = ImageIO.read(first.toFile()), b = ImageIO.read(second.toFile());
        try { return pixelsEqual(a, b); } finally { a.flush(); b.flush(); }
    }

    private static boolean pixelsEqual(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return false;
        for (int y = 0; y < a.getHeight(); y++) for (int x = 0; x < a.getWidth(); x++) {
            int one = a.getRGB(x, y), two = b.getRGB(x, y);
            // Hidden RGB of transparent pixels is not visible output.
            if ((one >>> 24) == 0 && (two >>> 24) == 0) continue;
            if (one != two) return false;
        }
        return true;
    }

    private static boolean opaquePixelsEqual(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return false;
        for (int y = 0; y < a.getHeight(); y++) for (int x = 0; x < a.getWidth(); x++) {
            int one = a.getRGB(x, y), two = b.getRGB(x, y);
            int alphaOne = one >>> 24, alphaTwo = two >>> 24;
            if ((alphaOne > 0 && alphaOne < 255) || (alphaTwo > 0 && alphaTwo < 255)) continue;
            if (alphaOne == 0 && alphaTwo == 0) continue;
            if (one != two) return false;
        }
        return true;
    }

    private static String digest(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private static String rgb(Color color) {
        return String.format(Locale.ROOT, "#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }

    private static String milliseconds(long start) {
        return String.format(Locale.ROOT, "%.3f", (System.nanoTime() - start) / 1_000_000.0);
    }

    private static String csv(Object... values) {
        return java.util.Arrays.stream(values).map(value -> "\"" + value.toString().replace("\"", "\"\"") + "\"")
            .collect(java.util.stream.Collectors.joining(","));
    }
}
