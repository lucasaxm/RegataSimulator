package com.boatarde.regatasimulator.util;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class ProcessRunnerTest {

    private ProcessBuilder child(String mode) throws Exception {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", Path.of(Child.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
            Child.class.getName(), mode);
    }

    @Test
    void drainsLargeStdoutAndStderrWithoutDeadlockAndCapsRetainedOutput() throws Exception {
        String output = new ProcessRunner(Duration.ofSeconds(10)).run(child("flood"), ProcessBuilder::start);
        assertThat(output).hasSize(64 * 1024);
        assertNoReaderThreads();
    }

    @Test
    void nonzeroExitIsRejected() throws Exception {
        ProcessBuilder builder = child("fail");
        assertThatThrownBy(() -> new ProcessRunner(Duration.ofSeconds(10)).run(builder, ProcessBuilder::start))
            .isInstanceOf(IOException.class).hasMessageContaining("exit code 7");
    }

    @Test
    void timeoutTerminatesChild() throws Exception {
        AtomicReference<Process> process = new AtomicReference<>();
        ProcessBuilder builder = child("block");
        assertThatThrownBy(() -> new ProcessRunner(Duration.ofMillis(500)).run(builder, pb -> {
            Process started = pb.start();
            process.set(started);
            return started;
        })).isInstanceOf(IOException.class).hasMessageContaining("timed out");
        assertThat(process.get().waitFor(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(process.get().isAlive()).isFalse();
        assertNoReaderThreads();
    }

    @Test
    void interruptionTerminatesChildAndPreservesFlag() throws Exception {
        AtomicReference<Process> process = new AtomicReference<>();
        ProcessBuilder builder = child("block");
        try {
            assertThatThrownBy(() -> new ProcessRunner(Duration.ofSeconds(10)).run(builder, pb -> {
                process.set(pb.start());
                Thread.currentThread().interrupt();
                return process.get();
            })).isInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThat(process.get().waitFor(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertNoReaderThreads();
    }

    @Test
    void timeoutRequestsTerminationOfDescendantsBeforeParent() throws Exception {
        Process process = org.mockito.Mockito.mock(Process.class);
        ProcessHandle descendant = org.mockito.Mockito.mock(ProcessHandle.class);
        org.mockito.Mockito.when(process.getInputStream()).thenReturn(java.io.InputStream.nullInputStream());
        org.mockito.Mockito.when(process.getErrorStream()).thenReturn(java.io.InputStream.nullInputStream());
        org.mockito.Mockito.when(process.descendants()).thenReturn(java.util.stream.Stream.of(descendant));
        org.mockito.Mockito.when(descendant.isAlive()).thenReturn(true);
        assertThatThrownBy(() -> new ProcessRunner(Duration.ofMillis(100)).run(new ProcessBuilder("unused"), pb -> process))
            .isInstanceOf(IOException.class).hasMessageContaining("timed out");
        var order = org.mockito.Mockito.inOrder(descendant, process);
        order.verify(descendant).destroy();
        order.verify(descendant).destroyForcibly();
        order.verify(process).destroy();
        assertNoReaderThreads();
    }

    private void assertNoReaderThreads() {
        assertThat(Thread.getAllStackTraces().keySet()).noneMatch(thread -> thread.isAlive()
            && (thread.getName().equals("image-stdout") || thread.getName().equals("image-stderr")));
    }

    public static class Child {
        public static void main(String[] args) throws IOException {
            switch (args[0]) {
                case "flood" -> {
                    byte[] bytes = new byte[200_000];
                    java.util.Arrays.fill(bytes, (byte) 'x');
                    System.out.write(bytes);
                    System.err.write(bytes);
                }
                case "fail" -> System.exit(7);
                case "block" -> System.in.read();
                default -> throw new IllegalArgumentException();
            }
        }
    }
}