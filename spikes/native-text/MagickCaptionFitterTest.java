package com.boatarde.regatasimulator.spike;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntUnaryOperator;

/** Pure standalone tests: no JUnit, native executable, font or application environment. */
public final class MagickCaptionFitterTest {
    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }

    private static final class Fixture implements AutoCloseable {
        final Path directory;
        final MagickCaptionFitter.Request request;
        final List<List<String>> commands = new ArrayList<>();
        IntUnaryOperator height = size -> 30;
        String batchOverride;
        String renderOverride;
        Exception failure;

        Fixture(String text, int requested, boolean fixed) throws Exception {
            directory = Files.createTempDirectory("regata-fit-test-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))).toRealPath();
            Path input = directory.resolve("owned.txt");
            Files.createFile(input, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(input, text, StandardCharsets.UTF_8);
            request = new MagickCaptionFitter.Request(text, 100, 20, requested, fixed,
                List.of("-density", "72", "-font", "trusted-pinned-font", "-background", "none",
                    "-fill", "red", "-stroke", "none", "-gravity", "NorthWest"), input, directory.resolve("caption.png"));
        }

        String command(String... arguments) throws Exception {
            List<String> args = List.of(arguments);
            commands.add(args);
            if (failure != null) throw failure;
            List<Integer> sizes = new ArrayList<>();
            for (int i = 0; i < args.size(); i++) {
                if (args.get(i).equals("-pointsize")) sizes.add(Integer.parseInt(args.get(++i)));
            }
            if (args.getLast().equals("null:")) {
                if (batchOverride != null) return batchOverride;
                StringBuilder out = new StringBuilder();
                for (int size : sizes) out.append(size).append(" 100 ").append(height.applyAsInt(size)).append('\n');
                return out.toString();
            }
            if (renderOverride != null) return renderOverride;
            return "100 " + height.applyAsInt(sizes.getFirst());
        }

        MagickCaptionFitter.Result fit() throws Exception {
            return MagickCaptionFitter.fit(request, this::command);
        }

        @Override
        public void close() throws Exception {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private int checks;

    private MagickCaptionFitterTest() { }

    public static int run() throws Exception {
        MagickCaptionFitterTest suite = new MagickCaptionFitterTest();
        suite.selection();
        suite.commandShape();
        suite.bounds();
        suite.parsers();
        suite.workLimits();
        suite.propagation();
        suite.originalBytesAndPaths();
        return suite.checks;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("PASS pure MagickCaptionFitter checks=" + run());
    }

    private void selection() throws Exception {
        try (Fixture f = new Fixture("abc", 128, false)) {
            f.height = size -> size == 128 ? 20 : 100;
            MagickCaptionFitter.Result result = f.fit();
            check(result.size() == 128, "top fits despite failed lower sizes: not a monotone predicate");
            check(result.probes() == 8 && result.fitCommands() == 2 && f.commands.size() == 2, "one batch + rerender");
            check(result.measurements().get(1).height() == 100, "lower failure actually measured");
        }
        try (Fixture f = new Fixture("abc", 100, false)) {
            f.height = size -> size == 97 || size == 93 ? 20 : 21;
            MagickCaptionFitter.Result result = f.fit();
            check(result.size() == 97, "highest exact integer among disjoint fits");
            check(result.checkedLargerSizes().stream().map(MagickCaptionFitter.Probe::size).toList()
                .equals(List.of(100, 99, 98)), "all larger integers measured");
        }
        try (Fixture f = new Fixture("x", 128, false)) {
            f.height = size -> size == 1 ? 20 : 21;
            MagickCaptionFitter.Result result = f.fit();
            check(result.size() == 1 && result.probes() == 128, "positive floor after all 128 probes");
            check(result.fitCommands() == 17 && f.commands.size() == 17, "actual worst-case command bound");
            long expectedWork = 1;
            for (int size = 1; size <= 128; size++) expectedWork += (long) size * size;
            check(result.workUnits() == expectedWork, "work includes all probes and selected rerender");
        }
        try (Fixture f = new Fixture("abc", 16, false)) {
            expect(MagickCaptionFitter.Type.FIT_REJECTED, f::fit);
            check(f.commands.size() == 2, "impossible does not render or exceed floor");
        }
        try (Fixture f = new Fixture("abc", 48, true)) {
            f.height = size -> 20;
            MagickCaptionFitter.Result result = f.fit();
            check(result.size() == 48 && result.probes() == 1 && result.fitCommands() == 2, "fixed exact requested only");
        }
        try (Fixture f = new Fixture("abc", 28, true)) {
            f.height = size -> size == 28 ? 21 : 1;
            expect(MagickCaptionFitter.Type.FIT_REJECTED, f::fit);
            check(f.commands.size() == 1, "fixed cannot shrink to lower fitting integer");
        }
    }

    private void commandShape() throws Exception {
        try (Fixture f = new Fixture("abc", 9, false)) {
            f.height = size -> size == 1 ? 20 : 21;
            f.fit();
            List<String> first = f.commands.getFirst();
            check(first.subList(first.indexOf("-size"), first.indexOf("-size") + 3)
                .equals(List.of("-size", "1x1", "xc:none")), "nonempty dummy stack");
            check(first.stream().filter("("::equals).count() == 8 && first.stream().filter(")"::equals).count() == 8,
                "eight parenthesized probes");
            check(first.stream().filter("+delete"::equals).count() == 8, "no retained probe rasters");
            check(first.stream().filter("info:"::equals).count() == 8 && first.getLast().equals("null:"), "measurement sinks");
            check(f.commands.get(1).stream().filter("-pointsize"::equals).count() == 1, "last partial batch");
            for (List<String> command : f.commands) {
                check(command.contains("caption:split=true"), "split enabled for probe and final explicit render");
                check(command.stream().noneMatch(arg -> arg.contains("max-pointsize") || arg.contains("start-pointsize")), "no auto-fit");
                for (int i = 0; i < command.size(); i++) {
                    if (command.get(i).equals("-size")) check(List.of("1x1", "100x").contains(command.get(i + 1)), "free height only");
                }
            }
            List<String> render = f.commands.getLast();
            check(render.getLast().equals(f.request.output().toString()) && !render.contains("+delete"), "selected PNG explicit destination");
            check(render.subList(render.indexOf("-format"), render.indexOf("-format") + 4)
                .equals(List.of("-format", "%w %h", "-write", "info:")), "final native dimensions emitted");
        }
    }

    private void bounds() throws Exception {
        for (String text : Arrays.asList(null, "", " \n", "x".repeat(4097), "a\0b", "a\tb", "\uD800", "\uDC00", "\uD800a")) {
            expect(MagickCaptionFitter.Type.INVALID_TEXT, () -> MagickCaptionFitter.validateAdmission(text, 100, 20, 1));
        }
        for (int[] style : List.of(new int[] {0, 20, 1}, new int[] {100, -1, 1}, new int[] {2049, 1, 1},
            new int[] {1, 2049, 1}, new int[] {2048, 2048, 1}, new int[] {100, 20, 0}, new int[] {100, 20, 129})) {
            expect(MagickCaptionFitter.Type.STYLE_LIMIT,
                () -> MagickCaptionFitter.validateAdmission("abc", style[0], style[1], style[2]));
        }
        MagickCaptionFitter.validateAdmission("x".repeat(4096), 1000, 2000, 128);
        check(true, "inclusive admission boundaries");
        try (Fixture f = new Fixture("abc", 129, false)) {
            expect(MagickCaptionFitter.Type.STYLE_LIMIT, f::fit);
            check(f.commands.isEmpty(), "invalid admission invokes no command");
        }
    }

    private void parsers() throws Exception {
        List<Integer> sizes = List.of(3, 2);
        for (String response : Arrays.asList(null, "", "3 100 10\n", "3 100 10", "3 100 10\n2 100 10\n\n",
            "3 100 10\n3 100 10\n", "2 100 10\n3 100 10\n", "3 100 10\n2 100 10\nwarning\n",
            "3 100 10\n2 100 10\nwarning", "3 100 10\n2 100 10", "3 100 10\r\n2 100 10\r\n",
            "3 99 10\n2 100 10\n", "3 100 0\n2 100 10\n", "3 100 -1\n2 100 10\n",
            "3 100 2147483648\n2 100 10\n", "3 2147483648 10\n2 100 10\n", "3 100 1.5\n2 100 10\n",
            "3 100 01\n2 100 10\n", "3 100 10 extra\n2 100 10\n", "3  100 10\n2 100 10\n")) {
            expect(MagickCaptionFitter.Type.MALFORMED_OUTPUT,
                () -> MagickCaptionFitter.parseBatch(response, sizes, 100));
        }
        check(MagickCaptionFitter.parseBatch("3 100 2147483647\n2 100 1\n", sizes, 100).getFirst().height() == Integer.MAX_VALUE,
            "unfitting large native height measured without JVM raster decode");
        try (Fixture f = new Fixture("abc", 3, false)) {
            f.batchOverride = "3 100 10\n";
            expect(MagickCaptionFitter.Type.MALFORMED_OUTPUT, f::fit);
            check(f.commands.size() == 1, "broken batch never rerenders");
        }
        for (String response : List.of("99 10", "100 11", "100 21")) {
            try (Fixture f = new Fixture("abc", 3, false)) {
                f.height = size -> 10;
                f.renderOverride = response;
                expect(MagickCaptionFitter.Type.OUTPUT_MISMATCH, f::fit);
                check(f.commands.size() == 2, "rerender dimensions must exactly match selected probe");
            }
        }
        for (String response : Arrays.asList(null, "100 0", "100 10\n", "100 10 warning", "100 2147483648")) {
            if (response == null) continue; // null batch output covered above; fake null means normal rendering.
            try (Fixture f = new Fixture("abc", 1, false)) {
                f.height = size -> 10;
                f.renderOverride = response;
                expect(MagickCaptionFitter.Type.MALFORMED_OUTPUT, f::fit);
            }
        }
    }

    private void workLimits() throws Exception {
        try (Fixture f = new Fixture("x".repeat(4096), 128, false)) {
            f.height = size -> size < 127 ? 1 : 21;
            expect(MagickCaptionFitter.Type.FIT_WORK_LIMIT, f::fit);
            check(f.commands.isEmpty(), "unfundable initial probe rejected before any command, not a blind lower-size fallback");
        }
        try (Fixture f = new Fixture("x".repeat(4096), 50, true)) {
            f.height = size -> 1;
            expect(MagickCaptionFitter.Type.FIT_WORK_LIMIT, f::fit);
            check(f.commands.size() == 1, "final render charged before callback too");
        }
        try (Fixture f = new Fixture("x".repeat(4096), 30, false)) {
            expect(MagickCaptionFitter.Type.FIT_WORK_LIMIT, f::fit);
            check(f.commands.size() == 1 && f.commands.getFirst().stream().filter("-pointsize"::equals).count() == 4,
                "partially funded batch never exceeds work cap");
        }
        try (Fixture f = new Fixture("João da Conceição entrou na regata. ".repeat(8), 96, false)) {
            expect(MagickCaptionFitter.Type.FIT_WORK_LIMIT, f::fit);
            check(f.commands.size() == 1, "heavy 96px sweep rejected by scalar admission before a long deadline sweep");
        }
    }

    private void propagation() throws Exception {
        try (Fixture f = new Fixture("abc", 3, false)) {
            IOException capacity = new IOException("synthetic cache capacity failure");
            f.failure = capacity;
            try {
                f.fit();
                throw new AssertionError("Expected process exception");
            } catch (IOException actual) {
                check(actual == capacity && f.commands.size() == 1, "fatal process error preserved, not fit rejection");
            }
        }
        try (Fixture f = new Fixture("abc", 3, false)) {
            InterruptedException interruption = new InterruptedException("synthetic interruption");
            f.failure = interruption;
            try {
                f.fit();
                throw new AssertionError("Expected interruption");
            } catch (InterruptedException actual) {
                check(actual == interruption && Thread.currentThread().isInterrupted(), "exception identity and interruption preserved");
            } finally {
                Thread.interrupted();
            }
            check(f.commands.size() == 1, "no commands after interruption");
        }
    }

    private void originalBytesAndPaths() throws Exception {
        String nfd = "Ac\u0327a\u0303o Joa\u0303o e\u0301\n%[fx:1+1] \\n @owned";
        try (Fixture f = new Fixture(nfd, 28, true)) {
            byte[] original = Files.readAllBytes(f.request.textFile());
            f.height = size -> 20;
            f.fit();
            check(MagickCaptionFitter.validateText(nfd) == nfd, "no Unicode normalization or replacement");
            check(Arrays.equals(original, Files.readAllBytes(f.request.textFile())), "owned UTF-8 NFD bytes unchanged");
            check(f.commands.stream().allMatch(c -> c.contains("caption:@" + f.request.textFile())), "all captions read original owned file");
            Files.writeString(f.request.textFile(), "different");
            int before = f.commands.size();
            expect(MagickCaptionFitter.Type.OWNED_PATH, f::fit);
            check(f.commands.size() == before, "mismatched owned bytes rejected before callback");
        }
        try (Fixture f = new Fixture("abc", 3, false)) {
            Path linked = f.request.output();
            Files.createSymbolicLink(linked, f.request.textFile().getFileName());
            expect(MagickCaptionFitter.Type.OWNED_PATH, f::fit);
            check(f.commands.isEmpty(), "output symlink rejected");
        }
        try (Fixture f = new Fixture("abc", 3, false)) {
            MagickCaptionFitter.Request outside = new MagickCaptionFitter.Request("abc", 100, 20, 3, false,
                f.request.base(), f.request.textFile(), f.directory.getParent().resolve("not-owned.png"));
            expect(MagickCaptionFitter.Type.OWNED_PATH, () -> MagickCaptionFitter.fit(outside, f::command));
            check(f.commands.isEmpty(), "output outside owned directory rejected");
        }
    }

    private void expect(MagickCaptionFitter.Type type, Action action) throws Exception {
        try {
            action.run();
        } catch (MagickCaptionFitter.Failure failure) {
            check(failure.type() == type, "typed failure " + type + ", got " + failure.type());
            return;
        }
        throw new AssertionError("Expected " + type);
    }

    private void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
