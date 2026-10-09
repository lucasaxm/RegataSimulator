package com.boatarde.regatasimulator.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Drains both pipes, retaining at most 64 KiB per pipe. Never logs subprocess output. */
public class ProcessRunner {

    private static final int OUTPUT_LIMIT = 64 * 1024;
    private final Duration timeout;

    public ProcessRunner(Duration timeout) {
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Process timeout must be positive");
        }
        this.timeout = timeout;
    }

    @FunctionalInterface
    public interface Starter {
        Process start(ProcessBuilder builder) throws IOException;
    }

    public String run(ProcessBuilder builder, Starter starter) throws IOException, InterruptedException {
        Process process = starter.start(builder);
        var readers = Executors.newVirtualThreadPerTaskExecutor();
        Future<String> stdout = readers.submit(() -> drain(process.getInputStream()));
        Future<String> stderr = readers.submit(() -> drain(process.getErrorStream()));
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IOException("Image process timed out");
            }
            String output = collect(stdout, deadline);
            collect(stderr, deadline);
            if (process.exitValue() != 0) {
                throw new IOException("Image process failed with exit code " + process.exitValue());
            }
            return output;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            terminate(process);
            close(process.getInputStream());
            close(process.getErrorStream());
            close(process.getOutputStream());
            stdout.cancel(true);
            stderr.cancel(true);
            readers.shutdownNow();
        }
    }

    private String collect(Future<String> future, long deadline) throws IOException, InterruptedException {
        try {
            return future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            throw new IOException("Image process output timed out", e);
        } catch (ExecutionException e) {
            throw new IOException("Could not read image process output", e.getCause());
        }
    }

    private String drain(InputStream stream) throws IOException {
        try (stream; var retained = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                retained.write(buffer, 0, Math.min(count, OUTPUT_LIMIT - retained.size()));
            }
            return retained.toString(StandardCharsets.UTF_8);
        }
    }

    private void terminate(Process process) {
        try {
            process.descendants().forEach(child -> {
                child.destroy();
                if (child.isAlive()) {
                    child.destroyForcibly();
                }
            });
        } catch (UnsupportedOperationException ignored) {
            // Some synthetic Process implementations have no ProcessHandle.
        }
        process.destroy();
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }

    private void close(java.io.Closeable stream) {
        try {
            if (stream != null) {
                stream.close();
            }
        } catch (IOException ignored) {
            // Preserve the original process outcome; termination has already been requested.
        }
    }
}