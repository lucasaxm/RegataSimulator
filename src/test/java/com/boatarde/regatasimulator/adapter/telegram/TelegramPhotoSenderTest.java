package com.boatarde.regatasimulator.adapter.telegram;

import org.apache.http.conn.ConnectTimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.ResponseParameters;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.net.ssl.SSLException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TelegramPhotoSenderTest {
    private final SendPhoto photo = SendPhoto.builder().chatId(123L).photo(new InputFile("synthetic-file-id")).build();
    private final Message delivered = new Message();
    private final List<Long> delays = new ArrayList<>();
    private final TelegramPhotoSender sender = new TelegramPhotoSender(delays::add);

    static Stream<Throwable> connectionFailures() {
        var generic = new SocketException("private error detail");
        generic.setStackTrace(new StackTraceElement[]{new StackTraceElement("sun.nio.ch.Net", "connect0", "Net.java", -2)});
        return Stream.of(new ConnectException(), new NoRouteToHostException(), new UnknownHostException(),
            new ConnectTimeoutException(), generic);
    }

    @ParameterizedTest @MethodSource("connectionFailures")
    void retriesConnectionStageFailureUsingSameMethod(Throwable cause) throws TelegramApiException {
        var calls = new AtomicInteger();
        var failure = new TelegramApiException("private error detail", cause);
        assertSame(delivered, sender.send(photo, method -> {
            assertSame(photo, method);
            if (calls.incrementAndGet() < 3) throw failure;
            return delivered;
        }));
        assertEquals(3, calls.get());
        assertEquals(List.of(1000L, 2000L), delays);
    }

    @Test void exhaustionHasThreeCallsAndNoFinalWait() {
        var calls = new AtomicInteger();
        var failure = new TelegramApiException(new ConnectException());
        assertSame(failure, assertThrows(TelegramApiException.class, () -> sender.send(photo, method -> {
            calls.incrementAndGet(); throw failure;
        })));
        assertEquals(3, calls.get());
        assertEquals(List.of(1000L, 2000L), delays);
    }

    static Stream<Throwable> uncertainFailures() {
        return Stream.of(new SocketTimeoutException("Read timed out"), new SocketException("Connection reset"),
            new SocketException("Network is unreachable"), new SSLException("TLS failure"), new java.io.IOException());
    }

    @ParameterizedTest @MethodSource("uncertainFailures")
    void doesNotReplayUncertainOrUnclassifiedFailure(Throwable cause) {
        var calls = new AtomicInteger();
        var failure = new TelegramApiException(cause);
        assertSame(failure, assertThrows(TelegramApiException.class, () -> sender.send(photo, method -> {
            calls.incrementAndGet(); throw failure;
        })));
        assertEquals(1, calls.get()); assertTrue(delays.isEmpty());
    }

    @Test void honorsRateLimitDelayWithoutCappingItToAnEarlyRetry() throws TelegramApiException {
        var calls = new AtomicInteger();
        var rateLimit = response(429, 3);
        assertSame(delivered, sender.send(photo, method -> {
            if (calls.incrementAndGet() == 1) throw rateLimit;
            return delivered;
        }));
        assertEquals(List.of(3000L), delays);
    }

    @Test void rateLimitsCannotExceedTotalWaitBudget() {
        var calls = new AtomicInteger();
        var rateLimit = response(429, 6);
        assertSame(rateLimit, assertThrows(TelegramApiException.class, () -> sender.send(photo, method -> {
            calls.incrementAndGet(); throw rateLimit;
        })));
        assertEquals(2, calls.get()); assertEquals(List.of(6000L), delays);
    }

    @Test void permanentResponsesAndInvalidRateLimitDelaysNeverRetry() {
        for (var failure : List.of(response(400, 1), response(401, 1), response(403, 1), response(500, 1),
            response(429, null), response(429, -1), response(429, 0), response(429, 11), response(429, Integer.MAX_VALUE))) {
            var calls = new AtomicInteger();
            assertSame(failure, assertThrows(TelegramApiException.class, () -> sender.send(photo, method -> {
                calls.incrementAndGet(); throw failure;
            })));
            assertEquals(1, calls.get());
        }
        assertTrue(delays.isEmpty());
    }

    @Test void rateLimitWithoutParametersAndServerErrorWithConnectionCauseDoNotRetry() {
        var missing = response(429, 1);
        when(missing.getParameters()).thenReturn(null);
        var server = response(503, 1);
        when(server.getCause()).thenReturn(new ConnectException());
        for (var failure : List.of(missing, server)) {
            var calls = new AtomicInteger();
            assertSame(failure, assertThrows(TelegramApiException.class, () -> sender.send(photo, method -> {
                calls.incrementAndGet(); throw failure;
            })));
            assertEquals(1, calls.get());
        }
        assertTrue(delays.isEmpty());
    }

    @Test void uncertainOuterCauseOverridesNestedConnectionFailure() {
        var timeout = new SocketTimeoutException(); timeout.initCause(new ConnectException());
        var failure = new TelegramApiException(timeout);
        assertSame(failure, assertThrows(TelegramApiException.class, () -> sender.send(photo, method -> { throw failure; })));
        assertTrue(delays.isEmpty());
    }

    @Test void cyclicAndExcessivelyDeepCausesFailClosed() {
        var one = new java.io.IOException();
        var two = new java.io.IOException();
        one.initCause(two); two.initCause(one);
        Throwable deep = new ConnectException();
        for (int i = 0; i < 20; i++) deep = new java.io.IOException(deep);
        for (var failure : List.of(new TelegramApiException(one), new TelegramApiException(deep))) {
            var calls = new AtomicInteger();
            assertSame(failure, assertThrows(TelegramApiException.class, () -> sender.send(photo, method -> {
                calls.incrementAndGet(); throw failure;
            })));
            assertEquals(1, calls.get());
        }
        assertTrue(delays.isEmpty());
    }

    @Test void exactlyTenSecondsOfRateLimitWaitingIsAllowed() throws TelegramApiException {
        var calls = new AtomicInteger();
        var rateLimit = response(429, 5);
        assertSame(delivered, sender.send(photo, method -> {
            if (calls.incrementAndGet() < 3) throw rateLimit;
            return delivered;
        }));
        assertEquals(3, calls.get()); assertEquals(List.of(5000L, 5000L), delays);
    }

    @Test void interruptionSetDuringBackoffStopsBeforeNextAttempt() {
        var calls = new AtomicInteger();
        var interrupted = new TelegramPhotoSender(millis -> Thread.currentThread().interrupt());
        try {
            assertThrows(TelegramApiException.class, () -> interrupted.send(photo, method -> {
                calls.incrementAndGet(); throw new TelegramApiException(new ConnectException());
            }));
            assertEquals(1, calls.get()); assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test void interruptedBackoffPreservesFlagAndStopsDelivery() {
        var calls = new AtomicInteger();
        var interrupted = new TelegramPhotoSender(millis -> { throw new InterruptedException(); });
        try {
            assertThrows(TelegramApiException.class, () -> interrupted.send(photo, method -> {
                calls.incrementAndGet(); throw new TelegramApiException(new ConnectException());
            }));
            assertEquals(1, calls.get()); assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test void alreadyInterruptedThreadDoesNotSend() {
        var calls = new AtomicInteger();
        try {
            Thread.currentThread().interrupt();
            assertThrows(TelegramApiException.class, () -> sender.send(photo, method -> { calls.incrementAndGet(); return delivered; }));
            assertEquals(0, calls.get()); assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    static Stream<Throwable> interruptions() {
        return Stream.of(new InterruptedException(), new java.nio.channels.ClosedByInterruptException());
    }

    @ParameterizedTest @MethodSource("interruptions")
    void nestedInterruptionIsNotRetried(Throwable interruption) {
        try {
            var failure = new TelegramApiException(interruption);
            assertSame(failure, assertThrows(TelegramApiException.class, () -> sender.send(photo, method -> { throw failure; })));
            assertTrue(Thread.currentThread().isInterrupted()); assertTrue(delays.isEmpty());
        } finally { Thread.interrupted(); }
    }

    @Test void successAndRuntimeFailureDoNotWaitOrRetry() throws TelegramApiException {
        assertSame(delivered, sender.send(photo, method -> delivered));
        var failure = new IllegalStateException("fixture");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> sender.send(photo, method -> { throw failure; })));
        assertTrue(delays.isEmpty());
    }

    private TelegramApiRequestException response(int code, Integer seconds) {
        var failure = mock(TelegramApiRequestException.class);
        when(failure.getErrorCode()).thenReturn(code);
        when(failure.getParameters()).thenReturn(new ResponseParameters(null, seconds));
        return failure;
    }
}