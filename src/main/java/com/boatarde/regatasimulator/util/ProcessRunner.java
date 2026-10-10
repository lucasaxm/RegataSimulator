package com.boatarde.regatasimulator.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
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
        FutureTask<String> stdout = new FutureTask<>(() -> drain(process.getInputStream()));
        FutureTask<String> stderr = new FutureTask<>(() -> drain(process.getErrorStream()));
        Thread stdoutReader = Thread.ofPlatform().daemon().name("image-stdout").start(stdout);
        Thread stderrReader = Thread.ofPlatform().daemon().name("image-stderr").start(stderr);
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
            awaitReaders(stdoutReader, stderrReader);
        }
    }

    private void awaitReaders(Thread... readers) {
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        try {
            for (Thread reader : readers) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                reader.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
            }
        } catch (InterruptedException e) {
            interrupted = true;
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
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
        boolean interrupted = Thread.interrupted();
        try {
            process.descendants().forEach(child -> {
                child.destroy();
                if (child.isAlive()) {
                    child.destroyForcibly();
                }
            });
            process.destroy();
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
            }
        } catch (UnsupportedOperationException ignored) {
            // Some synthetic Process implementations have no ProcessHandle.
            process.destroyForcibly();
        } catch (InterruptedException e) {
            interrupted = true;
            process.destroyForcibly();
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
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