package com.boatarde.regatasimulator.spike;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/** Standalone experiment: free-height dimensions are NOT proof of complete glyph coverage. */
public final class MagickCaptionFitter {
    public static final int BATCH_SIZE = 8;
    public static final int MAX_PROBES = 128;
    public static final int MAX_COMMANDS = 17;
    // Conservative local admission: oversized explicit-caption sweeps are costly even in one process.
    // This scalar cap is experimental, not a wall-clock/RSS guarantee or a production contract.
    public static final long MAX_WORK = 16_000_000L;
    private static final Pattern DIMENSIONS = Pattern.compile("[1-9][0-9]* [1-9][0-9]*");

    public enum Type {
        INVALID_TEXT, STYLE_LIMIT, FIT_REJECTED, FIT_WORK_LIMIT, MALFORMED_OUTPUT, OUTPUT_MISMATCH, OWNED_PATH
    }

    public static final class Failure extends Exception {
        private final Type type;

        Failure(Type type) {
            super(type.name());
            this.type = type;
        }

        public Type type() {
            return type;
        }
    }

    /** The caller owns process limits, deadlines, counting and fatal process errors. */
    @FunctionalInterface
    public interface Command {
        String run(String... arguments) throws Exception;
    }

    /** base contains only trusted closed fixture style/font arguments, not client arguments. */
    public record Request(String text, int width, int height, int requested, boolean fixed,
                          List<String> base, Path textFile, Path output) {
        public Request {
            base = List.copyOf(base);
        }
    }

    public record Probe(int size, int width, int height) {
        public boolean fits(int targetWidth, int targetHeight) {
            return width == targetWidth && height <= targetHeight;
        }
    }

    /** workUnits includes every measured size AND the selected explicit rerender. Not a memory bound. */
    public record Result(int size, int width, int height, int probes, int fitCommands, long workUnits,
                         List<Probe> measurements) {
        public Result {
            measurements = List.copyOf(measurements);
        }

        public List<Probe> checkedLargerSizes() {
            return measurements.stream().filter(probe -> probe.size() > size).toList();
        }
    }

    private static final class Counters {
        int probes;
        int commands;
        long work;

        boolean canSpend(long cost) {
            return cost <= MAX_WORK - work;
        }

        void spend(long cost) throws Failure {
            if (!canSpend(cost)) throw new Failure(Type.FIT_WORK_LIMIT);
            work += cost;
        }
    }

    private MagickCaptionFitter() { }

    public static String validateText(String text) throws Failure {
        if (text == null || text.isBlank() || text.length() > 4096) throw new Failure(Type.INVALID_TEXT);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) throw new Failure(Type.INVALID_TEXT);
            } else if (Character.isLowSurrogate(c) || (Character.isISOControl(c) && c != '\n')) {
                throw new Failure(Type.INVALID_TEXT);
            }
        }
        return text; // Deliberately no normalization, escaping, shortening or substitution.
    }

    public static void validateAdmission(String text, int width, int height, int requested) throws Failure {
        validateText(text);
        if (width < 1 || height < 1 || width > 2048 || height > 2048 || (long) width * height > 2_000_000
            || requested < 1 || requested > 128) throw new Failure(Type.STYLE_LIMIT);
    }

    public static Result fit(Request request, Command command) throws Exception {
        validateAdmission(request.text(), request.width(), request.height(), request.requested());
        guardPaths(request);
        Counters counters = new Counters();
        List<Probe> measurements = new ArrayList<>();
        int next = request.requested();
        while (next >= 1) {
            List<Integer> sizes = new ArrayList<>();
            while (next >= 1 && sizes.size() < BATCH_SIZE) {
                long cost = cost(request.text(), next);
                if (!counters.canSpend(cost)) break;
                counters.spend(cost);
                sizes.add(next--);
                if (request.fixed()) break;
            }
            if (sizes.isEmpty()) throw new Failure(Type.FIT_WORK_LIMIT);
            if (counters.probes + sizes.size() > MAX_PROBES || counters.commands >= MAX_COMMANDS - 1)
                throw new Failure(Type.FIT_WORK_LIMIT);
            counters.probes += sizes.size();
            counters.commands++;
            String output = invoke(command, batchArguments(request, sizes));
            List<Probe> batch = parseBatch(output, sizes, request.width());
            measurements.addAll(batch);
            // Descending explicit integers: lower failures never imply anything about a higher size.
            for (Probe probe : batch) {
                if (!probe.fits(request.width(), request.height())) continue;
                counters.spend(cost(request.text(), probe.size()));
                counters.commands++;
                Probe rendered = parseDimensions(invoke(command, renderArguments(request, probe.size())), probe.size());
                if (rendered.width() != probe.width() || rendered.height() != probe.height()
                    || !rendered.fits(request.width(), request.height())) throw new Failure(Type.OUTPUT_MISMATCH);
                return new Result(probe.size(), probe.width(), probe.height(), counters.probes, counters.commands,
                    counters.work, measurements);
            }
            if (request.fixed()) break;
        }
        throw new Failure(Type.FIT_REJECTED);
    }

    private static long cost(String text, int size) {
        return (long) text.length() * size * size;
    }

    private static String invoke(Command command, List<String> arguments) throws Exception {
        try {
            return command.run(arguments.toArray(String[]::new));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        }
    }

    static List<String> batchArguments(Request request, List<Integer> sizes) {
        List<String> args = base(request);
        args.addAll(List.of("-size", "1x1", "xc:none"));
        for (int size : sizes) {
            args.addAll(List.of("(", "-pointsize", Integer.toString(size), "-size", request.width() + "x",
                "caption:@" + request.textFile(), "-format", size + " %w %h\n", "-write", "info:", "+delete", ")"));
        }
        args.add("null:");
        return args;
    }

    static List<String> renderArguments(Request request, int size) {
        List<String> args = base(request);
        args.addAll(List.of("-pointsize", Integer.toString(size), "-size", request.width() + "x",
            "caption:@" + request.textFile(), "-format", "%w %h", "-write", "info:", request.output().toString()));
        return args;
    }

    private static List<String> base(Request request) {
        List<String> args = new ArrayList<>(request.base());
        args.addAll(List.of("-define", "caption:split=true"));
        return args;
    }

    static List<Probe> parseBatch(String output, List<Integer> sizes, int width) throws Failure {
        if (output == null) throw new Failure(Type.MALFORMED_OUTPUT);
        String[] lines = output.split("\n", -1);
        if (lines.length != sizes.size() + 1 || !lines[lines.length - 1].isEmpty()) throw new Failure(Type.MALFORMED_OUTPUT);
        List<Probe> probes = new ArrayList<>();
        for (int i = 0; i < sizes.size(); i++) {
            String prefix = sizes.get(i) + " ";
            if (!lines[i].startsWith(prefix)) throw new Failure(Type.MALFORMED_OUTPUT);
            Probe probe = parseDimensions(lines[i].substring(prefix.length()), sizes.get(i));
            if (probe.width() != width) throw new Failure(Type.MALFORMED_OUTPUT);
            probes.add(probe);
        }
        return probes;
    }

    private static Probe parseDimensions(String output, int size) throws Failure {
        if (output == null || !DIMENSIONS.matcher(output).matches()) throw new Failure(Type.MALFORMED_OUTPUT);
        String[] parts = output.split(" ");
        try {
            return new Probe(size, Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        } catch (NumberFormatException invalid) {
            throw new Failure(Type.MALFORMED_OUTPUT);
        }
    }

    private static void guardPaths(Request request) throws Exception {
        Path text = request.textFile(), output = request.output();
        if (text == null || output == null || !text.isAbsolute() || !output.isAbsolute()
            || !text.equals(text.normalize()) || !output.equals(output.normalize()) || text.equals(output)
            || !text.getParent().equals(output.getParent())) throw new Failure(Type.OWNED_PATH);
        Path parent = text.getParent();
        if (!parent.toRealPath().equals(parent) || !Files.getPosixFilePermissions(parent)
            .equals(PosixFilePermissions.fromString("rwx------"))) throw new Failure(Type.OWNED_PATH);
        if (!privateFile(text) || Files.size(text) > 4L * 4096
            || !Arrays.equals(Files.readAllBytes(text), request.text().getBytes(StandardCharsets.UTF_8)))
            throw new Failure(Type.OWNED_PATH);
        if (!Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            Files.createFile(output, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } else if (!privateFile(output)) {
            throw new Failure(Type.OWNED_PATH);
        }
    }

    private static boolean privateFile(Path path) throws Exception {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.getPosixFilePermissions(path)
            .equals(PosixFilePermissions.fromString("rw-------"));
    }
}
